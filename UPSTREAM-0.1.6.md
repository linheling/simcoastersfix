# 上游 0.1.6 对照：1.4.1 的 10 个混入里有 3 个装不上

> 2026-11-17。反编译 `simulatedcoasters-0.1.6.jar`，与 `libs/simulatedcoasters-0.1.5.jar` 逐类对比，
> 再对着字节码核对本工程 10 个混入的注入点。**结论先给：1.4.1 装 0.1.6 会崩，且上游把 1.0.0 / 1.1.0
> 的优化自己做了一遍（做得比我们更对）。**
>
> **状态（2026-11-17 下午）：已按 §7 移植为 1.5.0** —— 3 个失效混入删除、默认值改成跟上游、
> 依赖钉 `[0.1.6,0.1.7)`、新增 §9 那批热点的探针（默认开）。实机验证：`self-check 9/9` +
> `probe self-check 3/3`。§3–§9 保留为当时的原始判断，没有回改。

## 0. 三行总结

- **现象**：1.4.1 + 0.1.6，服务端 `ModLoadingException: Create: Coasters Simulated has failed to load correctly`
  → `MixinTransformerError` → `InjectionError: simc$trackCartMembership ... (0/1) succeeded. Scanned 0 target(s)`。
- **原因**：0.1.6 把三个注入点**改了结构**——搬进 lambda、改名成 private、抽进 helper；注入是按方法名/指令找的，
  找不到就按 `defaultRequire: 1` 直接致命。同时 0.1.6 自己内建了 section 调色板剪枝、事件驱动成员索引、
  rivet 每 tick 单次 pass、100 拍安全恢复。
- **下次怎么办**：把「混入 target 的方法名 + 指令序列」当上游契约，上游每次发版先跑 §10 的核对脚本，
  再决定发新版还是钉版本上限（`versionRange="[0.1.5,0.1.6)"`，让 FML 拒绝加载而不是启动崩）。

## 1. 材料与方法

| 项 | 值 |
|---|---|
| 旧基线 | `libs/simulatedcoasters-0.1.5.jar`，1,892,119 B，`sha256 F80B4C3C…B2049` |
| 新上游 | `D:\C\MC\mods\simulatedcoasters-0.1.6.jar`，1,907,773 B，`sha256 197C667E…09CC5` |
| 反编译 | Vineflower 1.10.1，`--thread-count=8 -e=create.jar -e=sable-2.0.6.jar …`，两边**同一组参数** |
| 产物 | `work/sc-015-src`（335 java / 40,582 行）、`work/sc-016-src`（337 java / 41,096 行）、`work/diff-015-016.diff`、`work/javap-016/` |
| 字节码核对 | `javap -p -c -constants -classpath simulatedcoasters-0.1.6.jar <class>` |
| 运行验证 | 开发服务端 `.\gradlew.bat runServer`（NeoForge 21.1.249 / Java 21），三组对照见 §4 |

class 级差异：**475 → 481 个 class，+6 新增，0 删除，18 个内容变化**。
新增的两个顶层类是重点：`rivet/RivetPlotMembership`、`track/cart/CoasterCartServerMembership`
（外加 4 个内部 record/状态类）。

## 2. 0.1.6 的设计转向

两处「每 tick 全量扫描」被换成了**事件驱动的成员索引**——正是 `OPTIMIZATION.md` §5 方案 D 建议提上游的那条：

| 上游类 | 0.1.5 | 0.1.6 |
|---|---|---|
| `CoasterCartBearingTickCache.scan` | 每 tick 重扫整个 plot（`getBlockState` 三重循环） | 委托 `CoasterCartServerMembership.scan`：按 UUID 查表，`representative` 位置再做一次 `getBlockState` 校验；**place / remove / 搬动 / 加载 / 移除都会 invalidate** |
| `CoasterCartIdIndex` | 每 tick 遍历容器里全部 sublevel 判 cart | `CoasterCartServerMembership.cartMap(level)` |
| `CoasterCartTrackSnap.onPrePhysicsTick` | 遍历**全部** sublevel（非 cart 也吃一遍） | `CoasterCartServerMembership.forEachCart`，只遍历 cart |
| `RivetDisassemblyEnforcer.onPrePhysicsTick` | 遍历全部 sublevel 跑 `enforceSingleRivet` + `popIfSelfHosted` | `RivetPlotMembership.beginEnforcePass`（同 gameTime 只跑一次）+ `rivetsAndDirty`（只跑已知 rivet 与 dirty） |
| `CoasterCartTrainLinkConstraint` 恢复 | `maybeRestorePersistedLinks`，守卫写成 `last != gameTime` = 每 tick 全量 | 改名 `maybeSafetyRestorePersistedLinks`，`gameTime % 100 == 0` 才跑 |
| `CoasterCartPlotScan` 剪枝 | `section != null && !section.hasOnlyAir()` | `skipSection()` = `null ‖ hasOnlyAir ‖ !maybeHas(matches)` ← **调色板级剪枝** |

