# MagicCapsule（荣耀 MagicOS 灵动胶囊助手）

一个基于 **Jetpack Compose + Material 3** 构建的开源 Android 应用，通过纯 Kotlin 反射调用荣耀 MagicOS 系统共享库 `framework-magic`，实现免 Root、免媒体播控占用的原生**灵动胶囊（Magic Capsule）**实时通知同步与高度自定义交互卡片。

## 核心特性（v18.0.0）

- **原生灵动胶囊接入**：通过 `<uses-library android:name="framework-magic" android:required="false" />` 与纯反射调用 `CapsuleData` / `NotificationEx.setHnFeature`，无需编译期依赖系统 JAR。
- **通知监听、锁定与防冲突保护**：
  - 基于 `NotificationListenerService` 实时捕获活跃通知，读取每条通知的真实发起应用包名（`sbn.packageName` / `sbn.opPkg`），严格隔离本应用自身包名防止循环触发。
  - 支持在通知选择器中点选**锁定（Pin）**单条通知（按 `key + packageName` 双重校验），不会被其他高频刷新通知顶掉。
- **自动模式白名单免限频机制**：
  - 声明 `QUERY_ALL_PACKAGES` 并在后台线程读取手机已安装应用列表与完整包名，支持按应用名/包名搜索并一键加白。
  - 白名单内的应用在自动模式下**完全不受刷新频率限制**与常驻通知过滤限制，且其通知存活期间不会被非白名单通知顶掉。
  - 非白名单应用支持自定义自动刷新间隔（1–60 秒）以及「仅同步白名单应用」开关。
- **展开卡片高度自定义与锁定尺寸**：
  - 内置 5 套一键主题预设（默认森林翡翠 `#FF0D1F18`）、4 套展开排版格式（紧凑单行 / 上下双按钮 / 居中大字 / 极简纯信息），全部锁定 `64dp` 内容区高度，切换样式或调整字号时整体展开大小保持不变。
  - 支持底色、标题色、副文色、按钮底色与前景强调色的 `#AARRGGBB` 独立自定义，以及标题（`10–26 sp`）、副文（`8–20 sp`）、按钮（`9–16 sp`）的 `-1sp` / `+1sp` 精确调节与实时预览。
- **横竖屏双态原生自适应**：采用 Android 原生双方向 `RemoteViews(landscapeExpandedRv, portraitExpandedRv)`，由系统 `SystemUI` 在旋转时原生无缝切换（竖屏自动避让 3D ToF 宽药丸挖孔，横屏居中紧凑显示）。
- **四分页 + 五个二级页面 & Pixel 圆角矢量图标**：
  - 底部四分页（概览 / 通知 / 外观 / 设置）配合 5 个独立二级页面（应用列表白名单、颜色高度自定义、文字 `sp` 字号调节、手动文案与模板、通知来源详情），支持系统侧滑返回。
  - 全应用内置 24 枚 Pixel Material Symbols Rounded 风格本地圆角 `VectorDrawable` 图标、Material You 圆形色调徽章，以及支持 Android 13+ `<monochrome>` 主题取色的自适应桌面图标。

## 目录结构

```text
app/src/main/
├── res/
│   ├── drawable/           # 24 枚 Pixel 风格圆角矢量图标与自适应前景/单色层
│   ├── layout/             # 通知中心紧凑布局 + 4 套锁定高度的展开卡片布局
│   └── mipmap-anydpi-v26/  # Material You 自适应桌面图标
└── java/com/app/magiccapsule/
    ├── model/              # CapsuleConfig, CapturedNotification, InstalledApp
    ├── data/               # SharedPreferences 持久化 (CapsulePrefs)
    ├── capsule/            # framework-magic 反射桥接与通知工厂
    ├── service/            # 前台保活服务与通知监听服务
    └── ui/                 # NavigationState, CapsuleComponents, SubPages, MainActivity, CapsuleViewModel
```

## 构建指南

### 方式一：Android Studio
1. 克隆仓库并用 Android Studio 打开根目录。
2. 连接荣耀 MagicOS 真机（Android 12 / API 31+），选择 `release` 或 `debug` 变体运行即可。

### 方式二：手机 Termux（ARM64 本地 Release R8 编译）
项目已内置阿里云 Maven 镜像、R8 代码压缩（`isMinifyEnabled = true`、`isShrinkResources = true`）与 `-Xmx1024m` 内存配置，可在配置好 Android SDK 与 ARM64 `aapt2` 的 Termux 环境中直接编译 Release 包：

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :app:assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk ~/storage/downloads/MagicCapsuleApp_v18_release.apk
```

## 注意事项

- 仅在搭载荣耀 `framework-magic` 共享库的 MagicOS 设备上触发原生灵动胶囊；非荣耀设备会自动降级为普通自定义通知。
- 首次使用请在应用内授予**通知权限**与系统**通知使用权**；为防止后台被系统管家回收，建议点击「申请忽略电池优化」并在系统 **设置 → 应用 → 应用启动管理** 中允许自启动与后台活动。

## License

[MIT](LICENSE)
