# Coaster Perf Fix (Unofficial)

> **非官方补丁。** 不隶属于 Create: Coasters Simulated 的作者 SilverGold，也未获其背书。
> 本仓库是独立实现，不包含原模组的任何代码。

`Create: Coasters Simulated 0.1.5` 的服务端性能补丁。**不改动原模组 jar**，用 Mixin 给四处
「在 pre-physics tick 里每 tick 全量重跑」的调用加上限流；1.1.0 起还给扫描原语本身加了一层
section 级剪枝。客户端可以继续用原版 0.1.5，网络协议没有任何变化。

**1.0.1 起还带一层 Sable Photomancy 蓝图兼容**（可选，装了 Photomancy 才生效）：
让蓝图保存/粘贴时铆钉、气球、车厢连杆不再丢。原理与验收日志见
[`BLUEPRINT_COMPAT.md`](BLUEPRINT_COMPAT.md)。

- Minecraft 1.21.1 / NeoForge 21.1.x
- 依赖：Create `6.0.10+`、Sable `2.0.0+`、Create: Coasters Simulated `0.1.5+`
- 可选：Sable Photomancy `sable_schematic_api 1.0.0+`（蓝图兼容层，没有就不启用）

## 为什么需要

四处问题的共同病灶是**「同 tick 去重」被当成了「限流」**：判断条件写成
`cached.gameTime == gameTime` 或 `last == gameTime`，只在同一个 tick 内有效，
跨 tick 必然失效，于是每 tick 全量重跑一遍。叠加 Sable 的 sublevel 数量后
直接把服务端 TPS 拖垮。

| # | 目标 | 原版 | 本补丁 |
|---|---|---|---|
| 1 | `CoasterCartBearingTickCache.scan` | `gameTime == cached.gameTime` → 缓存从不命中，每 tick 整块 plot 扫描 | TTL 缓存：有轴承格 40 刻 / 无轴承格 100 刻；**plot 还没有 chunk 时不缓存负结果**（1.4.1） |
| 2 | `RivetDisassemblyEnforcer.onPrePhysicsTick` | 每个 sublevel 每 tick 跑 `enforceSingleRivet` + `popIfSelfHosted` | 30 刻 / 20 刻节流 |
| 3 | `CoasterCartTrackSnap.onPrePhysicsTick` | 非 cart sublevel 每 tick 走 `onCartRemoved`（含 `CoasterCartTrainLinkSync` 发包） | 只在状态跃迁那一次执行 |
| 4 | `CoasterCartTrainLinkConstraint.maybeRestorePersistedLinks` | 每 tick 全量恢复持久化连杆 | 每 60 刻（3 秒）一次 |
| 5 | `isDefunctLinkPartner` / `onCartRemoved` | 「成员资格查不到」= 「这节车没了」→ 删掉持久化连杆 | 删之前**绕开缓存**重新裁决，`CART` / `UNKNOWN` 一律不删（1.4.1） |

第 1 项收益最大：`CoasterCartPlotScan.scanBearingCells` 是
「`plot.getLoadedChunks()` × section × 16×16×16 逐格 `getBlockState`」的三重循环，
而 `scan` 有 6 个调用点，且**不含 cart 的 sublevel 也照样被扫**。地图上绝大多数
sublevel（组装体、机械、平台）都不含 cart，TTL=100 等于把这块开销压到 1/100。

但 TTL 治的是**调多少次**，单次扫描的成本一直没动。`CoasterCartPlotScan` 拿
`section.hasOnlyAir()` 当唯一剪枝，而那个方法只是 `nonEmptyBlockCount == 0`——它挡的是
**纯空气** section。只要 section 里有一个方块（轨道、石头、羊毛），4096 次 `getBlockState`
一次不少。1.1.0 在同一个判断上再叠一层 `section.maybeHas(state -> state.is(目标方块))`：
调色板级判断，`GlobalPalette` 的 `maybeHas` 恒返回 `true`（保守放行），所以返回 `false`
是**证明**而不是猜测——不含目标方块的 section 整段跳过，`count` / `cells` / 代表点全部不变，
只是不再走本来就没命中的格子。这一层对 `RivetDisassemblyEnforcer` 的直调、客户端兜底和
调试渲染器同时生效。

