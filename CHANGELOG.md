# 更新日志

> English release notes (for the CurseForge / Modrinth changelog field): [`CHANGELOG.en.md`](CHANGELOG.en.md)

## 1.4.0 — 2026-11-16

**新增：贝塞尔曲线手柄长度缓存（'车在轨道上比不在轨道上贵几倍'的元凶）**

实测：车吸附在轨道上跑时，服务端 tick 线程最大的单项开销**不是物理**，而是曲线手柄长度的重复解析（`Rapier3D.step` 同期只有 12–18%，曲线那组到 30%）。

- `CoasterPeerCurveHandleLengths.getLengthAtEndpoint` 每次都被曲线采样循环调用，而它**无条件**先算一次 `fallbackSingleHandleLength` → `BezierConnection.getHandleLength()`；后者被上游自己的 mixin 包着、又回递归调 `getCanonicalLengths`——一次端点查询 = 两次 canonical 查询，每个采样点两个端点 ≈ 4 次。
- 每次 `getCanonicalLengths` 是：2 次 `synchronizedMap(WeakHashMap)` 查询 + 拼一个 `"<long>:<long>"` String 键 + 1 次 `HashMap<String>` 查询。
- 现在按「连接 + 端点」缓存，并用上游自带的 `AtomicLong REVISION` 精确失效（每个写入者都会 bump），**不引入任何时间滞后**。开关 `curveHandleLengthCache`（默认开）。
- **A/B 实测**（同一会话、改配置热重载、车在轨道上跑）：

| | busy 样本 | 曲线样本 | 其中 canonical | MSPT p95 | p99 |
|---|---|---|---|---|---|
| 关闭（= 上游） | 1141 / 1137 | 249 / 351 | **219 / 328** | 14.12 / 14.63 | 15.99 / 16.90 |
| 开启 | 876 / 715 | 84 / 93 | **0 / 0** | 13.60 / 11.76 | 15.46 / 13.42 |

  剩下的 84/93 全是缓存命中本身的开销，上游那段查找**一个样本都不剩**。
- 编译期新增依赖 `libs/create.jar`（只为 `BezierConnection`，**不进产物**）；自检 10 → **11 项**。

**已知边界**：缓存值由 revision 精确失效，轨道被编辑后下一次调用即生效，没有可见延迟。

## 1.3.0 — 2026-11-16

**新增：气球路径的物理管线读取去重**

`BalloonTetherController.onPrePhysicsTick` 里同一份速度被反复取：`applyTetherSpring` 一次取四份（气球线速度 / 角速度、宿主线速度 / 角速度），`dampHostSwing` 又把宿主的取一遍，`dampHostVerticalInBand` 再取一次。每次都是一个 `PhysicsPipeline` 往返（Rapier 侧）。实测字节码里现在是 **7 个调用点、5 个注入器**。

- 一次 pre-physics 钩子内，力与冲量只**入队**、步进时才应用，所以同一个刚体的速度在这次调用里不会变。按刚体记一次，重复的读取直接复用，开关 `balloonPhysicsMemo`（默认开）。
- 复用方式是把值**拷进调用方的缓冲区**，不是共享实例——调用方会原地改这个向量，别名会变成物理 bug。省掉的只是往返，不是分配的语义。
- 刻意没动：`wakeUp` 的重复调用（没有证据说明它贵，多一个注入点就多一处「上游改名就崩」）、两处 `level.clip()` 悬停射线（会碰手感）、每气球约 20 个向量/四元数分配（要改方法体）。
- 自检 9 项 → **10 项**。

**已知边界**：去重只活在一次钩子调用之内，不跨 tick；关掉开关即回到上游读法（行为本来就等价，开关是给排障用的）。

## 1.2.0 — 2026-11-16

**新增：气球与列车连杆的每 tick 重复工作**

上游把「每 tick 都会变的 `gameTime`」当成「只跑一次的标记」，同一个写法出现了两次。
`CoasterCartTrainLinkConstraint.maybeRestorePersistedLinks` 在 1.0.0 就限流了，这次轮到气球那处；
另外补了两处「同一个 tick 内把同样的答案再问一遍」。

- **气球恢复扫描限流** —— `BalloonTetherController.maybeRestoreTethers` 的守卫是 `last != gameTime`，而 `gameTime` 每 tick 都变，于是 `restoreTethers` 每物理 tick 遍历**全部 sublevel**（每个一次 `getBlockState(ZERO)` + 一次 `containsKey`），只为重新登记早就登记过的气球。开关 `balloonRestoreThrottle`（默认开），间隔 `balloonRestoreIntervalTicks`（默认 40 刻）。
- **气球 tick 内去重** —— `onPrePhysicsTick` 分两趟遍历活跃气球，两趟都各解析一次 气球 → sublevel → 方块实体 → 宿主。现在同一次调用内的重复直接复用，开关 `balloonTickMemo`（默认开）。
- **列车连杆 tick 内去重** —— `tick` 里每条连杆解析两次 cart（A / B），同一 tick 内的重复丢掉，开关 `trainLinkTickMemo`（默认开）。
- 自检 6 项 → **9 项**；启动日志会打出三个新开关的状态。

