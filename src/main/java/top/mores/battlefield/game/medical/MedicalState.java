package top.mores.battlefield.game.medical;

import java.util.UUID;

/** One record in the existing overworld player/team SavedData. Temporary actions are never saved. */
public final class MedicalState {
    public enum Role { ASSAULT, MEDIC, SUPPORT, RECON }
    public Role role = Role.ASSAULT;
    public UUID life = UUID.randomUUID();
    public String match = "";
    public String team = "";
    public boolean deployed;
    private int reserve;
    private long revision;
    private Action action;
    public record Action(long id, UUID life, long start, long end) {}

    public int reserve() { return reserve; }
    public long revision() { return revision; }
    public Action action() { return action; }
    public boolean medic() { return deployed && role == Role.MEDIC; }
    public boolean available() { return deployed && (medic() || reserve == 1); }
    public void deploy(Role effectiveRole, String matchId, String teamId) {
        role = effectiveRole; match = matchId; team = teamId;
        life = UUID.randomUUID(); deployed = true;
        reserve = role == Role.MEDIC ? 0 : 1;
        action = null; revision++;
    }
    public void restore(Role effectiveRole, UUID lifeId, String matchId, String teamId, boolean valid, int stock) {
        role = effectiveRole; life = lifeId; match = matchId; team = teamId; deployed = valid;
        reserve = role == Role.MEDIC ? 0 : (stock == 1 ? 1 : 0);
        action = null;
    }
    public Action begin(long now, int duration, boolean needsCare) {
        if (!available() || action != null || !needsCare) return null;
        action = new Action(++revision, life, now, now + duration);
        return action;
    }
    /** Consume only the current attempt after its deadline and final eligibility check. */
    public boolean complete(Action expected, long now, boolean eligible, boolean needsCare) {
        if (action == null || action != expected || !life.equals(expected.life()) || now < expected.end()) return false;
        boolean heal = eligible && available() && needsCare;
        action = null; revision++;
        if (heal && !medic()) reserve = 0;
        return heal;
    }
    public void cancel() { if (action != null) { action = null; revision++; } }
    public void invalidate() { cancel(); deployed = false; life = UUID.randomUUID(); revision++; }
    public boolean receive(UUID expectedLife) {
        if (!deployed || medic() || reserve != 0 || !life.equals(expectedLife)) return false;
        reserve = 1; revision++; return true;
    }
}