**1.2.0 又补了三处每 tick 重复工作**：气球恢复扫描的守卫是 `last != gameTime`，而 `gameTime`
每 tick 都变，于是它每物理 tick 遍历**全部 sublevel**，只为重新登记早就登记过的气球——和 1.0.0
修的连杆恢复是同一个写法错误，原版在两处各写了一遍，之前只补了一处。另外气球 tick 与连杆 tick
里各有一处「同一个 tick 内把同样的答案再问一遍」。三处都只在**一次钩子调用之内**复用，不缓存
跨 tick 的结果；三个开关都在配置里，关掉即回到上游行为。

**1.4.0 治的是「车在轨道上贵几倍」**：实测发现那段开销不是物理，而是贝塞尔曲线手柄长度被每个
采样点重复解析（`getLengthAtEndpoint` 每次内部还会再查两遍 canonical，而每次查询是两次
`synchronized` WeakHashMap + 一个新建 String 键）。现在按「连接 + 端点」缓存、用上游自带的
revision 精确失效。A/B 实测：canonical 查找样本 219/328 → **0/0**，曲线帧总样本 249/351 → 84/93，
MSPT p95 14.6 → 11.8 ms。开关 `curveHandleLengthCache`。原理与数据见
[`OPTIMIZATION.md`](OPTIMIZATION.md) §14。

**1.4.1 修的是 TTL 的副作用，而不是 TTL 本身**：连杆的存亡判定读的是「这节车还在不在」，
而 1.1.0 把这个判定从「每 tick 重扫」改成了「40/100 刻才重扫」。载入世界时 sublevel 已经在容器里、
plot 还没填充完，`isCartSubLevel` 就会对一节**还没加载好**的车返回 `false`；上游把这个 `false`
读成「车没了」，`unlinkPair` 直接把双向记录从车厢 NBT 里删掉——不可恢复。上游自己没事，是因为它
下一 tick 就重扫、答案立刻恢复；TTL 把「看不见」拉长到 2–5 秒，足够删干净整列车。

两处独立修补：**删除前重新裁决**（`CartLinkGuardMixin` + `CartMembership`：绕开缓存重算，
`CART` 或 `UNKNOWN` 一律拒绝删除），以及**负结果不进缓存**（plot 连一个 chunk 都没有时，
「没有轴承格」这个答案不写进缓存）。guard 只走本来就要删东西的路径，热路径零影响；
开关 `protectCartLinks`。原理、证据表与边界见 [`CHANGELOG.md`](CHANGELOG.md) 1.4.1。

## 配置

`config/simcoastersfix-server.toml`（首次启动生成）：

```toml
bearingScanTtlTicksWithCart      = 40    # 有轴承格 sublevel 的扫描缓存刻数
bearingScanTtlTicksEmpty         = 100   # 无轴承格 sublevel 的扫描缓存刻数
rivetCheckIntervalTicks          = 30    # 铆钉单铆钉检查间隔
rivetSelfHostCheckIntervalTicks  = 20    # 铆钉自托管弹出检查间隔
trainLinkRestoreIntervalTicks    = 60    # 连杆恢复间隔
balloonRestoreThrottle           = true  # 气球恢复扫描是否限流（1.2.0）
balloonRestoreIntervalTicks      = 40    # 气球恢复扫描间隔（1.2.0）
balloonTickMemo                  = true  # 气球 tick 内重复查询去重（1.2.0）
balloonPhysicsMemo               = true  # 气球 tick 内物理管线速度读取去重（1.3.0）
curveHandleLengthCache           = true  # 贝塞尔手柄长度缓存（1.4.0）
trainLinkTickMemo                = true  # 连杆 tick 内重复查询去重（1.2.0）
protectCartLinks                 = true  # 成员资格不可观测时拒绝删连杆（1.4.1）
```

调大 = 更省 CPU、感知更迟；**任一值设为 1 = 回到原版行为**，可用于 A/B 对照排障。

## 启动自检

