# Battlefield × BFBackpack · 第二阶段

Minecraft 1.20.1 / Forge 47.x / Java 17。Battlefield 负责当前战局、兵种、部署和 Forge 物品；BFBackpack 负责目录、解锁、权限、改装和持久化配装。本阶段替换旧编号背包接口，不开发完整武器编辑 GUI。

## 构建与安装

先构建 **BFBackpack PR #1 最新 API v3 / 协议 v2**（本阶段基线 `18bb865`），不能使用最初的 API v2 提交：

```sh
# 在 BFBackpack 仓库
mvn clean install
# 在 Battlefield 仓库
./gradlew test build
# 独立 Bukkit Bridge
mvn -f bukkit-bridge/pom.xml clean verify
```

客户端和服务端均安装 `build/libs/battlefield-1.0.jar`。服务端使用支持 Forge/Bukkit 的 Mohist，并安装项目实际依赖的 TaCZ（当前编译和实测版本：**1.1.7-hotfix**，Curse file `7401617`）及匹配枪包。

服务端 `plugins/` 放入 BFBackpack 插件和 `bukkit-bridge/target/BattlefieldBridge-1.0.0-SNAPSHOT.jar`。不要安装 `original-*.jar`，不要把 API classifier 当插件。Bridge 的 API 依赖为 provided，不 shade BFBackpack、Bukkit 或 Forge；仅将自己的 Gson 重定位。

BFBackpack 异步初始化时可返回 NOT_READY，Bridge 监听服务注册/注销并重新绑定。Forge 端按服务器 tick 重试发现插件；卸载和停服关闭注册、取消请求，迟到结果无法重新发装备。纯 Forge 没有 Bukkit/Bridge 时明确拒绝配装部署。

## 目录与弹药配置

原 BFBackpack 的 `BFEXAMPLE` / `example:*` 不会被认可。`integration-examples/bfbackpack/` 提供基于 TaCZ 默认包的四兵种示例，以及真实的弹药站、信标和 Minecraft 物品 Provider。它们不会自动覆盖服务器配置；开发服按需复制后执行 `/bfbackpack catalog reload`，原已保存但失效的 ID 仍由后端返回明确默认回退。

示例中的雪弹和铁剑仅为可构造物品，不代表新增手雷技能；不存在的修理工具等资源不会被空气代替。枪包更换后依然以运行时验证为准。

首次启动生成 `config/battlefield/deployment_loadout.json`：

```json
{
  "magazinePolicy": "FULL",
  "reserveMagazines": 3,
  "maxPermissionAgeMs": 1000,
  "slots": {"PRIMARY":0,"SECONDARY":1,"GADGET_1":2,"GADGET_2":3,"GRENADE":4,"MELEE":5}
}
```

`EMPTY` 可用于空弹匣策略；备用弹药、弹匣容量均来自服务端资源。总携弹仍受现有 `tacz_ammo_caps.json` 限制，与弹药站使用同一上限；两把枪共享弹药类型时不会叠加突破上限。备弹从主背包 9–35 槽放置，溢出拒绝部署，不丢到地面。

## 操作与诊断

保留现有地图、据点和信标选择界面。配装箭头改为四兵种，移除 1–9 号背包；客户端/服务端网络协议升级为 **7**，旧客户端会在握手时被拒绝。

仅管理员可用（权限等级 2）：

```text
/bt loadout bridge status
/bt loadout status <player>
/bt loadout preview <player> <ASSAULT|MEDIC|SUPPORT|RECON>
/bt loadout validate <player> <class>
/bt loadout class <player> <class>
```

`class` 走同一受验证部署入口，要求玩家在线、已进入对局并允许部署，战斗中的存活玩家无法利用它补枪。其他命令只读。目录与附件编辑仍使用 BFBackpack 管理命令；未来 GUI 使用文档中的受信服务端桥接操作。

## 验证

```sh
./gradlew test build
./gradlew runGameTestServer
mvn -f bukkit-bridge/pom.xml clean verify
```

GameTest 使用独立的 `build/medical-gametest-run`，不接触原服务器世界。已实际通过真实 Forge+TaCZ 的枪械/瞄准镜构造、弹药、资源快照重建，以及原始物品在玩家实体替换后的单次恢复测试。

完整线程、ClassLoader 边界、部署回退、第三阶段接口和验收状态见 [集成说明](docs/BFBACKPACK-INTEGRATION.md) 与 [测试报告](docs/PHASE2-TESTS.md)。**尚未进行真实 Mohist + BFBackpack + 真人客户端端到端联调，不能据此声称完整混合服务端兼容。**
