package io.github.phateio.worldrewild;

import java.io.File;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import io.github.phateio.worldrewild.StructureScanner.Entry;
import io.github.phateio.worldrewild.StructureScanner.WorldDir;

/**
 * Targeted, scheduled reset of rare structures. Runs the same "regenerate a box
 * of chunks" core as the resident sweep, but chooses <i>which</i> chunks by a
 * structure registry ({@link StructureScanner}) instead of by age — the
 * "structure special case". Separately it resets the End dragon fight so the
 * dragon can be fought again — the "dragon special case" — which is a direct NMS
 * action rather than a chunk regen, because the End's central arena is kept
 * force-loaded by the fight and so cannot be deleted/regenerated in place.
 */
final class StructureReset {

    private static final Set<String> DEFAULT_TYPES = new LinkedHashSet<>(List.of(
            "mansion", "stronghold", "ancient_city", "jungle_pyramid", "desert_pyramid",
            "pillager_outpost", "bastion_remnant", "end_city"));

    private final WorldRewild plugin;
    private final RegenEngine engine;
    private final StructureScanner scanner;

    // Config snapshot.
    private boolean enabled;
    private long intervalHours;   // reset cadence; also the wall-clock alignment period
    private long rescanTicks;
    private int margin;
    private final Set<String> types = new LinkedHashSet<>();
    private String endWorld;
    private boolean respawnDragon;

    private BukkitTask resetTask;
    private BukkitTask rescanTask;
    private long lastResetEpochMs = 0;

    StructureReset(WorldRewild plugin, RegenEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.scanner = new StructureScanner(plugin);
    }

    // ------------------------------------------------------------------ config

    void reloadConfig() {
        var s = plugin.getConfig().getConfigurationSection("structure-reset");
        enabled = s == null || s.getBoolean("enabled", true);
        intervalHours = s == null ? 6L : Math.max(1L, s.getLong("interval-hours", 6L));
        long rescanHours = s == null ? 168L : Math.max(1L, s.getLong("rescan-hours", 168L));
        rescanTicks = rescanHours * 3600L * 20L;
        margin = s == null ? 0 : Math.max(0, s.getInt("footprint-margin-chunks", 0));
        types.clear();
        List<String> cfgTypes = s == null ? List.of() : s.getStringList("types");
        types.addAll(cfgTypes.isEmpty() ? DEFAULT_TYPES : cfgTypes);
        endWorld = s == null ? null : s.getString("end-central.world", null);
        respawnDragon = s == null || s.getBoolean("end-central.respawn-dragon", true);
    }

