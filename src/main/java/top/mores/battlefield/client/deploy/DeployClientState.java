package top.mores.battlefield.client.deploy;

/**
 * 部署界面跨会话保留的一点客户端偏好：上次选的配装槽位、上次选的部署点。
 * <p>
 * 只影响"下次打开时默认选哪个"，不参与任何合法性判断。对局重置时由
 * {@code ClientGameState#reset()} 一并清掉，避免上一局的落点被带进下一局。
 */
public final class DeployClientState {

    private static final int MIN_BP = 1;
    private static final int MAX_BP = 9;

    private static int backpackSlot = 1;
    private static String optionId = "";

    private DeployClientState() {
    }

    public static int backpackSlot() {
        return backpackSlot;
    }

    public static void backpackSlot(int slot) {
        backpackSlot = Math.max(MIN_BP, Math.min(MAX_BP, slot));
    }

    public static String optionId() {
        return optionId;
    }

    public static void optionId(String id) {
        optionId = id == null ? "" : id;
    }

    public static void reset() {
        backpackSlot = 1;
        optionId = "";
    }
}
