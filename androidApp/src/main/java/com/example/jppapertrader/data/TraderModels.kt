package com.example.jppapertrader.data

data class WatchItem(
    val code: String,
    val name: String,
    val latestPrice: Double,
)

data class PositionItem(
    val code: String,
    val quantity: Int,
    val averagePrice: Double,
    val latestPrice: Double,
    val value: Double,
)

data class PortfolioSummary(
    val cash: Double,
    val marketValue: Double,
    val totalValue: Double,
    val positions: List<PositionItem>,
)

data class ScoreItem(
    val code: String,
    val action: String,
    val score: Double,
    val latestPrice: Double,
)

data class BacktestRun(
    val id: Long,
    val startDate: String,
    val endDate: String,
    val finalValue: Double,
    val totalReturn: Double,
    val tradesCount: Int,
)
