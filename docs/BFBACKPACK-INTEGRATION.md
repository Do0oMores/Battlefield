# 第二阶段集成契约

## 已确认的后端前置条件

开始前读取 BFBackpack PR #1 最新状态及 `18bb865832fd001d2cc607c058cafb4a7d8de3ef` 实际源码，确认 API=3、后端 JSON 协议=2：外部验证不占用数据库线程；`ValidationRegistration` 支持替换/注销；权限快照含捕获时间和会话代次，并可通过重新请求部署描述重新捕获。其 65 项测试实际运行，64 通过，真实 MySQL 用例 1 项因未配置数据库跳过。

本阶段没有修改 BFBackpack 数据库、目录或解锁实现，也没有合并 PR #1。独立 Bridge 编译依赖其 API classifier。

## 通信与 ClassLoader

```text
Battlefield Forge Mod
  BFBackpackServiceBridge
       │ 反射：String / int / long / Function / CompletableFuture
       ▼
BattlefieldBridge Bukkit 插件
       │ ServicesManager，API/DTO 由 BFBackpack 插件 loader 提供
       ▼
BFBackpack API v3 → 不可变 DeploymentLoadout
       │ 严格 JSON → 独立 LoadoutDto
       ▼
TaCZ/EquipmentFactory（Forge 主线程）→ EquipmentApplier → 当前 ServerPlayer
```

Bridge 插件 `plugin.yml` 声明 `depend: [BFBackpack]`，监听 ServiceRegisterEvent / ServiceUnregisterEvent，初始化未完成不授权。它把 `ValidationProvider` 注册为 `battlefield-bridge`；关闭自身句柄不会误删别的注册。

Forge 侧不引用 BFBackpack API、枚举、record 或 Bukkit ItemStack。连接入口为 `connect(int, Function<String,CompletableFuture<String>>)`，请求入口为 `request(String)`，返回 JDK Future/String。反射查找 Bukkit/插件后，插件方法调用均经 Bukkit scheduler 执行。两边即使各自加载同名协议类，也只交换 JSON。

每条请求携带连接代次；插件替换、服务注销、旧注册失效或 Forge 停止后，旧 Future 不能作为新连接的授权。最多 128 个请求，Bridge/BFBackpack/Forge 校验分别有超时边界；没有阻塞式等待。

JSON 上限 128 KiB（UTF-8），深度 24，每容器 512 项，字符串 2048 字符，资源 ID 128 字符。拒绝重复 key、非整数版本/修订、未知槽位、附件引用不匹配、玩家/兵种/保存快照不一致、非 VERIFIED 装备及其他提供方的校验绑定。不反序列化任意 Java 类型。

隔离 ClassLoader 测试证明 JDK 方法边界与独立 API 类型身份；受控 Bukkit scheduler 测试验证反射调用和重载生命周期。这不等于 Mohist 实机的可达性已经验证，安装后仍需检查 `/bt loadout bridge status`。

## 线程模型

| 操作 | 线程 |
| --- | --- |
| 玩家身份、战局/兵种、部署点、实体和 Inventory | Forge 服务端主线程 |
| Bukkit 服务发现后的插件调用、权限检查 | Bukkit 主线程（由 scheduler 调度） |
| SQL、永久解锁和确认缓存 | BFBackpack 有序数据库工作线程 |
| BFBackpack 运行时验证调用 | 后端独立校验池，返回 Future |
| TaCZ 资源索引读取和快照生成 | Forge 主线程 |
| 已发布资源索引的查询 | 只读不可变快照，允许校验线程读取 |
| 尚未缓存的精确附件组合构造 | 异步提交 Forge 主线程，结果发布为只读缓存 |
| 超时 | CompletableFuture/BFBackpack 的定时器 |

所有 Future 通过组合和主线程 Executor 衔接。数据库线程不等 Forge，Forge 不等数据库，Bukkit 不等 Forge；没有跨系统互相占锁等待。停服立即撤销部署令牌，关闭 Bridge 和资源验证器，待执行的游戏线程回调首先检查实例和令牌，不会在离局后发枪。

## TaCZ 实现

项目实际 JAR 的 Manifest 标记为 **1.1.7-hotfix**，使用官方同版本 tag `bd104cfe14bf4d3532d5ae61b46d36214b247537` 对照，并以该 JAR 完成编译和 GameTest：

