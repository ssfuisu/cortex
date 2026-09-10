package org.cortex.terminal.emulator

import android.graphics.Color

object TerminalColor {

    data class ColorScheme(
        val id: String,
        val displayName: String,
        val fg: Int,
        val bg: Int,
        val cursor: Int,
        val ansi: IntArray
    )

    val SCHEMES = mapOf(
        "catppuccin" to ColorScheme(
            id = "catppuccin",
            displayName = "Catppuccin Mocha",
            fg = Color.rgb(0xcd, 0xd6, 0xf4),
            bg = Color.rgb(0x18, 0x18, 0x25),
            cursor = Color.rgb(0x89, 0xb4, 0xfa),
            ansi = intArrayOf(
                Color.rgb(0x1e, 0x1e, 0x2e), // 0: Black
                Color.rgb(0xf3, 0x8b, 0xa8), // 1: Red
                Color.rgb(0xa6, 0xe3, 0xa1), // 2: Green
                Color.rgb(0xf9, 0xe2, 0xaf), // 3: Yellow
                Color.rgb(0x89, 0xb4, 0xfa), // 4: Blue
                Color.rgb(0xcb, 0xa6, 0xf7), // 5: Magenta
                Color.rgb(0x89, 0xdc, 0xeb), // 6: Cyan
                Color.rgb(0xba, 0xc2, 0xde), // 7: White
                Color.rgb(0x58, 0x5b, 0x70), // 8: Bright Black
                Color.rgb(0xf3, 0x8b, 0xa8), // 9: Bright Red
                Color.rgb(0xa6, 0xe3, 0xa1), // 10: Bright Green
                Color.rgb(0xf9, 0xe2, 0xaf), // 11: Bright Yellow
                Color.rgb(0x89, 0xb4, 0xfa), // 12: Bright Blue
                Color.rgb(0xcb, 0xa6, 0xf7), // 13: Bright Magenta
                Color.rgb(0x94, 0xe2, 0xd5), // 14: Bright Cyan
                Color.rgb(0xa6, 0xad, 0xc8)  // 15: Bright White
            )
        ),
        "tokyo_night" to ColorScheme(
            id = "tokyo_night",
            displayName = "Tokyo Night",
            fg = Color.rgb(0xc0, 0xca, 0xf5),
            bg = Color.rgb(0x1a, 0x1b, 0x26),
            cursor = Color.rgb(0xc0, 0xca, 0xf5),
            ansi = intArrayOf(
                Color.rgb(0x15, 0x16, 0x1e), // 0
                Color.rgb(0xf7, 0x76, 0x8e), // 1
                Color.rgb(0x9e, 0xce, 0x6a), // 2
                Color.rgb(0xe0, 0xaf, 0x68), // 3
                Color.rgb(0x7a, 0xa2, 0xf7), // 4
                Color.rgb(0xbb, 0x9a, 0xf7), // 5
                Color.rgb(0x7d, 0xcf, 0xff), // 6
                Color.rgb(0xa9, 0xb1, 0xd6), // 7
                Color.rgb(0x41, 0x48, 0x68), // 8
                Color.rgb(0xf7, 0x76, 0x8e), // 9
                Color.rgb(0x9e, 0xce, 0x6a), // 10
                Color.rgb(0xe0, 0xaf, 0x68), // 11
                Color.rgb(0x7a, 0xa2, 0xf7), // 12
                Color.rgb(0xbb, 0x9a, 0xf7), // 13
                Color.rgb(0x7d, 0xcf, 0xff), // 14
                Color.rgb(0xc0, 0xca, 0xf5)  // 15
            )
        ),
        "dracula" to ColorScheme(
            id = "dracula",
            displayName = "Dracula",
            fg = Color.rgb(0xf8, 0xf8, 0xf2),
            bg = Color.rgb(0x28, 0x2a, 0x36),
            cursor = Color.rgb(0xf8, 0xf8, 0xf2),
            ansi = intArrayOf(
                Color.rgb(0x21, 0x22, 0x2c), // 0
                Color.rgb(0xff, 0x55, 0x55), // 1
                Color.rgb(0x50, 0xfa, 0x7b), // 2
                Color.rgb(0xf1, 0xfa, 0x8c), // 3
                Color.rgb(0xbd, 0x93, 0xf9), // 4
                Color.rgb(0xff, 0x79, 0xc6), // 5
                Color.rgb(0x8b, 0xe9, 0xfd), // 6
                Color.rgb(0xf8, 0xf8, 0xf2), // 7
                Color.rgb(0x62, 0x72, 0xa4), // 8
                Color.rgb(0xff, 0x6e, 0x6e), // 9
                Color.rgb(0x69, 0xff, 0x94), // 10
                Color.rgb(0xff, 0xff, 0xa5), // 11
                Color.rgb(0xd6, 0xac, 0xff), // 12
                Color.rgb(0xff, 0x92, 0xdf), // 13
                Color.rgb(0xa4, 0xff, 0xff), // 14
                Color.rgb(0xff, 0xff, 0xff)  // 15
            )
        ),
        "gruvbox" to ColorScheme(
            id = "gruvbox",
            displayName = "Gruvbox Dark",
            fg = Color.rgb(0xeb, 0xdb, 0xb2),
            bg = Color.rgb(0x28, 0x28, 0x28),
            cursor = Color.rgb(0xeb, 0xdb, 0xb2),
            ansi = intArrayOf(
                Color.rgb(0x28, 0x28, 0x28), // 0
                Color.rgb(0xcc, 0x24, 0x1d), // 1
                Color.rgb(0x98, 0x97, 0x1a), // 2
                Color.rgb(0xd7, 0x99, 0x21), // 3
                Color.rgb(0x45, 0x85, 0x88), // 4
                Color.rgb(0xb1, 0x62, 0x86), // 5
                Color.rgb(0x68, 0x9d, 0x6a), // 6
                Color.rgb(0xa8, 0x99, 0x84), // 7
                Color.rgb(0x92, 0x83, 0x74), // 8
                Color.rgb(0xfb, 0x49, 0x34), // 9
                Color.rgb(0xb8, 0xbb, 0x26), // 10
                Color.rgb(0xfa, 0xbd, 0x2f), // 11
                Color.rgb(0x83, 0xa5, 0x98), // 12
                Color.rgb(0xd3, 0x86, 0x9b), // 13
                Color.rgb(0x8e, 0xc0, 0x7c), // 14
                Color.rgb(0xeb, 0xdb, 0xb2)  // 15
            )
        ),
        "one_dark" to ColorScheme(
            id = "one_dark",
            displayName = "One Dark",
            fg = Color.rgb(0xab, 0xb2, 0xbf),
            bg = Color.rgb(0x1e, 0x22, 0x2a),
            cursor = Color.rgb(0x52, 0x8b, 0xff),
            ansi = intArrayOf(
                Color.rgb(0x1e, 0x22, 0x2a), // 0
                Color.rgb(0xe0, 0x6c, 0x75), // 1
                Color.rgb(0x98, 0xc3, 0x79), // 2
                Color.rgb(0xe5, 0xc0, 0x7b), // 3
                Color.rgb(0x61, 0xaf, 0xef), // 4
                Color.rgb(0xc6, 0x78, 0xdd), // 5
                Color.rgb(0x56, 0xb6, 0xc2), // 6
                Color.rgb(0xab, 0xb2, 0xbf), // 7
                Color.rgb(0x5c, 0x63, 0x70), // 8
                Color.rgb(0xe0, 0x6c, 0x75), // 9
                Color.rgb(0x98, 0xc3, 0x79), // 10
                Color.rgb(0xe5, 0xc0, 0x7b), // 11
                Color.rgb(0x61, 0xaf, 0xef), // 12
                Color.rgb(0xc6, 0x78, 0xdd), // 13
                Color.rgb(0x56, 0xb6, 0xc2), // 14
                Color.rgb(0xff, 0xff, 0xff)  // 15
            )
        ),
        "nord" to ColorScheme(
            id = "nord",
            displayName = "Nord",
            fg = Color.rgb(0xd8, 0xde, 0xe9),
            bg = Color.rgb(0x2e, 0x34, 0x40),
            cursor = Color.rgb(0xd8, 0xde, 0xe9),
            ansi = intArrayOf(
                Color.rgb(0x3b, 0x42, 0x52), // 0
                Color.rgb(0xbf, 0x61, 0x6a), // 1
                Color.rgb(0xa3, 0xbe, 0x8c), // 2
                Color.rgb(0xeb, 0xcb, 0x8b), // 3
                Color.rgb(0x81, 0xa1, 0xc1), // 4
                Color.rgb(0xb4, 0x8e, 0xad), // 5
                Color.rgb(0x88, 0xc0, 0xd0), // 6
                Color.rgb(0xe5, 0xe9, 0xf0), // 7
                Color.rgb(0x4c, 0x56, 0x6a), // 8
                Color.rgb(0xbf, 0x61, 0x6a), // 9
                Color.rgb(0xa3, 0xbe, 0x8c), // 10
                Color.rgb(0xeb, 0xcb, 0x8b), // 11
                Color.rgb(0x81, 0xa1, 0xc1), // 12
                Color.rgb(0xb4, 0x8e, 0xad), // 13
                Color.rgb(0x8f, 0xbc, 0xbb), // 14
                Color.rgb(0xec, 0xef, 0xf4)  // 15
            )
        )
    )

