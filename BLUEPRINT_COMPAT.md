# Sable Photomancy 蓝图兼容层

> 1.0.1 加入，**2.0.0 起是本 mod 唯一的功能**（性能补丁已删除，最后一个带优化的版本是 1.5.2，
> 见 git tag `archive-1.5.2-perf`）。字段设计、保存/粘贴流程与验收日志都在本文件。

## 症状

用**手持蓝图工具**框选一片过山车建筑保存，再粘贴回来，会出现三件事：

1. **铆钉全没了。** 不是没存上，而是存了、贴出来了、然后在下一瞬间被自己拆掉。
2. **两节连成列车的过山车车厢散架了。** 车厢还在，连杆没了。
3. **气球不再吸附到原来的方块上**，跑到半空或者贴在一个错的位置。

游戏日志里**没有任何报错**——这三件事都是静默丢数据。

## 根因

Photomancy 的蓝图格式只搬运**两样东西**：子层级里的方块状态 + 方块实体 NBT。
所有「指向别处」的引用都原样复制，既不换算坐标，也不重映射 uuid。而 Create: Coasters
Simulated 恰好有三处数据是这么存的：

| 症状 | 数据存在哪 | 为什么坏了 |
|---|---|---|
| 铆钉消失 | `RivetBlockEntity` 的 `HostPos`（**绝对世界坐标**）+ `HostSubLevel` | 粘贴后 `HostPos` 还指着原来那片地。`applyHostLock` 一看那儿是空气 / 不是它该吸的方块，走 `scheduleTeardownIfInvalidHost` → `RivetRemoval.onHostBroken` → 铆钉被拆 |
| 车厢连杆 | `ServerSubLevel.getUserDataTag()` 里的 `simulatedcoasters:cart_train_links` | Photomancy 的 `SableBlueprintExporter.saveSubLevel` 把 `SubLevelData.extraData` 写死成空 `CompoundTag`，**子层级的 user data 从来没被导出过**。而且连杆两端存的是 sublevel uuid，粘贴必然换新 uuid |
| 气球错位 | `BalloonBlockEntity` 的 `HostSubLevel`（raw uuid） | 气球靠这个 uuid 找宿主子层级（`resolveHostSubLevel`）。uuid 换新之后解析不到宿主，`relocateHostKey` 也就没有起点 |

另外记一笔：车轨锚点之间的 `AnchorPeerCurves` 也是按绝对坐标存的，同理会坏，
但这次没有纳入修复范围（见「已知边界」）。

## 修复方式

全部走 Photomancy 自己提供的扩展点（`SableBlueprintMapperRegistry` /
`SableBlueprintEventRegistry`），**不 Mixin 对方的类**，也**不改动任何一边的 jar**。
顺序与 Photomancy 自带的 Create / Simulated / Aeronautics 兼容层一致。

| 文件 | 作用 |
|---|---|
| `blueprint/RivetHostBlueprintMapper.java` | 存盘时把 `HostPos` 换成蓝图内的方块引用（宿主在某个子层级里）或相对蓝图原点的偏移（宿主是世界方块）；删掉 `HostSubLevel`。**在方块实体 load 之前**把 `HostPos` 解回新坐标，于是「宿主是否还在」的检查看到的是正确值 |
| `blueprint/BalloonHostBlueprintMapper.java` | 存盘时把 `HostSubLevel` 换成宿主子层级的**源 uuid**；load 前用放置会话的 uuid 映射表换回新 uuid（气球的 `HostPos` / `HostLatticePos` 本来就是 plot 局部坐标，不用动） |
| `blueprint/CoasterBlueprintEvent.java` | 存盘时把每节车厢的连杆记录进 `globalExtraData`（伙伴不在蓝图里的连杆直接丢掉）；放置时用 `subLevelUuidMap()` 把两端 uuid 重映射后写回 user data，并**下一 tick**（等 `finalizePlacement` 把姿态 teleport 到位之后）重建铆钉的物理锁、注册回 `RivetHostIndex`，对气球跑 `BalloonLoadReconciler.reconcileBalloon(..., snapPose=true)`，最后 `CoasterCartTrainLinkConstraint.restorePersistedLinks` |
| `blueprint/CartTrainLinks.java` | 读写 `simulatedcoasters:cart_train_links`（含旧版单条 `cart_train_link`） |

为什么铆钉的坐标非要在 `beforeLoadBlockEntity` 里改：`RivetBlockEntity.loadAdditional`
读到什么就是什么，后面的 `onLoad → applyHostLock` 会立刻拿它去判存活。等到
`afterLoadBlockEntity` 再改就已经晚了。

## 日志确认

注册成功会在启动阶段打一条：

```
[simcoastersfix] blueprint compatibility armed: rivet host, balloon host, cart train links
```

没装 Photomancy 时：

```
[simcoastersfix] sable_schematic_api not installed, blueprint compatibility disabled
```

然后每次**保存**：

