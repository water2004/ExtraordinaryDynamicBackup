# ExtraordinaryDynamicBackup

Minecraft **26.3** 的 Fabric 区域热备份、热恢复原型。Java 25，包名与 Maven group 为 `org.edtp`。

选区按区块对齐、包含整个高度，可以混合已加载、未加载和尚未生成的位置。已加入 100×100 区块的自动化往返测试。

## 使用

需要最高命令权限。坐标均为**区块坐标**，两端包含在内。

```text
/edb backup test 0 0 99 99
/edb restore test
/edb list
/edb status
```

备份使用命令所在维度；恢复使用备份记录的原维度、原坐标。可用 `/execute in minecraft:the_nether run edb backup ...` 指定维度。备份名限 1–64 位英文字母、数字、下划线或连字符；已有名称不会覆盖。

操作从 tick 末开始，**暂停整服模拟**，分帧准备和读写，期间维持连接。它并非让玩家照常游玩的无停顿备份。进度每五秒输出，服务端控制台可查询 `edb status`。恢复前需让玩家离开选区，打开的容器会关闭。

未加载位置直接读取、替换存档中的记录，不为备份生成区块。地形、实体、POI 的存在与缺失分别记录；恢复为缺失会清除后来产生的数据。每批最多 32 个区块 NBT，主线程采用约 8 ms 的软时间预算；排序、事件及 UUID 元数据另行保存。

备份保存在 `<世界目录>/edb/backups/<名称>/`。先写 `.partial` 目录，全部完成后发布。`manifest.nbt` 是元数据，`runtime.nbt` 是方块事件与顺序信息，`chunks/x_z.nbt` 包含对应位置的三类存档数据。当前每次最多 65,536 区块，单个解压 NBT 上限 64 MiB。

## 当前边界

- 只支持相同存档的 Minecraft 26.3；真实玩家及其背包不属于区域备份，世界时间、天气、计分板等全局状态也不会回退。Carpet fake player 是例外，由适配器单独保存其玩家数据和已覆盖的运行状态。
- 保存原版区块数据与可序列化实体，并补充计划刻顺序、方块事件、实体年龄与部分移动状态、活塞进度、振动候选时间等已核查字段；普通 LivingEntity 的全部瞬态状态和任意模组脚本不保证完整恢复，也不承诺未来逐 tick 完全重演。
- 未加载数据只能保留磁盘已有内容，不能还原此前原版卸载时已经丢弃的运行字段；补充实体字段安装后的普通保存/再次卸载仍遵循原版语义。本原型没有将整个原版保存系统改成 SafeSave。
- 操作前要求真实玩家离开选区；真实玩家不随区域备份，也不允许把含真实玩家的乘坐树当作普通区域实体恢复。Carpet fake player 例外：保存其玩家 NBT、ActionPack 与已适配任务，车辆树放在区域实体数据中，并用车辆 UUID、座位序号恢复挂载和乘客顺序。普通实体树根在选区外但有成员在选区内时会拒绝；根在选区内时树随区域处理。检查已知实体 UUID 冲突，不扫描全世界离线实体。
- 恢复先预检完整备份，再卸载和替换。跨区块、跨存储文件的替换不是崩溃原子事务。替换阶段失败会保持维护暂停并报告错误，不自动回滚。
- 保留原版 watchdog。分帧返回主循环、异步磁盘 IO 避免把整次操作压成一个长 tick；8 ms 是软预算，无法抢占一次区块序列化或原版维护调用。
- 已验证范围见下文及[兼容性与压力测试记录](docs/compatibility-26.3.md)。已通过启用 10 秒 watchdog 的专用服组合压力测试；真实多人客户端刷新和 10,000 个已加载高密度区块仍需专项验证，当前是实验版本。

## 模组适配

适配代码集中在 `src/main/java/org/edtp/edb/adaptors/`，使用独立 Mixin 配置；不捆绑被适配模组。

