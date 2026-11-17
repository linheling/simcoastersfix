# 性能优化分析与方案

> 面向后续补丁修改的工作文档。
> 数据来源：`simulatedcoasters-0.1.5.jar` 反编译 + `neoforge-21.1.249` 源码核对，实测日期 2026-11-16。
> 本文所有"估算"都标了 `est`，未经 profiler 实测的数字不要当结论引用。

## 0. 一句话结论

1.0.1 治的是**调用频率**（四处 TTL / 节流），单次调用的成本没动。
更划算的是治**单次成本**：用 `LevelChunkSection.maybeHas` 把每 section 的 4096 次 `getBlockState` 剪成调色板级判断，**语义等价、不引入延迟**。
落地后 `bearingScanTtlTicks*` 这两条"可见延迟"边界可以删掉。

> **状态（2026-11-16）**：方案 A 已随 1.1.0 落地并实测（§11）；TTL 退场是下一步（§5 B），尚未做。

---

## 1. 版本与材料

| 项 | 值 | 来源 |
|---|---|---|
| Minecraft | 1.21.1 | `gradle.properties` |
| NeoForge | 21.1.249 | `gradle.properties`（本机 maven 镜像对 21.1.264/265 返回 404） |
| moddev 插件 | 2.0.144 | `build.gradle` |
| 补丁目标 | Create: Coasters Simulated **0.1.5** | Modrinth，2026-08-15 发布 |
| 上游最新版 | 仍是 0.1.5（2026-11-16 查） | `api.modrinth.com/v2/project/create-coasters-simulated/version` |
| JDK | `C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot` | **PATH 上的 java 是 25，不要直接用** |
| 反编译 | `tools\vf.jar`（Vineflower） | 335 个 java / 1.96 MB |

上游 0.1.5 的 changelog 第一条就是 "Several Critical FPS Performance Improvements"——说明作者在治 FPS，但**没有**治本文 §3 的扫描原语。所以"等上游修"不成立。

反编译现场（临时，可删）：

```
D:\C\MC\mods\_zhwork\scf-decompile\         # 335 java
D:\C\MC\mods\_zhwork\mc-src\                # 只抽了 LevelChunkSection / PalettedContainer / *Palette
```

复现命令见附录 B。

---

## 2. 现状：1.0.1 做了什么

四处都是"把每 tick 变成每 N tick"。原版病灶是拿 `==` 当去重（同 tick 内有效，跨 tick 必然失效）。

| # | 目标 | 原版行为 | 1.0.1 | 配置键 | 自检字段 |
|---|---|---|---|---|---|
| 1 | `CoasterCartBearingTickCache.scan` | `cached.gameTime == gameTime` 永不命中 | TTL 缓存 40/100 刻 | `bearingScanTtlTicksWithCart` / `…Empty` | `simc$scans` |
| 2 | `RivetDisassemblyEnforcer.onPrePhysicsTick` | 每 sublevel 每 tick `enforceSingleRivet` + `popIfSelfHosted` | 30 / 20 刻节流 | `rivetCheckIntervalTicks` / `rivetSelfHostCheckIntervalTicks` | `simc$lastRivetCheck` / `simc$lastSelfHostCheck` |
| 3 | `CoasterCartTrackSnap.onPrePhysicsTick` | 非 cart sublevel 每 tick 走 `onCartRemoved`（含发包） | 仅状态跃迁执行一次 | — | `simc$teardownDone` |
| 4 | `CoasterCartTrainLinkConstraint.maybeRestorePersistedLinks` | 每 tick 全量恢复持久化连杆 | 60 刻（3 秒）一次 | `trainLinkRestoreIntervalTicks` | `simc$lastRestore` |

注入技术：`@Inject` HEAD/RETURN + `@WrapOperation`（MixinExtras）。`simcoastersfix.mixins.json` 里 `defaultRequire: 1`——注入点找不到就直接加载失败，不会带病运行；`PatchSelfCheck` 在 `ServerStartedEvent` 强制加载五个目标类，逐个检查六个 `@Unique` 字段是否存在。

**这四处是对的**，问题在于它们都建立在"扫描原语很贵，所以少调几次"这个前提上，而没去降低单次成本。

---

## 3. 热路径数据

### 3.1 扫描原语

`CoasterCartPlotScan`（反编译 0.1.5:25-67）是两个结构相同的三重循环：

```java
public static BearingScanResult scanBearingCells(LevelPlot plot, Block bearingBlock) {
    for (PlotChunkHolder holder : plot.getLoadedChunks()) {
        LevelChunk chunk = holder.getChunk();
        ...
        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            LevelChunkSection section = chunk.getSection(sectionIndex);
            if (section != null && !section.hasOnlyAir()) {      // ← 唯一的剪枝
                ...
                for (int lx = 0; lx < 16; lx++)
                  for (int ly = 0; ly < 16; ly++)
                    for (int lz = 0; lz < 16; lz++)
                      if (section.getBlockState(lx, ly, lz).is(bearingBlock)) { ... }
            }
        }
    }
}
```

另一半 `forEachBearingWorldCell`（同文件 :77-105）循环体一样，只是把命中位置丢给 `Consumer`。

两个关键事实（已核对 `neoforge-21.1.249-sources.jar`）：

| 事实 | 实现 | 后果 |
|---|---|---|
| `LevelChunkSection.hasOnlyAir()` | `return this.nonEmptyBlockCount == 0;`（`LevelChunkSection:93`） | O(1)，但**只挡纯空气 section** |
| `LevelChunkSection.getBlockState(x,y,z)` | `return this.states.get(x,y,z);`（`:38`） | 单次便宜（数组索引 + palette 查表），但 ×4096 × section |

也就是说：**只要 section 里有任意一个非空气方块**——coaster 轨道、石头、羊毛，全都算——就会跑满 4096 次 `getBlockState`。这才是大头。

`scanBearingCells` 的返回值有三个消费者语义，改动时必须全部保持等价：

