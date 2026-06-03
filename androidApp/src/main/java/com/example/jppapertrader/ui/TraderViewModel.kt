package com.example.jppapertrader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jppapertrader.data.BacktestRun
import com.example.jppapertrader.data.BuyCheckHistoryItem
import com.example.jppapertrader.data.ChatGptSettings
import com.example.jppapertrader.data.PortfolioSummary
import com.example.jppapertrader.data.ScoreItem
import com.example.jppapertrader.data.SimulationRun
import com.example.jppapertrader.data.TradeHistoryItem
import com.example.jppapertrader.data.TraderRepository
import com.example.jppapertrader.data.WatchItem
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TraderViewModel @Inject constructor(
    private val repository: TraderRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TraderUiState())
    val uiState: StateFlow<TraderUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = repository.settings()
            _uiState.update {
                it.copy(
                    portfolio = repository.portfolio(),
                    watchlist = repository.watchlist(),
                    scores = repository.scores(),
                    runs = repository.backtestRuns(),
                    trades = repository.tradeHistory(),
                    buyChecks = repository.buyCheckHistory(),
                    simulations = repository.simulations(),
                    chatGptSettings = settings,
                    tradePrice = if (it.tradePrice.isBlank()) repository.latestPrice(it.tradeCode).toString() else it.tradePrice,
                    status = "準備完了",
                )
            }
        }
    }

    fun updateApiKey(value: String) = _uiState.update { it.copy(apiKeyInput = value) }
    fun updateModel(value: String) = _uiState.update { it.copy(modelInput = value) }
    fun updateTradeCode(value: String) = _uiState.update { it.copy(tradeCode = value) }
    fun updateTradeQuantity(value: String) = _uiState.update { it.copy(tradeQuantity = value) }
    fun updateTradePrice(value: String) = _uiState.update { it.copy(tradePrice = value) }
    fun updateBacktestStart(value: String) = _uiState.update { it.copy(backtestStart = value) }
    fun updateBacktestEnd(value: String) = _uiState.update { it.copy(backtestEnd = value) }
    fun updateWatchCode(value: String) = _uiState.update { it.copy(watchCode = value) }
    fun updateWatchName(value: String) = _uiState.update { it.copy(watchName = value) }
    fun updateTrainStart(value: String) = _uiState.update { it.copy(trainStart = value) }
    fun updateTrainEnd(value: String) = _uiState.update { it.copy(trainEnd = value) }
    fun updateTestStart(value: String) = _uiState.update { it.copy(testStart = value) }
    fun updateTestEnd(value: String) = _uiState.update { it.copy(testEnd = value) }
    fun updateObjective(value: String) = _uiState.update { it.copy(objective = value) }
    fun openScreen(screen: TraderScreen) = _uiState.update { it.copy(currentScreen = screen) }

    fun saveChatGptSettings() {
        val state = uiState.value
        repository.saveChatGptSettings(state.apiKeyInput, state.modelInput)
        _uiState.update {
            it.copy(
                apiKeyInput = "",
                chatGptSettings = repository.settings(),
                status = "ChatGPT設定を保存しました。",
            )
        }
    }

    fun clearApiKey() {
        repository.clearApiKey()
        _uiState.update { it.copy(apiKeyInput = "", chatGptSettings = repository.settings(), status = "ChatGPT APIキーを削除しました。") }
    }

    fun reset() {
        runAction { repository.reset() }
    }

    fun regeneratePrices() {
        runAction { repository.regeneratePrices() }
    }

    fun addWatchItem() {
        val state = uiState.value
        runAction { repository.addWatchItem(state.watchCode, state.watchName) }
    }

    fun buyCheck() {
        runTrade { code, quantity, price -> repository.buyCheck(code, quantity, price) }
    }

    fun buy() {
        runTrade { code, quantity, price -> repository.buy(code, quantity, price) }
    }

    fun sell() {
        runTrade { code, quantity, price -> repository.sell(code, quantity, price) }
    }

    fun runBacktest() {
        runAction { repository.runBacktest(uiState.value.backtestStart, uiState.value.backtestEnd) }
    }

    fun runTrainTestSimulation() {
        val state = uiState.value
        runAction {
            repository.runTrainTestSimulation(
                state.trainStart,
                state.trainEnd,
                state.testStart,
                state.testEnd,
                state.objective,
            )
        }
    }

    private fun runTrade(action: (String, Int, Double) -> String) {
        val state = uiState.value
        val quantity = state.tradeQuantity.toIntOrNull()
        val price = state.tradePrice.toDoubleOrNull()
        if (quantity == null || price == null) {
            _uiState.update { it.copy(message = "数量と価格は有効な数値を入力してください。", status = "入力エラー") }
            return
        }
        runAction { action(state.tradeCode, quantity, price) }
    }

    private fun runAction(action: () -> String) {
        _uiState.update { it.copy(status = "実行中...") }
        viewModelScope.launch(Dispatchers.IO) {
            val message = runCatching(action).getOrElse { it.message ?: it.toString() }
            _uiState.update {
                it.copy(
                    message = message,
                    portfolio = repository.portfolio(),
                    watchlist = repository.watchlist(),
                    scores = repository.scores(),
                    runs = repository.backtestRuns(),
                    trades = repository.tradeHistory(),
                    buyChecks = repository.buyCheckHistory(),
                    simulations = repository.simulations(),
                    chatGptSettings = repository.settings(),
                    status = "完了",
                )
            }
        }
    }
}

