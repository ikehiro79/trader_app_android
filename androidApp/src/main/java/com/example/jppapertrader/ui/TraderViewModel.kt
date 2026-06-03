package com.example.jppapertrader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jppapertrader.data.BacktestRun
import com.example.jppapertrader.data.ChatGptSettings
import com.example.jppapertrader.data.PortfolioSummary
import com.example.jppapertrader.data.ScoreItem
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
                    chatGptSettings = repository.settings(),
                    status = "完了",
                )
            }
        }
    }
}

data class TraderUiState(
    val portfolio: PortfolioSummary = PortfolioSummary(0.0, 0.0, 0.0, emptyList()),
    val watchlist: List<WatchItem> = emptyList(),
    val scores: List<ScoreItem> = emptyList(),
    val runs: List<BacktestRun> = emptyList(),
    val chatGptSettings: ChatGptSettings = ChatGptSettings("", "gpt-4.1-mini"),
    val apiKeyInput: String = "",
    val modelInput: String = "gpt-4.1-mini",
    val tradeCode: String = "7203",
    val tradeQuantity: String = "100",
    val tradePrice: String = "",
    val backtestStart: String = defaultStartDate(),
    val backtestEnd: String = defaultEndDate(),
    val message: String = "スタンドアロンデータはこの端末内に保存されます。",
    val status: String = "読み込み中",
)

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