```java
record BearingScanResult(@Nullable BlockPos representative, int bearingCount, List<BlockPos> cells)
```

`representative` 的选择规则是 `compareYXZ`：先比 Y，再比 X，再比 Z，取最小。

### 3.2 成本模型

```
cost(一次扫描) ≈ Σ_{section ∈ loadedChunks} [ hasOnlyAir ? 0 : 4096 × c ]
c ≈ 1–3 ns（est：数组索引 + palette.valueFor + 分支）
  实测 20–24 ns/block（热态）、85–130 ns（冷/解释执行）→ 本节 est 偏低约 8 倍，见 §12
```

1.21.1 主世界每个区块 24 个 section（−64..320）。

| plot 规模 | 区块 | section 槽位 | 非空气 ≈40% | `getBlockState` 次数 | 原 est（c=1–3ns） | 实测校准（c=21ns） |
|---|---|---|---|---|---|---|
| 3×3 | 9 | 216 | 86 | 35.2 万 | 0.4–1.1 ms | **7.4 ms** |
| 5×5 | 25 | 600 | 240 | 98.3 万 | 1.0–3.0 ms | **20.6 ms** |
| 11×11 | 121 | 2904 | 1162 | 476 万 | 4.8–14 ms | **100 ms** |

一个世界里几十个 sublevel 时，这些扫描叠加起来就是 TPS 杀手。`c` 的实测值来自
§12 的实测（热态 300+ 次扫描/5s 之后），冷启动会再高 4–6 倍。剩下的不确定性只在
「非空气比例」，那个随存档变。

### 3.3 调用点谱系

"是否被 1.0.1 覆盖"必须顺着调用链看到入口才准（见 §9 坑①）。

| 调用点（反编译行号） | 走的入口 | 每 tick 频率 | 1.0.1 覆盖 |
|---|---|---|---|
| `CoasterCartTrackSnap:500`，`CoasterCartResolve:36`，`CoasterCartDuplicateEnforcer:22`、`:65`，`CoasterCartIdIndex:64` | `CoasterCartBearingTickCache.scan` | 高（但 TTL 命中） | ✅ #1 |
| `RivetDisassemblyEnforcer:52` | **直调** `scanBearingCells(plot, RIVET)` | N/30 | ⚠️ 仅降频，单次成本没变 |
| `RivetDisassemblyEnforcer:63` | `forEachBearingWorldCell` | 仅 `bearingCount > 1` | ⚠️ 同上 |
| `CoasterCartDuplicateEnforcer:32` | `forEachBearingWorldCell` | 仅 `bearingCount > 1` | ⚠️ 同上 |
| `CoasterCartTrackSnap:506`、`CoasterCartResolve:42`、`CoasterCartDuplicateEnforcer:66` | 直调 `representativeBearingPlotPos` | 仅 `getLevel() == null` 兜底分支 | ⚠️ 边缘 |
| `CoasterCartClientMembership:140` | 客户端 `forEachBearingWorldCell` 兜底 | 逐帧，但 `cells` 为空才走（近乎死代码） | ❌ 未覆盖 |
| `CoasterCartClientMembership.reconcile → classify` | 客户端分类 | 新增 / DIRTY / 区块数变化时全量 | ❌ 未覆盖 |

结论：**#2 是最大的漏网**。`enforceSingleRivet` 对每个 sublevel 无条件对整块 plot 扫 `RIVET`，而绝大多数 sublevel 根本没有 rivet——它每个都要付一次全量扫描，补丁只能把它降到 1.5 秒一次。尖刺只是变稀，没消失。

### 3.4 每 tick 入口清单

`SimulatedCoasters.java:99-103` 注册的全部 tick 钩子：

| 入口 | 1.0.1 |
|---|---|
| `CoasterCartTrackSnap::onPrePhysicsTick` | #3 覆盖 |
| `RivetDisassemblyEnforcer::onPrePhysicsTick` | #2 覆盖 |
| `RivetCartDragCompensation::onPrePhysicsTick` | ❌ 未覆盖（`isRivetSubLevel` 是 O(1)，成本低） |
| `BalloonTetherController::onPrePhysicsTick` | ✅ 1.2.0 tick 内去重（§13）；悬停射线/向量分配未动 |
| `CoasterCartTrainLinkConstraint::onPostPhysicsTick` | #4 覆盖 |

外加 `BalloonEvents` / `RivetEvents` / `CoasterPathSubLevelHooks` 三处 `ServerTickEvent.Post`。

---

## 4. 方案 A：section 级剪枝（recommended）

### 4.1 API 验证

`LevelChunkSection.maybeHas(Predicate<BlockState>)` 在 21.1.249 中确实存在（`javap` 实测），实现链：

```
LevelChunkSection.maybeHas(p)  →  this.states.maybeHas(p)      // PalettedContainer:280
                               →  this.data.palette.maybeHas(p) // 委托给 palette，不是 strategy
```

三种 palette 的实现（源码已抽出核对）：

| palette | 触发条件 | `maybeHas` 行为 | 每次调用成本 |
|---|---|---|---|
| `LinearPalette` | ≤16 种状态（4-bit） | `for (i < size) if (test(values[i]))` | ≤16 次谓词 |
| `HashMapPalette` | ≤256 种状态（8-bit） | 逐项 `values.byId(i)` 测试 | ≤256 次谓词 |
| `GlobalPalette` | >256 种状态 | **`return true;`** | O(1)，保守放行 |

`GlobalPalette` 那句 `return true` 是这个方案能站住的原因：**它永远不谎报"没有"**。

### 4.2 为什么零正确性风险

`maybeHas` 返回 `false` ⇒ 该 section 内不存在 `state.is(bearingBlock)` 的方块 ⇒ "跳过这一 section"与"扫完 4096 格发现 0 个"结果完全等价。

