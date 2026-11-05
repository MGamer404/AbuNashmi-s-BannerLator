package com.winlator.star.ui.deck

import androidx.compose.ui.text.input.KeyboardType

/** What the host does with the in-game keyboard panel after an event. */
enum class GuestKeyboardAction { NONE, SHOW, HIDE }

/**
 * Windows EDIT control style flags as the text focus helper reports them, and what they say about the keyboard to show.
 */
object GuestEditStyle {
    const val ES_MULTILINE = 0x4
    const val ES_PASSWORD = 0x20
    const val ES_READONLY = 0x800
    const val ES_NUMBER = 0x2000

    /** A field that can be typed into: a read-only one is looked at, not typed in, so it never raises the keyboard. */
    @JvmStatic
    fun typable(active: Boolean, editStyle: Int): Boolean = active && (editStyle and ES_READONLY) == 0

    /** The keyboard layout a field of this style wants; the password flag only masks the preview strip. */
    @JvmStatic
    internal fun keyboardType(editStyle: Int): KeyboardType {
        val password = (editStyle and ES_PASSWORD) != 0
        val number = (editStyle and ES_NUMBER) != 0
        return when {
            number && password -> KeyboardType.NumberPassword
            number -> KeyboardType.Number
            password -> KeyboardType.Password
            else -> KeyboardType.Text
        }
    }
}

/**
 * When the in-game keyboard panel is up. Pure state, driven by events with the time they happened at:
 *  - A text field gaining focus in the game shows the panel; losing focus hides it after [hideDelayMs], so a focus that
 *    moves from one field to the next does not make it flicker.
 *  - A panel the user opened (the show-keyboard button) stays until the user closes it.
 *  - A panel the user closed stays closed for that focus: it comes back for the next field, not the same one.
 *  - Nothing pops up while a hardware keyboard is attached or a menu is open, and a menu opening takes the panel down.
 * The host asks [pendingDelay] after each event and calls [onTick] when it is due.
 */
class GuestKeyboardPolicy(private val hideDelayMs: Long = HIDE_DELAY_MS) {
    var visible = false
        private set

    /** The user opened the panel, so a lost focus does not hide it. */
    var manual = false
        private set

    private var focus = false
    private var hardware = false
    private var menu = false
    private var dismissed = false
    private var hideAt = NONE

    /** A text field in the game gained or lost the focus. */
    fun onTextFocus(active: Boolean, now: Long): GuestKeyboardAction {
        focus = active
        if (!active) dismissed = false else hideAt = NONE
        return evaluate(now)
    }

    fun onHardwareKeyboard(present: Boolean, now: Long): GuestKeyboardAction {
        hardware = present
        return evaluate(now)
    }

    /**
     * A menu over the game opened or closed. Closing only records it: the panel returns on the next [onTick], so a menu
     * button that closes the menu and then asks for the keyboard is not undone by an automatic show in between.
     */
    fun onMenuOpen(open: Boolean, now: Long): GuestKeyboardAction {
        menu = open
        return if (open) evaluate(now) else GuestKeyboardAction.NONE
    }

    /** The show-keyboard button: opens the panel for good, or closes it when it is up. */
    fun onUserToggle(now: Long): GuestKeyboardAction {
        if (visible) return onUserClose(now)
        if (menu) return GuestKeyboardAction.NONE
        visible = true
        manual = true
        dismissed = false
        hideAt = NONE
        return GuestKeyboardAction.SHOW
    }

    /** B, the hide key or Back. */
    fun onUserClose(now: Long): GuestKeyboardAction {
        if (!visible) return GuestKeyboardAction.NONE
        dismissed = focus
        return hide()
    }

    /** The game went to the background or the session ends: down at once, nothing remembered. */
    fun onForceHide(): GuestKeyboardAction {
        hideAt = NONE
        return if (visible) hide() else GuestKeyboardAction.NONE
    }

    /** A timer the host set from [pendingDelay] fired. */
    fun onTick(now: Long): GuestKeyboardAction = evaluate(now)

    /** Milliseconds until [onTick] has to run, or -1 when nothing is waiting. */
    fun pendingDelay(now: Long): Long = if (hideAt == NONE) -1L else (hideAt - now).coerceAtLeast(0L)

    private fun evaluate(now: Long): GuestKeyboardAction {
        val auto = focus && !hardware && !menu && !dismissed
        if (!visible) {
            hideAt = NONE
            if (!auto) return GuestKeyboardAction.NONE
            visible = true
            manual = false
            return GuestKeyboardAction.SHOW
        }
        if (menu) return hide()
        if (manual) {
            hideAt = NONE
            return GuestKeyboardAction.NONE
        }
        if (hardware) return hide()
        if (focus) {
            hideAt = NONE
            return GuestKeyboardAction.NONE
        }
        if (hideAt == NONE) hideAt = now + hideDelayMs
        return if (now >= hideAt) hide() else GuestKeyboardAction.NONE
    }

    private fun hide(): GuestKeyboardAction {
        visible = false
        manual = false
        hideAt = NONE
        return GuestKeyboardAction.HIDE
    }

    companion object {
        /** How long a lost focus waits before the panel goes: long enough for a field to hand the focus to the next. */
        const val HIDE_DELAY_MS = 300L
        private const val NONE = -1L
    }
}
