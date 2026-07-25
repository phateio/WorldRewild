package io.github.phateio.worldrewild;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.event.ResidenceAreaAddEvent;
import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.event.ResidenceSizeChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceSubzoneCreationEvent;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
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
        int minCx = Math.min(lo.getBlockX(), hi.getBlockX()) >> 4;
        int maxCx = Math.max(lo.getBlockX(), hi.getBlockX()) >> 4;
        int minCz = Math.min(lo.getBlockZ(), hi.getBlockZ()) >> 4;
        int maxCz = Math.max(lo.getBlockZ(), hi.getBlockZ()) >> 4;

        // Fast path: a structure a periodic scan already recorded. This also covers a
        // structure whose footprint reaches into the box from a start chunk outside it.
        Entry hit = structures.scanner().firstOverlapping(world, minCx, minCz, maxCx, maxCz);
        String type = hit != null ? hit.type
                // Slow path: the registry lags freshly generated terrain (scans are
                // periodic), so read the claimed chunks' structure NBT straight from the
                // region files. Closes the claim-before-scan gap that let a player fence
                // off a structure minutes after generating it, before any scan saw it.
                : structures.scanner().overlappingTypeLive(structures.regionDir(world),
                        structures.resetTypes(), minCx, minCz, maxCx, maxCz);
        if (type == null) {
            return false;
        }
        if (player != null) {
            player.sendMessage("§cYou cannot create a residence too close to the §e" + type + "§c.");
        }
        return true;
    }

    /**
     * List existing residences (and subzones) whose area overlaps a reset structure
     * the scanner has recorded. Read-only: it reports, never modifies or removes
     * anything. Surfaces claims made before the structure was discovered — the
     * claim-before-scan cases {@link #refuse} now blocks at creation, but which were
     * grandfathered in because existing residences are never revisited. Removing one,
     * if wanted, is a manual {@code /res} decision left to an admin.
     */
    void audit(CommandSender sender) {
        Residence residence = Residence.getInstance();
        if (residence == null) {
            sender.sendMessage("§cResidence is not available.");
            return;
        }
        StructureScanner scanner = structures.scanner();
        List<String> lines = new ArrayList<>();
        for (ClaimedResidence res : residence.getResidenceManager().getResidences().values()) {
            collectOverlaps(res, scanner, lines);
        }
        if (lines.isEmpty()) {
            sender.sendMessage("§6[audit] §fno residence overlaps a reset structure.");
            return;
        }
        sender.sendMessage("§6[audit] §e" + lines.size()
                + "§f residence area(s) overlap a reset structure:");
        lines.forEach(sender::sendMessage);
    }

    /** Append a line for each area of {@code res} (and its subzones) that overlaps one. */
    private static void collectOverlaps(ClaimedResidence res, StructureScanner scanner, List<String> out) {
        for (CuboidArea area : res.getAreaArray()) {
            if (area == null) {
                continue;
            }
            String world = area.getWorldName();
            Vector lo = area.getLowVector();
            Vector hi = area.getHighVector();
            if (world == null || lo == null || hi == null) {
                continue;
            }
            int minCx = Math.min(lo.getBlockX(), hi.getBlockX()) >> 4;
            int maxCx = Math.max(lo.getBlockX(), hi.getBlockX()) >> 4;
            int minCz = Math.min(lo.getBlockZ(), hi.getBlockZ()) >> 4;
            int maxCz = Math.max(lo.getBlockZ(), hi.getBlockZ()) >> 4;
            Entry hit = scanner.firstOverlapping(world, minCx, minCz, maxCx, maxCz);
            if (hit != null) {
                out.add("§7- §f" + res.getName() + " §7(" + res.getOwner() + ", " + world
                        + ") §7↔ §e" + hit.type + " §7@chunk " + hit.startCx + "," + hit.startCz);
            }
        }
        for (ClaimedResidence sub : res.getSubzones()) {
            collectOverlaps(sub, scanner, out);
        }
    }
}
