"""Candidate-repository discovery via the official GitHub and GitLab search APIs."""

from __future__ import annotations

import logging

from . import licenses
from .httpclient import Client, HttpError
from .state import State

log = logging.getLogger("harvester.discover")

GITHUB_API = "https://api.github.com"
GITLAB_API = "https://gitlab.com/api/v4"

# Code search finds repos by the files they contain. Bare extensions are used where the
# extension is PLC-specific; ambiguous ones get a content keyword.
CODE_QUERIES = [
    "extension:TcPOU",
    "extension:TcDUT",
    "extension:TcGVL",
    "extension:TcIO",
    "extension:plcproj",
    "extension:tsproj",
    "extension:scl",
    "extension:db DATA_BLOCK",
    "extension:udt END_TYPE",
    "extension:L5X",
    "extension:L5K",
    "extension:st END_VAR",
    "extension:st FUNCTION_BLOCK",
    "extension:xml tc6_0201",
    "extension:xml plcopen",
]

TOPICS = ["twincat", "codesys", "plc", "structured-text", "iec-61131-3", "tia-portal", "studio5000",
          "plcopen", "iec61131-3", "beckhoff", "oscat", "siemens-plc", "rslogix", "plc-programming"]
KEYWORDS = ["twincat", "codesys", "\"structured text\" plc", "\"iec 61131\"", "\"tia portal\"",
            "\"studio 5000\"", "plcopen", "oscat", "scl siemens", "l5x"]

# Repos added by name: they contain PLC code but no search above finds them.
SEED_REPOS = [
    "Open-Industry-Project/Open-Industry-Project",  # browser simulator; example ST programs
]

SEARCH_CAP = 1000          # GitHub returns at most 1000 results per query
CODE_SEARCH_INTERVAL = 6.5  # 10 requests/min for authenticated code search
REPO_SEARCH_INTERVAL = 2.1  # 30 requests/min
MAX_FILE_SIZE = 384 * 1024  # code search only indexes files below ~384 KB


def github_client(token: str) -> Client:
    return Client(GITHUB_API, {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })


def _github_repo_record(repo: dict) -> dict:
    return {
        "host": "github",
        "full_name": repo["full_name"],
        "html_url": repo["html_url"],
        "clone_url": f"https://github.com/{repo['full_name']}.git",
        "size_kb": repo.get("size"),
        "fork": repo.get("fork", False),
        **({"license": licenses.from_github(repo.get("license"))} if "license" in repo else {}),
    }


def _paged(client: Client, path: str, q: str, interval: float, state: State, qkey: str, on_item) -> int:
    """Walk all pages of a search; returns total_count. Resumes from the checkpoint."""
    qs = state.queries.setdefault(qkey, {"done": False, "pages": 0, "total": None, "truncated": False})
    if qs["done"]:
        return qs["total"] or 0
    page = qs["pages"] + 1
    total = qs["total"]
    while True:
        try:
            data, _ = client.get(path, {"q": q, "per_page": 100, "page": page}, interval=interval)
        except HttpError as e:
            if e.status == 422:  # beyond the 1000-result window or invalid query
                break
            raise
        total = data.get("total_count", 0)
        items = data.get("items", [])
        for item in items:
            on_item(item)
        qs.update(pages=page, total=total)
        state.save()
        if len(items) < 100 or page * 100 >= min(total, SEARCH_CAP):
            break
        page += 1
    qs["done"] = True
    qs["truncated"] = (total or 0) > SEARCH_CAP
    state.save()
    return total or 0


def github_code_search(client: Client, state: State, query: str) -> None:
    """Code search, split by file-size ranges so each slice stays under the 1000-result cap."""
    stack = [(0, MAX_FILE_SIZE)]
    while stack:
        lo, hi = stack.pop()
        q = f"{query} size:{lo}..{hi}"
        qkey = f"gh-code:{q}"
        qs = state.queries.get(qkey)
        if qs and qs.get("split"):
            mid = (lo + hi) // 2
            stack += [(mid + 1, hi), (lo, mid)]
            continue
        if not (qs and qs["done"]):
            # probe the first page to decide whether to split
            data, _ = client.get("/search/code", {"q": q, "per_page": 1}, interval=CODE_SEARCH_INTERVAL)
            total = data.get("total_count", 0)
            if total > SEARCH_CAP and hi - lo > 16:
                state.queries[qkey] = {"done": True, "split": True, "total": total, "pages": 0, "truncated": False}
                state.save()
                mid = (lo + hi) // 2
                stack += [(mid + 1, hi), (lo, mid)]
                continue
        new = 0

        def add(item):
            nonlocal new
            repo = item["repository"]
            if state.add_repo("github:" + repo["full_name"], _github_repo_record(repo), qkey):
                new += 1

        total = _paged(client, "/search/code", q, CODE_SEARCH_INTERVAL, state, qkey, add)
        log.info("%-45s total=%-6s new repos=%d (known: %d)", q, total, new, len(state.repos))


