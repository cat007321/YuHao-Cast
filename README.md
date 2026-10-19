# YuHao投屏

安卓电视 / 机顶盒投屏接收端：在同一局域网下接收手机视频 App 的投屏。

## 功能

- **DLNA 投屏**：支持爱奇艺、腾讯视频、芒果 TV、优酷等主流视频 App 的 TV 投屏
- **AirPlay**：支持 iPhone 屏幕镜像与视频投屏
- **直播流**：支持 rtmp 直播源（看球直播等）
- **多网卡自适应**：双网卡设备（如部分海信电视）自动选择正确的网段宣告
- 遥控器左右键快进快退（点播视频）
- 设置页：设备名称、更新服务器、诊断信息

## 技术栈

- Kotlin + Gradle（无 Android Studio 依赖，命令行可构建）
- DLNA/UPnP：自研轻量 SSDP + UPnP AVTransport 协议栈（`dlna/` 包）
- AirPlay：AirPlay 1.0 协议（RAOP 视频/照片，Bonjour 宣告）
- 播放：Media3 ExoPlayer（mp4 / HLS / DASH / RTMP）

## 构建

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 自动更新

在设置页填写「更新服务器地址」（存放 version.json 的 URL），格式：

```json
{"versionCode":21,"versionName":"2.1","url":"https://example.com/app.apk","notes":"更新说明"}
```

应用每天自动检查一次，也可手动检查。

## 版本历史

- v2.0 (build 20)：更名为 YuHao投屏，正式界面（诊断信息移至设置页），新图标，自动更新检查
- v1.18：返回键分级（关屏显 → 退出确认弹窗）
- v1.17：双网卡设备宣告网卡修复
