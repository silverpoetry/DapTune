# 贡献指南

DapTune 操作全局厂商 DSP。正确性、协议证据、可回滚性、隐私和明确的兼容边界优先于扩大设备列表或
“看起来成功”。

## 开发环境

- JDK 17；
- Android SDK Platform 36；
- Node.js 20 或更高，仅用于仓库文档与格式契约校验；
- 一台可恢复、明确提供兼容 DAP 的测试设备，涉及厂商接口时使用。

提交前运行：

~~~powershell
npx --yes markdownlint-cli2@0.18.1 "*.md" "docs/**/*.md" "tools/**/*.md" ".github/**/*.md"
node .\tools\validate-docs.mjs
node .\tools\validate-profile-contract.mjs
.\gradlew.bat --no-daemon testDebugUnitTest lintRelease :app:assembleRelease
~~~

CI 使用未签名 R8 Release 验证源码。不要提交本机 keystore 或通过 debug 签名伪造正式产物。

## 设计规则

1. 领域模型和 EQ 数学保持纯 Kotlin，Android 与厂商 API 留在 `platform:*`；
2. UI、数据层和路由层不得复制 DAP payload 编码或各自维护频点表；
3. 20 段曲线始终使用 signed Int Q4；应用增益上限由用户设置，不得硬编码为厂商上限，也不要加入人为衰减下限；
4. 新 DAP 分支必须基于 descriptor/protocol 证据，不按营销名称或设备型号猜测；
5. 不把 setter-only 零缓冲区当作真实回读，不把“命令接受”描述为“曲线已验证”；
6. DAP 写入必须串行、释放 effect，并为部分写入提供明确失败语义；
7. 自动切换保持事件驱动，不增加轮询、唤醒锁、第二进程或多层后台兜底；
8. 路由键不得持久化原始蓝牙地址；日志必须可清空且不包含音频内容；
9. 格式变化同时更新 Kotlin 解码器、单元测试、JSON Schema、示例和格式规范；
10. 不静默忽略无法映射到单条 20 段幅频曲线的 EQ 指令。

## 兼容性证据

改变 DAP descriptor、命令或播放路径支持时，PR 至少提供：

- Android 版本、设备代号和固件版本；
- effect name、implementation UUID、type UUID 和回读语义；
- 写入前后最小参数证据，敏感数据已删除；
- 代理型与 setter-only 的失败、部分写入和恢复行为；
- 扬声器、有线、蓝牙和目标特殊路由的回归结果；
- 系统 Dolby 设置页以及至少两个播放器的 A/B 结果。

不得提交厂商 APK、反编译源码树、完整 framework/分区文件、完整 `dumpsys`、真实录音、设备序列号、
IP、个人蓝牙地址、账户数据或签名材料。

## 代码、文档与提交

- Kotlin/KTS 使用 4 空格，XML/JSON/YAML/Markdown 使用 2 空格或现有格式；
- 除 Windows batch 外使用 LF；
- 公共契约写出单位、边界、舍入、错误和版本策略；
- 纯策略、转换、协议与 migration 必须有确定性单元测试；
- 提交标题简短、祈使或约定式，PR 说明原因、风险、证据和验证结果；
- 一个 PR 只解决一个完整问题，不顺手格式化无关模块。

提交贡献即表示你同意按 Apache License 2.0 分发该贡献。
