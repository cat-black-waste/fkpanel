# FK 控制台（Compose + Material 3）

Android 端远程控制台：实时屏幕、触摸注入、终端、文件、状态。

## 编译（电脑上）
```bash
# 方式一：Android Studio
#   打开本目录 → 等待 Gradle Sync → Run ▶

# 方式二：命令行（需 JDK 17 + Android SDK）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 修改服务器地址
`app/src/main/java/com/fk/panel/Api.kt` 顶部：
```kotlin
const val USER = "fk"
const val PASS = "fk123456"
private const val TS = "100.99.151.98"   // tailscale 固定 IP
private const val PORT = 8080
```
地址解析顺序：`127.0.0.1 → tailscale → ntfy 最新 IPv6`。

## 架构
- `MainActivity.kt`：Compose UI（Scaffold + NavigationBar + 4 页）
- `Api.kt`：HTTP 客户端（HttpURLConnection，无第三方依赖）
- `VideoDecoder.kt`：H.264 annexb → MediaCodec 硬解 → SurfaceView
- `ui/Theme.kt`：M3 主题（动态取色 + baseline 色板 + 严格类型比例）

## M3 规范落点
| 项 | 实现 |
|---|---|
| 颜色 | 全用 `MaterialTheme.colorScheme` 角色（primary/surfaceContainer/tertiary…），无硬编码 |
| 动态取色 | Android 12+ 用 `dynamicDarkColorScheme` |
| 类型 | titleLarge 22/28、titleMedium 16/24、bodyMedium 14/20、labelLarge 14/20 |
| 圆角 | 卡片 12dp、按钮全圆（`RoundedCornerShape(50)`） |
| 组件 | CenterAlignedTopAppBar、NavigationBar、Card、FilledTonalButton、OutlinedTextField、LinearProgressIndicator |