因此对 `count` / `cells` / `representative` 三个返回值都保持等价，且不影响 `cells` 的遍历顺序（跳过的是本来就不产生元素的 section）。返回 `true` 时走原逻辑，行为一字不变。

对比 TTL 方案的取舍：

| | 1.0.1 TTL | 方案 A |
|---|---|---|
| 结果精确性 | 有滞后（最坏 40/100 刻） | 精确，无滞后 |
| 收益来源 | 减少调用次数 | 降低单次成本 |
| 客户端收益 | 无（服务端语义） | 有（同一原语） |
| 失效模式 | 插拔 cart 后感知延迟 | 无（除非注入点消失） |

两者不冲突，A 落地后 TTL 可以逐步退场（§5 B）。

### 4.3 注入代码

沿用项目现有风格（MixinExtras `@WrapOperation`，可与其他 mod 的 redirect 组合）。注入点是**现有的 `hasOnlyAir()` 调用**，不需要 `@Overwrite` 复制 40 行逻辑：

```java
package dev.shiratori.simcoastersfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan", remap = false)
public abstract class PlotScanSectionFilterMixin {

    // 原代码：if (section != null && !section.hasOnlyAir()) { 4096 次扫 }
    // 返回值替换 hasOnlyAir() 的结果，返回 true = 跳过该 section。
    @WrapOperation(
            method = "scanBearingCells",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private static boolean simc$skipSectionsWithoutTarget(
            LevelChunkSection section, Operation<Boolean> original,
            LevelPlot plot, Block bearingBlock) {
        return original.call(section) || !section.maybeHas(state -> state.is(bearingBlock));
    }

    @WrapOperation(
            method = "forEachBearingWorldCell",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private static boolean simc$skipSectionsWithoutTargetForEach(
            LevelChunkSection section, Operation<Boolean> original,
            LevelPlot plot, Block bearingBlock, Consumer<MutableBlockPos> visitor) {
        return original.call(section) || !section.maybeHas(state -> state.is(bearingBlock));
    }
}
```

两个 `@WrapOperation` 的 handler 参数顺序是 **（被调方法的参数…, Operation, 外层方法剩余的捕获参数…）**，静态方法无 `this`。

### 4.4 预期收益

| 场景 | 现在 | 加 A |
|---|---|---|
| 常见 section（石+空气，palette 3–16） | 4096 次查表 | 3–16 次谓词 |
| 命中 section（真有 cart/rivet） | 4096 | 4096（本来就要扫） |
| `GlobalPalette` section | 4096 | 4096（`return true` 兜底） |
| `enforceSingleRivet` 对无 rivet 的 sublevel | 整块 plot 全量 | 每 section 一次 palette 判断 → 趋近于 0 |

量级 `est`：**每 section 50–1000 倍**，plot 级 100 倍上下。真实数字必须实测（§7）。

---

## 5. 其他候选

按 收益 / 风险 排序，A 之外的都是可选项。

### B. 退掉 TTL 的滞后（A 落地后做）

A 成立后 `bearingScanTtl*` 就没必要了。**分两步走**：先只加 A、TTL 不动，跑一周确认无回归；再把默认值改成 `1`（= 原版行为，配置键保留供排障），并删掉 README「已知边界」里那条"最坏 100 刻才感知"。

### C. 节流改 round-robin（消尖刺）

现在 #2 是"每 30 刻，所有 sublevel 一起扫"——均值降了，但第 30 刻是一次尖刺。改成每 tick 处理 `ceil(n / K)` 个（轮转游标），平均成本相同而分布平坦。新增配置 `rivetSpreadSlots`（默认如 4）。与 A 互补：A 降单次，C 降峰值。

### D. 事件驱动索引（建议提上游 PR）

`BearingScanResult` 已经带 `cells()`，说明数据结构本来就支持"维护一份 cell 列表"，缺的只是更新时机。真正根治：`plot → Set<BlockPos>`，在 `EmbeddedPlotLevelAccessor` 的 setBlock / destroyBlock 钩子上增量维护，扫描退化成一次查表。

改的是数据结构与不变量，风险高。**这种活正主做**比 mixin 稳，建议整理成 issue + PR 给 SilverGold，附 A 的一行级 patch 作为短期方案。

### E. `BalloonTetherController.onPrePhysicsTick`

`BalloonTetherController:110-173` 每 tick 对每个活跃气球：两次 `BalloonResolve.findBalloonById`，外加 `HashMap`、`HashMap`、`HashSet` 三次分配。有气球群时是稳定的 GC 压力。

**1.2.0 已做**：把这次调用内重复的三次解析（`findBalloonById` / `balloonBlockEntity` / `resolveHostSubLevel`，两趟各一次）收进 `TickEvalMemo`，行为等价（见 §13）。

**1.3.0 已做**：物理管线速度读取去重。`applyTetherSpring` 一次取 4 份速度、`dampHostSwing` 再取宿主 2 份、`dampHostVerticalInBand` 再取 1 份——字节码确认 **7 个调用点 / 5 个注入器**。依据是：一次 pre-physics 钩子内力与冲量只入队、步进时才应用，所以同一刚体的速度在这次调用里不变。复用是拷进调用方缓冲区，不是共享实例（别名会变成物理 bug）。开关 `balloonPhysicsMemo`。

**仍没做**：两处 `level.clip()` 悬停射线（4.5 格方块 clip，按 10 个气球估 0.2–0.8 ms/s，收益太小而它直接吃手感）、每气球约 20 个 `Vector3d`/`Quaterniond`（要重写方法体，mixin 做不到安全改写）。这两项要做得先实测手感。

### F. `BY_SUB` 的 `synchronizedMap`（不要乱动）

上游 `CoasterCartBearingTickCache:12` 用 `Collections.synchronizedMap(new IdentityHashMap<>())`，每次 `scan` 都进一次监视器；1.0.1 换成 `WeakHashMap` 也一样。看起来是纯开销，但 sable 的 sublevel 容器是否有异步访问路径**没有验证过**——在证明单线程之前不要换成裸 `HashMap`。列为未决问题。

