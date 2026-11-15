# DapTune JSON 配置格式 v1 / v2

本文定义 `com.weich.daptune.profile` 的版本 1 和版本 2。两者字段、频点和 Q4 单位相同，区别仅在正增益
表示范围。要求与 `CurveFileCodec` 一致；[v1 Schema](schema/daptune-profile-v1.schema.json)、
[v2 Schema](schema/daptune-profile-v2.schema.json)及校验脚本共同约束文件契约。

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

| 字段 | JSON 类型 | 要求 |
|---|---:|---|
| `format` | string | 必须严格等于 `com.weich.daptune.profile` |
| `version` | integer | 必须严格等于 `1` 或 `2` |
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
- v1 最大值为 `160`，即 `+10 dB`；v2 最大值为 `2147483647`，即 `134217727.9375 dB` 的数值表示范围；
- 最低技术边界是 Kotlin `Int` 的 `-2147483648`，应用不再人为设置 `-10 dB` 等衰减下限；
- 没有独立 `preamp` 字段。需要整体预衰减时，应直接从全部 20 个 Q4 值中减去相同数值；
- 数值能被文件和领域模型表示不等于厂商 DSP 支持，设备仍可能夹限、拒绝或忽略。创建者应使用合理范围并
  在目标设备上低音量验证。

Q4 是无损交换格式。解码不重采样；手写 dB 转 Q4 时才需要决定取整策略。导入转换另受用户的增益上限
设置约束：启用时超限曲线会整体下移到阈值并提示；若需保留全部原值，应先关闭限制或提高阈值。

应用内可从调音页顶部“导出曲线”或配置卡片“更多 → 导出”选择 DapTune JSON。调音页导出当前编辑结果，
配置卡片导出已保存的配置；导出不会自动保存或应用曲线。文件名使用 `.daptune.json` 后缀。
若任一频段高于 +160 Q4，自动导出 v2；否则仍导出 v1，以便旧版应用读取。导出不受当前上限设置影响。

## 6. 解码与拒绝条件

应用按以下规则解码：

1. JSON 必须能严格解码成已定义对象，缺失字段或未知字段直接失败；
2. 检查 `format` 和 `version`；
3. 检查 `band_plan` 和完整频点数组；
4. 检查规范化名称；
5. 检查 20 个 signed Int Q4；v1 额外检查 `+160` 上限，v2 不施加产品层上限；
6. 通过版本检查后，再按用户增益设置转换，以 `DAPTUNE_FILE` 来源保存。

错误文件不会回退到 GraphicEQ、ParametricEQ 或 CSV 解析器。用户在导入菜单明确选择
“DapTune 配置（JSON）”时也遵循同一规则。

## 7. Schema 和例子

- [v1 Schema Draft 2020-12](schema/daptune-profile-v1.schema.json)
- [v2 Schema Draft 2020-12](schema/daptune-profile-v2.schema.json)
- [平直](../examples/profiles/flat.daptune.json)
- [暖厚](../examples/profiles/warm.daptune.json)
- [深衰减](../examples/profiles/deep-attenuation.daptune.json)
- [扩展正增益 v2](../examples/profiles/extended-gain.daptune.json)（用于说明格式，不是设备调音建议）

在仓库根目录执行：

```powershell
node .\tools\validate-profile-contract.mjs
```

校验会从 Kotlin 代码读取频点、频段数、Q4 比例和文件版本边界，再核对两份 Schema 与全部示例；它还会
保证 `warm.daptune.json` 与应用内置“暖厚”曲线逐 Q4 完全相同。

## 8. 版本兼容策略

DapTune 0.5.0 起可读取 v1 和 v2；旧版只认识 v1，会拒绝 v2。v2 扩展了数值表示范围，没有新增成员，
也没有更换 `dolby-dap-20-v1` 频点计划。v1 的旧契约保持不变，不能用 `version: 1` 携带超出其范围的值。
修改用户的增益设置不会改变这些格式规则，也不代表改变厂商 DSP 的硬件或固件限制。