    /** The worlds to scan for structures, taken from the top-level {@code worlds} list. */
    private List<WorldDir> worldDirs() {
        File container = plugin.getServer().getWorldContainer();
        List<WorldDir> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("worlds")) {
            Object n = m.get("name");
            Object rd = m.get("region-dir");
            if (n != null && rd != null) {
                out.add(new WorldDir(n.toString(), new File(container, rd.toString())));
            }
        }
        return out;
    }

    // --------------------------------------------------------------- lifecycle

    void onEnable() {
        reloadConfig();
        scanner.load();
        if (!enabled) {
            plugin.getLogger().info("Structure reset disabled.");
            return;
        }
        // Build/refresh the registry, then do a first reset and start the timers.
        scanner.scanAsync(worldDirs(), types, scanner.neverScanned(), () -> {
            attemptReset("boot");
            startTimers();
        });
    }

    void onDisable() {
        stopTimers();
    }

    /**
     * Re-read config and re-arm the timers so interval-hours / rescan-hours /
     * enabled changes take effect without a server restart. Does not force an
     * immediate reset — startTimers() just re-aligns to the next wall-clock
     * boundary. If the plugin is being enabled from a never-scanned state, scan
     * first so the registry is populated before the next reset fires. The caller
     * must refresh the config from disk (plugin.reloadConfig()) beforehand.
     */
    void reload() {
        reloadConfig();
        stopTimers();
        if (!enabled) {
            plugin.getLogger().info("Structure reset disabled (reload).");
            return;
        }
        if (scanner.neverScanned()) {
            scanner.scanAsync(worldDirs(), types, true, this::startTimers);
        } else {
            startTimers();
        }
    }

    private void startTimers() {
        stopTimers();
        scheduleNextReset();
        rescanTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, () -> scanner.scanAsync(worldDirs(), types, false, null),
                        rescanTicks, rescanTicks);
    }

    /**
     * Schedule the next reset at the next wall-clock multiple of interval-hours
     * past local midnight (e.g. 6 -> 00:00/06:00/12:00/18:00), then re-arm itself
     * from the fired callback. Computing the delay fresh each time keeps it aligned
     * to the clock, so server-tick drift never accumulates.
     */
    private void scheduleNextReset() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime midnight = now.truncatedTo(ChronoUnit.DAYS);
        long hoursSince = Duration.between(midnight, now).toHours();
        ZonedDateTime next = midnight.plusHours((hoursSince / intervalHours + 1) * intervalHours);
        long delayTicks = Math.max(1L, Duration.between(now, next).toMillis() / 50L);
        resetTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            attemptReset("scheduled");
            scheduleNextReset();
        }, delayTicks);
        plugin.getLogger().info("Next structure reset at " + next.toLocalDateTime()
                + " (in " + String.format("%.1f", delayTicks / 72000.0) + "h).");
    }

    private void stopTimers() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
        if (rescanTask != null) {
            rescanTask.cancel();
            rescanTask = null;
        }
    }

    // ------------------------------------------------------------------ reset

    private int attemptReset(String reason) {
        return attemptReset(reason, null);
    }

    /** Regenerate every registered structure not currently occupied, and reset the End dragon. */
    private int attemptReset(String reason, String typeFilter) {
        List<Entry> entries = scanner.entries();
        List<RegenEngine.Box> boxes = new ArrayList<>();
        List<Entry> chosen = new ArrayList<>();
        for (Entry e : entries) {
            if (e.endCentral || !types.contains(e.type)
                    || (typeFilter != null && !e.type.equals(typeFilter))) {
                continue; // not a currently-configured type, or filtered out
            }
            World w = plugin.getServer().getWorld(e.world);
            if (w == null) {
                continue;
            }
            int minCx = e.minCx - margin;
            int minCz = e.minCz - margin;
            int maxCx = e.maxCx + margin;
            int maxCz = e.maxCz + margin;
            if (engine.playersNearBox(w, minCx, minCz, maxCx, maxCz)) {
                continue; // someone is there; leave it, retry next cycle
            }
            boxes.add(new RegenEngine.Box(w, minCx, minCz, maxCx, maxCz));
            chosen.add(e);
        }
        // The End dragon is a direct reset, not a chunk regen (its arena is force-loaded).
        if (typeFilter == null || "end_central".equals(typeFilter)) {
            resetEndDragon(false);
        }
        if (boxes.isEmpty()) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        boolean started = engine.runBatch(boxes,
                i -> scanner.markReset(chosen.get(i), nowMs),
                () -> {
                    lastResetEpochMs = nowMs;
                    plugin.getLogger().info("Structure reset (" + reason + "): "
                            + boxes.size() + " structure(s) regenerated.");
                });
        return started ? boxes.size() : -1;
    }

    /**
     * Reset the End dragon fight's saved state so a fresh dragon can spawn again.
     * Skips if the End is not configured/loaded, a dragon is already alive, or
     * (unless forced) a player is currently in the End.
     *
     * <p>This only resets the fight's flags (previously-killed etc.); it does not
     * touch any blocks. The fight re-derives "previously killed" from whether an
     * exit portal / end gateway exists within 8 chunks of the origin, so the dragon
     * actually respawns once the age-based sweep regenerates the (visited) central
     * island and removes those blocks — keeping End terrain on the same unified
     * sweep as every other world, with no special-case localised regen here.
     */
    private boolean resetEndDragon(boolean force) {
        if (!respawnDragon || endWorld == null) {
            return false;
        }
        World w = plugin.getServer().getWorld(endWorld);
        if (w == null || (!force && !w.getPlayers().isEmpty())) {
            return false;
        }
        try {
            DragonBattle db = w.getEnderDragonBattle();
            if (db == null) {
                return false; // no dragon fight in this world
            }
            if (db.getEnderDragon() != null) {
                return false; // a dragon is already alive
            }
            db.setPreviouslyKilled(false); // stable Bukkit API; scanState re-confirms from the portal state
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

    // --------------------------------------------------------------- commands

    List<String> cmdStatus() {
        List<String> out = new ArrayList<>();
        out.add("§6[struct] §fenabled=§e" + enabled + " §7registry=§f" + scanner.size()
                + (scanner.isScanning() ? " §c(scanning)" : "")
                + " §7margin=§f" + margin + " §7interval=§f" + intervalHours + "h");
        Map<String, Integer> counts = scanner.countsByType();
        if (counts.isEmpty()) {
            out.add("§7  (registry empty — run /wr struct scan)");
        } else {
            StringBuilder sb = new StringBuilder("§7  ");
            counts.forEach((t, n) -> sb.append("§f").append(t).append("§7=§e").append(n).append("§7 "));
            out.add(sb.toString());
        }
        return out;
    }

    void cmdScan(CommandSender sender) {
        if (scanner.isScanning()) {
            sender.sendMessage("§cA structure scan is already running.");
            return;
        }
        sender.sendMessage("§6[struct] §fscanning region files for structures...");
        scanner.scanAsync(worldDirs(), types, scanner.neverScanned(),
                () -> sender.sendMessage("§6[struct] §fscan done: registry=§e" + scanner.size()));
    }

    void cmdReset(CommandSender sender, String typeFilter) {
        int n = attemptReset("manual", typeFilter);
        if (n < 0) {
            sender.sendMessage("§cA regen batch is already running; try again shortly.");
        } else if (n == 0) {
            sender.sendMessage("§6[struct] §fnothing to regenerate"
                    + (typeFilter != null ? " for type '" + typeFilter + "'" : "")
                    + " (registry empty, worlds not loaded, or all occupied).");
        } else {
            sender.sendMessage("§6[struct] §fregenerating §e" + n + "§f structure(s)"
                    + (typeFilter != null ? " of type '" + typeFilter + "'" : "") + "...");
        }
    }

    void cmdEndReset(CommandSender sender) {
        if (resetEndDragon(false)) {
            sender.sendMessage("§6[end] §fEnd dragon fight reset; a fresh dragon spawns when a player enters the End.");
        } else {
            sender.sendMessage("§c[end] cannot reset: End not configured/loaded, a dragon is already alive, "
                    + "or a player is in the End.");
        }
    }
}