---

## 6. 落地清单（1.1.0）

顺序很重要：**先加 A，观察，再动 TTL**。

1. 新增 `src/main/java/dev/shiratori/simcoastersfix/mixin/PlotScanSectionFilterMixin.java`（§4.3）
2. `src/main/resources/simcoastersfix.mixins.json` 的 `mixins` 数组追加 `"PlotScanSectionFilterMixin"`
3. `PatchSelfCheck` 的注入没有 `@Unique` 字段可查，建议在 mixin 里加一个 `@Unique` 统计字段（如 `simc$sectionsSkipped`），自检时一并确认；顺带可在 debug 日志里报"本 tick 跳过 N 个 section"，便于现场判断
4. `FixConfig` 本轮**不动**（A 不需要配置）；C 阶段再加 `rivetSpreadSlots`
5. `README.md`：**「已知边界」的延迟条目不要删**——1.1.0 不动 TTL，删了就是文档说谎（§5 B 是两步走）；要改的是「只治重复调用、不治算法」那一条，换成"section 剪枝有下限 + §5 D 才是根治"
6. `CHANGELOG.md` / `CHANGELOG.en.md` 写 1.1.0
7. `gradle.properties` 的 `mod_version=1.1.0`
8. **改版本号必须 `clean`**，否则 `processResources` 不重跑，jar 内的 `neoforge.mods.toml` 仍是旧版本（已踩过）

---

## 7. 验证

### 7.1 能不能编译 / 注入是否生效

```
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
.\gradlew.bat clean build
.\gradlew.bat runServer        # run\eula.txt 需 eula=true
```

判据（启动日志，1.1.0 实测输出）：

```
[simcoastersfix] patch applied to CoasterCartBearingTickCache#simc$scans
[simcoastersfix] patch applied to CoasterCartPlotScan#simc$sectionsSkipped
[simcoastersfix] patch applied to RivetDisassemblyEnforcer#simc$lastRivetCheck
[simcoastersfix] patch applied to RivetDisassemblyEnforcer#simc$lastSelfHostCheck
[simcoastersfix] patch applied to CoasterCartTrackSnap#simc$teardownDone
[simcoastersfix] patch applied to CoasterCartTrainLinkConstraint#simc$lastRestore
[simcoastersfix] self-check OK: 6/6 mixin targets active
```

若出现 `patch NOT applied`：`remap = false` 引 vanilla 描述符这一条**已实测命中**（§9② 已关闭），
下一个嫌疑才是 `@At` 目标与上游版本不符。

### 7.2 收益是否真实

用 JDK 自带的 JFR 采样，不要靠感觉：

```
jcmd <pid> JFR.start name=scf settings=profile duration=90s filename=D:\tmp\scf.jfr
jcmd <pid> JFR.dump  name=scf filename=D:\tmp\scf.jfr
jfr print --events jdk.ExecutionSample D:\tmp\scf.jfr > D:\tmp\samples.txt
Select-String D:\tmp\samples.txt -Pattern "CoasterCartPlotScan" | Measure-Object -Line
```

判据：

- `CoasterCartPlotScan` 相关采样帧占比下降 **≥80%**（场景不变）
- 同样存活的 sublevel 数量下，MSPT 有可测下降
- `bearingScanTtl*=1`（关掉 TTL、回到每 tick 精确扫描）时，新 jar 的 MSPT **不高于**旧 jar 开 TTL 的水平 —— 这一条才是"更优"的真正证明。§4.4 的实测已经给出这个方向的证据（无 rivet 的 plot 上，每 tick 扫描只剩 0.9% 一个 tick 线程）

**单次成本的绝对值**（每次扫描多少 section / 多少 block / 多少 ns）JFR 看不到。2026-11-16
用一次性探针量过一轮，数字留在 §12；探针本身已经从代码里移除（它只是测量脚手架，不该
跟着发布版走）。要复测就临时加回去，或者直接用 §12 的推导口径。

### 7.3 正确性回归

- cart 放/破、轨道对接、蓝图保存粘贴（`BLUEPRINT_COMPAT.md` 的验收日志）都要过一遍
- `enforceSingleRivet` 的 `bearingCount > 1` 分支需要专门构造：一个 sublevel 里塞两个 rivet，确认仍能弹出多余的那一个

---

## 8. 风险登记

| 风险 | 概率 | 缓解 |
|---|---|---|
| 上游改 `scanBearingCells` 结构，注入点消失 | 中 | `defaultRequire: 1` → 加载失败而非静默；自检显式报告；`mods.toml` 考虑收紧版本上限 |
| `remap = false` 下 vanilla 描述符不匹配 | 低 | §7.1 实测；必要时给该 mixin 开 `remap = true` 或加 refmap |
| `GlobalPalette` section 无收益 | 高但无害 | 退回原逻辑，不影响正确性 |
| 与其它 mod 的 `@Redirect` 冲突 | 低 | 用 MixinExtras `@WrapOperation`（可组合） |
| 谓词 lambda 每 section 分配 | 低 | JIT 通常能消除；若实测有 GC 压力，改成按 `Block` 缓存的静态 `Predicate` |

---

## 9. 未决问题

1. **上游 0.1.6 会不会自己修？** 0.1.5 的 changelog 说在治 FPS，但目前 latest 仍是 0.1.5。若 0.1.6 改了扫描实现，A 的注入点可能失效——需要在那时立刻回归。
2. ~~**NeoForge 运行时命名**~~ ✅ **已关闭（1.1.0 实测）**：`remap = false` 的 mixin 引用 vanilla 成员在 1.21.1 命中，dev 与 production 同一套 official names，`LevelChunkSection;hasOnlyAir()Z` 零问题。
3. **客户端 `classify` 是否值得单独治**：只在新增 / DIRTY / 区块数变化时全量，频率未知，需要客户端侧 JFR 采样。
4. **`synchronizedMap` 是否可以去掉**：需要先确认 sable 的 sublevel 容器有无异步访问路径（§5 F）。

