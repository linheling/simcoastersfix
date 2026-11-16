# 性能优化分析与方案

> 面向后续补丁修改的工作文档。
> 数据来源：`simulatedcoasters-0.1.5.jar` 反编译 + `neoforge-21.1.249` 源码核对，实测日期 2026-11-16。
> 本文所有"估算"都标了 `est`，未经 profiler 实测的数字不要当结论引用。

## 0. 一句话结论

1.0.1 治的是**调用频率**（四处 TTL / 节流），单次调用的成本没动。
更划算的是治**单次成本**：用 `LevelChunkSection.maybeHas` 把每 section 的 4096 次 `getBlockState` 剪成调色板级判断，**语义等价、不引入延迟**。
落地后 `bearingScanTtlTicks*` 这两条"可见延迟"边界可以删掉。

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

注入技术：`@Inject` HEAD/RETURN + `@WrapOperation`（MixinExtras）。`simcoastersfix.mixins.json` 里 `defaultRequire: 1`——注入点找不到就直接加载失败，不会带病运行；`PatchSelfCheck` 在 `ServerStartedEvent` 强制加载四个目标类，逐个检查 `@Unique` 字段是否存在。

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
```

1.21.1 主世界每个区块 24 个 section（−64..320）。

| plot 规模 | 区块 | section 槽位 | 非空气 ≈40% | `getBlockState` 次数 | 单次扫描耗时 `est` |
|---|---|---|---|---|---|
| 3×3 | 9 | 216 | 86 | 35.2 万 | 0.4–1.1 ms |
| 5×5 | 25 | 600 | 240 | 98.3 万 | 1.0–3.0 ms |
| 11×11 | 121 | 2904 | 1162 | 476 万 | 4.8–14 ms |

一个世界里几十个 sublevel 时，这些扫描叠加起来就是 TPS 杀手。**注意这是估算**，`c` 的取值和"非空气比例"都随场景变，判据请用 §7 的 JFR 实测。

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
| `BalloonTetherController::onPrePhysicsTick` | ❌ 未覆盖（见 §5 E） |
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

`BalloonTetherController:110-173` 每 tick 对每个活跃气球：两次 `BalloonResolve.findBalloonById`，外加 `HashMap`、`HashMap`、`HashSet` 三次分配。有气球群时是稳定的 GC 压力。可缓存 host 解析结果，只在 host 变化时重算。收益取决于气球数量，优先级低于 A。

### F. `BY_SUB` 的 `synchronizedMap`（不要乱动）

上游 `CoasterCartBearingTickCache:12` 用 `Collections.synchronizedMap(new IdentityHashMap<>())`，每次 `scan` 都进一次监视器；1.0.1 换成 `WeakHashMap` 也一样。看起来是纯开销，但 sable 的 sublevel 容器是否有异步访问路径**没有验证过**——在证明单线程之前不要换成裸 `HashMap`。列为未决问题。

---

## 6. 落地清单（1.1.0）

顺序很重要：**先加 A，观察，再动 TTL**。

1. 新增 `src/main/java/dev/shiratori/simcoastersfix/mixin/PlotScanSectionFilterMixin.java`（§4.3）
2. `src/main/resources/simcoastersfix.mixins.json` 的 `mixins` 数组追加 `"PlotScanSectionFilterMixin"`
3. `PatchSelfCheck` 的注入没有 `@Unique` 字段可查，建议在 mixin 里加一个 `@Unique` 统计字段（如 `simc$sectionsSkipped`），自检时一并确认；顺带可在 debug 日志里报"本 tick 跳过 N 个 section"，便于现场判断
4. `FixConfig` 本轮**不动**（A 不需要配置）；C 阶段再加 `rivetSpreadSlots`
5. `README.md`：删「已知边界」的延迟条目；待办里加"§5 D 提上游 PR"
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

判据（启动日志）：

```
[simcoastersfix] patch applied to CoasterCartBearingTickCache
...
[simcoastersfix] patch applied to CoasterCartPlotScan      ← 新增项
[simcoastersfix] self-check OK: 6/6 mixin targets active
```

若出现 `patch NOT applied`，第一嫌疑是 `@At` 目标在 `remap = false` 下没匹配上 vanilla 描述符（NeoForge 1.20.5+ 运行时用 official names，理论上应命中，但**这一条要实测确认**，见 §9 未决②）。

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
- `bearingScanTtl*=1`（关掉 TTL、回到每 tick 精确扫描）时，新 jar 的 MSPT **不高于**旧 jar 开 TTL 的水平 —— 这一条才是"更优"的真正证明

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
2. **NeoForge 运行时命名**：`remap = false` 的 mixin 里引用 vanilla 成员，在 1.21.1 production 是否稳定命中，需 §7.1 实测确认。
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
