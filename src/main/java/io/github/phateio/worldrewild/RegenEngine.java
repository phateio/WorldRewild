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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
 * A thin biome-only rim on tile edges that still border old chunks heals on the
 * next rolling re-sweep, once those neighbours have themselves been converted.
 *
 * <p>Regeneration is TPS-gated and bounded by a concurrency cap so players never
 * trigger mass on-demand generation. After all worlds are swept it waits
 * {@code rescan-interval-hours} and repeats. A manual {@code /wr region} command
 * regenerates an arbitrary rectangle on demand (also two-phase).
 */
public final class RegenEngine {

    enum State { STOPPED, RUNNING, PAUSED, WAITING }

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int HEADER_BYTES = 8192; // 4 KiB location table + 4 KiB timestamps
    private static final int SLOTS = 1024;        // 32x32 chunk slots per region file
    private static final int[] EMPTY = new int[0];

    private record WorldEntry(String name, File regionDir) {}

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
    private long minAgeSeconds;
    private long rescanIntervalMs;
    private boolean enabled;
    private boolean autoResume;

    // Runtime state.
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
    private boolean manualActive = false;
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

    RegenEngine(WorldRewild plugin) {
        this.plugin = plugin;
        this.targetsFile = new File(plugin.getDataFolder(), "targets.bin");
        this.stateFile = new File(plugin.getDataFolder(), "state.properties");
    }

    // ------------------------------------------------------------------ config

    void reloadConfig() {
        plugin.reloadConfig();
        var c = plugin.getConfig();
        File container = plugin.getServer().getWorldContainer();
        worldEntries.clear();
        for (Map<?, ?> m : c.getMapList("worlds")) {
            Object n = m.get("name");
            Object rd = m.get("region-dir");
            if (n != null && rd != null) {
                worldEntries.add(new WorldEntry(n.toString(), new File(container, rd.toString())));
            }
        }
        tpsPause = c.getDouble("tps-pause", 18.0);
        tpsResume = c.getDouble("tps-resume", 18.5);
        intervalTicks = Math.max(1L, c.getLong("interval-ticks", 2L));
        perTick = Math.max(1, c.getInt("per-tick", 4));
        maxConcurrent = Math.max(1, c.getInt("max-concurrent-regens", 4));
        playerSafeRadius = Math.max(0, c.getInt("player-safe-radius-chunks", 4));
        protectSpawnRadius = Math.max(0, c.getInt("protect-spawn-radius-chunks", 0));
        maxConsecutiveFailures = Math.max(1, c.getInt("max-consecutive-failures", 5));
        // min-age-seconds (when >= 0) overrides min-age-days, for sub-day tuning/testing.
        long secs = c.getLong("min-age-seconds", -1L);
        minAgeSeconds = secs >= 0 ? secs : Math.max(0, c.getInt("min-age-days", 30)) * 86400L;
        rescanIntervalMs = Math.max(1L, c.getLong("rescan-interval-hours", 24L)) * 3600_000L;
        enabled = c.getBoolean("enabled", true);
        autoResume = c.getBoolean("auto-resume", true);
    }

    // --------------------------------------------------------------- lifecycle

    void onEnable() {
        reloadConfig();
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
        return "Progress cleared. Next /wr start rescans from scratch.";
    }