**没动的** —— 三处去重都只活在**一次钩子调用之内**，不缓存跨 tick 的结果；`ACTIVE_LINKS` 的 `List.copyOf` 快照保留（那是遍历中解链的安全前提）；气球那两个 `level.clip()` 悬停射线和每气球约 20 个 `Vector3d`/`Quaterniond` 也没动——它们接在物理反馈回路里，得实测手感才敢改。

**已知边界** —— 气球恢复的可见延迟 = `balloonRestoreIntervalTicks`（默认 40 刻 / 2 秒），且只有没走过 sublevel observer 的气球才会踩到。三个开关关掉即回到上游行为。

## 1.1.0 — 2026-11-16

**新增：扫描原语的 section 级剪枝**

1.0.1 治的是调用频率（四处 TTL / 节流），单次调用的成本没动。这版治单次成本。

- `CoasterCartPlotScan.scanBearingCells` / `forEachBearingWorldCell` 原先把 `section.hasOnlyAir()` 当唯一剪枝，但那个方法只是 `nonEmptyBlockCount == 0`——它只挡**纯空气** section，任意一个方块（轨道、石头、羊毛）都让 section 跑满 4096 次 `getBlockState`。现在在同一处判断上叠一层 `LevelChunkSection.maybeHas(state -> state.is(目标方块))`：调色板里没有目标方块就整段跳过。
- **语义等价**：`BearingScanResult` 的 `representative`（`compareYXZ` 取最小）、`bearingCount`、`cells` 的内容与顺序全部不变——被跳过的 section 本来就不产出元素。`GlobalPalette`（>256 种状态）的 `maybeHas` 恒返回 `true`，那类 section 自动退回原逻辑，所以返回 `false` 是**证明**而不是猜测。
- 收益面比 TTL 宽：`RivetDisassemblyEnforcer` 直调 `scanBearingCells`、客户端 `forEachBearingWorldCell` 兜底、`CoasterCartDebugRenderer` 走的是同一对方法，一处注入全覆盖。
- 自检从 4 项扩到 6 项：补上 `RivetDisassemblyEnforcer#simc$lastSelfHostCheck`（1.0.1 就注入了但从没被检查过）和 `CoasterCartPlotScan#simc$sectionsSkipped`。日志行改成 `类名#字段名`，同一个类占两项时不再看起来像重复输出。

**没动的**：四个节流间隔的默认值、配置文件格式、网络协议、蓝图兼容层。客户端仍然不需要更新。

**已知边界**：TTL 的可见延迟（40 / 100 刻）照旧——这版只降单次成本，没有缩短延迟；含 >256 种方块状态的 section 走 `GlobalPalette`，收益打折。

后续：把事件驱动索引（`plot → Set<BlockPos>` 增量维护，扫描退化成一次查表）整理成上游 issue + PR，原理见 [`OPTIMIZATION.md`](OPTIMIZATION.md) §5 D。

## 1.0.1 — 2026-11-15

**新增：Sable Photomancy 蓝图兼容（可选）**

装了 `sable_schematic_api 1.0.0+` 时自动启用；没装不影响原有的性能限流。

- **铆钉不再被自动拆除** —— 蓝图保存/粘贴后原 `HostPos` 还指着旧坐标，Coasters 判定宿主消失就把铆钉拆了。现在按蓝图内的方块引用（宿主在世界方块上时按相对蓝图原点的偏移）保存，并在方块实体加载**之前**换算回新坐标。
- **红气球重新吸附到粘贴后的宿主子层级** —— 原来存的是 raw sublevel uuid，粘贴必然换新 uuid，之后再也解析不到宿主。
- **过山车车厢的列车连杆保留** —— 连杆存在子层级 user data 里，Photomancy 从不导出；现在走蓝图边车，并把两端 uuid 重映射。
- 存盘格式新增的键都带 `simcoastersfix:` 前缀，**1.0.0 存的旧蓝图照常能用**，只是不含这些修复。

**没动的**：四个性能限流项、配置文件、网络协议、任何方块 / 物品 / 配方。客户端仍然不需要更新。

**已知边界**：车轨锚点之间的曲线连接（`AnchorPeerCurves`）仍是绝对坐标，本次未处理。原理与验收日志见 [`BLUEPRINT_COMPAT.md`](BLUEPRINT_COMPAT.md)。

## 1.0.0 — 2026-11-14

首个版本。四处 pre-physics tick 里的「全量重跑」改为可配置限流：轴承格扫描、铆钉拆解检查、车厢拆卸广播、持久化连杆恢复。
