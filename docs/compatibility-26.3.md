# Minecraft 26.3 兼容性与压力测试

记录日期：2026-10-27。Windows x64、Microsoft OpenJDK 25.0.2、Fabric Loader 0.19.5、Fabric API 0.162.0+26.3。测试使用独立 Gradle 运行目录，没有更改 Orbit 用户实例。

## 已执行的范围

以下 11 个模组在**同一进程**加载并完成集成测试和压力场景。固定下载地址及 SHA512/SHA256 存在 [`tests/compatibility-lock.json`](../tests/compatibility-lock.json)。

| 模组 | 实测版本 | 适配与验证范围 |
| --- | --- | --- |
| Fabric Carpet | 26.3+v261008 | 屏障在整个 tickServer 调用外；直接重入 tickServer 也受维护状态保护。未覆盖任意 Scarpet 脚本。 |
| Carpet TIS Addition | 1.82.4-mc26.3 | 维护期间拦住独立的 fake player network/action 队列；排队两类动作，实测暂停期间不执行、结束后各执行一次。 |
| Carpet AMS Addition | mc26.3-v26.3.1 | 同时加载并运行区域往返；没有发现本次范围内需要专用挂钩的路径，未逐项开启全部规则。 |
| Gugle Carpet Addition | v2.12.9+build.104 | 同时加载并运行区域往返；全局配置、机器人列表和其他独立 JSON 不纳入区域备份。 |
| Carpet EDTP Addition | 1.11 | 同时加载并运行区域往返；不备份权限等全局设置。 |
| Carpet Extra Extras | 26.3-1.5.0 | 补存 `cee$spawnedInLazyChunk`；将 TNT 标记设为 true 后实测恢复保留。其余规则没有穷举测试。 |
| Lithium | mc26.3-0.26.2-fabric | 使用其改写后的计划刻队列和区块缓存；验证剩余延迟、POI、方块实体、实体与区块重新实例化。 |
| VMP | 0.2.0+beta.7.238+26.3 | 适配替换后的距离传播器；恢复等级全部入队后再推进 holder。既有异步加载先完成票据清理，新加载请求延后。**不支持开启异步 spreadplayers 命令**。 |
| EntityCollisionOptimizer | 1.0.0-mc26.3-alpha.11 | 原生 FFM 后端实际启用；恢复后使方块碰撞缓存失效，实体通过正常 tracking 生命周期重建。每块查询恢复的 4 个实体，五轮共 1,024 个实体。 |
| Teleport Commands Fabric | 2.4.1 | tick 驱动的服务随外层屏障暂停；恢复前取消目标维度的 Wild 搜索并释放票据。带连接的模拟玩家验证备份保留请求、恢复取消请求且玩家仍在线。未完成真实客户端 TPA/RTP 全流程测试。 |
| C2ME | 0.4.2-alpha.0.106+26.3 | 维护屏障接入 C2ME scheduler 与 NoTickVD：等待调度/票据静止，保存并暂时停放选区 root tickets，再由 C2ME 正常降级；恢复时恢复票据并让 C2ME 正常升级。检测 C2ME 存储线程记录的写入/删除/flush 错误。通过 C2ME 单独基础与 10,000 未加载区块场景，以及下述 11 模组组合。 |

Carpet Extra（gnembon 项目）与 Carpet Extra Extras 是不同项目。本次没有拿 26.2 的 Carpet Extra 包冒充 26.3 兼容版本。组合测试中默认关闭 C2ME gcFree serializer；另一次打开该选项并启用 TIS fake-player ticks-like-real-player、GCA 延迟任务后，连同真实 C2ME 后台存储错误探针通过。C2ME-only 和该组合均是本记录所列特定版本与场景的结果。

适配代码按模组放在 `src/main/java/org/edtp/edb/adaptors/<mod>/`；需要 Mixin 的放在各自的 `mixin/` 子目录，配置位于 `src/main/resources/adaptors/<mod>.mixins.json`。Carpet、TIS Addition、GCA、VMP 和 C2ME 有独立 Mixin 配置；Carpet Extra Extras、EntityCollisionOptimizer 与 Teleport Commands Fabric 使用适配器代码，不各自声明单独 Mixin 配置。

“同时加载通过”只证明表中版本和实际场景。不同 additions 的每个规则、任意 Scarpet/其他模组脚本及任意第三方异步写世界代码均未获得无损保证。普通 LivingEntity 的全部瞬态状态没有被补齐；不保证任意实体或模组脚本在恢复后完整重演原时间线。

假人回归覆盖 UUID、背包、生命值、饥饿值、经验、游戏模式、物品使用进度和 ActionPack 全部已记录字段。挂载用例包括单人载具、双假人同船、嵌套载具，以及 `[猪, 假人, 猪]` 的交错座位；断言直接载具 UUID 和乘客顺序。备份后把一个假人移出选区、另一个下线，并新增假人，恢复后分别找回、重建和删除。开启 TIS `fakePlayerTicksLikeRealPlayer`，验证 GCA 已下线假人的两个延迟钓鱼任务不会残留或重复。

