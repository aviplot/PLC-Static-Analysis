"""Minimal JSON-over-HTTPS client with rate-limit aware back-off (stdlib only)."""

from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request

log = logging.getLogger("harvester.http")

USER_AGENT = "plca-corpus-harvester/0.1"


class HttpError(Exception):
    def __init__(self, status: int, url: str, body: str = ""):
        super().__init__(f"HTTP {status} for {url}: {body[:200]}")
        self.status = status


class Client:
    def __init__(self, base: str, headers: dict[str, str], min_interval: float = 0.0, max_retries: int = 8):
        self.base = base.rstrip("/")
        self.headers = {"User-Agent": USER_AGENT, **headers}
        self.min_interval = min_interval
        self.max_retries = max_retries
        self._last = 0.0

    def get(self, path: str, params: dict | None = None, interval: float | None = None) -> tuple[object, dict]:
        url = path if path.startswith("http") else self.base + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        delay = 5.0
        for attempt in range(self.max_retries + 1):
            wait = (interval if interval is not None else self.min_interval) - (time.monotonic() - self._last)
            if wait > 0:
                time.sleep(wait)
            self._last = time.monotonic()
            req = urllib.request.Request(url, headers=self.headers)
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    return json.loads(resp.read().decode("utf-8")), dict(resp.headers)
            except urllib.error.HTTPError as e:
                body = e.read().decode("utf-8", errors="replace")
                hdrs = dict(e.headers or {})
                if e.code in (403, 429) and attempt < self.max_retries and _is_rate_limited(e.code, hdrs, body):
                    sleep = _retry_after(hdrs) or delay
                    log.warning("rate limited (%s) on %s; sleeping %.0fs", e.code, path, sleep)
                    time.sleep(sleep)
                    delay = min(delay * 2, 900)
                    continue
                if e.code >= 500 and attempt < self.max_retries:
                    time.sleep(delay)
                    delay = min(delay * 2, 300)
                    continue
                raise HttpError(e.code, url, body) from None
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt < self.max_retries:
                    log.warning("network error on %s: %s; retrying", path, e)
                    time.sleep(delay)
                    delay = min(delay * 2, 300)
                    continue
                raise
        raise HttpError(0, url, "retries exhausted")


def _is_rate_limited(code: int, headers: dict, body: str) -> bool:
    if code == 429:
        return True
    h = {k.lower(): v for k, v in headers.items()}
    return (
        h.get("x-ratelimit-remaining") == "0"
        or "retry-after" in h
        or "rate limit" in body.lower()
        or "abuse" in body.lower()
    )


def _retry_after(headers: dict) -> float | None:
    h = {k.lower(): v for k, v in headers.items()}
    if "retry-after" in h:
        try:
            return float(h["retry-after"]) + 1
        except ValueError:
            pass
    for key in ("x-ratelimit-reset", "ratelimit-reset"):
        if h.get("x-ratelimit-remaining", h.get("ratelimit-remaining")) == "0" and key in h:
            try:
                return max(1.0, float(h[key]) - time.time() + 2)
            except ValueError:
                pass
    return None
