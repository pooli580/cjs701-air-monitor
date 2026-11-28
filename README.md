# CJS701 Air Monitor

Android 8.0 及以上的空气质量和设备监控客户端。首页显示七项空气读数，MD1000 页支持局域网监控及 MQTT 远程回退。

1.8 的「代理」页用于在同一局域网控制 MD1000 的代理服务、下载测速和节点选择。需要 MD1000 提供配对码。应用根据配对码验证板上 HTTPS 证书，并通过 Android Keystore 保护已保存的配对码。

1.8.1 增加节点延迟、最近一轮下载测速和手机 Wi-Fi 接入。更新板端以后，可在「代理」页启用本机接入，再把当前 Wi-Fi 的 HTTP 代理设为页面显示的主机和端口。MD1000 对常见国内平台和局域网地址执行直连规则，其余流量由当前策略组处理。

「订阅流量」显示每条订阅的剩余量、总量和更新时间。1.8.3 起用进度条和百分比展示剩余比例。数值取自服务商随订阅返回的额度数据。

1.8.4 可在「手机 Wi-Fi 接入」里为本机选择远程或下载模式。MD1000 将本机新建的代理连接交给所选策略组，现有连接继续使用原策略。

1.8.5 可在 MD1000 配置直连服务后显示直连状态。「关闭代理」关闭订阅服务，获准接入的设备继续经 MD1000 直连上网。

[从 Releases 下载 APK](https://github.com/pooli580/cjs701-air-monitor/releases/latest)。首次安装后，在「设置」中填写自己的数据源。应用默认只有 GitHub 更新源。

## 配置

- **局域网空气质量**填写 ESP32 的局域网 IPv4，接口为 `http://设备地址:8080/api/air`。
- **远程空气质量**启用「局域网不可用时使用 MQTT」，填写 `ssl://主机:端口`、空气主题和可选账号。首页和阈值提醒都优先读取局域网，断开后切换到 MQTT，恢复后自动切回。使用私有证书的服务器可导入 CA 证书。App 通过 Android Keystore 加密已保存的密码。
- **设备监控**填写监控台根地址，可再填写 MQTT 监控主题用于自动回退。监控台提供 `/api/history?view=system` 和 `/api/history?range=1h`、`6h`、`24h`。远程主题的消息为 gzip 压缩的 24 小时历史接口 JSON 快照，支持 QoS 1 和 Retain。
- **在线升级**在「设置 → 检查更新」中查看 GitHub 上的新版本。首次使用时按安卓安装界面确认。使用同一包名和签名覆盖安装时，应用继续使用原有设备设置。

空气读数 JSON 使用 `status`、`temperature_c`、`humidity_pct`、`hcho_mg_m3`、`tvoc_mg_m3`、`eco2_ppm`、`eco_ppm` 和 `aqi`。成功状态为 `live`，温度单位为摄氏度，湿度为百分比，甲醛和 TVOC 为 mg/m³，等效 CO₂ 和 CO 为 ppm。MQTT 消息还提供 `sampled_at`，单位为 Unix 秒。

历史接口 JSON 包含 `now`、`start`、`interval`、`samples` 和可选的 `system`。每条采样包含 `ts`、`esp`、`router`、`wan` 和 `air`。前三个探测字段使用毫秒表示延迟，`null` 表示未响应。

## 构建和发布

使用 Windows PowerShell 7、JDK 21、Android SDK 平台 API 36、Build Tools 36.0.0 及 Eclipse Paho Java MQTT 1.2.5 JAR。运行 `android/build-apk.ps1`，传入 `-Jdk`、`-Platform`、`-BuildTools`、`-MqttJar`、`-KeyStore` 和 `-OutputApk`。签名库别名为 `cjs701`，密码由进程环境变量 `CJS701_SIGNING_PASSWORD` 提供。

发布时在 `android/AndroidManifest.xml` 提高 `versionCode`，用原密钥签名，再用 `android/new-update-feed.ps1` 生成两份 `latest.json`。

- **Release** 将 `-ApkBaseUrl` 设置为该版本的 GitHub Release 下载目录，再将 APK 和清单一起上传到 Release。
- **App 更新源** 在 `updates` 分支根目录添加新版本 APK，并更新 `latest.json`。用 `-ApkUrl` 指定 APK 的 GitHub Contents API 链接，格式为 `https://api.github.com/repos/pooli580/cjs701-air-monitor/contents/APK文件名?ref=updates`。将 APK 和清单放在同一次提交中。App 通过 GitHub API 直接读取两份文件。

独立分发的构建应使用自己的签名密钥。

[MIT 许可证](LICENSE) · [第三方组件说明](NOTICE)
