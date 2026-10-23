package app.papertoslides.core.compose

/**
 * Text width estimate for fitting text into a box without a font engine. Latin uses the Helvetica/Arial
 * advance widths (Arial and LibreOffice's Liberation Sans share them); Devanagari and others are approximated.
 * A safety margin is applied by the composer.
 */
object Metrics {
    // Helvetica widths for ASCII 32..126, in 1/1000 em
    private val ASCII = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // space .. /
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 0 .. ?
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // @ .. O
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // P .. _
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // ` .. o
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,      // p .. ~
    )

    /** Width of one character in em. */
    fun em(c: Char): Double = when {
        c.code in 32..126 -> ASCII[c.code - 32] / 1000.0
        c in 'ऀ'..'ॿ' -> deva(c)
        c.isWhitespace() -> 0.278
        Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> 0.0
        c in '∀'..'⋿' || c in 'Ͱ'..'Ͽ' -> 0.6 // maths operators, Greek
        else -> 0.6
    }

    private fun deva(c: Char): Double = when (c) {
        in 'ऀ'..'ः', '़', '्', in 'ु'..'ै', in '॑'..'ॗ', in 'ॢ'..'ॣ' -> 0.0 // above/below marks, virama
        'ा', 'ी', 'ि', in 'ॉ'..'ौ' -> 0.28 // spacing vowel signs
        else -> 0.62
    }

    /** Width in inches of [text] at [pt], bold widened a little. */
    fun widthIn(text: String, pt: Double, bold: Boolean = false): Double {
        var em = 0.0
        for (c in text) em += em(c)
        return em * pt / 72.0 * (if (bold) 1.07 else 1.0)
    }
}