## 实际发现并修复的问题

1. 原先在 tickServer 内部取切面会受其他 Mixin 回调位置影响。现在从 `processPacketsAndTick` 包住整个调用，连同 DedicatedServer/GameTestServer 覆盖方法及 Fabric/Carpet tick 回调一起暂停。测试断言维护期间 START/END_SERVER_TICK 计数不变。
2. VMP 将原版 LoadingChunkTracker 置空，改用 Paper 距离传播器。适配器屏蔽传播结果而保留原始票据与距离场。第一次密集测试暴露了分批撤销屏蔽后过早推进 holder 导致的相邻依赖缺失；改为全部目标等级入队后统一推进，之后五轮密集测试通过。
3. VMP 的 server executor 在主线程可能内联执行 CompletableFuture 回调。适配器约束 VMP 加载入口，并等待它的票据清理 continuation；不能只看 MinecraftServer 待执行队列长度。异步 spreadplayers 还有独立工作线程，当前开关开启时拒绝 EDB 操作。
4. Extra Extras 的 TNT 标记未包含在原版 NBT；ECO 的碰撞缓存版本也不会因直接替换存档而自动递增。分别增加运行字段和缓存失效挂钩。
5. 准备阶段会执行维护前已接受的玩家传送。仅在操作开始检查玩家位置不够；在真正屏蔽、卸载前再次检查。测试故意把模拟玩家在 SETTLE 阶段移入选区，确认拒绝且区块实例未被替换。
6. TPC Wild 搜索能跨 tick 保留候选批次。通过其共享 operation manager 取消当前 Wild operation，再调用仅释放 Wild 搜索/票据的清理方法；没有调用会改变离线状态的 TeleportService.onPlayerQuit。未接受的 TPA 请求、玩家记录和普通延时传送不被这个挂钩清空。
7. 原版把只有一名玩家乘坐的载具存入玩家 `RootVehicle`，多名玩家乘坐则不同。区域快照统一存入实体记录，并剥离假人 NBT 的 `RootVehicle`；重建后按 UUID 挂载、按原座位顺序排序，避免丢失、重复或驾驶席互换。
8. TIS 和 GCA 的延迟任务会引用旧假人对象。恢复按 UUID 清理，包括已经下线的快照假人，再为新对象重建；Carpet/GCA 已受理的异步 profile 查询也纳入准备阶段等待。

## 压力数据与限制

完整原始指标：[`纯 Fabric`](../tests/results/26.3-vanilla-gametest.csv)、[`十模组组合`](../tests/results/26.3-common-mods-gametest.csv)、[`含 C2ME 的十一模组组合`](../tests/results/26.3-c2me-common-mods-gametest.csv)。三组均通过完整压力场景；C2ME 组合开启 gcFree serializer。

- 256 个已加载区块，每块 4 个实体，共 1,024 个实体，连续备份/修改/恢复五轮。检查新实例、捕获年龄、生命值、方块、箱子物品、POI、光照和实体空间查询。
- 100×100 个**实际有地形存档记录**的未加载区块，每条写不同标记；备份、全部覆盖、恢复，再逐块读盘校验。确认没有为这些位置新建已加载 holder。
- 另有 100×100 选区，混合已有与不存在记录，验证恢复能删除备份时不存在的数据。
- 十模组组合：密集往返的单次备份或恢复约 0.38–0.60 秒；10,000 条地形记录备份约 14.1 秒、恢复约 6.1 秒，压力阶段最长维护帧 41 ms。
- C2ME 十一模组组合：密集单次操作约 0.37–0.62 秒；10,000 条地形记录备份约 19.4 秒、恢复约 5.2 秒，压力阶段最长维护帧 17 ms。部分测试与独立专用服同时运行，这些数值仅作回归观测。

这些是 **GameTest 加速循环**的观测，不是普通 20 TPS 服务器的停顿承诺。GameTest 维护循环不按 50 ms 等待，因此 CSV 中维护帧数很大；测试主体给普通模拟 tick 增加了 1 ms 调度让步，避免磁盘等待耗尽测试 tick 上限。单帧 8 ms 仍只是软预算，不能抢占一个原版调用。

本测试的地形由模板复制并设置各块标记，不能代表十万个复杂方块实体、不同世界生成阶段或真实玩家创造的最坏存档。没有验证 10,000 个**全部已加载且高密度**区块。带连接的模拟玩家使用 EmbeddedChannel，不等于真实网络客户端、容器交互和断线重连测试。

Fabric 输出的“2 required tests”含其自带测试；EDB 通过一项顺序集成测试运行上述多个场景，不把它解释成两套独立压力测试。

## C2ME：已实现适配并完成当前组合测试

检查版本：0.4.2-alpha.0.106+26.3，C2ME 源码提交 `05d95af00560c0d591db3afe9a6042c8393833d6`，FlowSched 子模块 `a60f952`。C2ME-only 基础往返及 10,000 个未加载区块场景通过；11 模组组合默认 gcFree 关闭时的基础往返、fake-player 场景通过。另在 gcFree 打开、TIS fake-player ticks-like-real-player=true、GCA 延迟任务启用的 11 模组组合中，基础场景和隔离目录注入的 C2ME 后台真实写入错误探针通过。日志位于本机 `.gradle/research/compat/`，未纳入仓库。

