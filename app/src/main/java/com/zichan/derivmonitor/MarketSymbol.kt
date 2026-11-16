package com.zichan.derivmonitor

data class MarketSymbol(
    val code: String,
    val displayName: String,
    val category: String
)

object DefaultSymbols {
    val list = listOf(
        MarketSymbol("CRASH50", "Crash 50 Index", "Crash/Boom"),
        MarketSymbol("CRASH300N", "Crash 300 Index", "Crash/Boom"),
        MarketSymbol("CRASH500", "Crash 500 Index", "Crash/Boom"),
        MarketSymbol("CRASH1000", "Crash 1000 Index", "Crash/Boom"),
        MarketSymbol("BOOM300N", "Boom 300 Index", "Crash/Boom"),
        MarketSymbol("BOOM500", "Boom 500 Index", "Crash/Boom"),
        MarketSymbol("BOOM1000", "Boom 1000 Index", "Crash/Boom"),
        MarketSymbol("1HZ10V", "Volatility 10 (1s) Index", "Volatility"),
        MarketSymbol("R_10", "Volatility 10 Index", "Volatility"),
        MarketSymbol("1HZ25V", "Volatility 25 (1s) Index", "Volatility"),
        MarketSymbol("R_25", "Volatility 25 Index", "Volatility"),
        MarketSymbol("1HZ50V", "Volatility 50 (1s) Index", "Volatility"),
        MarketSymbol("R_50", "Volatility 50 Index", "Volatility"),
        MarketSymbol("1HZ75V", "Volatility 75 (1s) Index", "Volatility"),
        MarketSymbol("R_75", "Volatility 75 Index", "Volatility"),
        MarketSymbol("1HZ100V", "Volatility 100 (1s) Index", "Volatility"),
        MarketSymbol("R_100", "Volatility 100 Index", "Volatility"),
        MarketSymbol("stpRNG", "Step Index", "Synthetic"),
        MarketSymbol("JD10", "Jump 10 Index", "Jump Index"),
        MarketSymbol("JD25", "Jump 25 Index", "Jump Index"),
        MarketSymbol("JD50", "Jump 50 Index", "Jump Index"),
        MarketSymbol("JD75", "Jump 75 Index", "Jump Index"),
        MarketSymbol("JD100", "Jump 100 Index", "Jump Index"),
        MarketSymbol("frxXAUUSD", "Gold / USD (XAU/USD)", "Commodities"),
        MarketSymbol("frxXAGUSD", "Silver / USD (XAG/USD)", "Commodities"),
        MarketSymbol("cryBTCUSD", "Bitcoin / USD (BTC)", "Crypto"),
        MarketSymbol("cryETHUSD", "Ethereum / USD (ETH)", "Crypto"),
        MarketSymbol("frxEURUSD", "EUR / USD", "Forex"),
        MarketSymbol("frxGBPUSD", "GBP / USD", "Forex"),
        MarketSymbol("frxUSDJPY", "USD / JPY", "Forex"),
        MarketSymbol("frxAUDUSD", "AUD / USD", "Forex"),
        MarketSymbol("frxUSDCAD", "USD / CAD", "Forex"),
        MarketSymbol("OTC_DJI", "Wall Street 30", "Stock Indices"),
        MarketSymbol("OTC_NDX", "US Tech 100 (NASDAQ)", "Stock Indices"),
        MarketSymbol("OTC_SPC", "US 500 (S&P 500)", "Stock Indices")
    )
}
