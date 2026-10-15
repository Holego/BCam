package io.github.holego.bcam.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionPeriodTest {

    @Test
    fun standardPlayPeriods() {
        assertEquals(SubscriptionPeriod(1, PeriodUnit.WEEK), parseBillingPeriod("P1W"))
        assertEquals(SubscriptionPeriod(1, PeriodUnit.MONTH), parseBillingPeriod("P1M"))
        assertEquals(SubscriptionPeriod(3, PeriodUnit.MONTH), parseBillingPeriod("P3M"))
        assertEquals(SubscriptionPeriod(6, PeriodUnit.MONTH), parseBillingPeriod("P6M"))
        assertEquals(SubscriptionPeriod(1, PeriodUnit.YEAR), parseBillingPeriod("P1Y"))
    }

    @Test
    fun twelveMonthsIsAYear() {
        assertEquals(SubscriptionPeriod(1, PeriodUnit.YEAR), parseBillingPeriod("P12M"))
        assertEquals(SubscriptionPeriod(2, PeriodUnit.YEAR), parseBillingPeriod("P24M"))
    }

    @Test
    fun mixedYearsAndMonthsCollapseToMonths() {
        assertEquals(SubscriptionPeriod(18, PeriodUnit.MONTH), parseBillingPeriod("P1Y6M"))
    }

    @Test
    fun daysThatFillWholeWeeksBecomeWeeks() {
        assertEquals(SubscriptionPeriod(1, PeriodUnit.WEEK), parseBillingPeriod("P7D"))
        assertEquals(SubscriptionPeriod(2, PeriodUnit.WEEK), parseBillingPeriod("P14D"))
        assertEquals(SubscriptionPeriod(3, PeriodUnit.DAY), parseBillingPeriod("P3D"))
    }

    @Test
    fun unrecognisedInputGivesNull() {
        assertNull(parseBillingPeriod(null))
        assertNull(parseBillingPeriod(""))
        assertNull(parseBillingPeriod("monthly"))
        assertNull(parseBillingPeriod("P"))
        assertNull(parseBillingPeriod("P1M1W"))
        assertNull(parseBillingPeriod("PT5M"))
    }
}
