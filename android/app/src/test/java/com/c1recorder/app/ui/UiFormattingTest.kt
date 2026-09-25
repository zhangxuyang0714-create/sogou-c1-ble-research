package com.c1recorder.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormattingTest {

    @Test
    fun formatSessionDateTime_usesFixedUtc8_notViewerTimezone() {
        // Ground truth: RECORD/20260919/10_44_36.{WAV,AVC} on the device's own
        // USB storage — sessionId 0x6aadf714 must format to 10:44:36, the
        // real recording time, regardless of what timezone the viewing phone
        // is set to. (A ZoneId.systemDefault() build showed 11:44:36 on a
        // UTC+9 test phone — this is the regression this test guards.)
        assertEquals("2026-09-19 10:44:36", formatSessionDateTime(0x6aadf714L))
    }

    @Test
    fun friendlyErrorMessage_mapsKnownCategories() {
        assertEquals("操作超时，请重试", friendlyErrorMessage("下载超时：已收到 12 个数据包 / 960 字节，但未收到 FILE_TAIL，下载未完成"))
        assertEquals("数据校验未通过，可能不完整，请重试", friendlyErrorMessage("CRC 校验失败 (设备=0x1234, 本地计算=0x5678)，数据可能不完整或损坏，未生成文件"))
        assertEquals("音频解码失败，请重试", friendlyErrorMessage("CRC 校验通过，但音频解码失败 (CELT mono)"))
        assertEquals("设备未连接，请重新连接后重试", friendlyErrorMessage("设备未就绪"))
        assertEquals("设备正忙，请稍后重试", friendlyErrorMessage("已有操作进行中"))
    }

    @Test
    fun friendlyErrorMessage_fallsBackToGenericMessage_forUnrecognizedText() {
        assertEquals("操作失败，请重试", friendlyErrorMessage("something totally unexpected"))
    }
}
