# Ember

原画直出的私有影音播放器。Kotlin + Jetpack Compose + libmpv，一套代码跑手机 / 平板 / Android TV。

## 定位

给有 NAS / 115 网盘 / AList / Emby / Jellyfin / Plex 的影音玩家：
**不转码、不降质**，把服务器的原始码流直连拉到本地播放。

## 核心能力

- **libmpv 内核**：4K/8K、HDR10、HDR10+、Dolby Vision（Profile 5）、次世代音轨直通
- **STRM + 302 直连**：读 `.strm` 文本直链，手动跟 302 收集沿途 `Set-Cookie`，绕开服务器带宽
- **多源聚合**：Emby / Jellyfin / Plex / AList / WebDAV / 本地目录，一键切换
- **硬解优先**：`hwdec=auto-safe`（MediaCodec），失败自动回退软解，不黑屏
- **全手势**：水平 seek / 左亮度 / 右音量 / 双击暂停 / 单击呼出控制条
- **TV 自适应**：焦点描边 + 遥控器键位，同一份 APK 装手机和电视
- **隐私**：所有数据本地存储，凭据走 Android Keystore 加密，无统计无埋点

## 为什么不用 ExoPlayer / Media3

FFmpeg + libmpv 的解码广度和滤镜链远超 MediaCodec：mkv 内嵌 PGS、ASS 特效字幕、
DTS-HD / TrueHD 次世代音轨、10bit HEVC、老编码（RealVideo / VC-1）——这些 Media3
要么不支持要么要另装扩展。Ember 的目标是「什么都能播」，所以选 libmpv。

## STRM/302 为什么是「手动跟跳转」

网盘（115 等）的 302 响应会**下发新的鉴权 Cookie**。libmpv 自己跟跳转时拿不到
这些 Cookie，第二次请求 CDN 就会 403。所以 Ember 在 App 层用 OkHttp 手动跟跳转，
沿途收集所有 `Set-Cookie`，把最终 URL + Cookie 一起塞给 mpv，才能稳定直连。

## 构建

```
cd ember
gradle assembleRelease     # 或 ./gradlew（需要本地 Gradle 8.7）
```

产物在 `app/build/outputs/apk/release/`，按 ABI 拆分（arm64 / arm32 / universal）。

### 本地签名（可选）

在仓库根放 `key.properties`：

```
storeFile=/path/to/keystore.jks
storePassword=***
keyAlias=ember
keyPassword=***
```

不存在该文件时自动退回 debug 签名（功能一致，仅安装来源不同）。

## 目录结构

```
ember/
├── app/src/main/kotlin/cn/plum/ember/
│   ├── core/
│   │   ├── mpv/MpvEngine.kt        # libmpv 封装（加载/控制/轨道/章节/状态机）
│   │   ├── net/Http.kt             # OkHttp + 手动 302 + Cookie 收集
│   │   └── resolve/                # 源适配器（Emby/Jellyfin/Plex/AList/WebDAV/本地）
│   │       ├── SourceAdapter.kt    # 接口 + 工厂
│   │       ├── StreamResolver.kt   # STRM/302 解析管线
│   │       ├── EmbyAdapter.kt      # Emby + Jellyfin（同一套 API）
│   │       ├── PlexAdapter.kt
│   │       └── Adapters.kt         # AList / WebDAV / 本地
│   ├── data/                       # 模型 / SQLite / DataStore / EncryptedSharedPreferences
│   ├── ui/
│   │   ├── screens/                # Splash / Sources / Home / Library / Detail / Player / Search / Settings
│   │   ├── theme/Theme.kt          # 深色影院主题（炭火橙）
│   │   └── widgets/Widgets.kt      # 海报卡 / 焦点 / 货架 / 空态
│   ├── EmberApp.kt
│   └── MainActivity.kt             # 唯一 Activity + NavHost
└── .github/workflows/build.yml     # CI 构建 + Release 挂 APK
```

## 已知限制（v1.0）

- **未加密 ISO 播放未实现**：预编译的 libmpv AAR 普遍不带 libbluray/libdvdnav，
  `bd://` / `dvd://` 协议不可用。计划在 v1.1 加：自研 UDF 读取器 + 最长 m2ts/VOB 匹配。
- **扫码添加源**：UI 占位，v1.1 接 zxing。
- **SMB 直连**：通过系统「网络存储」或 WebDAV 桥接，原生 SMB 协议栈在 v1.2。

## 许可

GPL-2.0-or-later（跟随 mpv/FFmpeg 的许可约束）。
