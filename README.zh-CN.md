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
- 保存所选 Air 的地址，每次提醒都作为一次前台任务执行。
- 直接连接已保存的 Air 地址，建立 DTLS、执行并复核 pattern，然后关闭连接。
- 震动任务在独立进程执行，空闲时释放该进程的 GATT 客户端和服务端，让 Google Health 有机会重新连接。
- Air 暂时不可用时，按照短延迟进行两次重试。

配置示例：

```text
com.tencent.mm=single
org.telegram.messenger=double
com.whatsapp=long_gap
```

## 当前限制

- 按需共存的初步实测见[验证记录](docs/AIR_COEXISTENCE.md)。后台自动同步、真实来电和长期可靠性仍需验证。
- 当前只构建 `arm64-v8a`。
- pattern 是由已经验证的“切换强度—恢复强度”短震组组成，不是任意马达波形控制。
- Bridge 建立自己的 GATT/DTLS 会话，空闲时释放工作进程的蓝牙注册；不会复用 Google Health 的会话，也不保证双方能同时同步。
- 每次提醒不再以 Air 重新广播为前提，但连接或 DTLS 争用仍可能造成延迟或失败。
- 如果 Air 轮换 BLE 地址，当前版本可能需要重新选择一次设备。

## 基本使用

1. 安装应用并允许附近设备/蓝牙权限。
2. 点击“选择并保存 Fitbit Air”。首次扫描时可能需要等待 Google Health 空闲。
3. 配置最多 5 条 `包名=pattern` 规则并授予通知访问权限。
4. 先执行一次 `single` 按需连接测试；状态面板会记录连接、重试、成功或失败。
5. 保持 Google Health 正常运行，从规则中的应用发送真实通知。

## 隐私和安全

- 通知内容只在内存中用于短时去重。
- 监听规则保存在 Android 本地 `SharedPreferences` 中。
- 本项目不会把通知正文、设备密钥或用户数据上传到服务器。
- 仓库不包含 Google Health APK、反编译专有代码、账号凭据或设备密钥。

## 路线图

1. 扩大共存验证，覆盖更多设备、Google Health 后台自动同步和锁屏通知。
2. 增加 Companion Device presence 支持和 BLE 地址轮换处理。
3. 把包名文本配置改成 3–5 个应用的可视化选择器。
4. 增加 pattern 编辑器、冷却时间和安全预览。
5. 增加可复现的 GitHub Actions 构建与签名 Release APK。

构建方法及许可证说明见 [English README](README.md)。
