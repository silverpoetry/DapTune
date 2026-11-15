package com.weich.daptune.core.eq

import com.weich.daptune.core.model.DapBandPlan
import com.weich.daptune.core.model.EqCurve
import com.weich.daptune.core.model.GainLimit
import com.weich.daptune.core.model.ProfileSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ImportedCurve(
    val suggestedName: String,
    val gainsDb: List<Double>,
    val source: ProfileSource,
    val warnings: List<String> = emptyList(),
) {
    val minimumDb: Double get() = gainsDb.min()
    val maximumDb: Double get() = gainsDb.max()
    fun exceedsLimit(gainLimit: GainLimit = GainLimit()): Boolean =
        gainLimit.enabled && maximumDb > gainLimit.maximumDb
}

class CurveImportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Explicit values bypass detection and are never silently parsed as another format. */
enum class CurveImportFormat {
    AUTOMATIC,
    DAPTUNE_JSON,
    GRAPHIC_EQ,
    PARAMETRIC_EQ,
    FREQUENCY_GAIN_TABLE,
}

enum class CurveExportFormat(val mimeType: String, val extension: String) {
    DAPTUNE_JSON("application/json", "daptune.json"),
    GRAPHIC_EQ("text/plain", "GraphicEQ.txt"),
}

data class CurveExportDocument(val fileName: String, val mimeType: String, val text: String)

object CurveFileCodec {
    const val MAX_IMPORT_CHARACTERS: Int = 1_000_000

    private const val FileFormat = "com.weich.daptune.profile"
    private const val FileVersion = 1
    private const val DefaultSampleRate = 48_000.0

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun import(
        text: String,
        fileName: String,
        format: CurveImportFormat = CurveImportFormat.AUTOMATIC,
    ): ImportedCurve {
        require(text.length <= MAX_IMPORT_CHARACTERS) { "文件过大" }
        val normalized = text.removePrefix("\uFEFF").trim()
        if (normalized.isEmpty()) throw CurveImportException("文件为空")
        return try {
            when (format.takeUnless { it == CurveImportFormat.AUTOMATIC } ?: detectFormat(normalized)) {
                CurveImportFormat.AUTOMATIC -> error("Automatic format must be resolved")
                CurveImportFormat.DAPTUNE_JSON -> importNative(normalized, fileName)
                CurveImportFormat.GRAPHIC_EQ -> importGraphicEq(normalized, fileName)
                CurveImportFormat.PARAMETRIC_EQ -> importParametricEq(normalized, fileName)
                CurveImportFormat.FREQUENCY_GAIN_TABLE -> importDelimited(normalized, fileName)
            }
        } catch (error: CurveImportException) {
            throw error
        } catch (error: Exception) {
            throw CurveImportException("无法解析均衡器文件：${error.message ?: error::class.simpleName}", error)
        }
    }

    private fun detectFormat(text: String): CurveImportFormat = when {
        text.startsWith("{") -> CurveImportFormat.DAPTUNE_JSON
        graphicEqLines(text).isNotEmpty() -> CurveImportFormat.GRAPHIC_EQ
        text.lineSequence().any { rawLine ->
            val line = stripComment(rawLine)
            filterDirectiveRegex.containsMatchIn(line) ||
                line.startsWith("Preamp", ignoreCase = true) ||
                line.startsWith("Channel", ignoreCase = true) ||
                unsupportedProcessingDirectiveRegex.containsMatchIn(line)
        } -> CurveImportFormat.PARAMETRIC_EQ
        else -> CurveImportFormat.FREQUENCY_GAIN_TABLE
    }

    fun exportNative(name: String, curve: EqCurve): String {
        require(isCanonicalProfileName(name)) {
            "Profile name must contain 1–$MaxProfileNameLength characters without surrounding whitespace"
        }
        return json.encodeToString(
            ProfileFileDto(
                format = FileFormat,
                version = FileVersion,
                name = name,
                bandPlan = DapBandPlan.id,
                frequenciesHz = DapBandPlan.frequenciesHz.asList(),
                gainsQ4 = curve.toQ4List(),
            ),
        )
    }

