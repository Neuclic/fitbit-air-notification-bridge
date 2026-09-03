# 未解决问题

## P0：自定义短震会破坏 Google Health 的 Fitbit Air 会话

状态：**研究冻结 / 产品阻塞**。已在一台 Fitbit Air、Google Health
5.05–5.06.1 和 Bridge v10018–v10022 上验证。独立 Bridge 会话目前不存在
可接受的无 Root 后续实验。

### 实机证据

1. Google Health 已连接 Air，原本可以正常同步。
2. Bridge 按保存地址加入 Android 已存在的系统 GATT 链路，不进行扫描。
3. v10020 第一次尝试显示 `systemGatt=true`、`localGatt=true`、
   `source=SYSTEM_IMPORT`，但 `Gattlink/DTLS` 在 18 秒内没有产生连接事件并超时。
4. 等待 2 秒后，第二次尝试建立 DTLS 并成功执行了经过复核的短震。
5. Bridge 关闭自己的 client 后，Android 仍显示系统 GATT 链路连接，
   即 `before/after=true/true`。
6. Google Health 随后的真实手动同步报错误 3000。
7. v10020 清空并重新启动 Google Health 的 Activity task 后仍报 3000；
   测试手机上只有用户正常关闭 Google Health、稍后重新打开才会恢复。

### 技术判断

第一次失败并非本地 GATT 初始化慢，因为超时时系统和本地 GATT 都已连接。
更符合实机现象的解释是：第一次 Gattlink/DTLS 尝试使 Google Health 的私有协议
会话失效或被挤下，第二次尝试随后成为当前会话所有者。Bridge 关闭自己的 client
后，Google Health 后台进程仍保存着过期会话状态。Android 的物理链路仍为连接态，
不能证明协议共存或所有权交还成功。

普通第三方 APK 没有 Android 公开接口来复用 Google Health 的
`BluetoothGatt`/DTLS 会话、要求其私有会话重新协商，或结束其后台进程。
v10019 的 Link Controller 断链实验也只在本地返回完成，没有出现真实链路周期，
即 `sawDisconnect=false`。

### Google Health 5.06.1 通知协议新证据

对本地精确 5.06.1 安装包的只读核对缩小了协议问题，但没有改变 P0 结论。
该版本实际使用 Mobile Data 协议 `0x6001` 承载 Switchboard 记录：通过
`POST sb/records` 创建，再通过 `POST sb/delete-records` 清理。旧的
`sendSecureAppNotification` 与 `sendUnsecuredAppNotification` 在这个发布版中
没有实际实现。

官方“测试通知”流程会从本地设备模型中选择 `notifications=true` 的设备；
Switchboard 是否可用则由每台设备独立的 `SwitchboardSpecification` 表示。
这些值来自实时设备/服务器状态，APK 本身不能证明 Air 最终取得了什么值。

v10021 实机已完成 BOOTSTRAP DTLS 会话，但 `GET /.well-known/core` 在
807 ms 后返回 `4.04`。安全门保持 false，没有向设备写入记录。再次核对字节码后
确认，5.06.1 官方发送器会直接为 `sb/records` 与 `sb/delete-records` 构造
CoAP POST，并不依赖标准资源目录。因此该结果只证伪了目录发现这个前置条件，
不能证明两个已知入口不存在。

v10022 改为对每个已知路径各发送一次无 payload、无重传的 GET。只有两个路径
都返回 `2.xx` 或精确的 `4.05 Method Not Allowed`，才开放一次手动创建尝试；
`4.04`、超时和其他响应一律 fail closed。任何创建尝试之后都必须重新通过安全门。
探针仍使用 60 秒 TTL、两秒后删除、不重试创建，不会从
`NotificationListenerService` 自动运行，也不声称复用了 Google Health 会话。

v10022 的实机只读安全门已在 116 ms 内通过：两个路径都取得了允许值，其中画面
明确显示 `GET sb/delete-records -> 4.05`，界面随后开放第二步，且此时尚未写入
记录。保留截图裁掉了创建路径的精确响应码，因此当前只能确认它属于门控接受的
`2.xx` 或 `4.05`。

