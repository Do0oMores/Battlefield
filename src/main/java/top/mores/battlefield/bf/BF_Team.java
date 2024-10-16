package top.mores.battlefield.bf;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

public class BF_Team {

    private final Set<BF_Player> players;
    private final ChatColor color;
    private final String name;

    /**
     * 创建队伍实例
     * @param name 队伍名
     * @param color 队伍颜色
     */
    public BF_Team(final String name, final String color) {
        this.players = new HashSet<>();
        this.color = ChatColor.valueOf(color);
        this.name = name;
    }

    /**
     * 将玩家添加进团队
     * @param player 添加的玩家BF_Player
     */
    public void addPlayer(final BF_Player player) {
        this.players.add(player);
        player.getArena().increaseStartCount();
    }

    /**
     * 玩家名称颜色
     * @param player BF_Player
     * @return String玩家名称颜色
     */
    public String colorizePlayer(final BF_Player player) {
        return this.color + player.getName();
    }

    /**
     * 玩家名称颜色
     * @param player Player
     * @return String玩家名称颜色
     */
    public String colorizePlayer(final Player player) {
        return this.color + player.getName();
    }

    /**
     * 团队颜色
     * @return ChatColor团队颜色
     */
    public ChatColor getColor() {
        return this.color;
    }

    /**
     * 团队名称颜色
     * @return String团队名称颜色
     */
    public String getColoredName() {
        return this.color + this.name+ChatColor.RESET;
    }

    /**
     * 团队颜色代码
     * @return String团队颜色代码
     */
    public String getColorCodeString(){
        return "&"+Integer.toHexString(this.color.ordinal());
    }

    /**
     * 团队名
     * @return String团队名
     */
    public String getName() {
        return this.name;
    }

    /**
     * 返回团队成员
     * @return 返回HashSet团队成员
     */
    public Set<BF_Player> getTeamMembers() {
        return this.players;
    }

    /**
     * 队伍中是否有该玩家
     * @param player BF_Player
     * @return boolean
     */
    public boolean hasPlayer(final BF_Player player) {
        return this.players.contains(player);
    }

    /**
     * 是否所有人已准备
     * @return boolean
     */
    public boolean whetherEveryoneReady(){
        for (BF_Player player : this.players) {
            if (player.getStatus()!=PlayerStatus.READY) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将玩家从队伍中移除
     * @param player BF_Player
     */
    public void removePlayer(final BF_Player player) {
        this.players.remove(player);
    }

    /**
     * 是否为空队伍
     * @return boolean
     */
    public boolean whetherEmpty(){
        return this.getTeamMembers().isEmpty();
    }

    /**
     * 是否队伍内有玩家(至少一个)
     * @return boolean
     */
    public boolean notEmpty(){
        return !this.getTeamMembers().isEmpty();
    }
}
