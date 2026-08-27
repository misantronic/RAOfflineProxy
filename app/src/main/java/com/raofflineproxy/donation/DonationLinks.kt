package com.raofflineproxy.donation

data class DonationAmountOption(val label: String, val url: String, val amountCents: Int)

// The same Stripe Payment Links used on the docs donate page (docs/donate.md) and the Linux
// "Support me" QR code. Their custom "RA username" field on Stripe's own checkout page is what
// ties a monthly link back to an RA account server-side (see linkRaUsernameFromCheckoutSession
// in lambda/raop-support-payment), so reusing them here preserves that without any extra work.
object DonationLinks {
    val MONTHLY: List<DonationAmountOption> = listOf(
        DonationAmountOption("$1 / month", "https://buy.stripe.com/7sYcN7ghBd108DZ5xlbwk08", 100),
        DonationAmountOption("$3 / month", "https://buy.stripe.com/6oUaEZc1l6CC3jFe3Rbwk09", 300),
        DonationAmountOption("$5 / month", "https://buy.stripe.com/3cIeVf5CX4uubQb8Jxbwk0a", 500),
        DonationAmountOption("$8 / month", "https://buy.stripe.com/aFafZj1mH7GG8DZbVJbwk0b", 800),
        DonationAmountOption("$10 / month", "https://buy.stripe.com/6oU8wRc1lf989I31h5bwk0c", 1000),
        DonationAmountOption("$15 / month", "https://buy.stripe.com/5kQ3cxe9t2mm2fB0d1bwk0d", 1500)
    )

    val ONE_TIME: List<DonationAmountOption> = listOf(
        DonationAmountOption("$3", "https://buy.stripe.com/3cIfZj9Tdgdc07t6Bpbwk0g", 300),
        DonationAmountOption("$5", "https://buy.stripe.com/8x2eVf7L53qq4nJe3Rbwk0h", 500),
        DonationAmountOption("$8", "https://buy.stripe.com/dRmaEZ0iD8KK07te3Rbwk0i", 800),
        DonationAmountOption("$10", "https://buy.stripe.com/eVqbJ33uP0eeaM77Ftbwk0j", 1000),
        DonationAmountOption("$15", "https://buy.stripe.com/eVq14p7L55yy7zV2l9bwk0k", 1500)
    )

    // Indexes into MONTHLY/ONE_TIME that the amount dropdowns preselect.
    const val MONTHLY_DEFAULT_INDEX = 1 // "$3 / month"
    const val ONE_TIME_DEFAULT_INDEX = 1 // "$5"
}
