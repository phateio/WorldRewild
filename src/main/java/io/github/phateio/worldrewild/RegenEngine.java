package io.github.phateio.worldrewild;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Resident, perpetual resource-world regenerator.
 *
 * <p>Sweeps a list of worlds in order. Each world is processed one region file
 * at a time ("tile" = 32x32 chunks), nearest-to-spawn first. Every tile is
 * regenerated <b>two-phase</b>: first delete the stored data (block/entity/POI,
 * via Paper's Moonrise chunk system) of every eligible chunk in the tile, then
 * reload them so the server regenerates each from scratch (terrain, structures,
 * structure mobs) with the current generator, and unload so the fresh chunk is
 * saved. Deleting the whole tile first means interior chunks regenerate with no
 * old neighbours, so a cross-version map (e.g. 1.21.11 -> 26.2) actually converts
 * instead of being blended back toward the old terrain by the worldgen Blender.
 * A thin biome-only rim on tile edges that still border old chunks heals the next
 * time that area is swept, once those neighbours have themselves been converted.
 *
 * <p>Regeneration is TPS-gated and bounded by a concurrency cap so players never
 * trigger mass on-demand generation. After all worlds are swept it waits
 * {@code sweep-interval} and repeats. A manual {@code /wr region} command
 * regenerates an arbitrary rectangle on demand (also two-phase).
 */
public final class RegenEngine {

    enum State { STOPPED, RUNNING, PAUSED, WAITING }

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int HEADER_BYTES = 8192; // 4 KiB location table + 4 KiB timestamps
    private static final int SLOTS = 1024;        // 32x32 chunk slots per region file
    private static final int[] EMPTY = new int[0];
    private static final byte[] NO_HEADER = new byte[0]; // sentinel: region file absent/unreadable
    // A reset chunk's own post-reset save finalises up to a second or two after we
    // record its stamp (async), so stamp a bit into the future to stay at/above that
    // save's timestamp — otherwise the chunk looks "changed" forever. No unoccupied
    // chunk is legitimately modified within this window.
    private static final long STAMP_MARGIN_SECONDS = 60L;

    // regionDirOverride is the configured region-dir, or null to derive it from
    // the loaded world's folder (the normal case). minAgeSeconds is the resolved
    // per-world age threshold.
    private record WorldEntry(String name, File regionDirOverride, long minAgeSeconds) {}

    /** A chunk rectangle (inclusive) to regenerate as one box of a batch. */
    record Box(World world, int minCx, int minCz, int maxCx, int maxCz) {}

    @FunctionalInterface
    private interface RegionFileConsumer {
        void accept(File file, int rx, int rz) throws IOException;
    }

    private final WorldRewild plugin;
    private final File targetsFile;
    private final File stateFile;

    // Config snapshot (refreshed by reloadConfig).
    private final List<WorldEntry> worldEntries = new ArrayList<>();
    private double tpsPause;
    private double tpsResume;
    private long intervalTicks;
    private int perTick;
    private int maxConcurrent;
    private int playerSafeRadius;
    private int protectSpawnRadius;
    private int maxConsecutiveFailures;
    private long sweepIntervalMs;
    private boolean enabled;
    private boolean autoResume;
    private boolean skipUnchanged;   // global: reset a chunk only once it changed since our last reset
    private boolean respawnDragon;   // reset the End dragon after an End world's sweep pass

    // Runtime state.
    // Did the current world's pass regenerate the central-island chunk (0,0)? The End
    // exit portal sits in that chunk and is what vanilla reads to decide "dragon already
    // killed"; only when we actually rebuild it should the dragon fight be reset. Scoped
    // per world pass (cleared when a world's scan starts), persisted so a mid-pass restart
    // does not drop a pending reset.
    private boolean islandRegen = false;
    private volatile State state = State.STOPPED;
    private volatile boolean scanning = false;
    private boolean tpsPaused = false;
    private int worldIndex = 0;
    private WorldEntry cur;
    private int curSpawnCx = 0;
    private int curSpawnCz = 0;
    private long completed = 0;      // chunks regenerated this sweep (across worlds)
    private int inFlight = 0;        // async regens in progress (main-thread only)
    private long nextSweepEpochMs = 0;
    private boolean busy = false;   // a manual/structure regen batch is running; pauses the resident sweep
    private BukkitTask task;
    private int consecutiveFailures = 0;
    private String pauseReason = null;

    // Current world's tiles, processed nearest-to-spawn first.
    private int[] regions;          // packed [rx0,rz0,rx1,rz1,...]
    private int regionCount = 0;
    private int regionPos = 0;      // index of the tile being / about to be processed

    // The tile currently in flight (two-phase: delete every chunk, then reload).
    private boolean tileActive = false;
    private boolean tileDeleting = false;
    private int[] tileChunks;       // eligible chunks in this tile, packed [cx,cz,...]
    private boolean[] tileDeleted;  // parallel: was this chunk actually deleted this pass?
    private int tileN = 0;
    private int tileDelPos = 0;
    private int tileLoadPos = 0;

    // Per-chunk "reset stamp" sidecar used to skip chunks unchanged since our
    // last reset (see ResetStamps).
    private final ResetStamps stamps;

    RegenEngine(WorldRewild plugin) {
        this.plugin = plugin;
        this.targetsFile = new File(plugin.getDataFolder(), "targets.bin");
        this.stateFile = new File(plugin.getDataFolder(), "state.properties");
        this.stamps = new ResetStamps(plugin);
    }

    // ------------------------------------------------------------------ config

    void reloadConfig() {
        plugin.reloadConfig();
        var c = plugin.getConfig();
        File container = plugin.getServer().getWorldContainer();
        // worlds is shared with structure-reset, so it stays top-level; every other
        // resident-sweep knob lives under the resident-sweep section.
        skipUnchanged = c.getBoolean("resident-sweep.skip-unchanged-chunks", true);
        worldEntries.clear();
        for (Map<?, ?> m : c.getMapList("worlds")) {
            Object n = m.get("name");
            if (n == null) {
                continue;
            }
            String name = n.toString();
            Object mv = m.get("min-age");
            long minAge = Durations.seconds(mv == null ? null : mv.toString());
            if (minAge < 0) {
                plugin.getLogger().warning("World '" + name + "' has no valid min-age"
                        + " (e.g. 90d, 6h, 1d12h); skipping it.");
                continue;
            }
            Object rd = m.get("region-dir");
            File override = rd != null ? new File(container, rd.toString()) : null;
            worldEntries.add(new WorldEntry(name, override, minAge));
        }
        tpsPause = c.getDouble("resident-sweep.tps-pause", 18.0);
        tpsResume = c.getDouble("resident-sweep.tps-resume", 18.5);
        intervalTicks = Math.max(1L, c.getLong("resident-sweep.interval-ticks", 2L));
        perTick = Math.max(1, c.getInt("resident-sweep.per-tick", 4));
        maxConcurrent = Math.max(1, c.getInt("resident-sweep.max-concurrent-regens", 4));
        playerSafeRadius = Math.max(0, c.getInt("resident-sweep.player-safe-radius-chunks", 4));
        protectSpawnRadius = Math.max(0, c.getInt("resident-sweep.protect-spawn-radius-chunks", 0));
        maxConsecutiveFailures = Math.max(1, c.getInt("resident-sweep.max-consecutive-failures", 5));
        sweepIntervalMs = Durations.secondsOr(c.get("resident-sweep.sweep-interval"), 86400L) * 1000L;
        enabled = c.getBoolean("resident-sweep.enabled", false);
        autoResume = c.getBoolean("resident-sweep.auto-resume", true);
        respawnDragon = c.getBoolean("resident-sweep.respawn-dragon", false);
    }

    // --------------------------------------------------------------- lifecycle

    void onEnable() {
        reloadConfig();
        stamps.load();
        loadState();
        if (state == State.RUNNING || state == State.PAUSED) {
            if (autoResume && worldIndex < worldEntries.size() && loadTargets()) {
                cur = worldEntries.get(worldIndex);
                setSpawnFor(cur);
                startTask();
                plugin.getLogger().info("Resuming (" + state + "): " + progressLine());
            } else {
                state = State.STOPPED;
                if (enabled) {
                    startTask();
                    beginSweep();
                }
            }
        } else if (state == State.WAITING) {
            startTask();
            plugin.getLogger().info("Resident waiting; next sweep at stored time.");
        } else if (enabled) {
            startTask();
            beginSweep();
            plugin.getLogger().info("Resident enabled; starting first sweep.");
        }
    }

    void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (cur != null) {
            flushQuietly(getWorld(cur.name()));
        }
        persist();
    }

    // --------------------------------------------------------------- commands

    String cmdStart() {
        if (scanning) {
            return "A scan is in progress; please wait.";
        }
        if (state == State.RUNNING) {
            return "Already running. " + progressLine();
        }
        if (state == State.PAUSED) {
            return cmdResume();
        }
        if (worldEntries.isEmpty()) {
            return "No worlds configured.";
        }
        consecutiveFailures = 0;
        pauseReason = null;
        tpsPaused = false;
        if (task == null) {
            startTask();
        }
        beginSweep();
        return "Started a sweep of " + worldEntries.size() + " world(s).";
    }

    String cmdPause() {
        if (state != State.RUNNING) {
            return "Not running.";
        }
        state = State.PAUSED;
        pauseReason = "manual";
        persist();
        return "Paused (manual). " + progressLine();
    }

    String cmdResume() {
        if (state != State.PAUSED) {
            return "Not paused.";
        }
        consecutiveFailures = 0;
        pauseReason = null;
        tpsPaused = false;
        state = State.RUNNING;
        if (task == null) {
            startTask();
        }
        persist();
        return "Resumed. " + progressLine();
    }

    String cmdStop() {
        boolean had = state != State.STOPPED;
        state = State.STOPPED;
        pauseReason = null;
        consecutiveFailures = 0;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (cur != null) {
            flushQuietly(getWorld(cur.name()));
        }
        persist();
        return had ? "Stopped. " + progressLine() : "Already stopped.";
    }

    String cmdReset() {
        state = State.STOPPED;
        if (task != null) {
            task.cancel();
            task = null;
        }
        cur = null;
        regions = null;
        regionCount = 0;
        regionPos = 0;
        tileActive = false;
        tileDeleting = false;
        tileChunks = null;
        tileDeleted = null;
        tileN = 0;
        tileDelPos = 0;
        tileLoadPos = 0;
        completed = 0;
        worldIndex = 0;
        consecutiveFailures = 0;
        pauseReason = null;
        nextSweepEpochMs = 0;
        targetsFile.delete();
        stateFile.delete();
        stamps.clear();
        return "Progress cleared. Next /wr start rescans from scratch.";
    }

    List<String> cmdStatus() {
        List<String> out = new ArrayList<>();
        String worldInfo = cur != null ? cur.name() + " (" + (worldIndex + 1) + "/" + worldEntries.size() + ")" : "-";
        out.add("§6[WorldRewild] §fstate=§e" + state + (scanning ? " §c(scanning)" : "")
                + (busy ? " §c(batch running)" : ""));
        out.add("§7world=§f" + worldInfo + " §7tps-gate=§f" + tpsPause + "/" + tpsResume
                + " §7tps-paused=§f" + tpsPaused);
        out.add("§7current TPS=§f" + String.format("%.2f", currentTps())
                + " §7interval=§f" + intervalTicks + "t §7per-tick=§f" + perTick
                + " §7in-flight=§f" + inFlight + "/" + maxConcurrent + " §7min-age=§f"
                + (cur != null ? Durations.human(cur.minAgeSeconds()) : "-"));
        out.add("§7progress: §a" + completed + "§7 regen this sweep, tile §f" + regionPos + "§7/§f" + regionCount
                + (regionCount > 0 ? " §7(" + String.format("%.1f", 100.0 * regionPos / regionCount) + "%)" : "")
                + (tileActive ? " §7[" + (tileDeleting ? "del " + tileDelPos : "gen " + tileLoadPos)
                        + "/" + tileN + "]" : ""));
        if (state == State.WAITING) {
            long left = Math.max(0, nextSweepEpochMs - System.currentTimeMillis()) / 60000L;
            out.add("§7next sweep in §f~" + left + " min");
        }
        if (consecutiveFailures > 0 || pauseReason != null) {
            out.add("§7failures=§f" + consecutiveFailures + "/" + maxConsecutiveFailures
                    + (pauseReason != null ? " §7pause-reason=§c" + pauseReason : ""));
        }
        return out;
    }

    private record CountJob(String name, File regionDir, int spawnCx, int spawnCz, long minAgeSeconds,
                            boolean skipUnchanged) {}

    /** Dry scan: report eligible chunk counts per configured world, without starting. */
    void cmdCount(CommandSender sender) {
        if (scanning) {
            sender.sendMessage("§cA scan is in progress.");
            return;
        }
        List<CountJob> jobs = new ArrayList<>();
        for (WorldEntry e : worldEntries) {
            World w = getWorld(e.name());
            if (w == null) {
                jobs.add(new CountJob(e.name(), null, 0, 0, e.minAgeSeconds(), skipUnchanged));
            } else {
                Location s = w.getSpawnLocation();
                jobs.add(new CountJob(e.name(), regionDir(e, w), s.getBlockX() >> 4, s.getBlockZ() >> 4,
                        e.minAgeSeconds(), skipUnchanged));
            }
        }
        sender.sendMessage("§6[count] §fscanning " + jobs.size() + " world(s), protect-spawn="
                + protectSpawnRadius + "...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines = new ArrayList<>();
            long grand = 0;
            for (CountJob j : jobs) {
                if (j.regionDir() == null || !j.regionDir().isDirectory()) {
                    lines.add("§7  " + j.name() + ": §8(not loaded / no region dir)");
                    continue;
                }
                try {
                    long c = countEligible(j.name(), j.regionDir(), j.spawnCx(), j.spawnCz(),
                            j.minAgeSeconds(), j.skipUnchanged());
                    grand += c;
                    lines.add("§7  " + j.name() + ": §e" + c + " §7(min-age=" + Durations.human(j.minAgeSeconds()) + ")");
                } catch (Throwable t) {
                    lines.add("§7  " + j.name() + ": §cscan failed " + t);
                }
            }
            final long g = grand;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage("§6[count] §feligible chunks:");
                lines.forEach(sender::sendMessage);
                sender.sendMessage("§6[count] §ftotal: §e" + g);
            });
        });
    }

    // ------------------------------------------------------------- manual ops

    /** Manually regenerate a rectangle of chunks now (two-phase; refuses if players are in/near it). */
    void cmdRegion(CommandSender sender, String worldName, int ax, int az, int bx, int bz) {
        World w = getWorld(worldName);
        if (w == null) {
            sender.sendMessage("§cWorld '" + worldName + "' not loaded.");
            return;
        }
        int minCx = Math.min(ax, bx);
        int maxCx = Math.max(ax, bx);
        int minCz = Math.min(az, bz);
        int maxCz = Math.max(az, bz);
        if (playersNearBox(w, minCx, minCz, maxCx, maxCz)) {
            sender.sendMessage("§cRefused: a player is in/near the region. Clear the area first.");
            return;
        }
        long cells = (long) (maxCx - minCx + 1) * (maxCz - minCz + 1);
        boolean started = runBatch(List.of(new Box(w, minCx, minCz, maxCx, maxCz)), false, null,
                () -> sender.sendMessage("§6[region] §fdone regenerating " + cells + " chunks in " + worldName + "."));
        sender.sendMessage(started
                ? "§6[region] §fregenerating " + cells + " chunks in " + worldName + " (two-phase)..."
                : "§cA regen batch is already running; try again shortly.");
    }

    // ------------------------------------------------------------- batch regen

    /**
     * Two-phase regenerate a batch of chunk rectangles as one job, pausing the
     * resident sweep while it runs. Each box: delete every chunk (skipping ones
     * with a nearby player or already loaded), flush, then reload the deleted
     * ones so they regenerate — TPS-gated and concurrency-capped. {@code onBoxDone}
     * runs after each box's chunks are regenerated; {@code onDone} at the very end.
     * When {@code recordStamps} is true, each box's regenerated chunks are stamped
     * afterwards (so the age sweep treats them as unchanged until touched again).
     * Returns false (starting nothing) if a batch is already running or empty.
     */
    boolean runBatch(List<Box> boxes, boolean recordStamps, IntConsumer onBoxDone, Runnable onDone) {
        if (busy || boxes.isEmpty()) {
            return false;
        }
        busy = true;
        new BukkitRunnable() {
            int bi = 0;
            boolean deleting = true;
            long delIdx = 0;
            final List<int[]> deleted = new ArrayList<>();
            int loadPos = 0;

            private void nextBox() {
                bi++;
                deleting = true;
                delIdx = 0;
                deleted.clear();
                loadPos = 0;
            }

            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    busy = false;
                    return;
                }
                if (bi >= boxes.size()) {
                    cancel();
                    busy = false;
                    if (onDone != null) {
                        onDone.run();
                    }
                    return;
                }
                Box box = boxes.get(bi);
                World w = box.world();
                if (w == null) {
                    if (onBoxDone != null) {
                        onBoxDone.accept(bi);
                    }
                    nextBox();
                    return;
                }
                int wc = box.maxCx() - box.minCx() + 1;
                long cells = (long) wc * (box.maxCz() - box.minCz() + 1);
                if (deleting) {
                    int n = 0;
                    int budget = perTick * 4;
                    while (n < budget && delIdx < cells) {
                        if (currentTps() < tpsPause) {
                            return;
                        }
                        int cx = box.minCx() + (int) (delIdx % wc);
                        int cz = box.minCz() + (int) (delIdx / wc);
                        delIdx++;
                        n++;
                        if (playerNear(w, cx, cz) || w.isChunkLoaded(cx, cz)) {
                            continue;
                        }
                        try {
                            VanillaRegen.deleteChunk(w, cx, cz);
                            deleted.add(new int[]{cx, cz});
                        } catch (Throwable t) {
                            plugin.getLogger().warning("batch delete failed at " + w.getName()
                                    + " chunk(" + cx + "," + cz + "): " + t);
                        }
                    }
                    if (delIdx >= cells) {
                        flushStoragesQuietly(w);
                        deleting = false;
                    }
                    return;
                }
                int dispatched = 0;
                while (dispatched < perTick && inFlight < maxConcurrent) {
                    if (loadPos >= deleted.size()) {
                        if (inFlight == 0) {
                            flushQuietly(w);
                            if (recordStamps && skipUnchanged) {
                                recordStampsForChunks(w, deleted);
                            }
                            if (onBoxDone != null) {
                                onBoxDone.accept(bi);
                            }
                            nextBox();
                        }
                        return;
                    }
                    if (currentTps() < tpsPause) {
                        return;
                    }
                    int[] c = deleted.get(loadPos++);
                    if (w.isChunkLoaded(c[0], c[1])) {
                        continue;
                    }
                    loadRegen(w, c[0], c[1], null);
                    dispatched++;
                }
            }
        }.runTaskTimer(plugin, 1L, intervalTicks);
        return true;
    }

    /** True if a player is within player-safe-radius of the box (chunk coords, inclusive). */
    boolean playersNearBox(World w, int minCx, int minCz, int maxCx, int maxCz) {
        for (Player p : w.getPlayers()) {
            int px = p.getLocation().getBlockX() >> 4;
            int pz = p.getLocation().getBlockZ() >> 4;
            if (px >= minCx - playerSafeRadius && px <= maxCx + playerSafeRadius
                    && pz >= minCz - playerSafeRadius && pz <= maxCz + playerSafeRadius) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- End dragon

    /**
     * Reset the End dragon fight's saved state so a fresh dragon can spawn again.
     * Fired after this world's age-sweep pass (which regenerates the central island
     * and removes the exit portal / end gateways) — the fight then re-derives
     * "previously killed" from those now-cleared blocks on the next player entry.
     * Only resets flags, no blocks. Skips if there is no fight here, a dragon is
     * already alive, or (unless {@code force}) a player is currently in the End.
     */
    private boolean resetEndDragon(World w, boolean force) {
        if (w == null || (!force && !w.getPlayers().isEmpty())) {
            return false;
        }
        try {
            DragonBattle db = w.getEnderDragonBattle();
            if (db == null || db.getEnderDragon() != null) {
                return false; // no fight in this world, or a dragon is already alive
            }
            db.setPreviouslyKilled(false);
            if (VanillaRegen.forceEndRescan(w)) {
                plugin.getLogger().info("End dragon fight state reset in " + w.getName()
                        + "; a fresh dragon spawns once the central island is regenerated and a player enters.");
                return true;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Dragon reset failed: " + t);
        }
        return false;
    }

    /** {@code /wr end reset}: force a dragon-fight reset on the End world(s) in the sweep list. */
    void cmdEndReset(CommandSender sender) {
        boolean any = false;
        for (WorldEntry e : worldEntries) {
            if (resetEndDragon(getWorld(e.name()), true)) {
                any = true;
            }
        }
        sender.sendMessage(any
                ? "§6[end] §fEnd dragon fight reset; a fresh dragon spawns when a player enters the End."
                : "§c[end] cannot reset: no End world loaded, a dragon is already alive, or no fight present.");
    }

    /** Manually regenerate a single chunk immediately (proactive). */
    void vanillaRegen(CommandSender sender, String worldName, int cx, int cz) {
        World w = getWorld(worldName);
        if (w == null) {
            sender.sendMessage("§cWorld '" + worldName + "' not loaded.");
            return;
        }
        if (playerNear(w, cx, cz) || w.isChunkLoaded(cx, cz)) {
            sender.sendMessage("§cChunk is loaded / a player is nearby; move away and retry.");
            return;
        }
        try {
            VanillaRegen.deleteChunk(w, cx, cz);
            VanillaRegen.flushStorages(w);
            loadRegen(w, cx, cz, () ->
                    sender.sendMessage("§6[vanillaregen] §fchunk(" + cx + "," + cz + ") regenerated."));
            sender.sendMessage("§6[vanillaregen] §fdispatched for chunk(" + cx + "," + cz + ")...");
        } catch (Throwable t) {
            sender.sendMessage("§c[vanillaregen] failed: " + t);
        }
    }

    // ------------------------------------------------------------- diagnostics

    /** Count occurrences of a material in a chunk (loads the chunk). */
    List<String> cmdProbe(String worldName, int cx, int cz, String matName) {
        List<String> out = new ArrayList<>();
        World w = getWorld(worldName);
        if (w == null) {
            out.add("§cWorld '" + worldName + "' not loaded.");
            return out;
        }
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            out.add("§cUnknown material: " + matName);
            return out;
        }
        w.getChunkAt(cx, cz);
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        int baseX = cx << 4;
        int baseZ = cz << 4;
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    if (w.getBlockAt(baseX + x, y, baseZ + z).getType() == mat) {
                        count++;
                    }
                }
            }
        }
        int hy = w.getHighestBlockYAt(baseX + 8, baseZ + 8);
        out.add(String.format("§6[probe] §f%s chunk(%d,%d): §e%d§f × %s | surface(%d,%d)=Y%d %s",
                worldName, cx, cz, count, mat.name(), baseX + 8, baseZ + 8, hy,
                w.getBlockAt(baseX + 8, hy, baseZ + 8).getType().name()));
        return out;
    }

    /** List/count entities in a chunk by type (loads the chunk). */
    List<String> cmdEntities(String worldName, int cx, int cz, String filter) {
        List<String> out = new ArrayList<>();
        World w = getWorld(worldName);
        if (w == null) {
            out.add("§cWorld '" + worldName + "' not loaded.");
            return out;
        }
        Chunk c = w.getChunkAt(cx, cz);
        Map<String, Integer> counts = new TreeMap<>();
        int n = 0;
        for (Entity e : c.getEntities()) {
            String t = e.getType().name();
            if (filter != null && !t.toLowerCase().contains(filter.toLowerCase())) {
                continue;
            }
            counts.merge(t, 1, Integer::sum);
            n++;
        }
        out.add("§6[entities] §f" + worldName + " chunk(" + cx + "," + cz + "): §e" + n + "§f entities"
                + (filter != null ? " matching '" + filter + "'" : "")
                + (counts.isEmpty() ? " §7(none)" : ""));
        counts.forEach((type, num) -> out.add("§7  " + type + " §f× " + num));
        return out;
    }

    // ---------------------------------------------------------- regen primitive

    /**
     * Load a chunk async so vanilla generates it (assuming its stored data was
     * already deleted), then unload it so the fresh chunk is saved. The callback
     * and the in-flight accounting run on the main thread.
     */
    private void loadRegen(World w, int cx, int cz, Runnable onComplete) {
        inFlight++;
        w.getChunkAtAsync(cx, cz, true, chunk -> {
            try {
                w.unloadChunk(cx, cz, true);
            } catch (Throwable ignored) {
                // best effort; the chunk unloads and saves naturally otherwise
            }
            inFlight--;
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    // ------------------------------------------------------------- sweep engine

    private void beginSweep() {
        worldIndex = 0;
        completed = 0;
        scanWorldAndRun();
    }

    private void advanceWorld() {
        // Only reset the dragon fight if this world's pass actually rebuilt the central
        // island (chunk 0,0) — that is where the exit portal lives, and clearing it is
        // what makes a fresh dragon spawn. Without this gate the reset fired every pass,
        // spamming the log (and re-scanning the fight) every cycle even when nothing in
        // the End regenerated. resetEndDragon no-ops off the End, so it self-limits too.
        if (respawnDragon && cur != null && islandRegen) {
            resetEndDragon(getWorld(cur.name()), false);
        }
        worldIndex++;
        if (worldIndex >= worldEntries.size()) {
            state = State.WAITING;
            nextSweepEpochMs = System.currentTimeMillis() + sweepIntervalMs;
            cur = null;
            regions = null;
            regionCount = 0;
            regionPos = 0;
            tileActive = false;
            targetsFile.delete();
            persist();
            plugin.getLogger().info("Full sweep complete (" + completed + " chunks regenerated). "
                    + "Next sweep in " + (sweepIntervalMs / 3600_000L) + "h.");
            return;
        }
        scanWorldAndRun();
    }

    private void scanWorldAndRun() {
        if (worldIndex >= worldEntries.size()) {
            advanceWorld();
            return;
        }
        cur = worldEntries.get(worldIndex);
        islandRegen = false; // reset per world pass, before any early-return to advanceWorld
        World w = getWorld(cur.name());
        if (w == null) {
            plugin.getLogger().warning("World '" + cur.name() + "' not loaded; skipping.");
            advanceWorld();
            return;
        }
        File rd = regionDir(cur, w);
        if (!rd.isDirectory()) {
            plugin.getLogger().warning("Region dir missing for " + cur.name() + ": "
                    + rd.getAbsolutePath() + "; skipping.");
            advanceWorld();
            return;
        }
        setSpawnFor(cur);
        scanning = true;
        final int spawnCx = curSpawnCx;
        final int spawnCz = curSpawnCz;
        final File rdFinal = rd;
        final String name = cur.name();
        final long minAge = cur.minAgeSeconds();
        final boolean skipUnchanged = this.skipUnchanged;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int[] built;
            try {
                built = listRegions(name, rdFinal, spawnCx, spawnCz, minAge, skipUnchanged);
            } catch (Throwable t) {
                plugin.getLogger().severe("Scan failed for " + name + ": " + t);
                built = EMPTY;
            }
            final int[] result = built;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                regions = result;
                regionCount = result.length / 2;
                regionPos = 0;
                tileActive = false;
                scanning = false;
                writeTargets();
                state = State.RUNNING;
                tpsPaused = false;
                persist();
                plugin.getLogger().info("Scan " + name + ": " + regionCount + " region-tiles to sweep.");
            });
        });
    }

    // -------------------------------------------------------- region scanning

    /** Enumerate {@code r.X.Z.mca} files in a directory, parsing their region coordinates. */
    private static void forEachRegionFile(File dir, RegionFileConsumer cb) throws IOException {
        File[] files = dir.listFiles((d, n) -> REGION.matcher(n).matches());
        if (files == null) {
            return;
        }
        for (File f : files) {
            Matcher m = REGION.matcher(f.getName());
            if (m.matches()) {
                cb.accept(f, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
        }
    }

    /** Read a region file's header into {@code buf}; false if the file is truncated. */
    private static boolean readHeader(File f, byte[] buf) throws IOException {
        if (f.length() < HEADER_BYTES) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            in.readFully(buf, 0, HEADER_BYTES);
        }
        return true;
    }

    /**
     * Is slot {@code i} (0..1023) present on disk, past min-age, outside the
     * spawn guard, and — when {@code skipUnchanged} is on — changed since we last
     * reset it (its live timestamp advanced past its reset stamp)? {@code stamp}
     * is the region's per-slot reset stamps (null = none recorded, i.e. never
     * reset by us, so the chunk always counts as changed and passes).
     */
    private boolean isEligible(byte[] header, int i, int rx, int rz, int spawnCx, int spawnCz,
                              long nowSec, long minAge, boolean skipUnchanged, int[] stamp) {
        int b = i * 4;
        if ((header[b] | header[b + 1] | header[b + 2] | header[b + 3]) == 0) {
            return false; // no chunk stored in this slot
        }
        if (protectSpawnRadius > 0) {
            int cx = rx * 32 + (i & 31);
            int cz = rz * 32 + (i >> 5);
            if (Math.max(Math.abs(cx - spawnCx), Math.abs(cz - spawnCz)) <= protectSpawnRadius) {
                return false;
            }
        }
        long ts = readUInt32(header, 4096 + b);
        if (minAge > 0 && ts > 0 && (nowSec - ts) < minAge) {
            return false; // touched too recently to rewild
        }
        if (skipUnchanged) {
            long reset = stamp == null ? 0L : (stamp[i] & 0xFFFFFFFFL);
            if (ts <= reset) {
                return false; // unchanged since our last reset — nothing has modified it
            }
        }
        return true;
    }

    /** Region files with at least one eligible chunk, packed [rx,rz,...] nearest-to-spawn first. */
    private int[] listRegions(String world, File regionDir, int spawnCx, int spawnCz, long minAge,
                              boolean skipUnchanged) throws IOException {
        List<long[]> regs = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        byte[] header = new byte[HEADER_BYTES];
        forEachRegionFile(regionDir, (f, rx, rz) -> {
            if (!readHeader(f, header)) {
                return;
            }
            int[] stamp = skipUnchanged ? stamps.region(world, rx, rz) : null;
            for (int i = 0; i < SLOTS; i++) {
                if (isEligible(header, i, rx, rz, spawnCx, spawnCz, now, minAge, skipUnchanged, stamp)) {
                    long dx = (long) (rx * 32 + 16) - spawnCx;
                    long dz = (long) (rz * 32 + 16) - spawnCz;
                    regs.add(new long[]{dx * dx + dz * dz, rx, rz});
                    return; // one eligible chunk is enough to include this tile
                }
            }
        });
        regs.sort(Comparator
                .comparingLong((long[] a) -> a[0])
                .thenComparingLong(a -> a[1])
                .thenComparingLong(a -> a[2]));
        int[] arr = new int[regs.size() * 2];
        for (int i = 0; i < regs.size(); i++) {
            arr[2 * i] = (int) regs.get(i)[1];
            arr[2 * i + 1] = (int) regs.get(i)[2];
        }
        return arr;
    }

    /** Eligible chunks within one region file, packed [cx,cz,...]. */
    private int[] scanTile(String world, File regionDir, int rx, int rz, int spawnCx, int spawnCz,
                           long minAge, boolean skipUnchanged) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        if (!readHeader(new File(regionDir, "r." + rx + "." + rz + ".mca"), header)) {
            return EMPTY;
        }
        long now = System.currentTimeMillis() / 1000L;
        int[] stamp = skipUnchanged ? stamps.region(world, rx, rz) : null;
        int[] buf = new int[SLOTS * 2]; // worst case: every slot eligible
        int k = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (isEligible(header, i, rx, rz, spawnCx, spawnCz, now, minAge, skipUnchanged, stamp)) {
                buf[k++] = rx * 32 + (i & 31);
                buf[k++] = rz * 32 + (i >> 5);
            }
        }
        return k == 0 ? EMPTY : Arrays.copyOf(buf, k);
    }

    /** Count eligible chunks across all region files in a world (for /wr count). */
    private long countEligible(String world, File regionDir, int spawnCx, int spawnCz, long minAge,
                               boolean skipUnchanged) throws IOException {
        long now = System.currentTimeMillis() / 1000L;
        byte[] header = new byte[HEADER_BYTES];
        long[] count = {0L};
        forEachRegionFile(regionDir, (f, rx, rz) -> {
            if (!readHeader(f, header)) {
                return;
            }
            int[] stamp = skipUnchanged ? stamps.region(world, rx, rz) : null;
            for (int i = 0; i < SLOTS; i++) {
                if (isEligible(header, i, rx, rz, spawnCx, spawnCz, now, minAge, skipUnchanged, stamp)) {
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    // ------------------------------------------------------------------- tick

    private void startTask() {
        if (task == null) {
            task = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
        }
    }

    private void tick() {
        if (busy || state == State.STOPPED || state == State.PAUSED) {
            return;
        }
        if (state == State.WAITING) {
            // Guard on !scanning: beginSweep dispatches an async region scan but leaves
            // state WAITING until it completes (only then -> RUNNING). Without this the
            // next tick would see WAITING with the timer still elapsed and fire another
            // beginSweep, stacking one redundant concurrent scan per tick until the
            // first completes. scanWorldAndRun sets scanning=true synchronously, and
            // clears it together with the RUNNING transition, so this fires exactly once.
            if (!scanning && System.currentTimeMillis() >= nextSweepEpochMs) {
                beginSweep();
            }
            return;
        }
        if (scanning || cur == null || regions == null) {
            return;
        }
        double tps = currentTps();
        if (!tpsPaused && tps < tpsPause) {
            tpsPaused = true;
        } else if (tpsPaused && tps >= tpsResume) {
            tpsPaused = false;
        }
        if (tpsPaused) {
            return;
        }
        World w = getWorld(cur.name());
        if (w == null) {
            advanceWorld();
            return;
        }
        if (!tileActive && !beginTile(w)) {
            return;
        }
        if (tileDeleting) {
            runDeletePhase(w);
        } else {
            runLoadPhase(w);
        }
    }

    /** Load the next tile's eligible chunks; true if a tile is now active for this tick. */
    private boolean beginTile(World w) {
        if (regionPos >= regionCount) {
            if (inFlight == 0) {
                advanceWorld();
            }
            return false;
        }
        int rx = regions[2 * regionPos];
        int rz = regions[2 * regionPos + 1];
        int[] chunks;
        try {
            chunks = scanTile(cur.name(), regionDir(cur, w), rx, rz, curSpawnCx, curSpawnCz,
                    cur.minAgeSeconds(), skipUnchanged);
        } catch (Throwable t) {
            plugin.getLogger().warning("Tile scan failed r." + rx + "." + rz + ": " + t);
            chunks = EMPTY;
        }
        if (chunks.length == 0) {
            regionPos++; // nothing eligible here; move on next tick
            return false;
        }
        tileChunks = chunks;
        tileN = chunks.length / 2;
        tileDeleted = new boolean[tileN];
        tileDelPos = 0;
        tileLoadPos = 0;
        tileDeleting = true;
        tileActive = true;
        return true;
    }

    private void runDeletePhase(World w) {
        int budget = perTick * 4;
        int n = 0;
        while (n < budget && tileDelPos < tileN) {
            if (currentTps() < tpsPause) {
                return;
            }
            int i = tileDelPos++;
            n++;
            int cx = tileChunks[2 * i];
            int cz = tileChunks[2 * i + 1];
            if (playerNear(w, cx, cz) || w.isChunkLoaded(cx, cz)) {
                continue; // leave undeleted; it will not be reloaded and stays eligible next sweep
            }
            try {
                VanillaRegen.deleteChunk(w, cx, cz);
                tileDeleted[i] = true;
                if (cx == 0 && cz == 0) {
                    islandRegen = true; // central island cleared -> the End dragon fight may reset
                }
                consecutiveFailures = 0;
            } catch (Throwable t) {
                consecutiveFailures++;
                plugin.getLogger().warning("Delete failed at " + cur.name()
                        + " chunk(" + cx + "," + cz + "): " + t);
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    autoPause(t);
                    return;
                }
            }
        }
        if (tileDelPos >= tileN) {
            flushStoragesQuietly(w); // durability checkpoint before the reload phase
            tileDeleting = false;
        }
    }

    private void runLoadPhase(World w) {
        int done = 0;
        while (done < perTick && inFlight < maxConcurrent && tileLoadPos < tileN) {
            if (currentTps() < tpsPause) {
                return;
            }
            int i = tileLoadPos++;
            if (!tileDeleted[i]) {
                continue; // skipped in the delete phase
            }
            int cx = tileChunks[2 * i];
            int cz = tileChunks[2 * i + 1];
            if (w.isChunkLoaded(cx, cz)) {
                continue; // already regenerated on demand
            }
            loadRegen(w, cx, cz, () -> completed++);
            done++;
        }
        if (tileLoadPos >= tileN && inFlight == 0) {
            flushQuietly(w);
            if (skipUnchanged) {
                recordResetStamps(w);
            }
            regionPos++;
            tileActive = false;
            persist();
        }
    }

    /** Stamp the chunks the resident sweep just reset in the current tile. */
    private void recordResetStamps(World w) {
        List<int[]> reset = new ArrayList<>();
        for (int i = 0; i < tileN; i++) {
            if (tileDeleted[i]) {
                reset.add(new int[]{tileChunks[2 * i], tileChunks[2 * i + 1]});
            }
        }
        recordStampsForChunks(w, reset);
    }

    /**
     * After a reset, stamp every regenerated chunk (grouped by region file, which a
     * box may span) so a later scan treats it as unchanged — and skips it — until
     * its region timestamp advances past the stamp, i.e. a player has since modified
     * it. Stamp with {@code now + STAMP_MARGIN_SECONDS}, not the region timestamp
     * read back from disk: the chunk's own post-reset save finalises a second or two
     * later (async), so a stamp at read time sits just below that save and makes the
     * chunk look changed forever.
     */
    private void recordStampsForChunks(World w, List<int[]> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        int stampTs = (int) (System.currentTimeMillis() / 1000L + STAMP_MARGIN_SECONDS);
        Map<Long, List<int[]>> byRegion = new HashMap<>();
        for (int[] c : chunks) {
            byRegion.computeIfAbsent(regionKey(c[0] >> 5, c[1] >> 5), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<Long, List<int[]>> en : byRegion.entrySet()) {
            int rx = (int) (en.getKey() >> 32);
            int rz = (int) (long) en.getKey();
            int[] freshTs = new int[SLOTS];
            boolean[] mask = new boolean[SLOTS];
            for (int[] c : en.getValue()) {
                int slot = (c[0] & 31) | ((c[1] & 31) << 5);
                mask[slot] = true;
                freshTs[slot] = stampTs;
            }
            stamps.update(w.getName(), rx, rz, freshTs, mask);
        }
    }

    /**
     * Whether any chunk in a box has changed (its block-region timestamp advanced
     * past its reset stamp) since we last reset it — the same signal the age sweep
     * uses, letting structure reset skip structures nobody has touched. Returns
     * true (reset it) when skip-unchanged is off, or when a footprint chunk has no
     * stamp yet (never reset by us). Reads region headers off disk.
     */
    boolean boxChanged(World w, int minCx, int minCz, int maxCx, int maxCz) {
        if (!skipUnchanged) {
            return true;
        }
        File dir = regionDir(w);
        Map<Long, byte[]> headers = new HashMap<>();
        for (int cz = minCz; cz <= maxCz; cz++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                int rx = cx >> 5;
                int rz = cz >> 5;
                byte[] header = headers.computeIfAbsent(regionKey(rx, rz), k -> {
                    byte[] h = new byte[HEADER_BYTES];
                    try {
                        return readHeader(new File(dir, "r." + rx + "." + rz + ".mca"), h) ? h : NO_HEADER;
                    } catch (IOException e) {
                        return NO_HEADER;
                    }
                });
                if (header == NO_HEADER) {
                    continue;
                }
                int slot = (cx & 31) | ((cz & 31) << 5);
                int b = slot * 4;
                if ((header[b] | header[b + 1] | header[b + 2] | header[b + 3]) == 0) {
                    continue; // no chunk stored here
                }
                long ts = readUInt32(header, 4096 + b);
                int[] stamp = stamps.region(w.getName(), rx, rz);
                long s = stamp == null ? 0L : (stamp[slot] & 0xFFFFFFFFL);
                if (ts > s) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** Region dir for a world by name: the configured override if any, else derived. */
    private File regionDir(World w) {
        for (WorldEntry e : worldEntries) {
            if (e.name().equals(w.getName())) {
                return regionDir(e, w);
            }
        }
        return new File(w.getWorldFolder(), "region");
    }

    private void autoPause(Throwable t) {
        state = State.PAUSED;
        pauseReason = "auto-paused after " + consecutiveFailures + " consecutive failures";
        plugin.getLogger().severe("[WorldRewild] " + pauseReason + "; last error: " + t
                + ". Fix the cause and /wr resume.");
        persist();
    }

    // ----------------------------------------------------------------- helpers

    private void flushQuietly(World w) {
        if (w == null) {
            return;
        }
        try {
            VanillaRegen.flushLevel(w);
        } catch (Throwable t) {
            plugin.getLogger().warning("Flush failed: " + t);
        }
    }

    private void flushStoragesQuietly(World w) {
        try {
            VanillaRegen.flushStorages(w);
        } catch (Throwable ignored) {
            // best effort; direct deletes are already synchronous
        }
    }

    private void setSpawnFor(WorldEntry e) {
        World w = getWorld(e.name());
        if (w != null) {
            Location s = w.getSpawnLocation();
            curSpawnCx = s.getBlockX() >> 4;
            curSpawnCz = s.getBlockZ() >> 4;
        }
    }

    /**
     * The region directory for a world: the configured {@code region-dir} override
     * if set, otherwise derived from the loaded world's folder via the Bukkit API
     * ({@code World.getWorldFolder()} resolves custom dimensions correctly, e.g.
     * {@code world_2014/dimensions/minecraft/world_2024}). Needs the world loaded.
     */
    private static File regionDir(WorldEntry e, World w) {
        return e.regionDirOverride() != null ? e.regionDirOverride() : new File(w.getWorldFolder(), "region");
    }

    private boolean playerNear(World w, int cx, int cz) {
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            int px = l.getBlockX() >> 4;
            int pz = l.getBlockZ() >> 4;
            if (Math.max(Math.abs(px - cx), Math.abs(pz - cz)) <= playerSafeRadius) {
                return true;
            }
        }
        return false;
    }

    private double currentTps() {
        double[] t = plugin.getServer().getTPS();
        return (t != null && t.length > 0) ? t[0] : 20.0;
    }

    private World getWorld(String name) {
        return name == null ? null : plugin.getServer().getWorld(name);
    }

    private static long readUInt32(byte[] a, int off) {
        return ((a[off] & 0xFFL) << 24) | ((a[off + 1] & 0xFFL) << 16)
                | ((a[off + 2] & 0xFFL) << 8) | (a[off + 3] & 0xFFL);
    }

    private String progressLine() {
        return completed + " regen this sweep, tile " + regionPos + "/" + regionCount + " in "
                + (cur != null ? cur.name() : "-") + " (world " + (worldIndex + 1) + "/" + worldEntries.size() + ")";
    }

    // -------------------------------------------------------------- persistence

    private void writeTargets() {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(targetsFile)))) {
            out.writeInt(regionCount);
            for (int i = 0; i < regionCount * 2; i++) {
                out.writeInt(regions[i]);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write targets.bin: " + e);
        }
    }

    private boolean loadTargets() {
        if (!targetsFile.exists()) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(targetsFile))) {
            int n = in.readInt();
            int[] arr = new int[n * 2];
            for (int i = 0; i < n * 2; i++) {
                arr[i] = in.readInt();
            }
            regions = arr;
            regionCount = n;
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to read targets.bin: " + e);
            return false;
        }
    }

    private void persist() {
        Properties p = new Properties();
        p.setProperty("state", state.name());
        p.setProperty("worldIndex", Integer.toString(worldIndex));
        p.setProperty("regionPos", Integer.toString(regionPos));
        p.setProperty("completed", Long.toString(completed));
        p.setProperty("nextSweepEpochMs", Long.toString(nextSweepEpochMs));
        p.setProperty("consecutiveFailures", Integer.toString(consecutiveFailures));
        p.setProperty("islandRegen", Boolean.toString(islandRegen));
        p.setProperty("pauseReason", pauseReason == null ? "" : pauseReason);
        try (var out = new FileOutputStream(stateFile)) {
            p.store(out, "WorldRewild progress");
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to persist state: " + e);
        }
    }

    private void loadState() {
        if (!stateFile.exists()) {
            return;
        }
        Properties p = new Properties();
        try (var in = new FileInputStream(stateFile)) {
            p.load(in);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load state: " + e);
            return;
        }
        try {
            state = State.valueOf(p.getProperty("state", "STOPPED"));
        } catch (IllegalArgumentException ignored) {
            state = State.STOPPED;
        }
        worldIndex = parseInt(p.getProperty("worldIndex"), 0);
        regionPos = parseInt(p.getProperty("regionPos"), 0);
        completed = parseLong(p.getProperty("completed"), 0);
        nextSweepEpochMs = parseLong(p.getProperty("nextSweepEpochMs"), 0);
        consecutiveFailures = parseInt(p.getProperty("consecutiveFailures"), 0);
        islandRegen = Boolean.parseBoolean(p.getProperty("islandRegen", "false"));
        String pr = p.getProperty("pauseReason", "");
        pauseReason = pr.isEmpty() ? null : pr;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
