package io.phate.worldrewild;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldRewildPlugin extends JavaPlugin {

    private RegenEngine engine;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        engine = new RegenEngine(this);
        engine.onEnable();
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.onDisable();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6/wr §f<start|pause|resume|stop|status|count|reset|reload"
                    + "|region|probe|entities|vanillaregen>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> sender.sendMessage("§6[WorldRewild] §f" + engine.cmdStart());
            case "pause" -> sender.sendMessage("§6[WorldRewild] §f" + engine.cmdPause());
            case "resume" -> sender.sendMessage("§6[WorldRewild] §f" + engine.cmdResume());
            case "stop" -> sender.sendMessage("§6[WorldRewild] §f" + engine.cmdStop());
            case "reset" -> sender.sendMessage("§6[WorldRewild] §f" + engine.cmdReset());
            case "reload" -> {
                engine.reloadConfig();
                sender.sendMessage("§6[WorldRewild] §fconfig reloaded.");
            }
            case "status" -> send(sender, engine.cmdStatus());
            case "count" -> engine.cmdCount(sender);
            case "region" -> {
                if (args.length < 6) {
                    sender.sendMessage("§cusage: /wr region <world> <cx1> <cz1> <cx2> <cz2>");
                    return true;
                }
                try {
                    engine.cmdRegion(sender, args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                            Integer.parseInt(args[4]), Integer.parseInt(args[5]));
                } catch (NumberFormatException e) {
                    sender.sendMessage("§ccoordinates must be integers.");
                }
            }
            case "delete" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cusage: /wr delete <world> <chunkX> <chunkZ>");
                    return true;
                }
                try {
                    engine.cmdDelete(sender, args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                } catch (NumberFormatException e) {
                    sender.sendMessage("§ccoordinates must be integers.");
                }
            }
            case "vanillaregen" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cusage: /wr vanillaregen <world> <chunkX> <chunkZ>");
                    return true;
                }
                try {
                    engine.vanillaRegen(sender, args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                } catch (NumberFormatException e) {
                    sender.sendMessage("§ccoordinates must be integers.");
                }
            }
            case "probe" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cusage: /wr probe <world> <chunkX> <chunkZ> [material=DARK_OAK_PLANKS]");
                    return true;
                }
                try {
                    String mat = args.length >= 5 ? args[4] : "DARK_OAK_PLANKS";
                    send(sender, engine.cmdProbe(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]), mat));
                } catch (NumberFormatException e) {
                    sender.sendMessage("§ccoordinates must be integers.");
                }
            }
            case "entities" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cusage: /wr entities <world> <chunkX> <chunkZ> [typeFilter]");
                    return true;
                }
                try {
                    String f = args.length >= 5 ? args[4] : null;
                    send(sender, engine.cmdEntities(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]), f));
                } catch (NumberFormatException e) {
                    sender.sendMessage("§ccoordinates must be integers.");
                }
            }
            default -> sender.sendMessage("§cunknown subcommand: " + args[0]);
        }
        return true;
    }

    private static void send(CommandSender sender, List<String> lines) {
        for (String l : lines) {
            sender.sendMessage(l);
        }
    }
}
