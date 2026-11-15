# 均衡器导入格式

DapTune 的目标是把常见曲线可靠地转换到固定的 Dolby DAP 20 段频点，而不是假装支持任意音频处理链。
导入菜单既可自动识别，也可明确指定来源；明确指定后不会悄悄切换到另一个解析器。

## 支持概览

| 导入来源 | 支持内容 | 转换方式 |
|---|---|---|
| DapTune JSON | 原生 v1 对象 | 保留全部 Q4 整数，不重采样 |
| GraphicEQ | Wavelet、AutoEq 常见 `GraphicEQ:` 曲线 | 对数频率轴线性插值到 20 段 |
| ParametricEQ | Equalizer APO、AutoEq 的 PEQ 文本子集 | 按 48 kHz 计算 biquad 幅频响应后采样 |
| CSV / TSV / 文本表 | 频率和增益两列，或可识别表头 | 对数频率轴线性插值到 20 段 |
| AutoEq 在线目录 | 官方推荐索引和标准 `GraphicEQ` | 本地检索，按需下载后按 GraphicEQ 转换 |

所有输入最多 1,000,000 个 UTF-16 code unit。频率必须为正有限数，增益必须为有限数，至少需要两个
不同频点。超出源曲线首尾频率范围的 DAP 频点填为 `0 dB`，不做外推。

## AutoEq 在线导入

配置页的 AutoEq 入口读取上游 `results/README.md` 推荐索引。索引下载后在设备本地解析和检索，输入的
搜索词不会发送到搜索服务。只有选中某个条目时，应用才从同一官方仓库按需读取该条目的标准
`GraphicEQ.txt`；随后严格使用下述 GraphicEQ 解析、对数频率插值和 Q4 量化路径。导入结果保存为普通
自定义配置，可继续编辑、重命名或绑定设备。

索引在应用缓存中保留七天；缓存可用时，索引刷新失败会回退到缓存。清除应用缓存会删除该索引，但不会
删除已经导入的配置。在线目录只展示 AutoEq 上游标记为 recommended 的单一结果；需要其他测量来源、
目标曲线或个性化参数时，仍应从文件导入。

## DapTune JSON

这是唯一无损保存原生 20 段 Q4 数据的格式。对象、单位、频点和拒绝规则见
[DapTune JSON v1](daptune-json-format.md)。JSON 不能缺字段、增加字段或使用其他 band plan。

## GraphicEQ

支持一条如下格式的曲线：

```text
Preamp: -2 dB
GraphicEQ: 20 -1.0; 47 0.5; 1000 -2.25; 19688 -4.0; 20000 -4.0
```

规则：

- 必须正好有一条 `GraphicEQ:`；每项必须是 `frequency gain`，项目之间用分号分隔；
- 可包含一个或多个全局 `Preamp:`，其值相加后应用到每个采样点；
- 只接受 `Channel: all`，拒绝左右声道分别处理；
- 允许 `#` 行尾注释；
- 频点可不等于 DAP 频点，转换在 `log10(frequency)` 轴上做分段线性插值；
- 拒绝与 `Filter:`、卷积、Include、Copy、Delay 或 LoudnessCorrection 混合的配置。

Wavelet 和 AutoEq 文件经常使用该格式，但产品名称不是格式保证。导入前仍应查看文件内容。

## ParametricEQ

支持以下 Equalizer APO / AutoEq 子集：

```text
Preamp: -5.0 dB
Channel: all
Filter 1: ON PK Fc 120 Hz Gain 3.5 dB Q 0.80
Filter 2: ON LSC Fc 80 Hz Gain 2.0 dB Q 0.70
Filter 3: ON HSC Fc 8000 Hz Gain -2.5 dB Q 0.71
Filter 4: OFF PK Fc 3000 Hz Gain 9 dB Q 1.00
```

支持的活动滤波器类型：

- `PK`、`PEQ`：峰值；
- `LS`、`LSC`：低架；
- `HS`、`HSC`：高架。

`Fc` 必须大于 0 且低于 24 kHz，`Q` 必须为正；省略 Q 时使用
`0.7071067811865476`。`OFF` 滤波器忽略。多个 `Preamp` 数值相加。全部活动滤波器在 48 kHz
采样率下计算标准 biquad 幅频响应，再在固定 20 个中心频率上求和；因此它是确定性的幅频近似，不保留
原始滤波器、相位、声道或其他处理语义。

拒绝以下内容：

- 分声道配置，例如 `Channel: L`；
- GraphicEQ 与 Filter 混合；
- `Include`、`Copy`、`Convolution`、`Delay`、`LoudnessCorrection`；
- 无法识别的处理指令和不支持的滤波器类型。

## 频率增益表

支持逗号、制表符、分号或空白分隔。无表头时，前两列按 `frequency, gain` 解释：

```csv
47,1.25
1000,-2.0
19688,-4.5
```

有表头时，频率列可命名为 `frequency`、`frequency_hz`、`freq`、`freq_hz` 或 `hz`。增益列按以下
优先级查找：

```text
equalization
equalization_smoothed
eq
gain
gain_db
db
level
level_db
```

这使 AutoEq 的多列测量 CSV 优先使用 `equalization`，不会误把 `raw` 或 `error` 当成目标曲线。双引号或
单引号包裹的单元格会去除外层引号，但解析器不是完整 RFC 4180 CSV 引擎；字段本身不能再包含分隔符。

## 正增益超限

解析阶段保留输入的实际 dB。所有格式和 AutoEq 在导入转换时遵循“设置 → 均衡器”的增益上限。
启用时，如果峰值超过阈值，全部 20 段统一加上 `limit - peak` dB，再量化到 1/16 dB，保留相对增益差。
关闭后不进行整体下移或截平，仅验证数值能否表示。默认阈值为 +10 dB；阈值可修改。
原生 JSON 统一使用版本 1，允许完整 signed Int Q4。文件数值校验与应用增益设置相互独立。

负增益不会因为低于 `-10 dB` 而被裁切；超出 signed Int 技术表示范围则拒绝导入，不静默饱和。
需要消除正增益时，可使用“曲线处理 → 平移 → 峰值归零”。“阈值限制”是用户显式选择的逐段截平操作，
会改变曲线形状，不属于平移。

## 平移精度与边界

手动平移、峰值归零和均值归零共用同一个算法：仅将偏移量取整到 Q4，然后给 20 段加上同一个整数。
峰值归零使用 `-peak`；均值归零使用最接近 `-mean` 的 Q4 偏移量，残余均值绝对值至多 `0.03125 dB`。
界面显示实际偏移量和结果预览。若平移会超过启用的增益上限，操作不可确认，原曲线保留；可修改或关闭上限。
不会通过额外缩放、截平或悄悄减少偏移量来伪装成成功的平移。

## 文件导出

调音页顶部的“导出曲线”可导出当前编辑结果，包括尚未保存为配置的修改；配置页卡片的“更多 → 导出”
导出该配置已保存的曲线。两处都支持 DapTune JSON 和 GraphicEQ 文本，并使用系统文件选择器选择位置。

DapTune JSON 原样保存 20 个 Q4 整数；GraphicEQ 使用固定 20 个频点及最多四位小数的 dB，足以精确表示
1/16 dB。关闭增益限制或阈值足够高时，两种格式往返导入保留全部 Q4 值。GraphicEQ 交换的是幅频曲线，不包含 DAP 的
其他处理参数，也不保证其他均衡器实现产生完全相同的听感。
