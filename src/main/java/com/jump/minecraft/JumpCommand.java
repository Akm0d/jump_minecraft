package com.jump.minecraft;

import java.util.Locale;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

final class JumpCommand implements CommandExecutor {
    private final JumpAssignments assignments;

    JumpCommand(Server server, StationManager stations, StationRegistryServer registry, Plugin plugin) {
        assignments = new JumpAssignments(server, stations, registry, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "status", "stations" -> assignments.showStatus(sender);
            case "assign" -> assignments.assign(sender, args);
            case "arm" -> assignments.armCompatibility(sender, args);
            case "disarm" -> assignments.disarm(sender, args);
            case "mode" -> assignments.mode(sender, args);
            default -> false;
        };
    }

}
