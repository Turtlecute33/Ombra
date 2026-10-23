package app.ombra.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Account data from obscuravpn-api, snake_case. Timestamps are Unix seconds.

/** `Status.account`: the last `apiGetAccountInfo` result. */
@Serializable
data class AccountStatus(
    @SerialName("account_info") val accountInfo: AccountInfo,
    @SerialName("last_updated_sec") val lastUpdatedSec: Long = 0,
)

@Serializable
data class AccountInfo(
    /** THE funded flag: true while any funding method is active. */
    val active: Boolean = false,
    @SerialName("apple_subscription") val appleSubscription: AppleSubscriptionInfo? = null,
    @SerialName("auto_renews") val autoRenews: Long? = null,
    @SerialName("current_expiry") val currentExpiry: Long? = null,
    @SerialName("free_month_on_next_payment") val freeMonthOnNextPayment: Boolean = false,
    @SerialName("free_trial") val freeTrial: FreeTrialInfo? = null,
    @SerialName("google_subscription") val googleSubscription: GoogleSubscriptionInfo? = null,
    @SerialName("has_paid") val hasPaid: Boolean = false,
    @SerialName("has_referrer") val hasReferrer: Boolean = false,
    /** The account number: a secret. StatusSanitizer removes it before IPC. */
    val id: String? = null,
    @SerialName("monero_pending_payments") val moneroPendingPayments: List<MoneroPaymentInProgress> = emptyList(),
    val nnn: NnnCaseInfo? = null,
    @SerialName("primary_funding") val primaryFunding: FundingMethod? = null,
    @SerialName("received_free_months") val receivedFreeMonths: Long = 0,
    @SerialName("referral_code") val referralCode: String = "",
    @SerialName("stripe_subscription") val stripeSubscription: StripeSubscriptionInfo? = null,
    @SerialName("top_up") val topUp: TopUp? = null,
) {
    override fun toString(): String = "AccountInfo(active=$active, currentExpiry=$currentExpiry, autoRenews=$autoRenews)"
}

@Serializable
data class AppleSubscriptionInfo(
    val active: Boolean = false,
    @SerialName("auto_renew_status") val autoRenewStatus: Boolean = false,
    @SerialName("renewal_date") val renewalDate: Long = 0,
    val status: Int = 0,
)

@Serializable
data class GoogleSubscriptionInfo(
    val active: Boolean = false,
    @SerialName("auto_renew_status") val autoRenewStatus: Boolean = false,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val status: String = "",
)

@Serializable
data class FreeTrialInfo(
    val active: Boolean = false,
    @SerialName("expires_at") val expiresAt: Long = 0,
)

@Serializable
data class TopUp(
    val active: Boolean = false,
    @SerialName("credit_expires_at") val creditExpiresAt: Long = 0,
)

@Serializable
data class StripeSubscriptionInfo(
    val active: Boolean = false,
    val status: String = "",
    @SerialName("current_period_start") val currentPeriodStart: Long = 0,
    @SerialName("current_period_end") val currentPeriodEnd: Long = 0,
    @SerialName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean = false,
)

@Serializable
data class NnnCaseInfo(
    @SerialName("applied_months") val appliedMonths: Int? = null,
    @SerialName("claimed_months") val claimedMonths: Int = 0,
    @SerialName("share_id") val shareId: String = "",
)

/** [status] is one of confirming, expired, failed, paid, partially_paid, unpaid (kept as text). */
@Serializable
data class MoneroPaymentInProgress(val id: String, val status: String)

/** New funding methods may appear at any time: they decode to [Unknown]. */
@Serializable(with = FundingMethodSerializer::class)
enum class FundingMethod(override val wire: String) : WireEnum {
    AppleSubscription("apple_subscription"),
    FreeTrial("free_trial"),
    GoogleSubscription("google_subscription"),
    StripeSubscription("stripe_subscription"),
    TopUp("top_up"),
    Unknown("unknown"),
}

internal object FundingMethodSerializer :
    WireEnumSerializer<FundingMethod>("app.ombra.FundingMethod", FundingMethod.entries, FundingMethod.Unknown)

/** Result of `apiServiceStatus` (no login needed). */
@Serializable
data class ServiceStatus(
    @SerialName("free_trial") val freeTrial: FreeTrialStatus = FreeTrialStatus(),
    val nnn: NnnStatus = NnnStatus(),
)

@Serializable
data class FreeTrialStatus(val available: Boolean = false)

@Serializable
data class NnnStatus(
    @SerialName("claimed_months_target") val claimedMonthsTarget: Long = 0,
    @SerialName("claimed_months_total") val claimedMonthsTotal: Long = 0,
    @SerialName("submissions_open") val submissionsOpen: Boolean = false,
)
