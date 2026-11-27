# 更新日志

> English release notes (for the CurseForge / Modrinth changelog field): [`CHANGELOG.en.md`](CHANGELOG.en.md)

## 2.2.1 — 2026-11-27

**修复：蓝图放置会静默丢方块（日志只报三分之二），并在放置过程中自动修补**

### 现象

放置一份 1887 方块的蓝图（`RA-12 DarkMatter`），船体上少一整块隔舱，日志里是 133 条：

```
[Server thread/WARN] [net.minecraft.world.level.chunk.LevelChunk/]:
Trying to set block entity … at position BlockPos{x=20481024, y=124, z=20485120},
but state Block{minecraft:air} does not allow it
```

把放出来的子层级导出、和原蓝图逐格 diff（`tools/blueprint_diff.py`）：

```
原蓝图   1887 方块 / 1278 方块实体 / 206 调色板项 / 0 个 air
放出来   1686 方块 / 1145 方块实体
缺失 201、多出 0、类型变化 0
  ├─ 133 格带方块实体  ← 就是日志里那 133 条
  └─  68 格普通方块    ← 一条日志都没有（67 × create:black_sail、1 × minecraft:black_concrete）
```

**日志只报出实际损失的三分之二。** 无方块实体的方块被丢了，不会有任何记录。

丢失的是 `x 8..15 / y 0..5 / z 18..33` 一个连通体积。把同一份文件放到不同 plot 区（plot z 差 4096），丢的**本地坐标完全一样** —— 跟内容走，不跟落点走。

### 原因

`SableBlueprintPlacer.place` 对每个方块做两件事，字节码里是紧邻的两条无条件指令：

```
377: invokevirtual  net/minecraft/world/level/chunk/LevelChunk.setBlockState(BlockPos, BlockState, Z)
410: invokevirtual  dev/rew1nd/sableschematicapi/api/blueprint/BlueprintPlaceSession.recordPlacedBlock(...)
```

中间没有分支，也**不检查返回值**。而对被丢的那些坐标，`level.getChunk(...)` 给回来的不是 plot 的区块，是一个占位区块：

```
recorded=1887  didNotStickAtRecord=201  enteredSetBlockState=1692  writes=1692  wrongChunk=0
atRecord=minecraft:void_air   expected=create:copycat_panel
  | minY=-64 height=384 outside=false
  | chunk=EmptyLevelChunk@[0, 0]   sectionIndex=11 sections=24
```

链条是这样：

1. `ensureChunk()` 看到 `plot.getChunkHolder(localChunk) != null` 就不创建区块 —— plot 认为这个区块归自己；
2. 紧接着 `level.getChunk(x, z)` 拿到的却是 `EmptyLevelChunk`（占位，区块坐标 `[0, 0]`）—— level 的视图和 plot 的视图不一致；
3. `EmptyLevelChunk#setBlockState` 直接 `return null`，**一个方块都没写**；
4. 放置器不检查返回值，照旧 `recordPlacedBlock`；
5. 建方块实体阶段在这些空格子上挂 BE → 那 133 条 warning；剩下 68 个无实体方块连日志都没有。

读同一坐标得到 `void_air`（不是 `air`）正是占位区块的特征 —— 原版只在"位置不存在"时才这么答。

### 修法：把修补挪到方块实体阶段之前

在 `PLACE_BLOCK_STATES → LOAD_BLOCK_ENTITIES` 这个相位边界补，而不是等放置结束：

```
PLACE_BLOCK_STATES    方块状态全部写完
      ↓  ★ 这里补（本 mod 的 setPhase 钩子）
LOAD_BLOCK_ENTITIES   Photomancy 开始建 BE、装 NBT
```

补的时候**绕开 level 的区块视图**，改走 plot 自己的通道：

```java
BlockPos localPos = storagePos.subtract(subLevel.getPlot().getCenterBlock());
subLevel.getPlot().getEmbeddedLevelAccessor().setBlock(localPos, expected, 2);
```

因为修补发生在 BE 阶段之前，**接下来 Photomancy 会照原流程处理这些格子** —— 用的是蓝图里自己的方块实体 NBT，也会走 `SableBlueprintMapperRegistry` 的 mapper（铆钉 / 气球 / 连杆那套）。本 mod 一行 NBT 代码都不碰。