data class TraderUiState(
    val currentScreen: TraderScreen = TraderScreen.Menu,
    val portfolio: PortfolioSummary = PortfolioSummary(0.0, 0.0, 0.0, emptyList()),
    val watchlist: List<WatchItem> = emptyList(),
    val scores: List<ScoreItem> = emptyList(),
    val runs: List<BacktestRun> = emptyList(),
    val trades: List<TradeHistoryItem> = emptyList(),
    val buyChecks: List<BuyCheckHistoryItem> = emptyList(),
    val simulations: List<SimulationRun> = emptyList(),
    val chatGptSettings: ChatGptSettings = ChatGptSettings("", "gpt-4.1-mini"),
    val apiKeyInput: String = "",
    val modelInput: String = "gpt-4.1-mini",
    val watchCode: String = "",
    val watchName: String = "",
    val tradeCode: String = "7203",
    val tradeQuantity: String = "100",
    val tradePrice: String = "",
    val backtestStart: String = defaultStartDate(),
    val backtestEnd: String = defaultEndDate(),
    val trainStart: String = defaultTrainStartDate(),
    val trainEnd: String = defaultTrainEndDate(),
    val testStart: String = defaultStartDate(),
    val testEnd: String = defaultEndDate(),
    val objective: String = "balanced",
    val message: String = "スタンドアロンデータはこの端末内に保存されます。",
    val status: String = "読み込み中",
)

enum class TraderScreen(val title: String, val description: String) {
    Menu("メニュー", "画面を選択します。"),
    Watchlist("対象銘柄", "対象銘柄の確認、初期化、個別追加を行います。"),
    MarketSync("データ同期", "ローカル価格履歴の生成と最新価格を確認します。"),
    Signals("売買候補", "モメンタムスコア順に候補を確認します。"),
    TodayCandidates("本日の候補", "保存済み設定を参考に、本日の買い候補を確認します。"),
    ManualTrade("手動売買", "買い前チェック、買い、売り、保有状況を扱います。"),
    Backtest("バックテスト", "指定期間のバックテストを実行します。"),
    TrainTest("学習・検証", "学習期間と検証期間でパラメータ比較を保存します。"),
    SimulationHistory("シミュレーション履歴", "バックテストと学習・検証の履歴を確認します。"),
    History("履歴", "売買履歴、買い確認履歴、ログ相当の情報を確認します。"),
}

private fun defaultEndDate(): String {
    val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
}

private fun defaultStartDate(): String {
    val calendar = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -1)
        add(Calendar.MONTH, -1)
    }
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
}

private fun defaultTrainEndDate(): String {
    val calendar = Calendar.getInstance().apply {
        add(Calendar.MONTH, -2)
    }
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
}

private fun defaultTrainStartDate(): String {
    val calendar = Calendar.getInstance().apply {
        add(Calendar.MONTH, -14)
    }
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
}
