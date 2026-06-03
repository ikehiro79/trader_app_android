package com.example.jppapertrader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
        enableEdgeToEdge()
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
                    .windowInsetsPadding(WindowInsets.safeDrawing)
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
        Text("日本株ペーパートレーダー", style = MaterialTheme.typography.headlineMedium)
        Text("端末内データだけで動く、株式ペーパートレード用のAndroidアプリです。")
        Text("状態: ${state.status}", style = MaterialTheme.typography.labelLarge)
        Text(state.message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::refresh) { Text("更新") }
            TextButton(onClick = viewModel::reset) { Text("初期化") }
        }
    }
}

@Composable
private fun PortfolioCard(summary: PortfolioSummary) {
    AppCard("ポートフォリオ") {
        Text("現金: ${summary.cash.yen()}")
        Text("評価額: ${summary.marketValue.yen()}")
        Text("総資産: ${summary.totalValue.yen()}")
        Spacer(Modifier.height(8.dp))
        if (summary.positions.isEmpty()) {
            Text("保有銘柄はありません。")
        } else {
            summary.positions.forEach {
                Text("${it.code} 数量 ${it.quantity} 平均 ${it.averagePrice.number()} 最新 ${it.latestPrice.number()} 評価額 ${it.value.yen()}")
            }
        }
    }
}

@Composable
private fun ChatGptCard(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("ChatGPT API設定") {
        Text(state.chatGptSettings.maskedKey)
        OutlinedTextField(
            value = state.apiKeyInput,
            onValueChange = viewModel::updateApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenAI APIキー") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.modelInput,
            onValueChange = viewModel::updateModel,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("モデル") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::saveChatGptSettings) { Text("保存") }
            TextButton(onClick = viewModel::clearApiKey) { Text("削除") }
        }
    }
}

@Composable
private fun MarketCard(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("マーケットデータ") {
        Text("アプリ内で生成したローカル価格履歴を使用します。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::regeneratePrices) { Text("価格を再生成") }
        }
        state.scores.take(8).forEach {
            ScoreRow(it)
        }
    }
}

@Composable
private fun ScoreRow(item: ScoreItem) {
    Text("${item.code} ${item.action} スコア ${item.score.percent()} 最新 ${item.latestPrice.number()}")
}

@Composable
private fun TradingCard(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("手動売買") {
        OutlinedTextField(
            value = state.tradeCode,
            onValueChange = viewModel::updateTradeCode,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("銘柄コード") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.tradeQuantity,
            onValueChange = viewModel::updateTradeQuantity,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("数量") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.tradePrice,
            onValueChange = viewModel::updateTradePrice,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("価格") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::buyCheck) { Text("確認") }
            Button(onClick = viewModel::buy) { Text("買い") }
            TextButton(onClick = viewModel::sell) { Text("売り") }
        }
    }
}

@Composable
private fun BacktestCard(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("バックテスト") {
        OutlinedTextField(
            value = state.backtestStart,
            onValueChange = viewModel::updateBacktestStart,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("開始日") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.backtestEnd,
            onValueChange = viewModel::updateBacktestEnd,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("終了日") },
            singleLine = true,
        )
        Button(onClick = viewModel::runBacktest) { Text("実行") }
        state.runs.forEach {
            BacktestRunRow(it)
        }
    }
}

@Composable
private fun BacktestRunRow(run: BacktestRun) {
    Text("#${run.id} ${run.startDate} から ${run.endDate}")
    Text("最終資産 ${run.finalValue.yen()} リターン ${run.totalReturn.percent()} 売買回数 ${run.tradesCount}")
}

@Composable
private fun WatchlistCard(items: List<WatchItem>) {
    AppCard("ウォッチリスト") {
        items.take(31).forEach {
            Text("${it.code} ${it.name} 最新 ${it.latestPrice.number()}")
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
