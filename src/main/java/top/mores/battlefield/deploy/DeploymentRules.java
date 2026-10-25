package top.mores.battlefield.deploy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署规则：由游戏模式提供「有哪些合法部署点」以及这些点位当前的展示状态。
 * <p>
 * 界面只消费 {@link DeploymentOption}，服务端在真正执行部署前会用它重新算一遍并匹配 id，
 * 所以规则既是界面数据的来源，也是权威判定的一部分，不存在两套逻辑。
 * <p>
 * 扩展方式：新增一个实现并在 {@link Registry} 里注册，然后给 arena 配置写
 * {@code "gameMode": "<modeId>"}。目前工程里只有突破模式，其余模式（征服/突袭/团队死斗）
 * 尚未实现，故不提供空实现，避免"看起来支持实际跑不通"。
 */
public interface DeploymentRules {

    /** 与 sectors.json 里 {@code gameMode} 对应的标识。 */
    String modeId();

    /** 界面顶部显示的模式名。 */
    String displayName();

    /**
     * 计算当前可展示/可选择的部署点。顺序即界面列表顺序。
     * <p>
     * 实现必须保证：{@link DeploymentOption.State#UNAVAILABLE} 的条目一定带有非空的
     * {@link DeploymentOption#detail()} 说明原因，且不会被 {@link #find(List, String)} 命中为可部署。
     */
    List<DeploymentTarget> targets(DeploymentContext ctx);

    /** 在刚算出来的一批目标里按 id 匹配「可部署」的那一个。 */
    static DeploymentTarget find(List<DeploymentTarget> targets, String optionId) {
        if (optionId == null) return null;
        for (DeploymentTarget target : targets) {
            if (target.id().equals(optionId) && target.option().selectable()) return target;
        }
        return null;
    }

    /**
     * 按 arena 配置的 {@code gameMode} 取规则。未注册的模式回落到突破模式，
     * 并由调用方记录一次告警（配置写错时不至于开不出局）。
     */
    static DeploymentRules of(String modeId) {
        return Registry.forMode(modeId);
    }

    /** 模式 → 规则。注册表刻意做成懒加载，避免与 {@code BattlefieldGameManager} 互相初始化。 */
    final class Registry {
        private static final Map<String, DeploymentRules> BY_MODE = new LinkedHashMap<>();

        static {
            register(BreakthroughDeploymentRules.INSTANCE);
        }

        private Registry() {
        }

        public static void register(DeploymentRules rules) {
            if (rules == null || rules.modeId() == null || rules.modeId().isBlank()) return;
            BY_MODE.put(rules.modeId(), rules);
        }

        public static DeploymentRules forMode(String modeId) {
            if (modeId != null) {
                DeploymentRules rules = BY_MODE.get(modeId);
                if (rules != null) return rules;
            }
            return BreakthroughDeploymentRules.INSTANCE;
        }

        public static boolean knows(String modeId) {
            return modeId != null && BY_MODE.containsKey(modeId);
        }
    }
}
