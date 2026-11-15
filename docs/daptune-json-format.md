# DapTune JSON 配置格式 v1

本文定义 DapTune 原生配置文件 `com.weich.daptune.profile` 的版本 1。这里的要求与应用
`CurveFileCodec` 的解码行为一致；[JSON Schema](schema/daptune-profile-v1.schema.json)和仓库校验脚本
用于防止示例、文档与代码发生漂移。

## 1. 文件与编码

- 文件内容必须是一个 JSON 对象；
- 建议使用 UTF-8 和扩展名 `.daptune.json`；
- UTF-8 BOM、对象前后的空白可被应用接受；
- 文件最多读取 1,000,000 个 UTF-16 code unit；
- 六个成员全部必填，键的顺序不影响语义；
- 不接受未知成员，不接受 `null`，不进行版本猜测或宽松降级。

## 2. 完整对象结构

```json
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
```

## 3. 字段定义

| 字段 | JSON 类型 | v1 要求 |
|---|---:|---|
| `format` | string | 必须严格等于 `com.weich.daptune.profile` |
| `version` | integer | 必须严格等于 `1` |
| `name` | string | 1–40 个 UTF-16 code unit；首尾不能是空白 |
| `band_plan` | string | 必须严格等于 `dolby-dap-20-v1` |
| `frequencies_hz` | integer array | 必须与下方 20 个中心频率逐项、按顺序完全一致 |
| `gains_q4` | integer array | 必须正好 20 项；第 `i` 项对应第 `i` 个频率 |

`name` 的 40 单位限制与 Kotlin/Android `String.length` 一致。标准 JSON Schema 的 `maxLength`
按 Unicode code point 计数，不能准确表达 UTF-16 code unit，因此 Schema 使用扩展注解
`x-daptune-max-utf16-code-units`，最终以应用解码器和仓库校验脚本为准。

## 4. 固定频点

`dolby-dap-20-v1` 只允许以下数组，不允许删减、重排、替换或使用浮点频率：

| 索引 | Hz | 索引 | Hz | 索引 | Hz | 索引 | Hz |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 47 | 5 | 656 | 10 | 2250 | 15 | 7125 |
| 1 | 141 | 6 | 844 | 11 | 3000 | 16 | 9000 |
| 2 | 234 | 7 | 1031 | 12 | 3750 | 17 | 11250 |
| 3 | 328 | 8 | 1313 | 13 | 4688 | 18 | 13875 |
| 4 | 469 | 9 | 1688 | 14 | 5813 | 19 | 19688 |

频点数组不是显示用元数据，而是格式兼容性断言。未来若采用不同频点计划，必须使用新的
`band_plan` 和格式版本，不能让 v1 文件改变这个数组。

## 5. Q4 增益

每个 `gains_q4` 值是带符号 Q4 dB 整数：

```text
gain_db = gains_q4 / 16
gains_q4 = round(gain_db * 16)
```

例子：

| dB | Q4 |
|---:|---:|
| `+10.0` | `160` |
| `+1.5` | `24` |
| `+0.0625` | `1` |
| `0.0` | `0` |
| `-3.25` | `-52` |
| `-12.0` | `-192` |

约束如下：

- 只接受 JSON integer；`1.0`、字符串 `"16"`、`NaN` 和无穷值都无效；
- 最高值为 `160`，即 `+10 dB`；原生 DapTune JSON 不会自动压缩或裁切超限正增益；
- 最低技术边界是 Kotlin `Int` 的 `-2147483648`，应用不再人为设置 `-10 dB` 等衰减下限；
- 没有独立 `preamp` 字段。需要整体预衰减时，应直接从全部 20 个 Q4 值中减去相同数值；
- 极端负值虽然能被文件和领域模型表示，厂商 DSP 仍可能夹限、忽略或产生静音。创建者应使用合理范围并
  在目标设备上低音量验证。

Q4 是无损交换格式。应用导出再导入不会经过浮点采样；手写 dB 转 Q4 时才需要自行决定取整策略。

应用内可从调音页顶部“导出曲线”或配置卡片“更多 → 导出”选择 DapTune JSON。调音页导出当前编辑结果，
配置卡片导出已保存的配置；导出不会自动保存或应用曲线。文件名使用 `.daptune.json` 后缀，格式版本仍为 1。

## 6. 解码与拒绝条件

应用按以下规则处理 v1 文件：

1. JSON 必须能严格解码成已定义对象，缺失字段或未知字段直接失败；
2. 检查 `format` 和 `version`；
3. 检查 `band_plan` 和完整频点数组；
4. 检查规范化名称；
5. 检查 20 个 Q4 整数及 `+160` 上限；
6. 成功后以 `DAPTUNE_FILE` 来源保存，Q4 数组保持原值。

错误文件不会回退到 GraphicEQ、ParametricEQ 或 CSV 解析器。用户在导入菜单明确选择
“DapTune 配置（JSON）”时也遵循同一规则。

## 7. Schema 和例子

- [JSON Schema Draft 2020-12](schema/daptune-profile-v1.schema.json)
- [平直](../examples/profiles/flat.daptune.json)
- [暖厚](../examples/profiles/warm.daptune.json)
- [深衰减](../examples/profiles/deep-attenuation.daptune.json)

在仓库根目录执行：

```powershell
node .\tools\validate-profile-contract.mjs
```

校验会从 Kotlin 领域模型读取频点、频段数、Q4 比例和正增益上限，再核对 Schema 与全部示例；它还会
保证 `warm.daptune.json` 与应用内置“暖厚”曲线逐 Q4 完全相同。

## 8. 版本兼容策略

v1 读取器只读取版本 1，不假设未知版本向后兼容。新增可选语义、频点计划或单位时，应先定义新版本和
迁移规则；不得向 v1 对象直接增加成员，因为 v1 明确拒绝未知字段。这种封闭策略让拼写错误和不完整转换
尽早失败，而不是静默生成错误曲线。
