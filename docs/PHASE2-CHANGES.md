# 第二阶段文件变更清单

相对 Battlefield master（784557d）。保留原用户工作区和既有测试；没有删除原文件。

| 状态 | 文件 | 原因 |
| --- | --- | --- |
| 修改 | `.gitignore` | 忽略测试日志归档 |
| 修改 | `build.gradle` | 共享纯协议源码、隔离 Bukkit 实现、增加桥接测试运行依赖 |
| 修改 | `src/main/java/top/mores/battlefield/client/DeployPreviewClientState.java` | 保留地图与布局，以四兵种替换编号背包并处理异步结果 |
| 修改 | `src/main/java/top/mores/battlefield/client/deploy/DeployClientState.java` | 保留地图与布局，以四兵种替换编号背包并处理异步结果 |
| 修改 | `src/main/java/top/mores/battlefield/client/net/BattlefieldClientPackets.java` | 保留地图与布局，以四兵种替换编号背包并处理异步结果 |
| 修改 | `src/main/java/top/mores/battlefield/client/ui/RedeployScreen.java` | 保留地图与布局，以四兵种替换编号背包并处理异步结果 |
| 修改 | `src/main/java/top/mores/battlefield/command/BtCommands.java` | 管理员状态/预览/验证命令，兵种变更复用受控部署 |
| 修改 | `src/main/java/top/mores/battlefield/game/BattlefieldGameManager.java` | 保留战局/快照逻辑，改为准备与提交、复活后重取实体和失败补偿 |
| 修改 | `src/main/java/top/mores/battlefield/net/BattlefieldNet.java` | 四兵种、部署 UUID、结果绑定、限额预览及协议 7 |
| 修改 | `src/main/java/top/mores/battlefield/net/C2SDeployRequest.java` | 四兵种、部署 UUID、结果绑定、限额预览及协议 7 |
| 修改 | `src/main/java/top/mores/battlefield/net/C2SRequestBackpackPreview.java` | 四兵种、部署 UUID、结果绑定、限额预览及协议 7 |
| 修改 | `src/main/java/top/mores/battlefield/net/S2CBackpackPreview.java` | 四兵种、部署 UUID、结果绑定、限额预览及协议 7 |
| 修改 | `src/main/java/top/mores/battlefield/net/S2CDeployResultPacket.java` | 四兵种、部署 UUID、结果绑定、限额预览及协议 7 |
| 修改 | `src/main/java/top/mores/battlefield/server/BackpackBridge.java` | 移除旧 SingleBackpack 和 Bukkit ItemStack 转换，转接新后端 |
| 新增 | `README.md` | 双项目构建、安装、配置与验证入口 |
| 新增 | `bukkit-bridge/.gitignore` | 独立 Bukkit Bridge 构建、依赖和插件描述 |
| 新增 | `bukkit-bridge/pom.xml` | 独立 Bukkit Bridge 构建、依赖和插件描述 |
| 新增 | `bukkit-bridge/src/main/java/top/mores/battlefield/bukkit/BridgeLifecycle.java` | Bukkit API 服务发现、注册生命周期与受信 JSON 操作 |
| 新增 | `bukkit-bridge/src/main/java/top/mores/battlefield/protocol/BridgeFailure.java` | 独立 DTO、严格 JSON 边界、版本与明确错误码 |
| 新增 | `bukkit-bridge/src/main/java/top/mores/battlefield/protocol/BridgeProtocol.java` | 独立 DTO、严格 JSON 边界、版本与明确错误码 |
| 新增 | `bukkit-bridge/src/main/java/top/mores/battlefield/protocol/EquipmentSlot.java` | 独立 DTO、严格 JSON 边界、版本与明确错误码 |
| 新增 | `bukkit-bridge/src/main/java/top/mores/battlefield/protocol/LoadoutDto.java` | 独立 DTO、严格 JSON 边界、版本与明确错误码 |
| 新增 | `bukkit-bridge/src/main/java/top/mores/battlefield/protocol/LoadoutDtoAdapter.java` | 独立 DTO、严格 JSON 边界、版本与明确错误码 |
| 新增 | `bukkit-bridge/src/main/java/top/mores/battlefield/protocol/SoldierClass.java` | 独立 DTO、严格 JSON 边界、版本与明确错误码 |
| 新增 | `bukkit-bridge/src/main/resources/plugin.yml` | 独立 Bukkit Bridge 构建、依赖和插件描述 |
| 新增 | `bukkit-bridge/src/test/java/top/mores/battlefield/bukkit/BridgeClassLoaderTest.java` | ClassLoader 与协议/输入安全验证 |
| 新增 | `bukkit-bridge/src/test/java/top/mores/battlefield/protocol/BridgeProtocolTest.java` | ClassLoader 与协议/输入安全验证 |
| 新增 | `docs/BFBACKPACK-INTEGRATION.md` | 架构/线程/第三阶段契约、实际测试及文件清单 |
| 新增 | `docs/PHASE2-CHANGES.md` | 架构/线程/第三阶段契约、实际测试及文件清单 |
| 新增 | `docs/PHASE2-TESTS.md` | 架构/线程/第三阶段契约、实际测试及文件清单 |
| 新增 | `integration-examples/bfbackpack/attachments.yml` | 真实默认枪包、多 Provider 和四兵种目录示例 |
| 新增 | `integration-examples/bfbackpack/class-rules.yml` | 真实默认枪包、多 Provider 和四兵种目录示例 |
| 新增 | `integration-examples/bfbackpack/weapons.yml` | 真实默认枪包、多 Provider 和四兵种目录示例 |
| 新增 | `src/gameTest/java/top/mores/battlefield/LoadoutGameTests.java` | 真实 Forge/TaCZ 构造和快照恢复验证 |
| 新增 | `src/main/java/top/mores/battlefield/bridge/BFBackpackServiceBridge.java` | Forge 侧 JDK 反射通信、换代、超时和卸载取消 |
| 新增 | `src/main/java/top/mores/battlefield/command/LoadoutCommands.java` | 管理员状态/预览/验证命令，兵种变更复用受控部署 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/AsyncDeploymentFlow.java` | 两阶段异步部署、幂等令牌、弹药策略和集中库存应用 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/DeploymentAmmoPolicy.java` | 两阶段异步部署、幂等令牌、弹药策略和集中库存应用 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/DeploymentEquipmentLayout.java` | 两阶段异步部署、幂等令牌、弹药策略和集中库存应用 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/DeploymentLedger.java` | 两阶段异步部署、幂等令牌、弹药策略和集中库存应用 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/DeploymentSettings.java` | 两阶段异步部署、幂等令牌、弹药策略和集中库存应用 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/EquipmentApplier.java` | 两阶段异步部署、幂等令牌、弹药策略和集中库存应用 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/EquipmentFactory.java` | 两阶段异步部署、幂等令牌、弹药策略和集中库存应用 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/LoadoutCoordinator.java` | 两阶段异步部署、幂等令牌、弹药策略和集中库存应用 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/tacz/TaczGunFactory.java` | 真实 TaCZ 资源索引、精确附件组合与正常 Builder 构造 |
| 新增 | `src/main/java/top/mores/battlefield/loadout/tacz/TaczResourceValidator.java` | 真实 TaCZ 资源索引、精确附件组合与正常 Builder 构造 |
| 新增 | `src/test/java/top/mores/battlefield/bridge/BridgeLifecycleTest.java` | 桥接生命周期、可控 Future、幂等性、弹药和数据包测试 |
| 新增 | `src/test/java/top/mores/battlefield/loadout/AsyncDeploymentFlowTest.java` | 桥接生命周期、可控 Future、幂等性、弹药和数据包测试 |
| 新增 | `src/test/java/top/mores/battlefield/loadout/DeploymentAmmoPolicyTest.java` | 桥接生命周期、可控 Future、幂等性、弹药和数据包测试 |
| 新增 | `src/test/java/top/mores/battlefield/loadout/DeploymentLedgerTest.java` | 桥接生命周期、可控 Future、幂等性、弹药和数据包测试 |
| 新增 | `src/test/java/top/mores/battlefield/net/LoadoutPacketTest.java` | 桥接生命周期、可控 Future、幂等性、弹药和数据包测试 |
| 新增 | `tools/VerifyExampleCatalog.java` | 验证产物隔离和用真实 BFBackpack 解析示例 |
| 新增 | `tools/verify-phase2.ps1` | 验证产物隔离和用真实 BFBackpack 解析示例 |
