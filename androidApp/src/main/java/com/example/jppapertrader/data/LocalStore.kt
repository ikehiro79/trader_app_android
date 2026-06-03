package com.example.jppapertrader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

@Singleton
class LocalStore @Inject constructor(
    @ApplicationContext context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE watchlist(code TEXT PRIMARY KEY, name TEXT NOT NULL, active INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE prices(code TEXT NOT NULL, trade_date TEXT NOT NULL, close_price REAL NOT NULL, PRIMARY KEY(code, trade_date))")
        db.execSQL("CREATE TABLE positions(code TEXT PRIMARY KEY, quantity INTEGER NOT NULL, avg_price REAL NOT NULL)")
        db.execSQL("CREATE TABLE trades(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, side TEXT NOT NULL, quantity INTEGER NOT NULL, price REAL NOT NULL, traded_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE backtest_runs(id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT NOT NULL, start_date TEXT NOT NULL, end_date TEXT NOT NULL, final_value REAL NOT NULL, total_return REAL NOT NULL, trades_count INTEGER NOT NULL)")
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS backtest_runs")
        db.execSQL("DROP TABLE IF EXISTS trades")
        db.execSQL("DROP TABLE IF EXISTS positions")
        db.execSQL("DROP TABLE IF EXISTS prices")
        db.execSQL("DROP TABLE IF EXISTS watchlist")
        onCreate(db)
    }

    fun resetSeed() {
        writableDatabase.transaction {
            execSQL("DELETE FROM watchlist")
            execSQL("DELETE FROM prices")
            execSQL("DELETE FROM positions")
            execSQL("DELETE FROM trades")
            execSQL("DELETE FROM backtest_runs")
            seed(this)
        }
    }

    fun portfolioSummary(): PortfolioSummary {
        val db = readableDatabase
        val cash = cashBalance(db)
        val positions = mutableListOf<PositionItem>()
        db.rawQuery("SELECT code, quantity, avg_price FROM positions ORDER BY code", null).use { cursor ->
            while (cursor.moveToNext()) {
                val code = cursor.getString(0)
                val quantity = cursor.getInt(1)
                val average = cursor.getDouble(2)
                val latest = latestPrice(db, code)
                positions += PositionItem(
                    code = code,
                    quantity = quantity,
                    averagePrice = average,
                    latestPrice = latest,
                    value = latest * quantity,
                )
            }
        }
        val marketValue = positions.sumOf { it.value }
        return PortfolioSummary(
            cash = cash,
            marketValue = marketValue,
            totalValue = cash + marketValue,
            positions = positions,
        )
    }

    fun watchlist(): List<WatchItem> {
        val db = readableDatabase
        val items = mutableListOf<WatchItem>()
        db.rawQuery("SELECT code, name FROM watchlist WHERE active = 1 ORDER BY code", null).use { cursor ->
            while (cursor.moveToNext()) {
                val code = cursor.getString(0)
                items += WatchItem(
                    code = code,
                    name = cursor.getString(1),
                    latestPrice = latestPrice(db, code),
                )
            }
        }
        return items
    }

    fun scores(): List<ScoreItem> {
        val db = readableDatabase
        return watchlist().map { item ->
            val old = priceDaysAgo(db, item.code, 20)
            val score = if (old <= 0.0) 0.0 else item.latestPrice / old - 1.0
            ScoreItem(
                code = item.code,
                action = when {
                    score > 0.03 -> "BUY"
                    score < -0.03 -> "AVOID"
                    else -> "HOLD"
                },
                score = score,
                latestPrice = item.latestPrice,
            )
        }
    }

    fun regeneratePrices(): String {
        writableDatabase.transaction {
            execSQL("DELETE FROM prices")
            seedPrices(this)
        }
        return "Generated local price history for ${TOPIX_CORE30.size} symbols."
    }

    fun latestPrice(code: String): Double = latestPrice(readableDatabase, code)

    fun buyCheck(code: String, quantity: Int, price: Double): String {
        val db = readableDatabase
        val normalizedCode = code.trim()
        val cost = quantity * price
        if (normalizedCode.isEmpty()) return "BLOCK: code is required."
        if (!exists(db, normalizedCode)) return "REVIEW: code is not in the active watchlist."
        if (quantity <= 0 || price <= 0.0) return "BLOCK: quantity and price must be positive."
        val cash = cashBalance(db)
        if (cost > cash) return "BLOCK: not enough cash. Needed ${cost.yen()}, cash ${cash.yen()}."
        val old = priceDaysAgo(db, normalizedCode, 20)
        val score = if (old <= 0.0) 0.0 else price / old - 1.0
        if (score < -0.05) return "REVIEW: weak 20-day momentum. Score ${score.percent()}."
        return "ALLOW: estimated cost ${cost.yen()}, momentum score ${score.percent()}."
    }

    fun buy(code: String, quantity: Int, price: Double): String {
        val check = buyCheck(code, quantity, price)
        if (check.startsWith("BLOCK")) return check
        val normalizedCode = code.trim()
        writableDatabase.transaction {
            rawQuery("SELECT quantity, avg_price FROM positions WHERE code = ?", arrayOf(normalizedCode)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val oldQty = cursor.getInt(0)
                    val oldAvg = cursor.getDouble(1)
                    val newQty = oldQty + quantity
                    val newAvg = ((oldQty * oldAvg) + (quantity * price)) / newQty
                    update("positions", contentValuesOf("quantity" to newQty, "avg_price" to newAvg), "code = ?", arrayOf(normalizedCode))
                } else {
                    insert("positions", null, contentValuesOf("code" to normalizedCode, "quantity" to quantity, "avg_price" to price))
                }
            }
            insertTrade(this, normalizedCode, "BUY", quantity, price)
            upsertLatestPrice(this, normalizedCode, price)
        }
        return "Bought $quantity shares of $normalizedCode at ${price.number()}."
    }

    fun sell(code: String, quantity: Int, price: Double): String {
        val normalizedCode = code.trim()
        var message = ""
        writableDatabase.transaction {
            rawQuery("SELECT quantity FROM positions WHERE code = ?", arrayOf(normalizedCode)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    message = "BLOCK: no position for $normalizedCode."
                    return@transaction
                }
                val oldQty = cursor.getInt(0)
                if (quantity <= 0 || quantity > oldQty) {
                    message = "BLOCK: sell quantity must be between 1 and $oldQty."
                    return@transaction
                }
                if (quantity == oldQty) {
                    delete("positions", "code = ?", arrayOf(normalizedCode))
                } else {
                    update("positions", contentValuesOf("quantity" to oldQty - quantity), "code = ?", arrayOf(normalizedCode))
                }
            }
            insertTrade(this, normalizedCode, "SELL", quantity, price)
            upsertLatestPrice(this, normalizedCode, price)
            message = "Sold $quantity shares of $normalizedCode at ${price.number()}."
        }
        return message
    }

    fun runBacktest(startDate: String, endDate: String): String {
        val db = writableDatabase
        var cash = STARTING_CASH
        var trades = 0
        db.rawQuery("SELECT code FROM watchlist WHERE active = 1 ORDER BY code", null).use { cursor ->
            while (cursor.moveToNext()) {
                val code = cursor.getString(0)
                val start = firstPriceOnOrAfter(db, code, startDate)
                val end = lastPriceOnOrBefore(db, code, endDate)
                val momentumBase = priceDaysAgo(db, code, 20)
                if (start <= 0.0 || end <= 0.0 || momentumBase <= 0.0) continue
                val momentum = start / momentumBase - 1.0
                if (momentum > 0.01 && cash > 50_000.0) {
                    val allocation = min(100_000.0, cash * 0.20)
                    val qty = (allocation / start).toInt()
                    if (qty > 0) {
                        cash -= qty * start
                        cash += qty * end
                        trades += 2
                    }
                }
            }
        }
        val totalReturn = cash / STARTING_CASH - 1.0
        db.insert("backtest_runs", null, contentValuesOf(
            "started_at" to now(),
            "start_date" to startDate,
            "end_date" to endDate,
            "final_value" to cash,
            "total_return" to totalReturn,
            "trades_count" to trades,
        ))
        return "Final value: ${cash.yen()}\nTotal return: ${totalReturn.percent()}\nTrades: $trades"
    }

    fun backtestRuns(): List<BacktestRun> {
        val runs = mutableListOf<BacktestRun>()
        readableDatabase.rawQuery(
            "SELECT id, start_date, end_date, final_value, total_return, trades_count FROM backtest_runs ORDER BY id DESC LIMIT 10",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runs += BacktestRun(
                    id = cursor.getLong(0),
                    startDate = cursor.getString(1),
                    endDate = cursor.getString(2),
                    finalValue = cursor.getDouble(3),
                    totalReturn = cursor.getDouble(4),
                    tradesCount = cursor.getInt(5),
                )
            }
        }
        return runs
    }

    private fun seed(db: SQLiteDatabase) {
        TOPIX_CORE30.forEach { row ->
            db.insertWithOnConflict(
                "watchlist",
                null,
                contentValuesOf("code" to row.first, "name" to row.second, "active" to 1),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        seedPrices(db)
    }

    private fun seedPrices(db: SQLiteDatabase) {
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -180) }
        TOPIX_CORE30.forEach { row ->
            val code = row.first
            var price = 900.0 + abs(code.hashCode() % 7000)
            val day = calendar.clone() as Calendar
            repeat(181) { i ->
                val wave = sin((i + abs(code.hashCode() % 31)) / 9.0) * 0.012
                val drift = (abs(code.hashCode()) % 17 - 8) / 10000.0
                price = max(100.0, price * (1.0 + wave + drift))
                db.insertWithOnConflict(
                    "prices",
                    null,
                    contentValuesOf(
                        "code" to code,
                        "trade_date" to dateFormat.format(day.time),
                        "close_price" to round(price * 10.0) / 10.0,
                    ),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                day.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
    }

    private fun cashBalance(db: SQLiteDatabase): Double {
        var cash = STARTING_CASH
        db.rawQuery("SELECT side, quantity, price FROM trades", null).use { cursor ->
            while (cursor.moveToNext()) {
                val amount = cursor.getInt(1) * cursor.getDouble(2)
                cash += if (cursor.getString(0) == "SELL") amount else -amount
            }
        }
        return cash
    }

    private fun exists(db: SQLiteDatabase, code: String): Boolean =
        db.rawQuery("SELECT 1 FROM watchlist WHERE code = ? AND active = 1", arrayOf(code)).use { it.moveToFirst() }

    private fun latestPrice(db: SQLiteDatabase, code: String): Double =
        db.rawQuery("SELECT close_price FROM prices WHERE code = ? ORDER BY trade_date DESC LIMIT 1", arrayOf(code)).use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }

    private fun priceDaysAgo(db: SQLiteDatabase, code: String, days: Int): Double =
        db.rawQuery("SELECT close_price FROM prices WHERE code = ? ORDER BY trade_date DESC LIMIT 1 OFFSET $days", arrayOf(code)).use {
            if (it.moveToFirst()) it.getDouble(0) else latestPrice(db, code)
        }

    private fun firstPriceOnOrAfter(db: SQLiteDatabase, code: String, date: String): Double =
        db.rawQuery("SELECT close_price FROM prices WHERE code = ? AND trade_date >= ? ORDER BY trade_date ASC LIMIT 1", arrayOf(code, date)).use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }

    private fun lastPriceOnOrBefore(db: SQLiteDatabase, code: String, date: String): Double =
        db.rawQuery("SELECT close_price FROM prices WHERE code = ? AND trade_date <= ? ORDER BY trade_date DESC LIMIT 1", arrayOf(code, date)).use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }

    private fun insertTrade(db: SQLiteDatabase, code: String, side: String, quantity: Int, price: Double) {
        db.insert("trades", null, contentValuesOf("code" to code, "side" to side, "quantity" to quantity, "price" to price, "traded_at" to now()))
    }

    private fun upsertLatestPrice(db: SQLiteDatabase, code: String, price: Double) {
        db.insertWithOnConflict("prices", null, contentValuesOf("code" to code, "trade_date" to today(), "close_price" to price), SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun today(): String = dateFormat.format(Calendar.getInstance().time)
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Calendar.getInstance().time)

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun contentValuesOf(vararg pairs: Pair<String, Any?>): ContentValues =
        ContentValues().apply {
            pairs.forEach { (key, value) ->
                when (value) {
                    null -> putNull(key)
                    is String -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Double -> put(key, value)
                    is Float -> put(key, value)
                    is Boolean -> put(key, if (value) 1 else 0)
                    else -> put(key, value.toString())
                }
            }
        }

    companion object {
        private const val DB_NAME = "jp_paper_trader.db"
        private const val DB_VERSION = 1
        private const val STARTING_CASH = 1_000_000.0
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private val TOPIX_CORE30 = listOf(
            "2914" to "Japan Tobacco Inc.",
            "3382" to "Seven & i Holdings Co., Ltd.",
            "4063" to "Shin-Etsu Chemical Co., Ltd.",
            "4452" to "Kao Corporation",
            "4502" to "Takeda Pharmaceutical Company Limited",
            "4503" to "Astellas Pharma Inc.",
            "4568" to "Daiichi Sankyo Company, Limited",
            "6098" to "Recruit Holdings Co., Ltd.",
            "6501" to "Hitachi, Ltd.",
            "6758" to "Sony Group Corporation",
            "6861" to "Keyence Corporation",
            "6954" to "Fanuc Corporation",
            "6981" to "Murata Manufacturing Co., Ltd.",
            "7011" to "Mitsubishi Heavy Industries, Ltd.",
            "7203" to "Toyota Motor Corporation",
            "7267" to "Honda Motor Co., Ltd.",
            "7751" to "Canon Inc.",
            "7974" to "Nintendo Co., Ltd.",
            "8031" to "Mitsui & Co., Ltd.",
            "8035" to "Tokyo Electron Limited",
            "8058" to "Mitsubishi Corporation",
            "8306" to "Mitsubishi UFJ Financial Group, Inc.",
            "8316" to "Sumitomo Mitsui Financial Group, Inc.",
            "8411" to "Mizuho Financial Group, Inc.",
            "8766" to "Tokio Marine Holdings, Inc.",
            "8802" to "Mitsubishi Estate Co., Ltd.",
            "9020" to "East Japan Railway Company",
            "9022" to "Central Japan Railway Company",
            "9432" to "NTT, Inc.",
            "9433" to "KDDI Corporation",
            "9984" to "SoftBank Group Corp.",
        )
    }
}

fun Double.yen(): String = java.text.NumberFormat.getCurrencyInstance(Locale.JAPAN).format(this)
fun Double.number(): String = java.text.NumberFormat.getNumberInstance(Locale.JAPAN).format(this)
fun Double.percent(): String = String.format(Locale.US, "%.2f%%", this * 100.0)
