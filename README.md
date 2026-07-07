# WorldRewild

A Paper plugin for rewilding resource worlds.

## What it does

WorldRewild keeps a resource world fresh by continuously regenerating the chunks
nobody has touched for a while:

1. It sweeps a world one **region file at a time** (a "tile" = 32×32 chunks),
   nearest-to-spawn first. A chunk is eligible when its last-saved time (the
   region file's per-chunk timestamp — bumped when the chunk is modified and
   saved, not by merely loading it) is older than the world's `min-age`.
2. Each tile is regenerated **two-phase**: first it deletes every eligible
   chunk's block/entity/POI data through Paper's **Moonrise** chunk system, then
   it reloads them so the server **regenerates each from scratch with the current
   generator** — terrain, structures **and** structure mobs — and saves them.
3. Work is **TPS-gated** and capped to a few concurrent regenerations, so it runs
   quietly in the background and players never trigger a burst of on-demand
   generation.
4. After sweeping every configured world it waits `sweep-interval` and
   repeats.

No FAWE / WorldEdit required. The resident sweep is **off by default**
(`enabled: false`) — it's the heavy part, so start it on demand with `/wr start`.
The lightweight [structure reset](#rare-structures-on-a-fast-cycle) below runs on
its own schedule regardless.

### Skip chunks nothing has changed

By default (`skip-unchanged-chunks: true`) a chunk is regenerated again only once
it has **changed** since the last time WorldRewild reset it — detected by the
region file's per-chunk timestamp advancing past the "reset stamp" recorded right
after our own reset (stamps kept in `plugins/WorldRewild/stamps/`). Without this,
our own regen keeps bumping that timestamp, so a chunk nobody has touched would be
re-regenerated on every cycle forever — most wastefully in the End, whose vast
empty void would otherwise be re-reset daily. Chunks we have never reset have no
stamp and stay eligible, so the **first-pass cross-version conversion still sweeps
the whole map once**; after that, untouched wilderness goes dormant and only the
areas players actually change keep refreshing (on the `min-age` cycle).

### Renewing the End dragon

Set `respawn-dragon: true` on the End world and the age sweep renews the dragon
too. The central arena can't be chunk-regenerated in place, so instead the
ordinary sweep regenerates the End's central island — once a kill has changed it
and it goes idle — removing the old exit portal and end gateways. Only the pass
that actually regenerates the central-island chunk (0,0) then resets the fight's
saved state (via NMS); a pass that touches nothing there — nobody visited, so
skip-unchanged skips it — leaves the fight alone, so an unbeaten dragon is never
disturbed. Because the fight re-derives "previously killed" from whether an exit
portal / gateway still exists, a fresh dragon spawns on the next entry. So the
end-game journey (stronghold → portal → dragon → end city → elytra) renews.
Because it rides the age sweep, it only happens while the sweep is enabled;
`/wr end reset` forces a fight-state reset on demand.

### Rare structures on a fast cycle

Alongside the slow wilderness refresh, WorldRewild resets **specific rare
structures** on a much faster cycle (default every 6 hours, aligned to the
wall clock at 00:00/06:00/12:00/18:00) so their loot, mobs and build come back
for the next player. Structures are found automatically by reading each
chunk's stored `structures.starts` straight from the region files — no in-game
marking. Each configured type (by default the rare, high-value, low-count ones:
woodland mansion, stronghold, jungle/desert pyramid, pillager outpost, bastion
remnant) has its footprint regenerated with the same two-phase core, skipping any
structure a player is currently inside — and, with `skip-unchanged-chunks` on, any
that has not changed since its last reset, so only raided structures are refreshed.
Very numerous types like ancient cities and end cities are left off this fast cycle
and refresh via the slower age sweep.

### Why whole tiles, not one chunk at a time

On a cross-version map (e.g. a 1.21.11 world opened under 26.2), the worldgen
**Blender** blends a regenerating chunk toward its *old* neighbours' terrain and
biomes. Regenerating chunks one-by-one therefore just reproduces the old world.
Deleting a whole tile first means its **interior** chunks regenerate with no old
neighbours, so they come out as genuine current-version terrain (verified: an old
forested-land chunk became the correct 26.2 ocean + `sulfur_caves`). Only a thin
biome-only rim on tile edges that still border old chunks is blended, and that rim
heals the next time that area is swept, once those neighbours have themselves been
converted (with `skip-unchanged-chunks` on, once a player has been back through it).
The delete itself is a synchronous Moonrise `RegionFileStorage.clear`
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
| `/wr reset` | Clear saved progress and the reset-stamp cache |
| `/wr reload` | Reload `config.yml` |
| `/wr region <world> <cx1> <cz1> <cx2> <cz2>` | Manually regenerate a chunk rectangle now, two-phase (refuses if players are inside) |
| `/wr vanillaregen <world> <cx> <cz>` | Regenerate a single chunk now (subject to edge blending on a cross-version map — use `region` to convert an area) |
| `/wr struct status` | Structure-reset registry: counts per type, scan state |
| `/wr struct scan` | (Re)scan region files for structures (incremental after the first pass) |
| `/wr struct reset [type]` | Regenerate all registered structures now (optionally just one type) |
| `/wr end reset` | Reset the End dragon fight state now (a fresh dragon spawns after the central island is next regenerated by the sweep) |
| `/wr probe <world> <cx> <cz> <material>` | Count a material in a chunk (diagnostic) |
| `/wr entities <world> <cx> <cz> [type]` | List entities in a chunk (diagnostic) |

## Configuration

See [`config.yml`](config.yml). Time values are durations — `90d` / `6h` / `10m` /
`1d12h` (units d/h/m/s, concatenable). Both features share a top-level `worlds`
list — each entry is a name plus a **required** `min-age` duration (a world missing
or malforming it is skipped), optionally with a `region-dir` override (else derived
from the loaded world).

`resident-sweep`: `enabled` (default false), `skip-unchanged-chunks` (default true;
see above), `respawn-dragon` (renew the End dragon after its sweep pass),
`sweep-interval`, `tps-pause` / `tps-resume`, `interval-ticks` / `per-tick` /
`max-concurrent-regens`, `player-safe-radius-chunks`, `protect-spawn-radius-chunks`,
`max-consecutive-failures`, `auto-resume`.

`structure-reset`: `enabled`, `interval` (default 6h, wall-clock aligned), `rescan`
(registry refresh), `footprint-margin-chunks`, `types`.

## Building

```sh
./gradlew build   # outputs build/libs/WorldRewild-<version>.jar
```

Requires JDK 25 (Paper 26.2's API needs Java 25). The Paper API is resolved from
Maven, so no local server is needed to compile.

## Notes

- Requires Paper (uses the Moonrise chunk system via reflection). Tested on
  Paper 26.1.2 and 26.2.
- A classic cobblestone **dungeon** whose spawner sits exactly on a chunk border
  may generate without its spawner (the room and chest still generate). Structure
  spawners (trial chambers, etc.) regenerate correctly.
- Deleting chunk data bypasses block-logging plugins (e.g. CoreProtect) for the
  regenerated area.
- Loot chests refill on reset, but their contents are deterministic per seed +
  position — the same structure yields the same loot each time it is reset.
