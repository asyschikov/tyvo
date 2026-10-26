package com.tyvo.keyboard.bubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tyvo.keyboard.actions.QuickAction
import com.tyvo.keyboard.audio.AudioRecorder
import com.tyvo.keyboard.data.BubbleOpacity
import com.tyvo.keyboard.data.BubbleSize
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.history.RecordingStore
import com.tyvo.keyboard.ime.Dictation
import com.tyvo.keyboard.ime.DictationSession
import com.tyvo.keyboard.ime.UiState
import com.tyvo.keyboard.ime.isLocked
import com.tyvo.keyboard.ime.onScreenOff
import com.tyvo.keyboard.net.TyvoException
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.settings.SettingsActivity
import com.tyvo.keyboard.transcribe.Transcriber
import com.tyvo.keyboard.usage.UsageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The floating mic: Tyvo dictation over whatever keyboard is up.
 *
 * An accessibility service because that is the only way to put text into
 * another app's field without being its keyboard. The same access gives us
 * the two things the bubble needs to show up at the right moment: which
 * field has focus, and where the keyboard window is.
 *
 * Shown only while someone is typing: a field has focus and a keyboard is on
 * screen. Hidden when that keyboard is Tyvo's own, which already has a mic.
 */
class BubbleService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var settings: Settings
    private lateinit var recorder: AudioRecorder
    private lateinit var store: RecordingStore
    private lateinit var polisher: Polisher
    private lateinit var dictation: Dictation
    private lateinit var wm: WindowManager

    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    /** What the current bubble was built with. */
    private var bubbleSize: BubbleSize? = null
    private var bubbleOpacity: BubbleOpacity? = null
    private var panel: BubblePanel? = null
    private var panelParams: WindowManager.LayoutParams? = null

    /** The field the bubble would dictate into right now. */
    private var field: AccessibilityNodeInfo? = null

    /** The field holding the session's text, and where in it the text sits. */
    private var target: AccessibilityNodeInfo? = null
    private var placement: Placement? = null
    private val session = DictationSession()

    private var pipeline: Job? = null
    private var ticker: Job? = null
    private var state: UiState = UiState.Idle()

    /**
     * A finished dictation that came back while the phone was locked. Goes
     * into the first field the user types in after unlocking.
     */
    private var pendingInsert: Dictation.Result.Text? = null

    private var screenOff: android.content.BroadcastReceiver? = null

    /** Top of the keyboard window, the last time we looked. */
    private var keyboardTop: Int? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = Settings(this)
        recorder = AudioRecorder(cacheDir)
        store = RecordingStore(this)
        val usage = UsageStore(this)
        polisher = Polisher(settings, usage)
        dictation = Dictation(this, settings, store, Transcriber(settings, usage), polisher)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // Locking mid-sentence ends the dictation as if the bubble was
        // tapped, rather than leaving the mic recording silence.
        screenOff = onScreenOff { stopAndProcess() }
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Our own windows changing would otherwise feed back into a refresh.
        if (event.packageName == packageName) return
        // Focus and window events arrive in bursts; settle before looking.
        handler.removeCallbacks(refresher)
        handler.postDelayed(refresher, REFRESH_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        screenOff?.let { runCatching { unregisterReceiver(it) } }
        abortRecording()
        closePanel()
        hideBubble()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Visibility ------------------------------------------------------

    private val refresher = Runnable { refresh() }

    private fun refresh() {
        if (!::settings.isInitialized) return
        val focused = focusedField()
        keyboardTop = findKeyboardTop()

        // A different field means the session's text is no longer the last
        // thing the user was working on.
        if (!isBusy() && session.isActive && focused != null && focused != target) {
            endSession()
        }
        if (focused != null) field = focused

        val pending = pendingInsert
        if (pending != null && focused != null && !isLocked() && !isBusy()) {
            pendingInsert = null
            insertFresh(pending.text, pending.raw, pending.note)
        }

        val typing = focused != null && keyboardTop != null && !tyvoIsKeyboard()
        if (isBusy() || typing) showBubble() else hideBubble()
    }

    /** Something is in flight that the bubble must stay up for. */
    private fun isBusy(): Boolean =
        recorder.isRecording || pipeline?.isActive == true || panel != null

    private fun focusedField(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf {
            it.isEditable && !it.isPassword && it.packageName != packageName
        }

    private fun findKeyboardTop(): Int? {
        val bounds = Rect()
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return null
        ime.getBoundsInScreen(bounds)
        return if (bounds.height() > 0) bounds.top else null
    }

    private fun tyvoIsKeyboard(): Boolean =
        AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') == packageName

    private fun showBubble() {
        val size = settings.bubbleSize
        val opacity = settings.bubbleOpacity
        // Changed in the app since this one was made: rebuild to match.
        if (bubble != null && (size != bubbleSize || opacity != bubbleOpacity)) hideBubble()
        if (bubble != null) {
            positionBubble()
            return
        }
        bubbleSize = size
        bubbleOpacity = opacity
        val v = BubbleView(this, size.dp, opacity.alpha)
        v.onTap = { onBubbleTap() }
        v.onLongPress = { onBubbleHold() }
        v.onDrag = { dx, dy -> dragBubble(dx, dy) }
        v.onDragEnd = { rawX -> dropBubble(rawX) }
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or if (settings.bubbleOnLeft) Gravity.START else Gravity.END
            x = 0
        }
        bubble = v
        bubbleParams = p
        v.render(state)
        wm.addView(v, p)
        positionBubble()
    }

    private fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    /**
     * Where the user left it, but never under the keyboard: a bubble behind
     * the keys is a bubble that is not there.
     */
    private fun positionBubble() {
        val v = bubble ?: return
        val p = bubbleParams ?: return
        val ceiling = (keyboardTop ?: screenHeight()) - v.sizePx
        val saved = settings.bubbleY
        p.y = (if (saved < 0) ceiling else minOf(saved, ceiling)).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, p) }
        if (panel != null) positionPanel()
    }

    private fun dragBubble(dx: Float, dy: Float) {
        val v = bubble ?: return
        val p = bubbleParams ?: return
        closePanel()
        // x counts inward from whichever edge the bubble is docked to.
        p.x += (if (settings.bubbleOnLeft) dx else -dx).toInt()
        p.y += dy.toInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** Snaps to the nearer edge and remembers the height. */
    private fun dropBubble(rawX: Float) {
        val v = bubble ?: return
        val p = bubbleParams ?: return
        settings.bubbleOnLeft = rawX < screenWidth() / 2f
        settings.bubbleY = p.y
        p.x = 0
        p.gravity = Gravity.TOP or if (settings.bubbleOnLeft) Gravity.START else Gravity.END
        runCatching { wm.updateViewLayout(v, p) }
        positionBubble()
    }

    // ---- Recording -------------------------------------------------------

    private fun onBubbleTap() {
        when {
            recorder.isRecording -> stopAndProcess()
            // Mid-transcription there is nothing a tap could sensibly mean.
            state is UiState.Working -> Unit
            else -> startRecording()
        }
    }

    private fun startRecording() {
        setupNeeded()?.let { reason ->
            render(UiState.NeedsSetup(reason))
            toast(reason)
            startActivity(
                Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        pipeline?.cancel()
        closePanel()
        val file = recorder.start(onAutoStop = {
            // Hit the duration ceiling on the recorder thread.
            scope.launch { stopAndProcess() }
        })
        if (file == null) {
            render(UiState.Idle("Microphone unavailable."))
            toast("Microphone unavailable.")
            return
        }

        haptic()
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && recorder.isRecording) {
                bubble?.setAmplitude(recorder.amplitude)
                render(UiState.Recording(System.currentTimeMillis() - recorder.startedAtMs))
                delay(60)
            }
        }
    }

    private fun stopAndProcess() {
        if (!recorder.isRecording) return
        ticker?.cancel()
        val durationMs = System.currentTimeMillis() - recorder.startedAtMs
        val audio = recorder.stop()
        haptic()
        if (audio == null) {
            // A stray tap should not throw away text still open for editing.
            if (session.isActive) {
                render(reviewOrIdle(note = "Too short."))
            } else {
                render(UiState.Idle("Too short."))
                toast("Too short - speak while the mic is red.")
            }
            refresh()
            return
        }
        val (id, file) = dictation.save(audio, durationMs)
        transcribe(id, file, durationMs)
    }

    private fun transcribe(recordingId: String?, audio: File, durationMs: Long? = null) {
        pipeline?.cancel()
        pipeline = scope.launch {
            val result = dictation.run(recordingId, audio, durationMs) {
                render(UiState.Working(it))
            }
            when (result) {
                is Dictation.Result.Text -> if (isLocked()) {
                    pendingInsert = result
                    render(UiState.Idle())
                } else {
                    insertFresh(result.text, result.raw, result.note)
                }
                // Dictation has already told the user, by toast and
                // notification; the recording waits in the app.
                Dictation.Result.NothingHeard -> render(UiState.Idle("Nothing heard."))
                // A mistaken tap: back to where they were, with no message.
                Dictation.Result.Discarded -> render(reviewOrIdle())
                is Dictation.Result.Failed -> render(UiState.Idle(result.message))
            }
            refresh()
        }
    }

    private fun abortRecording() {
        ticker?.cancel()
        if (recorder.isRecording) recorder.cancel()
    }

    // ---- Writing to the field --------------------------------------------

    /**
     * Puts a fresh dictation in the field and opens a session on it.
     *
     * If the field will not take it -- it went away, or refuses both ways of
     * writing -- the text goes on the clipboard instead. It is in History
     * either way, but having to go and find it there is what we are avoiding.
     */
    private fun insertFresh(text: String, transcript: String, note: String?) {
        // Wherever the cursor is now: the user may have moved on while we
        // were transcribing, and typing goes where they are, not where they were.
        val node = focusedField() ?: field
        val placed = node?.takeIf { it.refresh() }?.let { FieldWriter.insert(this, it, text) }
        if (node == null || placed == null) {
            session.clear()
            target = null
            placement = null
            copy(text)
            render(UiState.Idle("Copied."))
            toast("Couldn't type here, so it's on the clipboard.")
            return
        }
        target = node
        placement = placed
        // Seed with the transcript, then promote the polished text, so
        // "Undo clean-up" still has something to fall back to.
        session.begin(transcript)
        if (transcript != text) session.setPolished(text)
        haptic()
        // The only note a fresh dictation carries is a failed clean-up.
        render(reviewOrIdle(note = note, failed = note != null))
    }

    /**
     * Swaps the session's text in the field for [text]. Ends the session if
     * the field no longer holds what we put there.
     */
    private fun replacePlaced(text: String): Boolean {
        val node = target
        val at = placement
        val next = if (node != null && at != null && node.refresh()) {
            FieldWriter.replace(this, node, at, text)
        } else {
            null
        }
        if (next == null) {
            endSession()
            return false
        }
        placement = next
        return true
    }

    // ---- Review panel ----------------------------------------------------

    private fun onBubbleHold() {
        haptic()
        when {
            panel != null -> closePanel()
            session.isActive -> openPanel()
            // Nothing to edit, so the hold is a shortcut into the app.
            !recorder.isRecording && state !is UiState.Working -> openApp()
        }
    }

    private fun openPanel() {
        val v = BubblePanel(this)
        v.onAction = { runAction(it) }
        v.actions = { settings.quickActions() }
        v.onUndo = { undo() }
        v.onAccept = {
            pipeline?.cancel()
            endSession()
        }
        v.onRepolish = { repolish() }
        v.onUnpolish = { unpolish() }
        v.onDelete = { deleteStaged() }
        v.onDismiss = { closePanel() }
        val margin = dp(12)
        val p = overlayParams(screenWidth() - 2 * margin).apply {
            gravity = Gravity.TOP or Gravity.START
            x = margin
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        panel = v
        panelParams = p
        (state as? UiState.Review)?.let { v.render(it) }
        wm.addView(v, p)
        positionPanel()
    }

    /** Just above the bubble, or below it when there is no room above. */
    private fun positionPanel() {
        val v = panel ?: return
        val p = panelParams ?: return
        val b = bubbleParams ?: return
        val size = bubble?.sizePx ?: 0
        v.measure(
            View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenHeight(), View.MeasureSpec.AT_MOST),
        )
        val above = b.y - v.measuredHeight
        p.y = if (above >= 0) above else b.y + size
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
    }

    private fun runAction(action: QuickAction) {
        // Always the base, never the variant on screen: chained rewrites
        // drift. Same rule as the keyboard.
        val source = session.base
        if (source.isBlank()) return
        pipeline?.cancel()
        pipeline = scope.launch {
            render(reviewOrIdle(note = action.label, busy = true))
            try {
                val result = polisher.transform(
                    text = source,
                    instruction = action.instruction,
                    translating = action.translating,
                )
                if (replacePlaced(result)) session.setVariant(result, action.id)
                render(reviewOrIdle())
            } catch (e: TyvoException) {
                render(reviewOrIdle(note = e.message, failed = true))
            } catch (e: Exception) {
                render(reviewOrIdle(note = "Rewrite failed.", failed = true))
            }
        }
    }

    /** A fresh clean-up of what was actually said, as the new base. */
    private fun repolish() {
        val source = session.rawTranscript.ifBlank { session.base }
        if (source.isBlank()) return
        pipeline?.cancel()
        pipeline = scope.launch {
            render(reviewOrIdle(note = "Polishing", busy = true))
            try {
                val polished = polisher.cleanUp(source)
                if (replacePlaced(polished)) session.setPolished(polished)
                render(reviewOrIdle())
            } catch (e: TyvoException) {
                render(reviewOrIdle(note = "Clean-up failed: ${e.message}", failed = true))
            } catch (e: Exception) {
                render(reviewOrIdle(note = "Clean-up failed.", failed = true))
            }
        }
    }

    private fun unpolish() {
        val raw = session.unpolish() ?: return
        replacePlaced(raw)
        haptic()
        render(reviewOrIdle())
    }

    private fun undo() {
        val restored = session.undo() ?: return
        replacePlaced(restored)
        haptic()
        render(reviewOrIdle())
    }

    /** Takes the dictation back out of the field and ends the session. */
    private fun deleteStaged() {
        pipeline?.cancel()
        val node = target
        val at = placement
        val removed = node != null && at != null && node.refresh() &&
            FieldWriter.remove(this, node, at)
        endSession()
        if (removed) haptic() else toast("You've edited it, so it's yours now.")
    }

    private fun endSession() {
        session.clear()
        target = null
        placement = null
        closePanel()
        if (state is UiState.Review) render(UiState.Idle())
    }

    // ---- Misc ------------------------------------------------------------

    private fun render(s: UiState) {
        state = s
        bubble?.render(s)
        if (s is UiState.Review) {
            panel?.let { it.render(s); positionPanel() }
        } else {
            closePanel()
        }
    }

    private fun reviewOrIdle(
        note: String? = null,
        busy: Boolean = false,
        failed: Boolean = false,
    ): UiState =
        if (session.isActive) {
            UiState.Review(
                text = session.current,
                canUndo = session.canUndo,
                canUnpolish = session.canUnpolish,
                isPolished = session.isPolished,
                activeAction = session.activeAction,
                busy = busy,
                note = note,
                failed = failed,
            )
        } else {
            UiState.Idle(note ?: "Text is no longer editable here.")
        }

    private fun setupNeeded(): String? = when {
        settings.currentKey.isBlank() -> "Tyvo needs an API key before it can hear you."
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED -> "Tyvo needs permission to use the microphone."
        else -> null
    }

    /**
     * An overlay that never takes focus, so the field keeps it -- and with it
     * the keyboard, and the cursor we are about to write at.
     */
    private fun overlayParams(width: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.width()
        } else {
            resources.displayMetrics.widthPixels
        }

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.height()
        } else {
            resources.displayMetrics.heightPixels
        }

    private fun openApp() {
        startActivity(
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Tyvo", text))
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun haptic() {
        val effect = VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(effect)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_DELAY_MS = 80L
    }
}
