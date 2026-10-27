# ExtraordinaryDynamicBackup 26.3 实现说明

本项目保存指定区域的存档内容和已核查的补充运行状态，在同一存档、同一维度、原坐标热恢复。恢复后接入当前世界时间，不倒回全局时间，也不追求未来完全重演。

当前是已通过原版与部分模组组合集成测试的实验实现；验证范围和未验证范围在本文末尾及[兼容性记录](compatibility-26.3.md)列出。技术栈固定为 Minecraft 26.3、Fabric Loader 0.19.5、Fabric API 0.162.0+26.3、Microsoft JDK 25。

## 文件职责

| 文件/目录 | 实际职责 |
| --- | --- |
| `ExtraordinaryDynamicBackup.java` | 注册命令、释放服务器生命周期资源 |
| `edb/command/` | 命令树、异步目录查询、恢复预览与限时确认 |
| `edb/config/BackupConfig.java` | OP 等级配置、严格校验和动态权限判断 |
| `edb/region/RegionSelection.java` | 同维度、完整高度、闭区间区块选区；不保存全区块对象列表 |
| `edb/region/RegionRepository.java` | 按世界持久化命名区域定义，原子替换 JSON 文件 |
| `edb/operation/RegionBackupService.java` | 主线程状态机、存储 Future、分批捕获/预检/替换、错误与进度处理 |
| `edb/storage/SnapshotIO.java` | 文件布局、压缩 NBT、存在标记、世界标识、完成发布 |
| `edb/runtime/RuntimeStateCodec.java` | 计划刻、方块事件、实体/方块实体顺序、时间重基 |
| `edb/bridge/RegionAccess.java` | 仅供当前 26.3 实现使用的窄访问桥接接口 |
| `edb/ui/OperationMessages.java` | 服务端文本、阶段进度、整服暂停与结束通知 |
| `edb/mixin/ServerMixin.java` 等 | tick 边界、通用任务队列、连接/命令/异步牌子更新门控 |
| `edb/mixin/ChunkTrackerMixin.java` 等 | 临时屏蔽加载距离图，使原版 DistanceManager 执行降级、卸载、恢复后的重新计算 |
| `edb/mixin/EntityManagerMixin.java` 等 | 区分内存实体、待载入实体与磁盘记录，推进原版实体保存/卸载 |
| `edb/mixin/SectionStorageMixin.java` 等 | POI 内存序列化、等待预取、恢复前缓存失效 |
| `edb/mixin/EntityMixin.java` 等 | 原版 NBT 之外的字段只在捕获时采集，在载入时安装 |
| `edb/adaptors/` | 可选模组预检、异步活动收尾、运行字段与缓存适配；独立 Mixin 配置 |
| `src/gametest/` | 真实 Minecraft/Fabric 环境中的顺序集成测试，不打入正式 mod JAR |
| `tests/compatibility-lock.json`、`scripts/` | 固定测试模组和哈希，准备独立的测试环境 |

没有为多版本预设适配层，没有持续记录事件历史，没有自动 undo、跨区域资源追踪、全存档 UUID 索引或崩溃事务日志。

## 选区与文件布局

区块坐标包含两端；例如 `0 0 99 99` 是 10,000 个区块。当前上限 65,536 区块。区域按区块完整高度保存，未实现任意方块立方体裁剪。

```text
<世界>/edb/
  world-id.txt
  regions.json             # 可复用区域定义，不是快照数据
  backups/
    <name>/
      manifest.nbt
      runtime.nbt
      chunks/
        <chunkX>_<chunkZ>.nbt
    .<name>-<uuid>.partial/
```

每个区块记录分别携带 `terrain_present`、`entities_present`、`poi_present` 与存在时的 NBT。缺失是快照的一部分：恢复时将对应当前记录清除，不能用“跳过不存在文件”代替。

`manifest.nbt` 记录格式、Minecraft/DataVersion、世界标识、维度、选区与捕获时间。`runtime.nbt` 保存选区方块事件及计划刻顺序范围。三路 NBT 放在同一个区块记录中，避免只备份 terrain 的不完整结果。

单个读取 NBT 的计量上限为 64 MiB；写出时也检查 NBT 大小。元数据和全部区块写完后才重命名发布目录；`.partial` 不属于可恢复备份。恢复中跨区块、跨 terrain/entities/poi 的替换不具备断电原子性。

