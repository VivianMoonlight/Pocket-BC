# Pocket BC

Android 原生 Bondage Club 客户端，提供账号会话、房间和角色交互、消息时间线及持久化发送队列。

> **开发中 · 未完成版本**：功能、界面和兼容性仍在调整，当前发布仅用于体验和反馈，不代表稳定版。

## 下载预览版

前往 [GitHub Releases](https://github.com/VivianMoonlight/Pocket-BC/releases)，选择标记为 **Pre-release** 的版本，在 **Assets** 中下载 APK。需要 Android 8.0（API 26）或更高版本。

预览版提供可安装的 debug APK，使用调试签名；它不是正式签名的生产版本。不同构建的签名可能不同，覆盖安装可能失败。卸载会清除本地数据，请先保存需要保留的内容。

发布由仓库的 `Android pre-release` 工作流完成：推送 `v*-alpha.*` 标签后运行单元测试、构建 APK，并创建附带 APK 和 SHA-256 校验文件的 GitHub Pre-release。发布说明会列出验证范围和已知限制。

## 当前架构

项目包含 16 个 Gradle 模块，以 [settings.gradle.kts](settings.gradle.kts) 为准：

- `app`：依赖装配、Activity、前台服务、Android 通知、资源访问和 WorkManager。
- `core/*`：稳定模型、协议、socket 和共享 UI。
- `domain/*`：消息、同步、BC 资源/文本、session 和 character 业务。
- `data/storage`：Room/DataStore 持久化适配。
- `feature/*`：Compose 页面、ViewModel、展示状态和用户意图。

职责、数据流和现有用户操作模型见 [当前架构](docs/current-architecture.md)，存储、发送恢复和兼容性契约见 [存储架构](docs/storage-architecture.md)。

## 关键约束

- `BcSessionForegroundService` 拥有活跃 session；UI 从同一个 runtime 读取状态。
- 角色操作通过统一目录、权限检查和 `BcCharacterInteractions.sendInteraction(...)` 发送；Echo 使用编译期内置、可静态验证的动作快照。
- Room 是消息时间线和已读状态的权威来源；发送前检查账号、房间和连接归属。
- 数据库迁移、已发布 DataStore key、BC 协议及文本资源差异所需的解析 fallback 属于兼容性契约。

## 构建

使用 **JDK 21** 和仓库自带的 Gradle Wrapper。保留原项目工具链：Gradle `9.6.1`、Android Gradle Plugin `9.2.1`；版本配置见 `gradle/wrapper/gradle-wrapper.properties` 和 `gradle/libs.versions.toml`。

1. 将本目录（包含 `settings.gradle.kts`）作为项目根目录，用 Android Studio 打开，或直接在此运行命令行。
2. 安装 Android SDK Platform 36，在 Android Studio 中将 Gradle JDK 设置为 JDK 21；命令行构建时将 `JAVA_HOME` 指向 JDK 21，并确认 `java -version`。
3. 在项目根目录创建 `local.properties`，填写自己的 SDK 目录。例如 Windows：

   ```properties
   sdk.dir=D:/Android/Sdk
   ```

   macOS/Linux 请替换为本机 SDK 的实际绝对路径。也可以使用已配置的 `ANDROID_HOME`。该文件与机器相关，不包含在源码归档中。
4. 首次构建需要联网下载 Gradle、构建插件及依赖，并通过 SDK Manager 安装所需 SDK 组件、接受许可。此归档不包含依赖缓存或 Android SDK。

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS/Linux：

```bash
chmod +x gradlew
./gradlew testDebugUnitTest assembleDebug
```

仅生成 Debug 安装包可运行 `./gradlew :app:assembleDebug`（Windows 使用 `.\gradlew.bat :app:assembleDebug`）。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

项目使用 compile SDK 36、target SDK 35、min SDK 26。Debug 包使用 `.debug` applicationId suffix，不会覆盖正式安装。设备上的通知、导航和生命周期行为需要另外验证。

初始源码归档历史见 [归档说明](ARCHIVE.md)，其中环境和验证结果仅描述当时的归档。
