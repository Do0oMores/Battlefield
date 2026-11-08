# 第二阶段实际测试报告

环境：Windows、Java 17.0.12、Gradle 8.8 / ForgeGradle 6、Minecraft 1.20.1、Forge 47.4.16、TaCZ Curse `7401617`（Manifest：1.1.7-hotfix）、Maven 3.9.16。验证日期：2026-10-27，Asia/Shanghai。

## 执行结果

| 工作区与实际命令 | 通过 | 跳过 | 失败/错误 | 结果 |
| --- | ---: | ---: | ---: | --- |
| BFBackpack：`mvn -o -B -ntp test` | 64 | 1 | 0 | BUILD SUCCESS；前置 API v3 回归 |
| BFBackpack：`mvn -B -ntp -DskipTests clean install` | 未运行 | 未运行 | 0 | 安装 API classifier 成功；此命令未声称执行测试 |
| Battlefield：`gradlew.bat --offline test build runGameTestServer --console=plain` | 122 个单元测试 + 14 个真实 GameTest | 0 | 0 | BUILD SUCCESSFUL；含发布重混淆构建 |
| Bridge：`mvn -o -B -ntp clean verify` | 12 | 0 | 0 | BUILD SUCCESS |
| `tools/verify-phase2.ps1 -BFBackpackJar <已构建后端JAR> -JavaHome <JDK17>` | 产物/示例检查通过 | — | 0 | PHASE2_ARTIFACT_OK |
| `git diff --check` | 通过 | — | 0 | 无空白错误 |

BFBackpack 唯一跳过项是真实 MySQL 集成测试，没有提供数据库环境。最初离线 install 缺少 Maven 插件依赖，补齐后成功；没有将失败尝试描述为通过。

最终 Gradle 输出保存在本地 `build/phase2-final-gradle.log`，单元报告在 `build/reports/tests/test/index.html`，GameTest 日志在 `build/medical-gametest-run/logs/latest.log`。日志实际出现：`All 14 required tests passed :)`。这些运行产物不提交到 Git。

## 已覆盖

| 层次 | 证据与边界 |
| --- | --- |
| 反射桥接生命周期 | 6 个实际 transport 测试：未安装、异步初始化、版本不兼容、卸载、重载换代、异步响应、受控超时和停用。使用真正 Bukkit 接口的动态代理和可控 scheduler，不冒充 Mohist。 |
| JSON / ClassLoader | 12 个 Bridge 测试：两个隔离 loader、仅 JDK 参数和返回类型、硬依赖描述、大小/深度/字符串/数量限制、重复字段、版本溢出、错误玩家/兵种/快照、无效附件定义和未验证装备。 |
| 异步部署 | 10 个可控 Future/游戏执行器测试：两次读取均不阻塞、构造/提交仅在游戏执行器、最终权限撤销、revision 改变、玩家退出、对局结束、实体/部署点失效、构造失败、应用失败、多玩家互不阻塞及超时。没有固定 sleep。 |
| 防重复 | 6 个 Ledger 测试：同 ID 共享尝试与结果、一次性提交、不同载荷复用 ID 拒绝、同玩家并发拒绝、退出/停用取消后迟到结果无效、新生命允许新部署。 |
| 弹药与布局 | 5 个测试：与弹药站总上限一致、低上限不能超装、空弹匣策略、零备弹、非法参数和槽位冲突。 |
| 网络 | 4 个新增测试：四兵种和部署 ID 编解码、无 actor/resource/数量入参、非法兵种、超大预览数量、结果 ID 和消息长度。原有包测试全部保留。 |
| 真实 TaCZ | 4 个新增 GameTest：真实 AK47 和瞄准镜的构造/读回、缺失枪械与附件拒绝、实际弹药与多 Provider、资源快照失效/重建。首次运行实际索引 47 把枪、96 个附件。 |
| 原始库存保护 | 上述 GameTest 中使用同 UUID 的两个真实 FakePlayer 实例，验证进场快照不被第二次捕获覆盖、替换实例还原原物品/护甲/副手、重复还原不复制。不是完整网络 PlayerList.respawn 的替代验收。 |
| 原玩法回归 | 现有 91 个单元测试和 10 个医疗 GameTest 保留并通过，没有删除或弱化测试。 |

## 尚未实机验收

- 没有可用的 **Mohist + Battlefield + BFBackpack + BattlefieldBridge + TaCZ + 真人客户端** 组合服务器，本次不能宣称完整混合服务端兼容。
- 仍需验证 Mohist 上 Forge 能否反射发现 Bukkit、插件装卸顺序、实际 scheduler 线程映射和权限插件撤权与部署提交的同 tick 行为。
- 仍需真人验证主基地/据点/小队信标的完整死亡部署、PlayerList.respawn 后连接和实体替换、网络重试、双人同时部署、断线重连及离局回大厅。
- 未执行真正的 Inventory mixin 故障注入和“复活后应用失败→退出→原库存恢复”的端到端补偿测试；目前有应用异常路径、纯逻辑失败测试和原库存真实 GameTest 分别覆盖。
- 未验证第三方枪包的任意脚本扩展或 TaCZ 其他版本。真实资源未知时拒绝，不根据目录白名单臆测可部署。
- 未运行真实 MySQL；本阶段没有重写后端数据库。

GameTest 启动时有服务器默认配置创建、认证公钥获取和 Forge 版本检查的环境日志；不影响上述 14 个测试完成。原医疗 mixin 对可选 charged-shoot 方法的编译警告也存在于基线，`require=0` 保留，没有为消除警告删测试或弱化守卫。

## Git 与交付边界

Battlefield 基于 `master` 的 `784557d`，工作分支 `feature/bfbackpack-integration`。为保留原目录未提交的截图和客户端测试，在独立 Git worktree 开发；原 `D:/code/Battlefield` 的 master 与用户文件未改动。

工作区位于 `D:/code/BFBackpack/.work/battlefield-integration`。BFBackpack 的源码仍在 `18bb865`，本阶段仅构建/测试它和读取 API，未合并其 PR #1。完整逐文件变更见 [PHASE2-CHANGES.md](PHASE2-CHANGES.md)。
