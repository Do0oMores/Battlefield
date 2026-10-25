package top.mores.battlefield.deploy;

/**
 * 一个可供展示与选择的部署位置。
 * <p>
 * 这是纯粹的界面数据：只有稳定标识、类型、状态和展示文案，不含任何服务端对象，
 * 也不含服务端坐标之外的判定信息。客户端拿到的就是这份结构的序列化结果，
 * 不存在"客户端持有 ServerLevel / 玩家实体"的可能。
 * <p>
 * {@code id} 在整个对局内稳定（{@code base} / {@code point:A} / {@code beacon:<uuid>}），
 * 服务端收到部署请求时按这个 id 重新构建一遍可选点再匹配，因此客户端无法凭 id 凭空造出落点。
 */
public record DeploymentOption(String id,
                               Kind kind,
                               State state,
                               String name,
                               String detail,
                               double x,
                               double z) {

    public enum Kind {
        /** 阵营主出生点。 */
        BASE,
        /** 当前战线上由己方控制（或敌方控制）的据点。 */
        CAPTURE_POINT,
        /** 小队复活信标。 */
        BEACON
    }

    public enum State {
        /** 可以部署。 */
        AVAILABLE,
        /** 可以部署，但当前处于交火/争夺状态，属于风险提示。 */
        CONTESTED,
        /** 不可部署，{@link #detail()} 必须说明原因。 */
        UNAVAILABLE
    }

    /** 是否允许作为部署目标（交战中仍允许，只是提示风险）。 */
    public boolean selectable() {
        return state != State.UNAVAILABLE;
    }
}
