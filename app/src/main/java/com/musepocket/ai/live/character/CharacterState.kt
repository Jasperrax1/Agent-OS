package com.musepocket.ai.live.character

internal enum class PokeSide { LEFT, RIGHT }

internal enum class CharacterState(
    private val file: String,
    val loop: Boolean = true,
    val startMs: Long = 0L,
) {
    IDLE("idle"),
    LISTENING("listening"),
    THINK("think"),
    WORK("work"),
    ANSWER("answer"),
    POKE_LEFT("poke_left", loop = false, startMs = 400),
    POKE_RIGHT("poke_right", loop = false, startMs = 900),
    ;

    val asset: String get() = "live/character/$file.mp4"
    val isPoke: Boolean get() = this == POKE_LEFT || this == POKE_RIGHT
}
