# Battlefield 模组 · 项目长期记忆

Minecraft 1.20.1 Forge 模组（Forge 47.4.16 / Java 17 / 依赖 TACZ 1.1.8），
运行于 **Mohist**（Forge + Bukkit 混合端）。实现"战地"式突破玩法：攻守阵营 + 战线推进 + 据点占领 + 票数。
完整结论见 **`docs/战地模式-开发结论总纲.md`**（新会话先读它）。

## 必须遵守的约定

1. **编译必须带绕代理参数**（本机 127.0.0.1:7890 常失效，JDK 证书也校验不过）：
   `./gradlew build -Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.proxyPort=0 -Dhttps.proxyPort=0 -Dhttp.nonProxyHosts='*' -Dnet.minecraftforge.gradle.check.certs=false -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`
2. **服务端与客户端必须放同一份 jar**，否则 Forge 握手阶段拒绝连接。
3. **`BattlefieldNet.PROTOCOL` 当前是 `"2"`**。若再改动任何包的字段布局，必须升号。
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
烟幕与 V1 尾迹发包、七项系统性缺陷（部署校验、跨维度存档、世界名、背包快照、计分隔离、包长约束、信标复活）。

未实测：部署包校验（需客户端发包）、信标实际放置。
最大缺口：无兵种体系、无武器/配件准入、无载具、无结算战绩。

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

测试环境：服务端 `C:\Users\MSN\Desktop\mohist1.20`，客户端 `C:\Users\MSN\Desktop\mc\.minecraft`。
实机测试流程已封装为用户级 skill `mc-mod-live-test`。
