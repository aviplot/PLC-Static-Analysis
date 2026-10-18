"""Content-based classification of candidate files.

Extensions only nominate a file; the content decides. `.st` is also Smalltalk and StringTemplate,
`.db` is usually SQLite, `.xml` is anything — so every format needs positive markers.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

CODESYS = "Codesys"
TWINCAT = "TwinCat"
SIEMENS = "Siemens"
ROCKWELL = "Rockwell"
PLCOPEN = "PlcOpen"

# extension (lower case, with dot) -> nominal format label
CANDIDATE_EXTENSIONS = {
    ".tcpou": "TcPOU",
    ".tcdut": "TcDUT",
    ".tcgvl": "TcGVL",
    ".tcio": "TcIO",
    ".plcproj": "plcproj",
    ".tsproj": "tsproj",
    ".st": "st",
    ".scl": "scl",
    ".db": "db",
    ".udt": "udt",
    ".l5x": "L5X",
    ".l5k": "L5K",
    ".xml": "plcopen",
}

LICENSE_FILE_RE = re.compile(r"^(LICEN[CS]E|COPYING|UNLICENSE)([.\-_][A-Za-z0-9.\-_]*)?$", re.I)

_I = re.IGNORECASE | re.MULTILINE

_ST_MARKERS = [
    re.compile(rf"\b{kw}\b", _I)
    for kw in (
        "END_VAR", "END_IF", "END_CASE", "END_FOR", "END_WHILE", "END_REPEAT",
        "FUNCTION_BLOCK", "END_FUNCTION_BLOCK", "END_FUNCTION", "END_PROGRAM",
        "END_TYPE", "END_STRUCT", "VAR_INPUT", "VAR_OUTPUT", "VAR_GLOBAL", "END_METHOD",
        "END_DATA_BLOCK", "END_ORGANIZATION_BLOCK",
    )
]
_SMALLTALK = re.compile(r"instanceVariableNames:|\bsubclass:\s*#|^!\w.*methodsFor:", _I)
_SIEMENS_IN_ST = re.compile(r"\bORGANIZATION_BLOCK\b|\bDATA_BLOCK\b|^\s*VERSION\s*:\s*'?\d", _I)
_SCL_BLOCK = re.compile(
    r"\b(FUNCTION_BLOCK|ORGANIZATION_BLOCK|DATA_BLOCK|FUNCTION|TYPE)\b.*?\bEND_"
    r"(FUNCTION_BLOCK|ORGANIZATION_BLOCK|DATA_BLOCK|FUNCTION|TYPE)\b",
    re.IGNORECASE | re.DOTALL,
)
_DB_BLOCK = re.compile(r"\bDATA_BLOCK\b.*?\bEND_DATA_BLOCK\b", re.IGNORECASE | re.DOTALL)
_UDT_BLOCK = re.compile(r"\bTYPE\b.*?\bEND_TYPE\b", re.IGNORECASE | re.DOTALL)
_PLCOPEN_NS = re.compile(r"plcopen\.org/xml/(tc6_020[01])", re.I)
_L5K = re.compile(r"\bIE_VER\b|^\s*CONTROLLER\b", _I)


@dataclass(frozen=True)
class Match:
    dialect: str
    format: str


def candidate_format(path: str) -> str | None:
    name = path.rsplit("/", 1)[-1]
    dot = name.rfind(".")
    if dot <= 0:
        return None
    return CANDIDATE_EXTENSIONS.get(name[dot:].lower())


def is_license_file(path: str) -> bool:
    return "/" not in path and bool(LICENSE_FILE_RE.match(path))


def decode(data: bytes) -> str:
    """Best-effort text decoding; PLC exports are mostly UTF-8 (with BOM), some UTF-16 or ANSI."""
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16", errors="replace")
    if data.startswith(b"\xef\xbb\xbf"):
        return data[3:].decode("utf-8", errors="replace")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("latin-1")


def count_lines(text: str) -> int:
    if not text:
        return 0
    return text.count("\n") + (0 if text.endswith("\n") else 1)


def _looks_binary(data: bytes) -> bool:
    head = data[:8192]
    if head.startswith((b"\xff\xfe", b"\xfe\xff")):
        return False
    return b"\x00" in head


def classify(path: str, data: bytes) -> Match | None:
    fmt = candidate_format(path)
    if fmt is None or _looks_binary(data):
        return None
    text = decode(data)
    # XML formats: the root element appears near the top
    head = text[:4096]

    if fmt in ("TcPOU", "TcDUT", "TcGVL", "TcIO"):
        return Match(TWINCAT, fmt) if "<TcPlcObject" in head else None
    if fmt == "plcproj":
        return Match(TWINCAT, fmt) if "<Project" in head else None
    if fmt == "tsproj":
        return Match(TWINCAT, fmt) if "<TcSmProject" in head else None
    if fmt == "L5X":
        return Match(ROCKWELL, fmt) if "<RSLogix5000Content" in head else None
    if fmt == "L5K":
        return Match(ROCKWELL, fmt) if _L5K.search(text[:65536]) else None
    if fmt == "plcopen":
        m = _PLCOPEN_NS.search(head)
        if m and "<project" in head:
            return Match(PLCOPEN, "plcopen-" + m.group(1).lower())
        return None
    if fmt == "scl":
        return Match(SIEMENS, fmt) if _SCL_BLOCK.search(text) else None
    if fmt == "db":
        return Match(SIEMENS, fmt) if _DB_BLOCK.search(text) else None
    if fmt == "udt":
        return Match(SIEMENS, fmt) if _UDT_BLOCK.search(text) else None
    if fmt == "st":
        if _SMALLTALK.search(text):
            return None
        hits = sum(1 for rx in _ST_MARKERS if rx.search(text))
        if hits < 2:
            return None
        return Match(SIEMENS if _SIEMENS_IN_ST.search(text) else CODESYS, fmt)
    return None
