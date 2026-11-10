# 源码归档说明

本页仅保留初始源码归档历史，文件数量、工具环境和验证结果不代表当前工作区。当前构建方式见 [README](README.md)。

此工程由上传的 `mobile-bc.zip` 清理得到。解压后，以包含 `settings.gradle.kts` 的 `mobile-bc/` 目录作为项目根目录。

## 保留内容

- `settings.gradle.kts` 声明的全部 16 个模块，包含全部 428 个源码、资源和测试文件。
- 原有 Gradle 构建脚本、版本目录、Wrapper JAR、Windows/Linux/macOS 启动脚本。
- 测试 fixture、内置数据快照及其生成脚本；即使某些 fixture 最初由脚本生成，它们仍是现有测试所需的输入，故完整保留。
- 原有架构文档、项目工作约定和维护脚本。

## 剔除内容

| 类别 | 文件数 | 说明 |
| --- | ---: | --- |
| 根目录及各模块的 `build/` | 21,941 | 中间文件、编译类、APK/AAR、KSP 生成代码、测试报告等可重新生成的产物 |
| `app/debug/` | 2 | 导出的 Debug APK 及其元数据 |
| 本地验证日志 | 3 | 根目录 `.codex-*-validation.log` |
| 目录快照 | 2 | `clean-tree.txt`、`filetree.txt` |
| 本机 SDK 配置 | 1 | `local.properties`；构建前按 README 在本机重新配置 |
| 独立业务数据导出 | 1 | `craft187577-2026-06-00.json`；包含 crafts 导出数据，未发现工程、源码或测试引用 |

共剔除 21,950 个文件，未压缩大小约 368 MiB。`mods/` 和 `platform/` 在原包中只有旧构建产物，未被当前 `settings.gradle.kts` 引用，因此清理后不再包含这两个目录。

## 必要的整理改动

- README 补充 JDK 21、Android SDK 36、本地 SDK 配置、跨平台构建命令及 APK 输出位置。
- AGENTS.md 将固定的 Windows 项目位置与 SDK 位置改为相对项目根目录及本机配置。
- `.gitignore` 增加本地验证日志和 Kotlin 缓存规则。
- 为 `gradlew` 设置 Unix 可执行权限。

业务源码、测试、资源、依赖版本及构建脚本均保持原样。原项目保留文件中，461 个文件与上传包逐字节一致；仅以上三个文本配置/说明文件有内容修改，另新增本说明。

## 验证结果与限制

已检查全部 16 个模块均存在，60 条项目依赖均指向保留模块，依赖无环且均可从 `app` 到达；107 处版本目录别名引用有效；24 个 XML 文件可解析；Wrapper JAR 完整且包含入口类，`gradlew` 通过 shell 语法检查。全部源码、资源及测试文件与原包逐字节一致。

尝试运行了 `./gradlew testDebugUnitTest assembleDebug`，但在下载原项目指定的 Gradle 9.6.1 时遇到 `Network is unreachable`，未进入项目编译和测试阶段。当前环境只提供 JDK 17，未发现 Android SDK。**本次完成的是源码清理与静态完整性检查，不能视为编译或单元测试通过。**

请按 README 在具备 JDK 21、Android SDK Platform 36 且可下载依赖的环境执行上述构建命令。保留原项目的 Gradle 9.6.1 / AGP 9.2.1 工具链，未因归档而升级或降级。归档不包含 SDK、Gradle 发行包或依赖缓存，首次构建需要联网。
