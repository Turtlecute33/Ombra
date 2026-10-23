package app.ombra.core

import java.security.SecureRandom

/**
 * Obscura account numbers: 19 random digits plus a Verhoeff check digit (20 digits). Same rules as
 * upstream obscura-ui/src/common/accountUtils.ts. The number is the only credential: never log it.
 */
object AccountNumber {
    const val LENGTH = 20
    private const val ID_LENGTH = LENGTH - 1
    private const val GROUP = 4

    /** 10^19: random ids are drawn below this bound. */
    private const val ID_BOUND: ULong = 10_000_000_000_000_000_000uL

    enum class Problem { TooShort, TooLong, BadChecksum }

    /** Keeps only ASCII digits, so pasted text with spaces or dashes works. */
    fun normalize(input: String): String = input.filter { it in '0'..'9' }

    /** Digits in groups of 4, for display and for input fields while typing. */
    fun format(input: String, separator: String = " "): String = normalize(input).chunked(GROUP).joinToString(separator)

    /** Null when [input] (after [normalize]) is a valid account number. */
    fun problem(input: String): Problem? {
        val digits = normalize(input)
        return when {
            digits.length < LENGTH -> Problem.TooShort
            digits.length > LENGTH -> Problem.TooLong
            checksum(digits) != 0 -> Problem.BadChecksum
            else -> null
        }
    }

    fun isValid(input: String): Boolean = problem(input) == null

    /** The Verhoeff check digit to append to [digits]. */
    fun checkDigit(digits: String): Int = INVERSE[checksum(digits + "0")]

    /**
     * A new account number from a CSPRNG: an unsigned 64-bit draw is kept only when below 10^19,
     * so every 19-digit id is equally likely; zero-padded, then the check digit.
     */
    fun generate(random: SecureRandom = SecureRandom()): String {
        while (true) {
            val draw = random.nextLong().toULong()
            if (draw < ID_BOUND) {
                val id = draw.toString().padStart(ID_LENGTH, '0')
                return id + checkDigit(id)
            }
        }
    }

    private fun checksum(digits: String): Int {
        var c = 0
        // Verhoeff runs from the rightmost digit; position i uses permutation P[i mod 8].
        for (i in digits.indices) {
            val digit = digits[digits.length - 1 - i] - '0'
            require(digit in 0..9) { "not a digit" }
            c = D[c][P[i % 8][digit]]
        }
        return c
    }

    private val D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )

    private val P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )

    private val INVERSE = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)
}