## barrier 的实际含义

不是“tick 返回便自动安全”。命令先排队；正常 tick 返回后，服务进入维护模式，暂停整服模拟与常规自动保存。外层 `runServer` 循环继续运行。

准备阶段仍需处理原版已接受的持久状态变化：

1. 拦截新的游戏交互和世界修改命令，暂停配置登录推进，延后玩家移除，维持已有连接的 keepalive。
2. 暂时允许服务器通用任务队列执行，完成已排队的 POI 更新等。等待已登记的异步牌子文字过滤及其主线程应用回调。
3. 推进区块专用主线程队列、实体反序列化/加载、POI 预取和已有卸载。
4. 确认目标维度现有 holder 的生成工作和 save dependency 已完成，检查选区实体加载状态与 POI pending read，等待光照 POST_UPDATE fence。
5. 通用队列又有工作时回到准备阶段；就绪后停止泵送通用与区块任务队列，开始逐批捕获或预检。

捕获阶段跨帧，但游戏时间不推进，相关主线程写入入口保持关闭，已准备完成的数据源不再继续普通加载或卸载。这是分批读取仍属于同一个稳定切面的前提。磁盘 worker 可以完成已有写入；原版 IOWorker 的 read 会先查看相同位置的 pending write，再读取磁盘。

牌子异步更新是正常运行期间唯一新增的特定在途操作计数：只在发起/完成该请求时增减，登记与进入维护互斥。它不存文字历史或逐 tick 事件。活塞、实体顺序等从捕获时的容器直接读取。

本实现覆盖所核查的原版通路与本 mod 自身操作，不是第三方 mod 任意后台线程写世界的通用隔离器。

## watchdog 与连接

26.3 `ServerWatchdog.run` 比较 `Util.getNanos()` 与 `server.getNextTickTime()`，后者来自主循环的 `nextTickTimeNanos`。它不直接检查世界 gameTime 是否增长。

因此维护过程保留外层循环，逐帧返回；不关闭 watchdog，也不伪造它的时间戳。文件压缩/解压和文件系统访问交给专用 IO executor，主线程只检查 Future 是否完成，完成后才 join 取结果。

区块 NBT 每批最多 32 条，捕获/预检使用约 8 ms 软预算；准备/卸载也分步推进。预算不能抢占一次 NBT 序列化、一次原版加载维护调用或极端实体树遍历，所以不宣称在任何世界数据下都能严格限制每帧时长。日志记录实际维护迭代数、总耗时和最慢维护帧；五分钟没有进度会报错。

连接维护不调用玩家模拟。配置阶段停止推进；已断开的玩家延后移除，避免卸载其载具/珍珠等改变捕获中的世界。完成后发送位置、容器和受影响方块预测的纠正；区域恢复通过原版 chunk sender 的丢弃/重载路径重发区块。专用服控制台在连接维护中仍可查询状态。通用队列关闭阶段的 RCON/聊天命令可能等待维护结束。

## 备份数据来源

| 状态 | 数据来源 |
| --- | --- |
| 已有稳定 LevelChunk | 主线程 `SerializableChunkData.copyOf(...).write()`，再补运行字段并脱离原对象 |
| 已有可序列化 ProtoChunk | 保存当前阶段的序列化内容，不提升到 FULL |
| 无内存 chunk | `ChunkMap.read`，不调用会加载/生成区块的 getChunk |
| 实体状态 LOADED | 从实体分区收集原版可保存的根实体与完整乘客树 |
| 实体 FRESH 且已有内存实体 | 先完成原版磁盘实体合并，再捕获，不能用内存少量实体覆盖磁盘其余实体 |
| 实体 PENDING | 等待原版加载完成 |
| 无已加载实体数据 | 直接读实体存储 NBT |
| POI 已缓存 | 序列化内存状态，包括尚未落盘的修改 |
| POI 正在预取 | 完成预取后再取内存状态 |
| POI 无缓存/预取 | 直接读 POI 存储 |

不保存整个选区的 NBT 集合。区块 NBT 内存随批次限制；UUID、顺序、方块事件等必要元数据仍随对象数量增长，不能把总内存描述成绝对常数。

## 热恢复流程

