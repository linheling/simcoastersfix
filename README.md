# Coaster Perf Fix (Unofficial)

> **非官方补丁。** 不隶属于 Create: Coasters Simulated 的作者 SilverGold，也未获其背书。
> 本仓库是独立实现，不包含原模组的任何代码。

`Create: Coasters Simulated 0.1.5` 的服务端性能补丁。**不改动原模组 jar**，用 Mixin 给四处
「在 pre-physics tick 里每 tick 全量重跑」的调用加上限流。客户端可以继续用原版 0.1.5，
网络协议没有任何变化。

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
| 1 | `CoasterCartBearingTickCache.scan` | `gameTime == cached.gameTime` → 缓存从不命中，每 tick 整块 plot 扫描 | TTL 缓存：有轴承格 40 刻 / 无轴承格 100 刻 |
| 2 | `RivetDisassemblyEnforcer.onPrePhysicsTick` | 每个 sublevel 每 tick 跑 `enforceSingleRivet` + `popIfSelfHosted` | 30 刻 / 20 刻节流 |
| 3 | `CoasterCartTrackSnap.onPrePhysicsTick` | 非 cart sublevel 每 tick 走 `onCartRemoved`（含 `CoasterCartTrainLinkSync` 发包） | 只在状态跃迁那一次执行 |
| 4 | `CoasterCartTrainLinkConstraint.maybeRestorePersistedLinks` | 每 tick 全量恢复持久化连杆 | 每 60 刻（3 秒）一次 |

第 1 项收益最大：`CoasterCartPlotScan.scanBearingCells` 是
「`plot.getLoadedChunks()` × section × 16×16×16 逐格 `getBlockState`」的三重循环，
而 `scan` 有 6 个调用点，且**不含 cart 的 sublevel 也照样被扫**。地图上绝大多数
sublevel（组装体、机械、平台）都不含 cart，TTL=100 等于把这块开销压到 1/100。

## 配置

`config/simcoastersfix-server.toml`（首次启动生成）：

```toml
bearingScanTtlTicksWithCart     = 40   # 有轴承格 sublevel 的扫描缓存刻数
bearingScanTtlTicksEmpty        = 100  # 无轴承格 sublevel 的扫描缓存刻数
rivetCheckIntervalTicks         = 30   # 铆钉单铆钉检查间隔
rivetSelfHostCheckIntervalTicks = 20   # 铆钉自托管弹出检查间隔
trainLinkRestoreIntervalTicks   = 60   # 连杆恢复间隔
```

调大 = 更省 CPU、感知更迟；**任一值设为 1 = 回到原版行为**，可用于 A/B 对照排障。

## 启动自检

Mixin 的注入只有在**目标类被加载**时才校验。类没被加载过，日志里就一片安静——
「没报错」很容易被误读成「补丁生效了」。所以模组在服务器启动完成时主动强制加载
四个目标类，检查注入的 `@Unique` 字段是否存在，逐项打日志：

```
[simcoastersfix] patch applied to CoasterCartBearingTickCache
[simcoastersfix] patch applied to RivetDisassemblyEnforcer
[simcoastersfix] patch applied to CoasterCartTrackSnap
[simcoastersfix] patch applied to CoasterCartTrainLinkConstraint
[simcoastersfix] self-check OK: 4/4 mixin targets active
```

看到 `self-check FAILED` 说明目标模组版本对不上，注入点没找到——此时
`mixins.json` 的 `defaultRequire: 1` 通常已经让加载直接失败，不会带病运行。

## 构建

需要 JDK 21。`maven.neoforged.net` 在本机不可达，走 `mirrors.qlu.edu.cn/bmclapi`
镜像（`~/.gradle/init.gradle` 已配），因此 `neo_version` 钉在 **21.1.249**——
该镜像对 21.1.264/265 返回 404。产物在 265 上运行兼容（只依赖 `ServerSubLevel`
等稳定 API）。

```
gradlew.bat build          # 产物 build/libs/simcoastersfix-1.0.1.jar
gradlew.bat runServer      # 本地 dev 验证（run/mods 需放 create + sable + simulatedcoasters）
```

`libs/` 下四个 jar 只用于编译期符号解析，**不会打进产物**，也**不在仓库里**
（`.gitignore` 已排掉 `libs/*.jar`）。自行准备：

- `simulatedcoasters-0.1.5.jar` — 补丁目标（MIT）
- `sable-2.0.6.jar` — `ServerSubLevel` 等类型（PolyForm Shield）
- `sable-companion-common-1.6.0.jar` — 从 sable jar 的 `META-INF/jarjar/` 里解出来的；
  `ServerSubLevel.getLevel()` 声明在这个 companion 接口上，缺它编译不过
- `sable-photomancy-1.0.1.jar` — 蓝图兼容层要编的 `api.blueprint.*` 扩展点；**只在编译期需要**，
  运行期由 Photomancy 本体提供

这四个 jar 属于第三方产物，再分发各有条件，因此不随源码发布。

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

- TTL 是**可见延迟**：cart 轴承格变化最坏要等 40 刻（有轴承）/ 100 刻（无轴承）才被感知。
  频繁插拔 cart 的场景可以调小 `bearingScanTtlTicksEmpty`。
- 本补丁只治「重复调用」，不治 `scanBearingCells` 本身 O(chunks×sections×4096) 的算法。
  根治要把扫描换成 section 层的非空气格索引——那是上游作者的事。
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
- [`OPTIMIZATION.md`](OPTIMIZATION.md) — 性能分析：热路径数据、调用点谱系、待办优化方案（section 级剪枝）与 JFR 验证方法

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