def github_repo_search(client: Client, state: State, q: str) -> None:
    qkey = f"gh-repo:{q}"
    new = 0

    def add(repo):
        nonlocal new
        if state.add_repo("github:" + repo["full_name"], _github_repo_record(repo), qkey):
            new += 1

    total = _paged(client, "/search/repositories", q, REPO_SEARCH_INTERVAL, state, qkey, add)
    log.info("%-45s total=%-6s new repos=%d (known: %d)", q, total, new, len(state.repos))


def github_seeds(client: Client, state: State) -> None:
    for full_name in SEED_REPOS:
        key = "github:" + full_name
        if key in state.repos and "gh-seed" in state.repos[key]["discovered_by"]:
            continue
        data, _ = client.get(f"/repos/{full_name}", interval=0.75)
        rec = _github_repo_record(data)
        rec["license"] = licenses.from_github(data.get("license"))
        state.add_repo(key, rec, "gh-seed")
        log.info("seed %s (%s)", full_name, rec["license"])
    state.save()


def github_enrich(client: Client, state: State) -> None:
    """Fill license/size for repos found via code search (whose payload lacks them)."""
    todo = [k for k, r in state.repos.items() if r["host"] == "github" and "license" not in r]
    log.info("fetching repo metadata for %d GitHub repos", len(todo))
    for i, key in enumerate(todo, 1):
        rec = state.repos[key]
        try:
            data, _ = client.get(f"/repos/{rec['full_name']}", interval=0.75)
            rec.update(_github_repo_record(data))
            rec["license"] = licenses.from_github(data.get("license"))
        except HttpError as e:
            if e.status in (404, 451, 403):
                rec["license"] = licenses.NOASSERTION
                rec["status"] = "skipped"
                rec["error"] = f"metadata HTTP {e.status}"
            else:
                raise
        if i % 50 == 0:
            state.save()
            log.info("  metadata %d/%d", i, len(todo))
    state.save()


def gitlab_client(token: str | None) -> Client:
    return Client(GITLAB_API, {"PRIVATE-TOKEN": token} if token else {}, min_interval=0.7)


def gitlab_search(client: Client, state: State) -> None:
    queries = [("topic", t) for t in TOPICS] + [("search", k.strip('"')) for k in KEYWORDS]
    for kind, value in queries:
        qkey = f"gl:{kind}={value}"
        qs = state.queries.setdefault(qkey, {"done": False, "pages": 0, "total": None, "truncated": False})
        if qs["done"]:
            continue
        page, new = qs["pages"] + 1, 0
        while page <= 50:
            params = {kind: value, "visibility": "public", "per_page": 100, "page": page,
                      "license": "true", "order_by": "id"}
            data, _ = client.get("/projects", params)
            for p in data:
                rec = {
                    "host": "gitlab",
                    "full_name": p["path_with_namespace"],
                    "html_url": p["web_url"],
                    "clone_url": p["http_url_to_repo"],
                    "fork": "forked_from_project" in p,
                    "license": licenses.from_gitlab(p.get("license")),
                }
                if not rec["fork"] and state.add_repo("gitlab:" + rec["full_name"], rec, qkey):
                    new += 1
            qs["pages"] = page
            state.save()
            if len(data) < 100:
                break
            page += 1
        qs["done"] = True
        qs["truncated"] = page > 50
        state.save()
        log.info("%-45s new repos=%d (known: %d)", qkey, new, len(state.repos))


def run(state: State, github_token: str | None, gitlab_token: str | None, skip_code_search: bool = False) -> None:
    if github_token:
        gh = github_client(github_token)
        github_seeds(gh, state)
        for t in TOPICS:
            github_repo_search(gh, state, f"topic:{t}")
        for k in KEYWORDS:
            github_repo_search(gh, state, f"{k} in:name,description,readme")
        if not skip_code_search:
            for q in CODE_QUERIES:
                github_code_search(gh, state, q)
        github_enrich(gh, state)
    else:
        log.warning("GITHUB_TOKEN not set: skipping GitHub (search API requires auth)")
    try:
        gitlab_search(gitlab_client(gitlab_token), state)
    except HttpError as e:
        log.warning("GitLab search failed: %s", e)
