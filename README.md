# Coaster Perf Fix (Unofficial)

> **非官方补丁。** 不隶属于 Create: Coasters Simulated 的作者 SilverGold，也未获其背书。
> 本仓库是独立实现，不包含原模组的任何代码。

`Create: Coasters Simulated 0.1.6` 的服务端性能补丁。**不改动原模组 jar**，用 Mixin 只保留
在 0.1.6 上仍然成立的几处优化，并带一组**默认开启的热点探针**。客户端装不装都行（专用服务器上
钩子只在服务端跑），网络协议没有任何变化。

1.5.0 是一次**减法**：0.1.6 把 1.0.0 / 1.1.0 治的病自己治了，所以那几条补丁不是「收益变小」，
而是**失去目标**——注入点被搬走或重命名，硬留着只会崩。删掉的理由与字节码证据见
[`UPSTREAM-0.1.6.md`](UPSTREAM-0.1.6.md)。

- Minecraft 1.21.1 / NeoForge 21.1.x
- 依赖：Create `6.0.10+`、Sable `2.0.0+`、Create: Coasters Simulated **`0.1.6`（只支持 0.1.6）**
- 可选：Sable Photomancy `sable_schematic_api 1.0.0+`（蓝图兼容层，没有就不启用）

## 0.1.6 自己已经做的（所以 1.5.0 删掉了）

| 原来补的 | 0.1.6 的做法 |
|---|---|
| section 级剪枝（1.1.0） | `CoasterCartPlotScan.skipSection` 里同一句 `maybeHas(state -> state.is(block))` |
| 轴承扫描 TTL（1.0.0） | 事件驱动成员索引 `CoasterCartServerMembership`，place / remove / 搬动 / 加载都精确失效，且用 `readyPlotChunkCount` 正面处理加载窗口 |
| 连杆恢复节流（1.0.0） | `maybeSafetyRestorePersistedLinks`，`gameTime % 100 == 0` 才跑，并且只遍历 cart |
| rivet 30/20 拍节流（1.0.0） | `RivetPlotMembership.beginEnforcePass`（同 gameTime 只跑一次）+ `rivetsAndDirty`（只扫 rivet 与 dirty） |
| TrackSnap 的 `onCartRemoved` 去重（1.0.0） | `forEachCart` 只遍历 cart，且只在成员索引判定「不是 cart」时才拆卸 |

## 1.5.0 还补的四处（都对着 0.1.6 的字节码核过）

| # | 目标 | 为什么还成立 |
|---|---|---|
| 1 | `CoasterPeerCurveHandleLengths.getLengthAtEndpoint` 缓存 | 该类 0.1.6 **字节未变**；每次查询仍是两次 `synchronized` WeakHashMap + 新建 String 键，并按 revision 精确失效。A/B：canonical 样本 219/328 → **0/0**，MSPT p95 14.6 → 11.8 ms（§14） |
| 2 | `BalloonTetherController.maybeRestoreTethers` 节流 | 该类字节未变，`last != gameTime` 的写法错误原样保留：每物理 tick 遍历全部 sublevel |
| 3 | 气球 tick 内查询/物理管线去重 | 同上；`findBalloonById` ×2、`balloonBlockEntity` ×2、`resolveHostSubLevel` ×2、7 处管线测速 |
| 4 | 连杆 tick 内 `findCartById` 去重 | `tick` 内偏移 74 / 84 仍是两次解析 |
| 5 | 删除前重新裁决（1.4.1 的 guard） | 0.1.6 的成员索引仍会把「plot 没填充完时归类过」的 sublevel **永久**缓存成「不是车」，实测 10,240 次误判、78 节车厢（1.5.1） |

配置全部可以单独关掉，关掉即回到上游行为。

## 热点探针（1.5.0 新增，默认开启；1.5.1 首次实测）

0.1.6 把「每 tick 全量扫」换成了事件索引，剩下的成本换了个形状：**每 tick 一次 O(sublevel) 的
遍历**，单次很便宜，采样到的 self-time 里几乎看不见。这类成本只能靠计数与计时证实或证伪，
所以 1.5.0 自带探针。真实存档（≈958 个存活 sublevel）跑出来的稳态：

```
probes[600t]: cart.readyChunks 574851x 135.49ms 0.24us/call | cart.reconcile 5424x 119.18ms 21.97us/call
            | cart.membershipScan 2325x 2.23ms 0.96us/call | cart.reclassify 9x
            | rivet.reconcileLive 2400x 38.13ms 15.89us/call | rivet.rivetsAndDirty 2400x 7.23ms 3.01us/call
            | plot.scanBearingCells 1599x 48.11ms 30.09us/call | alloc 209.01MB/s || total 583.95us/tick
```

结论：成员索引这一整趟 ≈ **0.2 ms/tick**，而且几乎全花在 `readyPlotChunkCount`（958 次/tick）——
**这是唯一被证实的 0.1.6 新增热点**，约占 tick 预算 0.4%；没有安全的局部修法（那个计数就是
「plot 何时填充完」的检测手段，属上游的活）。`plot.scanBearingCells` 的 78 µs/tick 大部分是
守卫在纠正上游那个错误的负缓存。

