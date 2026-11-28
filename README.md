# MagicCapsule（灵动胶囊助手）

一个基于 **Jetpack Compose + Material 3** 构建的开源 Android 应用（`minSdk = 31` / `compileSdk = 35` / `targetSdk = 35`），面向多品牌国产定制 ROM 与原生 Android 16+，提供**原生灵动胶囊 / 实况窗 / Live Updates** 与**全品牌通用悬浮横幅 / 可收起迷你胶囊**双引擎实时同步体验，免 Root 即可覆盖通知镜像、媒体播放与实时歌词、多地图融合导航、设备状态监控与全链路手动自检。

## 核心特性（v177.0.0）

- **多品牌独立分包胶囊引擎（自动路由 + 无缝兜底）**：
  - **荣耀 MagicOS**（`capsule/honor/MagicCapsuleBridge.kt`）：通过 `<uses-library android:name="framework-magic" android:required="false" />` 纯反射接入 `CapsuleData`、`StatusBarData`、`ExtraData`、`AodData` 息屏显示扩展与紧凑小字号 `RemoteViews`。
  - **华为 HarmonyOS / EMUI**（`capsule/huawei/HuaweiLiveCapsuleBridge.kt`）：接入 `notification.live.*` 实况窗协议；当系统实况窗开关未开启时（`areLiveNotificationsEnabled == false`），自动无缝回退至通用悬浮横幅/迷你胶囊，避免“既无实况窗又无悬浮窗”。
  - **Android 16+ 原生 Live Updates**（`capsule/android16/AndroidLiveUpdateBridge.kt`）：适配 `POST_PROMOTED_NOTIFICATIONS`、`setRequestPromotedOngoing` 与 `setShortCriticalText`。
  - **小米 HyperOS / vivo OriginOS**（`capsule/xiaomi/`、`capsule/vivo/`）：支持焦点通知与 SuperX 协议桥接及第三方实况通知 Extra 反向解析，非白名单场景自动回退通用悬浮层。
  - **全品牌通用悬浮横幅 / 迷你胶囊（`UniversalBannerHost`）**：基于 `TYPE_APPLICATION_OVERLAY`（不依赖 Shizuku），支持展开卡片、自动收起顶部迷你胶囊、左右滑动关闭、`Settings.canDrawOverlays + AppOps` 双源权限校验以及无障碍置顶层（`CapsuleOverlayAccessibilityService`）自动兜底。
- **媒体播放 / 录音机智能拦截与双独立联网 LRC 歌词引擎**：
  - 自动识别音乐、视频与录音/录屏会话，按类型动态渲染播控按钮（`上一曲 / 快退 / 播放暂停 / 快进 / 下一曲 / 歌词 / 收藏`），内置 60fps 进度插值同步与防流氓假媒体通知三重防伪墙；支持自定义通知栏播放器自动回溯绑定 `MediaSessionManager`。
  - **双独立联网 LRC 渠道物理隔离**：
    - 胶囊专用 `LrcCapsuleLyricEngine`（酷狗 → 网易云 → QQ音乐 → LRCLIB → LrcApi 级联）仅推送状态栏胶囊。
    - 横幅专用 `BannerLrcLyricEngine`（LRCLIB → 网易云 → QQ音乐 → LrcApi → 酷狗级联，独立磁盘缓存与时间轴）仅推送悬浮横幅，同时支持播放器原生 `tickerText` / `MediaMetadata.LYRIC` / 蓝牙 AVRCP 实时歌词优先显示。
- **多地图 Web API 胶囊导航（GNSS + IMU 惯性融合）**：
  - 支持高德、腾讯、百度与免 Key OSRM 四大地图源，支持起终点定位偏置校验与完整路线步骤预览。
  - 结合 `GnssStatus` 平均信噪比监测，弱星/室内场景自动切换 3 轴线性加速度 ENU 世界坐标投影速度积分与横竖屏 X-Y 异常采样剔除；通用模式下支持原地无闪回刷新（`updateNavBannerInPlace`），销毁时彻底清空传感器缓存防止状态机串扰。
- **设备状态监控与 LTPO 渲染功耗调度**：
  - 实时采集电量、电流、温度、刷新率、内存、网络、响铃模式与可选 Shizuku 增强指标（CPU 温区 / Doze），支持状态变化短时插播并自动断点续播媒体胶囊。
  - 内置 `RenderPowerGovernor` 三档 LTPO 帧率 LOD（`HIGH_FPS` / `BALANCED` / `IDLE_LOW_POWER`）与步进省电插值器，降低前台与悬浮层渲染功耗。
