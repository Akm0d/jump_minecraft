package com.jump.minecraft;

import java.util.List;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class JumpAssignments {
    private final Server server;
    private final StationManager stations;
    private final StationRegistryServer registry;

    JumpAssignments(Server server, StationManager stations, StationRegistryServer registry) {
        this.server = server;
        this.stations = stations;
        this.registry = registry;
    }

    boolean showStatus(CommandSender sender) {
        sender.sendMessage("JUMP registry: " + registry.status());
        List<String> lines = stations.statusLines();
        if (lines.isEmpty()) sender.sendMessage("JUMP stations: none registered");
        else lines.forEach(line -> sender.sendMessage("JUMP station: " + line));
        return true;
    }

    boolean assign(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /jump assign <station-id> <online-player>");
            return true;
        }
        Player player = server.getPlayerExact(args[2]);
        if (player == null) {
            sender.sendMessage("Player is not online: " + args[2]);
        } else {
            sender.sendMessage("JUMP: " + stations.assign(args[1], player));
        }
        return true;
    }

    boolean armCompatibility(CommandSender sender, String[] args) {
        Player player = args.length == 2
                ? server.getPlayerExact(args[1])
                : sender instanceof Player senderPlayer ? senderPlayer : null;
        if (player == null) sender.sendMessage("Usage: /jump arm <online-player>");
        else sender.sendMessage("JUMP: " + stations.assignFirstAvailable(player));
        return true;
    }

    boolean disarm(CommandSender sender, String[] args) {
        if (args.length == 2) {
            sender.sendMessage("JUMP: " + stations.unassignStation(args[1], "operator disarmed"));
        } else if (sender instanceof Player player) {
            stations.unassign(player.getUniqueId(), "operator disarmed");
            sender.sendMessage("JUMP: your station assignment was cleared");
        } else {
            stations.unassignAll("operator disarmed all stations");
            sender.sendMessage("JUMP: all station assignments were cleared");
        }
        return true;
    }
}
