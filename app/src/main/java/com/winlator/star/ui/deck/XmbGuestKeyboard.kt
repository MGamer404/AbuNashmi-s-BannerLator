package com.winlator.star.ui.deck

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.winlator.star.core.GuestTyper
import com.winlator.star.inputcontrols.ExternalController
import com.winlator.star.ui.theme.WinlatorTheme
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.roundToInt

// The XMB keyboard typing into the running game (X11 and Wayland), as a panel at the bottom of the game screen. The same
// panel body as on the Compose screens, so it looks and plays the same; what differs is where its keys go (a GuestTyper)
// and how it is hosted: a view of the panel's own height at the bottom of the activity, so touches above it still reach
// the game, and the pad is routed to it by the activity while it is up.

/**
 * What was typed this session, kept for the preview strip: the game's own text cannot be read back, so this is an echo of
 * the keys sent, with a cursor that follows the cursor keys. It only ever shows the recent end.
 */
internal class GuestEcho(private val limit: Int = ECHO_LIMIT) {
    var text: String = ""
        private set
    var cursor: Int = 0
        private set

    fun insert(s: String) {
        text = text.substring(0, cursor) + s + text.substring(cursor)
        cursor += s.length
        if (text.length > limit) {
            val cut = text.length - limit
            text = text.substring(cut)
            cursor = (cursor - cut).coerceAtLeast(0)
        }
    }

    fun backspace() {
        if (cursor == 0) return
        // A character outside the basic plane is two chars of the string and one key press.
        val pair = cursor >= 2 && Character.isLowSurrogate(text[cursor - 1]) && Character.isHighSurrogate(text[cursor - 2])
        val from = cursor - if (pair) 2 else 1
        text = text.removeRange(from, cursor)
        cursor = from
    }

    fun move(delta: Int) {
        cursor = (cursor + delta).coerceIn(0, text.length)
    }

    companion object {
        const val ECHO_LIMIT = 400
    }
}

/**
 * The keyboard's input when it types into the game: each key goes to the [typer] and the echo for the preview strip is
 * kept alongside. Enter is the action key (the field's own action is unknown), so the panel shows Enter and never closes
 * on it; the panel goes when the game's text field loses the focus.
 */
@Stable
internal class GuestKeySession(private val typer: GuestTyper, editStyle: Int) : XmbKeyboardInput {
    private val echo = GuestEcho()

    /** The layout this session was made for, so the host can tell when the field changed to another kind. */
    val keyboardType: KeyboardType = GuestEditStyle.keyboardType(editStyle)

    override val imeOptions = ImeOptions(keyboardType = keyboardType, imeAction = ImeAction.Default)
    override var value: TextFieldValue by mutableStateOf(TextFieldValue(""))
        private set
    override val hasNavigationKeys: Boolean get() = true

    private fun publish() {
        value = TextFieldValue(echo.text, TextRange(echo.cursor))
    }

    override fun commit(text: String) {
        if (text.isEmpty()) return
        typer.type(text)
        echo.insert(text)
        publish()
    }

    override fun backspace() {
        typer.press(GuestTyper.Key.BACKSPACE)
        echo.backspace()
        publish()
    }

    override fun moveCursor(delta: Int) {
        repeat(abs(delta)) { typer.press(if (delta < 0) GuestTyper.Key.LEFT else GuestTyper.Key.RIGHT) }
        echo.move(delta)
        publish()
    }

    override fun performAction() = pressKey(GuestTyper.Key.ENTER)

    override fun paste(text: String) = commit(text.replace("\r\n", "\n").replace('\r', '\n'))

    override fun pressKey(key: GuestTyper.Key) {
        if (key == GuestTyper.Key.ENTER) {
            typer.press(key)
            echo.insert("\n")
            publish()
        } else {
            typer.press(key)
        }
    }
}

/**
 * Hosts the in-game keyboard panel in an activity: the panel's view, when it shows (see [GuestKeyboardPolicy]), the pad
 * while it is up, and the keys it must never leave held. All calls come in on the main thread, or are moved to it.
 */