- **全链路手动自检中心 & 精细错误捕捉日志系统**：
  - 内置 **17 项手动自检中心（`SelfCheckEngine` + `SelfCheckPage`）**：一键检测权限与服务绑定、**无 Shizuku 实际悬浮窗 `1×1` 真实挂载深度探针**、品牌反射桥接、传入解析与来电直通仲裁、应用内传出配置/协议包/位图/导航 URL 编码审计，以及实机**弹出横幅通知**、**导航横幅原地刷新**与**胶囊三帧文本轮换**测试。
  - `AppLogger` 升级高精度报错捕捉（1500 行内存环 + 256KB 落盘、线程 ID、最多 4 层 `Caused by` 根因链、优先定位业务代码前 6 帧堆栈、运行时环境快照），并在 `GlobalCrashHandler` 崩溃日志中自动串联崩溃前 15 条运行轨迹。
- **严格隐私边界**：
  - 无障碍服务严格保持 `android:canRetrieveWindowContent="false"` 且不监听任何无障碍事件，仅用于可选的顶层悬浮横幅绘制，绝不读取屏幕内容。

## 目录结构

```text
app/src/main/
├── res/
│   ├── drawable/                # Pixel Material Symbols 风格圆角矢量图标与进度条背景
│   ├── layout/                  # 紧凑通知布局、胶囊小字号视图与 4 套展开卡片布局
│   └── mipmap-anydpi-v26/       # Material You 自适应桌面图标
└── java/com/app/magiccapsule/
    ├── MagicCapsuleApp.kt       # Application 初始化与全局崩溃/日志挂载
    ├── capsule/                 # 跨品牌路由与发布核心
    │   ├── DeviceBrandRouter.kt
    │   ├── CapsulePublisher.kt
    │   ├── CapsuleNotificationFactory.kt
    │   ├── honor/MagicCapsuleBridge.kt
    │   ├── huawei/HuaweiLiveCapsuleBridge.kt
    │   ├── xiaomi/XiaomiFocusBridge.kt
    │   ├── vivo/VivoSuperXBridge.kt
    │   └── android16/AndroidLiveUpdateBridge.kt
    ├── crash/                   # AppLogger 精细日志与 GlobalCrashHandler 崩溃捕手
    ├── data/                    # CapsulePrefs 持久化存储
    ├── model/                   # CapsuleConfig, CapturedNotification, InstalledApp, SystemStatusSnapshot
    ├── service/                 # 通知监听、媒体/歌词双引擎、导航融合引擎、通用悬浮宿主、自检引擎、LTPO 调度
    └── ui/                      # MainActivity, SelfCheckPage, SystemMonitorPage, SubPages, OnboardingPage 等
```

## 构建指南

### 方式一：Android Studio
1. 克隆仓库并用 Android Studio 打开根目录。
2. 连接 Android 12+（API 31+）真机，选择 `release` 或 `debug` 变体直接构建安装。

### 方式二：手机 Termux（ARM64 本地 Release R8 编译）
项目已内置阿里云 Maven 镜像、R8 压缩与资源裁剪配置，可在配置好 Android SDK 与 ARM64 `aapt2` 的 Termux 环境中直接本地出包：

```bash
rm -rf ~/MagicCapsuleApp && mkdir -p ~/MagicCapsuleApp && \
unzip -oq ~/storage/downloads/MagicCapsuleApp_v177.zip -d ~/MagicCapsuleApp && \
cd ~/MagicCapsuleApp && \
gradle :app:assembleRelease --no-daemon && \
cp app/build/outputs/apk/release/app-release.apk ~/storage/downloads/MagicCapsuleApp_v177_release.apk
```

## 权限与使用说明

- **必需权限**：通知发送权限（`POST_NOTIFICATIONS`）与系统**通知使用权**（用于捕获通知与媒体会话）。
- **悬浮窗权限（无需 Shizuku）**：通用悬浮横幅与迷你胶囊仅需系统「显示在其他应用上层（`SYSTEM_ALERT_WINDOW`）」；小米/vivo 等机型建议同时在应用权限中开启「后台弹出界面」并允许自启动。可在应用内「手动系统自检」运行**悬浮窗深度探针**实测是否被系统拦截。
- **可选权限**：定位权限（仅开启胶囊导航时使用）、无障碍服务（可选顶层横幅与兜底通道）、Shizuku（可选用于自动授予 `WRITE_SECURE_SETTINGS` 屏蔽重复系统横幅与读取 CPU 温区/Doze 状态）。

## 制作人与致谢

- **制作人**：Oyama Mahiro、北辞☪꯭᭄ꦿ
- **特别致谢**：Chan（Pro 订阅支持与宝贵反馈）、Frazil 🧊（首批实机测试反馈）

## License

[MIT](LICENSE)
