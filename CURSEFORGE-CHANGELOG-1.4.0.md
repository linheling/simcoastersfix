# Coaster Perf Fix 1.4.0 —— 自 1.0.1 起的全部更新

上一版公开发布是 **1.0.1**。这之后走了四个版本，全都围绕同一件事：把「每 tick 重做一遍」
变成「只在变化时做」。每一项都有独立配置开关，**默认全部开启**，关掉即回到上游行为，方便 A/B 排障。

## 1.4.0 —— 车在轨道上贵几倍，原因找到了

实测（真实客户端 + 采样剖析）：车吸附在过山车轨道上时，服务端 tick 线程最大的单项开销
**不是物理**，而是贝塞尔曲线手柄长度的重复解析——同期 `Rapier3D.step` 只有 12–18%，曲线那组到 30%。

- 曲线采样每个点要两个控制点，每个控制点解析一次手柄长度；而 `getLengthAtEndpoint` 每次
  还会**无条件**先算一次回退值，那个回退值被上游自己的 mixin 包着、又回递归查一遍同一份数据。
  等于一次端点查询查两遍，每个采样点约四遍。
- 每一次这样的查找 = 两次 `synchronized` 弱引用哈希表查询 + 拼一个 `"<long>:<long>"` 字符串键
  + 一次字符串哈希表查询。
- 现在按「曲线 + 端点」缓存，并用上游自带的 revision 计数器精确失效：轨道被编辑后下一次调用
  就生效，**不引入任何时间滞后**。对应开关 `curveHandleLengthCache`。
- 同一会话内热切换开关做 A/B（不重启客户端）：
  上游那段查找的采样数从 **219 / 328 变成 0 / 0**（一个都不剩）；
  曲线组总样本从 249 / 351 降到 84 / 93（剩下的是缓存命中本身）；
  MSPT p95 从 14.12 / 14.63 ms 降到 13.60 / 11.76 ms，p99 从 15.99 / 16.90 ms 降到 15.46 / 13.42 ms。

## 1.3.0 —— 气球路径的物理管线读取

一次物理钩子内，力与冲量只是入队、步进时才生效，所以同一个刚体的速度在这次调用里不会变。
同一份速度原本被读取七次（含气球与宿主的线速度/角速度），现在每个刚体最多读一次。
调用方仍然拿到自己的副本，物理行为不变。开关 `balloonPhysicsMemo`。

## 1.2.0 —— 气球恢复扫描 + 每 tick 内查询去重

- 上游把「每 tick 都会变的 gameTime」当成「只跑一次的标记」（`last != gameTime` 恒真），
  导致气球恢复扫描每物理 tick 遍历**全部子层级**，只为重新登记早就登记过的气球。
  现在默认 40 刻（2 秒）一次，开关 `balloonRestoreThrottle` / 间隔 `balloonRestoreIntervalTicks`。
- 气球钩子分两趟遍历活跃气球，两趟都各解析一次 气球 → 子层级 → 方块实体 → 宿主，
  同一次调用内的重复现在直接复用（开关 `balloonTickMemo`）。
- 列车连杆每条每 tick 解析两次车厢 A / B，同一 tick 内的重复同样去掉（开关 `trainLinkTickMemo`）。

## 1.1.0 —— 扫描的 section 级剪枝

轴承扫描原本只用 `hasOnlyAir()` 剪枝，而它只挡**纯空气** section：section 里只要有一个方块
（轨道、石头、羊毛），照样跑满 4096 格 `getBlockState`。现在同一个判断上再叠一层调色板判断
（`LevelChunkSection.maybeHas`），没有目标方块的 section 整段跳过。

结果完全等价（被跳过的 section 本来就不产出命中）。开发环境实测：不含目标方块的 plot 上剪掉
100%，整轮累计 82,845,696 → 1,646,592 次方块读取（省 98.0%）。

## 新增的配置项（旧配置会自动补上默认值）

```
balloonRestoreThrottle           = true   # 气球恢复扫描是否限流
balloonRestoreIntervalTicks      = 40     # 气球恢复扫描间隔
balloonTickMemo                  = true   # 气球 tick 内重复查询去重
trainLinkTickMemo                = true   # 连杆 tick 内重复查询去重
balloonPhysicsMemo               = true   # 气球 tick 内物理管线速度读取去重
curveHandleLengthCache           = true   # 贝塞尔手柄长度缓存
```

1.0.1 的五项（`bearingScanTtl*`、`rivetCheckIntervalTicks`、`rivetSelfHostCheckIntervalTicks`、
`trainLinkRestoreIntervalTicks`）数值和语义都没变。

## 升级注意

- **存档与配置文件兼容**：不需要删配置，新键会自动补默认值。
- **启动自检从 4 项扩到 11 项**，启动日志应当出现：
  `[simcoastersfix] self-check OK: 11/11 mixin targets active`。
  看到 `self-check FAILED` 说明上游版本对不上，此时补丁不会带病运行。
- 依赖不变：Minecraft 1.21.1 / NeoForge 21.1.x、Create 6.0.10+、Sable 2.0.0+、
  Create: Coasters Simulated 0.1.5。
- 专用服务器只需装在服务端；**单人游戏**的集成服务端跑在客户端进程里，所以那一份要放进客户端。
