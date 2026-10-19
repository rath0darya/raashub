#!/usr/bin/env python3
"""Build the Android source index exclusively from onion URLs in repository data."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
OUT = ROOT / "app" / "src" / "main" / "assets" / "onion_sources.json"
URL_RE = re.compile(r"""(?i)(?:https?://)?[a-z2-7]{16,56}\.onion(?:/[^\s"'<>\\]*)?""")
GROUP_RE = re.compile(r'"(?:group|name|title)"\s*:\s*"([^"]+)"', re.I)
records = {}

for path in sorted(DATA.rglob("*")):
    if not path.is_file() or path.suffix.lower() not in {".json", ".txt", ".csv"}:
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
            raw = match.group(0).rstrip(".,;)")
            if ".onion" not in raw.lower():
                continue
            url = raw if raw.lower().startswith(("http://", "https://")) else "http://" + raw
            key = url.lower()
            source = path.relative_to(ROOT).as_posix()
            if key not in records:
                records[key] = {"url": url, "entity": current_entity, "source": source, "sources": [source]}
            elif source not in records[key]["sources"]:
                records[key]["sources"].append(source)

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(sorted(records.values(), key=lambda r: r["url"].lower()), ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"Indexed {len(records)} unique onion URLs across {len({s for r in records.values() for s in r['sources']})} repository source references")
if not records:
    raise SystemExit("No repository onion URLs found; refusing to create an empty source index")
