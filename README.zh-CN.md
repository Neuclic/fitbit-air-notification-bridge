# Fitbit Air Notification Bridge

这是一个实验性、非官方的 Android 开源项目：把用户选定的手机通知转换成 Fitbit Air 上可区分的短震动 pattern。

项目基于 Fitbit 以 Apache-2.0 许可证发布的 [Golden Gate](https://github.com/Fitbit/golden-gate) 传输栈，使用 BLE、Gattlink、DTLS/CoAP 和 Android `NotificationListenerService`。通知处理在手机本地完成，不需要云端中转。

> **当前为 Alpha 版本。** 协议只在一台 Fitbit Air 上完成实机验证。本项目与 Fitbit、Google 无隶属关系，不应作为医疗、紧急或安全关键提醒工具。

## 当前能力

- 通过 Golden Gate/Gattlink 连接 Fitbit Air。
- 建立设备的 BOOTSTRAP DTLS 会话。
- 通过 `PUT /settings` 读取当前震动强度。
- 临时切换震动强度，800ms 后恢复，形成一组有限短震。
- 操作完成后重新读取，确认设备设置没有被改变。
- 最多监听 5 个 Android 应用包名。
- 每个应用可以选择 `single`、`double`、`triple`、`long_gap` 或 `urgent`。
- 过滤群组摘要、常驻通知、自身通知和短时间重复内容。
- 保存所选 Air 的地址，并提供经过验证的独占连接提醒模式。
- 提供 DNCS 共存探针：保持 Google Health 运行时检查系统连接、注册反向 GATT 服务并记录 Air 的订阅行为。
- 只有实际观察到 Notification Source 的 CCCD 订阅后，DNCS 测试发送才会解锁。

配置示例：

```text
com.tencent.mm=single
org.telegram.messenger=double
com.whatsapp=long_gap
```

## 当前限制

- v10013 的通知链路和 pattern 已经实机验证，但需要 Bridge 独占 Air 连接。
- v10014 的按需分时方案已经实机证伪：只关闭 Google Health 界面不会停止它的 Companion Device 后台服务，Air 不重新广播，Bridge 的三次扫描会失败。
- Android 没有公开 API 可让第三方应用复用另一个应用的 `BluetoothGatt` 或 DTLS 会话。
- v10015 是 DNCS 反向 GATT 共存探针，不是已经验证的共存方案；是否可行取决于 Air 会不会发现并订阅第二个 DNCS 服务实例。
- 当前只构建 `arm64-v8a`。
- pattern 是由已经验证的“切换强度—恢复强度”短震组组成，不是任意马达波形控制。
- 本应用和 Google Health 不能同时主动占用 Fitbit Air；常规扫描/连接模式不能共存。
- 如果 Air 轮换 BLE 地址，当前版本可能需要重新选择一次设备。

## 基本使用

1. 安装应用并允许附近设备/蓝牙权限。
2. 保持 Google Health 正常运行，打开“DNCS 共存探针”。
3. 先点击“刷新连接”，再点击“启动探针”。
4. 等待 30 秒；若仍未出现 CCCD 订阅，可点击一次“断开并等待 Google Health 重连”。该操作不强制停止 Google Health，并保留探针 DNCS 服务。
5. v10017 可先执行一次“未订阅直接发送”，记录 `accepted` 和 `onNotificationSent`，并确认 Air 是否震动。
6. 若仍无震动，从按钮进入蓝牙设置，关闭再打开蓝牙；保持探针活动，让它在 Google Health 重连时自动重注册 DNCS。
7. 截图保存探针日志；只有出现 Notification Source 的 `CCCD ... enabled=true` 时，正式测试发送按钮才会启用。
8. 独占连接的通知规则与 pattern 功能仍保留用于诊断，但 Google Health 未被强制停止时预计无法连接。

## 隐私和安全

- 通知内容只在内存中用于短时去重。
- 监听规则保存在 Android 本地 `SharedPreferences` 中。
- 本项目不会把通知正文、设备密钥或用户数据上传到服务器。
- 仓库不包含 Google Health APK、反编译专有代码、账号凭据或设备密钥。

## 路线图

1. 实机确认 Air 是否会访问或订阅 v10015 的第二个 DNCS 服务实例。
2. 若 DNCS 探针成立，补齐 Control Point/Data Source 并接入现有通知规则；若不成立，停止把普通 APK 描述为可共存方案。
3. 把包名文本配置改成 3–5 个应用的可视化选择器。
4. 增加 pattern 编辑器、冷却时间和安全预览。
5. 增加可复现的 GitHub Actions 构建与签名 Release APK。

构建方法及许可证说明见 [English README](README.md)。