已在同一实例验证 Carpet、TIS Addition、AMS Addition、Gugle Carpet Addition、EDTP Addition、Carpet Extra Extras、Lithium、VMP、EntityCollisionOptimizer、Teleport Commands Fabric。版本和具体测试范围见[兼容性记录](docs/compatibility-26.3.md)。这不代表 additions 的每个规则、脚本和组合都已覆盖。

C2ME 适配已通过特定 26.3 版本的 GameTest 场景，包括 C2ME 单独的未加载区块压力场景和 11 模组组合；维护期间通过 scheduler/NoTickVD 屏障静置，暂存区域 root tickets 后让 C2ME 自己降级，并检查其异步存储错误。细节和限制见[兼容性记录](docs/compatibility-26.3.md)。这不是对任意 C2ME 配置或所有第三方任务的无损保证。Carpet fake player 的 ActionPack NBT、动作进度、车辆 UUID 和座位顺序有专门往返覆盖；普通 LivingEntity 的全部瞬态状态及任意模组脚本仍不保证完整重演。VMP 的可选异步命令开关 `use_async_chunks_on_some_commands` 目前也必须关闭（默认关闭）。恢复会取消目标维度玩家的待处理 TPC Wild 搜索并释放其区块票据，避免沿用旧世界的候选位置；普通备份不取消这些请求。

## 在 IntelliJ IDEA 中打开

1. 选择 Open，打开本项目目录，按 Gradle 项目导入。
2. Project SDK 和 Gradle JVM 选择 Microsoft JDK 25：`C:\Users\think book\.jdks\ms-25.0.2`。
3. 等待 Gradle 同步完成，使用生成的 Minecraft Client / Minecraft Server 运行配置。

`gradle.properties` 已配置本机 JDK 路径；移动到其他电脑时需要修改 `org.gradle.java.home`。

## 命令

```powershell
.\gradlew.bat build
.\gradlew.bat runGameTest
.\gradlew.bat runClient
.\gradlew.bat runServer
```

构建产物：`build/libs/ExtraordinaryDynamicBackup-0.1.0.jar`。
入口类位于 `src/main/java/org/edtp` 和 `src/client/java/org/edtp`。
在此机器执行 Gradle 时，如果系统临时目录不可用，可先设置 `$env:TEMP='D:\Temp'; $env:TMP='D:\Temp'`。

## 自动验证

`runGameTest` 启动真实的 26.3 Fabric GameTest 服务器，审计全部 Mixin，并顺序验证：

- 已加载区块恢复内容且替换为新实例；实体也重新创建，保留年龄与计划刻剩余延迟。
- 准备阶段等待已排队任务和尚未完成的异步牌子更新回调。
- 100×100 未加载选区备份与恢复，不新增该区域的 holder；还原已有记录并删除原本缺失的记录。
- 维护期间世界时间不前进，操作跨多个主循环迭代推进。
- 延迟加载时安装运行态计划刻、推进顺序计数器，并消费安装标记。
- 活塞当前/前次进度与时间偏移、振动候选时间重基。
- 损坏的区块坐标在卸载前被拒绝。

GameTest 使用加速循环，其总耗时和循环次数不是普通 20 TPS 服务器的性能数据。另已通过十一模组组合的专用服测试，watchdog 阈值保持 10 秒：10,000 个未加载区块备份约 31 秒、恢复约 98 秒，期间整服模拟暂停；整轮最长维护帧 38 ms。

`-PedbStress=true` 额外运行 256 个已加载区块、1,024 个实体的五轮往返，以及 10,000 个实际有数据的未加载区块逐块验证。组合模组下载、固定哈希、运行命令和专用服测试准备步骤见[兼容性记录](docs/compatibility-26.3.md)。

## 设计方案

[实现说明与源码依据](docs/regional-backup-design.md)说明文件职责、屏障、加载/卸载、错误处理和仍待验证的部分。