---

## 10. 踩坑记录

**① 判断"是否被覆盖"要读到入口，不能只看字面量**
`CoasterCartDuplicateEnforcer:22` 那行写的是 `CoasterCartBearingTickCache.scan(sub)`，看起来是直调扫描；但它本身就是缓存入口，属于**已覆盖**。而 `:66` 的 `CoasterCartPlotScan.representativeBearingPlotPos` 才是直调，只是只在客户端 / `getLevel() == null` 分支上。

**② 保守型 API 的最差分支要读，它同时是安全边界**
`GlobalPalette.maybeHas` 直接 `return true`。做收益推断时这是"某些 section 没收益"；做正确性推断时这正是"永不谎报没有"的保证。同一个事实，两种读法都要写进结论。

**③ `hasOnlyAir()` 不是"有没有方块"，是"有没有非空气方块"**
它是 `nonEmptyBlockCount == 0` 的 O(1) 缓存字段。一开始我误以为它已经做了一次调色板级过滤——实际上它只挡纯空气，有任意方块的 section 都会跑满 4096 次。**这直接决定了 A 的收益量级**，也是本文档能成立的关键。

---

## 11. 落地记录（1.1.0，2026-11-16）

A 已落地并通过 `runServer` 实测。留**证据口径**，别只信日志行：

| 项 | 结果 |
|---|---|
| 产物 | `build/libs/simcoastersfix-1.1.0.jar`（246,863 B），jar 内 `neoforge.mods.toml` 为 `version="1.1.0"` |
| 自检 | `self-check OK: 6/6 mixin targets active`，含 `CoasterCartPlotScan#simc$sectionsSkipped` |
| `remap = false` 命中 | ✅ 未决②关闭（§9） |
| 注入形态 | `run/.mixin.out/class/.../CoasterCartPlotScan.class`（`-Dmixin.debug.export=true` 导出）：`scanBearingCells` 的调用点变成 4 参 wrapper（`LevelChunkSection, Operation, LevelPlot, Block`），`forEachBearingWorldCell` 是 5 参带 `Consumer`；原操作留在 `mixinextras$bridge$hasOnlyAir$8/9` 里 |
| 环境 | `runServer` + simulatedcoasters 0.1.5 / sable 2.0.6 / create 6.0.10（flywheel / ponder / veil 由 jarjar 带入），`Done (3.864s)` |

**没验到的**：运行时数据面。`runServer` 里没有 sublevel，`scanBearingCells` 一次都没真跑过——
正确性依据是 §4.2 的等价性论证加上面的字节码形态，**不是跑出来的**。§7.3 的 cart 放/破、
轨道对接、蓝图粘贴、双 rivet 分支仍需在真实存档里过一遍。

代码落点：

- `src/main/java/dev/shiratori/simcoastersfix/mixin/PlotScanSectionFilterMixin.java`
- `src/main/java/dev/shiratori/simcoastersfix/util/SectionFilter.java` — 谓词放这里而不是 mixin 内，
  目标类不会多出合成 lambda 方法（合成方法合并后容易和其它 mixin 撞名），也便于单独验证
- `PatchSelfCheck.TARGETS` → 6 条，新增 `targetCount()` 供启动日志用（原来硬编码的
  "4 mixin targets" 已经和实际条目脱钩——这类数字只能有一个来源）

**追加：一轮实测（同日）**。§7.2 原来只有「用 JFR 看占比」，看不到单次扫描的绝对量与
section 分解。2026-11-16 用一次性探针量了一轮，结论在 §12；探针代码已移除。两条最重要的：

- §3.2 的 `c ≈ 1–3 ns` 实测是 **20–24 ns/block**（热态），那两节的成本表**偏低约 8 倍**（已按实测重算）。
- TTL 命中 **99.7%**，扫描频率降 20–40 倍——第一次用直接计数验证 §2 的推断，而不是靠推理。

**与 §6 原计划的偏差**：§6 第 5 条让删 README「已知边界」的延迟条目，**没删**。TTL 本轮未动，
延迟依然存在；延迟条目留到 §5 B 第二步（TTL 默认值改 1）时一起处理，本轮只重写了
「只治重复调用、不治算法」那条。

## 12. 实测数据（2026-11-16，一次性探针）

用来验证 §3.2 的 est 和 §4 的收益。**探针代码已移除**（测量脚手架不进发布版，见 §11）；
下面留数字和口径，要复测就临时加回去。原始日志在 `build/probe2.log`、`build/probe3.log`。

场景：dev `runServer`（NeoForge 21.1.249 / MC 1.21.1，平世界），`/sable spawn sphere 8` 造 4 个
sublevel，物理暂停。每个 plot 约 9 个区块 → **每次扫描 216 个 section 槽位**（9 × 24，
与 §3.2 的「每区块 24 section」吻合）。造场景的手段是 RCON（`tools/rcon.ps1`），因为
`runServer` 不转发 stdin。

### 12.1 调用频率与 TTL

| 配置 | scanBearingCells | TickCache.scan 调用 | 缓存命中 | 单次扫描 section | 扫描耗时 ÷ 墙钟 |
|---|---|---|---|---|---|
| 出货默认 TTL 40 / 100 | 1.6–2.1 /s | 210 /s | **99.75%** | 216 | **0.008%** |
| TTL = 1（≈原版频率） | **41–62 /s** | 241 /s | 仅同 tick 去重 | 216 | **0.076%** |

整轮累计：`cache calls=38,788 hit=37,654 store=1,134` → **命中 97.1%**。

### 12.2 单次成本

| 状态 | ns / block | 单次扫描 p50 | p95 | 说明 |
|---|---|---|---|---|
| 冷（前 20–50 次调用，解释执行） | **85–130** | 65–131 µs | 262 µs–2.1 ms | 别拿这组当结论 |
| 热（每 5s 300+ 次扫描） | **20–24** | **16.4 µs** | 16–65 µs | 稳态 |