    fun exportDocument(name: String, curve: EqCurve, format: CurveExportFormat): CurveExportDocument {
        require(isCanonicalProfileName(name)) { "配置名称无效" }
        val fileName = name.map { character ->
            if (character.isISOControl() || character in "\\/:*?\"<>|") '_' else character
        }.joinToString("").trim(' ', '.').ifEmpty { "curve" }
        return CurveExportDocument(
            fileName = "$fileName.${format.extension}",
            mimeType = format.mimeType,
            text = when (format) {
                CurveExportFormat.DAPTUNE_JSON -> exportNative(name, curve)
                CurveExportFormat.GRAPHIC_EQ -> exportGraphicEq(curve)
            },
        )
    }

    fun exportGraphicEq(curve: EqCurve): String = buildString {
        append("GraphicEQ: ")
        DapBandPlan.frequenciesHz.forEachIndexed { index, frequency ->
            if (index > 0) append("; ")
            append(frequency)
            append(' ')
            append(formatGain(curve.gainDb(index)))
        }
        append('\n')
    }

    private fun importNative(text: String, fileName: String): ImportedCurve {
        val dto = json.decodeFromString<ProfileFileDto>(text)
        if (dto.format != FileFormat || dto.version != FileVersion) {
            throw CurveImportException("不支持的 DapTune 文件版本")
        }
        if (dto.bandPlan != DapBandPlan.id || dto.frequenciesHz != DapBandPlan.frequenciesHz.asList()) {
            throw CurveImportException("文件使用了不兼容的频点")
        }
        val profileName = dto.name.trim()
        if (!isCanonicalProfileName(dto.name)) {
            throw CurveImportException("配置名称必须为 1–$MaxProfileNameLength 个字符")
        }
        val curve = try {
            EqCurve.ofQ4(dto.gainsQ4)
        } catch (error: IllegalArgumentException) {
            throw CurveImportException("文件中的增益数组无效", error)
        }
        return ImportedCurve(
            suggestedName = profileName,
            gainsDb = curve.toDbList(),
            source = ProfileSource.DAPTUNE_FILE,
        )
    }

    private fun importGraphicEq(text: String, fileName: String): ImportedCurve {
        val lines = graphicEqLines(text)
        if (lines.size != 1) throw CurveImportException("文件必须只包含一条 GraphicEQ 曲线")
        validateRepresentableDirectives(text, allowGraphicEq = true)
        val payload = lines.single().substringAfter(':')
        val points = payload.split(';').map(String::trim).filter(String::isNotEmpty).mapIndexed { index, token ->
            val values = token.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (values.size != 2) throw CurveImportException("GraphicEQ 第 ${index + 1} 个频点格式错误")
            FrequencyGain(
                frequencyHz = values[0].toDoubleOrNull()
                    ?: throw CurveImportException("GraphicEQ 频率无效：${values[0]}"),
                gainDb = values[1].toDoubleOrNull()
                    ?: throw CurveImportException("GraphicEQ 增益无效：${values[1]}"),
            )
        }
        validatePoints(points)
        val preamp = parsePreamp(text)
        return ImportedCurve(
            suggestedName = baseName(fileName),
            gainsDb = sampleLogLinear(points).map { it + preamp },
            source = ProfileSource.GRAPHIC_EQ,
        )
    }

