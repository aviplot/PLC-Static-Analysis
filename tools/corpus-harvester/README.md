# corpus-harvester

Standalone tool (P0, not shipped) that collects public PLC source code into `corpus/external/`
and writes `corpus/MANIFEST.csv`, `corpus/STATS.md`, `corpus/SOURCES.md`. Python 3.10+, stdlib only.

```sh
cd tools/corpus-harvester
export GITHUB_TOKEN=...            # required for GitHub search (e.g. $(gh auth token))
export GITLAB_TOKEN=...            # optional
python3 -m harvester all           # discover + fetch + build
python3 -m harvester discover [--skip-code-search]
python3 -m harvester fetch [--workers 8] [--budget-mb 2500] [--max-file-mb 100] [--min-free-mb 1000] [--limit N]
python3 -m harvester build         # dedup + reports only
python3 -m unittest discover -s tests
```

All stages resume from `corpus/external/.harvest-state.json`; interrupt and re-run freely.
Failed repos are retried up to 3 times on later `fetch` runs.

## How it works
- **discover**: GitHub repo search (topics, keywords), GitHub code search per file type (each query
  split by `size:` ranges to get past the 1000-result cap), GitLab public project search. Backs off
  on 403/429 using `Retry-After` / rate-limit reset headers.
- **fetch**: `git clone --depth 1 --filter=blob:none --no-checkout --sparse`, list the tree, sparse-
  checkout only candidate extensions + root LICENSE/COPYING files (one batched blob fetch), classify
  by content (`harvester/classify.py`), keep matches, delete the clone. Order: code-search hits, then
  topic matches, then keyword matches. Never executes harvested content.
- **build**: SHA-256 dedup (first repo in discovery order wins, duplicates deleted from disk), then
  reports. License policy (`harvester/licenses.py`) is applied per file in the `policy` column.

## License policy
`fixture-ok` (MIT, BSD, Apache-2.0, Unlicense, CC0 variants) may become small committed fixtures,
listed in `corpus/SOURCES.md`. Everything else, including `NOASSERTION`, is `local-only`.
The whole `corpus/` directory (harvested files and the three reports) is git-ignored; never commit it.
