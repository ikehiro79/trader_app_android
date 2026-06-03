package com.example.jppapertrader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.jppapertrader.data.BacktestRun
import com.example.jppapertrader.data.PortfolioSummary
import com.example.jppapertrader.data.ScoreItem
import com.example.jppapertrader.data.WatchItem
import com.example.jppapertrader.data.number
import com.example.jppapertrader.data.percent
import com.example.jppapertrader.data.yen
import com.example.jppapertrader.ui.TraderUiState
import com.example.jppapertrader.ui.TraderViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: TraderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsState()
            TraderApp(state, viewModel)
        }
    }
}

@Composable
private fun TraderApp(state: TraderUiState, viewModel: TraderViewModel) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Header(state, viewModel)
                }
                item {
                    PortfolioCard(state.portfolio)
                }
                item {
                    ChatGptCard(state, viewModel)
                }
                item {
                    MarketCard(state, viewModel)
                }
                item {
                    TradingCard(state, viewModel)
                }
                item {
                    BacktestCard(state, viewModel)
                }
                item {
                    WatchlistCard(state.watchlist)
                }
            }
        }
    }
}

@Composable
private fun Header(state: TraderUiState, viewModel: TraderViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("JP Paper Trader", style = MaterialTheme.typography.headlineMedium)
        Text("Standalone Android app using Kotlin, Compose, Material3, MVVM, and Hilt.")
        Text("Status: ${state.status}", style = MaterialTheme.typography.labelLarge)
        Text(state.message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::refresh) { Text("Refresh") }
            TextButton(onClick = viewModel::reset) { Text("Reset") }
        }
    }
}

@Composable
private fun PortfolioCard(summary: PortfolioSummary) {
    AppCard("Portfolio") {
        Text("Cash: ${summary.cash.yen()}")
        Text("Market value: ${summary.marketValue.yen()}")
        Text("Total value: ${summary.totalValue.yen()}")
        Spacer(Modifier.height(8.dp))
        if (summary.positions.isEmpty()) {
            Text("No positions.")
        } else {
            summary.positions.forEach {
                Text("${it.code} qty ${it.quantity} avg ${it.averagePrice.number()} latest ${it.latestPrice.number()} value ${it.value.yen()}")
            }
        }
    }
}

@Composable
private fun ChatGptCard(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("ChatGPT API") {
        Text(state.chatGptSettings.maskedKey)
        OutlinedTextField(
            value = state.apiKeyInput,
            onValueChange = viewModel::updateApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenAI API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.modelInput,
            onValueChange = viewModel::updateModel,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::saveChatGptSettings) { Text("Save") }
            TextButton(onClick = viewModel::clearApiKey) { Text("Clear") }
        }
    }
}

@Composable
private fun MarketCard(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("Market Data") {
        Text("Local generated price history is bundled in the app.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::regeneratePrices) { Text("Regenerate") }
        }
        state.scores.take(8).forEach {
            ScoreRow(it)
        }
    }
}

@Composable
private fun ScoreRow(item: ScoreItem) {
    Text("${item.code} ${item.action} score ${item.score.percent()} latest ${item.latestPrice.number()}")
}

@Composable
private fun TradingCard(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("Manual Trade") {
        OutlinedTextField(
            value = state.tradeCode,
            onValueChange = viewModel::updateTradeCode,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Code") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.tradeQuantity,
            onValueChange = viewModel::updateTradeQuantity,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Quantity") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.tradePrice,
            onValueChange = viewModel::updateTradePrice,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Price") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::buyCheck) { Text("Check") }
            Button(onClick = viewModel::buy) { Text("Buy") }
            TextButton(onClick = viewModel::sell) { Text("Sell") }
        }
    }
}

@Composable
private fun BacktestCard(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("Backtest") {
        OutlinedTextField(
            value = state.backtestStart,
            onValueChange = viewModel::updateBacktestStart,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Start date") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.backtestEnd,
            onValueChange = viewModel::updateBacktestEnd,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("End date") },
            singleLine = true,
        )
        Button(onClick = viewModel::runBacktest) { Text("Run") }
        state.runs.forEach {
            BacktestRunRow(it)
        }
    }
}

@Composable
private fun BacktestRunRow(run: BacktestRun) {
    Text("#${run.id} ${run.startDate} to ${run.endDate}")
    Text("Value ${run.finalValue.yen()} return ${run.totalReturn.percent()} trades ${run.tradesCount}")
}

@Composable
private fun WatchlistCard(items: List<WatchItem>) {
    AppCard("Watchlist") {
        items.take(31).forEach {
            Text("${it.code} ${it.name} latest ${it.latestPrice.number()}")
        }
    }
}

@Composable
private fun AppCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
