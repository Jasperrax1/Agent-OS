package com.musepocket.ai.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionIntentTest {
    @Test
    fun recognizesNotesByActionAndTopic() {
        for (text in listOf(
            "帮我打开小艺慧记", "打开小艺会记", "帮我打开一下小艺惠记",
            "小豆啊，能帮忙把小易会记打开吗？", "小艺慧记打开一下", "切换到小艺慧记录个音",
            "小豆，请帮我记一下会议笔记。", "麻烦记录一下这场会议", "帮我做个会议纪要",
            "帮我把这次会议记下来", "帮我录一下这场会", "帮我记录一下今天的会议内容",
            "小豆，先把会议内容记下来吧", "帮我录一下这场游戏开发会议",
        )) {
            assertEquals(text, CompanionIntent.OPEN_NOTES, companionIntent(text))
        }
    }

    @Test
    fun recognizesGameRequestsWithFlexibleNamesAndVerbs() {
        for (text in listOf(
            "帮我生成一个游戏", "给我做一款飞机大战的小游戏吧", "请打开 Flying Bird",
            "来个小游戏", "来个特别好玩的小游戏", "我想玩个小游戏", "你能给我做个小游戏吗", "给我弄个飞机大战",
            "小豆，先把游戏打开一下", "帮我开一下预置游戏", "能不能来一局飞行小游戏",
            "请打开 ＦＬＹＩＮＧ ＢＩＲＤ",
        )) {
            assertEquals(text, CompanionIntent.OPEN_GAME, companionIntent(text))
        }
    }

    @Test
    fun ordinaryConversationAndNegationStayInLive() {
        for (text in listOf(
            "不要打开小艺慧记", "你知道小艺慧记怎么用吗", "会议笔记应该怎么写",
            "小艺慧记是什么", "你喜欢游戏吗", "别帮我生成一个游戏", "帮我写一份游戏介绍",
            "我想讨论怎么做游戏", "给我生成一个游戏海报", "我今天开会", "你推荐什么游戏",
            "帮我打开小艺慧记和生成一个游戏", "打开游戏，再帮我开小艺慧记",
            "不要记录这场会议", "帮我记录这场会议算了先不录", "记得小艺慧记吗",
            "给我做一个关于游戏的会议笔记",
        )) {
            assertNull(text, companionIntent(text))
        }
    }
}
