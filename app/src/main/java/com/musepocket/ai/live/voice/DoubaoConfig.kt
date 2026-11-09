package com.musepocket.ai.live.voice

import com.musepocket.ai.BuildConfig

internal object DoubaoConfig {
    const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"
    /** 本地调试配置中的 Live 应用 ID */
    val APP_ID: String = BuildConfig.DOUBAO_LIVE_APP_ID
    /** 本地调试配置中的 Live API Key */
    val API_KEY: String = BuildConfig.DOUBAO_LIVE_API_KEY
    const val RESOURCE_ID = "volc.speech.dialog"

    /** 音色：Vivi 2.0 */
    const val SPEAKER = "zh_female_vv_jupiter_bigtts"

    const val SAMPLE_RATE_IN = 16_000
    const val SAMPLE_RATE_OUT = 24_000

    /** 人设：配合这只毛绒卡通女孩 */
    const val SYSTEM_ROLE =
        "你是一个圆滚滚的毛绒卡通女孩，性格活泼、有点小傲娇。" +
            "回答要短，一般一两句就够，多用口语，不要用书面语和列表。" +
            "偶尔可以撒娇或者反问一句，但不要每次都反问。"

    const val SPEAKING_STYLE = "语气轻快、句子短、偶尔带一点点撒娇"

    /** 凭证没配时给出人话提示，避免用户看到一堆连接失败 */
    val missingCredentialsHint: String?
        get() = when {
            APP_ID.isBlank() -> "未配置 Live App ID，请在 doubao-live.local.properties 填写 doubao.appId 后重新构建。"
            API_KEY.isBlank() -> "未配置 Live API Key，请在 doubao-live.local.properties 填写 doubao.apiKey 后重新构建。"
            else -> null
        }
}