Mixin 的注入只有在**目标类被加载**时才校验。类没被加载过，日志里就一片安静——
「没报错」很容易被误读成「补丁生效了」。所以模组在服务器启动完成时主动强制加载
五个目标类，逐个检查注入的 `@Unique` 字段是否存在（十二个注入点，`RivetDisassemblyEnforcer`、
`CoasterCartTrainLinkConstraint` 各占三项，`BalloonTetherController` 占三项）：

```
[simcoastersfix] patch applied to CoasterCartBearingTickCache#simc$scans
[simcoastersfix] patch applied to CoasterCartPlotScan#simc$sectionsSkipped
[simcoastersfix] patch applied to RivetDisassemblyEnforcer#simc$lastRivetCheck
[simcoastersfix] patch applied to RivetDisassemblyEnforcer#simc$lastSelfHostCheck
[simcoastersfix] patch applied to CoasterCartTrackSnap#simc$teardownDone
[simcoastersfix] patch applied to CoasterCartTrainLinkConstraint#simc$lastRestore
[simcoastersfix] patch applied to BalloonTetherController#simc$balloonRestoreAt
[simcoastersfix] patch applied to BalloonTetherController#simc$balloonTickMemo
[simcoastersfix] patch applied to BalloonTetherController#simc$balloonPhysicsMemo
[simcoastersfix] patch applied to CoasterCartTrainLinkConstraint#simc$trainLinkTickMemo
[simcoastersfix] patch applied to CoasterCartTrainLinkConstraint#simc$cartLinkGuard
[simcoastersfix] patch applied to CoasterPeerCurveHandleLengths#simc$handleLengths
[simcoastersfix] self-check OK: 12/12 mixin targets active
```

看到 `self-check FAILED` 说明目标模组版本对不上，注入点没找到——此时
`mixins.json` 的 `defaultRequire: 1` 通常已经让加载直接失败，不会带病运行。

## 构建

需要 JDK 21。`maven.neoforged.net` 在本机不可达，走 `mirrors.qlu.edu.cn/bmclapi`
镜像（`~/.gradle/init.gradle` 已配），因此 `neo_version` 钉在 **21.1.249**——
该镜像对 21.1.264/265 返回 404。产物在 265 上运行兼容（只依赖 `ServerSubLevel`
等稳定 API）。

```
gradlew.bat build          # 产物 build/libs/simcoastersfix-1.4.1.jar
gradlew.bat runServer      # 本地 dev 验证（run/mods 需放 create + sable + simulatedcoasters）
```

`libs/` 下五个 jar 只用于编译期符号解析，**不会打进产物**，也**不在仓库里**
（`.gitignore` 已排掉 `libs/*.jar`）。自行准备：

- `simulatedcoasters-0.1.5.jar` — 补丁目标（MIT）
- `sable-2.0.6.jar` — `ServerSubLevel` 等类型（PolyForm Shield）
- `sable-companion-common-1.6.0.jar` — 从 sable jar 的 `META-INF/jarjar/` 里解出来的；
  `ServerSubLevel.getLevel()` 声明在这个 companion 接口上，缺它编译不过
- `sable-photomancy-1.0.1.jar` — 蓝图兼容层要编的 `api.blueprint.*` 扩展点；**只在编译期需要**，
  运行期由 Photomancy 本体提供
- `create.jar` — 1.4.0 起需要：曲线手柄长度补丁要编 `BezierConnection`（Create `6.0.10`，MIT/ARR 双协议）；
  **只在编译期需要**，运行期由 Create 本体提供

这五个 jar 属于第三方产物，再分发各有条件，因此不随源码发布。

## 与上游 fork 的差异

同目标的社区修复是 `sjzeero/create-coasters-simulated`（0.1.5 反编译 fork + 一个 fix commit）。
本补丁对照它实现，但有两处**故意不同**：

1. **第 3 项只拦 `onCartRemoved`**。fork 把 `tearDownAllRailCouplingForSub` 和
   `syncPlotSnapEngaged` 一起门控了，但（a）`tearDown` 只是 9 次 map remove，幂等且便宜；
   （b）`syncPlotSnapEngaged` 自身已有 `LAST_SNAP_ENGAGED_SYNCED` 幂等闸，稳态下并不发包。
   真正的每 tick 出血点只有 `onCartRemoved`。
