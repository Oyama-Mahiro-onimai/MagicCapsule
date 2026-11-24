# MagicCapsule（荣耀 MagicOS 灵动胶囊助手）

一个基于 **Jetpack Compose + Material 3** 构建的开源 Android 应用，通过纯 Kotlin 反射调用荣耀 MagicOS 系统共享库 `framework-magic`，实现免 Root、免媒体播控占用的原生**灵动胶囊（Magic Capsule）**实时状态展示与交互卡片。

## 核心特性

- **原生灵动胶囊接入**：通过 `<uses-library android:name="framework-magic" android:required="false" />` 与纯反射调用 `CapsuleData` / `NotificationEx.setHnFeature`，无需编译期依赖系统 JAR。
- **多种胶囊模板与主题色**：支持 `image_text`（图文）、`image_text_text`（双文案轮播）、`image_timer`（图标+计时）、`image_text_timer`（图文+计时）以及 12 种品牌色（`MAGIC_COLOR_1` ~ `MAGIC_COLOR_12`）。
- **通知中心与展开卡片分离**：
  - 通知中心默认态使用紧凑单行 `RemoteViews`（不占用过多屏幕）。
  - 顶部灵动胶囊展开态使用直角纯色高对比交互卡片（圆角由系统外框原生裁切，无双层圆角缝隙）。
- **全向屏幕与 3D ToF 挖孔自适应**：
  - 竖屏自动读取 `WindowInsets.Type.displayCutout()` 与状态栏高度动态避让 3D ToF 宽药丸挖孔。
  - 通过 `DisplayManager.DisplayListener` 实时监听横竖屏旋转，横屏自动切换至 70% 居中宽度与紧凑顶部间距。
- **前台服务保活与静默刷新**：基于 `FOREGROUND_SERVICE_SPECIAL_USE` 与 `setOnlyAlertOnce(true)` 定时静默刷新，支持一键申请忽略电池优化。
- **现代 Android 架构**：采用 Jetpack Compose + Material 3 动态取色、`enableEdgeToEdge` + `safeDrawing` + `imePadding`、`ViewModel` + `StateFlow` 单向数据流与 `SharedPreferences` 持久化。

## 目录结构

```text
app/src/main/java/com/app/magiccapsule/
├── model/      # 不可变配置模型 (CapsuleConfig)
├── data/       # SharedPreferences 本地持久化 (CapsulePrefs)
├── capsule/    # framework-magic 反射桥接与通知工厂 (MagicCapsuleBridge, CapsuleNotificationFactory)
├── service/    # 前台保活与横竖屏监听服务 (CapsuleKeepAliveService)
└── ui/         # Jetpack Compose UI 与 ViewModel (MainActivity, CapsuleViewModel)
```

## 构建指南

### 方式一：Android Studio
1. 克隆仓库并用 Android Studio 打开根目录。
2. 连接荣耀 MagicOS 真机（Android 12 / API 31+），点击 **Run** 即可。

### 方式二：手机 Termux（ARM64 本地编译）
项目已内置阿里云 Maven 镜像与 `-Xmx1024m` 内存配置，可在配置好 Android SDK 与 ARM64 `aapt2` 的 Termux 环境中直接构建：

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :app:assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/MagicCapsuleApp.apk
```

## 注意事项

- 仅在搭载荣耀 `framework-magic` 共享库的 MagicOS 设备上触发原生灵动胶囊；非荣耀设备会自动降级为普通自定义通知。
- 为防止后台被系统管家回收，建议在应用内点击「申请忽略电池优化」，并在系统 **设置 → 应用 → 应用启动管理** 中将本应用设为手动管理（允许自启动与后台活动）。

## License

[MIT](LICENSE)
