# 更新日志

> English release notes (for the CurseForge / Modrinth changelog field): [`CHANGELOG.en.md`](CHANGELOG.en.md)

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
