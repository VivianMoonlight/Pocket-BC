# 立绘脚本兼容补充包

随 APK 发布的本地兼容包，版本见 manifest.json，仅适用于 R133 和 manifest.json 指定的 Echo 提交。
它为静态目录补充经过审查的绘制逻辑，不承担旧存档转换或道具操作。

| 适配 | 范围 | 实现 |
| --- | --- | --- |
| Echo 白大褂 | Cloth、ClothOuter 及其叠加槽位 | 自动／不伪装／保持伪装时选择静态图层和姿势 |
| Echo 呢子大衣（资产名“大衣”） | 同上 | 根据模式、姿势和受限状态选择图层透明度 |
| Echo 白色礼服 | Cloth 及其叠加槽位 | 根据 Suspended 效果调整裙身图层的姿势；配套丝巾作为静态资产支持 ClothAccessory、长袖子_Luzi |
| Echo 玛丽珍皮鞋 | Shoes | 按鞋型调整鞋底位置 |
| Echo 标志纹饰 | BodyMarkings | 姿势和部位开关控制遮罩 |
| Echo 运动套装上衣 | Cloth、Bra | 嵌套文字配置绘制弧形文字 |
| Echo 身体论文 | BodyMarkings2_Luzi | 15 个文字子配置及姿势隐藏 |
| Echo 单／双渐变 | 额外头发_Luzi | 头发遮罩、位置、大小和旋转 |
| Echo 交领右衽 | ClothAccessory | WebGL 着色与身体遮罩绘制延长部分 |
| Echo 油光袜 | Suit、SuitLower | 按需合成剪裁遮罩，复用原生图片缓存生命周期 |
| 原版 SteampunkWings | Wings 及继承该图片组的槽位 | 允许既有、已校验的 BeforeDraw 初始化第零帧；不调用 ScriptDraw |

manifest.json 记录包版本、BC 版本、Echo 提交、具体资产、适配方式和来源文件哈希。
Echo 哈希采用 Git blob SHA-1，原版脚本采用 scripts.json 中的 SHA-256。
提取器在写入目录前调用与运行时相同的校验：包版本必须与生成目录一致，适配 ID、槽位和资产绑定必须完整且唯一。
未重新生成目录、遗漏已登记组件、重复绑定或残留未移植的 ScriptHooks 都会报错。
adapters.js 只包含明确登记的本地函数；没有远程脚本加载器、eval、计时器或交互事件注册。

Echo 绘制回调移植自清单所指向的源码，drawing.js 和 gl.js 中的遮罩及 WebGL 辅助类来自同一提交的 src/lib/draw，按 CC BY-NC-SA 4.0 分发。
白色礼服的上游设计署名为 01musume。
作者归属、完整许可证和例外见 [Echo NOTICE](../echo/NOTICE.md)、[许可证](../echo/LICENSE.txt)
及 [附录](../echo/LICENSE-APPENDIX.md)。移植保留绘制所需的返回值和画布操作，函数在本地适配文件中重新组织。固定半幅遮罩 half-black.png 为本地生成的 250×1000 黑色矩形。

## 添加或更新适配

1. 固定上游提交，核对来源和资产许可。记录源码哈希及准确的槽位、资产名。
2. 审查函数的依赖和副作用。当前支持已登记的 BeforeDraw 和 AfterDraw；ScriptDraw、网络、会话及游戏角色更新不在渲染能力内。
3. 先判断能否生成纯数据：普通静态定义直接提取；资源别名、已审核的编辑器剥离、冻结初始状态的常量结果由生成器处理并绑定来源哈希。检查颜色槽、尺寸、旋转、透明度范围和图层条件是否保留。仅生成数据时不增加本地回调或补充包版本。
4. 确实依赖运行时绘制计算时，才在 adapters.js 编写本地回调，在 manifest.json 登记绑定，并同步更新清单版本与 adapters.js 接受的包版本。回调返回绘制覆盖值或写入临时画布；需要文本初始化时仅处理渲染器持有的本地副本，不修改输入快照或发送角色更新。提取器扫描组件目录，按槽位和资产名匹配已登记回调并替换为适配 ID；其余运行时函数仍会被拒绝。源码变化须确认是否影响行为绑定或数据修正规则，不能仅因属于已审核文件就新增客户端补丁。
5. 运行上游对照测试、浏览器实际绘制测试及 Android 单元测试／构建。有设备时补充 WebView 验证。

