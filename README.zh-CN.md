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
- 保留 DNCS 排除性探针，用于复现实机上“重复服务注册成功但 Air 不订阅、不震动”的结果。
- v10018 增加不扫描的已知地址直连实验：尝试让本应用的 GATT client 加入 Google Health 已维持的系统 BLE 链路。

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
- v10017 已实机证伪 DNCS 共存：完整蓝牙重启后仍无 READ/WRITE/CCCD；未订阅直发虽返回 `accepted=true` 和 `status=0`，但 Air 不震动。
- v10018 的已知地址双 GATT-client 路径仍是待实机验证的协议探针，不是已确认的共存方案。
- 当前只构建 `arm64-v8a`。
- pattern 是由已经验证的“切换强度—恢复强度”短震组组成，不是任意马达波形控制。
- 本应用和 Google Health 不能同时主动占用 Fitbit Air；常规扫描/连接模式不能共存。
- 如果 Air 轮换 BLE 地址，当前版本可能需要重新选择一次设备。

## 基本使用

1. 安装应用并允许附近设备/蓝牙权限。
2. 保持 Google Health 正常运行，并确认它已经连接 Air。
3. 点击“直接加入已有链路并测试 single pattern”。v10018 不会先扫描 Air。
4. 记录最终状态中的 `systemGatt(before/after)`、失败阶段以及 Air 是否震动。
5. 测试后打开 Google Health，确认它仍可读取/同步 Air；不要只依据 Bridge 显示的 Android 系统连接状态。
6. DNCS 页面只保留用于复现已经确认的负面结果。

## 隐私和安全

- 通知内容只在内存中用于短时去重。
- 监听规则保存在 Android 本地 `SharedPreferences` 中。
- 本项目不会把通知正文、设备密钥或用户数据上传到服务器。
- 仓库不包含 Google Health APK、反编译专有代码、账号凭据或设备密钥。

## 路线图

1. 实机验证 v10018 能否在 Google Health 保持连接时建立第二个 GATT client、Gattlink 和 DTLS，并在释放后不破坏 Google Health。
2. 若 v10018 失败，把普通无特权 APK 共存路径标记为不可行，重新评估系统级集成或切换所有权的产品方案。
3. 把包名文本配置改成 3–5 个应用的可视化选择器。
4. 增加 pattern 编辑器、冷却时间和安全预览。
5. 增加可复现的 GitHub Actions 构建与签名 Release APK。

构建方法及许可证说明见 [English README](README.md)。