当前实现的维护边界包括：

- 通过 C2ME scheduler 的工作闸门确认待处理 dirty/状态推进已静止，并在 NoTickVD 票据队列排空后建立 barrier；只读取原版 holder 状态不足以做这个判断。
- 维护期间对选区 root ticket 做临时停放，保留依赖关系和 callback 语义，让 C2ME 自己执行降级；备份/恢复边界不直接清空其内部 holder、依赖或票据 map。
- 捕获 C2ME storage worker 对写入、删除与 flush 失败的记录，并纳入维护失败检查。单靠其普通写 future 完成不能证明磁盘写入成功；组合测试用隔离存储路径实际触发后台 IO 异常验证了错误探针。
- 针对 `ioSystem.gcFreeChunkSerializer` 的自定义 NBT 输出增加扩展字段桥接；在其启用的组合测试中验证 runtime NBT 往返。

这些测试没有证明所有 C2ME 模块配置、外部 ticket 来源、任意并发负载或任意存储故障均已覆盖。上述来源为 [C2ME](https://github.com/RelativityMC/C2ME-fabric)、FlowSched 子模块、`TheChunkSystem.java`、`ItemHolder.java`、`StatusAdvancingScheduler.java`、`C2MEStorageThread.java` 和 `c2me-rewrites-chunk-serializer`。研究源码保留在本机 `.gradle/research/compat/`，不提交第三方源码或 jar。

## 复现

```powershell
$env:TEMP='D:\Temp'; $env:TMP='D:\Temp'
.\scripts\prepare-compat.ps1
$profile='carpet,carpet-tis-addition,carpet-ams-addition,edtp,gca,extra-extras,lithium,vmp-fabric,eco,teleportcommandsfabric'
.\gradlew.bat build runGameTest "-PedbCompat=$profile" -PedbStress=true --console=plain
.\gradlew.bat runGameTest -PedbStress=true --console=plain
# C2ME 单独兼容场景：
.\gradlew.bat runGameTest -PedbCompat=c2me-fabric --console=plain
# 十一模组完整压力场景：
.\gradlew.bat runGameTest "-PedbCompat=$profile,c2me-fabric" -PedbStress=true --console=plain
```

没有 `edbCompat` 时运行纯 Fabric 基线。适配模组仅加到 gametest runtime classpath，不会打入 EDB jar。测试有墙钟截止时间；失败维护状态即使停止了游戏 tick，也会生成失败结果并退出。

gcFree 测试先在相应运行目录的 `config/c2me.toml` 中将 `[ioSystem]` 下的 `gcFreeChunkSerializer` 设为 `true`；GameTest 目录为 `build/run/gameTest`，专用服为 `build/run/edbDedicatedTest`。日志必须出现 `EDB verified C2ME gcFree serializer runtime NBT round trip`，不能仅凭模组在加载列表中判断该分支执行过。

## 专用服 watchdog 已通过

独立 `runEdbDedicatedTest` 在上述十一模组组合、gcFree serializer 开启、TIS 假人规则开启的情况下完成整套测试，结果文件为 `PASS`，Gradle 构建成功。服务器仅监听 127.0.0.1、随机端口，`max-tick-time=10000`、无玩家时不停服；没有关闭或提高 watchdog 阈值。每次测试使用新的独立世界，保留旧世界供检查，避免上次测试留下的实体污染计数。

原始压力数据见 [`26.3-c2me-common-mods-dedicated.csv`](../tests/results/26.3-c2me-common-mods-dedicated.csv)。256 区块/1,024 实体五轮操作中，单次备份约 1.03–1.14 秒、恢复约 3.29–4.04 秒。10,000 个有地形记录的未加载区块备份约 31.20 秒、恢复约 98.45 秒；均完成逐块验证。压力阶段最长维护帧 15 ms，包含假人等前置场景的整轮日志最长维护帧 38 ms，watchdog 未触发。

专用服按正常主循环节奏推进分帧状态机，因此总暂停时间明显长于加速 GameTest；这些操作期间整服模拟暂停。结果证明本次负载能持续返回主循环，不代表任意世界的帧耗时上界。

```powershell
.\scripts\prepare-dedicated-test.ps1
# 阅读并接受 Minecraft EULA 后，手动把 build/run/edbDedicatedTest/eula.txt 改为 eula=true
.\gradlew.bat runEdbDedicatedTest "-PedbCompat=$profile,c2me-fabric" --console=plain
```

测试目录的 EULA 已由用户接受（`eula=true`）。Windows 发布目录的有界重试、GCA 已下线假人旧任务清理均包含在通过版本中。真实多人协议、极端光照/生成边界和进程崩溃仍在未验证范围内；后台存储错误探针不等同于整盘故障测试。