1. 读取元数据，检查世界标识、维度、版本及选区。真实玩家须位于选区外；关闭容器，防止界面继续引用将卸载的方块实体。Carpet fake player 由专用适配器作为区域状态处理。
2. 建立上述稳定切面。逐批读取所有备份记录，检查坐标、存在标记、可解析的 terrain/POI、运行态计划刻、实体类型与 UUID。检测当前各维度已知实体中的外部 UUID 冲突。
3. 按当前区块后端停放选区 root tickets 并触发降级。原版路径屏蔽 LoadingChunkTracker 距离图中的目标位置，保留 ticket 内容，由 DistanceManager 两阶段更新 holder；C2ME 路径经自己的 scheduler/ticket adapter 停放目标 root tickets，由 C2ME 正常更新依赖和 holder。两条路径都不直接用旧 ticket level 造新 holder。
4. 持续推进原版 chunk/entity 卸载，确认目标从 updating/pending unload 离开、实体状态清除。等待三路存储同步和原目标/现有 holder 的光照任务；观察独立写入 Future，避免只靠一次 synchronize 忽略先前失败。
5. 清理 POI 的解码缓存/加载标记/dirty 状态、实体 empty-chunk cache、chunk 类型与最近访问缓存、寻路与结构查询缓存，清除原区域方块事件。
6. 分批写回 terrain/entities/poi，缺失记录写成删除。计划刻顺序整体平移到当前世界计数器之后，保持选区内原有相对顺序。
7. 等待写入和 flush 完成，撤销对应后端的屏蔽/票据停放并重新计算。原 ticket 决定哪些区块重新进入内存；没有 ticket 的位置保留在磁盘。
8. 通过原版路径建立新 chunk/entity 对象，安装已加载内容的运行态。尚未加载的运行态附在 NBT 中，等对应对象日后首次进入适当生命周期再安装。
9. 刷新客户端，恢复模拟。既有服务器任务随后继续执行；恢复后的结构查询缓存再失效一次，防止旧排队派生索引回调留下缓存。

普通实体乘坐树按根实体及其所在区块处理；根在选区外但有成员在选区内时拒绝。真实玩家不纳入区域实体快照，操作前要求其离开选区，包含真实玩家的树不作为可恢复实体树处理。Carpet fake player 是明确例外：根实体须在选区中，fake player 的玩家数据和挂载关系由 Carpet 适配器单独保存。车辆树仍由区域实体 NBT 保存；fake player 自身的玩家数据会剥离 `RootVehicle`，防止 vanilla 玩家加载路径重复恢复同一车辆。适配器记录 vehicle UUID 和 passenger seat index，在 fake player 重建后恢复挂载并重排座位，因此能保留与普通乘客交错的座位顺序。不会扫描世界所有未加载实体以证明全局 UUID 唯一性。

## 补充的运行态

- 计划刻：类型、位置、剩余延迟、优先级、subTickOrder；兼顾 FULL 但尚未 unpack 的 pending saved ticks。
- 方块事件：位置、方块类型、两个参数及队列内顺序。
- 实体：tickCount、firstTick、移动/接触相关字段、活塞推移累积和时间、姿态、支撑位置；再补 TNT portal 标记、坠落方块标记/起点、矿车 onRails。
- 活塞：progress、progressO、lastTicked 相对时间及捕获时 ticker 顺序。
- 振动：原版 listener NBT 保留事件与剩余 travel delay，仅重基 selector 的绝对候选时间。
- 实体与方块实体：安装捕获的选区内部相对 tick 顺序。不会强行恢复区域外对象在过去队列中的位置。
- Carpet fake player（仅在 Carpet 存在时）：单独保存玩家 NBT、Carpet ActionPack、当前物品使用进度、实体运行态、TIS fake-player 延迟任务和 GCA auto-fish 延迟任务；保存车辆 UUID 和座位序号以重建挂载与原座位顺序。真实玩家与其背包不属于区域备份。

延迟安装标记使用 `extraordinarydynamicbackup` NBT 段。恢复的 chunk 还未开始 ticking 时，标记随再次保存保留；实际 unpack 后消费。计划刻计数器前移，避免重启或延迟加载后复用旧顺序号。

这份字段清单不是全部 Java 对象状态的序列化证明；原版未保存的 AI 内部状态、任意第三方模组字段、随机数轨迹等不在已验证的无损范围内。

## 失败行为

