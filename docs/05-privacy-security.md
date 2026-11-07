# Bitap 隐私与安全规范

版本：0.1｜状态：冻结｜负责人：Lead

## 1. 隐私承诺（可验证）

1. **无网络权限**：`AndroidManifest.xml` 不声明 `android.permission.INTERNET`，也不声明 `ACCESS_NETWORK_STATE`。应用无法主动联网。
2. **不采集输入**：不记录按键、上下文、prompt、AI 结果；不写日志；不接入分析/崩溃 SDK。
3. **本地候选学习可控**：仅当设置开启且输入框允许个性化学习时，在本机私有存储记录用户主动选中的候选词、拼音与次数（`bitap_user_lexicon`），用于本地候选排序。设置页提供「本地候选学习」开关与「清除本地学习词库」入口；清除即可删除这些记录，不影响内置词库与模型。
4. **不读剪贴板**：0.1 不提供剪贴板读取；不申请相关权限。
5. **不读联系人/账号/位置/麦克风**。
6. **本地模型私有存储**：模型复制到 `filesDir/models/`，其他应用不可读；删除即清除。
7. **备份关闭**：`allowBackup=false`，`dataExtractionRules` 排除全部域。

## 2. 威胁模型

| 威胁 | 缓解 |
| --- | --- |
| 恶意 App 读取输入 | Android IME 沙箱 + 无网络；输入仅通过 `InputConnection` 交给当前编辑器。 |
| 模型文件被替换 | 只从用户选择 URI 复制；记录文件大小；加载失败拒绝运行；不执行模型内代码。 |
| 备份/云同步泄露偏好 | 备份关闭；偏好不含文本。本地学习记录仅存应用私有 SharedPreferences，可在设置页关闭/清除。 |
| 未来误加网络依赖 | CI 检查 Manifest 权限；PR 模板要求说明。 |
| 日志泄露 | Release 关闭调试日志；代码审查禁止打印输入文本。 |
| 键盘截屏 | 输入法不主动截屏；系统截屏由用户控制；不申请 `FLAG_SECURE`（会伤害正常使用）。 |

## 3. 权限清单

| 权限 | 用途 | 是否必需 |
| --- | --- | --- |
| `android.permission.BIND_INPUT_METHOD`（service 声明） | 系统绑定输入法 | 是 |
| 其他 | — | 否 |

## 4. 数据流

```
用户按键 → InputConnection（当前 App） → 不上传；按键序列不落盘
用户主动选词（学习开启） → 本机 UserLexiconStore（pinyin/word/count） → 设置页可关闭/清除
用户点 AI → 选中/最近一句（内存） → LiteRT 本地推理 → 面板预览（内存）
用户插入 → InputConnection
关闭键盘 → 全部内存状态释放
```

## 5. 发布检查

- [ ] `aapt dump permissions app-debug.apk` 不含 INTERNET。
- [ ] 全仓库 `grep -R "http" app/src/main` 仅文档注释/许可链接，无运行时代码。
- [ ] 无 `Log.d`/`Log.i` 打印输入或模型文本。
- [ ] `allowBackup=false`、`usesCleartextTraffic=false`。
- [ ] 依赖清单无广告/分析 SDK。
- [ ] 模型文件不在 APK 内（优化后 release APK ≈ 27.5 MB，模型单独导入）。
