package top.mores.battlefield.deploy;

/**
 * 服务端解析出来的部署目标：界面用的 {@link DeploymentOption} + 真正的落点。
 * <p>
 * 只在服务端流转，绝不下发给客户端——客户端只能拿到 {@link DeploymentOption} 的投影。
 * 维度不放在这里：所有部署点都位于本局的战场维度，由 {@code BattlefieldGameManager} 在传送时补上。
 */
public record DeploymentTarget(DeploymentOption option, double x, double y, double z, float yaw) {

    public String id() {
        return option.id();
    }
}
