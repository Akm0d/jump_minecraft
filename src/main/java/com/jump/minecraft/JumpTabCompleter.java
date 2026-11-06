package com.jump.minecraft;

import java.util.List;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

final class JumpTabCompleter implements TabCompleter {
    private final Server server;
    private final StationManager stations;

    JumpTabCompleter(Server server, StationManager stations) {
        this.server = server;
        this.stations = stations;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "stations", "assign", "disarm", "arm");
        if (args.length == 2 && (args[0].equalsIgnoreCase("assign")
                || args[0].equalsIgnoreCase("disarm"))) {
            return stations.stationIds();
        }
        if ((args.length == 3 && args[0].equalsIgnoreCase("assign"))
                || (args.length == 2 && args[0].equalsIgnoreCase("arm"))) {
            return server.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
        }
        return List.of();
    }
}