在仓库根目录运行（路径指向固定提交的本地源码及校验资料）：

```powershell
node --experimental-vm-modules scripts/generate-echo-portrait.mjs _local-artifacts/echo-clothing-ext _local-artifacts/echo-tree.json _local-artifacts/portrait-probe/remote
node scripts/verify-portrait-supplement.mjs _local-artifacts/echo-clothing-ext _local-artifacts/echo-utils/src/Tools/index.js
node scripts/verify-stocking-masks.mjs _local-artifacts/echo-clothing-ext
node scripts/verify-portrait.mjs
.\gradlew.bat testDebugUnitTest assembleDebug
```

更新 BC 或 Echo 时必须重新审查源码哈希并运行以上验证。抓源的目录回退不会绕过本包的版本检查。
已知限制：静态单人绘制；没有完整动画、双人场景或完整 Echo 游戏逻辑。

## 资源与成图持久化

Android 在应用私有数据目录 `files/portrait-resources-v1` 中保留最多 256 MiB 的资源，按最近读取时间淘汰。
Echo 图片按 Git blob 哈希复用，与镜像地址无关；原生资源按 BC 版本、路径及脚本哈希隔离。
读取时检查文件校验和及已知的上游哈希；失败响应不缓存，同一资源的并发请求合并。
数据目录不可写时仍返回下载结果。旧 `cache/portrait-resources-v1` 的资源在读取时校验并迁移，避免重复下载。
原始素材和成图都独立于系统临时缓存，应用重启或清理缓存后仍可读取；首次获取缺少的素材需要网络。
完整渲染成功后，在 `files/portrait-frames-v1` 原子保存 500×1000 PNG 和校验和，成图总额上限为 64 MiB，按最近读取时间淘汰。
成图键包含查看账号、完整绘制快照，以及 BC/Echo 数据与本地绘制实现的指纹。JSON 对象键排序不影响复用，数组绘制顺序保持不变。
同一外观再次打开时直接读取成图并用原生 Image 显示，不创建 WebView；重启后仍有效。
颜色、姿势、文字、尺寸等绘制属性变化时动态选择新成图，缺图才加载渲染器和所需素材，成功后再保存。
部分显示、超时或失败不写成图，避免将缺失外观长期保存。清除应用数据或卸载应用会删除这些私有文件。
版本指纹在进程启动时于 IO 线程准备；绘制数据包仍沿用下次启动应用的更新规则。新版本自然使用新成图键。
渲染器在穿搭快照之间保留最多 32 MiB 的已解码图片，失败及未完成的图片在下一次快照时重新请求。
这个上限约束跨快照保留量，单次复杂穿搭的绘制峰值可能更高。
关闭立绘后，应用保留至多一个空闲 WebView，复用脚本、资产目录和已解码图片；使用应用 Context，避免持有 Activity。
归还时清除人物、动画数据、画布、待处理快照和诊断中的人物信息。失败的渲染器直接销毁；内存压力或应用退到后台时释放空闲渲染器。
不同资源的下载互不等待，只有完全相同的资源键合并请求，避免慢图片通过锁碰撞阻塞缓存命中。
诊断状态包含 `runtimeReadyMs`（脚本与目录初始化）及 `elapsedMs`（本次快照到当前状态），可分别定位初始化与绘制等待。
运行 `$env:PORTRAIT_CACHE_PROBE='1'; node scripts/verify-portrait.mjs` 可验证五次清理后重开：人物已清空、画面一致、零新增资源请求并记录耗时。
原生界面通过限定页面来源的 WebMessagePort 接收完成事件，按快照 revision 拒绝过期结果，不使用定时轮询或 JavascriptInterface。
运行 `$env:PORTRAIT_FRAME_PROBE='1'; node scripts/verify-portrait.mjs` 验证颜色动态更新、完成事件和 PNG 导出。
资源目录只保存公共素材；成图目录按账号隔离命名，不保存穿搭 JSON、账号凭据或原始账号数据。
