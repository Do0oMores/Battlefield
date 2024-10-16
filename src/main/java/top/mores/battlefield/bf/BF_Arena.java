package top.mores.battlefield.bf;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class BF_Arena {

    private final Set<BF_Class> classes = new HashSet<>();
    private final Set<BF_Team> teams = new HashSet<>();
    private final Set<String> playedPlayers = new HashSet<>();
    private final Set<String> winners = new HashSet<>();
    private final Map<Player, UUID> entities = new HashMap<>();

    private final String name;
    private String prefix = "Battlefield";

    //游戏数据
    private int startCount;
    private BF_Scoreboard scoreboard = null;

    public BF_Arena(final String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    public String getPrefix() {
        return this.prefix;
    }

    public BF_Class getArenaClass(String className) {
        return classes.stream()
                .filter(ac -> ac.getName().equalsIgnoreCase(className))
                .findAny()
                .orElse(null);
    }

    public Set<BF_Class> getClasses() {
        return this.classes;
    }

    public Player getEntityOwner(final Entity entity) {
        return this.entities.entrySet().stream()
                .filter(e -> e.getValue().equals(entity.getUniqueId()))
                .findAny()
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    //获取该场游戏内的所有玩家
    public Set<BF_Player> getAllPlayers() {
        return BF_Player.getAllArenaPlayers().stream()
                .filter(ap -> this.equals(ap.getArena()))
                .collect(Collectors.toSet());
    }

    public void increaseStartCount() {
        this.startCount++;
    }

    public Set<BF_Team> getTeams() {
        return this.teams;
    }
}
