package com.musepocket.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentAsrProtocolTest {
    @Test
    fun signatureUsesRawSortedParametersAndEncodesSpecialCharactersInTheUrl() {
        val url = TencentAsrProtocol.signedUrl(
            appId = "123456",
            secretId = "demo+id/42",
            secretKey = "demo-key",
            timestamp = 1745932688,
            nonce = 20,
            voiceId = "test-utterance",
        )
        assertEquals(
            "wss://asr.cloud.tencent.com/asr/v2/123456?engine_model_type=16k_zh&expired=1745932988" +
                "&needvad=1&nonce=20&secretid=demo%2Bid%2F42&timestamp=1745932688&voice_format=1" +
                "&voice_id=test-utterance&signature=GZUMFRS%2BkcOJko%2FZvj%2Bgk0R4xKY%3D",
            url,
        )
        assertFalse(url.contains("demo-key"))
    }

    @Test
    fun pcmIsRawLittleEndianAndEndIsSeparateText() {
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, -1, -1),
            TencentAsrProtocol.pcmBytes(shortArrayOf(0, 0x1234, -1, 0), offset = 1, count = 2),
        )
        assertEquals("""{"type":"end"}""", TencentAsrProtocol.END_MESSAGE)
    }

    @Test
    fun partialRevisionsAndMultipleStableSlicesAreNotDuplicatedAtFinal() {
        val transcript = TencentAsrTranscript()
        assertEquals(TencentAsrTranscript.Update("", false), transcript.accept("""{"code":0}"""))
        assertEquals("帮我打", transcript.accept(slice(index = 0, type = 1, text = "帮我打")).text)
        assertEquals("帮我打开", transcript.accept(slice(index = 0, type = 1, text = "帮我打开")).text)
        assertEquals("帮我打开", transcript.accept(slice(index = 0, type = 2, text = "帮我打开")).text)
        assertEquals("帮我打开", transcript.accept(slice(index = 0, type = 2, text = "帮我打开")).text)
        assertEquals("帮我打开视频", transcript.accept(slice(index = 1, type = 1, text = "视频")).text)
        assertEquals("帮我打开视频播放器", transcript.accept(slice(index = 1, type = 2, text = "视频播放器")).text)

        val completed = transcript.accept("""{"code":0,"final":1}""")
        assertTrue(completed.isFinal)
        assertEquals("帮我打开视频播放器", completed.text)
    }

    @Test
    fun streamCompletionDoesNotPromoteAnUnconfirmedSlice() {
        val transcript = TencentAsrTranscript()
        assertEquals("暂定文本", transcript.accept(slice(index = 0, type = 1, text = "暂定文本")).text)
        assertEquals(TencentAsrTranscript.Update("", true), transcript.accept("""{"code":0,"final":1}"""))
    }

    @Test
    fun providerErrorDoesNotExposeRawResponseMessage() {
        val response = """{"code":4002,"message":"server supplied sensitive information"}"""
        val failure = runCatching { TencentAsrTranscript().accept(response) }.exceptionOrNull()
        assertTrue(failure is TencentAsrException)
        assertTrue(failure?.message.orEmpty().contains("4002"))
        assertFalse(failure?.message.orEmpty().contains("server supplied"))
    }

    private fun slice(index: Int, type: Int, text: String): String =
        """{"code":0,"result":{"index":$index,"slice_type":$type,"voice_text_str":"$text"}}"""
}
