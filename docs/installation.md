# 安装、更新与卸载

## 安装前

确认以下条件：

- Android 11 或更高；
- 系统设置中能够正常开启 Dolby 全景声；
- 你理解 DapTune 依赖私有厂商接口，系统更新后必须重新验证；
- 已备份重要自定义曲线。原生备份格式见 [DapTune JSON v1](daptune-json-format.md)。

普通安装不需要 root、LSPosed、Shizuku 或 ADB 常驻。不要为了“提高兼容性”随意刷入仓库里的特定机型
实验模块。

## 安装官方 APK

1. 打开项目 [Releases](https://github.com/silverpoetry/DapTune/releases)；
2. 下载对应版本的 `DapTune-vX.Y.Z.apk` 和 `.sha256`；
3. 校验 SHA-256，并在发布文档公布证书后使用 `apksigner` 校验证书；
4. 允许浏览器或文件管理器“安装未知应用”，完成安装后立即撤回不需要的安装权限；
5. 打开系统 Dolby 全景声，再启动 DapTune。

PowerShell 校验文件摘要：

```powershell
Get-FileHash .\DapTune-vX.Y.Z.apk -Algorithm SHA256
Get-Content .\DapTune-vX.Y.Z.apk.sha256
```

不要把本地 `optimized`、Android Studio debug 或其他人重新签名的 APK 当作官方 Release。不同证书的
APK 不能安全覆盖安装。

## 首次验证

1. 保持较低音量，使用固定的本地音乐或测试音；
2. 在“调音”选择平直并应用；
3. 临时把一个中频段改为明显但安全的衰减，应用后做 A/B；
4. 查看状态是“曲线已回读验证”还是“写入命令已接受”；
5. 恢复目标曲线，分别验证扬声器和实际要使用的耳机路径。

如果系统 Dolby 开关没有任何变化，或者其他系统播放器有效而某一个播放器无效，先按
[故障排查](troubleshooting.md)检查播放路径，不要继续反复写入。

## 自动切换

1. 在“设置”页面设置默认配置；
2. 为历史或当前播放设备设置专属规则；
3. 开启“自动切换”；
4. Android 12 及以上首次启动时允许“附近的设备”；授权前应用不会进入主界面；
5. 如需重启后恢复，再开启“设备重启后恢复自动切换”；
6. 在系统设置中允许后台运行并按需要取消电量限制。

自动切换依赖前台服务。Android 13+ 的通知权限只决定通知能否出现在通知抽屉，不是 AOSP 启动前台
服务的授权；但厂商后台策略可能把“没有可见通知”的应用管理得更严格。需要稳定运行时建议允许低优先级
通知，并确认服务仍出现在系统的活动应用界面。

拒绝“附近的设备”权限时不能进入主界面。权限被运行时撤销后，自动切换不会按名称或匿名地址猜测设备。
重新授权后应用会主动刷新当前播放设备，不需要重新连接耳机。

## 从源码构建

需要 JDK 17 和 Android SDK Platform 36：

```powershell
git clone https://github.com/silverpoetry/DapTune.git
Set-Location .\DapTune
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，应用 ID 是
`com.weich.daptune.debug`。它可以与正式版并存，但数据库、规则和前台服务互不共享。

R8 优化测试包：

```powershell
.\gradlew.bat --no-daemon :app:lintOptimized :app:assembleOptimized
```

该构建仍是 debug 应用 ID 和 debug 证书，不能公开发布。正式签名见
[发布签名](release-signing.md)。

## 更新

- 同一签名、同一应用 ID 的新版本可直接覆盖，Room migration 会保留数据；
- 更新前导出重要自定义配置；
- 更新后确认默认规则、设备绑定和自动切换日志；
- Android 系统大版本或厂商音频更新后，按首次验证流程重新测试。

签名不一致时，Android 会拒绝覆盖。不要通过禁用签名检查强装；先确认 APK 来源，再决定是否卸载旧版。

## 卸载与数据删除

1. 在“设置”关闭“按播放设备切换”；
2. 可选：导出要保留的配置；
3. 从系统设置卸载 DapTune。

卸载会删除本机应用数据库和 DataStore。Android 备份提供程序可能按用户的系统备份设置保留一份数据，
其删除周期由系统和备份提供商控制。如果曾安装 `turner` USB 实验模块，应在 root 管理器中单独禁用或
卸载并重启；卸载主应用不会删除 Magisk/KernelSU 模块。
