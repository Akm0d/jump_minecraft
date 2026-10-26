package com.jump.minecraft;

import java.util.List;
import java.util.Locale;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

final class JumpCommand implements CommandExecutor, TabCompleter {
    private final Server server;
    private final FlightController controller;
    private final JumpUdpClient udpClient;

    JumpCommand(Server server, FlightController controller, JumpUdpClient udpClient) {
        this.server = server;
        this.controller = controller;
        this.udpClient = udpClient;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("JUMP: " + controller.status());
            sender.sendMessage("UDP: " + udpClient.status());
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "arm" -> {
                Player target;
                if (args.length >= 2) {
                    target = server.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage("Player is not online: " + args[1]);
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage("Console usage: /jump arm <player>");
                    return true;
                }
                controller.arm(target);
                sender.sendMessage("JUMP armed for " + target.getName());
                return true;
            }
            case "disarm" -> {
                controller.disarm("operator disarmed");
                sender.sendMessage("JUMP disarmed");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("arm", "disarm", "status");
        if (args.length == 2 && args[0].equalsIgnoreCase("arm")) {
            return server.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
        }
        return List.of();
    }
}