class XmbGuestKeyboardHost(
    private val activity: Activity,
    private val typerSource: Supplier<GuestTyper?>,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** The panel came up or went down: the activity hands the pad over, shifts the screen and takes the focus back. */
        fun onOpenChanged(open: Boolean)

        /** The panel's key for Android's own keyboard was pressed; the panel is already closed. */
        fun onSystemKeyboard()
    }

    private val policy = GuestKeyboardPolicy()
    private val main = Handler(Looper.getMainLooper())
    private val triggers = XmbKeyboardTriggers()

    /** Pad keys whose press went to the panel, so their release goes there too. */
    private val keysUntilUp = HashSet<Int>()
    private var view: ComposeView? = null
    private var typer: GuestTyper? = null
    private var editStyle = 0
    private val tick = Runnable { handle(policy.onTick(now())) }

    internal var session by mutableStateOf<GuestKeySession?>(null)
        private set

    /** The panel is up. */
    fun isOpen(): Boolean = policy.visible

    /** Adds the (hidden) panel view to the activity. Once. */
    fun install() {
        if (view != null) return
        val v = ComposeView(activity)
        hideView(v)
        val half = (activity.resources.displayMetrics.heightPixels * XmbKeyboardGeometry.PANEL_HEIGHT_SHARE).roundToInt()
        activity.addContentView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, half, Gravity.BOTTOM))
        v.setContent {
            WinlatorTheme {
                DeckTheme {
                    Content()
                }
            }
        }
        view = v
    }

    @Composable
    private fun Content() {
        val s = session ?: return
        key(s) {
            XmbKeyboardPanelBody(
                session = s,
                onClose = { userClose() },
                onSystemKeyboard = { systemKeyboard() },
                triggers = triggers,
                inOwnWindow = false,
            )
        }
    }

    /** The show-keyboard button and gesture: opens the panel and keeps it, or closes it. */
    fun toggle() = onMain {
        if (!policy.visible && typer() == null) {
            callbacks.onSystemKeyboard()
            return@onMain
        }
        handle(policy.onUserToggle(now()))
    }

    /** B, Back or the panel's hide key. */
    fun userClose() = onMain { handle(policy.onUserClose(now())) }

    private fun systemKeyboard() {
        handle(policy.onUserClose(now()))
        callbacks.onSystemKeyboard()
    }

    /**
     * The game's focus is in a text field, or no longer is ([editStyle] is the Windows EDIT style flags, 0 when unknown).
     * A read-only field counts as none.
     */
    fun onTextFocus(active: Boolean, editStyle: Int) = onMain {
        val typable = GuestEditStyle.typable(active, editStyle)
        if (typable) this.editStyle = editStyle
        handle(policy.onHardwareKeyboard(hasHardwareKeyboard(), now()))
        handle(policy.onTextFocus(typable, now()))
        // The same panel is up and the field is now another kind (text to number): the layout follows it.
        val current = session
        val t = typer
        if (typable && policy.visible && current != null && t != null && current.keyboardType != GuestEditStyle.keyboardType(editStyle)) {
            session = GuestKeySession(t, editStyle)
        }
    }

    /** A menu over the game (the Deck menu) opened or closed; both use the pad, so only one is up. */
    fun onMenuOpen(open: Boolean) = onMain {
        handle(policy.onMenuOpen(open, now()))
        // The automatic panel returns after the menu if the field still has the focus, once whatever closed the menu has run.
        if (!open) main.post { handle(policy.onTick(now())) }
    }

    /** The game is leaving the screen or the session ends: the panel goes and no key stays held. */
    fun hide() = onMain { handle(policy.onForceHide()) }

    /** The session ends. */
    fun release() = onMain {
        handle(policy.onForceHide())
        main.removeCallbacks(tick)
        typer?.close()
        typer = null
    }

    /**
     * A key of a game controller while the panel is up goes to the panel (and its release after it), never to the game.
     * Back is left to Android, which closes the panel. True when the key was taken.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (view == null || !ExternalController.isGameController(event.device)) return false
        val kc = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (keysUntilUp.remove(kc)) {
                route(event)
                return true
            }
            return false
        }
        if (!policy.visible || kc == KeyEvent.KEYCODE_BACK) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0) {
            if (keysUntilUp.contains(kc)) route(event)
            return true
        }
        keysUntilUp.add(kc)
        route(event)
        return true
    }

    /** Pad motion while the panel is up: the analog triggers go to the panel. True while the panel owns the pad. */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!policy.visible || !ExternalController.isGameController(event.device)) return false
        triggers.onMotion(event)
        return true
    }

    private fun route(event: KeyEvent) {
        val v = view ?: return
        if (!v.hasFocus()) v.requestFocus()
        activity.window.superDispatchKeyEvent(event)
    }

    private fun typer(): GuestTyper? {
        if (typer == null) typer = typerSource.get()
        return typer
    }

    /** Does what the policy decided, then sets the timer for the hide it is waiting to do. */
    private fun handle(action: GuestKeyboardAction) {
        if (action == GuestKeyboardAction.SHOW) {
            if (!showView()) {
                // No way to type into this session: the policy forgets the panel.
                policy.onForceHide()
                return
            }
            callbacks.onOpenChanged(true)
        } else if (action == GuestKeyboardAction.HIDE) {
            view?.let { hideView(it) }
            session = null
            keysUntilUp.clear()
            typer?.releaseAll()
            callbacks.onOpenChanged(false)
        }
        main.removeCallbacks(tick)
        val wait = policy.pendingDelay(now())
        if (wait >= 0) main.postDelayed(tick, wait)
    }

    private fun showView(): Boolean {
        val v = view ?: return false
        val t = typer() ?: return false
        // The panel is the same share of the screen as in its own window, measured on the area the game uses.
        val area = (v.parent as? View)?.height?.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val lp = v.layoutParams as FrameLayout.LayoutParams
        lp.height = (area * XmbKeyboardGeometry.PANEL_HEIGHT_SHARE).roundToInt()
        lp.gravity = Gravity.BOTTOM
        v.layoutParams = lp
        session = GuestKeySession(t, editStyle)
        v.visibility = View.VISIBLE
        v.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.requestFocus()
        return true
    }

    /** Gone, and unable to take focus or touches, so the game session is exactly as without the panel. */
    private fun hideView(v: View) {
        v.clearFocus()
        v.visibility = View.GONE
        v.isFocusable = false
        v.isFocusableInTouchMode = false
        (v as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
    }

    private fun now(): Long = SystemClock.uptimeMillis()

    companion object {
        /** The XMB interface style is on: the keyboard of the game is the XMB one. */
        @JvmStatic
        fun isXmbMode(context: Context): Boolean = isXmbHome(context)
    }
}
