package io.phate.worldrewild;

import java.lang.reflect.Method;

import org.bukkit.World;

/**
 * SPIKE: reset a chunk's stored data (block + entity + POI) via Paper/Moonrise
 * internals so the server regenerates it from scratch through the vanilla
 * pipeline on next load. Unlike FAWE block-regen, vanilla generation also
 * places structures AND their entities (e.g. mansion evokers).
 *
 * <p>All access is reflective so the plugin still compiles without the server
 * jar on the classpath. Moonrise/NMS internals are version specific — this is a
 * feasibility spike, not a stable API.
 */
public final class VanillaRegen {

    private VanillaRegen() {
    }

    private static volatile boolean initialised;
    private static Method scheduleSave;   // (ServerLevel, int, int, CompoundTag, RegionFileType)
    private static Method flush;           // (ServerLevel)
    private static Method getHandle;       // CraftWorld#getHandle -> ServerLevel
    private static Object chunkData;
    private static Object entityData;
    private static Object poiData;

    private static synchronized void init(World world) throws Exception {
        if (initialised) {
            return;
        }
        Class<?> moonriseIo = Class.forName(
                "ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO");
        Class<?> regionFileType = Class.forName(
                "ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO$RegionFileType");
        Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
        Class<?> compoundTag = Class.forName("net.minecraft.nbt.CompoundTag");

        scheduleSave = moonriseIo.getMethod("scheduleSave",
                serverLevel, int.class, int.class, compoundTag, regionFileType);
        flush = moonriseIo.getMethod("flush", serverLevel);
        getHandle = world.getClass().getMethod("getHandle");
        chunkData = regionFileType.getField("CHUNK_DATA").get(null);
        entityData = regionFileType.getField("ENTITY_DATA").get(null);
        poiData = regionFileType.getField("POI_DATA").get(null);
        initialised = true;
    }

    /**
     * Queue deletion of the chunk's block, entity and POI data. The chunk must
     * be unloaded; the next load regenerates it via vanilla. Cheap, async — safe
     * to call on the main thread. Durability is handled by a periodic
     * {@link #flushLevel}.
     */
    public static void deleteChunk(World world, int cx, int cz) throws Exception {
        init(world);
        Object level = getHandle.invoke(world);
        scheduleSave.invoke(null, level, cx, cz, null, chunkData);
        scheduleSave.invoke(null, level, cx, cz, null, entityData);
        scheduleSave.invoke(null, level, cx, cz, null, poiData);
    }

    /** Flush all pending region IO for the world to disk. */
    public static void flushLevel(World world) throws Exception {
        init(world);
        flush.invoke(null, getHandle.invoke(world));
    }

    /**
     * Delete a chunk's data and flush immediately. Loading it afterwards
     * regenerates it. Convenience for one-shot / manual use.
     */
    public static void reset(World world, int cx, int cz) throws Exception {
        deleteChunk(world, cx, cz);
        flushLevel(world);
    }
}