    List<String> cmdStatus() {
        List<String> out = new ArrayList<>();
        String worldInfo = cur != null ? cur.name() + " (" + (worldIndex + 1) + "/" + worldEntries.size() + ")" : "-";
        out.add("§6[WorldRewild] §fstate=§e" + state + (scanning ? " §c(scanning)" : "")
                + (manualActive ? " §c(manual running)" : ""));
        out.add("§7world=§f" + worldInfo + " §7tps-gate=§f" + tpsPause + "/" + tpsResume
                + " §7tps-paused=§f" + tpsPaused);
        out.add("§7current TPS=§f" + String.format("%.2f", currentTps())
                + " §7interval=§f" + intervalTicks + "t §7per-tick=§f" + perTick
                + " §7in-flight=§f" + inFlight + "/" + maxConcurrent + " §7min-age=§f" + ageStr());
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

    private record CountJob(String name, File regionDir, int spawnCx, int spawnCz) {}

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
                jobs.add(new CountJob(e.name(), null, 0, 0));
            } else {
                Location s = w.getSpawnLocation();
                jobs.add(new CountJob(e.name(), e.regionDir(), s.getBlockX() >> 4, s.getBlockZ() >> 4));
            }
        }
        sender.sendMessage("§6[count] §fscanning " + jobs.size() + " world(s), min-age="
                + ageStr() + ", protect-spawn=" + protectSpawnRadius + "...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines = new ArrayList<>();
            long grand = 0;
            for (CountJob j : jobs) {
                if (j.regionDir() == null || !j.regionDir().isDirectory()) {
                    lines.add("§7  " + j.name() + ": §8(not loaded / no region dir)");
                    continue;
                }
                try {
                    long c = countEligible(j.regionDir(), j.spawnCx(), j.spawnCz());
                    grand += c;
                    lines.add("§7  " + j.name() + ": §e" + c);
                } catch (Throwable t) {
                    lines.add("§7  " + j.name() + ": §cscan failed " + t);
                }
            }
            final long g = grand;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage("§6[count] §feligible chunks (min-age=" + ageStr() + "):");
                lines.forEach(sender::sendMessage);
                sender.sendMessage("§6[count] §ftotal: §e" + g);
            });
        });
    }

    // ------------------------------------------------------------- manual ops

    /** Manually regenerate a rectangle of chunks (force; refuses if players are in/near it). */
    void cmdRegion(CommandSender sender, String worldName, int ax, int az, int bx, int bz) {
        World w = getWorld(worldName);
        if (w == null) {
            sender.sendMessage("§cWorld '" + worldName + "' not loaded.");
            return;
        }
        if (manualActive) {
            sender.sendMessage("§cA manual region job is already running.");
            return;
        }
        final int minCx = Math.min(ax, bx);
        final int maxCx = Math.max(ax, bx);
        final int minCz = Math.min(az, bz);
        final int maxCz = Math.max(az, bz);
        for (Player p : w.getPlayers()) {
            int px = p.getLocation().getBlockX() >> 4;
            int pz = p.getLocation().getBlockZ() >> 4;
            if (px >= minCx - playerSafeRadius && px <= maxCx + playerSafeRadius
                    && pz >= minCz - playerSafeRadius && pz <= maxCz + playerSafeRadius) {
                sender.sendMessage("§cRefused: player " + p.getName()
                        + " is in/near the region. Clear the area first.");
                return;
            }
        }
        final int wc = maxCx - minCx + 1;
        final long cells = (long) wc * (maxCz - minCz + 1);
        sender.sendMessage("§6[region] §f" + cells + " chunks in " + worldName
                + " (" + minCx + "," + minCz + ")..(" + maxCx + "," + maxCz + "), phase 1/2: deleting...");
        manualActive = true;
        // Delete the whole rectangle first, then regenerate, so interior chunks
        // regenerate free of old neighbours (see class doc).
        new BukkitRunnable() {
            long delIdx = 0;
            long loadIdx = 0;
            long deleted = 0;
            long done = 0;
            long skipped = 0;
            boolean deleting = true;

            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    manualActive = false;
                    return;
                }
                if (deleting) {
                    int n = 0;
                    while (n < perTick * 4 && delIdx < cells) {
                        if (currentTps() < tpsPause) {
                            return;
                        }
                        int cx = minCx + (int) (delIdx % wc);
                        int cz = minCz + (int) (delIdx / wc);
                        delIdx++;
                        n++;
                        if (w.isChunkLoaded(cx, cz)) {
                            continue; // a loaded chunk cannot be safely deleted; skip in load phase too
                        }
                        try {
                            VanillaRegen.deleteChunk(w, cx, cz);
                            deleted++;
                        } catch (Throwable t) {
                            plugin.getLogger().warning("region delete failed at " + worldName
                                    + " chunk(" + cx + "," + cz + "): " + t);
                        }
                    }
                    if (delIdx >= cells) {
                        flushStoragesQuietly(w);
                        deleting = false;
                        sender.sendMessage("§6[region] §fdeleted " + deleted + "; phase 2/2: regenerating...");
                    }
                    return;
                }
                int dispatched = 0;
                while (dispatched < perTick && inFlight < maxConcurrent) {
                    if (loadIdx >= cells) {
                        if (inFlight == 0) {
                            cancel();
                            manualActive = false;
                            flushQuietly(w);
                            sender.sendMessage("§6[region] §fdone: " + done + " regenerated, " + skipped + " skipped.");
                        }
                        return;
                    }
                    if (currentTps() < tpsPause) {
                        return;
                    }
                    int cx = minCx + (int) (loadIdx % wc);
                    int cz = minCz + (int) (loadIdx / wc);
                    loadIdx++;
                    if (playerNear(w, cx, cz) || w.isChunkLoaded(cx, cz)) {
                        skipped++;
                        continue;
                    }
                    loadRegen(w, cx, cz, null);
                    done++;
                    dispatched++;
                }
            }
        }.runTaskTimer(plugin, 1L, intervalTicks);
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
        worldIndex++;
        if (worldIndex >= worldEntries.size()) {
            state = State.WAITING;
            nextSweepEpochMs = System.currentTimeMillis() + rescanIntervalMs;
            cur = null;
            regions = null;
            regionCount = 0;
            regionPos = 0;
            tileActive = false;
            targetsFile.delete();
            persist();
            plugin.getLogger().info("Full sweep complete (" + completed + " chunks regenerated). "
                    + "Next sweep in " + (rescanIntervalMs / 3600_000L) + "h.");
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
        World w = getWorld(cur.name());
        if (w == null) {
            plugin.getLogger().warning("World '" + cur.name() + "' not loaded; skipping.");
            advanceWorld();
            return;
        }
        if (!cur.regionDir().isDirectory()) {
            plugin.getLogger().warning("Region dir missing for " + cur.name() + ": "
                    + cur.regionDir().getAbsolutePath() + "; skipping.");
            advanceWorld();
            return;
        }
        setSpawnFor(cur);
        scanning = true;
        final int spawnCx = curSpawnCx;
        final int spawnCz = curSpawnCz;
        final File rd = cur.regionDir();
        final String name = cur.name();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int[] built;
            try {
                built = listRegions(rd, spawnCx, spawnCz);
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

    /** Is slot {@code i} (0..1023) present on disk, past min-age, and outside the spawn guard? */
    private boolean isEligible(byte[] header, int i, int rx, int rz, int spawnCx, int spawnCz, long nowSec) {
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
        if (minAgeSeconds > 0) {
            long ts = readUInt32(header, 4096 + b);
            if (ts > 0 && (nowSec - ts) < minAgeSeconds) {
                return false;
            }
        }
        return true;
    }

    /** Region files with at least one eligible chunk, packed [rx,rz,...] nearest-to-spawn first. */
    private int[] listRegions(File regionDir, int spawnCx, int spawnCz) throws IOException {
        List<long[]> regs = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        byte[] header = new byte[HEADER_BYTES];
        forEachRegionFile(regionDir, (f, rx, rz) -> {
            if (!readHeader(f, header)) {
                return;
            }
            for (int i = 0; i < SLOTS; i++) {
                if (isEligible(header, i, rx, rz, spawnCx, spawnCz, now)) {
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
    private int[] scanTile(File regionDir, int rx, int rz, int spawnCx, int spawnCz) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        if (!readHeader(new File(regionDir, "r." + rx + "." + rz + ".mca"), header)) {
            return EMPTY;
        }
        long now = System.currentTimeMillis() / 1000L;
        int[] buf = new int[SLOTS * 2]; // worst case: every slot eligible
        int k = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (isEligible(header, i, rx, rz, spawnCx, spawnCz, now)) {
                buf[k++] = rx * 32 + (i & 31);
                buf[k++] = rz * 32 + (i >> 5);
            }
        }
        return k == 0 ? EMPTY : Arrays.copyOf(buf, k);
    }

    /** Count eligible chunks across all region files in a world (for /wr count). */
    private long countEligible(File regionDir, int spawnCx, int spawnCz) throws IOException {
        long now = System.currentTimeMillis() / 1000L;
        byte[] header = new byte[HEADER_BYTES];
        long[] count = {0L};
        forEachRegionFile(regionDir, (f, rx, rz) -> {
            if (!readHeader(f, header)) {
                return;
            }
            for (int i = 0; i < SLOTS; i++) {
                if (isEligible(header, i, rx, rz, spawnCx, spawnCz, now)) {
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
        if (manualActive || state == State.STOPPED || state == State.PAUSED) {
            return;
        }
        if (state == State.WAITING) {
            if (System.currentTimeMillis() >= nextSweepEpochMs) {
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
            chunks = scanTile(cur.regionDir(), rx, rz, curSpawnCx, curSpawnCz);
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
            regionPos++;
            tileActive = false;
            persist();
        }
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

    private String ageStr() {
        if (minAgeSeconds >= 86400) return (minAgeSeconds / 86400L) + "d";
        if (minAgeSeconds >= 3600) return (minAgeSeconds / 3600L) + "h";
        if (minAgeSeconds >= 60) return (minAgeSeconds / 60L) + "m";
        return minAgeSeconds + "s";
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