这套改动的效果是**上游自己把「每 tick 全量」这个病根治了**，而不是加节流。这一点决定了我们哪些补丁还值钱。

## 3. 10 个混入逐个对照

| # | 混入 | 引入 | 注入点 | 0.1.6 状态 | 结论 |
|---|---|---|---|---|---|
| 1 | `BalloonRestoreThrottleMixin` | 1.2.0 | `BalloonTetherController.maybeRestoreTethers` HEAD | 该类**字节未变** | ✅ 生效，仍然值钱 |
| 2 | `BalloonTickMemoMixin` | 1.2.0 / 1.3.0 | `onPrePhysicsTick` 三个 WrapOperation + 5 处管线测速 | 字节未变 | ✅ 生效 |
| 3 | `BearingScanThrottleMixin` | 1.0.0 | `CoasterCartBearingTickCache.scan/invalidate/remove/clearDimension` | 方法都在，但 `scan` 已变成「委托精确缓存」 | ⚠️ 装得上，**语义倒挂**（见 §6.2） |
| 4 | `CartLinkGuardMixin` | 1.4.1 | `isDefunctLinkPartner` / `onCartRemoved` HEAD | 两个方法都在 | ⚠️ 装得上，兜底价值下降（见 §6.4） |
| 5 | `CurveHandleLengthCacheMixin` | 1.4.0 | `CoasterPeerCurveHandleLengths.getLengthAtEndpoint` @WrapMethod + `revision()` | 该类字节未变 | ✅ 生效，**仍是最大收益** |
| 6 | `PlotScanSectionFilterMixin` | 1.1.0 | `scanBearingCells` / `forEachBearingWorldCell` 内的 `LevelChunkSection.hasOnlyAir()` | `hasOnlyAir` 被抽进 `skipSection`，**且上游自己加了 `maybeHas`** | ❌ **失效**（§4.3） |
| 7 | `RivetScanThrottleMixin` | 1.0.0 | `RivetDisassemblyEnforcer.onPrePhysicsTick` 内的 `enforceSingleRivet` / `popIfSelfHosted` | 调用点仍在（偏移 71 / 76） | ⚠️ 装得上，已被上游超越（见 §6.3） |
| 8 | `TrackSnapBroadcastGuardMixin` | 1.0.0 | `onPrePhysicsTick` 内的 `representativeBearingPlotPos` / `onCartRemoved` | **调用点搬进 `lambda$onPrePhysicsTick$0`** | ❌ **失效，实测首崩**（§4.1） |
| 9 | `TrainLinkRestoreThrottleMixin` | 1.0.0 | `CoasterCartTrainLinkConstraint.maybeRestorePersistedLinks` @Inject | **方法改名并转 private**：`maybeSafetyRestorePersistedLinks` | ❌ **失效**（§4.2） |
| 10 | `TrainLinkTickMemoMixin` | 1.2.0 | `tick` 内 `CoasterCartResolve.findCartById` @WrapOperation | `tick` 内偏移 74 / 84 仍是两处 | ✅ 生效 |

## 4. 三个失效，逐条证据

### 4.1 `TrackSnapBroadcastGuardMixin` —— 调用点搬进了 lambda

0.1.6 把整个 sublevel 循环改成 `CoasterCartServerMembership.forEachCart(sl, sub -> { ... })`，
lambda 体编译成同类的 `private static void lambda$onPrePhysicsTick$0(…8 个捕获参数, ServerSubLevel)`。
`javap` 里 `representativeBearingPlotPos` / `onCartRemoved` 的 `invokestatic` **只出现在这个 lambda 里**，
`onPrePhysicsTick` 方法体内一次都没有 → `@At(value="INVOKE", target=…)` 扫描到 0 个目标。

实测（原样 1.4.1 + 0.1.6，`work/runserver-016.log`）：

