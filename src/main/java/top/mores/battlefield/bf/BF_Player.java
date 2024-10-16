package top.mores.battlefield.bf;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

public class BF_Player {
    private static final Map<UUID, BF_Player> totalPlayers = new HashMap<>();

    private Player player;

    private BF_Arena arena;

    private BF_Class bfClass;

    private ItemStack[] savedInventory;

    private Scoreboard backupScoreboard;

    private String backupScoreboardTeam;
    private PlayerStatus status = PlayerStatus.NULL;

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public BF_Arena getArena() {
        return arena;
    }

    public void setArena(BF_Arena arena) {
        this.arena = arena;
    }

    public BF_Class getBfClass() {
        return bfClass;
    }

    public void setBfClass(BF_Class bfClass) {
        this.bfClass = bfClass;
    }

    public ItemStack[] getSavedInventory() {
        return savedInventory;
    }

    public void setSavedInventory(ItemStack[] savedInventory) {
        this.savedInventory = savedInventory;
    }

    public Scoreboard getBackupScoreboard() {
        return backupScoreboard;
    }

    public void setBackupScoreboard(Scoreboard backupScoreboard) {
        this.backupScoreboard = backupScoreboard;
    }

    public String getBackupScoreboardTeam() {
        return backupScoreboardTeam;
    }

    public void setBackupScoreboardTeam(String backupScoreboardTeam) {
        this.backupScoreboardTeam = backupScoreboardTeam;
    }

    public static BF_Player getTotalPlayer(Player player) {
        return totalPlayers.get(player.getUniqueId());
    }

    public static void removeTotalPlayer(Player player) {
        totalPlayers.remove(player.getUniqueId());
    }

    public static Set<BF_Player> getAllArenaPlayers() {
        return new HashSet<>(totalPlayers.values());
    }

    //返回玩家名称
    public String getName() {
        return this.player.getName();
    }

    public PlayerStatus getStatus() {
        return this.status;
    }

    public void setStatus(final PlayerStatus status) {
        this.status = status;
    }

//    public void setArenaClass(final BF_Class bfClass) {
//
//    }
}
