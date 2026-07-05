package io.github.phateio.worldrewild;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-chunk record of the region-file timestamp WorldRewild observed right after
 * it last reset each chunk — its "reset stamp". The age sweep uses it to skip
 * chunks that are <b>unchanged</b> since: a chunk whose live region timestamp
 * still equals its stamp has not been saved (nobody has loaded and changed it)
 * since we reset it, so regenerating it again would be pure waste. A chunk whose
 * timestamp has advanced past its stamp counts as <b>changed</b> and is eligible
 * to reset again. Chunks we have never reset have no stamp (treated as 0), so
 * they always look changed — which is what lets a first-pass cross-version
 * conversion sweep the whole map once.
 *
 * <p>Layout mirrors the world's region files: one 4 KiB file per stamped region
 * at {@code <dataFolder>/stamps/<world>/r.<rx>.<rz>.bin}, holding 1024 big-endian
 * int timestamps (slot = {@code (cx & 31) | ((cz & 31) << 5)}, matching
 * RegenEngine's slot↔chunk mapping). The whole set is small enough (~4 KiB ×
 * stamped regions) to hold in memory; a reset rewrites just the one changed
 * region file. All access is synchronized: the region scan reads stamps off the
 * main thread while the reset hook writes them on the main thread. Writes replace
 * the array reference (copy-on-write) so a reader holding one always sees a
 * consistent snapshot.
 */
final class ResetStamps {

    private static final int SLOTS = 1024;
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.bin");

    private final WorldRewild plugin;
    private final File root; // <dataFolder>/stamps
    // world -> (packed region key -> int[1024] of reset stamps)
    private final Map<String, Map<Long, int[]>> stamps = new HashMap<>();

    ResetStamps(WorldRewild plugin) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "stamps");
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** Load every persisted per-region stamp file into memory. */
    synchronized void load() {
        stamps.clear();
        File[] worldDirs = root.listFiles(File::isDirectory);
        if (worldDirs == null) {
            return;
        }
        for (File wd : worldDirs) {
            File[] files = wd.listFiles((d, n) -> REGION.matcher(n).matches());
            if (files == null) {
                continue;
            }
            Map<Long, int[]> regions = new HashMap<>();
            for (File f : files) {
                Matcher m = REGION.matcher(f.getName());
                if (!m.matches()) {
                    continue;
                }
                int[] region = readFile(f);
                if (region != null) {
                    regions.put(key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), region);
                }
            }
            if (!regions.isEmpty()) {
                stamps.put(wd.getName(), regions);
            }
        }
    }

    /** The reset stamps for a region (one per slot), or null if none recorded. */
    synchronized int[] region(String world, int rx, int rz) {
        Map<Long, int[]> regions = stamps.get(world);
        return regions == null ? null : regions.get(key(rx, rz));
    }

    /**
     * Stamp the chunks that were actually reset in a region ({@code resetMask[slot]}
     * true) with their fresh region timestamps, then persist that region's file.
     * {@code freshTs[slot]} is the region timestamp for that slot re-read after the
     * reset flush; slots not reset keep their previous stamp.
     */
    synchronized void update(String world, int rx, int rz, int[] freshTs, boolean[] resetMask) {
        Map<Long, int[]> regions = stamps.computeIfAbsent(world, w -> new HashMap<>());
        long k = key(rx, rz);
        int[] prev = regions.get(k);
        int[] next = prev == null ? new int[SLOTS] : prev.clone(); // copy-on-write for readers
        for (int i = 0; i < SLOTS; i++) {
            if (resetMask[i]) {
                next[i] = freshTs[i];
            }
        }
        regions.put(k, next);
        writeFile(world, rx, rz, next);
    }

    /** Drop all stamps for one world (memory + disk). */
    synchronized void clear(String world) {
        stamps.remove(world);
        deleteDir(new File(root, world));
    }

    /** Drop every stamp (memory + disk). */
    synchronized void clear() {
        stamps.clear();
        deleteDir(root);
    }

    // --------------------------------------------------------------- file I/O

    private int[] readFile(File f) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            int[] region = new int[SLOTS];
            for (int i = 0; i < SLOTS; i++) {
                region[i] = in.readInt();
            }
            return region;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read reset stamps " + f.getName() + ": " + e);
            return null;
        }
    }

    private void writeFile(String world, int rx, int rz, int[] region) {
        File dir = new File(root, world);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("Failed to create reset-stamps dir: " + dir);
            return;
        }
        File f = new File(dir, "r." + rx + "." + rz + ".bin");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            for (int i = 0; i < SLOTS; i++) {
                out.writeInt(region[i]);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write reset stamps " + f.getName() + ": " + e);
        }
    }

    private static void deleteDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) {
                    deleteDir(k);
                } else {
                    k.delete();
                }
            }
        }
        dir.delete();
    }
}
