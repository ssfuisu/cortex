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
                Color.rgb(0x7f, 0x84, 0x9c), // 8: Bright Black (WCAG AA >= 4.5:1 against #181825)
                Color.rgb(0xf3, 0x8b, 0xa8), // 9: Bright Red
                Color.rgb(0xa6, 0xe3, 0xa1), // 10: Bright Green
                Color.rgb(0xf9, 0xe2, 0xaf), // 11: Bright Yellow
                Color.rgb(0x89, 0xb4, 0xfa), // 12: Bright Blue
                Color.rgb(0xcb, 0xa6, 0xf7), // 13: Bright Magenta
                Color.rgb(0x94, 0xe2, 0xd5), // 14: Bright Cyan
                Color.rgb(0xa6, 0xad, 0xc8)  // 15: Bright White
            )
        ),
        // White, in the colors of GitHub's light terminal: dark text on white, saturated colors that
        // read on it (the "white" of the 16 is a gray, as it must be to be seen)
        "light" to ColorScheme(
            id = "light",
            displayName = "Light",
            fg = Color.rgb(0x1f, 0x23, 0x28),
            bg = Color.rgb(0xff, 0xff, 0xff),
            cursor = Color.rgb(0x09, 0x69, 0xda),
            ansi = intArrayOf(
                Color.rgb(0x24, 0x29, 0x2f), // 0: Black
                Color.rgb(0xcf, 0x22, 0x2e), // 1: Red
                Color.rgb(0x11, 0x63, 0x29), // 2: Green
                Color.rgb(0x9a, 0x67, 0x00), // 3: Yellow
                Color.rgb(0x09, 0x69, 0xda), // 4: Blue
                Color.rgb(0x82, 0x50, 0xdf), // 5: Magenta
                Color.rgb(0x1b, 0x7c, 0x83), // 6: Cyan
                Color.rgb(0x6e, 0x77, 0x81), // 7: White
                Color.rgb(0x57, 0x60, 0x6a), // 8: Bright Black
                Color.rgb(0xa4, 0x0e, 0x26), // 9: Bright Red
                Color.rgb(0x1a, 0x7f, 0x37), // 10: Bright Green
                Color.rgb(0xbf, 0x87, 0x00), // 11: Bright Yellow
                Color.rgb(0x09, 0x69, 0xda), // 12: Bright Blue
                Color.rgb(0x82, 0x50, 0xdf), // 13: Bright Magenta
                Color.rgb(0x31, 0x92, 0xaa), // 14: Bright Cyan
                Color.rgb(0x84, 0x8d, 0x97)  // 15: Bright White
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
        ),
        "solarized_dark" to ColorScheme(
            id = "solarized_dark",
            displayName = "Solarized Dark",
            fg = Color.rgb(0x83, 0x94, 0x96),
            bg = Color.rgb(0x00, 0x2b, 0x36),
            cursor = Color.rgb(0x93, 0xa1, 0xa1),
            ansi = intArrayOf(
                Color.rgb(0x07, 0x36, 0x42), // 0
                Color.rgb(0xdc, 0x32, 0x2f), // 1
                Color.rgb(0x85, 0x99, 0x00), // 2
                Color.rgb(0xb5, 0x89, 0x00), // 3
                Color.rgb(0x26, 0x8b, 0xd2), // 4
                Color.rgb(0xd3, 0x36, 0x82), // 5
                Color.rgb(0x2a, 0xa1, 0x98), // 6
                Color.rgb(0xee, 0xe8, 0xd5), // 7
                Color.rgb(0x58, 0x6e, 0x75), // 8
                Color.rgb(0xcb, 0x4b, 0x16), // 9
                Color.rgb(0x85, 0x99, 0x00), // 10
                Color.rgb(0xb5, 0x89, 0x00), // 11
                Color.rgb(0x26, 0x8b, 0xd2), // 12
                Color.rgb(0x6c, 0x71, 0xc4), // 13
                Color.rgb(0x2a, 0xa1, 0x98), // 14
                Color.rgb(0xfd, 0xf6, 0xe3)  // 15
            )
        ),
        "monokai" to ColorScheme(
            id = "monokai",
            displayName = "Monokai",
            fg = Color.rgb(0xf8, 0xf8, 0xf2),
            bg = Color.rgb(0x27, 0x28, 0x22),
            cursor = Color.rgb(0xf8, 0xf8, 0xf0),
            ansi = intArrayOf(
                Color.rgb(0x27, 0x28, 0x22), // 0
                Color.rgb(0xf9, 0x26, 0x72), // 1
                Color.rgb(0xa6, 0xe2, 0x2e), // 2
                Color.rgb(0xf4, 0xbf, 0x75), // 3
                Color.rgb(0x66, 0xd9, 0xef), // 4
                Color.rgb(0xae, 0x81, 0xff), // 5
                Color.rgb(0xa1, 0xef, 0xe4), // 6
                Color.rgb(0xf8, 0xf8, 0xf2), // 7
                Color.rgb(0x75, 0x71, 0x5e), // 8
                Color.rgb(0xf9, 0x26, 0x72), // 9
                Color.rgb(0xa6, 0xe2, 0x2e), // 10
                Color.rgb(0xf4, 0xbf, 0x75), // 11
                Color.rgb(0x66, 0xd9, 0xef), // 12
                Color.rgb(0xae, 0x81, 0xff), // 13
                Color.rgb(0xa1, 0xef, 0xe4), // 14
                Color.rgb(0xf9, 0xf8, 0xf5)  // 15
            )
        ),
        "rose_pine" to ColorScheme(
            id = "rose_pine",
            displayName = "Rosé Pine",
            fg = Color.rgb(0xe0, 0xde, 0xf4),
            bg = Color.rgb(0x19, 0x17, 0x24),
            cursor = Color.rgb(0xe0, 0xde, 0xf4),
            ansi = intArrayOf(
                Color.rgb(0x26, 0x23, 0x3a), // 0
                Color.rgb(0xeb, 0x6f, 0x92), // 1
                Color.rgb(0x31, 0x74, 0x8f), // 2
                Color.rgb(0xf6, 0xc1, 0x77), // 3
                Color.rgb(0x9c, 0xcf, 0xd8), // 4
                Color.rgb(0xc4, 0xa7, 0xe7), // 5
                Color.rgb(0xeb, 0xbc, 0xba), // 6
                Color.rgb(0xe0, 0xde, 0xf4), // 7
                Color.rgb(0x6e, 0x6a, 0x86), // 8
                Color.rgb(0xeb, 0x6f, 0x92), // 9
                Color.rgb(0x31, 0x74, 0x8f), // 10
                Color.rgb(0xf6, 0xc1, 0x77), // 11
                Color.rgb(0x9c, 0xcf, 0xd8), // 12
                Color.rgb(0xc4, 0xa7, 0xe7), // 13
                Color.rgb(0xeb, 0xbc, 0xba), // 14
                Color.rgb(0xe0, 0xde, 0xf4)  // 15
            )
        ),
        "everforest" to ColorScheme(
            id = "everforest",
            displayName = "Everforest",
            fg = Color.rgb(0xd3, 0xc6, 0xaa),
            bg = Color.rgb(0x2b, 0x33, 0x39),
            cursor = Color.rgb(0xd3, 0xc6, 0xaa),
            ansi = intArrayOf(
                Color.rgb(0x4b, 0x56, 0x5c), // 0
                Color.rgb(0xe6, 0x7e, 0x80), // 1
                Color.rgb(0xa7, 0xc0, 0x80), // 2
                Color.rgb(0xdb, 0xbc, 0x7f), // 3
                Color.rgb(0x7f, 0xbb, 0xb3), // 4
                Color.rgb(0xd6, 0x99, 0xb6), // 5
                Color.rgb(0x83, 0xc0, 0x92), // 6
                Color.rgb(0xd3, 0xc6, 0xaa), // 7
                Color.rgb(0x5c, 0x6a, 0x72), // 8
                Color.rgb(0xe6, 0x7e, 0x80), // 9
                Color.rgb(0xa7, 0xc0, 0x80), // 10
                Color.rgb(0xdb, 0xbc, 0x7f), // 11
                Color.rgb(0x7f, 0xbb, 0xb3), // 12
                Color.rgb(0xd6, 0x99, 0xb6), // 13
                Color.rgb(0x83, 0xc0, 0x92), // 14
                Color.rgb(0xe6, 0xdf, 0xc6)  // 15
            )
        ),
        "github_dark" to ColorScheme(
            id = "github_dark",
            displayName = "GitHub Dark",
            fg = Color.rgb(0xc9, 0xd1, 0xd9),
            bg = Color.rgb(0x0d, 0x11, 0x17),
            cursor = Color.rgb(0x58, 0xa6, 0xff),
            ansi = intArrayOf(
                Color.rgb(0x48, 0x4f, 0x58), // 0
                Color.rgb(0xff, 0x7b, 0x72), // 1
                Color.rgb(0x3f, 0xb9, 0x50), // 2
                Color.rgb(0xd2, 0x99, 0x22), // 3
                Color.rgb(0x58, 0xa6, 0xff), // 4
                Color.rgb(0xbc, 0x8c, 0xff), // 5
                Color.rgb(0x39, 0xc5, 0xcf), // 6
                Color.rgb(0xb1, 0xba, 0xc4), // 7
                Color.rgb(0x6e, 0x76, 0x81), // 8
                Color.rgb(0xff, 0xa1, 0x98), // 9
                Color.rgb(0x56, 0xd3, 0x64), // 10
                Color.rgb(0xe3, 0xb3, 0x41), // 11
                Color.rgb(0x79, 0xc0, 0xff), // 12
                Color.rgb(0xd2, 0xa8, 0xff), // 13
                Color.rgb(0x56, 0xd4, 0xdd), // 14
                Color.rgb(0xf0, 0xf6, 0xfc)  // 15
            )
        ),
        "ayu_mirage" to ColorScheme(
            id = "ayu_mirage",
            displayName = "Ayu Mirage",
            fg = Color.rgb(0xcb, 0xcc, 0xc6),
            bg = Color.rgb(0x1f, 0x24, 0x30),
            cursor = Color.rgb(0xff, 0xcc, 0x66),
            ansi = intArrayOf(
                Color.rgb(0x19, 0x1e, 0x2a), // 0
                Color.rgb(0xed, 0x82, 0x74), // 1
                Color.rgb(0xa6, 0xcc, 0x70), // 2
                Color.rgb(0xfa, 0xd0, 0x7b), // 3
                Color.rgb(0x6d, 0xcb, 0xfa), // 4
                Color.rgb(0xcf, 0xba, 0xfa), // 5
                Color.rgb(0x90, 0xe1, 0xc6), // 6
                Color.rgb(0xc7, 0xc7, 0xc7), // 7
                Color.rgb(0x68, 0x68, 0x68), // 8
                Color.rgb(0xf2, 0x87, 0x79), // 9
                Color.rgb(0xba, 0xe6, 0x7e), // 10
                Color.rgb(0xff, 0xd5, 0x80), // 11
                Color.rgb(0x73, 0xd0, 0xff), // 12
                Color.rgb(0xd4, 0xbf, 0xff), // 13
                Color.rgb(0x95, 0xe6, 0xcb), // 14
                Color.rgb(0xff, 0xff, 0xff)  // 15
            )
        ),
        "amoled" to ColorScheme(
            id = "amoled",
            displayName = "Black (AMOLED)",
            fg = Color.rgb(0xcd, 0xd6, 0xf4),
            bg = Color.rgb(0x00, 0x00, 0x00),
            cursor = Color.rgb(0x89, 0xb4, 0xfa),
            ansi = intArrayOf(
                Color.rgb(0x1e, 0x1e, 0x2e), // 0
                Color.rgb(0xf3, 0x8b, 0xa8), // 1
                Color.rgb(0xa6, 0xe3, 0xa1), // 2
                Color.rgb(0xf9, 0xe2, 0xaf), // 3
                Color.rgb(0x89, 0xb4, 0xfa), // 4
                Color.rgb(0xcb, 0xa6, 0xf7), // 5
                Color.rgb(0x89, 0xdc, 0xeb), // 6
                Color.rgb(0xba, 0xc2, 0xde), // 7
                Color.rgb(0x7f, 0x84, 0x9c), // 8
                Color.rgb(0xf3, 0x8b, 0xa8), // 9
                Color.rgb(0xa6, 0xe3, 0xa1), // 10
                Color.rgb(0xf9, 0xe2, 0xaf), // 11
                Color.rgb(0x89, 0xb4, 0xfa), // 12
                Color.rgb(0xcb, 0xa6, 0xf7), // 13
                Color.rgb(0x94, 0xe2, 0xd5), // 14
                Color.rgb(0xa6, 0xad, 0xc8)  // 15
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

    // The colors of the scheme that the cells of the sessions have been brought to (declared before
    // the init block: properties and init blocks of an object run in the order they are written)
    private var held = IntArray(18)

    init {
        applyTheme("catppuccin")
        held = inUse()
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

    /**
     * What a change of scheme makes of the colors that cells hold. A cell keeps the color the scheme
     * had when it was written (as a number, not as "the default"), so the screen and the scrollback
     * of a session that was there before the change would go on showing the old scheme: its
     * background came back as a block when the keyboard closed and the terminal grew into the rows
     * it had hidden. Only the default and the 16 ANSI colors change with a scheme; the other 240 and
     * the 24-bit ones are the same in all of them.
     *
     * A number does not say which of them it was: in Gruvbox the default background is also ANSI
     * black and the default foreground ANSI white. The colors are therefore tried in the order that
     * is likely for where the number is: as a foreground the default foreground, then the ANSI ones,
     * the default background last (text in the color of the background would not be seen); as a
     * background the default background, then the ANSI ones, the default foreground last. A cell
     * that is ANSI black on Gruvbox's black background is still seen as the background, and a
     * 24-bit color that equals one of them is changed with it.
     */
    class Remap internal constructor(
        private val fgFrom: IntArray, private val fgTo: IntArray,
        private val bgFrom: IntArray, private val bgTo: IntArray
    ) {
        fun mapFg(color: Int): Int {
            for (i in fgFrom.indices) if (fgFrom[i] == color) return fgTo[i]
            return color
        }

        fun mapBg(color: Int): Int {
            for (i in bgFrom.indices) if (bgFrom[i] == color) return bgTo[i]
            return color
        }
    }

    // [0] the default foreground, [1] the default background, [2..17] the ANSI colors
    private fun inUse() = intArrayOf(DEFAULT_FG, DEFAULT_BG, *ANSI_COLORS)

    /**
     * What to do with the cells of the sessions to bring them to the scheme in use, null when they are
     * there. Whoever gets it does that at once for all sessions: the next answer starts from here. (The
     * settings change the scheme in use for themselves, so the sessions are not told by a change.)
     */
    fun takeRemap(): Remap? {
        val now = inUse()
        if (now.indices.all { held[it] == now[it] }) {
            held = now
            return null
        }
        // The order of the colors, see Remap
        val fgOrder = (listOf(0) + (2..17) + listOf(1)).filter { held[it] != now[it] }
        val bgOrder = (listOf(1) + (2..17) + listOf(0)).filter { held[it] != now[it] }
        val remap = Remap(
            IntArray(fgOrder.size) { held[fgOrder[it]] }, IntArray(fgOrder.size) { now[fgOrder[it]] },
            IntArray(bgOrder.size) { held[bgOrder[it]] }, IntArray(bgOrder.size) { now[bgOrder[it]] }
        )
        held = now
        return remap
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
