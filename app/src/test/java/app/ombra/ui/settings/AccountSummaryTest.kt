package app.ombra.ui.settings

import app.ombra.core.model.AccountInfo
import app.ombra.core.model.AccountStatus
import app.ombra.core.model.FreeTrialInfo
import app.ombra.core.model.FundingMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountSummaryTest {
    private val now = 1_800_000_000L

    @Test
    fun noInfoYetIsUnknown() {
        assertEquals(AccountSummary.Unknown, accountSummary(null, now))
        assertFalse(AccountSummary.Unknown.active)
    }

    @Test
    fun aFundedAccountCountsTheDaysLeft() {
        val summary = accountSummary(account(AccountInfo(active = true, currentExpiry = now + 23 * DAY + 5, primaryFunding = FundingMethod.TopUp)), now)
        assertEquals(AccountState.Active, summary.state)
        assertEquals(TimeLeft.Days(23), summary.timeLeft)
        assertEquals(now + 23 * DAY + 5, summary.expiresAt)
        assertEquals(Funding.TopUp, summary.funding)
        assertEquals(now - 60, summary.checkedAt)
        assertTrue(summary.active)
    }

    @Test
    fun lessThanTenDaysIsSoon() {
        assertEquals(AccountState.Active, stateWith(currentExpiry = now + 10 * DAY))
        assertEquals(AccountState.ExpiresSoon, stateWith(currentExpiry = now + 10 * DAY - 1))
        assertEquals(TimeLeft.Hours(5), accountSummary(account(AccountInfo(active = true, currentExpiry = now + 5 * HOUR + 59)), now).timeLeft)
        assertEquals(TimeLeft.UnderAnHour, accountSummary(account(AccountInfo(active = true, currentExpiry = now + 59)), now).timeLeft)
    }

    @Test
    fun aRenewingSubscriptionShowsTheNextCharge() {
        val summary = accountSummary(
            account(AccountInfo(active = true, autoRenews = now + 17 * DAY, currentExpiry = now + 2 * DAY, primaryFunding = FundingMethod.StripeSubscription)),
            now,
        )
        assertEquals(AccountState.Renews, summary.state)
        assertEquals(now + 17 * DAY, summary.renewsAt)
        assertNull(summary.timeLeft, "a renewing account shows no countdown")
        assertEquals(Funding.Subscription, summary.funding)
    }

    @Test
    fun theActiveFlagDecides() {
        assertEquals(AccountState.Inactive, stateWith(active = false, currentExpiry = now + 30 * DAY))
        assertEquals(AccountState.ActiveNoDate, stateWith(currentExpiry = null))
    }

    @Test
    fun aRunningTrialShowsItsEnd() {
        val trial = AccountInfo(
            active = true,
            currentExpiry = now + 6 * DAY,
            primaryFunding = FundingMethod.FreeTrial,
            freeTrial = FreeTrialInfo(active = true, expiresAt = now + 6 * DAY),
        )
        val summary = accountSummary(account(trial), now)
        assertEquals(Funding.FreeTrial, summary.funding)
        assertEquals(now + 6 * DAY, summary.trialUntil)
        assertNull(accountSummary(account(trial.copy(freeTrial = FreeTrialInfo(active = false, expiresAt = now))), now).trialUntil)
    }

    @Test
    fun anUnknownFundingMethodShowsNoName() {
        assertNull(accountSummary(account(AccountInfo(active = true, primaryFunding = FundingMethod.Unknown)), now).funding)
    }

    private fun stateWith(active: Boolean = true, currentExpiry: Long?) =
        accountSummary(account(AccountInfo(active = active, currentExpiry = currentExpiry)), now).state

    private fun account(info: AccountInfo) = AccountStatus(info, lastUpdatedSec = now - 60)

    private companion object {
        const val HOUR = 3_600L
        const val DAY = 86_400L
    }
}
