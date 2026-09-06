package org.cortex.terminal.emulator

import android.graphics.Color

object TerminalColor {
    // Standard 16 ANSI colors
    val ANSI_COLORS = intArrayOf(
        Color.rgb(0x1e, 0x1e, 0x2e), // 0: Black
        Color.rgb(0xf3, 0x8b, 0xa8), // 1: Red
        Color.rgb(0xa6, 0xe3, 0xa1), // 2: Green
        Color.rgb(0xf9, 0xe2, 0xaf), // 3: Yellow
        Color.rgb(0x89, 0xb4, 0xfa), // 4: Blue
        Color.rgb(0xcb, 0xa6, 0xf7), // 5: Magenta
        Color.rgb(0x89, 0xdc, 0xeb), // 6: Cyan
        Color.rgb(0xba, 0xc2, 0xde), // 7: White
        Color.rgb(0x58, 0x5b, 0x70), // 8: Bright Black (Gray)
        Color.rgb(0xf3, 0x8b, 0xa8), // 9: Bright Red
        Color.rgb(0xa6, 0xe3, 0xa1), // 10: Bright Green
        Color.rgb(0xf9, 0xe2, 0xaf), // 11: Bright Yellow
        Color.rgb(0x89, 0xb4, 0xfa), // 12: Bright Blue
        Color.rgb(0xcb, 0xa6, 0xf7), // 13: Bright Magenta
        Color.rgb(0x94, 0xe2, 0xd5), // 14: Bright Cyan
        Color.rgb(0xa6, 0xad, 0xc8)  // 15: Bright White
    )

    val COLOR_PALETTE = IntArray(256).apply {
        // Copy first 16 colors
        for (i in 0 until 16) {
            this[i] = ANSI_COLORS[i]
        }
        // 6x6x6 color cube (indices 16-231)
        val steps = intArrayOf(0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff)
        var idx = 16
        for (r in steps) {
            for (g in steps) {
                for (b in steps) {
                    this[idx++] = Color.rgb(r, g, b)
                }
            }
        }
        // 24 grayscale colors (indices 232-255)
        for (i in 0 until 24) {
            val gray = 8 + i * 10
            this[idx++] = Color.rgb(gray, gray, gray)
        }
    }

    const val DEFAULT_FG = -0x140f09 // Light cream/white: 0xcdd6f4
    const val DEFAULT_BG = -0xe1e1d2 // Deep dark: 0x181825
    const val CURSOR_COLOR = -0x764b06 // 0x89b4fa
}