    private fun importParametricEq(text: String, fileName: String): ImportedCurve {
        val filters = mutableListOf<Biquad>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = stripComment(rawLine)
            if (line.isEmpty() || line.startsWith("Preamp", ignoreCase = true)) return@forEachIndexed
            validateRepresentableDirective(line, index + 1, allowGraphicEq = false)
            if (!line.startsWith("Filter", ignoreCase = true)) return@forEachIndexed
            if (Regex("(?i):\\s*OFF\\b").containsMatchIn(line)) return@forEachIndexed
            val match = activeFilterRegex.find(line)
                ?: throw CurveImportException("第 ${index + 1} 行包含不支持的滤波器")
            val type = match.groupValues[1].uppercase().removeSuffix("C")
            val frequency = match.groupValues[2].toDouble()
            val gain = match.groupValues[3].toDouble()
            val q = match.groupValues[4].takeIf(String::isNotBlank)?.toDouble() ?: DEFAULT_Q
            if (frequency <= 0.0 || frequency >= DefaultSampleRate / 2.0 || q <= 0.0) {
                throw CurveImportException("第 ${index + 1} 行的频率或 Q 值无效")
            }
            filters += Biquad.create(type, frequency, gain, q, DefaultSampleRate)
        }
        val preamp = parsePreamp(text)
        val hasPreamp = text.lineSequence()
            .map(::stripComment)
            .any { it.startsWith("Preamp", ignoreCase = true) }
        if (filters.isEmpty() && !hasPreamp) {
            throw CurveImportException("没有找到可用的参数均衡器滤波器")
        }
        val gains = DapBandPlan.frequenciesHz.map { frequency ->
            preamp + filters.sumOf { it.magnitudeDb(frequency.toDouble(), DefaultSampleRate) }
        }
        return ImportedCurve(
            suggestedName = baseName(fileName),
            gainsDb = gains,
            source = ProfileSource.PARAMETRIC_EQ,
            warnings = listOf("参数均衡器按 48 kHz 转换"),
        )
    }

    private fun importDelimited(text: String, fileName: String): ImportedCurve {
        val rows = text.lineSequence().mapIndexedNotNull { index, rawLine ->
            val line = stripComment(rawLine)
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")) {
                null
            } else {
                TableRow(index + 1, splitTableRow(line))
            }
        }.toList()
        if (rows.isEmpty()) throw CurveImportException("没有找到频率和增益数据")

        val firstRow = rows.first()
        val hasHeader = firstRow.values.any { it.toDoubleOrNull() == null }
        val columns = if (hasHeader) resolveTableColumns(firstRow) else TableColumns(0, 1)
        val dataRows = if (hasHeader) rows.drop(1) else rows
        val requiredColumn = maxOf(columns.frequency, columns.gain)
        val points = dataRows.map { row ->
            if (row.values.size <= requiredColumn) {
                throw CurveImportException("第 ${row.lineNumber} 行缺少频率或增益")
            }
            val frequency = row.values[columns.frequency].toDoubleOrNull()
                ?: throw CurveImportException("第 ${row.lineNumber} 行的频率不是有效数字")
            val gain = row.values[columns.gain].toDoubleOrNull()
                ?: throw CurveImportException("第 ${row.lineNumber} 行的增益不是有效数字")
            FrequencyGain(frequency, gain)
        }
        validatePoints(points)
        return ImportedCurve(
            suggestedName = baseName(fileName),
            gainsDb = sampleLogLinear(points),
            source = ProfileSource.CSV,
        )
    }

    private fun sampleLogLinear(points: List<FrequencyGain>): List<Double> {
        val sorted = points.sortedBy(FrequencyGain::frequencyHz)
        return DapBandPlan.frequenciesHz.map { targetInt ->
            val target = targetInt.toDouble()
            if (target < sorted.first().frequencyHz || target > sorted.last().frequencyHz) {
                0.0
            } else {
                val exact = sorted.indexOfFirst { it.frequencyHz == target }
                if (exact >= 0) {
                    sorted[exact].gainDb
                } else {
                    val upperIndex = sorted.indexOfFirst { it.frequencyHz > target }
                    val lower = sorted[upperIndex - 1]
                    val upper = sorted[upperIndex]
                    val ratio = (log10(target) - log10(lower.frequencyHz)) /
                        (log10(upper.frequencyHz) - log10(lower.frequencyHz))
                    lower.gainDb + (upper.gainDb - lower.gainDb) * ratio
                }
            }
        }
    }

    private fun validatePoints(points: List<FrequencyGain>) {
        if (points.size < 2) throw CurveImportException("至少需要两个频点")
        if (points.any { !it.frequencyHz.isFinite() || !it.gainDb.isFinite() || it.frequencyHz <= 0.0 }) {
            throw CurveImportException("文件包含无效频率或增益")
        }
        val sorted = points.sortedBy(FrequencyGain::frequencyHz)
        if (sorted.zipWithNext().any { (first, second) -> first.frequencyHz == second.frequencyHz }) {
            throw CurveImportException("文件包含重复频率")
        }
    }

    private fun parsePreamp(text: String): Double = text.lineSequence()
        .mapIndexedNotNull { index, rawLine ->
            val line = stripComment(rawLine)
            if (!line.startsWith("Preamp", ignoreCase = true)) return@mapIndexedNotNull null
            preampLineRegex.matchEntire(line)?.groupValues?.get(1)?.toDouble()
                ?: throw CurveImportException("第 ${index + 1} 行的 Preamp 格式无效")
        }
        .sum()

    private fun graphicEqLines(text: String): List<String> = text.lineSequence()
        .map(::stripComment)
        .filter { it.startsWith("GraphicEQ", ignoreCase = true) && ':' in it }
        .toList()

    private fun baseName(fileName: String): String = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBeforeLast('.', fileName)
        .removeSuffix("_GraphicEQ")
        .removeSuffix(" GraphicEQ")
        .removeSuffix("_ParametricEQ")
        .removeSuffix(" ParametricEQ")
        .trim()
        .ifBlank { "导入配置" }

    private fun isCanonicalProfileName(name: String): Boolean =
        name == name.trim() && name.isNotEmpty() && name.length <= MaxProfileNameLength

    private fun stripComment(line: String): String = line.substringBefore('#').trim()

    private fun validateRepresentableDirectives(text: String, allowGraphicEq: Boolean) {
        text.lineSequence().forEachIndexed { index, rawLine ->
            validateRepresentableDirective(stripComment(rawLine), index + 1, allowGraphicEq)
        }
    }

    private fun validateRepresentableDirective(
        line: String,
        lineNumber: Int,
        allowGraphicEq: Boolean,
    ) {
        if (line.isEmpty()) return
        if (line.startsWith("Channel", ignoreCase = true)) {
            if (!allChannelsRegex.matches(line)) {
                throw CurveImportException("第 $lineNumber 行使用了无法映射的分声道处理")
            }
            return
        }
        if (line.startsWith("Preamp", ignoreCase = true)) return
        if (filterDirectiveRegex.containsMatchIn(line) && allowGraphicEq) {
            throw CurveImportException("不能把 GraphicEQ 与参数滤波器混合转换")
        }
        if (graphicEqDirectiveRegex.containsMatchIn(line) && !allowGraphicEq) {
            throw CurveImportException("不能把参数滤波器与 GraphicEQ 混合转换")
        }
        if (unsupportedProcessingDirectiveRegex.containsMatchIn(line)) {
            throw CurveImportException("第 $lineNumber 行包含无法映射到 20 段曲线的处理指令")
        }
        if (filterDirectiveRegex.containsMatchIn(line) || graphicEqDirectiveRegex.containsMatchIn(line)) return
        throw CurveImportException("第 $lineNumber 行包含无法识别的指令")
    }

    private fun splitTableRow(line: String): List<String> {
        val values = when {
            '\t' in line -> line.split('\t')
            ',' in line -> line.split(',')
            ';' in line -> line.split(';')
            else -> line.split(Regex("\\s+"))
        }
        return values.map { it.trim().trim('"', '\'') }.filter(String::isNotEmpty)
    }

    private fun resolveTableColumns(header: TableRow): TableColumns {
        val normalized = header.values.map(::normalizeHeader)
        val frequency = normalized.indexOfFirst { it in FrequencyHeaders }
        if (frequency < 0) {
            throw CurveImportException("无法从第 ${header.lineNumber} 行确定频率列")
        }
        val gain = GainHeaderPriority.firstNotNullOfOrNull { candidate ->
            normalized.indexOf(candidate).takeIf { it >= 0 }
        } ?: if (header.values.size == 2) {
            1 - frequency
        } else {
            throw CurveImportException("无法确定增益列；请使用 gain、dB、eq 或 equalization 列名")
        }
        if (gain == frequency) throw CurveImportException("频率列和增益列不能相同")
        return TableColumns(frequency, gain)
    }

    private fun normalizeHeader(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

    private fun formatGain(value: Double): String =
        java.lang.String.format(java.util.Locale.US, "%.4f", value)
            .trimEnd('0')
            .trimEnd('.')

    @Serializable
    private data class ProfileFileDto(
        val format: String,
        val version: Int,
        val name: String,
        @SerialName("band_plan") val bandPlan: String,
        @SerialName("frequencies_hz") val frequenciesHz: List<Int>,
        @SerialName("gains_q4") val gainsQ4: List<Int>,
    )

    private data class FrequencyGain(val frequencyHz: Double, val gainDb: Double)
    private data class TableRow(val lineNumber: Int, val values: List<String>)
    private data class TableColumns(val frequency: Int, val gain: Int)

    private data class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a0: Double,
        val a1: Double,
        val a2: Double,
    ) {
        fun magnitudeDb(frequency: Double, sampleRate: Double): Double {
            val omega = 2.0 * PI * frequency / sampleRate
            val numeratorReal = b0 + b1 * cos(omega) + b2 * cos(2.0 * omega)
            val numeratorImaginary = -b1 * sin(omega) - b2 * sin(2.0 * omega)
            val denominatorReal = a0 + a1 * cos(omega) + a2 * cos(2.0 * omega)
            val denominatorImaginary = -a1 * sin(omega) - a2 * sin(2.0 * omega)
            val numeratorPower = numeratorReal * numeratorReal + numeratorImaginary * numeratorImaginary
            val denominatorPower = denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary
            return 10.0 * log10(numeratorPower / denominatorPower)
        }

        companion object {
            fun create(
                type: String,
                centerFrequency: Double,
                gainDb: Double,
                q: Double,
                sampleRate: Double,
            ): Biquad {
                val amplitude = 10.0.pow(gainDb / 40.0)
                val omega = 2.0 * PI * centerFrequency / sampleRate
                val cosine = cos(omega)
                val alpha = sin(omega) / (2.0 * q)
                return when (type) {
                    "PK", "PEQ" -> Biquad(
                        b0 = 1.0 + alpha * amplitude,
                        b1 = -2.0 * cosine,
                        b2 = 1.0 - alpha * amplitude,
                        a0 = 1.0 + alpha / amplitude,
                        a1 = -2.0 * cosine,
                        a2 = 1.0 - alpha / amplitude,
                    )
                    "LS" -> {
                        val root = 2.0 * sqrt(amplitude) * alpha
                        Biquad(
                            b0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + root),
                            b1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine),
                            b2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - root),
                            a0 = (amplitude + 1.0) + (amplitude - 1.0) * cosine + root,
                            a1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine),
                            a2 = (amplitude + 1.0) + (amplitude - 1.0) * cosine - root,
                        )
                    }
                    "HS" -> {
                        val root = 2.0 * sqrt(amplitude) * alpha
                        Biquad(
                            b0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + root),
                            b1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine),
                            b2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - root),
                            a0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + root,
                            a1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine),
                            a2 = (amplitude + 1.0) - (amplitude - 1.0) * cosine - root,
                        )
                    }
                    else -> throw CurveImportException("不支持的滤波器类型：$type")
                }
            }
        }
    }

    private const val DEFAULT_Q = 0.7071067811865476
    private const val MaxProfileNameLength = 40
    private val FrequencyHeaders = setOf("frequency", "frequencyhz", "freq", "freqhz", "hz")
    private val GainHeaderPriority = listOf(
        "equalization",
        "equalizationsmoothed",
        "eq",
        "gain",
        "gaindb",
        "db",
        "level",
        "leveldb",
    )
    private val preampLineRegex = Regex(
        "(?i)^\\s*Preamp\\s*:\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(?:dB)?\\s*$",
    )
    private val filterDirectiveRegex = Regex("(?i)^\\s*Filter(?:\\s+\\d+)?\\s*:")
    private val graphicEqDirectiveRegex = Regex("(?i)^\\s*GraphicEQ\\s*:")
    private val allChannelsRegex = Regex("(?i)^\\s*Channel\\s*:\\s*all\\s*$")
    private val unsupportedProcessingDirectiveRegex = Regex(
        "(?i)^\\s*(?:Include|Copy|Convolution|Delay|LoudnessCorrection)\\s*:",
    )
    private val activeFilterRegex = Regex(
        "(?i)^\\s*Filter(?:\\s+\\d+)?\\s*:\\s*ON\\s+(PK|PEQ|LSC|HSC|LS|HS)\\s+" +
            "Fc\\s+([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*Hz\\s+" +
            "Gain\\s+([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*dB" +
            "(?:\\s+Q\\s+([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)))?\\s*$",
    )
}
