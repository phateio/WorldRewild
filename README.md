# WorldRewild

A Paper plugin for rewilding resource worlds.

## What it does

WorldRewild keeps a resource world fresh by continuously regenerating the chunks
nobody has visited for a while:

1. It sweeps a world one **region file at a time** (a "tile" = 32×32 chunks),
   nearest-to-spawn first. A chunk is eligible when its last-saved time (the
   region file's per-chunk timestamp — updated whenever a player loads it) is
   older than `min-age-days`.
2. Each tile is regenerated **two-phase**: first it deletes every eligible
   chunk's block/entity/POI data through Paper's **Moonrise** chunk system, then
   it reloads them so the server **regenerates each from scratch with the current
   generator** — terrain, structures **and** structure mobs — and saves them.
3. Work is **TPS-gated** and capped to a few concurrent regenerations, so it runs
   quietly in the background and players never trigger a burst of on-demand
   generation.
4. After sweeping every configured world it waits `rescan-interval-hours` and
   repeats, so the world stays on a rolling refresh cycle.

No FAWE / WorldEdit required. Since our own regeneration updates the timestamp,
each area refreshes roughly every `min-age-days` while it stays unvisited.

### Why whole tiles, not one chunk at a time

On a cross-version map (e.g. a 1.21.11 world opened under 26.2), the worldgen
**Blender** blends a regenerating chunk toward its *old* neighbours' terrain and
biomes. Regenerating chunks one-by-one therefore just reproduces the old world.
Deleting a whole tile first means its **interior** chunks regenerate with no old
neighbours, so they come out as genuine current-version terrain (verified: an old
forested-land chunk became the correct 26.2 ocean + `sulfur_caves`). Only a thin
biome-only rim on tile edges that still border old chunks is blended, and that rim
**heals on the next rolling re-sweep** once those neighbours have themselves been
converted. The delete itself is a synchronous Moonrise `RegionFileStorage.clear`
(via `RegionDataController.startWrite(x,z,null)`/`finishWrite`); the older
`scheduleSave(..., null, ...)` path does **not** delete, so the chunk would only
be DataFixer-upgraded, never truly regenerated.

## Commands

`/worldrewild` (alias `/wr`), permission `worldrewild.admin` (default: op):

| Command | Description |
|---------|-------------|
| `/wr start` / `pause` / `resume` / `stop` | Control the resident sweeper |
| `/wr status` | Current state, world, progress, TPS |
| `/wr count` | Dry run: how many chunks are eligible right now (per world) |
| `/wr reset` | Clear saved progress |
| `/wr reload` | Reload `config.yml` |
| `/wr region <world> <cx1> <cz1> <cx2> <cz2>` | Manually regenerate a chunk rectangle now (refuses if players are inside) |
| `/wr vanillaregen <world> <cx> <cz>` | Regenerate a single chunk now |
| `/wr delete <world> <cx> <cz>` | Delete a chunk's data and flush, without reloading (diagnostic) |
| `/wr probe <world> <cx> <cz> [material]` | Count a material in a chunk (diagnostic) |
| `/wr entities <world> <cx> <cz> [type]` | List entities in a chunk (diagnostic) |

## Configuration

See [`config.yml`](config.yml). Key options: `enabled` (auto-start on boot),
`worlds` (list of name + region-dir), `min-age-days` (default 30),
`min-age-seconds` (sub-day override for testing; -1 = off),
`rescan-interval-hours`, `tps-pause` / `tps-resume`, `per-tick`,
`max-concurrent-regens`, `player-safe-radius-chunks`,
`protect-spawn-radius-chunks`.

## Building

```sh
./build.sh   # compiles with javac against the server's paper-api; outputs WorldRewild.jar
```

Requires JDK 21+ and a Paper server whose `libraries/` contain the matching
`paper-api` (set `SRV` to your server path).

## Notes

- Requires Paper (uses the Moonrise chunk system via reflection). Tested on
  Paper 26.1.2 and 26.2.
- Regenerated tiles convert an old-version map to the current generator; the
  first pass leaves a thin biome-only rim on tile edges bordering still-old
  chunks, which heals on the next rolling re-sweep (see *Why whole tiles* above).
- A classic cobblestone **dungeon** whose spawner sits exactly on a chunk border
  may generate without its spawner (the room and chest still generate). Structure
  spawners (trial chambers, etc.) regenerate correctly.
- Deleting chunk data bypasses block-logging plugins (e.g. CoreProtect) for the
  regenerated area.
