package top.mores.battlefield.bf;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BF_Player {
    private static final Map<UUID,BF_Player> totalPlayers = new HashMap<>();

    private Player player;

    private BF_Arena arena;

    private BF_Class bfClass;

    private ItemStack[] savedInventory;

    private Scoreboard backupScoreboard;

    private String backupScoreboardTeam;
}
