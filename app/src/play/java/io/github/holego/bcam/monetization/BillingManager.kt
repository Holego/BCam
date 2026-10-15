package io.github.holego.bcam.monetization

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import io.github.holego.bcam.R

/** What the screen needs to say about the subscription before the user taps. */
internal data class SubscriptionOffer(
    /** Localised price of one renewal, e.g. "$9.00", straight from Google Play. */
    val formattedPrice: String,
    /** How often it renews; null if Play reported something this app does not recognise. */
    val period: SubscriptionPeriod?,
)

/**
 * The ad-free subscription, through Google Play Billing.
 *
 * Product: an auto-renewing subscription with id [PRODUCT_ID] and exactly one base plan,
 * both created in the Play Console. The price and the renewal period are read from Google
 * Play at run time and are never hard-coded, so they can be changed in the Console without
 * an app update.
 *
 * "Subscribed" is re-checked with Google Play on every start ([queryOwned]). That is how a
 * subscription is restored after a reinstall or on a new device, and how a lapse, a
 * cancellation that has run out, a refund or an account hold takes effect. If Play cannot
 * be reached the last known answer is kept.
 *
 * There is no backend, so purchases are not verified server-side. For an ad-free toggle in
 * a small app that is an accepted trade-off; it does mean a rooted device can fake the
 * entitlement locally.
 */
internal class BillingManager(
    context: Context,
    /** `true`/`false` once Play has answered; `null` when it could not be reached. */
    private val onEntitlement: (subscribed: Boolean?) -> Unit,
    private val onProductReady: () -> Unit,
    private val onMessage: (messageRes: Int) -> Unit,
) : PurchasesUpdatedListener {

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    private var productDetails: ProductDetails? = null
    private var chosenOffer: ProductDetails.SubscriptionOfferDetails? = null

    /** Price and period to show, or null until Play has described the subscription. */
    var offer: SubscriptionOffer? = null
        private set

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    queryOwned()
                } else {
                    Log.w(TAG, "Billing setup failed: " + result.responseCode + " " + result.debugMessage)
                    onEntitlement(null)
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection() reconnects on the next call.
                Log.i(TAG, "Billing service disconnected")
            }
        })
    }

    fun end() {
        client.endConnection()
    }

    /** Opens Google Play's subscription sheet. Returns false if the product is not available. */
    fun launchPurchase(activity: Activity): Boolean {
        val details = productDetails ?: return false
        val offerToken = chosenOffer?.offerToken ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow: " + result.responseCode + " " + result.debugMessage)
        }
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach(::handlePurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            // Subscribed earlier (reinstall, other device): just re-read what Play knows.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryOwned()
            else -> {
                Log.w(TAG, "Purchase failed: " + result.responseCode + " " + result.debugMessage)
                onMessage(R.string.purchase_failed)
            }
        }
    }

    private fun queryProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val details = queryResult.productDetailsList.firstOrNull()
                if (details == null) {
                    Log.w(TAG, "Subscription $PRODUCT_ID is not configured in the Play Console")
                } else {
                    productDetails = details
                    chosenOffer = chooseOffer(details)
                    offer = chosenOffer?.let(::describe)
                }
            } else {
                Log.w(TAG, "queryProductDetails: " + result.responseCode + " " + result.debugMessage)
            }
            onProductReady()
        }
    }

    /**
     * Play lists only the offers this user is eligible for. With a single base plan that is
     * the base plan; if a free trial or intro price has been configured as well, the one
     * that costs least to start is chosen, and the base plan wins a tie.
     */
    private fun chooseOffer(details: ProductDetails): ProductDetails.SubscriptionOfferDetails? =
        details.subscriptionOfferDetails.orEmpty().minWithOrNull(
            compareBy<ProductDetails.SubscriptionOfferDetails>(
                { it.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros ?: Long.MAX_VALUE },
                { it.offerId != null },
            )
        )

    /** The terms to show are those of the recurring phase, i.e. what renewals will cost. */
    private fun describe(offer: ProductDetails.SubscriptionOfferDetails): SubscriptionOffer? {
        val recurring = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return null
        return SubscriptionOffer(recurring.formattedPrice, parseBillingPeriod(recurring.billingPeriod))
    }

    private fun queryOwned() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchases: " + result.responseCode + " " + result.debugMessage)
                onEntitlement(null)
                return@queryPurchasesAsync
            }
            val active = purchases.filter { grantsAdFree(it) }
            active.forEach(::acknowledgeIfNeeded)
            onEntitlement(active.isNotEmpty())
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (!purchase.products.contains(PRODUCT_ID)) return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                acknowledgeIfNeeded(purchase)
                onEntitlement(true)
                onMessage(R.string.purchase_thanks)
            }
            // Slow payment methods (cash, bank transfer). Ads go off once it completes.
            Purchase.PurchaseState.PENDING -> onMessage(R.string.purchase_pending)
            else -> Unit
        }
    }

    /**
     * A subscription that has been cancelled but has not yet run out is still returned as
     * purchased, and rightly keeps ads off until its end date. One that is suspended
     * (account hold after a failed payment) is not entitled.
     */
    private fun grantsAdFree(purchase: Purchase): Boolean =
        purchase.products.contains(PRODUCT_ID) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
            !purchase.isSuspended

    /**
     * Play refunds a purchase that is not acknowledged within three days. If this call
     * fails (offline), [queryOwned] retries it on the next start.
     */
    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { result ->
            Log.i(TAG, "acknowledgePurchase: " + result.responseCode)
        }
    }

    companion object {
        /**
         * Must match the subscription id created in Play Console > Monetize with Play >
         * Products > Subscriptions. It cannot be changed there afterwards.
         */
        const val PRODUCT_ID = "ad_free"
        private const val TAG = "Monetization"
    }
}