    var currentThemeId: String = "catppuccin"
        private set

    var DEFAULT_FG: Int = Color.rgb(0xcd, 0xd6, 0xf4)
    var DEFAULT_BG: Int = Color.rgb(0x18, 0x18, 0x25)
    var CURSOR_COLOR: Int = Color.rgb(0x89, 0xb4, 0xfa)

    val ANSI_COLORS = IntArray(16)
    val COLOR_PALETTE = IntArray(256)

    init {
        applyTheme("catppuccin")
    }

    fun rebuildPalette() {
        for (i in 0 until 16) {
            COLOR_PALETTE[i] = ANSI_COLORS[i]
        }
        val steps = intArrayOf(0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff)
        var idx = 16
        for (r in steps) {
            for (g in steps) {
                for (b in steps) {
                    COLOR_PALETTE[idx++] = Color.rgb(r, g, b)
                }
            }
        }
        for (i in 0 until 24) {
            val gray = 8 + i * 10
            COLOR_PALETTE[idx++] = Color.rgb(gray, gray, gray)
        }
    }

    fun applyTheme(themeId: String) {
        val scheme = SCHEMES[themeId] ?: SCHEMES["catppuccin"] ?: return
        currentThemeId = scheme.id
        DEFAULT_FG = scheme.fg
        DEFAULT_BG = scheme.bg
        CURSOR_COLOR = scheme.cursor
        for (i in 0 until 16) {
            ANSI_COLORS[i] = scheme.ansi[i]
        }
        rebuildPalette()
    }
}
