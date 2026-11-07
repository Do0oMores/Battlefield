package top.mores.battlefield.client.deploy;

import net.minecraft.client.resources.language.I18n;
import top.mores.battlefield.deploy.DeploymentData;
import top.mores.battlefield.deploy.DeploymentOption;

import java.util.regex.Pattern;

/** Localizes standard Battlefield labels while preserving configured map and beacon names. */
public final class DeployText {
    private static final Pattern POINT_COUNT = Pattern.compile("^(交战中 · 敌|己方控制 · 我方) ([0-9]+) 人$");

    private DeployText() {}

    public static String tr(String key, Object... args) {
        return I18n.get("battlefield.deploy." + key, args);
    }

    public static String name(DeploymentOption option) {
        return switch (option.kind()) {
            case BASE -> tr(option.name().equals("守方主基地") ? "base.defenders" : "base.attackers");
            case CAPTURE_POINT -> option.id().startsWith("point:") ? tr("point", option.id().substring(6)) : option.name();
            case BEACON -> option.name();
        };
    }

    public static String detail(DeploymentOption option) {
        String detail = option.detail();
        if (option.kind() == DeploymentOption.Kind.BASE) return tr("spawn.initial");
        if (option.kind() == DeploymentOption.Kind.BEACON) return tr(option.selectable() ? "spawn.beacon" : "spawn.blocked");
        var count = POINT_COUNT.matcher(detail);
        if (count.matches()) return tr(count.group(1).equals("交战中 · 敌") ? "spawn.enemies" : "spawn.allies", count.group(2));
        return switch (detail) {
            case "己方控制" -> tr("spawn.friendly");
            case "敌方控制，无法部署" -> tr("spawn.enemy");
            case "中立据点，尚不可部署" -> tr("spawn.neutral");
            default -> detail;
        };
    }

    public static String denial(DeploymentData data) {
        if (data.waitTicks() > 0 && data.denyReason().startsWith("阵亡等待中")) return tr("wait", (data.waitTicks() + 19) / 20);
        return switch (data.denyReason()) {
            case "你当前不在对局中。" -> tr("deny.no_match");
            case "观战者无法部署。" -> tr("deny.spectator");
            case "对局尚未开始。" -> tr("deny.waiting");
            case "对局已结束。" -> tr("deny.ended");
            case "战斗中无法重新部署。" -> tr("deny.alive");
            default -> data.denyReason();
        };
    }
}
