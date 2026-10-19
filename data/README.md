# Runtime Data

`data/` is the canonical runtime-data boundary for RaaSHub.

| File / directory | Purpose | Generated |
| --- | --- | --- |
| `directory.js` | Canonical entity and link dataset | Source-managed |
| `victims.json` | Victim intelligence records | Yes |
| `markets.json` | Market records and source snapshots | Yes |
| `status.json` | Latest link-health snapshot | Yes |
| `status-history.json` | Historical health snapshots | Yes |
| `groups/` | Per-entity generated shards | Yes |

Generated files should be updated by their owning workflow rather than by frontend code.