### 12.3 A 的收益

| 场景 | walked section | 不补丁应有 | 省掉 |
|---|---|---|---|
| plot 里没有目标方块（最常见） | 0 | 6,586,368 block / 5s | **100%** |
| plot 里有目标方块 | 2–3 / 次 | — | 89–95% |
| 整轮累计 | 402 | 82,845,696 block | **98.0%** |

### 12.4 §3.3「最大漏网」的量化

`RivetDisassemblyEnforcer.enforceSingleRivet` 对 4 个**没有 rivet** 的 sublevel、把间隔调到 1
（= 原版行为）：80 次扫描/s，不补丁时本应 **1,317,274 次 `getBlockState`/s**；A 之后只占
tick 线程 **0.85–0.90%**。这支持 §5 B：单次成本已经低到可以每 tick 跑，TTL 退场的前提成立。

### 12.5 口径与局限

- 扫描耗时占比 = `nanosTotal / 窗口墙钟`（tick 线程整段时间都在跑 tick）。**不要**再乘 20，
  那是把「一个 tick 的预算」和「墙钟占比」混在一起，会把数字放大 20 倍（踩过）。
- 「不补丁时该走多少 block」是算术推出来的：A 跳过的 section 正是「不补丁也会走完 4096 格
  然后 0 命中」的那些，所以基线 = `(walk + paletteSkip) × 4096`。时间基线不能这么推。
- 开发机、平世界、球体，不是真实存档；探针只在 4 个 sublevel 上跑过。
- `forEachBearingWorldCell` 整轮只采到 7 个样本，其中一次 292 ms 离群发生在冷启动 + 拆解
  路径上，**样本不足以定性**。

## 13. 1.2.0：原版把「每 tick 变的值」当成了「只跑一次的标记」，写了两遍

同一个思维错误在原版里出现了两次，写法一字不差：

```java
Long last = LAST_RESTORE_GAME_TIME.get(dimension);
if (last == null || last != gameTime) {     // gameTime 每 tick 都变 → 恒成立
   LAST_RESTORE_GAME_TIME.put(dimension, gameTime);
   restoreXXX(level);                       // ← 每 tick 全量，不是「一次」
}
```

| 位置 | 行 | 1.2.0 前 | 1.2.0 |
|---|---|---|---|
| `CoasterCartTrainLinkConstraint.maybeRestorePersistedLinks` | `:270-278` | 1.0.0 起已限流 60 刻 | 不变 |
| `BalloonTetherController.maybeRestoreTethers` | `:217-225` | **每物理 tick** 调 `restoreTethers`，遍历全部 sublevel（每个一次 `getBlockState(ZERO)` + 一次 `containsKey`） | 默认 40 刻一次 |

另外两处是「同一个 tick 内把同样的答案再问一遍」，都用 `TickEvalMemo` 收掉：

- `BalloonTetherController.onPrePhysicsTick`：两趟遍历，每趟各解析 气球 → sublevel → 方块实体 → 宿主。
  字节码里能看到两处 `findBalloonById`、两处 `balloonBlockEntity`、两处 `resolveHostSubLevel` 都被包了一层。
- `CoasterCartTrainLinkConstraint.tick`：每条活跃连杆解析 cart A / cart B，同 tick 内的重复丢掉。

**为什么可以等价**：`TickEvalMemo` 的作用域是**一次钩子调用**——`begin()` 在 HEAD、`end()` 在 RETURN，
而这两个钩子内部不改 sublevel 容器、不改方块实体，所以同一个 tick 内的第二次查询不可能得到不同答案。
`begin()` 无条件清空而不是数嵌套层数，是为了让「钩子抛异常导致 `end()` 没跑」也不会把旧值留到下一次。

**没动的地方（有意）**：`ACTIVE_LINKS` 的 `List.copyOf` 快照（遍历中解链的安全前提）、气球的两处
悬停射线、每气球的向量分配。这三样要么有并发语义，要么接在物理反馈里，都不属于「零行为差异」。

### 13.1 1.3.0：气球路径的物理管线读取

同一个「一次调用内答案不变」的口径用在物理管线上：`applyTetherSpring` 取气球线速度/角速度与宿主
线速度/角速度共 4 次，`dampHostSwing` 再取宿主 2 次，`dampHostVerticalInBand` 再取 1 次。字节码确认
**7 个调用点被 5 个 `@WrapOperation` 覆盖**（同一方法里同描述符的多个调用点由一次注解全部包住）。

依据是 Sabre/Rapier 的语义：这次钩子里调的 `applyAndRecordPointForce` / `applyLinearImpulse` 只是
**入队**，速度在 `step` 时才变，所以同一刚体的速度在一次 `onPrePhysicsTick` 内恒定。复用是
`destination.set(cached)` 拷进调用方缓冲区，不是返回共享实例——上游确实会原地改这些向量。

刻意没碰 `wakeUp` 的重复调用：没有证据说明它贵，而每多一个注入点就多一处「上游改名即崩」。

**探针已移除**（§11），所以 1.2.0 的三项**没有独立实测数据**；上线前按 §7.2 用 JFR 看
`BalloonTetherController` / `CoasterCartTrainLinkConstraint` 的帧占比，或临时把开关逐个关掉做 A/B。

## 14. 「车在轨道上贵几倍」的实测归因（1.4.0）

用户观察：车吸附在过山车轨道上时，性能消耗是不在轨道上的几倍。以下是实测出来的原因，
以及一次被数据推翻的假设。

### 14.1 先说被推翻的那个假设

看了 `CoasterCartTrackSnap` 的吸附分支后，第一判断是「每车每 tick 多一个 5/6 轴锁定的
`GenericConstraintHandle`，solver 每个 substep 都要解它」。**实测否定**：

