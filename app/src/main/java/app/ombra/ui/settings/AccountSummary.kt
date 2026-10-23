package app.ombra.ui.settings

import androidx.compose.runtime.Immutable
import app.ombra.core.model.AccountStatus
import app.ombra.core.model.FundingMethod

/** The funding state of the account, as the UI words it. */
enum class AccountState {
    /** No account info yet (not fetched since login). */
    Unknown,

    /** Funded and renews by itself. */
    Renews,

    /** Funded until a date. */
    Active,

    /** Funded, with less than [AccountSummary.SOON_DAYS] days left. */
    ExpiresSoon,

    /** Funded; the API gives no date (a funding method this version does not know). */
    ActiveNoDate,

    /** No time left. */
    Inactive,
}

/** How the account is funded. Unknown methods stay null: new ones may appear at any time. */
enum class Funding { FreeTrial, TopUp, Subscription, GooglePlay, AppStore }

/** Time until a date, rounded down: whole days, else whole hours, else under an hour. */
sealed interface TimeLeft {
    data class Days(val days: Int) : TimeLeft

    data class Hours(val hours: Int) : TimeLeft

    data object UnderAnHour : TimeLeft
}

/** The account info as the Account screen shows it. Times are Unix seconds. */
@Immutable
data class AccountSummary(
    val state: AccountState,
    /** When the time runs out ([AccountState.Active], [AccountState.ExpiresSoon]). */
    val expiresAt: Long? = null,
    val timeLeft: TimeLeft? = null,
    /** The next charge ([AccountState.Renews]). */
    val renewsAt: Long? = null,
    val funding: Funding? = null,
    /** End of a free trial that is running. */
    val trialUntil: Long? = null,
    /** When :vpn fetched the info. */
    val checkedAt: Long? = null,
) {
    val active: Boolean
        get() = when (state) {
            AccountState.Renews, AccountState.Active, AccountState.ExpiresSoon, AccountState.ActiveNoDate -> true
            AccountState.Unknown, AccountState.Inactive -> false
        }

    companion object {
        val Unknown = AccountSummary(AccountState.Unknown)

        /** Upstream warns from 10 days before the end. */
        const val SOON_DAYS = 10
    }
}

/** Follows the API rules: `active` is the funded flag; `auto_renews` and `current_expiry` say for how long. */
fun accountSummary(account: AccountStatus?, nowSeconds: Long): AccountSummary {
    val info = account?.accountInfo ?: return AccountSummary.Unknown
    val expiry = info.currentExpiry
    val renews = info.autoRenews
    val left = expiry?.let { timeLeft(it, nowSeconds) }
    val state = when {
        !info.active -> AccountState.Inactive
        renews != null -> AccountState.Renews
        left == null -> AccountState.ActiveNoDate
        left is TimeLeft.Days && left.days >= AccountSummary.SOON_DAYS -> AccountState.Active
        else -> AccountState.ExpiresSoon
    }
    val dated = state == AccountState.Active || state == AccountState.ExpiresSoon
    return AccountSummary(
        state = state,
        expiresAt = expiry.takeIf { dated },
        timeLeft = left.takeIf { dated },
        renewsAt = renews.takeIf { state == AccountState.Renews },
        funding = fundingOf(info.primaryFunding),
        trialUntil = info.freeTrial?.takeIf { it.active }?.expiresAt,
        checkedAt = account.lastUpdatedSec.takeIf { it > 0 },
    )
}

fun timeLeft(untilSeconds: Long, nowSeconds: Long): TimeLeft {
    val seconds = (untilSeconds - nowSeconds).coerceAtLeast(0)
    return when {
        seconds >= DAY -> TimeLeft.Days((seconds / DAY).toInt())
        seconds >= HOUR -> TimeLeft.Hours((seconds / HOUR).toInt())
        else -> TimeLeft.UnderAnHour
    }
}

private fun fundingOf(method: FundingMethod?): Funding? = when (method) {
    FundingMethod.FreeTrial -> Funding.FreeTrial
    FundingMethod.TopUp -> Funding.TopUp
    FundingMethod.StripeSubscription -> Funding.Subscription
    FundingMethod.GoogleSubscription -> Funding.GooglePlay
    FundingMethod.AppleSubscription -> Funding.AppStore
    FundingMethod.Unknown, null -> null
}

private const val HOUR = 3_600L
private const val DAY = 86_400L
