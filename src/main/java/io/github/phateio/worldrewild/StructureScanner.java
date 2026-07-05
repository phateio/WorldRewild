package io.github.phateio.worldrewild;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Enumerates rare structures by reading chunk NBT ({@code structures.starts})
 * directly from the region files — no running-game state and no manual marking.
 * Builds a persistent registry of {world, type, footprint bbox}. Scans are
 * incremental: only chunks saved since the last scan are decompressed, so after
 * the initial full pass ongoing refreshes are cheap.
 */
final class StructureScanner {

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int HEADER_BYTES = 8192;

    /** A registered structure, or the synthetic End central-island entry. */
    static final class Entry {
        final String world;
        final String type;        // e.g. "mansion"
        final int startCx;
        final int startCz;
        int minCx, minCz, maxCx, maxCz;   // chunk footprint (inclusive)
        long lastReset;           // epoch millis of our last reset (0 = never)

        Entry(String world, String type, int startCx, int startCz,
              int minCx, int minCz, int maxCx, int maxCz, long lastReset) {
            this.world = world;
            this.type = type;
            this.startCx = startCx;
            this.startCz = startCz;
            this.minCx = minCx;
            this.minCz = minCz;
            this.maxCx = maxCx;
            this.maxCz = maxCz;
            this.lastReset = lastReset;
        }

        String key() {
            return world + "|" + type + "|" + startCx + "|" + startCz;
        }

        long chunkCount() {
            return (long) (maxCx - minCx + 1) * (maxCz - minCz + 1);
        }
    }

    /** A world to scan: display name + its region directory. */
    record WorldDir(String name, File regionDir) {}

    private final WorldRewild plugin;
    private final File file;
    private final Map<String, Entry> registry = new LinkedHashMap<>();
    private long lastScanEpochSec = 0;
    private volatile boolean scanning = false;

