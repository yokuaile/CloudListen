# 云听书 CloudListen

Android 有声书应用（com.yunting.audiobook），当前版本 **1.0.25（versionCode 26）**。

- 技术栈：Kotlin + Jetpack Compose + Media3/ExoPlayer + OkHttp + Gson，minSdk 21
- 功能：书架 / 本地 / 发现三个 tab，聚合哔哩哔哩与公众号等免费有声书源并行搜索，
  「我的」页含累计听书 / 我的收藏 / 收听记录
- 视觉：微信风格（绿 + 白），滑块与进度条均为无圆点的纯色细轨道样式

## 构建

无 gradle wrapper，用本机 Gradle 8.7 + JDK 17 构建：

```bash
# local.properties 中指定 sdk.dir
gradle assembleRelease
```

- 签名：自备 `app/keystore/yunting.jks`（alias: yunting，出于安全考虑未入公开仓库）
- minSdk 21 / compileSdk 34

## 目录

- `app/src/main/java/com/yunting/audiobook/ui/` — 各界面（Compose）
- `app/src/main/java/com/yunting/audiobook/playback/` — 播放服务与播放中枢
- `app/src/main/java/com/yunting/audiobook/data/` — 数据层（源、本地数据、用户数据）
- `CHANGELOG.md` — 版本迭代记录
