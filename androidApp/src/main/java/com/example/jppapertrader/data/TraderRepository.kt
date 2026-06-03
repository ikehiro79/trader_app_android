package com.example.jppapertrader.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraderRepository @Inject constructor(
    private val store: LocalStore,
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("jp_paper_trader_settings", Context.MODE_PRIVATE)

    fun portfolio(): PortfolioSummary = store.portfolioSummary()
    fun watchlist(): List<WatchItem> = store.watchlist()
    fun scores(): List<ScoreItem> = store.scores()
    fun backtestRuns(): List<BacktestRun> = store.backtestRuns()
    fun regeneratePrices(): String = store.regeneratePrices()
    fun reset(): String {
        store.resetSeed()
        return "Local database reset with TOPIX Core30 seed data."
    }

    fun latestPrice(code: String): Double = store.latestPrice(code)
    fun buyCheck(code: String, quantity: Int, price: Double): String = store.buyCheck(code, quantity, price)
    fun buy(code: String, quantity: Int, price: Double): String = store.buy(code, quantity, price)
    fun sell(code: String, quantity: Int, price: Double): String = store.sell(code, quantity, price)
    fun runBacktest(startDate: String, endDate: String): String = store.runBacktest(startDate, endDate)

    fun settings(): ChatGptSettings = ChatGptSettings(
        apiKey = preferences.getString(KEY_OPENAI_API_KEY, "").orEmpty(),
        model = preferences.getString(KEY_OPENAI_MODEL, "gpt-4.1-mini").orEmpty(),
    )

    fun saveChatGptSettings(apiKey: String, model: String) {
        preferences.edit().apply {
            if (apiKey.isNotBlank()) putString(KEY_OPENAI_API_KEY, apiKey.trim())
            putString(KEY_OPENAI_MODEL, model.ifBlank { "gpt-4.1-mini" })
        }.apply()
    }

    fun clearApiKey() {
        preferences.edit().remove(KEY_OPENAI_API_KEY).apply()
    }

    companion object {
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
    }
}

data class ChatGptSettings(
    val apiKey: String,
    val model: String,
) {
    val maskedKey: String
        get() = when {
            apiKey.isBlank() -> "No API key saved."
            apiKey.length < 12 -> "Saved key: saved"
            else -> "Saved key: ${apiKey.take(7)}...${apiKey.takeLast(4)}"
        }
}