| 计数项 | 对应 0.1.6 的哪一段 | 看点 |
|---|---|---|
| `cart.reconcile` | `CoasterCartServerMembership.reconcile` | 每 tick 每个维度被调用多次，但只有第一次是真跑；看 `us/call` 而不是次数 |
| `cart.readyChunks` | `CoasterCartPlotScan.readyPlotChunkCount` | **每 tick 每个 sublevel 无条件付一次**，遍历 plot 的 loaded chunks；最可疑的新开销 |
| `cart.membershipScan` | `CoasterCartServerMembership.scan` | 缓存命中仍做一次 `getBlockState` 校验，N 辆车 = N 次世界读/tick |
| `cart.reclassify` | `classify` 次数 | 稳态应趋近 0；一直非 0 说明 dirty / ready-chunk 逻辑在抖 |
| `rivet.reconcileLive` | `RivetPlotMembership.reconcileLive` | 每 tick 全 sublevel + 三套 set 裁剪 + 新 record |
| `rivet.rivetsAndDirty` | `RivetPlotMembership.rivetsAndDirty` | 每 tick 建 `HashSet` + `ArrayList` 再逐条查容器 |
| `plot.scanBearingCells` | 三重循环块扫描 | 稳态应极少；每 tick 都有就是成员索引没兜住 |
| `alloc` | 服务端线程分配率 | 上面几处「每 tick new 一个 record/set」的总账 |

读法：`x` 是窗口内调用次数，第一个时长是总计，第二个是单次；`alloc` 用
`com.sun.management.ThreadMXBean` 取服务端线程的分配字节数（取不到时这一项自动消失）。
空载 dev 世界稳定后总计约 **10 µs/tick**，几乎全是 `reconcile` 的 early-out 与两次 rivet pass——
有 sublevel 的世界里这几项才会真正长起来。

**开销**：每次调用两次 `nanoTime`（约 40 ns），只读不改行为；计数是裸 `long`，跨线程丢一拍精度、
不会错。探针混入单独一个 config，`required=false` + `defaultRequire=0`：将来上游再动这些方法，
**丢的是探针数据，不是游戏**。启动时 `probe self-check` 会告诉我们哪几个探针真的挂上了。

## 配置

`config/simcoastersfix-server.toml`（首次启动生成）：

```toml
bearingScanTtlTicksWithCart      = 1     # 1 = 只在同一 tick 内去重（0.1.6 的成员索引已经精确）
bearingScanTtlTicksEmpty         = 1
rivetCheckIntervalTicks          = 1     # 1 = 交给上游的 beginEnforcePass
rivetSelfHostCheckIntervalTicks  = 1
balloonRestoreThrottle           = true  # 气球恢复扫描限流
balloonRestoreIntervalTicks      = 40
balloonTickMemo                  = true  # 气球 tick 内重复查询去重
balloonPhysicsMemo               = true  # 物理管线速度读取去重
trainLinkTickMemo                = true  # 连杆 tick 内重复查询去重
curveHandleLengthCache           = true  # 贝塞尔手柄长度缓存
protectCartLinks                 = true  # 删除前重新裁决（0.1.6 仍会缓存错误的「不是车」）
repairCartMembershipIndex        = true  # 拒绝后让上游重判，把错误的负缓存踢掉（1.5.2）
hotspotProbes                    = true  # 热点探针
hotspotProbeIntervalTicks        = 600   # 探针窗口（600 = 30 秒）
```

调大 TTL 或 rivet 间隔 = 更省 CPU、感知更迟；**都设成 1 = 回到 0.1.6 本身的行为**，可用于 A/B。

> 从 1.4.x 升级：旧配置里的 `trainLinkRestoreIntervalTicks` 已经没有对应代码，NeoForge 会忽略它；
> `bearingScanTtl*` / `rivet*` 的旧值（40/100/30/20）会保留——**建议删掉配置文件让它按新默认重建**，
> 否则等于只用了半个 1.5.0。

## 启动自检

Mixin 的注入只有在**目标类被加载**时才校验。类没被加载过，日志里就一片安静——
「没报错」很容易被误读成「补丁生效了」。所以模组在服务器启动完成时主动强制加载目标类，
逐个检查注入的 `@Unique` 字段是否存在（九个注入点）：

```
[simcoastersfix] self-check OK: 9/9 mixin targets active
[simcoastersfix] hotspot probes on: one summary line every 600 ticks
[simcoastersfix] probe self-check OK: 3/3 probe mixins active
```

看到 `self-check FAILED` 说明上游版本对不上——`mixins.json` 的 `defaultRequire: 1` 通常已经让
加载直接失败，不会带病运行。探针那行是 WARN 而不是 ERROR：探针掉线只是少一份数据。

## 构建

