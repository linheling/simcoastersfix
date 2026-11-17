# libs/ — 编译期依赖，不进仓库

`build.gradle` 用 `compileOnly files('libs/...')` 逐条写死引用这里的四个 jar。
它们全是第三方产物，再分发各有条件，所以 `libs/*.jar` 已被 `.gitignore` 排除——
**clone 下来这个目录是空的，直接 `gradlew build` 会在解析符号时失败。**

| 文件 | 用途 | 协议 |
|---|---|---|
| `simulatedcoasters-0.1.6.jar` | 补丁目标（`CoasterCartBearingTickCache` 等四个类） | MIT |
| `sable-2.0.6.jar` | `ServerSubLevel` 等类型 | PolyForm Shield 1.0.0 |
| `sable-companion-common-1.6.0.jar` | `ServerSubLevel.getLevel()` 声明在这个 companion 接口上 | PolyForm Shield 1.0.0 |
| `sable-photomancy-1.0.1.jar` | 蓝图兼容层的 `api.blueprint.*` 扩展点；**只在编译期需要** | MIT |

获取方式：

1. 前三个从 CurseForge / Modrinth 下对应版本，把 jar 原名复制进来即可。
2. `sable-companion-common` 不是独立发布的——从 sable jar 里解出来：

   ```powershell
   Expand-Archive libs\sable-2.0.6.jar -DestinationPath _sable
   Copy-Item _sable\META-INF\jarjar\*companion-common*.jar libs\sable-companion-common-1.6.0.jar
   Remove-Item -Recurse _sable
   ```

这些 jar **不会被打进产物**，`tools/make_icon.py` 也只是从 `simulatedcoasters-0.1.6.jar`
里读底图。重新生成图标前先把它放好。