- `flags = 2` 只发客户端；邻居更新交给 Photomancy 自己的 `notifyPlacedBlocks`（它对整个 placed 列表跑一遍，与放置器原先 `setBlockState(..., false)` 的语义一致）。
- 修补点早于 `finalizePlacement`（`updateBoundingBox` + 传送），所以补上的方块进包围盒与质量，不是事后贴上去的孤块。
- 日志只在真的缺东西时打一行：

```
[simcoastersfix] blueprint place repair: recorded=1887 missing=201 repaired=201 skipped=0 (block entity data still restored by Photomancy)
```

### 2.2.0 的弯路（未发布）

第一版是在 `onPlaceAfterBlocks`（放置结束后）补的：方块能补回来，但**方块实体 NBT 补不回来** —— NBT 在蓝图对象里，`BlueprintPlaceSession` 不暴露它，而放置器的 BE 阶段已经跑过了。结果是那 133 格拿到默认 BE（copycat 没材质、传送带是初始状态），日志里多一项 `blockEntitiesNotRestored=133`。

所以 2.2.1 把修补前移到了相位边界。现在那条 late 分支只作为兜底保留：**它一旦出现在日志里，就说明相位钩子没挂上**（`setPhase` 改名或被别的 mixin 挡了），此时退化到 2.2.0 的行为，不会崩。

### 兼容性与风险

- 新增 **1 个 mixin**（`BlueprintPlacePhaseRepairMixin`，注入 `BlueprintPlaceSession#setPhase` 的 HEAD）。这是 2.0.0"零 mixin"之后唯一的 mixin，独立可选配置 `simcoastersfix.repair.mixins.json` + `require = 0` + `remap = false`：Photomancy 改名字时静默失效，不会把游戏带崩。
- 只在 `phase == LOAD_BLOCK_ENTITIES` 时触发；同一线程有重入保护。
- 验证过的环境：MC 1.21.1 / NeoForge 21.1.x、Sable 2.0.6、Photomancy 1.0.1、Drydock 4.288、Create: Coasters Simulated 0.1.6。

### 未修 / 待办

- **上游那个 plot/level 区块视图不一致本身没有修**，本 mod 只是绕过它。报告（含 201 格坐标、`EmptyLevelChunk` 证据、复现脚本）已整理：`reports/photomancy-place-placeholder-chunk.md`。
- `repaired` 明显小于 `missing` 时说明还有别的成因，请把那一行日志发到 issue。

**下次怎么办**：看到 `setBlockEntity` 说"这里是空气"，先别信日志的条数 —— 它只覆盖带方块实体的方块；要真数损失，得导出后逐格 diff。以及：**判断"写入有没有发生"不能只看自己的钩子计数**，得去读世界状态（`void_air` 和 `air` 的差别就是这次的突破口）。

## 2.1.0 — 2026-11-17

**修复：蓝图打印把 Coasters 的方块当成免费的（材料清单里只有草方块这类原版方块）**

现象：蓝图里存了草方块和过山车车厢，胶卷/材料清单只认得草方块；打印出来两样都在——
车厢那部分**零物品消耗**。

原因：Photomancy 的默认成本规则只有一行——收 `state.getBlock().asItem()`，`asItem()` 是空气就不收：

```java
Item item = payload.state().getBlock().asItem();
return item == Items.AIR ? CostQuote.empty(...) : CostQuote.of(new CostLine(...));
```

而 Coasters 有三个方块**没有注册 BlockItem**（物品是普通 `Item`，因为方块不是从背包放的，
是走子层级生成的）：

| 方块 | 对应物品 | 玩家实际消耗 |
|---|---|---|
| `simulatedcoasters:coaster_cart` | `coaster_cart` | `CoasterCartItem` 放置后 `shrink(1)`（两条路径各一处） |
| `simulatedcoasters:rivet` | `rivet` | `RivetPlacePayload` 放完 `shrink(1)` |
| `simulatedcoasters:red_balloon` | `red_balloon` | `BalloonAttachInteraction` 吸附后 `shrink(1)` |

于是 `asItem()` 全是 AIR → 默认规则返回空报价 → 打印免费。

