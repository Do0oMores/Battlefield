package top.mores.battlefield.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.mores.battlefield.Battlefield;

public class MainCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender,@NotNull Command command,@NotNull String s, String[] strings) {
        if (commandSender instanceof Player player) {
            if (strings.length == 1 && strings[0].equalsIgnoreCase("reload")) {
                if (player.isOp()) {
                    Battlefield.getInstance().reloadConfig();
                    player.sendMessage(ChatColor.GREEN + "配置文件已重载！");
                } else {
                    player.sendMessage(ChatColor.RED + "你没有执行该命令的权限！");
                }
            }
        }else {
            commandSender.sendMessage("该命令只能由玩家执行！");
        }
        return true;
    }
}