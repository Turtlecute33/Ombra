package app.ombra.ui.onboarding

import app.ombra.core.AccountNumber

/** Rules of the account number field: digits only, 20 at most, shown as 5 groups of 4. */
object AccountNumberInput {
    const val GROUPS = 5
    const val GROUP_SIZE = AccountNumber.LENGTH / GROUPS

    enum class Check { Incomplete, Invalid, Valid }

    /** Digits of typed or pasted text (spaces, dashes and labels drop out), cut to 20. */
    fun sanitize(text: CharSequence): String = AccountNumber.normalize(text.toString()).take(AccountNumber.LENGTH)

    /** The 5 groups; the ones not typed yet are empty. */
    fun groups(digits: String): List<String> =
        List(GROUPS) { i -> digits.drop(i * GROUP_SIZE).take(GROUP_SIZE) }

    /** Index of the group that takes the next digit; the last group when the number is complete. */
    fun activeGroup(digits: String): Int = (digits.length / GROUP_SIZE).coerceAtMost(GROUPS - 1)

    /** Local Verhoeff check, before anything goes over the network. */
    fun check(digits: String): Check = when (AccountNumber.problem(digits)) {
        null -> Check.Valid
        AccountNumber.Problem.TooShort -> Check.Incomplete
        AccountNumber.Problem.TooLong, AccountNumber.Problem.BadChecksum -> Check.Invalid
    }
}