2. **不引入 fork 在 `isRemoved` 分支新增的 `onCartRemoved` 调用**——那是行为修正而非性能项，
   混进性能补丁会扩大回归面。

另外本补丁把四个间隔做成了配置项（fork 是硬编码），并加了启动自检（fork 没有）。

## 已知边界

- **连杆被删掉不可恢复**：1.4.1 只保证「不会再删」。如果上一次载入已经把某列车的耦合从 NBT 里清掉了，
  只能回滚世界备份或重新连一次。
- 1.4.1 的 guard 覆盖 `isDefunctLinkPartner`（`pruneDefunctLinks` / `tryRestoreLink` / 每 tick 的
  `restorePersistedLinks`）与 `onCartRemoved`（pre-physics 的拆卸路径）；`pruneInvalidLinksForCoupling`
  ——玩家主动连车时那条支路——仍读缓存，未加 guard。
- TTL 是**可见延迟**：cart 轴承格变化最坏要等 40 刻（有轴承）/ 100 刻（无轴承）才被感知。
  频繁插拔 cart 的场景可以调小 `bearingScanTtlTicksEmpty`。1.1.0 只降单次扫描成本，没有
  缩短这个延迟——把 `bearingScanTtl*` 设成 `1` 才能回到每 tick 精确扫描的观感。
- section 级剪枝有**下限**：含 >256 种方块状态的 section 走 `GlobalPalette`，`maybeHas`
  恒为 `true`，那类 section 退回原来的 4096 格全扫。常见场景（轨道 + 空气 + 少量装饰）远
  达不到 256 种，收益正常。
- 真正的根治是**事件驱动索引**（`plot → Set<BlockPos>`，在 setBlock / destroyBlock 上增量
  维护，扫描退化成一次查表）。那要改数据结构与不变量，属于上游作者的活，本补丁只提交短期
  patch——见 [`OPTIMIZATION.md`](OPTIMIZATION.md) §5 D。
- 服务端装即可，客户端不必装——**但这条只对专用服务器成立**。单人游戏里集成服务端跑在客户端进程内，
  jar 仍然要放进客户端的 `mods/`。装在纯客户端也无害（tick 处理器和蓝图钩子都只在服务端跑）。

## 图标

`src/main/resources/logo.png`（512×512）取自原模组 jar 内的 `logo.png`，右下角叠了一个红色 `fix`
（Arial Black + 白色描边 + 深色投影，旋转 -10°），保证在模组列表 32/64 px 下仍可辨认。
先在 1080 空间完成叠加再 LANCZOS 缩到 512，保证与设计稿逐像素一致。
生成脚本见 [`tools/make_icon.py`](tools/make_icon.py)，自带从 `libs/` 里的原 jar 提取底图，
不落中间文件；重新运行会产出同样字节的 `logo.png`。

图标是原模组美术的**修改版**，因此按 MIT 的署名要求，jar 内 `META-INF/NOTICE`
带有完整归属声明与 MIT 全文。

## 开发文档

- [`BLUEPRINT_COMPAT.md`](BLUEPRINT_COMPAT.md) — 蓝图兼容层的原理与验收日志
- [`OPTIMIZATION.md`](OPTIMIZATION.md) — 性能分析：热路径数据、调用点谱系、section 级剪枝（已落地）、实测数据与 JFR 验证方法

## 许可

本补丁自身：**MIT**（jar 内 `LICENSE`）。

第三方：

| 组件 | 协议 | 本仓库的处理 |
|---|---|---|
| Create: Coasters Simulated（SilverGold） | MIT | 图标为其衍生作品，已在 `META-INF/NOTICE` 附版权声明与 MIT 全文 |
| Sable（RyanHCode） | PolyForm Shield 1.0.0 | 仅在运行期链接，**不再分发**任何部分 |
| Sable Photomancy（Rew1nd） | MIT | 蓝图兼容层只调用其公开扩展点，**不包含也不分发**其代码 |
| Create（simibubi） | 代码 MIT / `assets/` All Rights Reserved | 不包含任何代码或资产 |

完整声明见 [`src/main/resources/META-INF/NOTICE`](src/main/resources/META-INF/NOTICE)。

上传 CurseForge 时请把项目协议设为 **MIT**。
