# DapTune

![DapTune 标题图](docs/assets/daptune-hero.png)

[English](README_EN.md) · [安装指南](docs/installation.md) · [兼容性](docs/compatibility.md) ·
[DapTune JSON](docs/daptune-json-format.md) · [导入格式](docs/import-formats.md) ·
[架构](docs/architecture.md) · [问题排查](docs/troubleshooting.md)

[![CI](https://github.com/silverpoetry/DapTune/actions/workflows/ci.yml/badge.svg)](https://github.com/silverpoetry/DapTune/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/silverpoetry/DapTune?display_name=tag&sort=semver)](https://github.com/silverpoetry/DapTune/releases)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)](docs/compatibility.md)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

DapTune 是面向系统已集成 Xiaomi Dolby DAP 的 Android 本地调音工具。它管理固定 20 段曲线，
通过全局 `AudioEffect` session 0 把参数 110（`GraphicEqualizerBandGains`）写入系统现有的
厂商 DSP，并可在播放设备变化时自动应用对应配置。

DapTune 不处理或转发 PCM，不读取录音，不替换 Android 音频路由，也不会开启、破解或模拟 Dolby。
应用只写已经识别的 DAP 参数 110，不修改空间音频、音量均衡或其他 Dolby 参数。

> [!WARNING]
> DapTune 是独立实验性项目，与 Dolby Laboratories、Xiaomi 及相关品牌无关。它操作全局厂商音效，
> 依赖 Android 未公开接口和具体固件实现。系统更新、播放器输出模式或 USB/直通路径都可能改变实际效果。
> 首次安装、系统升级和更换输出路径后，应先在低音量下用明显但安全的测试曲线做 A/B 验证；不要只把
> “命令已接受”当作声音已经经过 DAP。

## 能做什么

### 曲线与配置

- 编辑 Dolby DAP 固定 20 个中心频点，内部使用 signed Q4（1/16 dB）保存和写入；
- 正增益最高 `+10 dB`，不人为设置最大衰减；预览和纵轴会随实际负增益动态扩展；
- 大尺寸交互曲线和 20 段触感滑杆支持点选频点、锁定频段和 0.5 dB 操作步进；
- 保存、复制、覆盖、重命名和删除自定义配置，自定义配置优先排列；
- 提供平直、暖厚、澎湃、响度、人声、明亮和柔和等内置起点。

### 导入、导出与曲线处理

- 导入无损 DapTune JSON、Wavelet/AutoEq `GraphicEQ`、Equalizer APO/AutoEq 参数均衡器以及
  CSV、TSV 或文本频率增益表；
- 可在配置页按耳机型号检索 AutoEq 官方推荐结果，选中后自动下载、转换并保存为自定义配置；
- 支持自动识别，也允许明确指定来源；显式选择后不会静默切换到其他解析器；
- 调音页及配置卡片支持导出无损 DapTune JSON 或 GraphicEQ 文本，调音页导出包含尚未保存的修改；
- “平移”菜单提供自定偏移量、峰值归零和均值归零，统一移动全部频段；超限会提示，不改变曲线形状；
- 平滑、全部归零与硬阈值截平独立提供；超限文件导入默认整体下移至 +10 dB，不再等比压缩；
- 对非原生曲线使用确定性的对数频率插值或 48 kHz biquad 幅频采样，再统一量化到 20 段 Q4。

### 设备自动切换

- 区分手机扬声器、通用有线耳机、具体蓝牙或 LE Audio 设备、USB 和 HDMI 输出；
- 按“精确设备规则 → 默认规则 → 内置平直”选择配置；
- 当前设备切换配置后同步更新该设备规则，不要求跳回调音页；
- 使用事件驱动前台服务，不轮询音频、不持有唤醒锁、不创建第二个常驻进程；
- 可在用户开启自动切换的前提下，于开机或应用更新后恢复服务；
- 日志记录路由、配置、触发来源、写入结果和验证等级，并支持在独立页面清空。
- 关于页支持手动检查 GitHub 正式版本，以及默认开启、24 小时限频的自动检查更新。

### 写入安全

- 启动时检查 effect descriptor、控制权、effect/DAP 开关、profile 数量和回读能力；
- Xiaomi proxy `DAP` 会写入所有 profile、逐项回读 20 段并在不一致时回滚；
- direct `DAP_offload` 按 setter-only 语义处理，不把零填充读取缓冲区误判成真实曲线；
- 写入串行化并始终释放 `AudioEffect`；不满足关键条件时拒绝修改。

## 运行边界

DapTune 的作用域止于“选择一条 20 段曲线并交给系统现有 DAP”。它不会：

- 在应用内实现软件均衡器、卷积器、限制器或其他 PCM DSP；
- 接管播放器、音频焦点、采样率、输出设备或系统 Dolby 总开关；
- 保证每个播放器、每种 USB DAC、独占/direct/offload 或低延迟路径都经过全局 DAP；
- 在没有目标 implementation 的设备上创建 Dolby DSP；
- 通过 root、LSPosed、Shizuku 或持续 ADB 绕过系统权限。

仓库中的 `tools/magisk/daptune-usb-dsp-offload` 是只面向已验证 Xiaomi `turner` 路线的独立
实验工具，不是主应用的依赖，也不适用于其他产品。

## 兼容性概览

Android 版本、手机品牌或设置页显示“杜比全景声”都不能单独证明兼容。DapTune 不维护手机型号白名单，
而是在运行时核对 descriptor 和协议。

| 范围 | 支持状态 | 验证语义 |
|---|---|---|
| Xiaomi proxy `DAP` type `fa81dbde-588b-11ed-9b6a-0242ac120002` | 支持 | 全部 profile 的 20 段逐项回读；失败回滚 |
| Direct `DAP_offload` type `46d279d9-9be7-453d-9d7c-ef937f675587` | 支持 | setter-only；复核控制权、开关和 profile 状态 |
| 经过全局 DAP 的扬声器、蓝牙、有线输出 | 条件支持 | 必须用相同播放器、音量和输出做实际 A/B |
| USB 独立 ALSA、播放器 direct/offload、独占或低延迟输出 | 不保证 | 路径可能完全绕过全局 DAP |
| Android 标准均衡器或其他 implementation | 不支持 | descriptor 不匹配时拒绝写入 |

## 系统要求

- Android 11（API 30）或更高版本；
- implementation UUID 为 `9d4921da-8225-4f29-aefa-39537a04bcaa`；
- 系统 Dolby 与 DAP 已开启，应用能够获得 effect 控制权；
- 当前媒体路径确实经过这个全局 DAP。

已验证固件上的主应用不需要 root、LSPosed、Shizuku 或常驻 ADB。完整 UUID、profile、回读和播放路径
边界见[兼容性说明](docs/compatibility.md)。

## 安装

### 官方发布包

1. 从 [Releases](https://github.com/silverpoetry/DapTune/releases) 下载
   `DapTune-vX.Y.Z.apk` 和同名 `.sha256`；
2. 校验 SHA-256，不安装第三方重打包、Actions 临时产物或 `optimized` 测试包；
3. 保持系统 Dolby 全景声开启，安装后先按下一节做低音量验证；
4. 首次打开时授予“附近的设备”权限；需要自动切换时再按需允许通知并配置后台策略。

~~~powershell
Get-FileHash .\DapTune-vX.Y.Z.apk -Algorithm SHA256
Get-Content .\DapTune-vX.Y.Z.apk.sha256
~~~

如果 Releases 页面还没有版本，表示尚未发布正式签名 APK。此时请按
[从源码构建](docs/installation.md#从源码构建)验证，不要把本地 debug 证书构建当作官方版本。
正式 APK 的签名证书 SHA-256 固定为
`79:62:1F:C9:4C:0C:56:C8:10:8C:BE:C8:32:28:81:7C:D0:6A:E6:1B:05:26:92:53:C3:2D:8D:D2:60:89:4E:F0`。
安装、覆盖更新、签名差异和卸载流程见[安装指南](docs/installation.md)，证书与产物验证见
[发布签名文档](docs/release-signing.md#正式发布证书)。

## 首次使用

1. 在系统设置中打开 Dolby 全景声，并确认系统自己的开关能产生可辨认变化；
2. 打开 DapTune 并授予“附近的设备”权限；该权限是准确识别蓝牙设备的前置条件；
3. 在“调音”选择平直并应用；
4. 低音量下临时对一个中频段做明显衰减，用同一播放器、同一音量和同一输出 A/B；
5. 查看结果是“曲线已回读验证”还是“写入命令已接受”，再恢复目标曲线；
6. 在“配置”导入或保存命名曲线；
7. 在“自动”设置默认配置和各设备规则，再开启自动切换；
8. 如需重启后恢复，开启对应选项，并按设备系统要求允许自启动、后台运行或取消电量限制；
9. 切换输出后查看日志，确认识别到的路由、选中的配置和写入结果。

### 结果状态

| 状态 | 含义 | 能证明什么 |
|---|---|---|
| 曲线已回读验证 | proxy DAP 返回的每个 profile、每个 Q4 值都与目标一致 | 参数存储与写入一致 |
| 写入命令已接受 | setter-only 调用成功，关键 DAP 状态仍有效 | 系统接受了命令，但无法逐值回读 |
| 应用失败 / 已回滚 | 状态检查、写入、保存或回读不满足条件 | 目标曲线不能视为已应用 |

任何状态都不能单独证明当前播放器的 PCM 已经过该 DAP；最终仍需在目标输出路径上听测。

## 自动切换与后台

自动切换只在用户显式开启时运行。服务先建立路由监听，再立即解析并应用当前输出；同一服务生命周期内，
相同设备和相同曲线不会重复写入。设备规则优先于默认配置，删除历史设备会同时移除对应设备记录和规则。

Android 12 及以上首次进入应用前必须授予“附近的设备”权限。DapTune 只在完整蓝牙地址能由系统已配对
设备清单唯一验证时创建持久设备键；匿名地址、设备名称或 MediaRouter 包装 ID 都不会写入设备历史或
参与专属规则。权限在运行期间被撤销后，界面会返回权限页，自动切换也不会按名称猜测；重新授权后立即
刷新当前路由。
路由回调或厂商 API 的单次异常不会终止监听，服务会记录失败并按有上限的退避重新注册。

“设备重启后恢复自动切换”的含义是：自动切换已经开启时，在 `BOOT_COMPLETED` 或应用覆盖更新后恢复
前台服务。它不会代替“自动切换”总开关，也不会在用户明确停止后自行重启。

Android 13+ 拒绝通知权限时，AOSP 仍允许前台服务运行，但通知不会出现在通知抽屉；部分厂商后台管理
可能因此更积极地终止应用。需要长期稳定切换时，建议允许低优先级通知，并确认系统“活动应用”中仍能
看到服务。详见[兼容性说明](docs/compatibility.md#自动切换与通知)。

## 配置与导入格式

| 来源 | 支持范围 | 转换 |
|---|---|---|
| DapTune JSON v1 | 固定 20 段 signed Q4 | 无损，不重采样 |
| GraphicEQ | Wavelet、AutoEq 常见 `GraphicEQ:` 与全局 `Preamp:` | 对数频率轴线性插值 |
| ParametricEQ | `PK/PEQ`、`LS/LSC`、`HS/HSC`，可含全局 `Preamp:` | 按 48 kHz 求 biquad 幅频响应 |
| CSV / TSV / 文本表 | 两列频率与增益，或受支持的表头 | 对数频率轴线性插值 |
| AutoEq 在线目录 | 官方推荐结果中的耳机型号与标准 `GraphicEQ` | 本地检索，按需下载，再走同一转换器 |

不支持独立左右声道、卷积、Include、Copy、Delay、LoudnessCorrection 或无法映射到单条 20 段幅频
曲线的指令；应用会明确拒绝，而不是静默丢弃。完整语法、表头优先级、超范围行为和正增益超限策略见
[导入格式说明](docs/import-formats.md)。

### DapTune JSON v1

原生格式扩展名建议为 `.daptune.json`，六个字段全部必填，未知字段会被拒绝：

~~~json
{
  "format": "com.weich.daptune.profile",
  "version": 1,
  "name": "Flat",
  "band_plan": "dolby-dap-20-v1",
  "frequencies_hz": [
    47, 141, 234, 328, 469, 656, 844, 1031, 1313, 1688,
    2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 13875, 19688
  ],
  "gains_q4": [
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  ]
}
~~~

`gains_q4` 使用 `dB = Q4 / 16`；最大值是 `160`（`+10 dB`），负值没有产品层人为下限。
没有独立 `preamp` 字段，整体预衰减应直接减去全部 20 个 Q4 值。

- [完整 DapTune JSON v1 规范](docs/daptune-json-format.md)
- [JSON Schema Draft 2020-12](docs/schema/daptune-profile-v1.schema.json)
- [平直示例](examples/profiles/flat.daptune.json)
- [暖厚示例](examples/profiles/warm.daptune.json)
- [深衰减示例](examples/profiles/deep-attenuation.daptune.json)

## 设计边界

~~~text
文件 / 20 段编辑器
        │
        ▼
EqCurve：20 个 signed Q4 Int ───────┐
                                     │
Android 输出路由事件                 │
        │                            │
        ▼                            ▼
PlaybackRouteMonitor ──> 设备规则 ──> ApplyEqCurve
                                         │
                                         ▼
                                DapWriter 事务
                                         │
                                         ▼
                           AudioEffect session 0
                           参数 110 / 全部 profile
                              │                 │
                              ▼                 ▼
                       proxy：回读/回滚   offload：setter-only
~~~

工程采用多模块、单向依赖和本地优先设计：

- `core:model`：不可变曲线、配置、路由、DAP 结果和日志模型；
- `core:eq`：严格解析、插值、biquad 采样、Q4 量化和曲线变换；
- `domain`：仓储与平台边界、配置选择和应用用例；
- `data`：Room、DataStore 和仓储实现；
- `platform:dap`：descriptor 分类、私有 `AudioEffect` 桥和写入事务；
- `platform:routing`：输出事件、设备身份和隐私安全的稳定键；
- `feature:*`：Material 3 界面、ViewModel 和前台服务；
- `app`：Hilt、Compose 导航和应用入口。

完整数据流、依赖约束和架构决策见[架构文档](docs/architecture.md)与
[ADR 目录](docs/adr)。

## 隐私与安全

- `INTERNET` 只用于读取 AutoEq 官方内容和本项目 GitHub Release 更新元数据；
- 不声明麦克风、摄像头、位置或媒体读取权限；
- 不包含分析、广告、遥测、远程崩溃上报或上传端点；
- 不读取、录制、缓存或上传音频；
- 原始蓝牙地址不写数据库；仅由系统已配对设备清单验证后的地址在本机生成 SHA-256 设备键；
- 配置、规则、设备显示名和可清空日志仅保存在应用私有存储，系统备份行为由 Android 用户设置决定；
- 安全问题请使用
  [GitHub Private Vulnerability Reporting](https://github.com/silverpoetry/DapTune/security/advisories/new)，
  不要公开提交设备标识、完整系统转储、厂商文件或签名材料。

详见[隐私说明](PRIVACY.md)和[安全策略](SECURITY.md)。

## 构建与验证

需要 Node.js 20 或更高版本、JDK 17、Android SDK Platform 36 和项目提交的 Gradle Wrapper：

~~~powershell
npx --yes markdownlint-cli2@0.18.1 "*.md" "docs/**/*.md" "tools/**/*.md" ".github/**/*.md"
node .\tools\validate-docs.mjs
node .\tools\validate-profile-contract.mjs
.\gradlew.bat --no-daemon testDebugUnitTest lintRelease :app:assembleRelease
~~~

`release` 启用 R8 和资源收缩；没有完整签名环境变量时只生成未签名 APK，用于源码验证。
`optimized` 使用同样优化，但采用 `.debug` 应用 ID 和本机 debug 证书，只用于测试机性能验证，
不能作为正式发布包。

CI 会检查 Markdown 和站内链接、DapTune JSON 规范/Schema/示例与 Kotlin 常量的一致性、Gradle
Wrapper、JVM 单元测试、Android Lint 和 R8 Release 构建。`v*` 标签发布工作流会核对
`versionName`、使用仓库 Secrets 签名、用 `apksigner` 验证，并同时发布 APK 和 SHA-256。
完整发布流程见[发布签名与产物验证](docs/release-signing.md)。

## 常见问题

- **提示“未找到兼容的 Dolby DAP”**：先核对系统 Dolby、implementation UUID 和系统更新；
- **显示已应用但声音完全一样**：优先检查播放器 direct/offload、输出顺序和目标路由是否绕过 DAP；
- **USB 有线耳机无效**：USB DAC 可能走独立 ALSA handler，主应用无法强制改写硬件路由；
- **划掉任务后不再自动切换**：确认自动切换、前台服务、通知和厂商后台策略；
- **导入失败或曲线不对**：显式选择来源并检查不支持的声道、滤波器或超限正增益。

逐项诊断步骤和提交问题所需的最小脱敏信息见[问题排查](docs/troubleshooting.md)。

## 文档

- [安装、更新与卸载](docs/installation.md)
- [兼容性与已知限制](docs/compatibility.md)
- [DapTune JSON 配置格式 v1](docs/daptune-json-format.md)
- [常见均衡器导入格式](docs/import-formats.md)
- [系统架构](docs/architecture.md)
- [问题排查](docs/troubleshooting.md)
- [发布签名与产物验证](docs/release-signing.md)
- [架构决策记录](docs/adr)
- [更新记录](CHANGELOG.md)
- [隐私说明](PRIVACY.md)
- [安全策略](SECURITY.md)
- [贡献指南](CONTRIBUTING.md)
- [第三方许可](THIRD_PARTY_NOTICES.md)

## 贡献

改变 DAP descriptor、协议、回读语义或播放路径支持时，必须提供可复现且已脱敏的实机证据；格式变化
必须同步更新解码器、单元测试、JSON Schema、例子和规范。请勿提交厂商 APK、完整 framework、真实
录音、设备序列号、蓝牙地址、IP、账号或签名材料。完整要求见[贡献指南](CONTRIBUTING.md)。

## 许可与商标

DapTune 以 [Apache License 2.0](LICENSE) 发布，第三方组件及许可见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Dolby、Dolby Atmos、Xiaomi、Android 及其他名称和商标归各自权利人所有；这里的引用只用于描述
兼容接口和测试环境，不表示认可、授权或隶属关系。
