"""Checkpoint file: everything needed to resume any stage."""

from __future__ import annotations

import json
import logging
import os
import threading
from pathlib import Path

log = logging.getLogger("harvester.state")


class State:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.RLock()
        self.data = self._load(path)
        self.data.setdefault("queries", {})  # query key -> {"done": bool, "pages": int, "total": int, "truncated": bool}
        self.data.setdefault("repos", {})    # repo key -> repo record
        self.data.setdefault("next_order", 0)

    @staticmethod
    def _load(path: Path) -> dict:
        """Loads the checkpoint, falling back to the previous good copy if it is damaged."""
        for candidate in (path, path.with_suffix(".bak")):
            if not candidate.exists():
                continue
            try:
                data = json.loads(candidate.read_text(encoding="utf-8"))
            except (ValueError, UnicodeDecodeError) as e:
                log.warning("checkpoint %s is damaged (%s); trying the backup", candidate, e)
                continue
            if candidate != path:
                log.warning("resuming from backup checkpoint %s", candidate)
            return data
        if path.exists():
            raise RuntimeError(f"checkpoint {path} and its backup are damaged; move them away to start over")
        return {}

    @property
    def repos(self) -> dict:
        return self.data["repos"]

    @property
    def queries(self) -> dict:
        return self.data["queries"]

    def add_repo(self, key: str, record: dict, discovered_by: str) -> bool:
        """Register a candidate repo; returns True if it was new."""
        with self.lock:
            existing = self.repos.get(key)
            if existing:
                if discovered_by not in existing["discovered_by"]:
                    existing["discovered_by"].append(discovered_by)
                for k, v in record.items():
                    existing.setdefault(k, v)
                return False
            record = dict(record)
            record["discovered_by"] = [discovered_by]
            record["order"] = self.data["next_order"]
            record.setdefault("status", "pending")
            self.data["next_order"] += 1
            self.repos[key] = record
            return True

    def save(self) -> None:
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            # fsync before the rename: after a power loss NTFS (and ext4 without auto_da_alloc) can
            # otherwise keep the new name but not the data, leaving a file of zero bytes.
            # discover and fetch run in parallel threads; discover adds keys without taking the lock,
            # so retry if a dict changed size mid-serialization
            for attempt in range(20):
                try:
                    text = json.dumps(self.data, indent=1, sort_keys=True)
                    break
                except RuntimeError:
                    if attempt == 19:
                        raise
            with tmp.open("w", encoding="utf-8") as fh:
                fh.write(text)
                fh.flush()
                os.fsync(fh.fileno())
            if self.path.exists():
                os.replace(self.path, self.path.with_suffix(".bak"))
            os.replace(tmp, self.path)