随后唯一一次受限创建在 529 ms 后返回 `POST sb/records -> 4.01
Unauthorized`，Air 没有震动。创建没有成功，因此探针未发送删除请求。Bridge
断开后，Google Health 手动同步仍报错误 3000。

5.06.1 还会注册独立的 `MobileDataTlsKeyResolver`：provider 按 `MD-*` 身份
取得主/备 AES 设备密钥；密钥由 Google Health 已认证的内部设备客户端下载，
并保存在其应用私有 BKS 文件中。公开 Google Health `users.pairedDevices` 资源
只有只读范围下的 `get`/`list` 元数据，没有 DTLS key 或设备命令方法。因此，
安全会话身份是 `4.01` 的最强解释；但没有固件证据时，仍不能排除设备功能或
entitlement 门控共同参与。

相关 GATT characteristic 也不是密钥导出入口。Google Health 的读取路径把返回
值当作 UTF-8 identity/state；真正的 secret 先从私有 key provider 取得，再由
官方 app 写入 Air。让 Bridge 写入自生成 key 会修改设备信任状态，并可能挤掉
Google Health 的 key，因此不在可接受边界内。

独立会话 Switchboard 路线至此应作为产品方向关闭：它既不能获准创建通知，
也不能保持 Google Health 同步。不要继续更换 payload 重试，也不要尝试提取或
复用 Google Health 私有密钥。

2026-09-02 的后续核对在没有再次写入设备的前提下，把冻结时留下的两个重开
条件对着同一份 5.06.1 安装包关掉了。公开 paired-device 资源仍只有 `get`/`list`
元数据。5.06.1 的 manifest 里没有可导出的 service、provider 或 receiver 能提交
Switchboard 记录，或进入 `GoldenGateCommandHandler`。应用内唯一的通知广播是
`exported=false`。Deeplink 只会打开界面。`GET 4.05` 之后的 `POST 4.01` 符合
Golden Gate 把已认证会话和 BOOTSTRAP 分成不同 CoAP group 的做法，换 payload
不能取得写入授权。

普通无特权应用仍然没有进入 Google Health 现有 Air 会话的路径。因此实现上的
后续是单独的、明确非产品的同进程实验，见
`research/google-health-506-same-session/`：对这份精确 5.06.1 做研究签名，
在进程内加载 Frida Gadget，复用现有 `GoldenGateCommandHandler`。它不是普通
第三方 APK，也不解除产品冻结。

### 研究冻结决定——2026-08-25

在本项目既定边界内——普通无 Root Android、保持 Google Health 可用、不借助
提权控制其进程、也不要求用户人工切换连接所有权——独立 Bridge 连接方向已经
没有合理的下一项实现或实机测试。v10013–v10022 仅作为协议知识和负向证据保留，
不再视为可以继续递进的产品版本。

这是一项有边界的冻结决定，不表示 Air 通知功能永远不可能实现。只有先独立确认
以下任一前提，才重开实现研究：

- Google/Fitbit 提供正式设备命令或通知 API；或者
- 存在普通 app 可调用的 IPC/API，能够进入 Google Health 已授权的 Air Handler
  和现有 Gattlink/DTLS 会话。

如果未来决定完全替代 Google Health，则应另立完整伴侣 App 项目，重新处理配对、
密钥配置、数据同步、恢复和验收；它不属于现有 Bridge 路线的继续开发。

### 产品决策

项目明确不把以下方案视为可接受解法：

- 使用 Shizuku、root、无障碍自动化或其他提权方式强行停止并重启 Google Health。
- 把用户人工切换 Bridge 所有权模式和 Google Health 同步模式作为正常产品流程。

因此，自定义通知 Bridge 暂时不能用于日常场景。只有找到不会夺取或破坏
Google Health 活跃 Air 会话的非侵入式协议入口后，才能继续产品化。

### 解除阻塞的验收条件

未来方案必须在普通、无特权 APK 上同时满足：

1. 成功触发指定的有限短震。
2. 不强停 Google Health、不做界面自动化，也不要求用户人工切换所有权。
3. 震动后 Google Health 能完成一次真实同步且不报错误 3000。
4. 连续重复多次仍成立，不出现不断增长的等待或过期会话。
