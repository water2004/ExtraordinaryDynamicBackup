# Minecraft 26.3 兼容性与压力测试

记录日期：2026-10-27。Windows x64、Microsoft OpenJDK 25.0.2、Fabric Loader 0.19.5、Fabric API 0.162.0+26.3。测试使用独立 Gradle 运行目录，没有更改 Orbit 用户实例。

## 已执行的范围

以下十个模组在**同一进程**加载并完成集成测试和压力场景。固定下载地址及 SHA512/SHA256 存在 [`tests/compatibility-lock.json`](../tests/compatibility-lock.json)。

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

Carpet Extra（gnembon 项目）与 Carpet Extra Extras 是不同项目。本次没有拿 26.2 的 Carpet Extra 包冒充 26.3 兼容版本。

“同时加载通过”只证明表中版本和实际场景。不同 additions 的每个规则、任意脚本及任意第三方异步写世界代码均未获得无损保证。

## 实际发现并修复的问题

1. 原先在 tickServer 内部取切面会受其他 Mixin 回调位置影响。现在从 `processPacketsAndTick` 包住整个调用，连同 DedicatedServer/GameTestServer 覆盖方法及 Fabric/Carpet tick 回调一起暂停。测试断言维护期间 START/END_SERVER_TICK 计数不变。
2. VMP 将原版 LoadingChunkTracker 置空，改用 Paper 距离传播器。适配器屏蔽传播结果而保留原始票据与距离场。第一次密集测试暴露了分批撤销屏蔽后过早推进 holder 导致的相邻依赖缺失；改为全部目标等级入队后统一推进，之后五轮密集测试通过。
3. VMP 的 server executor 在主线程可能内联执行 CompletableFuture 回调。适配器约束 VMP 加载入口，并等待它的票据清理 continuation；不能只看 MinecraftServer 待执行队列长度。异步 spreadplayers 还有独立工作线程，当前开关开启时拒绝 EDB 操作。
4. Extra Extras 的 TNT 标记未包含在原版 NBT；ECO 的碰撞缓存版本也不会因直接替换存档而自动递增。分别增加运行字段和缓存失效挂钩。
5. 准备阶段会执行维护前已接受的玩家传送。仅在操作开始检查玩家位置不够；在真正屏蔽、卸载前再次检查。测试故意把模拟玩家在 SETTLE 阶段移入选区，确认拒绝且区块实例未被替换。
6. TPC Wild 搜索能跨 tick 保留候选批次。通过其共享 operation manager 取消当前 Wild operation，再调用仅释放 Wild 搜索/票据的清理方法；没有调用会改变离线状态的 TeleportService.onPlayerQuit。未接受的 TPA 请求、玩家记录和普通延时传送不被这个挂钩清空。

## 压力数据与限制

完整原始指标：[`26.3-common-mods-gametest.csv`](../tests/results/26.3-common-mods-gametest.csv)、[`26.3-vanilla-gametest.csv`](../tests/results/26.3-vanilla-gametest.csv)。

- 256 个已加载区块，每块 4 个实体，共 1,024 个实体，连续备份/修改/恢复五轮。检查新实例、捕获年龄、生命值、方块、箱子物品、POI、光照和实体空间查询。
- 100×100 个**实际有地形存档记录**的未加载区块，每条写不同标记；备份、全部覆盖、恢复，再逐块读盘校验。确认没有为这些位置新建已加载 holder。
- 另有 100×100 选区，混合已有与不存在记录，验证恢复能删除备份时不存在的数据。
- 常用模组组合的密集往返单次记录耗时约 0.4–0.54 秒；10,000 个有数据记录的备份约 14.7 秒、恢复约 7.2 秒。该轮压力阶段总计约 34 秒，记录到最长维护帧 18 ms。

这些是 **GameTest 加速循环**的观测，不是普通 20 TPS 服务器的停顿承诺。GameTest 维护循环不按 50 ms 等待，因此 CSV 中维护帧数很大；测试主体给普通模拟 tick 增加了 1 ms 调度让步，避免磁盘等待耗尽测试 tick 上限。单帧 8 ms 仍只是软预算，不能抢占一个原版调用。

本测试的地形由模板复制并设置各块标记，不能代表十万个复杂方块实体、不同世界生成阶段或真实玩家创造的最坏存档。没有验证 10,000 个**全部已加载且高密度**区块。带连接的模拟玩家使用 EmbeddedChannel，不等于真实网络客户端、容器交互和断线重连测试。