```
Caused by: org.spongepowered.asm.mixin.injection.throwables.InjectionError:
  Critical injection failure: Callback method simc$trackCartMembership(
    Ldev/ryanhcode/sable/sublevel/SubLevel;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)
    Lnet/minecraft/core/BlockPos; in simcoastersfix.mixins.json:TrackSnapBroadcastGuardMixin
    from mod simcoastersfix failed injection check, (0/1) succeeded. Scanned 0 target(s).
→ ModLoadingException: Create: Coasters Simulated (simulatedcoasters) has failed to load correctly
```

崩点在**mod 构造期**：`SimulatedCoasters` 构造函数里 `NeoForge.EVENT_BUS.addListener(CoasterCartTrackSnap::onPrePhysicsTick)`
会强制加载这个类，所以不是「玩到车才崩」，是**开服就崩**。

### 4.2 `TrainLinkRestoreThrottleMixin` —— 方法被改名 + 上游自带 100 拍门

`maybeRestorePersistedLinks(ServerLevel)` → `private static void maybeSafetyRestorePersistedLinks(ServerLevel)`，
内部 `if (gameTime % 100L == 0L)`；`tick` 第一行也随之改成调用新名字。
`@Inject(method="maybeRestorePersistedLinks")` 找不到目标（实测第二组对照，`work/runserver-016-B.log`）：

```
Critical injection failure: @Inject annotation on simc$throttleRestore could not find any targets
matching 'maybeRestorePersistedLinks' in
dev/silvergold/simulatedcoasters/track/cart/CoasterCartTrainLinkConstraint
```

### 4.3 `PlotScanSectionFilterMixin` —— `hasOnlyAir` 被抽走，且上游自己做了同一件事

0.1.5：`if (section != null && !section.hasOnlyAir())` 直接写在两个扫描方法里。
0.1.6：两个方法都改成 `if (!skipSection(section, matches))`，`skipSection` 里
`return section == null || section.hasOnlyAir() || !section.maybeHas(matches);`

`javap` 证实 `hasOnlyAir` 在整个类里只出现在 `skipSection`（偏移 5），
`scanBearingCells` / `forEachBearingWorldCell` 里 0 次 → 两个 WrapOperation 都没目标。

这个**不会崩启动**，但比崩启动阴：`PatchSelfCheck` 在 `ServerStartedEvent` 里 `Class.forName` 到它，
`catch (Throwable)` 把 `MixinTransformerError` 吞成一行日志，所以服务端照常起来——
**类从此是坏的**，第一次真用到（放车、连车、`CoasterCartDuplicateEnforcer` 扫描）才抛
`NoClassDefFoundError`，而且现场离「注入失败」很远，很难回溯。

实测第三组对照（去掉 #8 #9 两个混入后能起服）里，自检把它抓了出来：

```
[simcoastersfix] patch applied to CoasterCartBearingTickCache#simc$scans
[simcoastersfix] patch NOT applied to dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan#simc$sectionsSkipped (MixinTransformerError)
[simcoastersfix] patch applied to RivetDisassemblyEnforcer#simc$lastRivetCheck
[simcoastersfix] patch applied to RivetDisassemblyEnforcer#simc$lastSelfHostCheck
[simcoastersfix] patch NOT applied to dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrackSnap#simc$teardownDone (NoSuchFieldException)
[simcoastersfix] patch NOT applied to dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint#simc$lastRestore (NoSuchFieldException)
…
[simcoastersfix] self-check FAILED: only 9/12 mixin targets active
```

> 顺带一条副产品：`PatchSelfCheck` 用 `catch (Throwable)` 兜住了 `MixinTransformerError`，
> 所以「注入失败」在自检里表现为 `patch NOT applied … (MixinTransformerError)` 而不是崩溃——**自检有用**，
> 但只有在其它混入不先把服务端炸掉的时候才看得到。

## 5. 上游已经「抄」过去的四条

| 我们的 | 上游 0.1.6 的等价实现 | 谁更好 |
|---|---|---|
| 1.1.0 section 调色板剪枝（`maybeHas`） | `CoasterCartPlotScan.skipSection` 里同一句 `maybeHas(state -> state.is(block))` | 一样；上游写在源码里，不用 mixin |
| 1.0.0 TTL 缓存想解决的「每 tick 全量扫」 | `CoasterCartServerMembership`：事件驱动 + 精确失效 + **`readyPlotChunkCount` 处理加载窗口** | **上游更对**：TTL 是「猜多久之后答案才变」，它是「答案什么时候真的变」 |
| 1.0.0 rivet 30/20 拍节流 | `RivetPlotMembership.beginEnforcePass`（每 tick 单次）+ `rivetsAndDirty`（只扫 rivet 与 dirty） | **上游更对**：我们只是把「全量扫」摊薄，它把「全量」删了 |
| 1.0.0 连杆恢复 60 拍节流 | `gameTime % 100 == 0` + `forEachCart` | 上游更狠（100 拍）且只遍历 cart |

