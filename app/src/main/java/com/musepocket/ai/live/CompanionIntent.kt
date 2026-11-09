package com.musepocket.ai.live

import java.text.Normalizer
import java.util.Locale

internal enum class CompanionIntent { OPEN_NOTES, OPEN_GAME }

private const val NOTES_NAME = "小[艺易][慧会汇惠绘][记纪计]"
private const val OPEN = "打开|开启|启动|进入|切到|切换到|调出|唤起|开(?!会)"
private const val RECORD = "记录|录音|录制|录|记下|记(?!得|住)"
private const val MEETING = "会议|开会|(?:这场|这次|这个|本次|今天的?)会"
private const val GAME = "游戏|飞机大战|飞行小鸟|flyingbird"
private const val GAME_ACTION = "生成|做|制作|创建|开发|写|弄|整|玩|来(?:一个|一款|一局|个|款|局)|$OPEN"

private val separators = Regex("[\\p{P}\\s]+")
private val requestLead = Regex(
    "^(?:你好|小豆[啊呀]?|那个|嗯|呃|请|你|能不能|可不可以|可以|能否|能|麻烦|帮我|帮忙|" +
        "帮一下|给我|我想|我要|想要|先|直接)+",
)
private val nonCommand = Regex(
    "不要|不用|不必|不想|先不|^别|先别|也别|就别|还是别|千万别|别(?:再|给|帮|开|录|记|生成|做|玩)|" +
        "取消|算了|停止|怎么|如何|是什么|什么是|什么叫|好玩吗|知道|听说|聊聊|讨论",
)
private val notesName = Regex(NOTES_NAME)
private val openLead = Regex("^(?:$OPEN)")
private val notesNameFirst = Regex("^(?:把)?$NOTES_NAME(?:给我|帮我|先|直接)*(?:$OPEN)")
private val meeting = Regex(MEETING)
private val recordLead = Regex("^(?:$RECORD)")
private val meetingFirst = Regex(
    "^(?:把)?(?:(?:今天的|这场|这次|本次)?会议|(?:这场|这次|这个|本次)会)" +
        "(?:的?(?:内容|笔记|纪要|记要|记录))?(?:给我|帮我|先)*(?:$RECORD)",
)
private val notesArtifact = Regex("(?:会议|开会)的?(?:笔记|纪要|记要|记录)")
private val takeNotesLead = Regex("^(?:做|写)")
private val game = Regex(GAME)
private val gameLead = Regex("^(?:$GAME_ACTION)")
private val gameFirst = Regex("^(?:把)?(?:预置)?(?:小?游戏|飞机大战|飞行小鸟|flyingbird)(?:给我|帮我|先)*(?:$GAME_ACTION)")
private val gameMaterial = Regex("(?:$GAME)的?(?:介绍|攻略|文案|海报|截图|教程|说明|策划|代码)")
private val joinedActions = Regex("(?:然后|接着|同时|并且|顺便|和|再)(?:请|帮我|给我)*(?:$GAME_ACTION|$RECORD)")

internal fun companionIntent(transcript: String): CompanionIntent? {
    val text = Normalizer.normalize(transcript, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(separators, "")
        .replaceFirst(requestLead, "")
    if (text.isEmpty() || nonCommand.containsMatchIn(text)) return null

    val namedNotes = notesName.containsMatchIn(text)
    val notesTask = notesArtifact.containsMatchIn(text)
    val gameTopic = game.containsMatchIn(text)
    // One utterance opens one page. Two requested destinations leave the turn in Live.
    if (gameTopic && (namedNotes || notesTask) && joinedActions.containsMatchIn(text)) return null

    val wantsNotes = (namedNotes && (openLead.containsMatchIn(text) || notesNameFirst.containsMatchIn(text))) ||
        (meeting.containsMatchIn(text) && recordLead.containsMatchIn(text)) ||
        meetingFirst.containsMatchIn(text) ||
        (notesTask && takeNotesLead.containsMatchIn(text))
    val wantsGame = gameTopic && !gameMaterial.containsMatchIn(text) &&
        (gameLead.containsMatchIn(text) || gameFirst.containsMatchIn(text))

    return when {
        wantsNotes && !wantsGame -> CompanionIntent.OPEN_NOTES
        wantsGame && !wantsNotes -> CompanionIntent.OPEN_GAME
        else -> null
    }
}