```
20s 采样（车在轨道上跑），server tick 线程：
  Rapier3D.step                     17.79% self
  CoasterPeerCurveHandleLengths.getCanonicalLengths   16.21% ←
  CoasterPeerCurveHandleLengths.edgeKeyFromEndpoints   6.68% ←
  Rapier3D.isConstraintValid         1.13%
```

关节类帧（`addConstraint` / `setFrame` / `isConstraintValid`）加起来 1% 出头，**不是主因**。
同一次采样里曲线手柄长度占了 **21.8–30.9%**（249–301 / 999–1141 busy 样本）。

### 14.2 真凶与机制

`CoasterPeerCurveHandleLengths.getLengthAtEndpoint`（被曲线采样循环每点调用两次）:

1. **无条件**先算 `fallbackSingleHandleLength` → `BezierConnection.getHandleLength()`；
2. 后者被上游自己的 `BezierConnectionGetHandleLengthCoasterMixin` 包着，又回递归调
   `getCanonicalLengths` —— 一次端点查询 = **两次** canonical 查询；
3. 每次 `getCanonicalLengths` = 2 次 `synchronizedMap(WeakHashMap)` 查询 +
   拼一个 `"<long>:<long>"` String 键 + 1 次 `HashMap<String>` 查询。

所以每个曲线采样点约 4 次这样的三连查。这就是「在轨道上」独有的开销：不吸附的车根本走不到
`CoasterBezierEdgeSamplingContext.plotLocalCenterline` 这条路径。

### 14.3 修法与 A/B（1.4.0）

缓存 `getLengthAtEndpoint`（返回 `double`，无别名问题），用上游自带的 `AtomicLong REVISION`
精确失效——每个写入者（`putCanonical` / `putPreviewCanonical` / `commitPreviewLengths` /
`removeEdge` / `takeCanonicalLengthsForRemap`）都会 bump 它，所以**不引入任何时间滞后**。

同一会话内热切换 `curveHandleLengthCache` 做 A/B（不用重启客户端）：

| | busy 样本 | 曲线样本 | 其中 canonical | MSPT p95 | p99 |
|---|---|---|---|---|---|
| 关闭（= 上游） | 1141 / 1137 | 249 / 351 | **219 / 328** | 14.12 / 14.63 | 15.99 / 16.90 |
| 开启 | 876 / 715 | 84 / 93 | **0 / 0** | 13.60 / 11.76 | 15.46 / 13.42 |

`getCanonicalLengths` 与 `edgeKeyFromEndpoints` 在开启后**一个样本都不剩**；剩下的 84/93 全是
我们缓存命中本身的开销（`wrapMethod$...$simc$cachedLengthAtEndpoint` 7.4–9.5% busy）。

### 14.4 还能再压吗

- **可以，但风险更高**：给 `BezierConnection` 加一个 `@Unique` 字段存 `(revision, h0, h1)`，
  把缓存命中从「一次 map 查表 + 拆箱」压到「一次字段读」，预计能把剩下的 7–9% 再砍掉大半。
  代价是 mixin 进 Create 的类——上游/别的 mod 也在改 `BezierConnection`，冲突面变大。
- **不值得**：`updateAfterSnap` 里那个用不上的 `GenericConstraintConfiguration` 构造、
  `snapTargetWorld → snapMc` 算两遍（约 100–300 ns/车/tick），以及 `CoasterTrackStaffRailStabilizer.remove`
  每 tick 的锁 + map remove（~50 ns）。

### 14.5 教训

上一轮我基于源码就直接断言「是关节」，结果 JFR 一测就被推翻。**「读代码得出的因果」必须用
采样验证一次**，尤其是这种「看起来显然」的物理开销——真正贵的东西藏在采样循环里。

## 附录 A：调用点原始清单

反编译 `0.1.5` 的行号，仅供定位，重建后可能偏移。

```
CoasterCartBearingTickCache.scan(...)          # 已覆盖入口
  CoasterCartIdIndex:64
  CoasterCartDuplicateEnforcer:22, :65
  CoasterCartResolve:36, :208
  CoasterCartTrackSnap:500

CoasterCartPlotScan.scanBearingCells(plot, block)   # 直调，未被 TTL 覆盖
  RivetDisassemblyEnforcer:52   (RIVET)
  RivetDisassemblyEnforcer:95   (RIVET, cleanupRivetBlocksAfterMove)

CoasterCartPlotScan.representativeBearingPlotPos(...)
  CoasterCartResolve:42, :210, :213
  CoasterCartTrackSnap:506
  CoasterCartDuplicateEnforcer:66
  CoasterCartLinkPlacement:49, :287

CoasterCartPlotScan.forEachBearingWorldCell(...)
  RivetDisassemblyEnforcer:63
  CoasterCartBlock:71
  CoasterCartDuplicateEnforcer:32
  CoasterCartClientMembership:140        # 客户端
  CoasterCartDebugRenderer:61            # 仅调试渲染

CoasterCartBearingTickCache.invalidate / remove / clearDimension
  CoasterCartSpawner:116, CoasterCartTrackSnap:333,
  CoasterCartTrainLinkConstraint:208, CoasterCartTrainLinkHooks:73
```

## 附录 B：复现反编译

```powershell
$java = "C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot\bin\java.exe"
$wf   = "D:\C\MC\mods\_zhwork\scf-decompile"
& $java -Xmx2G -jar "D:\C\MC\mods\tools\vf.jar" -dgs=1 -hes=1 -hdc=1 `
    "D:\C\MC\mods\projects\simcoastersfix\libs\simulatedcoasters-0.1.5.jar" $wf
```

MC 侧源码（`LevelChunkSection` / `PalettedContainer` / `*Palette`）从 moddev 产物里抽：

```powershell
$src = "D:\C\MC\mods\projects\simcoastersfix\build\moddev\artifacts\neoforge-21.1.249-sources.jar"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($src)
$zip.Entries | Where-Object { $_.FullName -match 'level/chunk/(Palette|LinearPalette|HashMapPalette|GlobalPalette|PalettedContainer|LevelChunkSection)\.java$' } |
  ForEach-Object { [System.IO.Compression.ZipFileExtensions]::ExtractToFile($_, "D:\C\MC\mods\_zhwork\mc-src\$($_.Name)", $true) }
