package com.openstation.launcher

internal object AssistantDisplayPolicy {
    const val IDLE_REPLY_MS = 15_000L

    fun text(state: String, liveModel: String, lastModel: String, liveUser: String,
             error: String, replyAtMs: Long, nowMs: Long): String {
        if (state == "error" && error.isNotBlank()) return "Ошибка: $error"
        if (state == "idle" && (replyAtMs <= 0L || nowMs - replyAtMs >= IDLE_REPLY_MS)) return ""
        val model = liveModel.trim().ifEmpty { lastModel.trim() }
        if (model.isNotEmpty()) return model
        val user = liveUser.trim()
        if (state != "idle" && user.isNotEmpty()) return "«$user»"
        if (error.isNotBlank() && replyAtMs > 0L && nowMs - replyAtMs < 6000L) return "Ошибка: $error"
        return ""
    }
}