需要 JDK 21。`maven.neoforged.net` 在本机不可达，走 `mirrors.qlu.edu.cn/bmclapi` 镜像
（`~/.gradle/init.gradle` 已配），因此 `neo_version` 钉在 **21.1.249**。

```
gradlew.bat build          # 产物 build/libs/simcoastersfix-1.5.0.jar
gradlew.bat runServer      # 本地 dev 验证（run/mods 需放 create + sable + simulatedcoasters 0.1.6）
```

> 改了 `gradle.properties` 的版本号要 `processResources --rerun-tasks`（或 clean），
> 否则 jar 里的 `neoforge.mods.toml` 还是旧版本号。

`libs/` 下的 jar 只用于编译期符号解析，**不会打进产物**，也**不在仓库里**（`.gitignore` 已排掉）。
自行准备 `simulatedcoasters-0.1.6.jar`（MIT）、`sable-2.0.6.jar`（PolyForm Shield）、
`sable-companion-common-1.6.0.jar`（从 sable jar 的 `META-INF/jarjar/` 解出）、
`sable-photomancy-1.0.1.jar`（只在编译期需要）、`create.jar`（只在编译期需要）。
明细见 [`libs/README.md`](libs/README.md)。

## 蓝图兼容

1.0.1 起带一层 Sable Photomancy 蓝图兼容（可选，装了 Photomancy 才生效）：让蓝图保存/粘贴时
铆钉、气球、车厢连杆不再丢。这一层不走 Mixin，但 `RivetRemoval` 在 0.1.6 的变更列表里，
**升级后请跑一次保存/粘贴验收**。原理与验收日志见 [`BLUEPRINT_COMPAT.md`](BLUEPRINT_COMPAT.md)。

## 已知边界

- **只支持 0.1.6**：`neoforge.mods.toml` 钉的是 `[0.1.6,0.1.7)`。0.1.6 自己就搬走了三个 1.4.1
  依赖的注入点，把版本范围放开等于让用户去踩同一个坑。
- **连杆被删掉不可恢复，而 0.1.6 仍会误判**：`protectCartLinks` 默认开。实测（2026-11-17，真实存档）
  一个 30 秒窗口里守卫拒绝了 **10,240 次**拆卸、涉及 **78 节车厢**，上游 `unlinked` 0 次——
  关掉它，这 78 条耦合会在载入时被删进 NBT。根因是 0.1.6 的成员索引会把「plot 还没填充完时
  归类过的 sublevel」永久缓存成「不是车」，而唯一的重判条件是 `readyChunkCount`，chunk 对象
  早在、内容晚到时它不会变。证据与数字见 [`CHANGELOG.md`](CHANGELOG.md) 1.5.1。
- `pruneInvalidLinksForCoupling`（玩家主动连车那条支路）仍未加 guard。
- 探针数字是**观察**，不是结论：先看 `cart.readyChunks` 与 `cart.membershipScan`，再决定要不要
  为它们写补丁。判断口径同 [`OPTIMIZATION.md`](OPTIMIZATION.md) §14：读代码得到的因果必须用采样验一次。
- 服务端装即可，客户端不必装——**但单人游戏里集成服务端跑在客户端进程内**，jar 仍要放进
  客户端 `mods/`。

## 图标

`src/main/resources/logo.png`（512×512）取自原模组 jar 内的 `logo.png`，右下角叠了一个红色 `fix`
（Arial Black + 白色描边 + 深色投影，旋转 -10°），保证在模组列表 32/64 px 下仍可辨认。
生成脚本见 [`tools/make_icon.py`](tools/make_icon.py)，自带从 `libs/` 里的原 jar 提取底图。
图标是原模组美术的**修改版**，因此按 MIT 的署名要求，jar 内 `META-INF/NOTICE` 带有完整归属声明。

## 开发文档

- [`UPSTREAM-0.1.6.md`](UPSTREAM-0.1.6.md) — 0.1.6 反编译对照：三个注入点为什么失效、上游内建了什么、新热点候选
- [`OPTIMIZATION.md`](OPTIMIZATION.md) — 性能分析：热路径数据、调用点谱系、A/B 方法与 §15 移植记录
- [`BLUEPRINT_COMPAT.md`](BLUEPRINT_COMPAT.md) — 蓝图兼容层的原理与验收日志

## 许可

本补丁自身：**MIT**（jar 内 `LICENSE`）。

| 组件 | 协议 | 本仓库的处理 |
|---|---|---|
| Create: Coasters Simulated（SilverGold） | MIT | 图标为其衍生作品，已在 `META-INF/NOTICE` 附版权声明与 MIT 全文 |
| Sable（RyanHCode） | PolyForm Shield 1.0.0 | 仅在运行期链接，**不再分发**任何部分 |
| Sable Photomancy（Rew1nd） | MIT | 蓝图兼容层只调用其公开扩展点，**不包含也不分发**其代码 |
| Create（simibubi） | 代码 MIT / `assets/` All Rights Reserved | 不包含任何代码或资产 |

上传 CurseForge 时请把项目协议设为 **MIT**。