```
[simcoastersfix] blueprint save: frames=12 rivetSubLevels=2 balloonSubLevels=1 cartLinkSubLevels=1
```

每次**粘贴**：

```
[simcoastersfix] blueprint place: subLevels=12 cartLinks=2 (physics re-attach queued)
[simcoastersfix] blueprint place: re-attached rivets=2 balloons=1
```

这几条数字就是验收标准：`rivetSubLevels` 为 0 说明铆钉子层级根本没进蓝图
（那是导出侧的问题，不在本层能修的范围）；`re-attached rivets` 为 0 说明贴出来了但没修上。

## 材料成本：为什么打印曾经是免费的（2.1.0）

Photomancy 的胶片打印机（`survival/filmprinter/BlueprintMaterialQuoteCompiler`）会遍历
`blueprint.subLevels()` 里的每一个方块，交给 `BlueprintBlockCostRules.quotePlacement` 换成物品。
默认规则只有一行：

```java
Item item = payload.state().getBlock().asItem();
return item == Items.AIR ? CostQuote.empty(...) : CostQuote.of(new CostLine(...));
```

Coasters 的方块**大多没有 BlockItem**——物品是普通 `Item`，因为方块是走子层级生成、不从背包放：

```java
// SimulatedCoasters.java
COASTER_CART = ITEMS.registerItem("coaster_cart", CoasterCartItem::new, ...);   // 不是 BlockItem
RIVET        = ITEMS.registerItem("rivet", Item::new, ...);                      // 不是 BlockItem
RED_BALLOON  = ITEMS.registerItem("red_balloon", Item::new, ...);                // 不是 BlockItem
COASTER_ANCHORPOINT = ITEMS.registerSimpleBlockItem("coaster_anchorpoint", ...); // 这个有
```

所以 `asItem()` 全是 AIR ⇒ 报价为空 ⇒ 蓝图里草方块收钱、车厢/铆钉/气球一分不收。

修法是用 Photomancy 公开的成本规则表（Create 机械、Copycats 的兼容也是这张表）：

```java
BlueprintBlockCostRules.register(block, rule);   // 1 方块 = 1 物品，CostTiming.IMMEDIATE_BLOCK
```

三条规则的 id 都用 `simcoastersfix:` 前缀，`quoteNbtLoad` 返回空（和默认规则一致）。
数量口径对齐 mod 自己的消耗：`CoasterCartItem` 放一辆车 `shrink(1)`、`RivetPlacePayload` 一个铆钉
`shrink(1)`、`BalloonAttachInteraction` 一个气球 `shrink(1)`。

**未覆盖**：`coaster_track_material`。它继承 Create 的 `TrackBlock`，没有对应物品；而 1 个
`coaster_track` 放的是一整段贝塞尔曲线（几十个轨道方块）。按方块收 = 几十倍超收，按段收需要
蓝图保留「段」的边界，上游才有这个信息。

## 已知边界

- **世界方块宿主的铆钉救不回来。** 蓝图只含子层级内容，普通世界方块不会被复制。
  这类铆钉会退化用「相对蓝图原点的偏移」定位，那块地如果是空气，它仍然会被正常拆除
  （这是原模组对「宿主没了」的正确行为，不是丢数据）。
- **车轨锚点之间的曲线连接（`AnchorPeerCurves`）没做。** 它的对端坐标、链式提升的
  `ChainLiftSpan` / `ChainLiftFirstHop`、染色键全是绝对值，需要单独一套重映射。
  如果粘贴后出现「锚点还在但曲线断了」，就是这条。
- **只在 Photomancy 走 `SableBlueprintEvent` 的路径上生效。** 完整放置器
  (`SableBlueprintPlacer`) 和生存模式的增量放置器 (`SableBlueprintIncrementalPlacer`)
  都会发这些事件，两条路径都覆盖。
- 存盘格式里新加的自定义键都带 `simcoastersfix:` 前缀，Coasters 的 `loadAdditional`
  不认识会直接忽略；旧蓝图（1.0.0 存的）不受影响，只是没有这个修复。

## 回归测试清单

1. 摆两块贴在一起的子层级方块 → 各放一个铆钉 → 蓝图工具框选 → 存 → 在别处粘贴。
   期望：铆钉还在，并且吸在对应的新方块上（日志 `re-attached rivets=N`）。
2. 两节过山车车厢连成列车 → 框选保存 → 粘贴。
   期望：车厢仍是列车，连杆还在（日志 `cartLinks=N`）。
3. 在子层级方块上放一个红气球 → 保存 → 粘贴。
   期望：气球贴在对应方块上，不是浮在半空。
4. 不装 Photomancy 启动一次。
   期望：正常启动，日志说 `blueprint compatibility disabled`。
5. 蓝图里放一节车厢 → 胶片打印机看材料清单。
   期望：出现 `coaster_cart`，数量 = 蓝图里的车厢方块数（2.1.0 之前这一项是 0）。
