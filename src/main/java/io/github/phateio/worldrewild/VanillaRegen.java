package io.github.phateio.worldrewild;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.bukkit.World;

/**
 * Reset a chunk's stored data (block + entity + POI) via Paper/Moonrise
 * internals so the server regenerates it from scratch through the vanilla
 * pipeline on next load. Unlike FAWE block-regen, vanilla generation also
 * places structures AND their entities (e.g. mansion evokers).
 *
 * <p>Deletion goes through the Moonrise {@code RegionDataController}:
 * {@code startWrite(x, z, null)} yields a WriteData whose result is DELETE, and
 * {@code finishWrite} then calls {@code RegionFile.clear(pos)} — a synchronous,
 * internally-synchronized region-file mutation. We do NOT use
 * {@code scheduleSave(..., null, ...)}: its async Consumer path treats a null
 * tag as "nothing to save" and never deletes, so the subsequent load just reads
 * (and DataFixer-upgrades) the old chunk instead of regenerating it.
 *
 * <p>All access is reflective so the plugin still compiles without the server
 * jar on the classpath. Moonrise/NMS internals are version specific.
 */
public final class VanillaRegen {

    private VanillaRegen() {
    }

    private static volatile boolean initialised;
    private static Method flush;            // (ServerLevel)
    private static Method flushStorages;    // (ServerLevel) throws IOException
    private static Method getControllerFor; // (ServerLevel, RegionFileType) -> RegionDataController
    private static Method startWrite;       // RegionDataController#(int,int,CompoundTag) -> WriteData
    private static Method finishWrite;      // RegionDataController#(int,int,WriteData)
    private static Method getHandle;        // CraftWorld#getHandle -> ServerLevel
    private static Object chunkData;
    private static Object entityData;
    private static Object poiData;

    // End dragon-fight re-scan (looked up separately; a name change here must not
    // break chunk deletion above). Only needsStateScanning is reflected — the
    // Bukkit DragonBattle API covers previously-killed, and scanState() re-derives
    // dragon uuid / killed / respawn stage on the forced re-scan.
    private static volatile boolean dragonInit;
    private static Method getDragonFight;
    private static Method setDirty;
    private static Field fNeedsScan;

    private static synchronized void init(World world) throws Exception {
        if (initialised) {
            return;
        }
        Class<?> moonriseIo = Class.forName(
                "ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO");
        Class<?> regionFileType = Class.forName(
                "ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO$RegionFileType");
        Class<?> controller = Class.forName(
                "ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO$RegionDataController");
        Class<?> writeData = Class.forName(
                "ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO$RegionDataController$WriteData");
        Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
        Class<?> compoundTag = Class.forName("net.minecraft.nbt.CompoundTag");

        flush = moonriseIo.getMethod("flush", serverLevel);
        flushStorages = moonriseIo.getMethod("flushRegionStorages", serverLevel);
        getControllerFor = moonriseIo.getMethod("getControllerFor", serverLevel, regionFileType);
        startWrite = controller.getMethod("startWrite", int.class, int.class, compoundTag);
        finishWrite = controller.getMethod("finishWrite", int.class, int.class, writeData);
        getHandle = world.getClass().getMethod("getHandle");
        chunkData = regionFileType.getField("CHUNK_DATA").get(null);
        entityData = regionFileType.getField("ENTITY_DATA").get(null);
        poiData = regionFileType.getField("POI_DATA").get(null);
        initialised = true;
    }

    /**
     * Synchronously delete the chunk's block, entity and POI data from the
     * region files (clears the region-file slot). The chunk must be unloaded;
     * the next load then regenerates it from scratch via vanilla.
     */
    public static void deleteChunk(World world, int cx, int cz) throws Exception {
        init(world);
        Object level = getHandle.invoke(world);
        for (Object type : new Object[]{chunkData, entityData, poiData}) {
            Object ctrl = getControllerFor.invoke(null, level, type);
            Object wd = startWrite.invoke(ctrl, new Object[]{cx, cz, null});
            finishWrite.invoke(ctrl, new Object[]{cx, cz, wd});
        }
    }

    /** Flush all pending region IO for the world to disk. */
    public static void flushLevel(World world) throws Exception {
        init(world);
        flush.invoke(null, getHandle.invoke(world));
    }

    /** Force-flush the world's region storages to disk. */
    public static void flushStorages(World world) throws Exception {
        init(world);
        flushStorages.invoke(null, getHandle.invoke(world));
    }

    private static synchronized void initDragon() throws Exception {
        if (dragonInit) {
            return;
        }
        Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
        Class<?> fight = Class.forName("net.minecraft.world.level.dimension.end.EnderDragonFight");
        Class<?> savedData = Class.forName("net.minecraft.world.level.saveddata.SavedData");
        getDragonFight = serverLevel.getMethod("getDragonFight");
        setDirty = savedData.getMethod("setDirty");
        fNeedsScan = fight.getDeclaredField("needsStateScanning");
        fNeedsScan.setAccessible(true);
        dragonInit = true;
    }

    /**
     * Force the End dragon fight to re-scan its state on the next tick with a player
     * present, and persist that. On the re-scan the fight re-derives everything —
     * previously-killed (from whether an exit portal / end gateway still exists within
     * 8 chunks of the origin), the live dragon, killed flag and respawn stage — so a
     * fresh dragon spawns once those blocks are gone (which the age-based sweep handles
     * when it regenerates the central island). The caller resets the previously-killed
     * flag via the stable Bukkit DragonBattle API; only needsStateScanning has no
     * Bukkit setter, so it alone is reflected here. Returns false if this world has no
     * dragon fight. Reflects into the NMS EnderDragonFight (version specific), separate
     * from the delete-init so a mismatch here cannot break chunk regeneration.
     */
    public static boolean forceEndRescan(World world) throws Exception {
        init(world);
        initDragon();
        Object fight = getDragonFight.invoke(getHandle.invoke(world));
        if (fight == null) {
            return false;
        }
        fNeedsScan.setBoolean(fight, true);
        setDirty.invoke(fight); // persist, else the change reverts on the next save
        return true;
    }
}
