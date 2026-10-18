"""Per-repo fetch: partial shallow clone, check out only candidate files, keep content matches."""

from __future__ import annotations

import hashlib
import logging
import os
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path, PurePosixPath

from . import classify
from .state import State

log = logging.getLogger("harvester.fetch")

GIT_ENV = {
    **os.environ,
    "GIT_TERMINAL_PROMPT": "0",
    "GIT_LFS_SKIP_SMUDGE": "1",
    "GIT_ASKPASS": "true",
    "GCM_INTERACTIVE": "never",
}
GIT_CFG = ["-c", "protocol.file.allow=never", "-c", "core.symlinks=false", "-c", "credential.helper="]


class BudgetExceeded(Exception):
    pass


def _git(args: list[str], cwd: Path | None = None, timeout: int = 900, stdin: bytes | None = None) -> bytes:
    r = subprocess.run(["git", *GIT_CFG, *args], cwd=cwd, env=GIT_ENV, input=stdin,
                       capture_output=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"git {args[0]} failed: {r.stderr.decode(errors='replace').strip()[-300:]}")
    return r.stdout


def _safe_rel(path: str) -> PurePosixPath | None:
    p = PurePosixPath(path)
    if p.is_absolute() or ".." in p.parts or not p.parts:
        return None
    return p


def _sparse_pattern(path: str) -> str:
    """Anchored gitignore-style pattern that matches exactly one path."""
    escaped = "".join("\\" + ch if ch in "\\*?[" else ch for ch in path)
    if escaped.endswith(" "):
        escaped = escaped[:-1] + "\\ "
    return "/" + escaped


def du_bytes(root: Path) -> int:
    total = 0
    for dirpath, _, files in os.walk(root):
        for f in files:
            try:
                total += os.lstat(os.path.join(dirpath, f)).st_size
            except OSError:
                pass
    return total


def fetch_repo(rec: dict, external: Path, tmp_root: Path, max_file_bytes: int) -> dict:
    """Returns updates for the repo record. Never raises for repo-level problems."""
    dest = external / rec["host"] / rec["full_name"]
    with tempfile.TemporaryDirectory(dir=tmp_root) as tmp:
        work = Path(tmp) / "r"
        try:
            _git(["clone", "--quiet", "--depth", "1", "--filter=blob:none", "--no-checkout", "--sparse",
                  "--single-branch", rec["clone_url"], str(work)], timeout=600)
            sha = _git(["rev-parse", "HEAD"], cwd=work).decode().strip()
            # no `-l`: reading sizes would lazily fetch every blob in a blob:none clone
            listing = _git(["ls-tree", "-r", "-z", "--full-tree", "HEAD"], cwd=work)
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            return {"status": "failed", "error": str(e)[:300]}

        wanted = []
        for entry in listing.split(b"\0"):
            if not entry:
                continue
            meta, _, path_b = entry.partition(b"\t")
            mode, otype, _ = meta.split()
            path = path_b.decode("utf-8", errors="surrogateescape")
            if otype != b"blob" or mode == b"120000" or _safe_rel(path) is None:
                continue
            if classify.candidate_format(path) or classify.is_license_file(path):
                wanted.append(path)

        files, too_big = [], 0
        if wanted:
            try:
                # Sparse checkout of exact paths: unpack-trees prefetches all missing blobs in one
                # batch, whereas `checkout -- <paths>` lazily fetches them one round-trip at a time.
                patterns = "".join(_sparse_pattern(p) + "\n" for p in wanted)
                _git(["sparse-checkout", "set", "--no-cone", "--stdin"], cwd=work,
                     stdin=patterns.encode("utf-8", errors="surrogateescape"), timeout=1800)
                _git(["checkout", "--quiet", "HEAD"], cwd=work, timeout=1800)
            except (RuntimeError, subprocess.TimeoutExpired) as e:
                return {"status": "failed", "error": str(e)[:300], "sha": sha}

            license_files = []
            for path in wanted:
                src = work / path
                if not src.is_file():
                    continue
                if src.stat().st_size > max_file_bytes:
                    too_big += 1
                    continue
                if classify.is_license_file(path):
                    license_files.append(path)
                    continue
                data = src.read_bytes()
                m = classify.classify(path, data)
                if m is None:
                    continue
                target = dest / path
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(src), target)
                files.append({
                    "path": path,
                    "dialect": m.dialect,
                    "format": m.format,
                    "size": len(data),
                    "lines": classify.count_lines(classify.decode(data)),
                    "sha256": hashlib.sha256(data).hexdigest(),
                })
            if files:
                for path in license_files:
                    target = dest / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(str(work / path), target)
                rec_license_files = license_files
            else:
                rec_license_files = []
        else:
            rec_license_files = []

    return {
        "status": "done",
        "sha": sha,
        "files": files,
        "license_files": rec_license_files,
        "skipped_too_big": too_big,
        "error": None,
    }


