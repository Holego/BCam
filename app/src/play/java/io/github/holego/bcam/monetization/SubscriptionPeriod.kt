package io.github.holego.bcam.monetization

enum class PeriodUnit { DAY, WEEK, MONTH, YEAR }

/** How often a subscription renews, e.g. 1 YEAR or 3 MONTH. */
data class SubscriptionPeriod(val count: Int, val unit: PeriodUnit)

private val ISO_PERIOD = Regex("""^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?$""")

/**
 * Parses the ISO-8601 billing period Google Play reports for a subscription phase
 * ("P1W", "P1M", "P3M", "P6M", "P1Y", ...) into the form the screen wants to say out loud.
 * Returns null for anything it does not recognise, in which case the caller simply shows the
 * price without a period.
 *
 * The price and the period are configured in the Play Console, never in the app: changing the
 * subscription from monthly to yearly needs no app update.
 */
fun parseBillingPeriod(iso: String?): SubscriptionPeriod? {
    if (iso.isNullOrEmpty()) return null
    val match = ISO_PERIOD.matchEntire(iso) ?: return null
    fun group(i: Int) = match.groupValues[i].toIntOrNull() ?: 0

    val years = group(1)
    val months = group(2)
    val weeks = group(3)
    val days = group(4)

    val totalMonths = years * 12 + months
    return when {
        totalMonths > 0 && weeks == 0 && days == 0 ->
            if (totalMonths % 12 == 0) SubscriptionPeriod(totalMonths / 12, PeriodUnit.YEAR)
            else SubscriptionPeriod(totalMonths, PeriodUnit.MONTH)
        totalMonths == 0 && weeks > 0 && days == 0 -> SubscriptionPeriod(weeks, PeriodUnit.WEEK)
        totalMonths == 0 && weeks == 0 && days > 0 ->
            if (days % 7 == 0) SubscriptionPeriod(days / 7, PeriodUnit.WEEK)
            else SubscriptionPeriod(days, PeriodUnit.DAY)
        else -> null
    }
}
