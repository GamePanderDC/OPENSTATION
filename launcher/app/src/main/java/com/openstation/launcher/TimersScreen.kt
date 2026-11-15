package com.openstation.launcher

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun formatTimerDuration(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    } else {
        String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}

fun timerRemainingSec(t: JSONObject, fetchedAtMs: Long): Int =
    t.optInt("remainingSec", 0) - ((SystemClock.elapsedRealtime() - fetchedAtMs) / 1000L).toInt()

/**
 * LiquidGlass Timers & Alarms modal panel.
 * Left column lists active timers, configured alarms, and any ringing-alarm Stop/Snooze banner.
 * Right column provides D-pad friendly timer/alarm creation.
 */
class TimersScreen(
    context: Context,
    private val post: (path: String, body: String) -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    private val sounds = listOf(
        "chime" to "Сигнал",
        "wave" to "Моя волна",
        "calm" to "Спокойная волна"
    )

    private val uiHandler = Handler(Looper.getMainLooper())
    private var timers = JSONArray()
    private var fetchedAt = SystemClock.elapsedRealtime()
    private var alarms = JSONArray()
    private var ringingAlarm: JSONObject? = null
    private var listKey = "-"
    private val timerRows = ArrayList<Triple<JSONObject, TextView, ProgressBar>>()
    private val listCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var isAlarm = false
    private var kindInitialized = false
    private val kindBtns = ArrayList<TextView>()
    private val btnClose: TextView
    private val btnReset: TextView
    private val left = Picker(0, 99, 10)
    private val right = Picker(0, 59, 0)
    private val tvUnit = makeText("мин", 15f, "#94A3B8")
    private val days = BooleanArray(7) { it < 5 }
    private val dayBtns = ArrayList<TextView>()
    private val daysRow = LinearLayout(context)
    private var soundIdx = 0
    private val tvSoundValue = makeText("", 14.5f, "#F8FAFC", bold = true)
    private val soundRow = LinearLayout(context)
    private val btnSave: TextView

    private val tickerRunnable = object : Runnable {
        override fun run() {
            tick()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun makeText(txt: String, sizeSp: Float, colorHex: String, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = txt
            textSize = sizeSp
            setTextColor(Color.parseColor(colorHex))
            typeface = if (bold) YandexFonts.displayBold(context) else YandexFonts.textMedium(context)
        }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        LiquidGlassKit.applyGlass(
            root,
            radiusDp = 24f,
            baseTint = Color.parseColor("#D6080D18"),
            focusTint = Color.parseColor("#E010182C"),
            normalStroke = Color.parseColor("#2AFFFFFF"),
            focusStroke = Color.parseColor("#447DD3FC"),
            refractStrength = 1.05f
        )
        addView(root, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- Left column: Active Timers & Alarms ----
        val leftCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val headRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        headRow.addView(makeText("Таймеры и будильники", 22f, "#FFFFFF", bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        btnClose = makePill("Закрыть", gold = false) { onClose() }.apply {
            id = View.generateViewId()
        }
        headRow.addView(btnClose)
        leftCol.addView(headRow)

        listCol.clipChildren = false
        listCol.clipToPadding = false
        leftCol.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(listCol)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        leftCol.addView(
            makeText("OK — вкл/выкл · удержание OK — удалить · BACK — закрыть", 12f, "#94A3B8"),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) }
        )
        root.addView(leftCol, LinearLayout.LayoutParams(0, -1, 1.15f).apply { marginEnd = dp(20) })

        // ---- Right column: Editor ----
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            clipChildren = false
            clipToPadding = false
        }
        LiquidGlassKit.applyGlass(
            card,
            radiusDp = 20f,
            baseTint = Color.parseColor("#880B1323"),
            focusTint = Color.parseColor("#880B1323"),
            normalStroke = Color.parseColor("#22FFFFFF"),
            focusStroke = Color.parseColor("#22FFFFFF")
        )

        val kinds = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66050912"))
                cornerRadius = dp(16).toFloat()
            }
        }
        listOf("Таймер", "Будильник").forEachIndexed { i, txt ->
            val b = makeText(txt, 14f, "#E2E8F0", bold = true).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f).setDuration(80).start()
                    if (hasFocus && (i == 1) != isAlarm) {
                        setKind(i == 1)
                    }
                }
                setOnClickListener { setKind(i == 1) }
            }
            kindBtns.add(b)
            kinds.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (i == 0) marginEnd = dp(4)
            })
        }
        card.addView(kinds)

        val pickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        pickRow.addView(left.view)
        pickRow.addView(makeText(":", 48f, "#94A3B8"), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        pickRow.addView(right.view)
        pickRow.addView(tvUnit, LinearLayout.LayoutParams(dp(44), -2).apply { marginStart = dp(10) })
        card.addView(pickRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        daysRow.apply {
            clipChildren = false
            clipToPadding = false
        }
        dayNames.forEachIndexed { i, d ->
            val b = makeText(d, 13f, "#E2E8F0", bold = true).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setOnClickListener { days[i] = !days[i]; renderDays() }
            }
            dayBtns.add(b)
            daysRow.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (i < 6) marginEnd = dp(5)
            })
        }
        card.addView(daysRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        soundRow.apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = true
            isClickable = true
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 12f,
                baseTint = Color.parseColor("#44141E30"),
                focusTint = Color.parseColor("#881E2E48"),
                normalStroke = Color.parseColor("#20FFFFFF"),
                focusStroke = Color.parseColor("#78BAE6FD")
            )
            setOnClickListener {
                soundIdx = (soundIdx + 1) % sounds.size
                renderSound()
            }
            addView(makeText("Мелодия", 13.5f, "#94A3B8"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tvSoundValue)
        }
        card.addView(soundRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        card.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
        }
        btnReset = makePill("Сброс", gold = false) { resetEditor() }.apply {
            id = View.generateViewId()
            (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(8)
        }
        btnRow.addView(btnReset)
        btnSave = makePill("", gold = true) { save() }.apply {
            id = View.generateViewId()
        }
        btnRow.addView(btnSave, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        root.addView(card, LinearLayout.LayoutParams(0, -1, 1f))

        setKind(false)
        renderSound()
        rebuildList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        uiHandler.removeCallbacks(tickerRunnable)
        uiHandler.post(tickerRunnable)
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacks(tickerRunnable)
        super.onDetachedFromWindow()
    }

    fun focusFirst() {
        kindBtns.firstOrNull()?.requestFocus() ?: left.btnUp.requestFocus()
    }

    fun update(
        newTimers: JSONArray?,
        timersFetchedAt: Long,
        newAlarms: JSONArray?,
        newRingingAlarm: JSONObject? = null
    ) {
        timers = newTimers ?: JSONArray()
        fetchedAt = timersFetchedAt
        alarms = newAlarms ?: JSONArray()
        ringingAlarm = newRingingAlarm
        val key = buildString {
            append(ringingAlarm?.optInt("id", -1) ?: -1).append('|')
            for (i in 0 until timers.length()) {
                val t = timers.optJSONObject(i)
                append(t?.optInt("id")).append(':')
            }
            append('|').append(alarms.toString())
        }
        if (key != listKey) {
            listKey = key
            rebuildList()
        } else {
            tick()
        }
    }

    fun tick() {
        for ((t, tv, pb) in timerRows) {
            val rem = timerRemainingSec(t, fetchedAt).coerceAtLeast(0)
            val total = t.optInt("totalSec", 1).coerceAtLeast(1)
            tv.text = formatTimerDuration(rem)
            pb.progress = (rem * 1000L / total).toInt().coerceIn(0, 1000)
        }
    }

    private fun makePill(label: String, gold: Boolean, onClick: () -> Unit): TextView {
        return makeText(label, 13.5f, if (gold) "#0B0F17" else "#E2E8F0", bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isFocusable = true
            isClickable = true
            setBackgroundResource(if (gold) R.drawable.bg_pill_gold else R.drawable.bg_pill)
            clipToOutline = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.025f else 1f).scaleY(if (hasFocus) 1.025f else 1f).setDuration(90).start()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun rebuildList() {
        val focusedIdx = (0 until listCol.childCount).indexOfFirst { listCol.getChildAt(it).hasFocus() }
        listCol.removeAllViews()
        timerRows.clear()

        val ringing = ringingAlarm
        if (ringing != null) {
            val ringBox = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#44EF4444"))
                    setStroke(dp(1), Color.parseColor("#AAFCA5A5"))
                    cornerRadius = dp(16).toFloat()
                }
            }
            val rTitle = ringing.optString("label").ifBlank { "Звонит будильник" }
            ringBox.addView(
                makeText("🔔 $rTitle", 15f, "#FFFFFF", bold = true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            ringBox.addView(makePill("Стоп", gold = true) {
                post("/api/alarms/stop", "{}")
            })
            ringBox.addView(makePill("+5 мин", gold = false) {
                post("/api/alarms/snooze", JSONObject().put("minutes", 5).toString())
            }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
            })
            listCol.addView(ringBox, itemLp())
        }

        for (i in 0 until timers.length()) {
            val t = timers.optJSONObject(i) ?: continue
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            LiquidGlassKit.applyGlass(
                box,
                radiusDp = 16f,
                baseTint = Color.parseColor("#550E2A47"),
                focusTint = Color.parseColor("#77143A60"),
                normalStroke = Color.parseColor("#4438BDF8"),
                focusStroke = Color.parseColor("#8838BDF8")
            )
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val tvRem = makeText("", 24f, "#FFFFFF", bold = true)
            val name = t.optString("label").ifBlank { "Таймер" }
            col.addView(tvRem)
            col.addView(
                makeText("$name · из ${formatTimerDuration(t.optInt("totalSec"))}", 12.5f, "#CBD5E1"),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) }
            )
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val id = t.optInt("id")
            row.addView(makePill("Отмена", gold = false) {
                post("/api/timers/cancel", JSONObject().put("id", id).toString())
            })
            box.addView(row)

            val pb = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 1000
            }
            box.addView(pb, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(8) })
            timerRows.add(Triple(t, tvRem, pb))
            listCol.addView(box, itemLp())
        }

        for (i in 0 until alarms.length()) {
            val a = alarms.optJSONObject(i) ?: continue
            val on = a.optBoolean("enabled", true)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isFocusable = true
                isClickable = true
                LiquidGlassKit.applyGlass(
                    this,
                    radiusDp = 16f,
                    baseTint = Color.parseColor(if (on) "#66121E33" else "#440B101B"),
                    focusTint = Color.parseColor("#881D2F4F"),
                    normalStroke = Color.parseColor(if (on) "#3A38BDF8" else "#18FFFFFF"),
                    focusStroke = Color.parseColor("#887DD3FC")
                )
                setOnClickListener { setAlarmEnabled(i, !on) }
                setOnLongClickListener { confirmDelete(i); true }
            }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(makeText(a.optString("time"), 24f, if (on) "#FFFFFF" else "#64748B", bold = true))
            col.addView(
                makeText(alarmSubtitle(a), 12.5f, if (on) "#CBD5E1" else "#64748B"),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) }
            )
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(makeText(if (on) "ВКЛ" else "ВЫКЛ", 12f, if (on) "#6EE7B7" else "#64748B", bold = true).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (on) "#2410B981" else "#1864748B"))
                    setStroke(dp(1), Color.parseColor(if (on) "#6610B981" else "#3364748B"))
                    cornerRadius = dp(999).toFloat()
                }
            })
            listCol.addView(row, itemLp())
        }

        if (listCol.childCount == 0) {
            val assistantName = LiveOrbState.effectiveName()
            listCol.addView(
                makeText(
                    "Нет активных таймеров и будильников.\nСоздайте справа или скажите «$assistantName, поставь таймер на 5 минут».",
                    14f,
                    "#94A3B8"
                ).apply { setLineSpacing(dp(4).toFloat(), 1.0f) }
            )
        }
        tick()

        val listFocusable = listCol.getFocusables(View.FOCUS_DOWN).isNotEmpty()
        updateNavigationLinks(listFocusable)
        if (focusedIdx >= 0 && listCol.childCount > 0) {
            val target = listCol.getChildAt(focusedIdx.coerceAtMost(listCol.childCount - 1))
            val focusable = target?.getFocusables(View.FOCUS_DOWN)?.firstOrNull()
            if (focusable != null) focusable.requestFocus()
        }
    }

    private fun itemLp() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }

    private fun alarmSubtitle(a: JSONObject): String {
        val d = a.optJSONArray("days")
        val on = BooleanArray(7) { d?.optBoolean(it, false) ?: false }
        val daysTxt = when {
            on.all { it } -> "ежедневно"
            on.none { it } -> "однократно"
            on.take(5).all { it } && !on[5] && !on[6] -> "пн–пт"
            !on.take(5).any { it } && on[5] && on[6] -> "сб, вс"
            else -> dayNames.filterIndexed { i, _ -> on[i] }.joinToString(", ") { it.lowercase() }
        }
        val sound = sounds.firstOrNull { it.first == a.optString("sound") }?.second ?: "Сигнал"
        val name = a.optString("label").ifBlank { "Будильник" }
        return "$name · $daysTxt · $sound"
    }

    private fun postAlarms(arr: JSONArray) {
        alarms = arr
        listKey = "-"
        update(timers, fetchedAt, arr, ringingAlarm)
        post("/api/alarms", JSONObject().put("alarms", arr).toString())
    }

    private fun copyAlarms(skip: Int = -1): JSONArray {
        val out = JSONArray()
        for (i in 0 until alarms.length()) {
            if (i != skip) out.put(JSONObject(alarms.optJSONObject(i)?.toString() ?: "{}"))
        }
        return out
    }

    private fun setAlarmEnabled(idx: Int, enabled: Boolean) {
        val arr = copyAlarms()
        arr.optJSONObject(idx)?.put("enabled", enabled)
        postAlarms(arr)
    }

    private fun confirmDelete(idx: Int) {
        val a = alarms.optJSONObject(idx) ?: return
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Удалить будильник ${a.optString("time")}?")
            .setPositiveButton("Удалить") { _, _ -> postAlarms(copyAlarms(skip = idx)) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateNavigationLinks(listFocusable: Boolean = listCol.getFocusables(View.FOCUS_DOWN).isNotEmpty()) {
        val activeKind = kindBtns[if (isAlarm) 1 else 0]
        kindBtns[0].nextFocusLeftId = btnClose.id
        kindBtns[0].nextFocusRightId = kindBtns[1].id
        kindBtns[0].nextFocusDownId = left.btnUp.id
        kindBtns[1].nextFocusLeftId = kindBtns[0].id
        kindBtns[1].nextFocusRightId = kindBtns[1].id
        kindBtns[1].nextFocusDownId = right.btnUp.id

        btnClose.nextFocusRightId = activeKind.id
        btnClose.nextFocusDownId = if (listFocusable) View.NO_ID else activeKind.id

        val leftEscapeId = if (listFocusable) View.NO_ID else btnClose.id
        left.btnUp.nextFocusUpId = activeKind.id
        left.btnUp.nextFocusDownId = left.btnDown.id
        left.btnUp.nextFocusLeftId = leftEscapeId
        left.btnUp.nextFocusRightId = right.btnUp.id

        left.btnDown.nextFocusUpId = left.btnUp.id
        left.btnDown.nextFocusDownId = if (isAlarm) dayBtns[0].id else btnReset.id
        left.btnDown.nextFocusLeftId = leftEscapeId
        left.btnDown.nextFocusRightId = right.btnDown.id

        right.btnUp.nextFocusUpId = activeKind.id
        right.btnUp.nextFocusDownId = right.btnDown.id
        right.btnUp.nextFocusLeftId = left.btnUp.id
        right.btnUp.nextFocusRightId = right.btnUp.id

        right.btnDown.nextFocusUpId = right.btnUp.id
        right.btnDown.nextFocusDownId = if (isAlarm) dayBtns[3].id else btnSave.id
        right.btnDown.nextFocusLeftId = left.btnDown.id
        right.btnDown.nextFocusRightId = right.btnDown.id

        dayBtns.forEachIndexed { idx, db ->
            db.nextFocusUpId = if (idx < 3) left.btnDown.id else right.btnDown.id
            db.nextFocusDownId = soundRow.id
            db.nextFocusLeftId = if (idx == 0) leftEscapeId else dayBtns[idx - 1].id
            db.nextFocusRightId = if (idx == dayBtns.lastIndex) db.id else dayBtns[idx + 1].id
        }

        soundRow.nextFocusUpId = dayBtns[0].id
        soundRow.nextFocusDownId = btnSave.id
        soundRow.nextFocusLeftId = leftEscapeId
        soundRow.nextFocusRightId = soundRow.id

        btnReset.nextFocusUpId = if (isAlarm) soundRow.id else left.btnDown.id
        btnReset.nextFocusLeftId = leftEscapeId
        btnReset.nextFocusRightId = btnSave.id

        btnSave.nextFocusUpId = if (isAlarm) soundRow.id else right.btnDown.id
        btnSave.nextFocusLeftId = btnReset.id
        btnSave.nextFocusRightId = btnSave.id
    }

    private fun setKind(alarm: Boolean) {
        val changed = !kindInitialized || isAlarm != alarm
        kindInitialized = true
        isAlarm = alarm
        kindBtns.forEachIndexed { i, b ->
            val sel = (i == 1) == alarm
            LiquidGlassKit.applyGlass(
                b,
                radiusDp = 12f,
                baseTint = Color.parseColor(if (sel) "#DDE8C686" else "#26141E30"),
                focusTint = Color.parseColor(if (sel) "#F2F0D399" else "#7823395B"),
                normalStroke = Color.parseColor(if (sel) "#88FDE68A" else "#16FFFFFF"),
                focusStroke = Color.parseColor(if (sel) "#967DD3FC" else "#78BAE6FD")
            )
            b.setTextColor(Color.parseColor(if (sel) "#0B0F17" else "#E2E8F0"))
        }
        if (changed) resetEditor()
        daysRow.visibility = if (alarm) View.VISIBLE else View.GONE
        soundRow.visibility = if (alarm) View.VISIBLE else View.GONE
        tvUnit.text = if (alarm) "" else "мин"
        btnSave.text = if (alarm) "Сохранить будильник" else "Запустить таймер"
        renderDays()
        updateNavigationLinks()
    }

    private fun resetEditor() {
        if (isAlarm) {
            left.set(0, 23, 7)
            right.set(0, 59, 30)
        } else {
            left.set(0, 99, 10)
            right.set(0, 59, 0)
        }
    }

    private fun renderDays() {
        dayBtns.forEachIndexed { i, b ->
            val active = days[i]
            LiquidGlassKit.applyGlass(
                b,
                radiusDp = 10f,
                baseTint = Color.parseColor(if (active) "#8838BDF8" else "#33141E30"),
                focusTint = Color.parseColor(if (active) "#BB38BDF8" else "#66243652"),
                normalStroke = Color.parseColor(if (active) "#997DD3FC" else "#20FFFFFF"),
                focusStroke = Color.parseColor("#787DD3FC")
            )
            b.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#94A3B8"))
        }
    }

    private fun renderSound() {
        tvSoundValue.text = "${sounds[soundIdx].second} ›"
    }

    private fun save() {
        if (isAlarm) {
            val timeStr = String.format(Locale.US, "%02d:%02d", left.value, right.value)
            val o = JSONObject()
                .put("time", timeStr)
                .put("days", JSONArray().apply { days.forEach { put(it) } })
                .put("label", "")
                .put("enabled", true)
                .put("sound", sounds[soundIdx].first)
            postAlarms(copyAlarms().put(o))
            Toast.makeText(context, "Будильник на $timeStr сохранён", Toast.LENGTH_SHORT).show()
        } else {
            val sec = left.value * 60 + right.value
            if (sec < 1) {
                Toast.makeText(context, "Укажите время таймера", Toast.LENGTH_SHORT).show()
                return
            }
            post("/api/timers", JSONObject().put("seconds", sec).put("label", "").toString())
            Toast.makeText(context, "Таймер на ${formatTimerDuration(sec)} запущен", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Picker(private var min: Int, private var max: Int, var value: Int) {
        private val tv = makeText("", 52f, "#FFFFFF").apply { gravity = Gravity.CENTER }
        val btnUp = makeStepperButton("▲") { step(1) }
        val btnDown = makeStepperButton("▼") { step(-1) }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            isClickable = false
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 18f,
                baseTint = Color.parseColor("#330D1728"),
                focusTint = Color.parseColor("#55142238"),
                normalStroke = Color.parseColor("#24FFFFFF"),
                focusStroke = Color.parseColor("#447DD3FC")
            )
            addView(btnUp, LinearLayout.LayoutParams(dp(92), dp(30)))
            addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) })
            addView(btnDown, LinearLayout.LayoutParams(dp(92), dp(30)))
            layoutParams = LinearLayout.LayoutParams(dp(110), -2)
        }

        private fun makeStepperButton(symbol: String, onStep: () -> Unit): TextView {
            return makeText(symbol, 15f, "#CBD5E1", bold = true).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                LiquidGlassKit.applyGlass(
                    this,
                    radiusDp = 10f,
                    baseTint = Color.parseColor("#26142034"),
                    focusTint = Color.parseColor("#88223C63"),
                    normalStroke = Color.parseColor("#18FFFFFF"),
                    focusStroke = Color.parseColor("#78BAE6FD")
                )
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f).setDuration(80).start()
                    (v as TextView).setTextColor(Color.parseColor(if (hasFocus) "#FFFFFF" else "#94A3B8"))
                }
                setOnKeyListener { _, keyCode, event ->
                    val isSelect = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER ||
                        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (isSelect && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && event.repeatCount % 2 == 0) {
                        onStep()
                        true
                    } else {
                        false
                    }
                }
                setOnClickListener { onStep() }
            }
        }

        fun set(min: Int, max: Int, value: Int) {
            this.min = min
            this.max = max
            this.value = value
            render()
        }

        private fun step(d: Int) {
            val span = max - min + 1
            value = ((value - min + d) % span + span) % span + min
            render()
        }

        private fun render() {
            tv.text = String.format(Locale.US, "%02d", value)
        }
    }
}
