package top.mores.battlefield.game.combat;

import net.minecraft.server.level.ServerPlayer;

/**
 * 「这名玩家该用哪档回血」的查询入口。
 * <p>
 * 本系统只认这个接口，不认兵种、不认装备、不认队伍。M2 做兵种时只要
 * {@link CombatRegenService#setProfileProvider} 换一个实现（突击兵 {@code scaled(0.6, 1.4)}、
 * 支援兵压制更抗打…），回血结算本身一行都不用改。
 * <p>
 * 返回 {@code null} 表示「这个人没有特殊档位，用配置里的基础档」。
 */
@FunctionalInterface
public interface RegenProfileProvider {

    RegenProfile profileFor(ServerPlayer player);
}
