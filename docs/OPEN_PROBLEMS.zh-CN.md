# 未解决问题

## P0：自定义短震会破坏 Google Health 的 Fitbit Air 会话

状态：**协议级阻塞，尚未解决**。已在一台 Fitbit Air、Google Health
5.05 和 Bridge v10018–v10020 上重复验证。

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