    StructureScanner(WorldRewild plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "structures.tsv");
    }

    boolean isScanning() {
        return scanning;
    }

    synchronized List<Entry> entries() {
        return new ArrayList<>(registry.values());
    }

    synchronized int size() {
        return registry.size();
    }

    synchronized boolean neverScanned() {
        return lastScanEpochSec == 0;
    }

    synchronized Map<String, Integer> countsByType() {
        Map<String, Integer> m = new TreeMap<>();
        for (Entry e : registry.values()) {
            m.merge(e.type, 1, Integer::sum);
        }
        return m;
    }

    synchronized void markReset(Entry e, long epochMs) {
        Entry cur = registry.get(e.key());
        if (cur != null) {
            cur.lastReset = epochMs;
            save();
        }
    }

    synchronized void clear() {
        registry.clear();
        lastScanEpochSec = 0;
        save();
    }

    // ------------------------------------------------------------------- scan

    /**
     * Scan the given worlds for structures of {@code types} (short ids, e.g.
     * "mansion"). Incremental unless {@code forceFull}: only chunks saved since
     * the last scan are decompressed. Runs off the main thread; {@code onDone}
     * runs on the main thread when finished.
     */
    void scanAsync(List<WorldDir> worlds, Set<String> types, boolean forceFull, Runnable onDone) {
        if (scanning) {
            return;
        }
        scanning = true;
        final long threshold = forceFull ? 0 : lastScanEpochSec;
        final long nowSec = System.currentTimeMillis() / 1000L;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Entry> found = new ArrayList<>();
            long[] stats = {0, 0}; // files scanned, chunks decompressed
            try {
                for (WorldDir wd : worlds) {
                    if (wd.regionDir() == null || !wd.regionDir().isDirectory()) {
                        continue;
                    }
                    File[] files = wd.regionDir().listFiles((d, n) -> REGION.matcher(n).matches());
                    if (files == null) {
                        continue;
                    }
                    for (File f : files) {
                        try {
                            scanRegion(wd.name(), f, types, threshold, found, stats);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Structure scan failed for " + f.getName() + ": " + t);
                        }
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Structure scan error: " + t);
            }
            int total;
            synchronized (this) {
                for (Entry e : found) {
                    Entry cur = registry.get(e.key());
                    if (cur != null) {
                        cur.minCx = e.minCx;
                        cur.minCz = e.minCz;
                        cur.maxCx = e.maxCx;
                        cur.maxCz = e.maxCz;
                    } else {
                        registry.put(e.key(), e);
                    }
                }
                registry.values().removeIf(e -> !types.contains(e.type));
                lastScanEpochSec = nowSec;
                save();
                total = registry.size();
            }
            scanning = false;
            plugin.getLogger().info("Structure scan " + (forceFull ? "(full)" : "(incremental)")
                    + ": " + found.size() + " starts seen, registry=" + total
                    + " (region files=" + stats[0] + ", chunks read=" + stats[1] + ").");
            if (onDone != null) {
                plugin.getServer().getScheduler().runTask(plugin, onDone);
            }
        });
    }

    private void scanRegion(String world, File f, Set<String> types, long threshold,
                            List<Entry> out, long[] stats) throws IOException {
        if (f.length() < HEADER_BYTES) {
            return;
        }
        byte[] header = new byte[HEADER_BYTES];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(header);
            stats[0]++;
            for (int i = 0; i < 1024; i++) {
                int b = i * 4;
                int off = ((header[b] & 0xFF) << 16) | ((header[b + 1] & 0xFF) << 8) | (header[b + 2] & 0xFF);
                int cnt = header[b + 3] & 0xFF;
                if (off == 0 || cnt == 0) {
                    continue;
                }
                if (threshold > 0) {
                    long ts = readUInt32(header, 4096 + b);
                    if (ts <= threshold) {
                        continue; // unchanged since last scan
                    }
                }
                byte[] data = readChunk(raf, off, cnt);
                if (data == null) {
                    continue;
                }
                stats[1]++;
                extractStarts(world, Nbt.read(data), types, out);
            }
        }
    }

    private static byte[] readChunk(RandomAccessFile raf, int offSectors, int sectorCount) throws IOException {
        raf.seek((long) offSectors * 4096L);
        int len = raf.readInt();
        if (len <= 1 || len > sectorCount * 4096) {
            return null;
        }
        int comp = raf.readUnsignedByte();
        if ((comp & 0x80) != 0) {
            return null; // external .mcc payload — skip (rare, only huge chunks)
        }
        byte[] payload = new byte[len - 1];
        raf.readFully(payload);
        try {
            return switch (comp) {
                case 1 -> new GZIPInputStream(new ByteArrayInputStream(payload)).readAllBytes();
                case 2 -> new InflaterInputStream(new ByteArrayInputStream(payload)).readAllBytes();
                case 3 -> payload;
                default -> null; // e.g. 4 = LZ4, not used by this server
            };
        } catch (IOException e) {
            return null; // torn read (server saving concurrently) — skip
        }
    }

    @SuppressWarnings("unchecked")
    private static void extractStarts(String world, Map<String, Object> root, Set<String> types, List<Entry> out) {
        Object structs = root.get("structures");
        if (!(structs instanceof Map)) {
            return;
        }
        Object starts = ((Map<String, Object>) structs).get("starts");
        if (!(starts instanceof Map)) {
            return;
        }
        for (Object v : ((Map<String, Object>) starts).values()) {
            if (!(v instanceof Map)) {
                continue;
            }
            Map<String, Object> start = (Map<String, Object>) v;
            if (!(start.get("id") instanceof String id)) {
                continue;
            }
            String shortId = id.substring(id.indexOf(':') + 1);
            if (!types.contains(shortId)) {
                continue;
            }
            // Footprint = union of the pieces' bounding boxes (block coords).
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            if (start.get("Children") instanceof List<?> children) {
                for (Object co : children) {
                    if (co instanceof Map && ((Map<String, Object>) co).get("BB") instanceof int[] bb && bb.length == 6) {
                        minX = Math.min(minX, bb[0]);
                        minZ = Math.min(minZ, bb[2]);
                        maxX = Math.max(maxX, bb[3]);
                        maxZ = Math.max(maxZ, bb[5]);
                    }
                }
            }
            int startCx = num(start.get("ChunkX"));
            int startCz = num(start.get("ChunkZ"));
            int minCx, minCz, maxCx, maxCz;
            if (maxX == Integer.MIN_VALUE) {
                minCx = maxCx = startCx; // no piece BB found; fall back to the start chunk
                minCz = maxCz = startCz;
            } else {
                minCx = minX >> 4;
                minCz = minZ >> 4;
                maxCx = maxX >> 4;
                maxCz = maxZ >> 4;
            }
            out.add(new Entry(world, shortId, startCx, startCz, minCx, minCz, maxCx, maxCz, 0));
        }
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long readUInt32(byte[] a, int off) {
        return ((a[off] & 0xFFL) << 24) | ((a[off + 1] & 0xFFL) << 16)
                | ((a[off + 2] & 0xFFL) << 8) | (a[off + 3] & 0xFFL);
    }

    // ------------------------------------------------------------ persistence

    synchronized void load() {
        registry.clear();
        if (!file.exists()) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#lastScan=")) {
                    try {
                        lastScanEpochSec = Long.parseLong(line.substring(10).trim());
                    } catch (NumberFormatException ignored) {
                        // keep default
                    }
                    continue;
                }
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t");
                if (p.length < 9) {
                    continue; // (a trailing 10th column from older files is simply ignored)
                }
                Entry e = new Entry(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]),
                        Integer.parseInt(p[7]), Long.parseLong(p[8]));
                registry.put(e.key(), e);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load structures.tsv: " + e);
        }
    }

    private void save() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println("#lastScan=" + lastScanEpochSec);
            for (Entry e : registry.values()) {
                pw.println(String.join("\t", e.world, e.type,
                        Integer.toString(e.startCx), Integer.toString(e.startCz),
                        Integer.toString(e.minCx), Integer.toString(e.minCz),
                        Integer.toString(e.maxCx), Integer.toString(e.maxCz),
                        Long.toString(e.lastReset)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save structures.tsv: " + e);
        }
    }
}
