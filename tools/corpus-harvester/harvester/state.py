"""Checkpoint file: everything needed to resume any stage."""

from __future__ import annotations

import json
import os
import threading
from pathlib import Path


class State:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.RLock()
        if path.exists():
            self.data = json.loads(path.read_text(encoding="utf-8"))
        else:
            self.data = {}
        self.data.setdefault("queries", {})  # query key -> {"done": bool, "pages": int, "total": int, "truncated": bool}
        self.data.setdefault("repos", {})    # repo key -> repo record
        self.data.setdefault("next_order", 0)

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
            tmp.write_text(json.dumps(self.data, indent=1, sort_keys=True), encoding="utf-8")
            os.replace(tmp, self.path)