修法：用 Photomancy 公开的成本规则注册表 `BlueprintBlockCostRules.register(block, rule)` 给三个方块
各注册一条「1 方块 = 1 该物品」的规则。这和 mod 自己的消耗逻辑（一车一物品、一铆钉一物品、
一气球一物品）是一一对应的。Photomancy 自带的 Create 机械与 Copycats 兼容也是走这个入口。

- `coaster_anchorpoint` 不用管：它是唯一用 `registerSimpleBlockItem` 注册的，默认规则本来就收钱。
- **未覆盖**：`coaster_track_material`。它没有物品，而 1 个 `coaster_track` 放的是一整段贝塞尔曲线
  （几十个轨道方块）——按方块收会几十倍超收。这一项需要在「一段轨道」这个粒度上做，属上游的活。
- 启动日志新增一行：`blueprint cost rules armed: 3/3 Coasters blocks now charge their item`。

**注意**：2.1.0 仍然**不含** 1.4.1 那条连杆守卫（见 2.0.0 的警告）。存档里有连成列车的过山车时，
先备份再装。

## 2.0.0 — 2026-11-17

**范围收缩：删掉全部性能补丁与探针，只留 Sable Photomancy 蓝图兼容**

原因：`simulatedcoasters 0.1.6` 把 1.x 补丁要修的东西自己修了，而且把剩下几条的注入点搬走或改名
（详见 tag `archive-1.5.2-perf` 里的 `UPSTREAM-0.1.6.md`）。逐条核对后，性能部分已经没有保留价值：

| 删除 | 依据 |
|---|---|
| `PlotScanSectionFilterMixin` | 0.1.6 的 `skipSection` 里是同一行 `maybeHas` |
| `BearingScanThrottleMixin` / `RivetScanThrottleMixin` | 上游换成事件驱动成员索引 + `beginEnforcePass`，节流失去目标 |
| `TrackSnapBroadcastGuardMixin` / `TrainLinkRestoreThrottleMixin` | 注入点被搬进 lambda / 改名 private，硬留会崩 |
| `CurveHandleLengthCacheMixin` | 仍有效，但属于「性能补丁」，不在本次保留范围内 |
| `CartLinkGuardMixin` + `CartMembership` + `MembershipRepair` | 随性能部分一起去掉 |
| 热点探针（`probe/`、探针 mixin config） | 同上 |
| `FixConfig` / `FixLimits` / `PatchSelfCheck` / 两个 mixin config | 不再需要 |

保留：`blueprint/` 全部（`RivetHostBlueprintMapper`、`BalloonHostBlueprintMapper`、
`CoasterBlueprintEvent`、`BlueprintRefs`、`CartTrainLinks`、`BlueprintCompat`），代码一行未改。

其他变化：

- 显示名 `Coaster Perf Fix (Unofficial)` → **`Coaster Blueprint Compat (Unofficial)`**，
  modId 仍是 `simcoastersfix`（升级路径不断）。
- 编译期依赖去掉 `libs/create.jar`。
- 启动日志从「9 个注入点自检」变成一行 `blueprint compat armed` + 一行 Photomancy 检测结果。
- 旧配置 `config/simcoastersfix-server.toml` 由新版本忽略，可以删掉。

**性能补丁去哪了**：最后一个带全部优化的版本是 1.5.2，代码在 git tag `archive-1.5.2-perf`，
产物是 `dist/simcoastersfix-1.5.2.jar`。

**⚠ 升级警告**：2.0.0 不含 1.4.1 的「删除前重新裁决」守卫，而 0.1.6 的成员索引仍有误删连杆的
回归（载入窗口里归类过的 sublevel 会被永久记成「不是车」）。存档里有连成列车的过山车时，
装 2.0.0 前先备份，或继续用 1.5.2。需要的话可以把守卫单独作为 2.0.1 的正确性补丁加回来。

## 1.5.2 — 2026-11-17

**让上游的成员索引自己纠正：守卫每拒绝一次，就把那条错误的负缓存踢掉**

1.5.1 的会话日志把病根钉死了：`cart.reclassify` 在载入窗口是 **1008 次**，之后 11 个 30 秒窗口
**全是 0**；同一时间守卫拒绝了 **22,560 次**（130 节车厢，上游 `unlinked` 0 次）。也就是说
0.1.6 把这 130 个 sublevel 在「plot 还没填充」时归类了一次，此后**永远不会再看第二眼**——
`readyChunkCount` 不变化就不重判，而 chunk 对象早在、内容晚到时它确实不变。

