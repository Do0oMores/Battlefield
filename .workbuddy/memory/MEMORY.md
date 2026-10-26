# Battlefield 模组 · 项目长期记忆

Minecraft 1.20.1 Forge 模组（Forge 47.4.16 / Java 17 / 依赖 TACZ 1.1.8），
运行于 **Mohist**（Forge + Bukkit 混合端）。实现"战地"式突破玩法：攻守阵营 + 战线推进 + 据点占领 + 票数。
完整结论见 **`docs/战地模式-开发结论总纲.md`**（新会话先读它）。

## 必须遵守的约定

1. **编译必须带绕代理参数**（本机 127.0.0.1:7890 常失效，JDK 证书也校验不过）：
   `./gradlew build -Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.proxyPort=0 -Dhttps.proxyPort=0 -Dhttp.nonProxyHosts='*' -Dnet.minecraftforge.gradle.check.certs=false -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`
2. **服务端与客户端必须放同一份 jar**，否则 Forge 握手阶段拒绝连接。
3. **`BattlefieldNet.PROTOCOL` 当前是 `"5"`**（历史：2 烟幕包 / 3 消息表移动 / 4 部署界面 optionId / 5 战区底图）。
   若再改动任何包的字段布局，必须升号。
4. **队伍/小队/背包快照的 SavedData 一律锚定 `server.overworld()`**，不要用 `player.serverLevel()`。
5. **新增世界不要靠 `Bukkit.createWorld()` 生成**（会跑默认地形生成器）；实例世界必须"先复制目录再加载"。
6. **配置里写维度用 `minecraft:overworld`**。`minecraft:world` 是非法维度键（主世界文件夹叫 world，但键是 overworld）。

## 关键实证（已实测，不要再猜）

- Mohist 把 Bukkit 世界注册为 `minecraft:<世界名>`（实测有 `world2`、`lobby`）；主世界键恒为 `minecraft:overworld`。
- `Bukkit.createWorld()` 在 Mohist 上**会成功**——不抛异常、静默建世界、永久加载、默认 NORMAL 地形、主线程卡顿。这是坑不是特性。
- `online-mode=false` 必须配 `enforce-secure-profile=false`，否则离线客户端卡在 `Connecting`，两端都不报原因。
- SavedData 文件外层有 `data` + `DataVersion` 包裹，读字段要先取 `["data"]`。
- `ServerLevel` 的逐人粒子方法是 `sendParticles(ServerPlayer, T, boolean, ...)`（同名重载）；`force=true` 内部阈值 512 格。
- `PacketDistributor.TargetPoint` 第 4 参是**半径本身**，不是半径平方。
- `keepInventory` 等 gamerule 是 per-level 且随 level.dat 复制，新世界必须显式覆盖。

## 当前状态

已修并实测通过：性能六项（sendState / 状态包降频 / 冻结传送 / 移动区域遍历 / 记分板 / 弹药上限 IO）、
烟幕与 V1 尾迹发包、七项系统性缺陷（部署校验、跨维度存档、世界名、背包快照、计分隔离、包长约束、信标复活）、
**100 血基线 + 脱战回血 + 动态回血上限**（见下「回血底盘」）、**医疗包系统**（见下）。

未实测：部署包校验（需客户端发包）、信标实际放置、死亡→部署复活后的 6 秒冷却（需点界面）。
最大缺口：无武器配件准入、无载具、无结算战绩。

## 医疗包系统（2026-10-26，基线 `5e16263` + 未提交改动）

四兵种（突击/医疗/支援/侦察，仅部署时选择）+ 自疗（C）+ 医疗兵投递（J）+ 全套 TACZ 输入拦截 mixin。
README 见 `docs/医疗包系统-实现与验证.md`。要点：

- **自疗不再一次加满**：14 tick 动作截止只「消耗医疗包 + 登记回血窗口」，
  之后每 tick `healPlayer(step, false)` 补 `maxHealth / medicalHealTicks`（默认 40 tick ≈ 50 HP/s），
  补满或到期才 `resetRegenCeiling`。窗口是 `MedicalPouchService.DRIPS`（临时 map，不落盘，按 `life` 绑命）。
  **窗口期间不锁动作**，死亡/重部署/离局/切维度立即终止且不补上限。
- 储备 0/1 与生效角色存在 `TeamSavedData.medical`（主世界 SavedData）；动作/装备快照/投递只在内存。
- **`src/gameTest` 不在 `build` 里**：改 `MedicalConfig` 的 record 参数必须同步 `MedicalGameTests` 里
  两处 `new MedicalConfig(...)`，否则只有跑 `runGameTestServer` 才会暴露。
  GameTest 跑法：`./gradlew runGameTestServer -x downloadAssets -x downloadMCMeta` + 本机绕证书参数。

## 代码线现状（2026-10-25 已合并，单一主干）

**只有一条线：`master` @ `2fe3c80`**，主工作区与 `.claude/worktrees/minecraft-battlefield-audit-92cc3c`
（分支 `claude/...-audit-92cc3c`）**指向同一提交**，工作区都干净。

合并历史：
- `caad590`（origin/master，含 PR #43~#46：每局临时世界、小队面板、部署界面、修 NoClassDefFoundError）
- `812755e` 把主工作区未提交的三轮修复 3-way 移植到新 master
- `e5b98f9` 审计修复 A1~A10 + 战场世界槽位化
- `2fe3c80` 补文档与截图

