package com.musepocket.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentTtsProtocolTest {
    @Test
    fun signatureUsesGetAndRawUnicodeBeforeQueryEncoding() {
        val url = TencentTtsProtocol.signedUrl(
            appId = "123456",
            secretId = "demo+id/42",
            secretKey = "demo-key",
            timestamp = 1745932688,
            sessionId = "test-utterance",
            voiceType = 101001,
            text = "你好 + A&B",
        )
        assertEquals(
            "wss://tts.cloud.tencent.com/stream_ws?Action=TextToStreamAudioWS&AppId=123456" +
                "&Codec=pcm&Expired=1745932988&SampleRate=16000&SecretId=demo%2Bid%2F42" +
                "&SessionId=test-utterance&Text=%E4%BD%A0%E5%A5%BD%20%2B%20A%26B" +
                "&Timestamp=1745932688&VoiceType=101001&Signature=ZB8cNXWJc14r40iroKZpedFpMrk%3D",
            url,
        )
        assertFalse(url.contains("demo-key"))
    }

    @Test
    fun onlyFinalSuccessfulFrameCompletesSynthesis() {
        assertFalse(TencentTtsProtocol.isFinal("""{"code":0,"final":0}"""))
        assertTrue(TencentTtsProtocol.isFinal("""{"code":0,"final":1}"""))
    }

    @Test
    fun authorizationFailureNeverSurfacesProviderMessage() {
        val rawMessage = "request contains private text"
        val error = runCatching {
            TencentTtsProtocol.isFinal("""{"code":10003,"message":"$rawMessage","final":1}""")
        }.exceptionOrNull()
        assertTrue(error is TencentTtsException)
        assertTrue(error?.message.orEmpty().contains("10003"))
        assertFalse(error?.message.orEmpty().contains(rawMessage))
    }
}