改动：守卫拒绝时顺手调一次 `CoasterCartServerMembership.invalidate(sub)`，让上游下一问重扫 plot
（现已有内容，答案自然是 cart）。一次修复之后这节车就不再被误判，重复的 54 次/秒拒绝随之消失。

- 该方法是 **package-private**，类本身也是 package-private，没有公开入口 → 用反射（一次解析、
  缓存 `MethodHandle`）；任何失败都只 WARN 一次并降级为「不修索引，守卫照旧」。
- 新增开关 `repairCartMembershipIndex`（默认 true）。
- 预期效果（下一次启动可以验）：日志出现 `repaired the cart membership index for N sub-levels`，
  `kept cart-train couplings` 从每分钟 ~215 行掉到 ~0，`plot.scanBearingCells` 从 78 µs/tick 掉到接近 0。
- 本版**没有实机验证过反射通道**——按本项目的规矩，编译通过不算数。如果上游的模块不给访问，
  会留一行 `#invalidate is not reachable` 的 WARN，守卫仍然生效。

## 1.5.1 — 2026-11-17

**实测把 1.5.0 的一个默认值打回去了：`protectCartLinks` 必须默认开**

现象：1.5.0 装进真实存档（0.1.6 + 1.5.0，探针开）。30 秒窗口里守卫生效 **10,240 次**、涉及
**78 节车厢**、上游 `unlinked` **0 次**——也就是说，如果没有这个守卫，这 78 条耦合会在载入时被上游
从车厢 NBT 里删掉。

原因：0.1.6 的 `CoasterCartServerMembership` 仍会把「plot 还没填充完时被归类过的 sublevel」
**永久缓存成「不是车」**。它唯一的重判条件是 `readyChunkCount` 变化，而 chunk 对象早就在、
只是内容还没写进去时，这个计数不会变：

```java
int readyChunks = CoasterCartPlotScan.readyPlotChunkCount(sub.getPlot());
boolean needsClassify = existing == null || dirtySnapshot.contains(id)
        || !existing.isCart() && existing.readyChunkCount != readyChunks;
```

`entryFor` 的缓存命中分支对非 cart 条目直接返回（`!cached.isCart() || …` 短路），所以这些条目
再也不会被重扫；`isDefunctLinkPartner` 读的正是它 → 每 100 tick 的恢复 pass 都会判定「这节车没了」。

改动：

- `protectCartLinks` 默认 **true**（代码、配置注释、README 同步改），并把上面的证据写进注释。
- 没有别的行为改动；1.5.0 的删除与默认值其余部分不变。

**探针实测**（同一会话，600 tick/窗口，取三个稳定窗口）：

| 计数项 | 调用/窗口 | 总耗时 | 归一化 |
|---|---|---|---|
| `cart.readyChunks` | 575,000 | 130 ms | **958 次/tick，0.22 ms/tick** |
| `cart.reconcile` | 5,424 | 115 ms | 9 次/tick，0.20 ms/tick |
| `cart.membershipScan` | 2,325 | 2.2 ms | 3.7 µs/tick |
| `cart.reclassify` | 9 | ~0 | 稳态几乎不重判（这正是上文的病根） |
| `rivet.reconcileLive` | 2,400 | 34.5 ms | 4 次/tick，58 µs/tick |
| `rivet.rivetsAndDirty` | 2,400 | 6.8 ms | 4 次/tick，11 µs/tick |
| `plot.scanBearingCells` | 1,599 | 47.0 ms | 2.7 次/tick，78 µs/tick（**其中大部分是守卫的重新扫描**） |
| `alloc` | — | — | 服务端线程 ~210 MB/s |
| 合计 | — | — | **~560 µs/tick** |

结论：0.1.6 的成员索引 pass 约 **0.2 ms/tick**，而它几乎全部花在 `readyPlotChunkCount` 上
（每个存活 sublevel 每 tick 一次，≈958 次）。这是**唯一被证实的 0.1.6 新增热点**，但只有约
0.4% 的 tick 预算，且没有安全的局部修法（那个计数正是「plot 何时填充完」的检测手段，属上游的活）。
守卫的重新扫描约 78 µs/tick —— 代价来自上游那个错误的负缓存，不是我们自找的。

