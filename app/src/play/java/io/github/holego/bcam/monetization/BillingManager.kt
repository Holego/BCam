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

/**
 * The one-time "remove ads" purchase, through Google Play Billing.
 *
 * Product: a *non-consumable* in-app product with id [PRODUCT_ID], priced at US$9.00 in
 * the Play Console. The price shown in the app is whatever Play reports for the user's
 * country, so it is never hard-coded here.
 *
 * Entitlement is re-checked with Google Play on every start ([queryOwned]); that is also
 * how a purchase is restored after a reinstall or on a new device, and how a refund takes
 * effect. If Play cannot be reached the last known answer is kept.
 *
 * There is no backend, so purchases are not verified server-side. For a US$9 unlock in a
 * small app that is an accepted trade-off; it does mean a rooted device can fake the
 * entitlement locally.
 */
internal class BillingManager(
    context: Context,
    /** `true`/`false` once Play has answered; `null` when it could not be reached. */
    private val onEntitlement: (owned: Boolean?) -> Unit,
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

    /** Localised price such as "$9.00", or null until Play has described the product. */
    val formattedPrice: String?
        get() = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice

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

    /** Opens Google Play's purchase sheet. Returns false if the product is not available. */
    fun launchPurchase(activity: Activity): Boolean {
        val details = productDetails ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
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
            // Bought earlier (reinstall, other device): just re-read what Play knows.
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
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = queryResult.productDetailsList.firstOrNull()
                if (productDetails == null) {
                    Log.w(TAG, "Product $PRODUCT_ID is not configured in the Play Console")
                }
            } else {
                Log.w(TAG, "queryProductDetails: " + result.responseCode + " " + result.debugMessage)
            }
            onProductReady()
        }
    }

    private fun queryOwned() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchases: " + result.responseCode + " " + result.debugMessage)
                onEntitlement(null)
                return@queryPurchasesAsync
            }
            val owned = purchases.filter { ownsRemoveAds(it) }
            owned.forEach(::acknowledgeIfNeeded)
            onEntitlement(owned.isNotEmpty())
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

    private fun ownsRemoveAds(purchase: Purchase): Boolean =
        purchase.products.contains(PRODUCT_ID) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED

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
        /** Must match the product id created in Play Console > Monetize > In-app products. */
        const val PRODUCT_ID = "remove_ads"
        private const val TAG = "Monetization"
    }
}
