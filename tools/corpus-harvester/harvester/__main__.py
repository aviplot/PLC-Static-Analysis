"""plca corpus harvester.

    python3 -m harvester [--corpus DIR] {discover,fetch,build,all} [options]

Every stage is resumable from corpus/external/.harvest-state.json.
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
import threading
from pathlib import Path

from . import discover, fetch, report
from .state import State


def _discover_while_fetching(run_discover, fetch_args: tuple, limit: int | None) -> None:
    """Search is mostly idle waiting on rate limits, so fetch repos as they are discovered.

    Discover runs in a thread; fetch passes repeat on whatever is pending. Failed repos are retried
    only in the final pass, after discovery has finished, so quick passes do not burn their attempts.
    """
    log = logging.getLogger("harvester")
    finished = threading.Event()
    error: list[BaseException] = []

    def target():
        try:
            run_discover()
        except BaseException as e:  # re-raised after fetching what was found
            error.append(e)
        finally:
            finished.set()

    threading.Thread(target=target, name="discover", daemon=True).start()
    remaining = limit
    while True:
        last = finished.is_set()
        n, stop = fetch.run(*fetch_args, remaining, retry_failed=last)
        if remaining is not None:
            remaining -= n
        if stop or last or (remaining is not None and remaining <= 0):
            break
        if n == 0:
            finished.wait(120)
    if error:
        log.error("discovery failed; fetched what was found so far")
        raise error[0]


def main(argv: list[str] | None = None) -> int:
    default_corpus = Path(__file__).resolve().parents[3] / "corpus"
    ap = argparse.ArgumentParser(prog="harvester", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", type=Path, default=default_corpus, help="corpus directory (default: %(default)s)")
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("discover", help="search GitHub/GitLab for candidate repos")
    d.add_argument("--skip-code-search", action="store_true", help="repo/topic search only (much faster)")
    d.add_argument("--max-repos", type=int, help="stop code search once this many candidate repos are known")
    f = sub.add_parser("fetch", help="partial-clone candidates and keep matching files")
    sub.add_parser("build", help="dedup and write MANIFEST.csv, STATS.md, SOURCES.md")
    a = sub.add_parser("all", help="discover + fetch + build")
    a.add_argument("--skip-code-search", action="store_true")
    a.add_argument("--max-repos", type=int, help="stop code search once this many candidate repos are known")
    for p in (f, a):
        p.add_argument("--workers", type=int, default=6)
        p.add_argument("--max-file-mb", type=int, default=100, help="skip single files larger than this")
        p.add_argument("--budget-mb", type=int, default=2500, help="stop fetching when the corpus reaches this")
        p.add_argument("--min-free-mb", type=int, default=1000, help="stop fetching below this much free disk")
        p.add_argument("--limit", type=int, help="fetch at most N repos this run")
        p.add_argument("--refetch-dialect", help="fetch again every done repo that has files of this dialect "
                       "(after adding a file type), e.g. TwinCat")
    args = ap.parse_args(argv)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s", datefmt="%H:%M:%S")
    corpus: Path = args.corpus
    external = corpus / "external"
    external.mkdir(parents=True, exist_ok=True)
    state = State(external / ".harvest-state.json")

    def run_discover():
        discover.run(state, os.environ.get("GITHUB_TOKEN"), os.environ.get("GITLAB_TOKEN"),
                     skip_code_search=args.skip_code_search, max_repos=args.max_repos)

    if args.cmd == "discover":
        run_discover()
    if args.cmd in ("fetch", "all") and args.refetch_dialect:
        n = fetch.mark_for_refetch(state, args.refetch_dialect)
        logging.getLogger("harvester").info("marked %d repos with %s files for refetch", n, args.refetch_dialect)
    fetch_args = (state, external, args.workers, args.max_file_mb, args.budget_mb, args.min_free_mb)
    if args.cmd == "fetch":
        fetch.run(*fetch_args, args.limit)
    if args.cmd == "all":
        _discover_while_fetching(run_discover, fetch_args, args.limit)
    if args.cmd in ("build", "all"):
        report.run(state, external, corpus)
    return 0


if __name__ == "__main__":
    sys.exit(main())