原始日志（1.3 MB，含全部探针窗口与 652 行守卫记录）留在 `work/client-probe-20261117.log`。

## 1.5.0 — 2026-11-17

**适配 simulatedcoasters 0.1.6：删掉 3 个已失去目标的混入，新增默认开启的热点探针**

现象：1.4.1 装 0.1.6，服务端**构造期**崩：

```
ModLoadingException: Create: Coasters Simulated has failed to load correctly
→ MixinTransformerError → InjectionError: simc$trackCartMembership … (0/1) succeeded. Scanned 0 target(s)
```

原因：0.1.6 把三个注入点改了结构——`CoasterCartTrackSnap.onPrePhysicsTick` 的循环体搬进
`lambda$onPrePhysicsTick$0`；`maybeRestorePersistedLinks` 改名 private `maybeSafetyRestorePersistedLinks`；
`LevelChunkSection.hasOnlyAir()` 被抽进 `skipSection`（**顺便，上游自己加了 `maybeHas` 调色板剪枝**，
就是 1.1.0 那一条）。注入按方法名和指令找，找不到就按 `defaultRequire: 1` 致命。

- **删除** `PlotScanSectionFilterMixin`、`TrackSnapBroadcastGuardMixin`、`TrainLinkRestoreThrottleMixin`
  以及只被前者引用的 `util/SectionFilter`；自检 12 → **9** 项。
- **保留**（对着 0.1.6 的字节码逐条核过，目标类字节未变）：贝塞尔手柄长度缓存、气球恢复节流、
  气球 tick 内三处解析去重 + 7 处管线测速去重、连杆 tick 内 `findCartById` 去重。
- **默认值改为「跟上游」**：`bearingScanTtl*` 1（同 tick 去重）、`rivet*` 1（交给 `beginEnforcePass`）、
  `protectCartLinks` false（TTL=1 时没有滞后可兜）。删掉 `trainLinkRestoreIntervalTicks`。
- **依赖钉死** `simulatedcoasters [0.1.6,0.1.7)`：版本不符时 FML 在加载期拒绝，而不是构造期崩。

**新增：热点探针（默认开启）**

0.1.6 把「每 tick 全量扫」换成事件索引，剩下的成本变成**每 tick 一次 O(sublevel) 的遍历**，
单次很便宜、采样里看不见。探针把这几段单独计时/计数，每 600 tick 打一行：

```
[simcoastersfix] probes[600t]: cart.reconcile 3618x 4.54ms 1.25us/call | rivet.reconcileLive 1800x 1.61ms 0.89us/call | rivet.rivetsAndDirty 1800x 0.46ms 0.25us/call | alloc 0.43MB/s || total 11.01us/tick
```

- 计数项：`cart.readyChunks`（每 tick 每 sublevel 无条件遍历 plot chunks，**最可疑**）、
  `cart.reconcile`、`cart.membershipScan`（缓存命中仍做一次 `getBlockState`）、`cart.reclassify`、
  `rivet.reconcileLive`、`rivet.rivetsAndDirty`、`plot.scanBearingCells`，外加服务端线程分配率 `alloc`。
- 开销：每次调用两次 `nanoTime`（~40 ns），只读；计数是裸 `long`，丢精度不丢正确性。
- 探针混入在**独立 config**（`simcoastersfix-probes.mixins.json`，`required=false` +
  `defaultRequire=0`）：将来上游再动这些方法，丢的是数据不是游戏。启动时 `probe self-check` 报 3/3。
- 开关 `hotspotProbes` / `hotspotProbeIntervalTicks`。

**实测**（dev 专服，NeoForge 21.1.249 / Java 21，simulatedcoasters 0.1.6 + 1.5.0 源码）：

- `self-check OK: 9/9 mixin targets active`、`probe self-check OK: 3/3`；
- 空载世界稳态 **10–11 µs/tick**，几乎全是 `reconcile` 的 early-out 与两次 rivet pass；
  `cart.readyChunks` 为 0 是因为该测试世界没有 sublevel（有 sublevel 才会长起来）。

