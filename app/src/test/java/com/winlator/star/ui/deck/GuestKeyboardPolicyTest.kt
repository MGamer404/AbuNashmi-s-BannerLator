package com.winlator.star.ui.deck

import androidx.compose.ui.text.input.KeyboardType
import com.winlator.star.ui.deck.GuestKeyboardAction.HIDE
import com.winlator.star.ui.deck.GuestKeyboardAction.NONE
import com.winlator.star.ui.deck.GuestKeyboardAction.SHOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the in-game XMB keyboard shows and hides: focus, the debounce, the user's own open and close, hardware keyboards and menus. */
class GuestKeyboardPolicyTest {

    private val policy = GuestKeyboardPolicy()

    @Test fun aTextFieldGainingFocus_showsThePanel() {
        assertEquals(SHOW, policy.onTextFocus(true, 0))
        assertTrue(policy.visible)
        assertFalse(policy.manual)
    }

    @Test fun focusLost_hidesOnlyAfterTheDebounce() {
        policy.onTextFocus(true, 0)
        assertEquals(NONE, policy.onTextFocus(false, 1000))
        assertTrue(policy.visible)
        assertEquals(GuestKeyboardPolicy.HIDE_DELAY_MS, policy.pendingDelay(1000))
        assertEquals(NONE, policy.onTick(1299))
        assertEquals(HIDE, policy.onTick(1300))
        assertFalse(policy.visible)
        assertEquals(-1L, policy.pendingDelay(1300))
    }

    @Test fun debounceIsAbout300ms() {
        assertEquals(300L, GuestKeyboardPolicy.HIDE_DELAY_MS)
    }

    @Test fun focusHandedToTheNextField_doesNotFlicker() {
        policy.onTextFocus(true, 0)
        policy.onTextFocus(false, 1000)
        // The next field takes the focus 100 ms later: the panel never went away and nothing is pending.
        assertEquals(NONE, policy.onTextFocus(true, 1100))
        assertTrue(policy.visible)
        assertEquals(-1L, policy.pendingDelay(1100))
        assertEquals(NONE, policy.onTick(1400))
        assertTrue(policy.visible)
    }

    @Test fun pendingDelay_countsDownFromTheFocusLoss() {
        policy.onTextFocus(true, 0)
        policy.onTextFocus(false, 1000)
        assertEquals(100L, policy.pendingDelay(1200))
        assertEquals(0L, policy.pendingDelay(5000))
    }

    @Test fun aPanelTheUserOpened_staysThroughFocusLoss() {
        assertEquals(SHOW, policy.onUserToggle(0))
        assertTrue(policy.manual)
        policy.onTextFocus(true, 10)
        assertEquals(NONE, policy.onTextFocus(false, 20))
        assertEquals(NONE, policy.onTick(10_000))
        assertTrue(policy.visible)
        assertEquals(-1L, policy.pendingDelay(10_000))
    }

    @Test fun theButtonAgain_closesIt() {
        policy.onUserToggle(0)
        assertEquals(HIDE, policy.onUserToggle(5))
        assertFalse(policy.visible)
        assertFalse(policy.manual)
    }

    @Test fun theButtonOnAnAutomaticPanel_closesIt() {
        policy.onTextFocus(true, 0)
        assertEquals(HIDE, policy.onUserToggle(5))
        assertFalse(policy.visible)
    }

    @Test fun aPanelTheUserClosed_doesNotComeBackForTheSameFocus() {
        policy.onTextFocus(true, 0)
        assertEquals(HIDE, policy.onUserClose(5))
        // Events for the same focus (a caret report, a hardware keyboard change, a tick) leave it closed.
        assertEquals(NONE, policy.onTextFocus(true, 6))
        assertEquals(NONE, policy.onHardwareKeyboard(false, 7))
        assertEquals(NONE, policy.onTick(8))
        assertFalse(policy.visible)
    }

    @Test fun aPanelTheUserClosed_comesBackForTheNextField() {
        policy.onTextFocus(true, 0)
        policy.onUserClose(5)
        policy.onTextFocus(false, 100)
        assertEquals(SHOW, policy.onTextFocus(true, 200))
    }

    @Test fun closingWithNothingUp_doesNothing() {
        assertEquals(NONE, policy.onUserClose(0))
    }

    @Test fun aHardwareKeyboard_keepsTheAutomaticPanelAway() {
        policy.onHardwareKeyboard(true, 0)
        assertEquals(NONE, policy.onTextFocus(true, 1))
        assertFalse(policy.visible)
    }

