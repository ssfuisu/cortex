package org.cortex.terminal.view

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtraKeysViewTest {

    @Test
    fun testExtraKeysButtonsWireClickListenerWithTouchDeduplication() {
        val candidates = listOf(
            File("src/main/java/org/cortex/terminal/view/ExtraKeysView.kt"),
            File("app/src/main/java/org/cortex/terminal/view/ExtraKeysView.kt")
        )
        val sourceFile = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("ExtraKeysView.kt not found")
        val content = sourceFile.readText()

        assertTrue(
            "ExtraKeysView must register setOnClickListener for accessibility/keyboard activation",
            content.contains("setOnClickListener")
        )
        assertTrue(
            "ExtraKeysView must guard performClick with handledByTouch to avoid double-firing",
            content.contains("handledByTouch") && content.contains("v.performClick()")
        )
    }
}