**升级须知**：旧配置里的 `trainLinkRestoreIntervalTicks` 已无代码，NeoForge 会忽略；
`bearingScanTtl*` / `rivet*` 的旧值会保留在文件里，等于只用了半个 1.5.0——**建议删掉配置文件重建**。

原始对照材料（反编译产物、19 文件 diff、javap、三组起服日志）见 [`UPSTREAM-0.1.6.md`](UPSTREAM-0.1.6.md)。

## 1.4.1 — 2026-11-16

**修复：重新加载世界后，车厢之间的连杆全部断掉（而且不可恢复）**

现象：载入存档（或让车厢重新加载）几秒后，整条列车之间的耦合全断，日志刷出一大串
`[cart-link] unlinked`；`coupler snapped`（张力拉断）**一条都没有**——所以不是物理扯断的。
更糟的是它**永久**：`unlinkPair` 会调 `CoasterCartTrainLinkData.removeLink`，把记录从车厢的
持久化 NBT 里删掉，重启世界也回不来。

证据（本机日志全量统计，`unlinked` / `restored link`）：

| 版本 | 会话 | unlinked | restored |
|---|---|---|---|
| 1.0.1 | 11-15 ×6 | 0 | 0 |
| **1.2.0** | 11-16 19:43 | **11**（载入后 6 秒一整批） | 0 |
| 1.3.0 | 11-16 | 108（伴随 33 次 restore、78 次 link、6 次 snap） | 33 |
| 1.4.0 | 11-16 白天 | 1（同场新建了 80 条连杆） | 0 |
| 1.4.0 | 11-16 夜间 | **78（载入后一整批）** | **0** |

`unlinked` 只在 **1.1.0 引入扫描 TTL 之后**出现，且都发生在**载入后几秒**——1.0.1 的六个会话一条都没有。

原因链：`restorePersistedLinks` → `pruneDefunctLinks` / `tryRestoreLink` →
`isDefunctLinkPartner(level, partnerId)` → `CoasterCartResolve.isCartSubLevel` →
`CoasterCartBearingTickCache.scan`。上游这个扫描是**每 tick 重算**的；1.1.0 把它换成了
40 / 100 刻的 TTL。载入世界时 sublevel 已经在容器里、它的 plot 还没填充完（或者缓存里正躺着
上一次那条「无轴承格」），于是 `isCartSubLevel` 对**一节还没加载好的车**返回 `false`。
上游把这个 `false` 读成「这节车已经没了」，直接删耦合。

上游自己不出事，是因为它下一 tick 就重扫、答案立刻恢复；我们把「看不见」延长成了 2–5 秒，
足够它在恢复之前把整列车删干净。

修法（两条互相独立，任一单独生效都能挡住这条链）：

1. **删除前重新裁决**（新增 `CartLinkGuardMixin`，挂 `isDefunctLinkPartner` 与 `onCartRemoved`）：
   真要删之前用 `CartMembership` **绕开缓存**重算一次成员资格。判定为 `CART`（新扫描找到了轴承格）
   或 `UNKNOWN`（plot 里连一个方块都没有 = 数据还没到）→ **拒绝删除**；只有「plot 有内容、确实
   没有轴承格」才按上游语义删。真正被移除的车（`isRemoved()`）不受影响，照旧清理。
2. **负结果不进缓存**：`plot` 连一个 chunk 对象都拿不出来时，「没有轴承格」这个答案不缓存——
   那正是载入窗口，缓存它等于把「还没加载」写成「不存在」。

开销：guard 只活在本**来就要删东西**的路径上；`onCartRemoved` 侧先用一个 NBT key 短路，
没有连杆的 sublevel 一次扫描都不做，热路径零影响。开关 `protectCartLinks`（默认开）。

- 拒绝删除时留日志，方便下次考古：
  `[simcoastersfix] kept cart-train couplings of <uuid> at isDefunctLinkPartner (...)`。
- 自检 11 → **12** 项。

**已知边界**

- **上一次载入时已经被删掉的连杆找不回来**：NBT 里已经没有了。用世界备份回滚到出问题之前，
  或者手动重连一次；修好的版本只保证「不会再删」。
- `pruneInvalidLinksForCoupling`（玩家主动连车时那条支路）仍读缓存，本次未加 guard——它只在
  玩家插车厢时跑，触发面比每 tick 的 `restorePersistedLinks` 小得多。

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
