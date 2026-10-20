#!/usr/bin/env python3
"""Permanently remove duplicate URL entries from the structured source directory.

The main directory is the canonical first-priority source. Remaining URLs in
per-group JSON files are retained only once across the entire source dataset.
URL identity ignores scheme and trailing slashes but preserves path case and
query strings, since paths can be case-sensitive.
"""
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
DIRECTORY = DATA / "directory.json"
GROUPS = DATA / "groups"
seen = set()
files_changed = 0
urls_removed = 0
urls_seen = 0


def normalize_url(raw):
    value = str(raw or "").strip().rstrip(".,;)")
    if not value:
        return None
    # Remove accidental outer URLs that wrap a real protocol URL.
    for _ in range(4):
        nested = re.match(r"(?i)^https?://[^/]+/(https?://.+)$", value)
        if not nested:
            break
        value = nested.group(1)
    if not value.lower().startswith(("http://", "https://")):
        value = "http://" + value
    try:
        parsed = urlsplit(value)
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
            return None
    except ValueError:
        return None
    return value


def url_key(raw):
    value = normalize_url(raw)
    if not value:
        return None
    parsed = urlsplit(value)
    return (parsed.hostname.lower() + parsed.path.rstrip("/")
            + ("?" + parsed.query if parsed.query else "")
            + ("#" + parsed.fragment if parsed.fragment else ""))


def clean_file(path):
    global files_changed, urls_removed, urls_seen
    try:
        original = path.read_text(encoding="utf-8")
        payload = json.loads(original)
    except (OSError, json.JSONDecodeError):
        return
    if isinstance(payload, list):
        rows = payload
    elif isinstance(payload, dict):
        rows = [payload]
    else:
        return

    changed = False
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("links"), list):
            continue
        kept = []
        for link in row["links"]:
            raw = link if isinstance(link, str) else (link.get("url") if isinstance(link, dict) else None)
            if not raw:
                kept.append(link)
                continue
            urls_seen += 1
            normalized = normalize_url(raw)
            if normalized is not None and normalized != str(raw):
                changed = True
                if isinstance(link, str):
                    link = normalized
                else:
                    link = dict(link)
                    link["url"] = normalized
            key = url_key(normalized if normalized is not None else raw)
            if key is None or key not in seen:
                if key is not None:
                    seen.add(key)
                kept.append(link)
            else:
                urls_removed += 1
                changed = True
        if len(kept) != len(row["links"]):
            row["links"] = kept

    # Remove now-empty group rows from the master directory, but keep individual
    # group files as metadata records so their provenance is not lost.
    if path == DIRECTORY and isinstance(payload, list):
        filtered = [row for row in payload if not isinstance(row, dict) or row.get("links")]
        if len(filtered) != len(payload):
            payload = filtered
            changed = True

    if changed:
        serialized = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
        path.write_text(serialized, encoding="utf-8")
        files_changed += 1


if DIRECTORY.is_file():
    clean_file(DIRECTORY)
if GROUPS.is_dir():
    for file_path in sorted(GROUPS.glob("*.json")):
        clean_file(file_path)

print(f"Scanned {urls_seen} structured URL entries; removed {urls_removed} duplicates; changed {files_changed} files.")
