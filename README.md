# YuHao投屏

安卓电视 / 机顶盒投屏接收端：在同一局域网下接收手机视频 App 的投屏。

## 功能

- **DLNA 投屏**：支持爱奇艺、腾讯视频、芒果 TV、哔哩哔哩等主流视频 App 的 TV 投屏
- **图片投屏**：支持手机相册类 App 投屏照片，视频与图片切换时电视画面同步切换
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

应用默认从 **GitHub 仓库** 检查更新（`update/version.json`，经 jsDelivr CDN 加速，国内电视可直接访问），
每天自动检查一次，也可在设置页手动检查。设置页清空更新地址并保存可停用。

version.json 格式：

```json
{"versionCode":21,"versionName":"2.0.1","url":"https://cdn.jsdelivr.net/gh/cat007321/YuHao-Cast@v2.0.1/update/YuHaoCast-v2.0.1.apk","notes":"更新说明"}
```

## 发布新版本流程

1. `app/build.gradle.kts` 递增 `versionCode` 与 `versionName`，构建 APK：
   `gradle assembleDebug`，重命名产物为 `update/YuHaoCast-vX.Y.Z.apk`
2. 更新 `update/version.json` 的 `versionCode/versionName/url/notes`
   （url 指向本仓库 `update/` 下的 APK，用 `@vX.Y.Z` 标签地址）
3. 提交并打标签推送：

```bash
git add -A && git commit -m "vX.Y.Z"
git tag vX.Y.Z
git push origin main --tags
```

4. 在 GitHub 上基于该标签创建 Release（可选，便于网页浏览下载）

## 版本历史
- v2.2.0 (build 32)：播放界面去掉中央暂停/快进/快退按钮——遥控确定键暂停/继续、左右键快退/快进；进度条上方新增清晰度选择，多码率视频流自动出现可选分辨率，默认最高清晰度

- v2.1.9 (build 31)：更新源增加 gh-proxy.com 实时镜像（ghproxy.net 故障时仍能收到更新提示）；下载地址改为 GitHub 直链并自动轮换 ghproxy / gh-proxy / ghfast 三个加速镜像。含 v2.1.8 全部内容
- v2.1.8 (build 30)：播放缓冲上限放开至 256MB 并按设备堆内存自适应——4GB 大内存设备（极米 H6 等）缓冲可达 20~40 秒，128MB 堆设备保持约 80MB 不变
- v2.1.7 (build 29)：更新源首选 ghproxy 实时镜像（jsDelivr @main 缓存最长 12 小时且会故障，导致电视收不到更新提示），全部镜像依次轮询
- v2.1.6 (build 28)：检查更新与下载更新自动轮换多个镜像源（jsDelivr 三节点 + GitHub 加速镜像），电视网络受限也能正常更新；播放缓冲上限按设备内存自适应（海信电视 128MB 堆≈80MB 缓冲，回看缓冲缩至 2s 让位）
- v2.1.5 (build 27)：修复大体积本地视频（10~30GB 高码率）投屏反复缓冲——播放缓冲上限 32MB→64MB，起播缓冲加深（首起 4s / 卡后 8s），内存仍为字节封顶不会 OOM
- v2.1.4 (build 26)：更新体验优化——应用内下载更新包并显示实时下载进度（已下载 MB / 百分比），下载完成自动调起系统安装器
- v2.1.3 (build 25)：修复本地高码率（4K）视频投屏内存溢出——播放缓冲改为按字节封顶（32MB），不再随码率增长撑爆堆
- v2.1.2 (build 24)：修复手机相册投屏内存溢出（无扩展名地址自动探测内容类型，图片/视频分流；加载错误最多重试 8 次后停止）
- v2.1.1 (build 23)：修复投屏高码率视频内存不足（加载失败 OOM），启动应用时自动检查更新
- v2.1.0 (build 22)：新增图片投屏（相册投屏自动切换显示，视频/图片互切），去除设置入口
- v2.0.1 (build 21)：更新源迁移到 GitHub（默认自动检查更新）
- v2.0 (build 20)：更名为 YuHao投屏，正式界面（诊断信息移至设置页），新图标，自动更新检查
- v1.18：返回键分级（关屏显 → 退出确认弹窗）
- v1.17：双网卡设备宣告网卡修复
