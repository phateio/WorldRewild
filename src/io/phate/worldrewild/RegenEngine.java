package io.phate.worldrewild;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Resident, perpetual resource-world regenerator.
 *
 * <p>Sweeps a list of worlds in order. For each world it enumerates every
 * generated chunk that has not been accessed for at least {@code min-age-days}
 * (via the region file's per-chunk timestamp), then proactively regenerates
 * each one — delete its stored data (block/entity/POI) through Paper's Moonrise
 * chunk system, then load it asynchronously so the server regenerates it from
 * scratch (terrain, structures, structure mobs) with the current generator, and
 * unload it so the fresh chunk is saved. Regeneration is TPS-gated and bounded
 * by a concurrency cap so players never trigger mass on-demand generation.
 *
 * <p>After all worlds are swept it waits {@code rescan-interval-hours} and
 * repeats, so the resource world stays on a rolling refresh cycle. A manual
 * {@code /wr region} command regenerates an arbitrary rectangle on demand.
 */
public final class RegenEngine {

    enum State { STOPPED, RUNNING, PAUSED, WAITING }

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static final class WorldEntry {
        final String name;
        final File regionDir;

        WorldEntry(String name, File regionDir) {
            this.name = name;
            this.regionDir = regionDir;
        }
    }

    private final WorldRewildPlugin plugin;

    // Config snapshot.
    private final List<WorldEntry> worldEntries = new ArrayList<>();
    private double tpsPause;
    private double tpsResume;
    private long intervalTicks;
    private int perTick;
    private int maxConcurrent;
    private int playerSafeRadius;
    private int protectSpawnRadius;
    private int maxConsecutiveFailures;
    private int flushEvery;
    private long minAgeSeconds;
    private long rescanIntervalMs;
    private boolean enabled;
    private boolean autoResume;

    // Per-region timestamp-block cache for the age re-check.
    private long tsCacheRegion = Long.MIN_VALUE;
    private File tsCacheDir;
    private byte[] tsCacheBlock;

    // Runtime.
    private volatile State state = State.STOPPED;
    private volatile boolean scanning = false;
    private boolean tpsPaused = false;
    private int worldIndex = 0;
    private WorldEntry cur;
    private int[] targets;          // current world, packed pairs [cx0,cz0,cx1,cz1,...]
    private int total = 0;
    private int pos = 0;
    private long completed = 0;     // chunks regenerated this sweep (across worlds)
    private int inFlight = 0;       // async regens in progress (main-thread only)
    private long nextSweepEpochMs = 0;
    private boolean manualActive = false;
    private BukkitTask task;
    private int sincePersist = 0;
    private int sinceFlush = 0;
    private int consecutiveFailures = 0;
    private String pauseReason = null;

    private final File targetsFile;
    private final File stateFile;

    RegenEngine(WorldRewildPlugin plugin) {
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
        if (worldEntries.isEmpty()) {
            for (String n : new String[]{"world_2024", "world_2024_nether", "world_2024_the_end"}) {
                worldEntries.add(new WorldEntry(n,
                        new File(container, "world_2014/dimensions/minecraft/" + n + "/region")));
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
        flushEvery = Math.max(1, c.getInt("flush-every", 128));
        minAgeSeconds = Math.max(0, c.getInt("min-age-days", 30)) * 86400L;
        rescanIntervalMs = Math.max(1L, c.getLong("rescan-interval-hours", 24L)) * 3600_000L;
        enabled = c.getBoolean("enabled", true);
        autoResume = c.getBoolean("auto-resume", true);
    }

    // ------------------------------------------------------------- lifecycle

    void onEnable() {
        reloadConfig();
        loadState();
        if (state == State.RUNNING || state == State.PAUSED) {
            if (autoResume && worldIndex < worldEntries.size() && loadTargets()) {
                cur = worldEntries.get(worldIndex);
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
            flushQuietly(getWorld(cur.name));
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
            flushQuietly(getWorld(cur.name));
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
        targets = null;
        total = 0;
        pos = 0;
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
        String worldInfo = cur != null ? cur.name + " (" + (worldIndex + 1) + "/" + worldEntries.size() + ")" : "-";
        out.add("§6[WorldRewild] §fstate=§e" + state + (scanning ? " §c(scanning)" : "")
                + (manualActive ? " §c(manual running)" : ""));
        out.add("§7world=§f" + worldInfo + " §7tps-gate=§f" + tpsPause + "/" + tpsResume
                + " §7tps-paused=§f" + tpsPaused);
        out.add("§7current TPS=§f" + String.format("%.2f", currentTps())
                + " §7interval=§f" + intervalTicks + "t §7per-tick=§f" + perTick
                + " §7in-flight=§f" + inFlight + "/" + maxConcurrent + " §7min-age=§f" + (minAgeSeconds / 86400L) + "d");
        out.add("§7progress: §a" + completed + "§7 regen this sweep, cursor §f" + pos + "§7/§f" + total
                + (total > 0 ? " §7(" + String.format("%.2f", 100.0 * pos / total) + "%)" : ""));
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

    /** Dry scan: report eligible chunk counts per configured world, without starting. */
    void cmdCount(CommandSender sender) {
        if (scanning) {
            sender.sendMessage("§cA scan is in progress.");
            return;
        }
        List<Object[]> jobs = new ArrayList<>();
        for (WorldEntry e : worldEntries) {
            World w = getWorld(e.name);
            if (w == null) {
                jobs.add(new Object[]{e.name, null, 0, 0});
            } else {
                Location s = w.getSpawnLocation();
                jobs.add(new Object[]{e.name, e.regionDir, s.getBlockX() >> 4, s.getBlockZ() >> 4});
            }
        }
        sender.sendMessage("§6[count] §fscanning " + jobs.size() + " world(s), min-age="
                + (minAgeSeconds / 86400L) + "d, protect-spawn=" + protectSpawnRadius + "...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines = new ArrayList<>();
            long grand = 0;
            for (Object[] j : jobs) {
                String name = (String) j[0];
                File rd = (File) j[1];
                if (rd == null || !rd.isDirectory()) {
                    lines.add("§7  " + name + ": §8(not loaded / no region dir)");
                    continue;
                }
                try {
                    int c = scanRegions(rd, (int) j[2], (int) j[3]).length / 2;
                    grand += c;
                    lines.add("§7  " + name + ": §e" + c);
                } catch (Throwable t) {
                    lines.add("§7  " + name + ": §cscan failed " + t);
                }
            }
            final long g = grand;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage("§6[count] §feligible chunks (min-age=" + (minAgeSeconds / 86400L) + "d):");
                lines.forEach(sender::sendMessage);
                sender.sendMessage("§6[count] §ftotal: §e" + g);
            });
        });
    }

    // ------------------------------------------------------------- manual ops

    /** Manually regenerate a rectangle of chunks in a world (force; refuses if players present). */
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
        sender.sendMessage("§6[region] §fregenerating " + cells + " chunks in " + worldName
                + " (" + minCx + "," + minCz + ")..(" + maxCx + "," + maxCz + "), pausing resident...");
        manualActive = true;
        new BukkitRunnable() {
            long idx = 0;
            long done = 0;
            long skipped = 0;

            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    manualActive = false;
                    return;
                }
                int dispatched = 0;
                while (dispatched < perTick && inFlight < maxConcurrent) {
                    if (idx >= cells) {
                        if (inFlight == 0) {
                            cancel();
                            manualActive = false;
                            flushQuietly(w);
                            sender.sendMessage("§6[region] §fdone: " + done + " regenerated, " + skipped + " skipped.");
                        }
                        return;
                    }
                    if (currentTps() < tpsPause) {
                        return; // wait for TPS to recover
                    }
                    int cx = minCx + (int) (idx % wc);
                    int cz = minCz + (int) (idx / wc);
                    idx++;
                    if (playerNear(w, cx, cz) || w.isChunkLoaded(cx, cz)) {
                        skipped++;
                        continue;
                    }
                    try {
                        dispatchRegen(w, cx, cz, () -> {
                        });
                        done++;
                        dispatched++;
                    } catch (Throwable t) {
                        skipped++;
                        plugin.getLogger().warning("region regen failed at " + worldName
                                + " chunk(" + cx + "," + cz + "): " + t);
                    }
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
            dispatchRegen(w, cx, cz, () ->
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
        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matName);
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
        org.bukkit.Chunk c = w.getChunkAt(cx, cz);
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        int n = 0;
        for (org.bukkit.entity.Entity e : c.getEntities()) {
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
        for (java.util.Map.Entry<String, Integer> en : counts.entrySet()) {
            out.add("§7  " + en.getKey() + " §f× " + en.getValue());
        }
        return out;
    }

    // ---------------------------------------------------------- regen primitive

    /**
     * Proactively regenerate one chunk: delete its stored data, load it async so
     * vanilla regenerates it, then unload it so the fresh chunk is saved. The
     * caller must have verified the chunk is unloaded and clear of players.
     */
    private void dispatchRegen(World w, int cx, int cz, Runnable onComplete) {
        try {
            VanillaRegen.deleteChunk(w, cx, cz);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        inFlight++;
        w.getChunkAtAsync(cx, cz, true, chunk -> {
            try {
                w.unloadChunk(cx, cz, true);
            } catch (Throwable ignored) {
                // save/unload best-effort; it will unload naturally otherwise
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
            targets = null;
            total = 0;
            pos = 0;
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
        World w = getWorld(cur.name);
        if (w == null) {
            plugin.getLogger().warning("World '" + cur.name + "' not loaded; skipping.");
            advanceWorld();
            return;
        }
        if (!cur.regionDir.isDirectory()) {
            plugin.getLogger().warning("Region dir missing for " + cur.name + ": "
                    + cur.regionDir.getAbsolutePath() + "; skipping.");
            advanceWorld();
            return;
        }
        scanning = true;
        Location spawn = w.getSpawnLocation();
        final int spawnCx = spawn.getBlockX() >> 4;
        final int spawnCz = spawn.getBlockZ() >> 4;
        final File rd = cur.regionDir;
        final String name = cur.name;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int[] built;
            try {
                built = scanRegions(rd, spawnCx, spawnCz);
            } catch (Throwable t) {
                plugin.getLogger().severe("Scan failed for " + name + ": " + t);
                built = new int[0];
            }
            final int[] result = built;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                targets = result;
                total = result.length / 2;
                pos = 0;
                scanning = false;
                writeTargets();
                state = State.RUNNING;
                tpsPaused = false;
                persist();
                plugin.getLogger().info("Scan " + name + ": " + total + " chunks eligible; sweeping.");
            });
        });
    }

    private int[] scanRegions(File regionDir, int spawnCx, int spawnCz) throws IOException {
        File[] files = regionDir.listFiles((d, n) -> REGION.matcher(n).matches());
        if (files == null) {
            return new int[0];
        }
        List<long[]> chunks = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        byte[] header = new byte[8192];
        for (File f : files) {
            Matcher m = REGION.matcher(f.getName());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1));
            int rz = Integer.parseInt(m.group(2));
            if (f.length() < 8192) continue;
            try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
                in.readFully(header);
            }
            for (int i = 0; i < 1024; i++) {
                int b = i * 4;
                if ((header[b] | header[b + 1] | header[b + 2] | header[b + 3]) == 0) {
                    continue;
                }
                int cx = rx * 32 + (i & 31);
                int cz = rz * 32 + (i >> 5);
                if (protectSpawnRadius > 0
                        && Math.max(Math.abs(cx - spawnCx), Math.abs(cz - spawnCz)) <= protectSpawnRadius) {
                    continue;
                }
                if (minAgeSeconds > 0) {
                    long ts = readUInt32(header, 4096 + b);
                    if (ts > 0 && (now - ts) < minAgeSeconds) {
                        continue;
                    }
                }
                long distSq = (long) (cx - spawnCx) * (cx - spawnCx)
                        + (long) (cz - spawnCz) * (cz - spawnCz);
                chunks.add(new long[]{distSq, cx, cz});
            }
        }
        chunks.sort(Comparator
                .comparingLong((long[] a) -> a[0])
                .thenComparingLong(a -> a[1])
                .thenComparingLong(a -> a[2]));
        int[] arr = new int[chunks.size() * 2];
        for (int i = 0; i < chunks.size(); i++) {
            arr[2 * i] = (int) chunks.get(i)[1];
            arr[2 * i + 1] = (int) chunks.get(i)[2];
        }
        return arr;
    }

    // ------------------------------------------------------------------- tick

    private void startTask() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
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
        // RUNNING
        if (scanning || cur == null || targets == null) {
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
        World w = getWorld(cur.name);
        if (w == null) {
            advanceWorld();
            return;
        }

        int done = 0;
        int iterations = 0;
        int maxIterations = perTick * 64 + 256; // bound skip-scanning per tick
        while (done < perTick && inFlight < maxConcurrent && pos < total && iterations < maxIterations) {
            iterations++;
            int cx = targets[2 * pos];
            int cz = targets[2 * pos + 1];
            pos++;
            // Skipped chunks are simply left for the next daily sweep.
            if (playerNear(w, cx, cz) || w.isChunkLoaded(cx, cz) || tooRecent(cur.regionDir, cx, cz)) {
                continue;
            }
            try {
                dispatchRegen(w, cx, cz, () -> completed++);
                consecutiveFailures = 0;
            } catch (Throwable t) {
                consecutiveFailures++;
                plugin.getLogger().warning("Regen failed at " + cur.name + " chunk(" + cx + "," + cz + "): " + t);
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    state = State.PAUSED;
                    pauseReason = "auto-paused after " + consecutiveFailures + " consecutive failures";
                    plugin.getLogger().severe("[WorldRewild] " + pauseReason + "; last error: " + t
                            + ". Fix the cause and /wr resume.");
                    persist();
                    return;
                }
                continue;
            }
            done++;
        }

        sinceFlush += done;
        if (sinceFlush >= flushEvery) {
            flushQuietly(w);
            sinceFlush = 0;
        }
        sincePersist += done;
        if (sincePersist >= 64) {
            persist();
            sincePersist = 0;
        }

        if (pos >= total && inFlight == 0) {
            flushQuietly(w);
            plugin.getLogger().info("Sweep of " + cur.name + " done.");
            advanceWorld();
        }
    }

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

    // --------------------------------------------------------------- age check

    private boolean tooRecent(File regionDir, int cx, int cz) {
        if (minAgeSeconds <= 0) {
            return false;
        }
        long ts = chunkLastSaved(regionDir, cx, cz);
        if (ts <= 0) {
            return false;
        }
        return (System.currentTimeMillis() / 1000L - ts) < minAgeSeconds;
    }

    private long chunkLastSaved(File regionDir, int cx, int cz) {
        int rx = cx >> 5;
        int rz = cz >> 5;
        long key = ((long) rx << 32) | (rz & 0xffffffffL);
        if (key != tsCacheRegion || !regionDir.equals(tsCacheDir)) {
            tsCacheRegion = key;
            tsCacheDir = regionDir;
            tsCacheBlock = null;
            File f = new File(regionDir, "r." + rx + "." + rz + ".mca");
            if (f.isFile() && f.length() >= 8192) {
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    raf.seek(4096);
                    byte[] block = new byte[4096];
                    raf.readFully(block);
                    tsCacheBlock = block;
                } catch (IOException ignored) {
                    tsCacheBlock = null;
                }
            }
        }
        if (tsCacheBlock == null) {
            return 0;
        }
        return readUInt32(tsCacheBlock, ((cx & 31) + (cz & 31) * 32) * 4);
    }

    private static long readUInt32(byte[] a, int off) {
        return ((a[off] & 0xFFL) << 24) | ((a[off + 1] & 0xFFL) << 16)
                | ((a[off + 2] & 0xFFL) << 8) | (a[off + 3] & 0xFFL);
    }

    // -------------------------------------------------------------- persistence

    private void writeTargets() {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(targetsFile)))) {
            out.writeInt(total);
            for (int i = 0; i < total * 2; i++) {
                out.writeInt(targets[i]);
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
            targets = arr;
            total = n;
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
        p.setProperty("pos", Integer.toString(pos));
        p.setProperty("total", Integer.toString(total));
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
        pos = parseInt(p.getProperty("pos"), 0);
        total = parseInt(p.getProperty("total"), 0);
        completed = parseLong(p.getProperty("completed"), 0);
        nextSweepEpochMs = parseLong(p.getProperty("nextSweepEpochMs"), 0);
        consecutiveFailures = parseInt(p.getProperty("consecutiveFailures"), 0);
        String pr = p.getProperty("pauseReason", "");
        pauseReason = pr.isEmpty() ? null : pr;
    }

    // ----------------------------------------------------------------- helpers

    private String progressLine() {
        return completed + " regen this sweep, " + pos + "/" + total + " in "
                + (cur != null ? cur.name : "-") + " (world " + (worldIndex + 1) + "/" + worldEntries.size() + ")";
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
