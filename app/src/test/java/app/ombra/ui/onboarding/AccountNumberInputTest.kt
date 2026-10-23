package app.ombra.ui.onboarding

import app.ombra.core.AccountNumber
import app.ombra.ui.onboarding.AccountNumberInput.Check
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountNumberInputTest {
    private val valid = "1234567890123456789".let { it + AccountNumber.checkDigit(it) }

    @Test
    fun pastedTextKeepsOnlyTwentyDigits() {
        assertEquals("12345678", AccountNumberInput.sanitize("1234 - 5678"))
        assertEquals(valid, AccountNumberInput.sanitize("Account: ${AccountNumber.format(valid, "-")}"))
        assertEquals(valid, AccountNumberInput.sanitize(valid + "999"))
        assertEquals("", AccountNumberInput.sanitize("no digits"))
    }

    @Test
    fun digitsFallIntoFiveGroupsOfFour() {
        assertEquals(listOf("", "", "", "", ""), AccountNumberInput.groups(""))
        assertEquals(listOf("1234", "56", "", "", ""), AccountNumberInput.groups("123456"))
        assertEquals(valid.chunked(4), AccountNumberInput.groups(valid))
    }

    @Test
    fun theActiveGroupTakesTheNextDigit() {
        assertEquals(0, AccountNumberInput.activeGroup(""))
        assertEquals(0, AccountNumberInput.activeGroup("123"))
        assertEquals(1, AccountNumberInput.activeGroup("1234"))
        assertEquals(4, AccountNumberInput.activeGroup("1234567890123456"))
        assertEquals(4, AccountNumberInput.activeGroup(valid))
    }

    @Test
    fun verhoeffRunsLocally() {
        assertEquals(Check.Incomplete, AccountNumberInput.check(valid.dropLast(1)))
        assertEquals(Check.Valid, AccountNumberInput.check(valid))
        val typo = valid.substring(0, 5) + ((valid[5] - '0' + 1) % 10) + valid.substring(6)
        assertEquals(Check.Invalid, AccountNumberInput.check(typo))
        val swapped = valid.substring(0, 2) + valid[3] + valid[2] + valid.substring(4)
        assertEquals(Check.Invalid, AccountNumberInput.check(swapped))
    }
}
