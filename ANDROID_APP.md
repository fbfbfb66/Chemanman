# 开单助手 Android MVP

这是 `com.goings.kaidanzhushou` 的原生 Android 客户端。技术栈为 Kotlin、Jetpack Compose、单 Activity、Room、WorkManager、CameraX 和 OkHttp，最低 Android 8.0（API 26）。

## 本地运行

1. 使用 Android Studio 打开仓库根目录。
2. 选择 JDK 21 与 Android SDK 36。
3. 运行 `app` Debug 配置。
4. 首次识别前，在“设置”中输入每台设备自己的 Kimi API Key。

命令行验收：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`。

## 数据与隐私

- 原图规范化为最长边 4096px 的高质量 JPEG；Kimi 上传副本最长边 2200px。
- 图片、Room 数据库和导出副本位于 App 私有目录，不会自动删除或备份。
- API Key 使用 Android Keystore AES-256-GCM 加密，不进入源码、构建配置或日志。
- 相册导入使用系统 Photo Picker，Excel 另存使用系统文件选择器，因此不申请广泛存储权限。
- 只有用户明确点击“开始 AI 识别”后才会上传图片。

## 识别与导出

- Kimi 使用 `kimi-k3`、流式 Chat Completions、`reasoning_effort=high` 和严格 JSON Schema。
- WorkManager 队列从并发 1 开始，连续成功后升到 2/4；遇到 429 立即降回 1。
- 网络、429、5xx 最多重试 3 次；401、余额不足和永久参数错误暂停整批。
- 导出前必须逐条人工确认。XLSX 工作表固定为“导入数据”，包含 Schema v1.0 的 16 列，不生成公式。

## 尚需真机验收

代码与 JVM 自动化测试不替代相机硬件和真实业务样本验收。发布前仍需在 API 26、API 36 与至少一台真实手机上验证权限、旋转、闪光灯和连续 100 张拍摄；随后用 10 张脱敏托运单校准提示词，并用现有油猴脚本完成最终 Schema 预检。
