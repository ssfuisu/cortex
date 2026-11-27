package org.cortex.terminal.view

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import androidx.appcompat.app.AppCompatActivity
import org.cortex.terminal.R
import org.cortex.terminal.emulator.TerminalColor
import org.cortex.terminal.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalImeTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var view: TerminalView
    private lateinit var sent: ByteArrayOutputStream

    @Before
    fun setUp() {
        TerminalColor.applyTheme("catppuccin")
        Chrome.apply(TerminalColor.SCHEMES.getValue("catppuccin"))
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        activity.setTheme(R.style.Theme_Cortex)
        sent = ByteArrayOutputStream()
        val session = TerminalSession(activity).apply {
            markRunningForTest(true)
            testOutputObserver = { bytes -> sent.write(bytes) }
        }
        view = TerminalView(activity).apply {
            this.session = session
        }
    }

    @Test
    fun inputConnectionAllowsKoreanCompositionAndExposesComposingText() {
        val attrs = EditorInfo()
        val ic = view.onCreateInputConnection(attrs)
        assertNotNull(ic)
        assertEquals(InputType.TYPE_CLASS_TEXT, attrs.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(0, attrs.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)

        assertTrue(ic.setComposingText("ㅎ", 1))
        assertEquals(" ㅎ", ic.getTextBeforeCursor(10, 0).toString())
        assertEquals(" ㅎ", ic.getSurroundingText(10, 10, 0)?.text.toString())
        assertEquals(" ㅎ", ic.getExtractedText(ExtractedTextRequest(), 0)?.text.toString())

        assertTrue(ic.setComposingText("하", 1))
        assertEquals(" 하", ic.getTextBeforeCursor(10, 0).toString())

        sent.reset()
        assertTrue(ic.commitText("하", 1))
        // "하" was already pushed while composing, so committing it must not duplicate or delete it
        assertEquals(0, sent.size())
        assertEquals(" ", ic.getTextBeforeCursor(10, 0).toString())
    }

    @Test
    fun composingPrefixPreservesUnchangedHangulSyllablesAndDelCountsCodePoints() {
        val ic = view.onCreateInputConnection(EditorInfo())
        assertTrue(ic.setComposingText("ㅎ", 1))
        assertTrue(ic.setComposingText("하", 1))
        assertTrue(ic.setComposingText("한국", 1))
        sent.reset()

        assertTrue(ic.setComposingText("한글", 1))
        val bytes = sent.toByteArray()
        assertEquals(0x7f, bytes[0].toInt() and 0xff)
        assertEquals("글", String(bytes, 1, bytes.size - 1, Charsets.UTF_8))
    }

    @Test
    fun hardwareLanguageSwitchNotSwallowedAndActionMultipleCommitsHangul() {
        val langKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_LANGUAGE_SWITCH)
        assertFalse(view.onKeyDown(KeyEvent.KEYCODE_LANGUAGE_SWITCH, langKey))

        val multi = KeyEvent(0L, "한글", 0, 0)
        assertTrue(view.onKeyMultiple(KeyEvent.KEYCODE_UNKNOWN, 0, multi))
        assertEquals("한글", sent.toString(Charsets.UTF_8.name()))
    }
}
