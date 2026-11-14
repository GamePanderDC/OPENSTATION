package com.openstation.launcher

internal enum class MusicAuthFailure { LOGIN_REQUIRED, STREAM_ERROR, STALE }

internal fun classifyMusicAuthFailure(
    checkedToken: String,
    currentToken: () -> String,
    tokenRejected: () -> Boolean
): MusicAuthFailure {
    val rejected = checkedToken.isEmpty() || try { tokenRejected() } catch (_: Exception) { false }
    if (currentToken() != checkedToken) return MusicAuthFailure.STALE
    return if (rejected) MusicAuthFailure.LOGIN_REQUIRED else MusicAuthFailure.STREAM_ERROR
}
