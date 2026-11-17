# Coaster Blueprint Compat (Unofficial)

> **非官方补丁。** 不隶属于 Create: Coasters Simulated 的作者 SilverGold，也未获其背书。
> 本仓库是独立实现，不包含原模组的任何代码。

**唯一的功能**：给 `Create: Coasters Simulated 0.1.6` 加一层 Sable Photomancy 蓝图兼容，让蓝图
保存/粘贴时**铆钉、气球、车厢连杆**不再丢。用 Photomancy 的官方扩展点实现，不改动原模组 jar，
也不碰游戏逻辑。

- Minecraft 1.21.1 / NeoForge 21.1.x
- 依赖：Create `6.0.10+`、Sable `2.0.0+`、Coasters Simulated **`0.1.6`（只支持 0.1.6）**
- 可选：Sable Photomancy `sable_schematic_api 1.0.0+`——**没有它这个 mod 什么都不做**（只打一行日志）

## 2.0.0：只留蓝图兼容

`simulatedcoasters 0.1.6` 把 1.x 那几条性能补丁要修的东西自己修了（事件驱动的成员索引、
section 调色板剪枝、rivet 每 tick 单次 pass、连杆恢复 100 拍），剩下几条补丁的注入点也被搬走或
改名。逐条核对之后，2.0.0 把**性能部分整个删掉**，只保留蓝图兼容层：

| 删掉的 | 为什么 |
|---|---|
| section 调色板剪枝（1.1.0） | 0.1.6 自己做了同一行 `maybeHas` |
| 轴承扫描 TTL、rivet 节流、连杆恢复节流、TrackSnap 广播去重（1.0.0） | 0.1.6 换成事件驱动索引，节流失去目标；其中两个注入点已被改名/搬进 lambda |
| 热点探针（1.5.0） | 随性能部分一起去掉 |
| 贝塞尔手柄长度缓存（1.4.0） | 它仍然有效，但它属于「性能补丁」，不在本次保留范围内 |

**想要回性能补丁**：最后一个带全部优化与探针的版本是 **1.5.2**，对应 git tag
`archive-1.5.2-perf`，产物可以直接从 `dist/simcoastersfix-1.5.2.jar` 取。

## 它修的是什么

Photomancy 的蓝图只搬运「子层级里的方块状态 + 方块实体 NBT」，所有**指向别处**的引用原样复制——
不换算坐标、不重映射 uuid。而 Coasters Simulated 恰好有三处数据是这么存的：

1. **铆钉宿主**：铆钉方块实体记着宿主坐标，粘贴后坐标还指着旧世界，于是下一 tick 被自检拆掉；
2. **气球宿主**：同上，气球找不到原来的方块就飘走或贴在错位置；
3. **车厢连杆**：耦合存在 `ServerSubLevel#getUserDataTag()` 里，Photomancy 会原样搬运，但运行时
   索引（`CoasterCartTrainLinkConstraint`）不会跟着重建。

这三件事游戏里**都没有报错**——是静默丢数据。兼容层的做法是注册两个 blueprint mapper 和一个
`SableBlueprintEvent` 钩子，在保存时把引用写进蓝图、在粘贴后把它们重映射到新 id 上。
原理、字段与验收日志见 [`BLUEPRINT_COMPAT.md`](BLUEPRINT_COMPAT.md)。

## 材料成本（2.1.0）

蓝图打印（胶卷/胶片）会按「方块 → 物品」算材料，而这条链的默认规则是
`state.getBlock().asItem()`，遇到空气就不收钱。Coasters 有三个方块**没有 BlockItem**
（物品是普通 `Item`，方块走子层级生成，不从背包放）：

| 方块 | 收的物品 | 依据 |
|---|---|---|
| `coaster_cart`（方块） | `coaster_cart` | `CoasterCartItem` 放置后 `shrink(1)` |
| `rivet`（方块） | `rivet` | `RivetPlacePayload` `shrink(1)` |
| `red_balloon`（方块） | `red_balloon` | `BalloonAttachInteraction` `shrink(1)` |

2.1.0 用 Photomancy 公开的 `BlueprintBlockCostRules.register(block, rule)` 给这三个方块补上
「1 方块 = 1 物品」，于是打印不再白嫖它们。`coaster_anchorpoint` 本身有 BlockItem，默认规则就收钱。

**未覆盖**：`coaster_track_material`（轨道方块）。它没有物品，而 1 个 `coaster_track` 放的是一整段
贝塞尔曲线——按方块收会几十倍超收，得在「一段轨道」的粒度上做，属上游的活。

