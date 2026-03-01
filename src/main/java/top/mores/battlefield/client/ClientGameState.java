package top.mores.battlefield.client;

import net.minecraft.client.Minecraft;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.net.S2CSectorAreaPacket;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClientGameState {
    private ClientGameState() {
    }

    public static boolean inBattle = false;

    /**
     * 0=ATTACKERS 1=DEFENDERS 2=SPECTATOR
     */
    public static byte myTeam = 2;

    public static int attackerTickets = 0;
    public static int defenderTickets = 0;
    public static int remainingTimeTicks = 0;
    public static int myScore = 0;
    public static int myLastBonus = 0;
    public static int hudScore = 0;
    public static int hudLastScoreClientTick = -1;
    public static List<String> squadPlayerIds = Collections.emptyList();
    public static List<Integer> squadPlayerScores = Collections.emptyList();
    public static int squadTotalScore = 0;
    public static List<S2CGameStatePacket.PointInfo> points = Collections.emptyList();
    public static int phase = 0;
    public static String overlayTitle = "";
    public static String overlaySub = "";
    public static int overlayTicks = 0;
    private static final Map<String, Integer> lastProgressById = new HashMap<>();
    private static final Map<String, Byte> lastOwnerTeamById = new HashMap<>();
    public static final Map<String, Integer> deltaProgressById = new HashMap<>();
    public static int sectorIndex = 0;

    public static final int HUD_TOAST_HIDE_TICKS = 60; // 每条显示多久（3s=60tick）
    public static final int HUD_TOAST_MAX_LINES = 5;   // 最多显示几行
    public static final int HUD_TOAST_STACK_WINDOW = 14; // N tick 内同类合并

    // 2D 可活动区域（固定圈）
    public static List<S2CSectorAreaPacket.AreaCircle> attackerAreas = Collections.emptyList();
    public static List<S2CSectorAreaPacket.AreaCircle> defenderAreas = Collections.emptyList();
    public static final java.util.ArrayDeque<ScoreToast> SCORE_TOASTS = new java.util.ArrayDeque<>();

    public static final class ScoreToast {
        public int amount;           // +5
        public String text;          // 占领
        public int color;            // 颜色
        public int startTick;        // 产生时刻
        public int count;            // 合并次数（可选，用于 “x3”）
        public String key;           // 合并key（可选）

        public ScoreToast(int amount, String text, int color, int startTick, String key) {
            this.amount = amount;
            this.text = text;
            this.color = color;
            this.startTick = startTick;
            this.key = key;
            this.count = 1;
        }
    }

    public static void update(boolean inBattle0, byte myTeam0, int atk, int def,
                              int remainTicks, int score, int bonus,
                              List<String> squadIds, List<Integer> squadScores, int squadTotal,
                              List<S2CGameStatePacket.PointInfo> pts,
                              int phase0, String overlayTitle0, String overlaySub0, int overlayTicks0) {
        int prevMyScore = myScore;
        inBattle = inBattle0;

        if (!inBattle) {
            reset();
            return;
        }

        myTeam = myTeam0;
        attackerTickets = atk;
        defenderTickets = def;
        remainingTimeTicks = remainTicks;
        myScore = score;
        myLastBonus = bonus;

        if (myScore > prevMyScore) {
            hudScore += (myScore - prevMyScore);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                hudLastScoreClientTick = mc.player.tickCount;
            }
        }

        squadPlayerIds = squadIds;
        squadPlayerScores = squadScores;
        squadTotalScore = squadTotal;
        points = pts;
        phase = phase0;
        overlayTitle = overlayTitle0 == null ? "" : overlayTitle0;
        overlaySub = overlaySub0 == null ? "" : overlaySub0;
        overlayTicks = overlayTicks0;
        deltaProgressById.clear();

        for (S2CGameStatePacket.PointInfo p : pts) {
            Integer last = lastProgressById.put(p.id, p.progress);
            int dp = (last == null) ? 0 : (p.progress - last);
            deltaProgressById.put(p.id, dp);

            Byte lastOwner = lastOwnerTeamById.put(p.id, p.ownerTeam);
            if (lastOwner == null || myTeam > 1) continue;

            // 语音仅在“控制权真正切换”时触发，避免“只推进过进度但从未占下”也播失去点位。
            if (lastOwner != myTeam && p.ownerTeam == myTeam) {
                VoiceManager.play(ModSounds.VOICE_POINT_CAPTURED.get());
            } else if (lastOwner == myTeam && p.ownerTeam != myTeam && p.ownerTeam <= 1) {
                VoiceManager.play(ModSounds.VOICE_POINT_LOST.get());
            }
        }
    }

    public static void updateAreas(int newSectorIndex,
                                   List<S2CSectorAreaPacket.AreaCircle> atk,
                                   List<S2CSectorAreaPacket.AreaCircle> def) {

        boolean changed = (newSectorIndex != sectorIndex);
        sectorIndex = newSectorIndex;

        attackerAreas = (atk == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(atk);
        defenderAreas = (def == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(def);

        if (changed && Minecraft.getInstance().player != null) {
            VoiceManager.play(ModSounds.VOICE_SECTOR_PUSH.get());
        }
    }

    public static void reset() {
        inBattle = false;
        myTeam = 2;
        attackerTickets = 0;
        defenderTickets = 0;
        remainingTimeTicks = 0;
        myScore = 0;
        myLastBonus = 0;
        hudScore = 0;
        hudLastScoreClientTick = -1;
        squadPlayerIds = Collections.emptyList();
        squadPlayerScores = Collections.emptyList();
        squadTotalScore = 0;
        points = Collections.emptyList();
        phase = 0;
        overlayTitle = "";
        overlaySub = "";
        overlayTicks = 0;
        attackerAreas = Collections.emptyList();
        defenderAreas = Collections.emptyList();
        sectorIndex = 0;
        lastProgressById.clear();
        lastOwnerTeamById.clear();
        deltaProgressById.clear();
        SCORE_TOASTS.clear();
    }

    /**
     * 推送一条得分提示
     *
     * @param amount   分值（正数）
     * @param text     描述，如 "占领"
     * @param color    颜色ARGB
     * @param nowTick  当前tick（mc.player.tickCount）
     * @param mergeKey 合并key（同一个key在短时间内会合并到上一条），比如 "CAPTURE"
     */
    public static void pushScoreToast(int amount, String text, int color, int nowTick, String mergeKey) {
        if (amount <= 0) return;
        if (text == null || text.isBlank()) text = "得分";

        // 更新“中心总分HUD”的显示计时
        hudLastScoreClientTick = nowTick;

        // 过期清理
        pruneScoreToasts(nowTick);

        // ===== 可选：短时间内同类合并（连击感更强） =====
        ScoreToast last = SCORE_TOASTS.peekLast();
        if (last != null && mergeKey != null && mergeKey.equals(last.key)
                && (nowTick - last.startTick) <= HUD_TOAST_STACK_WINDOW) {
            last.amount += amount;
            last.count += 1;
            last.startTick = nowTick; // 刷新显示时间
            return;
        }

        // 正常新增
        SCORE_TOASTS.addLast(new ScoreToast(amount, text, color, nowTick, mergeKey));

        // 控制最大行数（超过就丢最早的）
        while (SCORE_TOASTS.size() > HUD_TOAST_MAX_LINES) {
            SCORE_TOASTS.pollFirst();
        }
    }

    public static void pruneScoreToasts(int nowTick) {
        while (!SCORE_TOASTS.isEmpty()) {
            ScoreToast t = SCORE_TOASTS.peekFirst();
            if (nowTick - t.startTick >= HUD_TOAST_HIDE_TICKS) {
                SCORE_TOASTS.pollFirst();
            } else break;
        }
        if (SCORE_TOASTS.isEmpty()) {
            // 没有toast了，是否隐藏中心HUD由你的原逻辑决定
        }
    }
}
