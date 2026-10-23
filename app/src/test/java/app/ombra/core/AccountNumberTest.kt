package app.ombra.core

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountNumberTest {
    // Check digits computed with the upstream TypeScript algorithm (accountUtils.ts) run in node.
    private val validNumbers = listOf(
        "55550123456780554337",
        "12345678901234567895",
        "00000000000000000004",
        "99999999999999999995",
    )

    @Test
    fun checkDigitMatchesKnownVerhoeffVectors() {
        assertEquals(3, AccountNumber.checkDigit("236"))
        assertEquals(1, AccountNumber.checkDigit("12345"))
        assertEquals(0, AccountNumber.checkDigit("142857"))
        assertEquals(7, AccountNumber.checkDigit("5555012345678055433"))
        assertEquals(4, AccountNumber.checkDigit("0000000000000000000"))
    }

    @Test
    fun acceptsValidNumbers() {
        validNumbers.forEach { assertTrue(AccountNumber.isValid(it), it) }
    }

    @Test
    fun acceptsFormattedInput() {
        assertTrue(AccountNumber.isValid("5555 0123 4567 8055 4337"))
        assertTrue(AccountNumber.isValid("5555 - 0123 - 4567 - 8055 - 4337"))
    }

    @Test
    fun rejectsEverySingleDigitError() {
        val number = validNumbers.first()
        for (i in number.indices) {
            for (d in '0'..'9') {
                if (d == number[i]) continue
                val typo = number.substring(0, i) + d + number.substring(i + 1)
                assertEquals(AccountNumber.Problem.BadChecksum, AccountNumber.problem(typo), typo)
            }
        }
    }

    @Test
    fun rejectsAdjacentTranspositions() {
        val number = validNumbers.first()
        for (i in 0 until number.length - 1) {
            if (number[i] == number[i + 1]) continue
            val swapped = number.substring(0, i) + number[i + 1] + number[i] + number.substring(i + 2)
            assertFalse(AccountNumber.isValid(swapped), swapped)
        }
    }

    @Test
    fun reportsLengthProblems() {
        assertEquals(AccountNumber.Problem.TooShort, AccountNumber.problem(""))
        assertEquals(AccountNumber.Problem.TooShort, AccountNumber.problem("5555012345678055433"))
        assertEquals(AccountNumber.Problem.TooLong, AccountNumber.problem("555501234567805543370"))
        assertNull(AccountNumber.problem("55550123456780554337"))
    }

    @Test
    fun formatsInGroupsOfFour() {
        assertEquals("5555 0123 4567 8055 4337", AccountNumber.format("55550123456780554337"))
        assertEquals("5555-0123-4", AccountNumber.format("5555 0123 4", separator = "-"))
        assertEquals("55550123456780554337", AccountNumber.normalize(" 5555-0123 4567.8055/4337 "))
    }

    @Test
    fun generatedNumbersAreAlwaysValid() {
        val random = SecureRandom()
        repeat(2_000) {
            val number = AccountNumber.generate(random)
            assertEquals(AccountNumber.LENGTH, number.length)
            assertTrue(number.all { it in '0'..'9' }, number)
            assertTrue(AccountNumber.isValid(number), number)
        }
    }

    @Test
    fun generateRejectsDrawsAtOrAboveTenToTheNineteen() {
        // -1 is 2^64 - 1 unsigned and 10^19 is the first rejected value; 0 is accepted and zero-padded.
        val draws = ArrayDeque(listOf(-1L, 10_000_000_000_000_000_000uL.toLong(), 0L))
        val random = object : SecureRandom() {
            override fun nextLong(): Long = draws.removeFirst()
        }
        assertEquals("00000000000000000004", AccountNumber.generate(random))
        assertTrue(draws.isEmpty())
    }

    @Test
    fun generateKeepsTheLargestAcceptedDraw() {
        val max = 9_999_999_999_999_999_999uL.toLong()
        val random = object : SecureRandom() {
            override fun nextLong(): Long = max
        }
        assertEquals("99999999999999999995", AccountNumber.generate(random))
    }
}