这就是为什么 §6 里只剩四个混入还值钱：**病根由上游治掉了，节流型补丁失去目标。**

## 6. 还装得上、但已经不该默认开的三个

### 6.1 `BearingScanThrottleMixin`（1.0.0，TTL 40/100 拍）

`scan` 现在只是一次 synchronized `byId` 查表 + 一次 `level.getBlockState(representative)` 校验。
再在外面套 40/100 拍 TTL：

- **收益**：省掉每 cart 每 tick 的那一次 `getBlockState`（以及一次加锁）。有几十辆车时才有量。
- **代价**：把「看不见」重新拉长到 2–5 秒。1.4.1 的 `CartLinkGuardMixin` 正是为兜这个副作用而写的；
  上游已经用「chunk 就绪数变化就重新分类」正面解决了加载窗口，我们的 TTL 反而在它上面又盖了一层不精确。
- **建议**：默认 `false`，保留开关做 A/B；真要保留，就把 `bearingScanTtlTicks*` 压到 1–2 拍（等于只做同 tick 去重）。

### 6.2 `RivetScanThrottleMixin`（1.0.0，30/20 拍）

调用点还在，能装。但 0.1.6 的 `enforceSingleRivet` 里已经有
`if (originIsRivet && !RivetPlotMembership.isDirty(...)) return false;`——不该扫的本来就不扫了。
我们再按 30 拍拦一道，实际效果是**把 dirty 标记的处理最多推迟 30 拍（1.5 s）**，而且被拦掉的那次不会走
`noteAfterEnforce` 记账。收益趋零、风险是「拆重复铆钉慢半秒」。
**建议**：默认 `false`。

### 6.3 `CartLinkGuardMixin`（1.4.1，删除前重审）

`isDefunctLinkPartner` / `onCartRemoved` 都还在，能装，且只走「本来就要删东西」的路径，热路径零成本。
但它的存在理由（TTL 的滞后 + 加载窗口看不见车）在 0.1.6 已经由 membership 精确化消除了；
只有 §6.1 的 TTL 还开着时才需要它。
**建议**：跟 TTL 开关绑定——TTL 关就一起关，别留死代码在热路径上。

### 6.4 仍然建议保留的四个

| 混入 | 为什么还值钱 | 证据 |
|---|---|---|
| `CurveHandleLengthCacheMixin` | `CoasterPeerCurveHandleLengths` 0.1.6 **字节未变**，§14 里 21.8–30.9% busy 的热点还在；`revision()` 精确失效机制也没变 | class 差异表里没有这个类；`javap` 里 `revision()`/`getLengthAtEndpoint` 签名一致 |
| `BalloonRestoreThrottleMixin` | `BalloonTetherController` 字节未变，`last != gameTime` 的写法错误原样保留 | 同上；`javap` 里 `maybeRestoreTethers` 仍被 `onPrePhysicsTick` 第 11 条指令调用 |
| `BalloonTickMemoMixin` | 同上，三个 memo 点位（`findBalloonById` 偏移 110/306、`resolveHostSubLevel` 偏移 141/365、5 处管线测速）全部在 | `javap` 逐条核对 |
| `TrainLinkTickMemoMixin` | `tick` 仍然每链接两次 `findCartById`（偏移 74 / 84） | `javap` 逐条核对 |

## 7. 建议的 1.5.0 移植清单

```
src/main/resources/simcoastersfix.mixins.json
  -  "PlotScanSectionFilterMixin"       # 上游已内建 maybeHas
  -  "TrackSnapBroadcastGuardMixin"     # 调用点进 lambda；且上游只遍历 cart 了
  -  "TrainLinkRestoreThrottleMixin"    # 方法改名，上游 100 拍门

src/main/java/.../mixin/
  -  PlotScanSectionFilterMixin.java    # 连同 util/SectionFilter.java（若无人引用）
  -  TrackSnapBroadcastGuardMixin.java
  -  TrainLinkRestoreThrottleMixin.java

src/main/java/.../PatchSelfCheck.java
  -  {"…CoasterCartPlotScan", "simc$sectionsSkipped"}
  -  {"…CoasterCartTrackSnap", "simc$teardownDone"}
  -  {"…CoasterCartTrainLinkConstraint", "simc$lastRestore"}   # 12 → 9 项

src/main/java/.../FixLimits.java + FixConfig.java
  -  bearingTtl* 默认 1（同 tick 去重）或整体默认关
  -  rivetIntervalTicks / selfHostIntervalTicks 默认关
  -  protectCartLinks 默认关（与 TTL 联动）
  -  trainLinkRestoreIntervalTicks 无主了 → 删
```

