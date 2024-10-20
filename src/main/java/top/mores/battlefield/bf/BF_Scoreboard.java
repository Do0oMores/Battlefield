package top.mores.battlefield.bf;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import static java.util.Optional.ofNullable;

public class BF_Scoreboard {

    private final BF_Arena arena;
    private Scoreboard scoreboard;

    public BF_Scoreboard(BF_Arena arena) {
        this.arena = arena;

    }

    public void initScoreboard() {
        this.initCommandScoreboard(true);

        String sbHeaderPrefix = ChatColor.GREEN + "Battlefield" + ChatColor.RESET + " - " + ChatColor.YELLOW;
        String sbHeaderName = sbHeaderPrefix + this.arena.getName();

        if (sbHeaderName.length() > 32) {
            if (this.arena.getPrefix().length() <= 14) {
                sbHeaderName = sbHeaderPrefix + this.arena.getPrefix();
            } else {
                sbHeaderName = sbHeaderName.substring(0, 32);
            }
        }
        //
    }

    private void initCommandScoreboard(boolean addTeamEntry) {
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        for (BF_Team team : this.arena.getTeams()) {
            final Team newTeam = this.scoreboard.registerNewTeam(team.getName());
            newTeam.setPrefix(team.getColor().toString());
            newTeam.setSuffix(ChatColor.RESET.toString());
            newTeam.setColor(team.getColor());
            newTeam.setCanSeeFriendlyInvisibles(true);
            newTeam.setAllowFriendlyFire(false);
            newTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OTHER_TEAMS);
            if (addTeamEntry) {
                newTeam.addEntry(team.getName());
            }
        }
    }
}