def _priority(rec: dict) -> int:
    """High-signal discoveries first: code-search hits, then topic matches, then keyword matches."""
    by = rec["discovered_by"]
    if any(q.startswith("gh-code:") for q in by):
        return 0
    if any(":topic" in q or "topic:" in q for q in by):
        return 1
    return 2


def run(state: State, external: Path, workers: int, max_file_mb: int, budget_mb: int, min_free_mb: int,
        limit: int | None = None) -> None:
    tmp_root = external / ".tmp"
    shutil.rmtree(tmp_root, ignore_errors=True)
    tmp_root.mkdir(parents=True, exist_ok=True)
    pending = sorted(
        (k for k, r in state.repos.items() if r["status"] in ("pending", "failed") and not r.get("fork")
         and r.get("attempts", 0) < 3),
        key=lambda k: (_priority(state.repos[k]), state.repos[k]["order"]),
    )
    if limit:
        pending = pending[:limit]
    used = du_bytes(external)
    log.info("fetching %d repos with %d workers (corpus currently %.1f MB)", len(pending), workers, used / 1e6)

    def check_budget():
        free = shutil.disk_usage(external).free
        if free < min_free_mb * 1024 * 1024:
            raise BudgetExceeded(f"free disk below {min_free_mb} MB")
        if used > budget_mb * 1024 * 1024:
            raise BudgetExceeded(f"corpus budget of {budget_mb} MB reached")

    done = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        it = iter(pending)
        futures = {}

        def submit_next():
            key = next(it, None)
            if key is not None:
                futures[pool.submit(fetch_repo, dict(state.repos[key]), external, tmp_root,
                                    max_file_mb * 1024 * 1024)] = key

        for _ in range(workers * 2):
            submit_next()
        stop = None
        try:
            while futures:
                fut = next(as_completed(futures))
                key = futures.pop(fut)
                try:
                    upd = fut.result()
                except Exception as e:  # defensive: one repo must not stop the harvest
                    upd = {"status": "failed", "error": f"{type(e).__name__}: {e}"[:300]}
                with state.lock:
                    rec = state.repos[key]
                    rec["attempts"] = rec.get("attempts", 0) + 1
                    rec.update(upd)
                    used += sum(f["size"] for f in upd.get("files", []))
                done += 1
                nfiles = len(upd.get("files", []))
                if nfiles or upd["status"] == "failed":
                    log.info("[%d/%d] %s %s: %d files %s", done, len(pending), rec["host"], rec["full_name"],
                             nfiles, upd.get("error") or "")
                if done % 10 == 0:
                    state.save()
                if stop is None:
                    try:
                        check_budget()
                        submit_next()
                    except BudgetExceeded as e:
                        stop = str(e)
                        log.warning("stopping fetch after in-flight repos finish: %s", e)
        finally:
            state.data["budget_stop"] = stop
            state.save()
    shutil.rmtree(tmp_root, ignore_errors=True)
