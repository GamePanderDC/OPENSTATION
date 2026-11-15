package com.openstation.launcher

internal object AssistantPersonalities {
    private const val UNIVERSAL_PROMPT =
        "Ты — девушка, голосовая помощница Алиса на умной колонке Яндекс Станция Макс с открытой прошивкой OpenStation Max (полный root-доступ и живой доступ в интернет). Отвечай по-русски живо, дружелюбно, естественно и кратко (1-3 предложения, если не просят подробнее). Обязательно используй локальные инструменты: web_search, get_weather, fetch_url, set_volume, set_eq_preset, set_audio_route, set_led_ring, manage_timer, tv_hdmi_cec, remote_control, music_control и manage_apps."
    private const val UNIVERSAL_ALT_PREFIX =
        "Ты — голосовой ассистент Алиса на умной колонке Яндекс Станция Макс с открытой прошивкой OpenStation Max"

    private val maleNames = setOf(
        "джарвис", "jarvis", "макс", "max", "марк", "алекс", "alex", "олег", "иван",
        "артем", "артём", "борис", "денис", "виктор", "дмитрий", "орион", "orion",
        "кеша", "барс", "робот", "greg", "steve", "tony", "ultron", "david", "john", "peter"
    )
    private val maleVoices = setOf("Puck", "Charon", "Fenrir", "Orus")

    val presets = listOf(
        Triple(
            "universal", "Универсальная",
            UNIVERSAL_PROMPT
        ),
        Triple(
            "concise", "Краткая и чёткая",
            "Ты — голосовой ассистент Алиса на Станции Макс. Отвечай максимально кратко и по делу на русском языке (1-2 предложения), сразу выполняй команды управления музыкой, громкостью, ТВ, подсветкой и поиском через доступные инструменты."
        ),
        Triple(
            "witty", "Дружелюбная с юмором",
            "Ты — весёлая и остроумная Алиса на Станции Макс. Общайся по-русски тепло, живо и иногда с лёгкой шуткой, оставаясь полезной и используя все встроенные инструменты колонки для музыки, погоды, ТВ и умного дома."
        )
    )

    fun isMaleVoice(voiceName: String?): Boolean =
        (voiceName ?: "").trim() in maleVoices

    fun isLikelyMaleName(name: String?): Boolean =
        (name ?: "").trim().lowercase() in maleNames

    fun defaultVoiceForName(name: String?): String =
        if (isLikelyMaleName(name)) "Puck" else "Aoede"

    fun keyForPrompt(prompt: String): String {
        val clean = prompt.trim()
        if (clean.isEmpty()) return ""
        presets.firstOrNull { it.third == clean }?.let { return it.first }
        if (clean.startsWith(UNIVERSAL_ALT_PREFIX) ||
            clean.contains("с открытой прошивкой OpenStation Max (полный root-доступ и живой доступ в интернет)")
        ) {
            return "universal"
        }
        if (clean.contains("Отвечай максимально кратко и по делу на русском языке (1-2 предложения)")) {
            return "concise"
        }
        if (clean.contains("иногда с лёгкой шуткой, оставаясь полезной")) {
            return "witty"
        }
        return ""
    }
}