$zip.Dispose()
```

## 附录 C：关键源码摘录

`LevelChunkSection`（1.21.1，official names）：

```java
public BlockState getBlockState(int x, int y, int z) { return this.states.get(x, y, z); }   // :38
public boolean hasOnlyAir() { return this.nonEmptyBlockCount == 0; }                      // :93
public boolean maybeHas(Predicate<BlockState> p) { return this.states.maybeHas(p); }      // :172
```

`PalettedContainer.maybeHas`：

```java
public boolean maybeHas(Predicate<T> p) { return this.data.palette.maybeHas(p); }         // :280
```

`GlobalPalette.maybeHas`：

```java
public boolean maybeHas(Predicate<T> p) { return true; }
```

## 15. 1.5.0：上游把病根治了，我们改做「减法 + 量体温」

**这是本文件里第一次「删代码」而不是「加代码」的版本。** 全部依据見
[`UPSTREAM-0.1.6.md`](UPSTREAM-0.1.6.md)（反编译 + 19 文件 diff + javap + 三组起服实测）。

### 15.1 病根被上游治好，节流失去目标

0.1.6 引入了两个成员索引类（`CoasterCartServerMembership`、`RivetPlotMembership`），把
「每 tick 全量扫」换成了「事件驱动 + 精确失效」：

| 我们原来的补丁 | 0.1.6 的做法 | 结论 |
|---|---|---|
| 1.0.0 轴承扫描 TTL（40/100 刻） | 成员索引 + `readyPlotChunkCount` 处理加载窗口 | 删收益基础：`forEachCart` 已不再遍历非 cart |
| 1.0.0 rivet 30/20 拍节流 | `beginEnforcePass` 每 gameTime 一次 + `rivetsAndDirty` | 保留代码但默认 1（无操作） |
| 1.0.0 连杆恢复 60 拍 | `gameTime % 100 == 0` | 删除（方法改名成 private） |
| 1.0.0 TrackSnap 广播去重 | `forEachCart` 只遍历 cart | 删除（调用点进了 lambda） |
| 1.1.0 section 剪枝 | `skipSection` 内的 `maybeHas` | 删除（上游同款） |

`1.1.0` 那次判断是对的——上游用了**一模一样**的一行 `maybeHas(state -> state.is(block))`。
这条值得记下来：补丁的价值有保质期，上游一旦内建，继续留着只是多一个会随版本崩的注入点。

### 15.2 为什么 1.5.0 不「顺手再找几个新优化」

因为 0.1.6 的剩余成本换了形状：不是某个函数贵，而是**每 tick 一次 O(sublevel) 的遍历**
（`reconcile`、`reconcileLive`、`rivetsAndDirty`），单次 0.25–1.3 µs，采样器的 self-time 里几乎看不见。
按 §14 的教训（读代码得出的因果必须用采样验一次），这时候应该先**量**，而不是先写 mixin。

所以 1.5.0 带的是探针（默认开）：

| 计数项 | 目标 | 假设（待证） |
|---|---|---|
| `cart.readyChunks` | `CoasterCartPlotScan.readyPlotChunkCount` | 每 tick 每 sublevel **无条件**遍历 plot 的 loaded chunks（在 `needsClassify` 之前算），是 0.1.6 新增开销里最可疑的一条 |
| `cart.reconcile` | `CoasterCartServerMembership.reconcile` | 每 tick 每维度被调多次，只有第一次真跑；看 us/call 不看次数 |
| `cart.membershipScan` | `CoasterCartServerMembership.scan` | 缓存命中仍做一次 `getBlockState` ⇒ N 车 = N 次世界读/tick |
| `cart.reclassify` | `classify` | 稳态应趋 0；非 0 = dirty / ready-chunk 在抖 |
| `rivet.reconcileLive` / `rivet.rivetsAndDirty` | `RivetPlotMembership` | 每 tick 全 sublevel + 三套 set 裁剪 + 新 record + 两个集合分配 |
| `plot.scanBearingCells` | 块级扫描 | 稳态应极少；每 tick 都有 = 索引没兜住 |
| `alloc` | 服务端线程分配率 | 「每 tick new record/set」的总账 |

实现约定（三条都要守住）：

1. **只读**：只有 `System.nanoTime()` 与计数器自增，不改参数、不改返回值、不短路；
2. **便宜**：两次 `nanoTime` ≈ 40 ns；计数器是裸 `long`，跨线程丢精度不丢正确性；
3. **安静**：每窗口一行，窗口内全零则不打印；`alloc` 用 `com.sun.management.ThreadMXBean`，
   取不到就自动隐去，不报警。

探针放在**独立 mixin config**（`required=false` + `defaultRequire=0`）并各带一个 `@Unique`
锚点字段，由 `ProbeSelfCheck` 在启动时核对——「探针掉线」必须和「探针测得 0」可区分，否则默认开
的探针会变成长期假数据。

### 15.3 实测（2026-11-17，dev 专服）

```
self-check OK: 9/9 mixin targets active
probe self-check OK: 3/3 probe mixins active
probes[600t]: cart.reconcile 3618x 4.54ms 1.25us/call | rivet.reconcileLive 1800x 1.61ms 0.89us/call
             | rivet.rivetsAndDirty 1800x 0.46ms 0.25us/call | alloc 0.43MB/s || total 11.01us/tick
```

空载世界稳态 **10–11 µs/tick**；`cart.readyChunks` 为 0 是因为该测试世界没有 sublevel
（`run/world/sublevels` 只有一个空文件）。**真实存档的数字还没量**——这正是探针要解决的事，
下一次进游戏后看那三行，再决定要不要为 `readyPlotChunkCount` 写补丁。