Fabric 输出的“2 required tests”含其自带测试；EDB 通过一项顺序集成测试运行上述多个场景，不把它解释成两套独立压力测试。

## C2ME：尚未完成适配

检查版本：0.4.2-alpha.0.106+26.3，C2ME 源码提交 `05d95af00560c0d591db3afe9a6042c8393833d6`，FlowSched 子模块 `a60f952`。已实际验证与 EDB 共存启动，并且 backup/restore 均在排入维护之前明确拒绝。**这不是 C2ME 热备份/热恢复通过测试。**

必要缺口经源码复核：

- `TheChunkSystem` / FlowSched 自己管理 holder、异步升级/降级和依赖票据。原版 holder 表、LoadingChunkTracker 和原版 unload pump 不能证明它已经静止；NoTickVD 等还会直接添加 C2ME 票据。
- FlowSched `ItemHolder.markDirty0` 排队的 dirty 工作不计入 busyRefCounter；仅观察 holder.isBusy 或一次 getOpFuture 会漏掉尚未开始的工作。冻结推进器后等待已有任务也可能阻塞其尚需推进的依赖，不能粗暴给整个调度器加锁。
- C2ME 普通写 future 只确认提交到内部写队列。`flush(true)` 确实等待已收纳写入并 sync，但写入、删除、sync 的部分异常被内部捕获后只记录日志。需要写入准入边界及独立错误检测，不能把 future 正常完成当作持久化成功。
- 可选 `ioSystem.gcFreeChunkSerializer` 直接输出固定字段的原始 NBT 字节，绕过原版 `SerializableChunkData.write`，会遗漏 EDB 的延迟安装扩展；默认关闭也不能当作全面适配证明。
- 恢复还需要让目标 holder 按 C2ME 的依赖关系正常降级、释放和重新建立，并防止恢复期间新增的外部票据/写入穿过边界。不能直接清空它的内部 map、重用已消费的 ticket callback，或偷偷停用 C2ME 后声称适配完成。

以上缺口尚未实现完整协议，因此当前采取操作前拒绝。原始依据为 [C2ME](https://github.com/RelativityMC/C2ME-fabric)、其 FlowSched 子模块、`TheChunkSystem.java`、`ItemHolder.java`、`StatusAdvancingScheduler.java`、`C2MEStorageThread.java` 和 `c2me-rewrites-chunk-serializer`。研究源码保留在本机 `.gradle/research/compat/`，不提交第三方源码或 jar。

## 复现

```powershell
$env:TEMP='D:\Temp'; $env:TMP='D:\Temp'
.\scripts\prepare-compat.ps1
$profile='carpet,carpet-tis-addition,carpet-ams-addition,edtp,gca,extra-extras,lithium,vmp-fabric,eco,teleportcommandsfabric'
.\gradlew.bat build runGameTest "-PedbCompat=$profile" -PedbStress=true --console=plain
.\gradlew.bat runGameTest -PedbStress=true --console=plain
# 只验证安全拒绝，不执行 C2ME 下的备份/恢复：
.\gradlew.bat runGameTest -PedbCompat=c2me-fabric --console=plain
```

没有 `edbCompat` 时运行纯 Fabric 基线。适配模组仅加到 gametest runtime classpath，不会打入 EDB jar。测试有墙钟截止时间；失败维护状态即使停止了游戏 tick，也会生成失败结果并退出。

## 专用服 watchdog 尚待执行

已准备独立 `runEdbDedicatedTest` 运行配置与 `scripts/prepare-dedicated-test.ps1`：仅监听 127.0.0.1、随机端口、独立世界目录、watchdog 阈值 10 秒、无玩家时不停服。运行同一套压力场景，完成后写 PASS/FAIL 并关服；Gradle 检查结果。

```powershell
.\scripts\prepare-dedicated-test.ps1
# 阅读并接受 Minecraft EULA 后，手动把 build/run/edbDedicatedTest/eula.txt 改为 eula=true
.\gradlew.bat runEdbDedicatedTest "-PedbCompat=$profile" --console=plain
```

本次尚未获得测试目录的 EULA 接受确认，因此没有运行这个专用服配置；也没有把 GameTest 的结果写成 watchdog 已通过。真实多人协议、极端光照/生成边界、磁盘故障和进程崩溃仍在未验证范围内。
