package io.github.phateio.worldrewild;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldRewild extends JavaPlugin {

    private static final String USAGE =
            "§6/wr §f<start|pause|resume|stop|status|count|reset|reload|region|vanillaregen|probe|entities>";

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
            sender.sendMessage(USAGE);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> reply(sender, engine.cmdStart());
            case "pause" -> reply(sender, engine.cmdPause());
            case "resume" -> reply(sender, engine.cmdResume());
            case "stop" -> reply(sender, engine.cmdStop());
            case "reset" -> reply(sender, engine.cmdReset());
            case "reload" -> {
                engine.reloadConfig();
                reply(sender, "config reloaded.");
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
            case "vanillaregen" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cusage: /wr vanillaregen <world> <chunkX> <chunkZ>");
                    return true;
                }
                withChunk(sender, args, (cx, cz) -> engine.vanillaRegen(sender, args[1], cx, cz));
            }
            case "probe" -> {
                if (args.length < 5) {
                    sender.sendMessage("§cusage: /wr probe <world> <chunkX> <chunkZ> <material>");
                    return true;
                }
                withChunk(sender, args, (cx, cz) -> send(sender, engine.cmdProbe(args[1], cx, cz, args[4])));
            }
            case "entities" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cusage: /wr entities <world> <chunkX> <chunkZ> [typeFilter]");
                    return true;
                }
                String filter = args.length >= 5 ? args[4] : null;
                withChunk(sender, args, (cx, cz) -> send(sender, engine.cmdEntities(args[1], cx, cz, filter)));
            }
            default -> sender.sendMessage("§cunknown subcommand: " + args[0]);
        }
        return true;
    }

    @FunctionalInterface
    private interface ChunkAction {
        void run(int cx, int cz);
    }

    /** Parse {@code args[2]} (chunkX) and {@code args[3]} (chunkZ), then run the action. */
    private static void withChunk(CommandSender sender, String[] args, ChunkAction action) {
        try {
            action.run(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        } catch (NumberFormatException e) {
            sender.sendMessage("§ccoordinates must be integers.");
        }
    }

    private static void reply(CommandSender sender, String message) {
        sender.sendMessage("§6[WorldRewild] §f" + message);
    }

    private static void send(CommandSender sender, List<String> lines) {
        lines.forEach(sender::sendMessage);
    }
}