## 安装

- 客户端/服务端都装（蓝图工具在客户端，粘贴在服务端；单人游戏两者同一进程）。
- 专用服务器上必须装；客户端可以不装，但那样你画不出蓝图。
- 装了就生效，游戏内**没有任何按钮、命令或 GUI**。

## 构建

需要 JDK 21。`maven.neoforged.net` 在本机不可达，走 `mirrors.qlu.edu.cn/bmclapi` 镜像
（`~/.gradle/init.gradle` 已配），因此 `neo_version` 钉在 **21.1.249**。

```
gradlew.bat build          # 产物 build/libs/simcoastersfix-2.0.0.jar
gradlew.bat runServer      # 本地 dev 验证
```

`libs/` 下四个 jar 只用于编译期符号解析，**不会打进产物**，也**不在仓库里**（`.gitignore` 已排掉）：

- `simulatedcoasters-0.1.6.jar` — 补丁目标（MIT）
- `sable-2.0.6.jar` — `ServerSubLevel` 等类型（PolyForm Shield 1.0.0）
- `sable-companion-common-1.6.0.jar` — 从 sable jar 的 `META-INF/jarjar/` 解出；
  `ServerSubLevel.getLevel()` 声明在这个 companion 接口上
- `sable-photomancy-1.0.1.jar` — 蓝图 API 的 `api.blueprint.*` 扩展点

明细见 [`libs/README.md`](libs/README.md)。`create.jar` 自 2.0.0 起不再需要。

## 已知边界

- **2.0.0 不含连杆守卫。** 1.4.1–1.5.2 里那条「删除前重新裁决」的补丁随性能部分一起去掉了，而
  `simulatedcoasters 0.1.6` 的成员索引**仍有一个已知回归**：载入时若某个 sublevel 在 plot 还没填充完
  就被归类，它会被永久缓存成「不是车」，于是每 100 tick 的连杆恢复 pass 都会判定「这节车没了」，
  `unlinkPair` 会把耦合从车厢 NBT 里删掉且不可恢复。实测一次会话里误判过 130 节车厢（见 1.5.1 的
  CHANGELOG）。**如果你的存档里有连成列车的过山车，装 2.0.0 前先备份，或者继续用 1.5.2。**
  需要的话可以把那条守卫单独作为 2.0.1 的「正确性补丁」加回来——它不是性能优化。
- **只支持 0.1.6**：`neoforge.mods.toml` 钉的是 `[0.1.6,0.1.7)`。兼容层读的是
  `RivetHostIndex`、`BalloonLoadReconciler`、连杆 NBT 布局这些**没有稳定契约**的内部结构，
  上游换个版本就得重新核对。
- **蓝图保存时仍会丢一次「当时什么也没存」的情况**：如果铆钉/气球所在的子层级在保存那一刻还没被
  Photomancy 收录，兼容层拿不到 frame，只能跳过——详见 `BLUEPRINT_COMPAT.md` 的验收日志。
- 动画类的东西（铆钉的插入动画、气球的摆动）不会被蓝图保留，这是蓝图格式本身的边界。
- **性能**：2.0.0 不含任何性能补丁。上游 0.1.6 自己处理了「每 tick 全量扫描」；本 mod 的
  热路径影响只有蓝图事件（保存/粘贴时触发）。

## 图标与许可

`src/main/resources/logo.png`（512×512）取自原模组 jar 内的 `logo.png`，右下角叠了一个红色 `fix`。
生成脚本见 [`tools/make_icon.py`](tools/make_icon.py)。图标是原模组美术的**修改版**，因此按 MIT 的
署名要求，jar 内 `META-INF/NOTICE` 带有完整归属声明与 MIT 全文。

本补丁自身：**MIT**（jar 内 `LICENSE`）。

| 组件 | 协议 | 本仓库的处理 |
|---|---|---|
| Create: Coasters Simulated（SilverGold） | MIT | 图标为其衍生作品，已在 `META-INF/NOTICE` 附版权声明与 MIT 全文 |
| Sable（RyanHCode） | PolyForm Shield 1.0.0 | 仅在运行期链接，**不再分发**任何部分 |
| Sable Photomancy（Rew1nd） | MIT | 只调用其公开扩展点，**不包含也不分发**其代码 |
| Create（simibubi） | 代码 MIT / `assets/` All Rights Reserved | 不包含任何代码或资产 |

上传 CurseForge 时请把项目协议设为 **MIT**。
