package com.weich.daptune.core.eq

import com.weich.daptune.core.model.DapBandPlan
import com.weich.daptune.core.model.EqCurve
import com.weich.daptune.core.model.GainLimit
import com.weich.daptune.core.model.ProfileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveFileCodecTest {
    @Test
    fun extendedGainExportsV2AndRoundTripsWithoutAnEnabledCeiling() {
        val curve = EqCurve.ofQ4(List(DapBandPlan.bandCount) { if (it == 0) 320 else -400 })
        val text = CurveFileCodec.exportNative("Extended", curve)
        assertTrue(text.contains("\"version\": 2"))
        val imported = CurveFileCodec.import(text, "extended.daptune.json")
        assertEquals(curve, EqTransforms.quantize(imported.gainsDb, OverflowMode.FIT, GainLimit(enabled = false)).curve)
        org.junit.Assert.assertThrows(CurveImportException::class.java) {
            CurveFileCodec.import(text.replace("\"version\": 2", "\"version\": 1"), "invalid-v1.json")
        }
    }

    @Test
    fun exportedDocumentsPreserveQ4AndUseSafeFileNames() {
        val curve = EqCurve.ofQ4(List(DapBandPlan.bandCount) { (it - 10) * 17 })
        val name = "AirPods / 自定义:1"
        CurveExportFormat.entries.forEach { format ->
            val document = CurveFileCodec.exportDocument(name, curve, format)
            assertEquals("AirPods _ 自定义_1.${format.extension}", document.fileName)
            assertEquals(format.mimeType, document.mimeType)
            val imported = CurveFileCodec.import(document.text, document.fileName)
            assertEquals(curve, EqTransforms.quantize(imported.gainsDb, OverflowMode.FIT).curve)
            if (format == CurveExportFormat.DAPTUNE_JSON) assertEquals(name, imported.suggestedName)
        }
    }

    @Test
    fun graphicEq_usesLogFrequencyInterpolation() {
        val imported = CurveFileCodec.import(
            "GraphicEQ: 47 0; 470 10; 4700 0; 19688 -2",
            "sample.txt",
        )

        assertEquals(DapBandPlan.bandCount, imported.gainsDb.size)
        assertEquals(0.0, imported.gainsDb.first(), 0.0001)
        assertTrue(imported.gainsDb[4] > 9.9)
        assertEquals(-2.0, imported.gainsDb.last(), 0.0001)
    }

    @Test
    fun parametricPeak_hasRequestedGainNearCenter() {
        val imported = CurveFileCodec.import(
            "Preamp: -2 dB\nFilter 1: ON PK Fc 3000 Hz Gain 6 dB Q 1.000",
            "ParametricEQ.txt",
        )

        val centerIndex = DapBandPlan.frequenciesHz.indexOf(3000)
        assertEquals(4.0, imported.gainsDb[centerIndex], 0.01)
    }

    @Test
    fun nativeFormat_roundTripsQ4Exactly() {
        val curve = EqCurve.ofQ4(List(DapBandPlan.bandCount) { it * 10 - 300 })
        val encoded = CurveFileCodec.exportNative("测试", curve)

        val decoded = CurveFileCodec.import(encoded, "test.json")
        val converted = EqTransforms.quantize(decoded.gainsDb, OverflowMode.CLAMP).curve

        assertEquals(curve, converted)
    }

    @Test(expected = CurveImportException::class)
    fun nativeFormat_requiresFormatDiscriminator() {
        CurveFileCodec.import(
            nativeJson().replace("\"format\": \"com.weich.daptune.profile\",", ""),
            "missing-format.daptune.json",
            CurveImportFormat.DAPTUNE_JSON,
        )
    }

    @Test(expected = CurveImportException::class)
    fun nativeFormat_requiresVersion() {
        CurveFileCodec.import(
            nativeJson().replace("\"version\": 1,", ""),
            "missing-version.daptune.json",
            CurveImportFormat.DAPTUNE_JSON,
        )
    }

    @Test(expected = CurveImportException::class)
    fun nativeFormat_rejectsUnknownMembers() {
        CurveFileCodec.import(
            nativeJson().replaceFirst("{", "{\n  \"unexpected\": true,"),
            "unknown-member.daptune.json",
            CurveImportFormat.DAPTUNE_JSON,
        )
    }

    @Test(expected = CurveImportException::class)
    fun nativeFormat_rejectsBlankName() {
        CurveFileCodec.import(
            nativeJson().replace("\"name\": \"Example\"", "\"name\": \"   \""),
            "blank-name.daptune.json",
            CurveImportFormat.DAPTUNE_JSON,
        )
    }

    @Test(expected = CurveImportException::class)
    fun nativeFormat_rejectsNonCanonicalNameWhitespace() {
        CurveFileCodec.import(
            nativeJson().replace("\"name\": \"Example\"", "\"name\": \" Example \""),
            "padded-name.daptune.json",
            CurveImportFormat.DAPTUNE_JSON,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nativeFormat_exportRejectsInvalidName() {
        CurveFileCodec.exportNative(" Invalid ", EqCurve.flat())
    }

    @Test
    fun repositoryNativeExamples_areAcceptedAndRemainQ4Exact() {
        listOf(
            "flat.daptune.json",
            "warm.daptune.json",
            "deep-attenuation.daptune.json",
        ).forEach { resourceName ->
            val text = requireNotNull(javaClass.classLoader?.getResource(resourceName)) {
                "Missing example resource: $resourceName"
            }.readText()
            val imported = CurveFileCodec.import(
                text,
                resourceName,
                CurveImportFormat.DAPTUNE_JSON,
            )
            val curve = EqTransforms.quantize(imported.gainsDb, OverflowMode.CLAMP).curve
            val roundTripped = CurveFileCodec.import(
                CurveFileCodec.exportNative(imported.suggestedName, curve),
                resourceName,
                CurveImportFormat.DAPTUNE_JSON,
            )

            assertEquals(imported.gainsDb, roundTripped.gainsDb)
        }
    }

    @Test
    fun graphicEq_doesNotTreatDeepAttenuationAsOverflow() {
        val imported = CurveFileCodec.import(
            "GraphicEQ: 47 -30; 19688 -12",
            "deep-cut.txt",
        )

        assertEquals(-30.0, imported.minimumDb, 0.001)
        assertTrue(!imported.exceedsLimit())
    }

    @Test
    fun autoEqCsv_usesEqualizationColumnInsteadOfRawMeasurement() {
        val imported = CurveFileCodec.import(
            """
                frequency,raw,smoothed,error,error_smoothed,equalization,equalized_raw,equalized_smoothed,target
                47,-18,-17,-10,-9,2,-16,-15,0
                19688,-25,-24,-12,-11,-3,-28,-27,0
            """.trimIndent(),
            "AutoEq.csv",
        )

        assertEquals(ProfileSource.CSV, imported.source)
        assertEquals(2.0, imported.gainsDb.first(), 0.0001)
        assertEquals(-3.0, imported.gainsDb.last(), 0.0001)
    }

    @Test
    fun frequencyGainTable_acceptsCommonUnitHeadersAndTabDelimiter() {
        val imported = CurveFileCodec.import(
            "Frequency (Hz)\tGain (dB)\n47\t1.25\n19688\t-4.5",
            "measurement.tsv",
            CurveImportFormat.FREQUENCY_GAIN_TABLE,
        )

        assertEquals(1.25, imported.gainsDb.first(), 0.0001)
        assertEquals(-4.5, imported.gainsDb.last(), 0.0001)
    }

    @Test
    fun parametricEq_acceptsEqualizerApoShelfAliasesAndInlineComments() {
        val imported = CurveFileCodec.import(
            """
                Preamp: -1.5 dB # headroom
                Filter 1: ON LSC Fc 120 Hz Gain 4 dB Q 0.70 # low shelf
                Filter 2: OFF PK Fc 3000 Hz Gain 8 dB Q 1.00
            """.trimIndent(),
            "Peace.peace",
            CurveImportFormat.PARAMETRIC_EQ,
        )

        assertEquals(ProfileSource.PARAMETRIC_EQ, imported.source)
        assertTrue(imported.gainsDb.first() > imported.gainsDb.last())
    }

    @Test
    fun parametricEq_sumsMultipleGlobalPreampDirectives() {
        val imported = CurveFileCodec.import(
            "Preamp: -1 dB\nPreamp: -2.5 dB\nChannel: all",
            "headroom.txt",
            CurveImportFormat.PARAMETRIC_EQ,
        )

        assertTrue(imported.gainsDb.all { it == -3.5 })
    }

    @Test(expected = CurveImportException::class)
    fun parametricEq_rejectsIncludeInsteadOfSilentlyDroppingIt() {
        CurveFileCodec.import(
            "Include: filters.txt\nPreamp: -2 dB",
            "config.txt",
            CurveImportFormat.PARAMETRIC_EQ,
        )
    }

    @Test(expected = CurveImportException::class)
    fun parametricEq_rejectsChannelSpecificProcessing() {
        CurveFileCodec.import(
            "Channel: L\nFilter 1: ON PK Fc 1000 Hz Gain 2 dB Q 1",
            "left-only.txt",
            CurveImportFormat.PARAMETRIC_EQ,
        )
    }

    @Test(expected = CurveImportException::class)
    fun parametricEq_rejectsUnknownDirectivesInsteadOfIgnoringThem() {
        CurveFileCodec.import(
            "Device: headphones\nFilter 1: ON PK Fc 1000 Hz Gain 2 dB Q 1",
            "config.txt",
            CurveImportFormat.PARAMETRIC_EQ,
        )
    }

    @Test(expected = CurveImportException::class)
    fun explicitFormat_doesNotFallBackToAnotherParser() {
        CurveFileCodec.import(
            "frequency,gain\n47,1\n19688,-2",
            "curve.txt",
            CurveImportFormat.GRAPHIC_EQ,
        )
    }

    private fun nativeJson(): String = CurveFileCodec.exportNative("Example", EqCurve.flat())
}
