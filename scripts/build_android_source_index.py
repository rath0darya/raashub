#!/usr/bin/env python3
"""Build the offline Android source index from the repository's directory and data files."""
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
DIRECTORY = DATA / "directory.json"
OUT = ROOT / "app" / "src" / "main" / "assets" / "onion_sources.json"
URL_RE = re.compile(r"""(?i)(?:https?://)?[a-z2-7]{16,56}\.onion(?:/[^\s"'<>\\]*)?""")
GROUP_RE = re.compile(r'"(?:group|name|title)"\s*:\s*"([^"]+)"', re.I)
records = {}


def normalize_url(raw):
    raw = str(raw or "").strip().rstrip(".,;)")
    if not raw:
        return None
    if not raw.lower().startswith(("http://", "https://")):
        raw = "http://" + raw
    parsed = urlsplit(raw)
    if not parsed.hostname:
        return None
    return raw


def key_for(url):
    """Deduplicate URL identities across scheme and trailing-slash variants."""
    parsed = urlsplit(url)
    host = (parsed.hostname or "").lower()
    path = parsed.path.rstrip("/")
    return host + path + ("?" + parsed.query if parsed.query else "") + ("#" + parsed.fragment if parsed.fragment else "")


# The structured directory contains group/market labels and both onion and clear-web links.
if DIRECTORY.is_file():
    try:
        directory_items = json.loads(DIRECTORY.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        directory_items = []
    for item in directory_items if isinstance(directory_items, list) else []:
        if not isinstance(item, dict):
            continue
        entity = str(item.get("group") or item.get("name") or item.get("title") or "Unnamed source").strip()
        fallback_types = item.get("types") or []
        fallback_category = str(fallback_types[0] if fallback_types else "other").lower()
        for link in item.get("links") or []:
            if isinstance(link, str):
                raw, category = link, fallback_category
            elif isinstance(link, dict):
                raw = link.get("url")
                category = str(link.get("type") or fallback_category).lower()
            else:
                continue
            url = normalize_url(raw)
            if not url:
                continue
            key = key_for(url)
            source = "data/directory.json"
            if key not in records:
                records[key] = {"url": url, "entity": entity, "source": source,
                                "sources": [source], "category": category}
            elif source not in records[key]["sources"]:
                records[key]["sources"].append(source)

# Keep additional onion sources found elsewhere in the repository and merge their provenance.
for path in sorted(DATA.rglob("*")):
    if not path.is_file() or path == DIRECTORY or path.suffix.lower() not in {".json", ".txt", ".csv"}:
        continue
    try:
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        continue
    current_entity = path.stem.replace("-", " ").replace("_", " ")
    for line in lines:
        entity_match = GROUP_RE.search(line)
        if entity_match:
            current_entity = entity_match.group(1).strip() or current_entity
        for match in URL_RE.finditer(line):
            url = normalize_url(match.group(0))
            if not url:
                continue
            key = key_for(url)
            source = path.relative_to(ROOT).as_posix()
            if key not in records:
                records[key] = {"url": url, "entity": current_entity, "source": source,
                                "sources": [source], "category": "other"}
            elif source not in records[key]["sources"]:
                records[key]["sources"].append(source)

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(sorted(records.values(), key=lambda r: r["url"].lower()),
                           ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

onion_count = sum(".onion" in urlsplit(record["url"]).hostname.lower() for record in records.values())
clear_count = len(records) - onion_count
print(f"Indexed {len(records)} unique URLs ({onion_count} onion, {clear_count} clear-web) across {len({s for r in records.values() for s in r['sources']})} source references")
if not records:
    raise SystemExit("No source URLs found; refusing to create an empty source index")
