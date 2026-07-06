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
    private long intervalSeconds; // reset cadence; also the wall-clock alignment period
    private long rescanTicks;
    private int margin;
    private final Set<String> types = new LinkedHashSet<>();

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
        intervalSeconds = s == null ? 21600L : Durations.secondsOr(s.get("interval"), 21600L); // 6h
        long rescanSec = s == null ? 604800L : Durations.secondsOr(s.get("rescan"), 604800L); // 7d
        rescanTicks = rescanSec * 20L;
        margin = s == null ? 0 : Math.max(0, s.getInt("footprint-margin-chunks", 0));
        types.clear();
        List<String> cfgTypes = s == null ? List.of() : s.getStringList("types");
        types.addAll(cfgTypes.isEmpty() ? DEFAULT_TYPES : cfgTypes);
    }

    /**
     * The worlds to scan for structures, taken from the top-level {@code worlds}
     * list. The region directory is the configured {@code region-dir} override if
     * set, else derived from the loaded world's folder (a world with no override
     * that is not loaded is skipped — it cannot be resolved).
     */
    private List<WorldDir> worldDirs() {
        File container = plugin.getServer().getWorldContainer();
        List<WorldDir> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("worlds")) {
            Object n = m.get("name");
            if (n == null) {
                continue;
            }
            String name = n.toString();
            Object rd = m.get("region-dir");
            File dir;
            if (rd != null) {
                dir = new File(container, rd.toString());
            } else {
                World w = plugin.getServer().getWorld(name);
                if (w == null) {
                    continue; // no override and world not loaded — can't derive its region dir
                }
                dir = new File(w.getWorldFolder(), "region");
            }
            out.add(new WorldDir(name, dir));
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
     * Re-read config and re-arm the timers so interval / rescan / enabled changes
     * take effect without a server restart. Does not force an
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
     * Schedule the next reset at the next wall-clock multiple of the interval past
     * local midnight (e.g. 6h -> 00:00/06:00/12:00/18:00), then re-arm itself from
     * the fired callback. Computing the delay fresh each time keeps it aligned to
     * the clock, so server-tick drift never accumulates.
     */
    private void scheduleNextReset() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime midnight = now.truncatedTo(ChronoUnit.DAYS);
        long secsSince = Duration.between(midnight, now).getSeconds();
        ZonedDateTime next = midnight.plusSeconds((secsSince / intervalSeconds + 1) * intervalSeconds);
        // A tick-scheduled callback can wake a hair before its target instant: the
        // tick countdown is anchored to the tick's start, but `now` is sampled partway
        // into that tick. Without this guard the recomputed `next` would be that same
        // boundary, and we would re-fire (and re-reset) every tick until the wall clock
        // finally crosses it. Treat a sub-second gap as "already at the boundary" and
        // aim past it, so each boundary fires exactly once.
        if (Duration.between(now, next).toMillis() < 1000L) {
            next = next.plusSeconds(intervalSeconds);
        }
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
        return attemptReset(reason, null, true);
    }

    /**
     * Regenerate registered structures and reset the End dragon. When {@code gate}
     * is true (boot/scheduled) a structure is skipped unless a footprint chunk has
     * changed since its last reset (honours skip-unchanged-chunks); a manual reset
     * passes false to force every matching structure regardless.
     */
    private int attemptReset(String reason, String typeFilter, boolean gate) {
        List<Entry> entries = scanner.entries();
        List<RegenEngine.Box> boxes = new ArrayList<>();
        List<Entry> chosen = new ArrayList<>();
        for (Entry e : entries) {
            if (!types.contains(e.type) || (typeFilter != null && !e.type.equals(typeFilter))) {
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
            if (gate && !engine.boxChanged(w, minCx, minCz, maxCx, maxCz)) {
                continue; // unchanged since our last reset — nobody has raided it
            }
            boxes.add(new RegenEngine.Box(w, minCx, minCz, maxCx, maxCz));
            chosen.add(e);
        }
        if (boxes.isEmpty()) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        boolean started = engine.runBatch(boxes, true,
                i -> scanner.markReset(chosen.get(i), nowMs),
                () -> {
                    lastResetEpochMs = nowMs;
                    plugin.getLogger().info("Structure reset (" + reason + "): "
                            + boxes.size() + " structure(s) regenerated.");
                });
        return started ? boxes.size() : -1;
    }

    // --------------------------------------------------------------- commands

    List<String> cmdStatus() {
        List<String> out = new ArrayList<>();
        out.add("§6[struct] §fenabled=§e" + enabled + " §7registry=§f" + scanner.size()
                + (scanner.isScanning() ? " §c(scanning)" : "")
                + " §7margin=§f" + margin + " §7interval=§f" + Durations.human(intervalSeconds));
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
        int n = attemptReset("manual", typeFilter, false);
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

}