**不想移植也行**，但必须把依赖上限钉住，让 FML 在加载期就拒绝，而不是构造期崩：

```toml
[[dependencies.${mod_id}]]
modId="simulatedcoasters"
versionRange="[0.1.5,0.1.6)"   # 或精确到 0.1.5 系列
```

现在的 `[0.1.5,)` 会放 0.1.6 进来然后崩，这是最差的一种失败方式。

## 8. 编译 ≠ 能跑（这次又验证了一遍）

把 `build.gradle` 的 compileOnly 换成 `libs/simulatedcoasters-0.1.6.jar`，`.\gradlew.bat compileJava`：

```
> Task :compileJava
BUILD SUCCESSFUL in 5s
```

**能编译**，因为：

- 注入目标全是注解里的**字符串**，编译期不校验；
- Java 代码直接引用的 API 一个都没删（`scanBearingCells`、`BearingScanResult`、`findCartById`、
  `getLengthAtEndpoint`、`revision`、`scan` 都还在），改名的 `maybeRestorePersistedLinks` 只出现在注解字符串里。

所以「编译过了」对混入工程几乎**没有意义**，唯一的验证手段是加载期自检 + 起服。

## 9. 0.1.6 新出现的热点（仅假设，**未采样**）

按 §14 的教训，读代码得到的因果必须用采样验一次，以下三条**只是候选**，别当结论：

1. `CoasterCartServerMembership.entryFor` 在命中缓存时仍做一次 `level.getBlockState(representative).is(bearingBlock)`
   —— 每 cart 每 tick 一次世界读，几十辆车时值得量一下。
2. `RivetPlotMembership.beginEnforcePass → reconcileLive` 每 tick 遍历容器里**全部** sublevel 认领未知项
   （`RivetHostKey.isRivetSubLevel` 逐条判），这是新的 O(sublevels)/tick。
3. 两处 `synchronized (BY_DIM)` 包着 `HashMap`（不是 `ConcurrentHashMap`），且 `beginEnforcePass` 每拍
   `new DimensionState(...)` 换壳——分配与锁的都在，量级应该不大，但和「每 tick」乘起来才看得到。

## 10. 复现

```powershell
cd D:\C\MC\mods

# 1) 反编译（两边同参数）
$jdk = "C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot\bin\java.exe"
& $jdk -Xmx3G -jar tools\vf.jar --thread-count=8 --indent-string="    " `
    -e=projects\simcoastersfix\libs\create.jar -e=projects\simcoastersfix\libs\sable-2.0.6.jar `
    simulatedcoasters-0.1.6.jar projects\simcoastersfix\work\sc-016-src

# 2) 逐类对比
cd projects\simcoastersfix
git --no-pager diff --no-index --text work\sc-015-src\dev work\sc-016-src\dev

# 3) 核对注入点（先看方法还在不在，再看 INVOKE 在不在那个方法里）
& "C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot\bin\javap.exe" -p -c -constants `
    -classpath D:\C\MC\mods\simulatedcoasters-0.1.6.jar `
    dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrackSnap | Select-String 'onPrePhysicsTick|representativeBearingPlotPos'

# 4) 起服验证（把 0.1.6 放进 run\mods，开发类路径自带本 mod 时先挪走 jar）
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
.\gradlew.bat runServer --console=plain
```

> `run/world` 与 `run/mods` 在测试前后各备份了一份到 `work/world-backup`、`work/runmods-backup`，
> 测完已还原；`build.gradle` / `mixins.json` / `src` 工作区现在是干净状态（`git status` 为空）。

## 附：本次留在 `work/` 的东西（可删）

| 路径 | 内容 |
|---|---|
| `work/sc-015-src`、`work/sc-016-src` | 两边反编译源码（同参数，供 diff） |
| `work/diff-015-016.diff` | 19 个文件的完整 diff（+809 / −222） |
| `work/javap-016/` | 8 个关键类的 `javap -p -c` 反汇编 |
| `work/runserver-016{,-B,-C}.log` | 三组起服实测日志（A 全量崩 / B 少 TrackSnap 崩 / C 能起但自检 9/12） |
| `work/world-backup`、`work/runmods-backup` | 测试前快照 |