原主工作区那份未提交改动已确认是子集（20 文件里 15 个逐字节一致，5 个为移植时的冲突解决版本），
备份在 `.workbuddy/backup/2026-10-25-main-uncommitted.patch` 与 `PlayerSnapshotStore.java`。
worktree 已无独有内容，可安全 `git worktree remove`。

**部署 jar（553KB / 142 类）来自 `e5b98f9` 的源码**；`master` 后续只加了文档，源码未变。
改源码后要重新 `build` 并同时更新服务端与客户端 mods。

### 已修复的 P0（审计报告 A 系列，已核实代码）

- **A3**：模板世界不再是 `minecraft:world`；新增 `data/battlefield/dimension/battle.json`
  预注册维度 `battlefield:battle`（flat 生成器），不再复制运行中的主世界
- **A6**：`ScoreManager.clearPlayer()` 不再调 `SquadScoreManager.clearAll()`，小队分按对局清
- **A7**：`SquadSavedData` 已删除，小队数据改为按对局在内存分桶
- **A5**：区域包改为按本局参与者发送，不再依赖维度做隔离
- 新增 `server/arena/` 槽位体系（模板/几何/注册/还原）、`PacketRateLimiter`、`BattleTeleport`

完整审计见 `docs/战地模式-审计报告-2026-10-24.md`。

### 兵种系统的技术底盘（2026-10-25 核实，只查不改）

- **武器准入有官方钩子**：TACZ `GunShootEvent` / `GunFireEvent` 在服务端发布且可取消，
  `LivingEntityShoot.shoot` 取消后返回 `ShootResult.FORGE_EVENT_CANCEL`（字节码已确认）→ 无需 mixin 即可让非法枪打不响。
- **枪械分类只能取 `CommonGunIndex.getType()` 的字符串**（数据包自由填），必须自己维护 gunId→兵种白名单，不能只信 type。
- **回血底盘已重建（2026-10-25，替代下面那条旧结论）**：
  `keepPlayerFed()` 现在把饥饿钉在 **17**（原版门槛 18 → 自然回血彻底关闭，且「不等于就写」防止吃食物顶回 18+），
  `game/combat/CombatRegenService` **每 0.1 秒结算 +1 点**（`regenIntervalTicks=2 / regenHealPerInterval=1.0`，
  10 HP/s，逐点上涨而不是整段跳），延迟 6 秒、压制时 ×0.25 且 +3 秒。
  100 血是真的改 `Attributes.MAX_HEALTH`（`PlayerHealthService`，固定 UUID 的永久修饰器，进局挂/离局摘）。
  配置在 `server_config.json` 的 12 个新字段（`CombatConfig` 校验，`[0,1]` 用 `fraction()` 钳制）。
  兵种倍率通过 `CombatRegenService.setProfileProvider(...)` + `RegenProfile.scaled(delay, rate)` 注入，结算逻辑不用改。
  **`gunDamageScale` 仍是 1.0，单发伤害没有实测回填 → 100 血下 TTK 现在是原来的 5 倍，这是已知未完成项。**
- **动态回血上限（2026-10-26 已落地，战地式伤情）**：
  受伤时 `ceiling -= (真实损失 - min(损失, maxRecoverableDamage))`，只能降不能升，默认 30（100 血标尺）。
  挂在 `LivingDamageEvent`（LOWEST）：护甲/附魔/吸收盾都已结算完，`getAmount()` 就是真实掉血；
  **不要改回 `LivingHurtEvent`**（会把伤害算多）。
  **公式与设计稿的偏差**：设计稿 `min(旧上限, 新血量+可恢复量)` 在「回血途中再挨一下」时会把还没回上来的血
  判成永久伤，实现取 `min(旧上限, max(旧上限-永久伤, 新血量+可恢复量))`，两种场景都自洽。
  治疗入口 `PlayerHealthService.healPlayer(player, amount, resetRegenCeiling)`（死人不救 / 不超上限）。
  指令 `/bt heal|regeninfo [player]`，`/battlefield` 是 `/bt` 的 **redirect 别名**。
  已知缺口：客户端血条淡红段不反映 ceiling，要修得发新包（PROTOCOL 5→6）。
- 旧的「keepPlayerFed 喂 20 导致原版回血满速」结论已作废，不要再按它设计。
- **配装源头是外部 Bukkit 插件 `Backpack`**（`server/BackpackBridge` 反射，9 个槽位，部署界面已能预览）。
  兵种限定武器的真正落点在配装，而不是开火拦截。
- `mixins.json` 已配好但 `mixins` 数组为空 —— 至今零 mixin，新增会引入 Mohist 上的首测风险。
- 已具备可复用的骨架：受伤/击杀事件（`ScoreManager`）、队伍判定（`TeamManager`）、
  客户端输入封锁（`BattlefieldClientMovementBlocker`）、世界空间渲染（`BattlefieldAreaRenderer`）、
  弹药写入（`TaczAmmoStationBlock` 的 count/giveInventoryAmmo）、信标全套（`RespawnBeacon*`）。


测试环境：服务端 `C:\Users\MSN\Desktop\mohist1.20`，客户端 `C:\Users\MSN\Desktop\mc\.minecraft`。
实机测试流程已封装为用户级 skill `mc-mod-live-test`。
