package top.mores.battlefield.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;
import top.mores.battlefield.Battlefield;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BattlefieldServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("battlefield").resolve("server_config.json");

    private static volatile Settings SETTINGS = Settings.defaults();

    private BattlefieldServerConfig() {
    }

    public static void init() {
        reload();
    }

    public static void reload() {
        try {
            Files.createDirectories(FILE.getParent());
            if (!Files.exists(FILE)) {
                writeDefault();
            }

            try (Reader reader = Files.newBufferedReader(FILE)) {
                Root root = GSON.fromJson(reader, Root.class);
                SETTINGS = Settings.from(root);
            }
        } catch (Exception e) {
            Battlefield.LOGGER.error("[Battlefield] 加载服务端配置失败，回退默认值: {}", FILE, e);
            SETTINGS = Settings.defaults();
        }
    }

    public static Settings get() {
        return SETTINGS;
    }

    private static void writeDefault() throws IOException {
        Root root = Root.defaults();
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(root, writer);
        }
    }

    public static final class Settings {
        public final int teamCap;
        public final int squadCap;
        public final int teamSquadCap;

        public final int captureIntervalTicks;
        public final int scoreAwardIntervalTicks;
        public final int captureBaseStep;
        public final int captureBonusStep;
        public final int captureEmptyDecayStep;
        public final boolean captureDecayOnTie;

        public final double areaRadiusScale;
        public final int outsideAreaKillTicks;

        public final int gameCountdownSeconds;
        public final int gameEndingSeconds;
        /** 阵亡后到允许部署之间的等待（tick）。界面在这个等待期间打开并显示倒计时。 */
        public final int deployOpenDelayTicks;

        public final int bombardDurationTicks;
        public final int bombardTotalBombs;
        public final float bombardRadius;
        public final float bombardDamageRadius;
        public final float bombardMaxDamage;

        public final int scoreStreakWindowTicks;
        public final int scoreStreakKillBonus;
        public final int scoreSquadWipeBonus;
        public final int scoreHeadshotKillBonus;
        public final int scoreKillBase;
        public final int scoreToastMergeWindowTicks;
        public final int scoreToastFlushAfterTicks;

        public final double v1StartBackwardDistance;
        public final double v1StartHeightBase;
        public final int v1StartHeightRandom;
        public final double v1TargetMinHeightOffset;
        public final double v1ArcHeightMin;
        public final double v1ArcHeightScale;
        public final int v1FlightTicks;
        public final double v1KillRadius;
        public final double v1MaxDamageRadius;
        public final float v1MaxDamage;

        public final int slotBaseX;
        public final int slotBaseZ;
        public final int slotSpacingBlocks;
        public final int maxSlots;
        public final int restoreBudgetMillis;
        public final int restoreChunkWindow;

        /** 是否用地图模板烘焙部署界面的底图。关掉后界面退回坐标示意图。 */
        public final boolean deployMapBakeEnabled;
        /** 底图精度：每像素代表多少格。越小越清晰，烘焙越慢、包越大。 */
        public final int deployMapBlocksPerPixel;
        /** 底图最长边像素上限，超过则自动降低精度。 */
        public final int deployMapMaxPixels;

        private Settings(int teamCap, int squadCap, int teamSquadCap,
                         int captureIntervalTicks, int scoreAwardIntervalTicks, int captureBaseStep, int captureBonusStep, int captureEmptyDecayStep, boolean captureDecayOnTie,
                         double areaRadiusScale, int outsideAreaKillTicks,
                         int gameCountdownSeconds, int gameEndingSeconds, int deployOpenDelayTicks,
                         int bombardDurationTicks, int bombardTotalBombs, float bombardRadius, float bombardDamageRadius, float bombardMaxDamage,
                         int scoreStreakWindowTicks, int scoreStreakKillBonus, int scoreSquadWipeBonus, int scoreHeadshotKillBonus, int scoreKillBase,
                         int scoreToastMergeWindowTicks, int scoreToastFlushAfterTicks,
                         double v1StartBackwardDistance, double v1StartHeightBase, int v1StartHeightRandom, double v1TargetMinHeightOffset,
                         double v1ArcHeightMin, double v1ArcHeightScale, int v1FlightTicks,
                         double v1KillRadius, double v1MaxDamageRadius, float v1MaxDamage,
                         int slotBaseX, int slotBaseZ, int slotSpacingBlocks, int maxSlots,
                         int restoreBudgetMillis, int restoreChunkWindow,
                         boolean deployMapBakeEnabled, int deployMapBlocksPerPixel, int deployMapMaxPixels) {
            this.teamCap = teamCap;
            this.squadCap = squadCap;
            this.teamSquadCap = teamSquadCap;
            this.captureIntervalTicks = captureIntervalTicks;
            this.scoreAwardIntervalTicks = scoreAwardIntervalTicks;
            this.captureBaseStep = captureBaseStep;
            this.captureBonusStep = captureBonusStep;
            this.captureEmptyDecayStep = captureEmptyDecayStep;
            this.captureDecayOnTie = captureDecayOnTie;
            this.areaRadiusScale = areaRadiusScale;
            this.outsideAreaKillTicks = outsideAreaKillTicks;
            this.gameCountdownSeconds = gameCountdownSeconds;
            this.gameEndingSeconds = gameEndingSeconds;
            this.deployOpenDelayTicks = deployOpenDelayTicks;
            this.bombardDurationTicks = bombardDurationTicks;
            this.bombardTotalBombs = bombardTotalBombs;
            this.bombardRadius = bombardRadius;
            this.bombardDamageRadius = bombardDamageRadius;
            this.bombardMaxDamage = bombardMaxDamage;
            this.scoreStreakWindowTicks = scoreStreakWindowTicks;
            this.scoreStreakKillBonus = scoreStreakKillBonus;
            this.scoreSquadWipeBonus = scoreSquadWipeBonus;
            this.scoreHeadshotKillBonus = scoreHeadshotKillBonus;
            this.scoreKillBase = scoreKillBase;
            this.scoreToastMergeWindowTicks = scoreToastMergeWindowTicks;
            this.scoreToastFlushAfterTicks = scoreToastFlushAfterTicks;
            this.v1StartBackwardDistance = v1StartBackwardDistance;
            this.v1StartHeightBase = v1StartHeightBase;
            this.v1StartHeightRandom = v1StartHeightRandom;
            this.v1TargetMinHeightOffset = v1TargetMinHeightOffset;
            this.v1ArcHeightMin = v1ArcHeightMin;
            this.v1ArcHeightScale = v1ArcHeightScale;
            this.v1FlightTicks = v1FlightTicks;
            this.v1KillRadius = v1KillRadius;
            this.v1MaxDamageRadius = v1MaxDamageRadius;
            this.v1MaxDamage = v1MaxDamage;
            this.slotBaseX = slotBaseX;
            this.slotBaseZ = slotBaseZ;
            this.slotSpacingBlocks = slotSpacingBlocks;
            this.maxSlots = maxSlots;
            this.restoreBudgetMillis = restoreBudgetMillis;
            this.restoreChunkWindow = restoreChunkWindow;
            this.deployMapBakeEnabled = deployMapBakeEnabled;
            this.deployMapBlocksPerPixel = deployMapBlocksPerPixel;
            this.deployMapMaxPixels = deployMapMaxPixels;
        }

        private static Settings defaults() {
            return new Settings(
                    16, 4, 16,
                    10, 60, 4, 2, 2, false,
                    1.6, 200,
                    10, 10, 20,
                    15 * 20, 30, 20f, 3f, 18f,
                    15 * 20, 50, 200, 25, 100,
                    6, 12,
                    60.0, 120.0, 81, 70.0,
                    45.0, 0.18, 30 * 20,
                    20.0, 30.0, 40.0f,
                    16384, 0, 2048, 8,
                    4, 48,
                    true, 3, 320
            );
        }

        private static Settings from(Root root) {
            Settings def = defaults();
            if (root == null) return def;

            return new Settings(
                    positive(root.teamCap, def.teamCap),
                    positive(root.squadCap, def.squadCap),
                    positive(root.teamSquadCap, def.teamSquadCap),
                    positive(root.captureIntervalTicks, def.captureIntervalTicks),
                    positive(root.scoreAwardIntervalTicks, def.scoreAwardIntervalTicks),
                    positive(root.captureBaseStep, def.captureBaseStep),
                    positive(root.captureBonusStep, def.captureBonusStep),
                    positive(root.captureEmptyDecayStep, def.captureEmptyDecayStep),
                    root.captureDecayOnTie == null ? def.captureDecayOnTie : root.captureDecayOnTie,
                    positiveDouble(root.areaRadiusScale, def.areaRadiusScale),
                    positive(root.outsideAreaKillTicks, def.outsideAreaKillTicks),
                    positive(root.gameCountdownSeconds, def.gameCountdownSeconds),
                    positive(root.gameEndingSeconds, def.gameEndingSeconds),
                    positive(root.deployOpenDelayTicks, def.deployOpenDelayTicks),
                    positive(root.bombardDurationTicks, def.bombardDurationTicks),
                    positive(root.bombardTotalBombs, def.bombardTotalBombs),
                    positiveFloat(root.bombardRadius, def.bombardRadius),
                    positiveFloat(root.bombardDamageRadius, def.bombardDamageRadius),
                    positiveFloat(root.bombardMaxDamage, def.bombardMaxDamage),
                    positive(root.scoreStreakWindowTicks, def.scoreStreakWindowTicks),
                    positive(root.scoreStreakKillBonus, def.scoreStreakKillBonus),
                    positive(root.scoreSquadWipeBonus, def.scoreSquadWipeBonus),
                    positive(root.scoreHeadshotKillBonus, def.scoreHeadshotKillBonus),
                    positive(root.scoreKillBase, def.scoreKillBase),
                    positive(root.scoreToastMergeWindowTicks, def.scoreToastMergeWindowTicks),
                    positive(root.scoreToastFlushAfterTicks, def.scoreToastFlushAfterTicks),
                    positiveDouble(root.v1StartBackwardDistance, def.v1StartBackwardDistance),
                    positiveDouble(root.v1StartHeightBase, def.v1StartHeightBase),
                    positive(root.v1StartHeightRandom, def.v1StartHeightRandom),
                    positiveDouble(root.v1TargetMinHeightOffset, def.v1TargetMinHeightOffset),
                    positiveDouble(root.v1ArcHeightMin, def.v1ArcHeightMin),
                    positiveDouble(root.v1ArcHeightScale, def.v1ArcHeightScale),
                    positive(root.v1FlightTicks, def.v1FlightTicks),
                    positiveDouble(root.v1KillRadius, def.v1KillRadius),
                    positiveDouble(root.v1MaxDamageRadius, def.v1MaxDamageRadius),
                    positiveFloat(root.v1MaxDamage, def.v1MaxDamage),
                    root.slotBaseX == null ? def.slotBaseX : root.slotBaseX & ~511,
                    root.slotBaseZ == null ? def.slotBaseZ : root.slotBaseZ & ~511,
                    Math.max(512, positive(root.slotSpacingBlocks, def.slotSpacingBlocks) & ~511),
                    positive(root.maxSlots, def.maxSlots),
                    positive(root.restoreBudgetMillis, def.restoreBudgetMillis),
                    positive(root.restoreChunkWindow, def.restoreChunkWindow),
                    root.deployMapBakeEnabled == null ? def.deployMapBakeEnabled : root.deployMapBakeEnabled,
                    positive(root.deployMapBlocksPerPixel, def.deployMapBlocksPerPixel),
                    positive(root.deployMapMaxPixels, def.deployMapMaxPixels)
            );
        }
    }

    private static int positive(Integer value, int def) {
        return value != null && value > 0 ? value : def;
    }

    private static double positiveDouble(Double value, double def) {
        return value != null && value > 0 ? value : def;
    }

    private static float positiveFloat(Float value, float def) {
        return value != null && value > 0 ? value : def;
    }

    private static final class Root {
        String _comment = "Battlefield 服务端热重载配置。执行 /bt reload 即可重新加载本文件。";

        Integer teamCap = 16;
        String teamCap_comment = "每个阵营的最大人数上限。";
        Integer squadCap = 4;
        String squadCap_comment = "每个小队的最大人数。";
        Integer teamSquadCap = 16;
        String teamSquadCap_comment = "每个阵营可创建的小队数量上限。";

        Integer captureIntervalTicks = 10;
        String captureIntervalTicks_comment = "战线占点逻辑刷新间隔（tick）。";
        Integer scoreAwardIntervalTicks = 60;
        String scoreAwardIntervalTicks_comment = "据点内持续得分的发放间隔（tick）。";
        Integer captureBaseStep = 4;
        String captureBaseStep_comment = "占点基础推进值。";
        Integer captureBonusStep = 2;
        String captureBonusStep_comment = "人数优势时每多 1 人的额外推进值。";
        Integer captureEmptyDecayStep = 2;
        String captureEmptyDecayStep_comment = "据点无人时向当前控制方回退的推进值。";
        Boolean captureDecayOnTie = false;
        String captureDecayOnTie_comment = "人数相等时是否向当前控制方缓慢回退。";

        Double areaRadiusScale = 1.6;
        String areaRadiusScale_comment = "服务端判定可活动区域时，对据点半径的倍率。";
        Integer outsideAreaKillTicks = 200;
        String outsideAreaKillTicks_comment = "玩家离开可活动区域后强制死亡所需时长（tick）。";

        Integer gameCountdownSeconds = 10;
        String gameCountdownSeconds_comment = "开局倒计时秒数。";
        Integer gameEndingSeconds = 10;
        String gameEndingSeconds_comment = "结算阶段持续秒数。";
        Integer deployOpenDelayTicks = 20;
        String deployOpenDelayTicks_comment = "阵亡后到允许部署之间的等待（tick，20=1 秒）。期间部署界面已打开但按钮不可用。";

        Integer bombardDurationTicks = 300;
        String bombardDurationTicks_comment = "区域轰炸持续时间（tick）。";
        Integer bombardTotalBombs = 30;
        String bombardTotalBombs_comment = "单次区域轰炸投弹总数。";
        Float bombardRadius = 20f;
        String bombardRadius_comment = "区域轰炸随机落点半径。";
        Float bombardDamageRadius = 3f;
        String bombardDamageRadius_comment = "单枚炸弹伤害半径。";
        Float bombardMaxDamage = 18f;
        String bombardMaxDamage_comment = "单枚炸弹最大伤害。";

        Integer scoreStreakWindowTicks = 300;
        String scoreStreakWindowTicks_comment = "连杀判定时间窗（tick）。";
        Integer scoreStreakKillBonus = 50;
        String scoreStreakKillBonus_comment = "连杀额外加分。";
        Integer scoreSquadWipeBonus = 200;
        String scoreSquadWipeBonus_comment = "团灭小队额外加分。";
        Integer scoreHeadshotKillBonus = 25;
        String scoreHeadshotKillBonus_comment = "爆头击杀额外加分。";
        Integer scoreKillBase = 100;
        String scoreKillBase_comment = "击杀基础分。";
        Integer scoreToastMergeWindowTicks = 6;
        String scoreToastMergeWindowTicks_comment = "同类加分提示合并窗口（tick）。";
        Integer scoreToastFlushAfterTicks = 12;
        String scoreToastFlushAfterTicks_comment = "加分提示最大延迟发送时间（tick）。";

        Double v1StartBackwardDistance = 60.0;
        String v1StartBackwardDistance_comment = "V1 起飞点相对出生点的后撤距离。";
        Double v1StartHeightBase = 120.0;
        String v1StartHeightBase_comment = "V1 起飞基础高度增量。";
        Integer v1StartHeightRandom = 81;
        String v1StartHeightRandom_comment = "V1 起飞高度随机范围（0~N-1）。";
        Double v1TargetMinHeightOffset = 70.0;
        String v1TargetMinHeightOffset_comment = "V1 起飞高度相对目标点的最小抬高值。";
        Double v1ArcHeightMin = 45.0;
        String v1ArcHeightMin_comment = "V1 弹道最小拱高。";
        Double v1ArcHeightScale = 0.18;
        String v1ArcHeightScale_comment = "V1 弹道拱高随水平距离增长的倍率。";
        Integer v1FlightTicks = 600;
        String v1FlightTicks_comment = "V1 飞行总时长（tick）。";
        Double v1KillRadius = 20.0;
        String v1KillRadius_comment = "V1 内圈即死半径。";
        Double v1MaxDamageRadius = 30.0;
        String v1MaxDamageRadius_comment = "V1 最大伤害半径。";
        Float v1MaxDamage = 40.0f;
        String v1MaxDamage_comment = "V1 外圈伤害衰减起点的最大伤害。";

        Integer slotBaseX = 16384;
        String slotBaseX_comment = "战场维度中第 0 号槽位的起点 X（方块，自动对齐到 512）。远离 0,0 以避开常驻出生区块。";
        Integer slotBaseZ = 0;
        String slotBaseZ_comment = "战场维度中槽位所在行的起点 Z（方块，自动对齐到 512）。";
        Integer slotSpacingBlocks = 2048;
        String slotSpacingBlocks_comment = "相邻槽位的间距（方块，至少 512）。应大于「地图宽度 + 512」，保证两局之间看不到、粒子与声音也传不过去。";
        Integer maxSlots = 8;
        String maxSlots_comment = "战场维度最多划分多少个槽位，即全服实例化对局并发上限。";
        Integer restoreBudgetMillis = 4;
        String restoreBudgetMillis_comment = "地图还原每 tick 最多占用的主线程时间（毫秒），所有还原任务共用。";
        Integer restoreChunkWindow = 48;
        String restoreChunkWindow_comment = "地图还原时预先异步加载的区块数。";

        Boolean deployMapBakeEnabled = true;
        String deployMapBakeEnabled_comment = "是否用地图模板烘焙部署界面底图（读模板区块数据，不加载世界）。关掉则退回坐标示意图。";
        Integer deployMapBlocksPerPixel = 3;
        String deployMapBlocksPerPixel_comment = "底图精度：每像素代表多少格。1 最清晰但很慢，3~4 适合战术图。";
        Integer deployMapMaxPixels = 320;
        String deployMapMaxPixels_comment = "底图最长边像素上限，超出自动降低精度。";

        static Root defaults() {
            return new Root();
        }
    }
}
