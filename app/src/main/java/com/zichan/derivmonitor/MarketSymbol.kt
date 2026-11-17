package com.zichan.derivmonitor

data class MarketSymbol(
    val code: String,
    val displayName: String,
    val category: String
)

data class PriceAlert(
    val symbol: String,
    val displayName: String,
    val targetPrice: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val triggered: Boolean = false
)

object DefaultSymbols {
    val list = listOf(
        MarketSymbol("CRASH50", "Crash 50 Index", "Derived"),
        MarketSymbol("frxXAUUSD", "Gold / USD", "Commodities"),
        MarketSymbol("frxEURUSD", "EUR/USD", "Forex"),
        MarketSymbol("frxGBPUSD", "GBP/USD", "Forex"),
        MarketSymbol("R_50", "Volatility 50 Index", "Derived"),
        MarketSymbol("R_100", "Volatility 100 Index", "Derived"),
        MarketSymbol("OTC_SPC", "US 500", "Stock Indices")
    )

    val popularDisplayNames = listOf(
        "Crash 50", "Boom 1000", "Volatility 75", "Volatility 100",
        "Volatility 50", "Volatility 25", "Volatility 10", "Jump 100",
        "Gold", "Silver", "Bitcoin", "Ethereum", "EUR/USD", "GBP/USD",
        "USD/JPY", "US 500", "S&P 500", "Wall Street 30", "US Tech 100",
        "NASDAQ", "Germany 40", "UK 100", "Japan 225"
    )
}
