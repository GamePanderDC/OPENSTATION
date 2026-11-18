package com.openstation.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Optional on-device voice for the ChatGPT provider. */
internal class LocalSpeech(private val context: Context) {
    private data class Turn(val id: String, val text: String, val reply: (Boolean) -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var waiting: Turn? = null
    private var speaking: Turn? = null
    private var closed = false

    fun speak(id: String, text: String, reply: (Boolean) -> Unit) = main.post {
        if (closed || text.isBlank() || id.isBlank() || text.length > TextToSpeech.getMaxSpeechInputLength()) {
            reply(false)
            return@post
        }
        waiting?.reply?.invoke(false)
        waiting = Turn(id, text, reply)
        if (initialized) {
            speakWaiting()
        } else if (engine == null) {
            try {
                val configured = Settings.Secure.getString(context.contentResolver, "tts_default_synth")
                val engines = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                    .map { it.serviceInfo.packageName }
                val name = (listOfNotNull(configured, "com.k2fsa.sherpa.onnx.tts.engine") + engines)
                    .firstOrNull { it in engines }
                if (name == null) {
                    onInit(TextToSpeech.ERROR)
                } else {
                    engine = TextToSpeech(context, { status -> main.post { onInit(status) } }, name)
                }
            } catch (_: RuntimeException) {
                onInit(TextToSpeech.ERROR)
            }
        }
    }

    private fun onInit(status: Int) {
        if (closed) return
        val tts = engine
        val russian = try {
            if (status == TextToSpeech.SUCCESS) {
                tts?.voices?.filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired }
                    ?.maxWithOrNull(compareBy({ it.quality }, { -it.latency }))
            } else null
        } catch (_: RuntimeException) {
            null
        }
        if (tts == null || russian == null || tts.setVoice(russian) != TextToSpeech.SUCCESS) {
            waiting?.reply?.invoke(false)
            waiting = null
            tts?.shutdown()
            engine = null
            return
        }
        tts.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { main.post { finish(utteranceId, true) } }
            @Deprecated("Android's compatibility callback")
            override fun onError(utteranceId: String) { main.post { finish(utteranceId, false) } }
            override fun onError(utteranceId: String, errorCode: Int) {
                main.post { finish(utteranceId, false) }
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) {
                main.post { finish(utteranceId, false) }
            }
        })
        initialized = true
        speakWaiting()
    }

    private fun speakWaiting() {
        val turn = waiting ?: return
        waiting = null
        speaking?.reply?.invoke(false)
        speaking = turn
        try {
            if (engine?.speak(turn.text, TextToSpeech.QUEUE_FLUSH, null, turn.id) == TextToSpeech.SUCCESS) return
        } catch (_: RuntimeException) {
        }
        finish(turn.id, false)
    }

    private fun finish(id: String, success: Boolean) {
        val turn = speaking ?: return
        if (turn.id != id) return
        speaking = null
        turn.reply(success)
    }

    fun stop(id: String) = main.post {
        if (waiting?.id == id) {
            waiting = null
        }
        if (speaking?.id == id) {
            speaking = null
            engine?.stop()
        }
    }

    fun close() = main.post {
        closed = true
        waiting = null
        speaking = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        initialized = false
    }
}
