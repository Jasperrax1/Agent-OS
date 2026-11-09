package com.musepocket.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionRouterTest {
    @Test
    fun aiApplicationWithExplicitRequestOpensAgentController() {
        val reply = result("AGENT_CONTROLLER", "ai_application", "open_agent_controller")
        assertEquals(VisionRoute.AGENT_CONTROLLER, parseVisionResult(reply, "帮我打开 Agent 控制器").route)
        assertEquals(VisionRoute.AGENT_CONTROLLER, parseVisionResult(reply, "帮我查看 AI 额度").route)
    }

    @Test
    fun videoApplicationWithExplicitRequestOpensPlayer() {
        for (instruction in listOf("帮我生成一个视频播放器", "打开这个视频应用", "播放这个视频", "打开遥控器")) {
            assertEquals(instruction, VisionRoute.VIDEO_PLAYER, parseVisionResult(videoReply, instruction).route)
        }
    }

    @Test
    fun objectAndSoftwareQuestionsBothStayInVision() {
        val objectReply = result("OBJECT_QA", "physical_object", "answer_question", answer = "这是一个水杯。")
        val objectResult = parseVisionResult(objectReply, "这是什么？")
        assertEquals(VisionRoute.OBJECT_QA, objectResult.route)
        assertEquals("这是一个水杯。", objectResult.answer)
        val softwareReply = result("OBJECT_QA", "video_application", "answer_question", answer = "这是一个视频应用。")
        assertEquals(VisionRoute.OBJECT_QA, parseVisionResult(softwareReply, "这是什么软件？").route)
    }

    @Test
    fun explicitObjectQuestionCanGetAnHonestLowConfidenceAnswer() {
        val reply = result(
            "OBJECT_QA", "physical_object", "answer_question",
            confidence = "0.38", answer = "看起来像一盒牛奶，品牌看不清。",
        )
        val answer = parseVisionResult(reply, "这 是 什 么？")
        assertEquals(VisionRoute.OBJECT_QA, answer.route)
        assertEquals("看起来像一盒牛奶，品牌看不清。", answer.answer)
        assertEquals(VisionRoute.OBJECT_QA, parseVisionResult(reply, "打开视频播放器").route)
    }

    @Test
    fun sceneAloneAndQuestionOrNegationCannotOpenPlayer() {
        val blocked = listOf(
            "这个",
            "这是什么软件？",
            "这软件是用来做什么的，可以打开播放器吗？",
            "怎么打开视频播放器？",
            "不要打开视频播放器",
            "先别帮我播放这个视频",
            "不用替我制作视频播放器",
        )
        for (instruction in blocked) {
            assertEquals(instruction, VisionRoute.OBJECT_QA, parseVisionResult(videoReply, instruction).route)
        }
    }

    @Test
    fun wrongSceneWrongIntentAndUncertainResultsCannotNavigate() {
        val unsafeReplies = listOf(
            result("VIDEO_PLAYER", "physical_object", "open_video_player"),
            result("VIDEO_PLAYER", "video_application", "answer_question"),
            result("VIDEO_PLAYER", "video_application", "open_video_player", confidence = "0.79"),
            result("OBJECT_QA", "unknown", "unclear", confidence = "0.3"),
        )
        for (reply in unsafeReplies) {
            assertEquals(VisionRoute.OBJECT_QA, parseVisionResult(reply, "打开视频播放器").route)
        }
    }

    @Test
    fun casualChatAndRejectedRouteProduceSpeakableAnswers() {
        val chat = result("OBJECT_QA", "other", "answer_question", "0.3", "你好，想聊点什么？")
        assertEquals("你好，想聊点什么？", parseVisionResult(chat, "你好呀").answer)
        val rejected = result("OBJECT_QA", "unknown", "unclear", "0.3", "画面与指令未匹配。")
        val fallback = parseVisionResult(rejected, "打开播放器")
        assertEquals(VisionRoute.OBJECT_QA, fallback.route)
        org.junit.Assert.assertFalse(fallback.answer.contains("匹配"))
    }

    @Test
    fun malformedJsonUnknownRouteAndNonNumericConfidenceAreRejected() {
        val invalidReplies = listOf(
            "```json\n$videoReply\n```",
            "$videoReply trailing",
            videoReply.replace('"', '\''),
            videoReply.replace("VIDEO_PLAYER", "UNKNOWN"),
            videoReply.replace("VIDEO_PLAYER", " VIDEO_PLAYER "),
            videoReply.replace("0.95", "\"0.95\""),
            videoReply.replace("0.95", "2"),
            videoReply.dropLast(1) + ",\"unexpected\":true}",
        )
        for (reply in invalidReplies) {
            assertThrows(VisionRequestException::class.java) {
                parseVisionResult(reply, "打开视频播放器")
            }
        }
    }

    @Test
    fun endpointAcceptsHttpsApiRootOrFullPath() {
        val root = "https://ark.cn-beijing.volces.com/api/v3"
        val endpoint = "$root/chat/completions"
        assertEquals(endpoint, arkEndpoint(root)?.toExternalForm())
        assertEquals(endpoint, arkEndpoint("$endpoint/")?.toExternalForm())
        assertNull(arkEndpoint(root.replace("https", "http")))
        assertNull(arkEndpoint("$endpoint?redirect=true"))
        assertNull(arkEndpoint("https://user:password@example.com/api/v3"))
    }

    private val videoReply = result("VIDEO_PLAYER", "video_application", "open_video_player")

    private fun result(
        route: String,
        scene: String,
        intent: String,
        confidence: String = "0.95",
        answer: String = "已识别。",
    ): String = """{"route":"$route","scene":"$scene","intent":"$intent","confidence":$confidence,"answer":"$answer"}"""
}
