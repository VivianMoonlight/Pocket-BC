# Liko-AFC 心形锁接入

协议核对来源：[BC-AFC，提交 7377a48c244ff9ef30eaa6eab63a6baeda746880](https://github.com/awdrrawd/BC-AFC/tree/7377a48c244ff9ef30eaa6eab63a6baeda746880)。主要文件为 `features/heartlock/permissions.js`、`lock.js`、`snapshot.js`、`net/heartlock.js` 和 `compat/r132-heartlock.js`。上游为 MIT 许可，作者 Likolisu；本地实现按协议独立编写。

已实现向同房其他佩戴者施加心形锁及显式解锁。锁具选择可搜索 Liko、AFC、心形锁或拓展恋人。上锁读取佩戴者公开的 `AFC.lockPerms`、拓展恋人及原生关系；挂锁者可在关系撤销后解锁，其他人必须仍获得佩戴者授权。普通高安全锁钥匙不能绕过心形锁授权。

原生属性使用 `HighSecurityPadlock`，伴随心形锁标记与唯一锁身份。`HeartLockApply` 携带同一身份和版本 2 的完整物品快照，保留文字、颜色、难度及制作信息。解锁发送指定身份的 `HeartLock::Remove` 并清除心形锁标记。两份消息在同一次最终房间、角色及权限校验内按序发送；这不代表服务端提供跨消息事务。旧式无身份锁的移除通知在物品更新之后发送。

对于 R132 丢失标记的物品，只有公开摘要的佩戴者、挂锁者、物品和槽位一致时才识别为心形锁。心形锁需先显式解锁，拆卸或替换不能跳过配套通知。

仍待完成：私密配置的请求与验证、笔记编辑、计时器与震动/高潮控制面板，以及移动端作为佩戴者时的插件私有数据持久化、计时到期和防脱逃运行逻辑。现阶段不声明完整替代 AFC 插件。无 ADB，尚未进行 Android 真机或双客户端在线联调。
