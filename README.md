# WorldRewild

A Paper plugin for rewilding resource worlds by regenerating chunks left untouched for weeks — TPS-gated, no downtime.

## What it does

WorldRewild keeps a resource world fresh by continuously regenerating the chunks
nobody has visited for a while:

1. It scans a world's region files and picks every generated chunk whose
   last-saved time (the region file's per-chunk timestamp — updated whenever a
   player loads it) is older than `min-age-days`.
2. For each one (that is unloaded and clear of players) it deletes the chunk's
   block/entity/POI data through Paper's **Moonrise** chunk system, then loads it
   asynchronously so the server **regenerates it from scratch with the current
   generator** — terrain, structures **and** structure mobs — and saves it.
3. Work is **TPS-gated** and capped to a few concurrent regenerations, so it runs
   quietly in the background and players never trigger a burst of on-demand
   generation.
4. After sweeping every configured world it waits `rescan-interval-hours` and
   repeats, so the world stays on a rolling refresh cycle.

No FAWE / WorldEdit required. Since our own regeneration updates the timestamp,
each area refreshes roughly every `min-age-days` while it stays unvisited.

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
| `/wr probe <world> <cx> <cz> [material]` | Count a material in a chunk (diagnostic) |
| `/wr entities <world> <cx> <cz> [type]` | List entities in a chunk (diagnostic) |

## Configuration

See [`config.yml`](config.yml). Key options: `enabled` (auto-start on boot),
`worlds` (list of name + region-dir), `min-age-days` (default 30),
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
- Chunks are regenerated one at a time, so a classic cobblestone **dungeon**
  whose spawner sits on a chunk border may generate without its spawner (the
  room and chest still generate). Structure spawners (trial chambers, etc.)
  regenerate correctly. Terrain at chunk borders may show minor seams.
- Deleting chunk data bypasses block-logging plugins (e.g. CoreProtect) for the
  regenerated area.
