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
        db.execSQL("CREATE TABLE buy_checks(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, quantity INTEGER NOT NULL, price REAL NOT NULL, result TEXT NOT NULL, checked_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE simulations(id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL, objective TEXT NOT NULL, train_start TEXT NOT NULL, train_end TEXT NOT NULL, test_start TEXT NOT NULL, test_end TEXT NOT NULL, baseline_return REAL NOT NULL, optimized_return REAL NOT NULL, chatgpt_return REAL NOT NULL)")
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS backtest_runs")
        db.execSQL("DROP TABLE IF EXISTS simulations")
        db.execSQL("DROP TABLE IF EXISTS buy_checks")
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
            execSQL("DELETE FROM simulations")
            execSQL("DELETE FROM buy_checks")
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
                    name = item.name,
                    action = when {
                    score > 0.03 -> "買い候補"
                    score < -0.03 -> "見送り"
                    else -> "様子見"
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
        return "${TOPIX_CORE30.size}銘柄分のローカル価格履歴を生成しました。"
    }

    fun latestPrice(code: String): Double = latestPrice(readableDatabase, code)

    fun buyCheck(code: String, quantity: Int, price: Double): String {
        val db = readableDatabase
        val normalizedCode = code.trim()
        val cost = quantity * price
        val result = when {
            normalizedCode.isEmpty() -> "不可: 銘柄コードを入力してください。"
            !exists(db, normalizedCode) -> "要確認: 有効なウォッチリストにない銘柄です。"
            quantity <= 0 || price <= 0.0 -> "不可: 数量と価格は正の数で入力してください。"
            else -> null
        }
        if (result != null) {
            insertBuyCheck(writableDatabase, normalizedCode.ifBlank { "-" }, quantity, price, result)
            return result
        }
        val cash = cashBalance(db)
        if (cost > cash) {
            val message = "不可: 現金が不足しています。必要額 ${cost.yen()}、現金 ${cash.yen()}。"
            insertBuyCheck(writableDatabase, normalizedCode, quantity, price, message)
            return message
        }
        val old = priceDaysAgo(db, normalizedCode, 20)
        val score = if (old <= 0.0) 0.0 else price / old - 1.0
        val message = if (score < -0.05) {
            "要確認: 20日モメンタムが弱いです。スコア ${score.percent()}。"
        } else {
            "許可: 概算購入額 ${cost.yen()}、モメンタムスコア ${score.percent()}。"
        }
        insertBuyCheck(writableDatabase, normalizedCode, quantity, price, message)
        return message
    }

    fun buy(code: String, quantity: Int, price: Double): String {
        val check = buyCheck(code, quantity, price)
        if (check.startsWith("不可")) return check
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
        return "$normalizedCode を ${price.number()}円で $quantity 株買いました。"
    }

    fun sell(code: String, quantity: Int, price: Double): String {
        val normalizedCode = code.trim()
        var message = ""
        writableDatabase.transaction {
            rawQuery("SELECT quantity FROM positions WHERE code = ?", arrayOf(normalizedCode)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    message = "不可: $normalizedCode の保有がありません。"
                    return@transaction
                }
                val oldQty = cursor.getInt(0)
                if (quantity <= 0 || quantity > oldQty) {
                    message = "不可: 売却数量は1から$oldQty の範囲で入力してください。"
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
            message = "$normalizedCode を ${price.number()}円で $quantity 株売りました。"
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
        return "最終資産: ${cash.yen()}\n総リターン: ${totalReturn.percent()}\n売買回数: $trades"
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

    fun tradeHistory(): List<TradeHistoryItem> {
        val rows = mutableListOf<TradeHistoryItem>()
        readableDatabase.rawQuery(
            "SELECT id, code, side, quantity, price, traded_at FROM trades ORDER BY id DESC LIMIT 100",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += TradeHistoryItem(
                    id = cursor.getLong(0),
                    code = cursor.getString(1),
                    side = if (cursor.getString(2) == "BUY") "買い" else "売り",
                    quantity = cursor.getInt(3),
                    price = cursor.getDouble(4),
                    tradedAt = cursor.getString(5),
                )
            }
        }
        return rows
    }

    fun buyCheckHistory(): List<BuyCheckHistoryItem> {
        val rows = mutableListOf<BuyCheckHistoryItem>()
        readableDatabase.rawQuery(
            "SELECT id, code, quantity, price, result, checked_at FROM buy_checks ORDER BY id DESC LIMIT 100",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += BuyCheckHistoryItem(
                    id = cursor.getLong(0),
                    code = cursor.getString(1),
                    quantity = cursor.getInt(2),
                    price = cursor.getDouble(3),
                    result = cursor.getString(4),
                    checkedAt = cursor.getString(5),
                )
            }
        }
        return rows
    }

    fun addWatchItem(code: String, name: String): String {
        val normalizedCode = code.trim()
        if (normalizedCode.isEmpty()) return "銘柄コードを入力してください。"
        writableDatabase.transaction {
            insertWithOnConflict(
                "watchlist",
                null,
                contentValuesOf("code" to normalizedCode, "name" to name.ifBlank { normalizedCode }, "active" to 1),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            if (latestPrice(this, normalizedCode) <= 0.0) {
                seedOnePrice(this, normalizedCode)
            }
        }
        return "$normalizedCode を対象銘柄に追加しました。"
    }

    fun runTrainTestSimulation(trainStart: String, trainEnd: String, testStart: String, testEnd: String, objective: String): String {
        val baseline = estimateReturn(testStart, testEnd, 0.005)
        val optimized = estimateReturn(testStart, testEnd, 0.015)
        val chatgpt = estimateReturn(testStart, testEnd, 0.010)
        writableDatabase.insert(
            "simulations",
            null,
            contentValuesOf(
                "created_at" to now(),
                "objective" to objective,
                "train_start" to trainStart,
                "train_end" to trainEnd,
                "test_start" to testStart,
                "test_end" to testEnd,
                "baseline_return" to baseline,
                "optimized_return" to optimized,
                "chatgpt_return" to chatgpt,
            ),
        )
        return "学習・検証を保存しました。\nベースライン: ${baseline.percent()}\n最適化: ${optimized.percent()}\nChatGPT調整: ${chatgpt.percent()}"
    }

    fun simulations(): List<SimulationRun> {
        val rows = mutableListOf<SimulationRun>()
        readableDatabase.rawQuery(
            "SELECT id, created_at, objective, train_start, train_end, test_start, test_end, baseline_return, optimized_return, chatgpt_return FROM simulations ORDER BY id DESC LIMIT 50",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += SimulationRun(
                    id = cursor.getLong(0),
                    createdAt = cursor.getString(1),
                    objective = cursor.getString(2),
                    trainStart = cursor.getString(3),
                    trainEnd = cursor.getString(4),
                    testStart = cursor.getString(5),
                    testEnd = cursor.getString(6),
                    baselineReturn = cursor.getDouble(7),
                    optimizedReturn = cursor.getDouble(8),
                    chatGptReturn = cursor.getDouble(9),
                )
            }
        }
        return rows
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

    private fun seedOnePrice(db: SQLiteDatabase, code: String) {
        var price = 900.0 + abs(code.hashCode() % 7000)
        val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -180) }
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

    private fun insertBuyCheck(db: SQLiteDatabase, code: String, quantity: Int, price: Double, result: String) {
        db.insert("buy_checks", null, contentValuesOf("code" to code, "quantity" to quantity, "price" to price, "result" to result, "checked_at" to now()))
    }

    private fun estimateReturn(startDate: String, endDate: String, threshold: Double): Double {
        val db = readableDatabase
        val returns = mutableListOf<Double>()
        db.rawQuery("SELECT code FROM watchlist WHERE active = 1 ORDER BY code", null).use { cursor ->
            while (cursor.moveToNext()) {
                val code = cursor.getString(0)
                val start = firstPriceOnOrAfter(db, code, startDate)
                val end = lastPriceOnOrBefore(db, code, endDate)
                val base = priceDaysAgo(db, code, 20)
                if (start > 0.0 && end > 0.0 && base > 0.0 && start / base - 1.0 > threshold) {
                    returns += end / start - 1.0
                }
            }
        }
        return if (returns.isEmpty()) 0.0 else returns.average()
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
        private const val DB_VERSION = 2
        private const val STARTING_CASH = 1_000_000.0
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private val TOPIX_CORE30 = listOf(
            "2914" to "日本たばこ産業",
            "3382" to "セブン＆アイ・ホールディングス",
            "4063" to "信越化学工業",
            "4452" to "花王",
            "4502" to "武田薬品工業",
            "4503" to "アステラス製薬",
            "4568" to "第一三共",
            "6098" to "リクルートホールディングス",
            "6501" to "日立製作所",
            "6758" to "ソニーグループ",
            "6861" to "キーエンス",
            "6954" to "ファナック",
            "6981" to "村田製作所",
            "7011" to "三菱重工業",
            "7203" to "トヨタ自動車",
            "7267" to "本田技研工業",
            "7751" to "キヤノン",
            "7974" to "任天堂",
            "8031" to "三井物産",
            "8035" to "東京エレクトロン",
            "8058" to "三菱商事",
            "8306" to "三菱UFJフィナンシャル・グループ",
            "8316" to "三井住友フィナンシャルグループ",
            "8411" to "みずほフィナンシャルグループ",
            "8766" to "東京海上ホールディングス",
            "8802" to "三菱地所",
            "9020" to "東日本旅客鉄道",
            "9022" to "東海旅客鉄道",
            "9432" to "NTT",
            "9433" to "KDDI",
            "9984" to "ソフトバンクグループ",
        )
    }
}

fun Double.yen(): String = java.text.NumberFormat.getCurrencyInstance(Locale.JAPAN).format(this)
fun Double.number(): String = java.text.NumberFormat.getNumberInstance(Locale.JAPAN).format(this)
fun Double.percent(): String = String.format(Locale.US, "%.2f%%", this * 100.0)
