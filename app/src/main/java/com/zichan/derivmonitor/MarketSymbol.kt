package com.zichan.derivmonitor

data class MarketSymbol(
    val code: String,
    val displayName: String,
    val category: String
)

/**
 * Only starter selections live here. The searchable market catalogue is loaded
 * from Deriv's public active_symbols endpoint; do not invent or hard-code
 * broker-specific symbol codes for indices.
 */
object DefaultSymbols {
    val list = listOf(
        MarketSymbol("CRASH50", "Crash 50 Index", "Derived"),
        MarketSymbol("frxXAUUSD", "Gold / USD (XAU/USD)", "Commodities")
    )

    // Used only to rank live API results in the search list.
    val popularDisplayNames = listOf(
        "Crash 50",
        "Boom 1000",
        "Volatility 75",
        "Volatility 100",
        "Volatility 50",
        "Volatility 25",
        "Volatility 10",
        "Jump 100",
        "Gold",
        "Silver",
        "Bitcoin",
        "Ethereum",
        "EUR/USD",
        "GBP/USD",
        "USD/JPY",
        "US 500",
        "S&P 500",
        "Wall Street 30",
        "US Tech 100",
        "NASDAQ",
        "Germany 40",
        "UK 100",
        "Japan 225"
    )
}
