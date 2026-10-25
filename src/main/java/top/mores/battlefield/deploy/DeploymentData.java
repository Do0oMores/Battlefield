package top.mores.battlefield.deploy;

import java.util.List;

/**
 * 服务端同步给部署界面的全部数据。
 * <p>
 * 只包含界面需要的东西：模式标识、战线信息、玩家在本次同步时的阵营与可部署状态，
 * 以及可选部署点列表。票数、据点进度、阶段、剩余时间等通用战局数据仍然走
 * {@code S2CGameStatePacket}，不在这里重复一份。
 *
 * @param modeId      {@link DeploymentRules#modeId()}，客户端据此选择顶部信息模板
 * @param mapName     arena 的 areaName（地图名），未配置时为空串
 * @param mapId       战区底图标识；为空表示这一局没有底图（静态世界/未开烘焙/还在烘），客户端画坐标示意图
 * @param sectorLabel 已格式化好的战线文案（例如「战线 S1（1/3）」），没有战线时为空串
 * @param team        0=攻方 1=守方 2=观战
 * @param canDeploy   本次同步时刻是否允许部署（阶段/阵营/存活/阵亡等待全部通过）
 * @param denyReason  不允许部署时的原因；允许时为空串
 * @param waitTicks   距允许部署还剩多少 tick，仅用于展示倒计时，合法性仍由服务端判定
 */
public record DeploymentData(String modeId,
                             String modeName,
                             String mapName,
                             String mapId,
                             String sectorLabel,
                             byte team,
                             boolean canDeploy,
                             String denyReason,
                             int waitTicks,
                             List<DeploymentOption> options) {

    /** 玩家不在对局中 / 对局已结束时的空数据。 */
    public static final DeploymentData CLOSED =
            new DeploymentData("", "", "", "", "", (byte) 2, false, "", 0, List.of());

    public DeploymentOption find(String optionId) {
        if (optionId == null) return null;
        for (DeploymentOption option : options) {
            if (option.id().equals(optionId)) return option;
        }
        return null;
    }

    /** 第一个可部署的位置；全部不可用时返回 null。 */
    public DeploymentOption firstSelectable() {
        for (DeploymentOption option : options) {
            if (option.selectable()) return option;
        }
        return null;
    }
}
