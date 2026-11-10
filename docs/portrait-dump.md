# 立绘 dump 与重放

`portrait/dump.js` 记录当前 WebView 的立绘诊断信息，`scripts/dump-portrait.mjs` 通过本机 DevTools 导出。它不重新加载立绘、不修改角色、不发送游戏操作，也不增加应用网络接口。

## 从手机导出

安装 Debug APK，在手机打开一个角色的立绘并保持页面。使用已有 Android SDK：

```powershell
$adb = 'D:/Android/Sdk/platform-tools/adb.exe'
$portraitPid = (& $adb shell pidof dev.vivian.mobilebc.debug).Trim()
& $adb forward tcp:18765 "localabstract:webview_devtools_remote_$portraitPid"
node scripts/dump-portrait.mjs --out _local-artifacts/portrait-dump-001
& $adb forward --remove tcp:18765
```

输出目录必须不存在。多个立绘页面同时打开时，工具列出页面 ID；用 `--page ID` 选择。`--port` 可指定其他本地转发端口。应用重启后 PID 会变化，需要重新建立转发。

输出包括 `dump.json` 和 `canvas.png`。报告包含：

- BC 版本、Echo 提交与 WebView User-Agent。
- 当前状态、遗漏条目和缺图数。
- 最近 64 条事件：页面启动、脚本加载、Echo 准备、运行时就绪、外观应用、状态变化和渲染异常。
- HTML、body、canvas 的实际显示尺寸及视口尺寸。
- 画布固有尺寸、非透明像素数和读取错误。
- 当前图片缓存中各资源的地址和加载状态。

`checks.hasLayout=false` 且 `hasPixels=true` 表示角色已经画出，但页面布局不可见，能定位曾出现的零高度问题。`imagesFailed` 与 `imagesPending` 分别表示失败及尚未完成的图片；这里只报告缓存状态，不把它猜成某个 HTTP 或解码错误。浏览器画布截图不包含 Compose 外层的遮挡、透明度或手机界面，必要时仍需对比真机截图。

默认不导出输入外观。需要重放时加 `--include-scene`：只保存 assetFamily、activePose 和 appearance，不包含顶层 memberNumber、昵称、账号、聊天或登录凭据。外观的原始 Property 会保留，以复现绘制问题；其中可能有自定义文字及所有者编号，分享前应检查。

## 本地重放

```powershell
$env:PORTRAIT_REPLAY_DUMP = '_local-artifacts/portrait-dump-001/dump.json'
$env:PORTRAIT_REPLAY_OUT = '_local-artifacts/portrait-replay-001'
node scripts/verify-portrait.mjs
Remove-Item Env:PORTRAIT_REPLAY_DUMP, Env:PORTRAIT_REPLAY_OUT
```

输入必须由 `--include-scene` 导出。工具使用已有的 Chrome 回归环境和资源缓存，保存重放后的 `dump.json`、`canvas.png`。完成状态必须为 ready 或 partial，且布局、像素均有效，否则保存报告后以非零退出码结束。partial 仍表示有未支持条目或缺图，并不等于完整兼容。

BC／Echo 版本必须与 dump 一致；动态数据包需通过 `ECHO_DATA_PACK` 指向当时的完整包。相同版本仍可能受到平台、字体和网络差异影响，桌面重放不能代替 Android WebView 验证。

不设置 `PORTRAIT_REPLAY_DUMP` 时运行完整回归，包括诊断模块的零布局识别、外观快照导出、错误事件和容量限制。dump 按需扫描画布像素，不在每帧执行。

## 逐件目录检查

```powershell
$env:ECHO_SWEEP_OUT = '_local-artifacts/echo-render-audit-new'
node scripts/verify-portrait.mjs
Remove-Item Env:ECHO_SWEEP_OUT
```

输出目录必须不存在。工具使用默认角色、站姿和各资产的默认选项，依次加载完整 Echo 目录；每完成一项就追加 `results.jsonl`，非 ready 状态另存对应索引的 dump。可用 `ECHO_SWEEP_START` 和 `ECHO_SWEEP_COUNT` 限定范围，便于复验某一段。也可设置 `ECHO_SWEEP_ASSETS` 指向一个 JSON 字符串数组文件，按 `组名/资产名` 精确选择条目，例如 `["Cloth/小西装S","Socks/丝袜2"]`；未知条目会报错。输出索引始终对应完整目录。

状态异常、布局或画布为空、资源尚未完成时也保存 dump；检查全部条目后以非零退出码报告失败，避免把“遍历完成”当成“全部通过”。

这些失败 dump 包含检查工具生成的外观场景，可直接作为 `PORTRAIT_REPLAY_DUMP` 输入；场景使用合成角色数据，不读取手机账号。

这项检查定位默认场景中的缺图、未支持条目和渲染异常。ready 不代表所有姿势、体型或选项组合均已通过，也不能证明被其他衣服遮挡的图案可见；复杂适配仍需独立的像素与行为回归。

设置 `ECHO_SWEEP_POSE` 为姿势名称的 JSON 数组可检查其他姿势，例如 PowerShell 中 `$env:ECHO_SWEEP_POSE='["AllFours"]'`。结果逐项保存所用姿势；未设置时使用默认站姿。完成后使用 `Remove-Item Env:ECHO_SWEEP_POSE` 清除设置。

## 镜像规则检查

运行 `node scripts/verify-echo-mirrors.mjs` 可独立检查实际打包的 Echo 适配器：单向和多级复制、复制组专属资产、noMirror、显式资产列表、价格与原组隔离，以及图片组、绘制回调、扩展配置和直接别名继承。该检查使用合成定义，不验证上游交互菜单、手动注册配置或所有真实资产的视觉效果；原生躯干双向注册另由 `EchoGithubUpdateTest` 验证。
