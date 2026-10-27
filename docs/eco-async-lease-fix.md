# ECO + Async：碰撞表借用计数与停服修复

核查版本：EntityCollisionOptimizer `1.0.0-mc26.3-alpha.11`，源码标签 `v1.0.0-alpha.11`；使用 `tests/compatibility-lock.json` 中固定 SHA-256 的发布 JAR，另用 `javap` 核对实际方法签名。

## 原因

停服时的调用链是 `CollisionFrame.destroy()` → `LevelCollisionFrame.close()` → `CollisionStateTable.clear()`。`clear()` 检查 `borrowers != 0` 后抛出 `Clearing a borrowed collision table`，这项保护本身是正确的：不能释放仍被借用的原生内存。

同步缺口在运行期的借用与归还路径：

1. `LevelCollisionFrame.collectPushable()` 是同步方法，持有当前 frame 的监视器，调用 `PushBatch.prepare()`，再由 `CollisionStateTable.borrow()` 执行普通整数 `borrowers++`。
2. `LivingEntityMixin.pushEntities()` 用 try-with-resources 关闭批次。`PushBatch.close()` 先在 frame 锁之外执行 `bodies.release()`，其中有普通整数 `--borrowers` 和延迟退役行清理。
3. 直到之后调用 `recycler.accept(this)`，才通过 `LevelCollisionFrame.recycle()` 获取 frame 锁。这个锁保护了对象池，却没有保护计数减少与退役行清理。

因此，同一维度多个 Async 实体任务的归还可以相互竞争，也能与另一个任务持锁获取新批次竞争，形成读改写丢失。`try-with-resources` 只能保证执行 close，不能让共享计数原子化。停服阶段已经先执行 Async 的关闭逻辑；不能把这个问题仅解释为“停服时还在正常借用”，也不能靠把计数清零解决。

源码依据：[LevelCollisionFrame](https://github.com/water2004/EntityCollisionOptimizer/blob/v1.0.0-alpha.11/src/main/java/org/edtp/entitycollisionoptimizer/natives/LevelCollisionFrame.java)、[PushBatch](https://github.com/water2004/EntityCollisionOptimizer/blob/v1.0.0-alpha.11/src/main/java/org/edtp/entitycollisionoptimizer/natives/PushBatch.java)、[CollisionStateTable](https://github.com/water2004/EntityCollisionOptimizer/blob/v1.0.0-alpha.11/src/main/java/org/edtp/entitycollisionoptimizer/natives/CollisionStateTable.java)。

## EDB 中的修复

适配集中在 `adaptors/entity_collision_optimizer/`，新增独立 Mixin 配置：

- `EcoFrameMixin` 在 `collectPushable()` 调用 `prepare()` 之前，把所属 frame 绑定到批次。失败清理和对象池复用也使用同一个所有者。
- `EcoPushBatchMixin` 让 `close()` 在所属 frame 的监视器内执行。借用、归还、退役行清理与回收共用已有的锁，没有重置计数、强行释放内存或跳过异常。
- 同时让 `applyNativeRun()` 持有该锁。另一个 `prepare()` 可能扩容 body table 并关闭旧 arena，原生推挤调用不能在持有旧行指针时与其并发。

适配只在安装 ECO 时加载；没有新增核心常驻线程或逐 tick 扫描。运行时成本是批次归还和原生推挤对现有 frame 锁的使用，同一维度这些共享表操作会串行化。它没有把整个实体 tick 串行化，也不声称解决所有第三方模组的实体状态并发问题。

## 可复现验证与停服判定

`EcoLeaseProbe` 使用真实 ECO 类创建一个批次。主线程持有其 frame 锁时，让另一个线程关闭批次，并等该线程阻塞在监视器上，然后读取借用计数：

- 修复前：`before=1, during=0`，测试稳定失败；关闭线程已经在锁外减少了计数，随后才阻塞在回收阶段。
- 修复后：计数在锁持有期间保持不变，解锁后关闭线程完成，计数准确减少一次。

完整往返测试结束时，还会检查所有维度的借用计数均为 0。Gradle 不再只看 GameTest 的成功结果：同时要求 `SERVER_STOPPED` 生命周期标记，并拒绝日志中出现 `Exception stopping the server` 的运行。这样功能断言成功、停服却失败的情况不能再显示为成功构建。

修复前后的详细运行记录见[本轮回归报告](../tests/results/26.3-command-ux.md)。