- [`TimelessAPI`](https://github.com/MCModderAnchor/TACZ/blob/1.1.7-hotfix/src/main/java/com/tacz/guns/api/TimelessAPI.java)：服务端 gun/attachment/ammo 索引。
- [`GunItemBuilder`](https://github.com/MCModderAnchor/TACZ/blob/1.1.7-hotfix/src/main/java/com/tacz/guns/api/item/builder/GunItemBuilder.java)：`create/setId/setCount/setFireMode/build`，检查非空和真实 gunId。
- [`IGun` / GunItemDataAccessor](https://github.com/MCModderAnchor/TACZ/blob/1.1.7-hotfix/src/main/java/com/tacz/guns/api/item/nbt/GunItemDataAccessor.java)：检查 allowAttachmentType / allowAttachment，再 installAttachment，最后读回实际附件。未选择的内置附件由 TaCZ 自己保留。
- [`AttachmentItemBuilder`](https://github.com/MCModderAnchor/TACZ/blob/1.1.7-hotfix/src/main/java/com/tacz/guns/api/item/builder/AttachmentItemBuilder.java)：构造前先检查附件索引和 AttachmentType；Builder 本身不能代替资源存在校验。
- [`AttachmentDataUtils`](https://github.com/MCModderAnchor/TACZ/blob/1.1.7-hotfix/src/main/java/com/tacz/guns/util/AttachmentDataUtils.java)：读取安装扩容附件后的弹匣容量；AmmoItemBuilder 构造对应弹药，IGun API 设置初始弹匣，关闭额外膛内弹以保证携弹上限。

应用代码不调用 forceBuild，不手写枪械/附件 NBT。快照保存的只是字符串、枚举名、允许集合和资源代次，不向校验线程泄露 TaCZ 可变索引。AddReloadListenerEvent 使快照不可用，完整数据同步后在 Forge 主线程重建；旧组合缓存失效，提交时再比较资源代次。

Provider 支持 `TACZ`（兼容后端已有目录）、`TACZ_GUN`、`TACZ_AMMO`、`MINECRAFT_ITEM`、`BATTLEFIELD_ITEM`。普通物品分别限定 minecraft/battlefield 命名空间并验证真实注册表。BFEXAMPLE、不存在的装备和未知 Provider 均拒绝；不会用空气表示成功。

## 部署准备与提交

1. 绑定网络认证的 sender UUID、服务器当前对局 ID、阵营、生命代次和实体引用，校验部署阶段、死亡等待和选点。
2. 使用 UUID DeploymentId 登记一次请求；相同 ID 复用 Future/结果，另一 ID 并发请求被拒绝，不靠冷却实现防重。
3. 异步读取最新 BFBackpack 部署描述，要求状态 OK、每槽 VERIFIED、玩家/兵种/快照引用匹配。
4. Forge 主线程预构造六槽和备弹，检查完整性和可容纳性，此时不修改 Inventory。
5. 再次请求后端，重新捕获权限并比较配装、每枪改装、目录、会话和 Provider 代次；与 Prepare 不同则拒绝。
6. Forge 主线程检查最终权限快照年龄（默认 1000 ms）、资源代次、当前实体/对局/阵营/生命和当前部署点。
7. 领取一次性提交权；必要时 PlayerList.respawn。随后按 UUID 重新获取当前 ServerPlayer，再次解析部署点，不使用旧实体发装备。
8. 原子应用完整配装，显式清理战场内旧装备、护甲和副手；成功后登记实际战局兵种与医疗生命周期，再返回成功。

目标点在等待中易主或信标消失时返回明确失败，保留部署界面让玩家改选主基地；不使用 Prepare 时的坐标。非本流程触发的原生复活暂置观察模式并打开受验证部署入口，不能直接绕过后端重新领枪。

构造失败不复活、不改库存；应用异常先恢复当前战场库存，再退出战局恢复进场前快照，避免半套装备留在战场。退出、断线、结算和停服撤销未完成请求。原 PlayerSnapshotStore 的 SavedData、进场清空、离局还原和断线恢复机制保留；战斗弹药/耐久没有任何写回后端模板的入口。

权限是有时效的授权快照，并非跨两个框架的数据库锁。最终重新捕获和年龄上限尽量缩短窗口；部署与权限管理同 tick 的严格原子性仍需在实际 Mohist 调度语义下验收，不能声称分布式事务保证。

## 第三阶段 GUI 接口

客户端只能发送用户意图，不能发送任意玩家 UUID、资源 ID 或物品数量。新增 GUI 网络包应在 Forge 服务端取得 sender，调用 `LoadoutCoordinator.get().bridge()`：

| 目的 | 受信服务端 operation | 主要输入/返回 |
| --- | --- | --- |
| 四兵种配装 | `loadout`，分别四次 | soldierClass / ClassLoadout JSON |
| 可选装备与解锁状态 | `catalog` | soldierClass / WeaponCatalogEntry 列表；按 unlock/access 展示 |
| 当前枪械改装 | `customization` | weaponId / WeaponCustomization |
| 附件列表与解锁 | `attachments` | weaponId / AttachmentCatalogEntry 列表 |
| 保存装备选择 | `selectEquipment` | soldierClass、slot、内部 itemId、expected revision |
| 保存/删除附件 | `selectAttachment` / `removeAttachment` | weaponId、附件 slot、内部 itemId、expected revision |
| CAS 结果 | 所有写操作 | code=REVISION_CONFLICT 时重新读并让玩家确认 |
| 刷新装备预览 | `preview(player,class)` | 异步构造的六个 Forge ItemStack，只下发预览，不入服务器库存 |
| 最终部署 | `BackpackBridge.deploy(player,option,class,requestId)` | 当前两阶段流程与明确成功/失败结果 |

普通请求由 `request(uuid,class,operation)` 构建；写入和枪械查询使用 `request(JsonObject)`，字段包含 bridgeProtocol=1、operation、player、soldierClass 等，连接代次由桥接层覆盖注入。此方法仅供服务器内部使用，没有通用客户端 RPC 入口；也没有 grant/revoke 管理后门。

GUI 必须保留 requestId 直到成功/明确失败，超时重发使用同一 ID。配装/改装 revision 独立，不能混用；已确认的后端错误码原样保留于 BridgeFailure/JSON 结果。部署失败也必须保持界面可继续选择，不能乐观关闭或忽略物品应用失败。
