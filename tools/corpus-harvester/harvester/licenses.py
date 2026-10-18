"""License policy from CLAUDE.md, enforced here rather than by hand."""

NOASSERTION = "NOASSERTION"
FIXTURE_OK = "fixture-ok"
LOCAL_ONLY = "local-only"

PERMISSIVE = frozenset({
    "MIT", "MIT-0",
    "BSD-2-Clause", "BSD-3-Clause", "BSD-3-Clause-Clear", "0BSD",
    "Apache-2.0",
    "Unlicense",
    "CC0-1.0",
})

# GitLab reports licensee keys (lower-case); map the common ones to SPDX ids.
_GITLAB_KEYS = {
    "mit": "MIT", "mit-0": "MIT-0",
    "bsd-2-clause": "BSD-2-Clause", "bsd-3-clause": "BSD-3-Clause", "bsd-3-clause-clear": "BSD-3-Clause-Clear",
    "0bsd": "0BSD", "apache-2.0": "Apache-2.0", "unlicense": "Unlicense", "cc0-1.0": "CC0-1.0",
    "gpl-2.0": "GPL-2.0", "gpl-3.0": "GPL-3.0", "lgpl-2.1": "LGPL-2.1", "lgpl-3.0": "LGPL-3.0",
    "agpl-3.0": "AGPL-3.0", "mpl-2.0": "MPL-2.0", "epl-2.0": "EPL-2.0", "isc": "ISC",
    "cc-by-4.0": "CC-BY-4.0", "cc-by-sa-4.0": "CC-BY-SA-4.0", "bsl-1.0": "BSL-1.0", "wtfpl": "WTFPL",
}


def policy(spdx: str | None) -> str:
    return FIXTURE_OK if spdx in PERMISSIVE else LOCAL_ONLY


def from_github(license_obj: dict | None) -> str:
    spdx = (license_obj or {}).get("spdx_id")
    return spdx or NOASSERTION


def from_gitlab(license_obj: dict | None) -> str:
    key = ((license_obj or {}).get("key") or "").lower()
    return _GITLAB_KEYS.get(key, NOASSERTION)
