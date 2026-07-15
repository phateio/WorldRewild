package io.github.phateio.worldrewild;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import com.bekvon.bukkit.residence.event.ResidenceAreaAddEvent;
import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.event.ResidenceSizeChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceSubzoneCreationEvent;
import com.bekvon.bukkit.residence.protection.CuboidArea;

import io.github.phateio.worldrewild.StructureScanner.Entry;

/**
 * Optional integration, wired up only when the Residence plugin is installed
 * (soft-dependency; this class is loaded and registered from
 * {@link WorldRewild#onEnable()} only in that case, so the Residence API classes
 * it references are never touched otherwise).
 *
 * <p>Refuses any residence claim — creating a residence or subzone, adding an
 * area, or resizing — whose footprint would cover a chunk that structure-reset
 * periodically regenerates. This stops a player fencing off a stronghold,
 * mansion, and the like from everyone else, and spares them a claim the next
 * reset would wipe anyway. Existing residences are left untouched; players with
 * {@code worldrewild.bypass.structureclaim} (op by default) are exempt.
 */
final class ResidenceGuard implements Listener {

    static final String BYPASS = "worldrewild.bypass.structureclaim";

    private final StructureReset structures;

    ResidenceGuard(StructureReset structures) {
        this.structures = structures;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onCreate(ResidenceCreationEvent e) {
        if (refuse(e.getPlayer(), e.getPhysicalArea())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSubzone(ResidenceSubzoneCreationEvent e) {
        if (refuse(e.getPlayer(), e.getPhysicalArea())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onAreaAdd(ResidenceAreaAddEvent e) {
        if (refuse(e.getPlayer(), e.getPhysicalArea())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onResize(ResidenceSizeChangeEvent e) {
        if (refuse(e.getPlayer(), e.getNewArea())) {
            e.setCancelled(true);
        }
    }

    /**
     * True if this claim should be refused: the guard is on, the player cannot
     * bypass, and the area overlaps a reset structure. Sends the player a reason
     * as a side effect when it refuses.
     */
    private boolean refuse(Player player, CuboidArea area) {
        if (!structures.blockResidenceClaims() || area == null) {
            return false;
        }
        if (player != null && player.hasPermission(BYPASS)) {
            return false;
        }
        String world = area.getWorldName();
        Vector lo = area.getLowVector();
        Vector hi = area.getHighVector();
        if (world == null || lo == null || hi == null) {
            return false;
        }
        int x1 = lo.getBlockX() >> 4;
        int x2 = hi.getBlockX() >> 4;
        int z1 = lo.getBlockZ() >> 4;
        int z2 = hi.getBlockZ() >> 4;
        Entry hit = structures.scanner().firstOverlapping(world,
                Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
        if (hit == null) {
            return false;
        }
        if (player != null) {
            player.sendMessage("§cYou can't claim here: this structure is periodically reset (§e"
                    + hit.type + "§c), so the claim would be wiped.");
        }
        return true;
    }
}