备份失败不会发布完成目录。恢复预检失败不会进入卸载/替换。开始屏蔽卸载或写回后发生故障，保持维护暂停并报告阶段和异常；不会在部分恢复的世界上自动继续模拟，也没有自动回滚。

用户在正常维护期间输入控制台 stop，会排到操作完成后执行；已经进入失败暂停时允许 stop。服务器关闭不能把跨文件替换变成原子事务，故实验版应先在独立测试存档验证。

## 主要原版依据

以下路径相对于本机生成的 `.gradle/research/minecraft-26.3/net/minecraft/`，均为 26.3 源码；生成源码不提交到仓库。

- `server/MinecraftServer.java`：runServer、tickServer、processPacketsAndTick、pollTaskInternal、waitUntilNextTick。
- `server/dedicated/ServerWatchdog.java`：墙钟与 getNextTickTime 的比较、超时终止。
- `server/level/ServerChunkCache.java`：独立 MainThreadExecutor、pollTask、runDistanceManagerUpdates。
- `server/level/ChunkMap.java`：继承 SimpleRegionStorage、scheduleChunkLoad、processUnloads、scheduleUnload、prepareTickingChunk、客户端发送。
- `server/level/LoadingChunkTracker.java`、`ChunkTracker.java`、`DistanceManager.java`：距离图、原票据与 holder 的正常两阶段状态变更。
- `world/level/entity/PersistentEntitySectionManager.java`：FRESH/PENDING/LOADED、storeChunkSections、processChunkUnload。
- `world/level/chunk/storage/EntityStorage.java`、`SectionStorage.java`、`SimpleRegionStorage.java`、`IOWorker.java`：三路存储、pending 读取/写入与 synchronize 的范围。
- `server/level/ServerLevel.java`：updatePOIOnBlockStateChange、onStructureStartsAvailable、startTickingChunk。
- `server/network/ServerGamePacketListenerImpl.java`：shouldHandleMessage、tick、handleSignUpdate 的异步回调。
- `world/level/chunk/storage/SerializableChunkData.java`、`world/ticks/LevelChunkTicks.java`、`world/entity/Entity.java`、`world/level/block/piston/PistonMovingBlockEntity.java`、`world/level/gameevent/vibrations/VibrationSelector.java`：序列化与运行态差异。

## 验证与尚未证明的部分

`runGameTest` 使用真实 26.3 Fabric 启动并执行 Mixin audit。项目的一项顺序集成测试覆盖：已加载区块新实例恢复、实体年龄/计划刻延迟、全局排队与在途异步操作、100×100 未加载区域及缺失记录、维护期间 gameTime 固定、延迟 tick 标记安装、活塞/vibration NBT 生命周期、损坏坐标预检。Fabric 另含自己的内置测试，不能把输出的测试总数当成项目场景数。

GameTest 的 waitUntilNextTick 只 drain 任务、不按 20 TPS 等待，因此日志中循环数可能极大；其耗时不应外推普通服务器停顿时间。测试未启动 DedicatedServer 的 watchdog。

后续已完成 vanilla 和常用模组组合下的 256 区块/1,024 实体五轮恢复，以及 10,000 个实际有地形存档的未加载区块往返。还覆盖 TIS 队列、VMP 加载收尾、Extra Extras TNT 标记、TPC Wild 取消、准备阶段玩家进入选区、Carpet fake player 车辆 seat order 与 GCA 延迟任务回归。C2ME 适配已加入 scheduler/NoTickVD 静止屏障、root ticket 暂存并由其正常降级/恢复、存储线程 IO 错误检查和 gcFree serializer runtime NBT bridge；C2ME-only 与记录中的指定组合已通过 GameTest。版本、指标和覆盖限制见[兼容性记录](compatibility-26.3.md)。另已完成十一模组组合、gcFree serializer 开启的专用服压力测试，watchdog 阈值保持 10 秒，结果 PASS；10,000 个有地形记录的未加载区块备份约 31 秒、恢复约 98 秒。它不代表任意模组任务或所有 C2ME 配置均通过。

仍待专门验证：真实多人协议与容器/预测刷新、断线和重新配置竞态、极端光照边界/世界生成中的大规模恢复、10,000 个高密度已加载区块、整盘写满/进程崩溃和任意第三方模组写入。已验证隔离路径真实 IO 写入失败的 C2ME 错误探针，不把它等同于全部存储故障。当前不承诺生产环境可用或任意运行态完全无损。
