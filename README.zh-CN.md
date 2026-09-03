# Fitbit Air Notification Bridge

这是一个实验性、非官方的 Android 开源项目：把用户选定的手机通知转换成 Fitbit Air 上可区分的短震动 pattern。

项目基于 Fitbit 以 Apache-2.0 许可证发布的 [Golden Gate](https://github.com/Fitbit/golden-gate) 传输栈，使用 BLE、Gattlink、DTLS/CoAP 和 Android `NotificationListenerService`。通知处理在手机本地完成，不需要云端中转。

> **当前为 Alpha 版本。** 协议只在一台 Fitbit Air 上完成实机验证。本项目与 Fitbit、Google 无隶属关系，不应作为医疗、紧急或安全关键提醒工具。

> **研究状态——独立 Bridge 会话已于 2026-08-25 冻结。** 在普通无 Root 产品边界内，
> 独立 Bridge 会话无法同时取得 Air 通知创建权限并保持 Google Health 正常同步。
> 下一项研究实验是明确非产品的同进程路径，见
> [`research/google-health-506-same-session/`](research/google-health-506-same-session/README.md)。

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
- 使用不扫描的已知地址直连，让本应用的 GATT client 加入 Google Health 已维持的系统 BLE 链路。
- v10020 会保留每一次连接尝试的真实阶段和错误，并在震动后提供由用户点击的 Google Health task 重启动作。
- v10022 研究版增加前台手动 Switchboard 通知探针；只有对两个已知入口的无 payload GET 都取得严格的路径证据后，才开放第二次写入点击。

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
- v10018 已确认第二个 GATT client 能加入并触发震动，但它不是透明共存方案。
- v10018 实机结果为：震动成功，但仅释放 Bridge client 后 Google Health 同步报 3000；这排除了“两个 DTLS 会话可透明共存”。
- v10019 的 Link Controller 请求在本地返回完成，但实机为 `systemGatt=true/true`、`sawDisconnect=false`；Google Health 仍需正常关闭界面后重新打开才能同步。因此普通无特权 APK 的自动交还路径已判定不可行。
- v10020 清空并重新启动 Google Health Activity task 后，实机同步仍报错误 3000。旧会话因此不属于界面 task，而存在于寿命更长的后台进程状态中。
- v10020 第一次尝试是在系统和本地 GATT 均已连接时，于 `Gattlink/DTLS` 阶段超时；第二次才成功。这符合协议会话被夺取的特征，不是本地蓝牙初始化慢。
- 对 Google Health 5.06.1 的静态核对表明，现役通知传输是 Mobile Data/Switchboard（`POST sb/records`，并用 `POST sb/delete-records` 清理）；旧的 `sendSecureAppNotification` 与 `sendUnsecuredAppNotification` 在该版本没有实际实现。仅凭 APK 仍不能证明 Air 的实时设备模型或固件开放了这条传输。
- v10021 实机已建立 BOOTSTRAP DTLS 会话，但 `GET /.well-known/core` 返回 `4.04`；安全门保持 false，设备没有写入记录。它只证伪了“必须先靠标准目录发现资源”，不能证明两个已知入口不存在，因为 5.06.1 的官方发送器并不先做目录发现。
- v10022 已证明两个 Switchboard 路径存在，但唯一一次受限 `POST sb/records` 在 `PSK=BOOTSTRAP` 会话下返回 `4.01 Unauthorized`，没有震动；Bridge 断开后 Google Health 仍报错误 3000。创建失败，因此没有执行删除请求。
- Google Health 5.06.1 注册了 Mobile Data DTLS resolver：主/备 AES 设备密钥由其已认证的内部设备客户端下载并保存在应用私有 keystore。公开 Google Health paired-device 资源只有 `get`/`list` 元数据，没有命令会话或 DTLS 密钥。因此独立 Bridge 路线目前没有可接受的无 root 后续写入实验。2026-09-02 的后续核对确认 5.06.1 没有可导出的命令 IPC，公开 API 也没有进入 Google Health 现有 Air 会话的入口。
- 项目明确不接受 Shizuku/root/无障碍等提权强停重启方案，也不接受用户在 Bridge 与 Google Health 间人工切换所有权作为正式工作流。
- 当前只构建 `arm64-v8a`。
- pattern 是由已经验证的“切换强度—恢复强度”短震组组成，不是任意马达波形控制。
- 本应用和 Google Health 不能同时主动占用 Fitbit Air；常规扫描/连接模式不能共存。
- 如果 Air 轮换 BLE 地址，当前版本可能需要重新选择一次设备。

## 基本使用

1. 安装应用并允许附近设备/蓝牙权限。
2. 保持 Google Health 正常运行，并确认它已经连接 Air。
3. v10020-v10022 只应作为历史诊断版本。v10022 Switchboard 实验已经完成，不要重复第二步；唯一一次创建返回 `4.01`，没有震动。
4. 不要在日常使用中启用通知规则：仅建立 Bridge 的竞争 DTLS 会话，就可能让 Google Health 持续报错误 3000。
5. DNCS 页面只保留用于复现已经确认的负面结果。

完整阻塞问题、实机证据、明确放弃的绕行方案和验收条件见 [未解决问题](docs/OPEN_PROBLEMS.zh-CN.md)。

## 隐私和安全

- 通知内容只在内存中用于短时去重。
- 监听规则保存在 Android 本地 `SharedPreferences` 中。
- 本项目不会把通知正文、设备密钥或用户数据上传到服务器。
- 仓库不包含 Google Health APK、反编译专有代码、账号凭据或设备密钥。

## 路线图

1. 寻找无需特权、不会夺取或破坏 Google Health 活跃会话的协议震动入口。
2. 不把 Shizuku/root/无障碍强停自动化或人工所有权切换作为产品方案继续推进。
3. 把包名文本配置改成 3–5 个应用的可视化选择器。
4. 增加 pattern 编辑器、冷却时间和安全预览。
5. 增加可复现的 GitHub Actions 构建与签名 Release APK。

构建方法及许可证说明见 [English README](README.md)。