    @Test fun aHardwareKeyboardAttached_takesAnAutomaticPanelDown_butNotOneTheUserOpened() {
        policy.onTextFocus(true, 0)
        assertEquals(HIDE, policy.onHardwareKeyboard(true, 1))

        policy.onTextFocus(false, 2)
        policy.onHardwareKeyboard(false, 3)
        assertEquals(SHOW, policy.onUserToggle(4))
        assertEquals(NONE, policy.onHardwareKeyboard(true, 5))
        assertTrue(policy.visible)
    }

    @Test fun aMenuOpening_takesThePanelDown_evenOneTheUserOpened() {
        policy.onUserToggle(0)
        assertEquals(HIDE, policy.onMenuOpen(true, 1))
        assertFalse(policy.visible)
    }

    @Test fun noAutomaticPanelWhileAMenuIsOpen() {
        policy.onMenuOpen(true, 0)
        assertEquals(NONE, policy.onTextFocus(true, 1))
        assertFalse(policy.visible)
    }

    @Test fun afterTheMenuCloses_theFocusedFieldGetsItsPanelBackOnTheNextTick() {
        policy.onTextFocus(true, 0)
        policy.onMenuOpen(true, 1)
        // Closing only records it, so a menu button that closes the menu and then asks for the keyboard is not undone.
        assertEquals(NONE, policy.onMenuOpen(false, 2))
        assertFalse(policy.visible)
        assertEquals(SHOW, policy.onTick(3))
    }

    @Test fun theKeyboardButtonAfterTheMenuClosed_winsOverTheAutomaticReturn() {
        policy.onTextFocus(true, 0)
        policy.onMenuOpen(true, 1)
        policy.onMenuOpen(false, 2)
        assertEquals(SHOW, policy.onUserToggle(3))
        assertTrue(policy.manual)
        assertEquals(NONE, policy.onTick(4))
        assertTrue(policy.visible)
    }

    @Test fun theKeyboardButtonWhileAMenuIsOpen_isIgnored() {
        policy.onMenuOpen(true, 0)
        assertEquals(NONE, policy.onUserToggle(1))
        assertFalse(policy.visible)
    }

    @Test fun forceHide_takesAnyPanelDownAtOnce_andLeavesNoTimerBehind() {
        policy.onTextFocus(true, 0)
        policy.onTextFocus(false, 10)
        assertEquals(HIDE, policy.onForceHide())
        assertEquals(-1L, policy.pendingDelay(20))
        assertEquals(NONE, policy.onForceHide())
    }

    @Test fun repeatedFocusReports_doNotShowTwice() {
        assertEquals(SHOW, policy.onTextFocus(true, 0))
        assertEquals(NONE, policy.onTextFocus(true, 1))
        assertEquals(NONE, policy.onHardwareKeyboard(false, 2))
    }

    @Test fun aReadOnlyField_isNotATextField() {
        assertFalse(GuestEditStyle.typable(true, GuestEditStyle.ES_READONLY))
        assertFalse(GuestEditStyle.typable(true, GuestEditStyle.ES_READONLY or GuestEditStyle.ES_MULTILINE))
        assertTrue(GuestEditStyle.typable(true, 0))
        assertTrue(GuestEditStyle.typable(true, GuestEditStyle.ES_MULTILINE))
        assertFalse(GuestEditStyle.typable(false, 0))
    }

    @Test fun editStyleChoosesTheLayout() {
        assertEquals(KeyboardType.Text, GuestEditStyle.keyboardType(0))
        assertEquals(KeyboardType.Text, GuestEditStyle.keyboardType(GuestEditStyle.ES_MULTILINE))
        assertEquals(KeyboardType.Number, GuestEditStyle.keyboardType(GuestEditStyle.ES_NUMBER))
        assertEquals(KeyboardType.Password, GuestEditStyle.keyboardType(GuestEditStyle.ES_PASSWORD))
        assertEquals(KeyboardType.NumberPassword, GuestEditStyle.keyboardType(GuestEditStyle.ES_NUMBER or GuestEditStyle.ES_PASSWORD))
    }

    @Test fun editStyleFlags_matchWindows() {
        assertEquals(0x2000, GuestEditStyle.ES_NUMBER)
        assertEquals(0x20, GuestEditStyle.ES_PASSWORD)
        assertEquals(0x4, GuestEditStyle.ES_MULTILINE)
        assertEquals(0x800, GuestEditStyle.ES_READONLY)
    }
}
