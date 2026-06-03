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
import com.example.jppapertrader.data.BuyCheckHistoryItem
import com.example.jppapertrader.data.PortfolioSummary
import com.example.jppapertrader.data.ScoreItem
import com.example.jppapertrader.data.SimulationRun
import com.example.jppapertrader.data.TradeHistoryItem
import com.example.jppapertrader.data.WatchItem
import com.example.jppapertrader.data.number
import com.example.jppapertrader.data.percent
import com.example.jppapertrader.data.yen
import com.example.jppapertrader.ui.TraderScreen
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
                when (state.currentScreen) {
                    TraderScreen.Menu -> item { MenuScreen(viewModel) }
                    TraderScreen.Watchlist -> item { WatchlistScreen(state, viewModel) }
                    TraderScreen.MarketSync -> item { MarketSyncScreen(state, viewModel) }
                    TraderScreen.Signals -> item { SignalsScreen(state) }
                    TraderScreen.TodayCandidates -> item { TodayCandidatesScreen(state, viewModel) }
                    TraderScreen.ManualTrade -> item { ManualTradeScreen(state, viewModel) }
                    TraderScreen.Backtest -> item { BacktestScreen(state, viewModel) }
                    TraderScreen.TrainTest -> item { TrainTestScreen(state, viewModel) }
                    TraderScreen.SimulationHistory -> item { SimulationHistoryScreen(state) }
                    TraderScreen.History -> item { HistoryScreen(state) }
                }
            }
        }
    }
}

@Composable
private fun Header(state: TraderUiState, viewModel: TraderViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("日本株ペーパートレーダー", style = MaterialTheme.typography.headlineMedium)
        Text(state.currentScreen.title, style = MaterialTheme.typography.titleMedium)
        Text(state.currentScreen.description)
        Text("状態: ${state.status}", style = MaterialTheme.typography.labelLarge)
        Text(state.message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.currentScreen != TraderScreen.Menu) {
                TextButton(onClick = { viewModel.openScreen(TraderScreen.Menu) }) { Text("メニュー") }
            }
            Button(onClick = viewModel::refresh) { Text("更新") }
            TextButton(onClick = viewModel::reset) { Text("初期化") }
        }
    }
}

@Composable
private fun MenuScreen(viewModel: TraderViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TraderScreen.entries.filter { it != TraderScreen.Menu }.forEach { screen ->
            AppCard(screen.title) {
                Text(screen.description)
                Button(onClick = { viewModel.openScreen(screen) }) { Text("開く") }
            }
        }
    }
}

@Composable
private fun WatchlistScreen(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("対象銘柄") {
        Text("元アプリの「対象銘柄」タブに相当します。対象銘柄一覧、初期化、個別追加を行います。")
        OutlinedTextField(
            value = state.watchCode,
            onValueChange = viewModel::updateWatchCode,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("銘柄コード") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.watchName,
            onValueChange = viewModel::updateWatchName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("銘柄名") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::addWatchItem) { Text("追加 / 更新") }
            TextButton(onClick = viewModel::reset) { Text("TOPIX Core30に戻す") }
        }
        state.watchlist.forEach { WatchRow(it) }
    }
}

@Composable
private fun MarketSyncScreen(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("データ同期") {
        Text("元アプリの「データ同期」タブに相当します。スタンドアロン版では外部同期の代わりにローカル価格履歴を生成します。")
        Button(onClick = viewModel::regeneratePrices) { Text("履歴価格を再生成") }
        Text("最新価格")
        state.watchlist.take(20).forEach { WatchRow(it) }
    }
}

@Composable
private fun SignalsScreen(state: TraderUiState) {
    AppCard("売買候補スコア") {
        Text("元アプリの「売買候補」タブに相当します。モメンタムスコア、判断、最新価格を一覧表示します。")
        state.scores.sortedByDescending { it.score }.forEach { ScoreRow(it) }
    }
}

@Composable
private fun TodayCandidatesScreen(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("本日の購入候補") {
        Text("元アプリの「本日の候補」タブに相当します。保存済みシミュレーションの代替として、現在のスコア上位を本日の候補として表示します。")
        val candidates = state.scores.filter { it.score > 0.0 }.sortedByDescending { it.score }.take(10)
        if (candidates.isEmpty()) {
            Text("本日の買い候補はありません。")
        } else {
            candidates.forEach { ScoreRow(it) }
        }
        Text("候補を買う前に、手動売買画面で買い前チェックを実行してください。")
        Button(onClick = { viewModel.openScreen(TraderScreen.ManualTrade) }) { Text("手動売買へ") }
    }
}

@Composable
private fun ManualTradeScreen(state: TraderUiState, viewModel: TraderViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PortfolioCard(state.portfolio)
        ChatGptCard(state, viewModel)
        AppCard("手動売買") {
            Text("元アプリの「手動売買」タブに相当します。買い前チェック、通常買い、売りを実行します。")
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
                Button(onClick = viewModel::buyCheck) { Text("市場確認") }
                Button(onClick = viewModel::buy) { Text("通常買い") }
                TextButton(onClick = viewModel::sell) { Text("売る") }
            }
        }
        BuyCheckHistoryCard(state.buyChecks.take(10))
    }
}

@Composable
private fun BacktestScreen(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("バックテスト") {
        Text("元アプリの「バックテスト」タブに相当します。期間を指定してローカル価格履歴で検証します。")
        DateFields(state, viewModel)
        Button(onClick = viewModel::runBacktest) { Text("バックテスト実行") }
        state.runs.firstOrNull()?.let { BacktestRunRow(it) }
    }
}

@Composable
private fun TrainTestScreen(state: TraderUiState, viewModel: TraderViewModel) {
    AppCard("学習・検証シミュレーション") {
        Text("元アプリの「学習・検証」タブに相当します。学習期間と検証期間を指定し、baseline / optimized / ChatGPT調整の比較を保存します。")
        OutlinedTextField(state.trainStart, viewModel::updateTrainStart, Modifier.fillMaxWidth(), label = { Text("学習開始日") }, singleLine = true)
        OutlinedTextField(state.trainEnd, viewModel::updateTrainEnd, Modifier.fillMaxWidth(), label = { Text("学習終了日") }, singleLine = true)
        OutlinedTextField(state.testStart, viewModel::updateTestStart, Modifier.fillMaxWidth(), label = { Text("検証開始日") }, singleLine = true)
        OutlinedTextField(state.testEnd, viewModel::updateTestEnd, Modifier.fillMaxWidth(), label = { Text("検証終了日") }, singleLine = true)
        OutlinedTextField(state.objective, viewModel::updateObjective, Modifier.fillMaxWidth(), label = { Text("目的関数") }, singleLine = true)
        Button(onClick = viewModel::runTrainTestSimulation) { Text("学習→検証シミュレーション実行") }
        state.simulations.firstOrNull()?.let { SimulationRow(it) }
    }
}

@Composable
private fun SimulationHistoryScreen(state: TraderUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard("バックテスト履歴") {
            if (state.runs.isEmpty()) Text("保存済みバックテストはありません。")
            state.runs.forEach { BacktestRunRow(it) }
        }
        AppCard("シミュレーション履歴") {
            if (state.simulations.isEmpty()) Text("保存済みシミュレーションはありません。")
            state.simulations.forEach { SimulationRow(it) }
        }
    }
}

@Composable
private fun HistoryScreen(state: TraderUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard("売買履歴") {
            if (state.trades.isEmpty()) Text("売買履歴はありません。")
            state.trades.forEach { TradeRow(it) }
        }
        BuyCheckHistoryCard(state.buyChecks)
        AppCard("シグナルログ") {
            Text("スタンドアロン版では直近の売買候補スコアをログ相当として表示します。")
            state.scores.take(20).forEach { ScoreRow(it) }
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
private fun DateFields(state: TraderUiState, viewModel: TraderViewModel) {
    OutlinedTextField(state.backtestStart, viewModel::updateBacktestStart, Modifier.fillMaxWidth(), label = { Text("開始日") }, singleLine = true)
    OutlinedTextField(state.backtestEnd, viewModel::updateBacktestEnd, Modifier.fillMaxWidth(), label = { Text("終了日") }, singleLine = true)
}

@Composable
private fun WatchRow(item: WatchItem) {
    Text("${item.code} ${item.name} 最新 ${item.latestPrice.number()}")
}

@Composable
private fun ScoreRow(item: ScoreItem) {
    Text("${item.code} ${item.name} ${item.action} スコア ${item.score.percent()} 最新 ${item.latestPrice.number()}")
}

@Composable
private fun BacktestRunRow(run: BacktestRun) {
    Text("#${run.id} ${run.startDate} から ${run.endDate}")
    Text("最終資産 ${run.finalValue.yen()} リターン ${run.totalReturn.percent()} 売買回数 ${run.tradesCount}")
}

@Composable
private fun SimulationRow(run: SimulationRun) {
    Text("#${run.id} ${run.objective} ${run.trainStart}〜${run.trainEnd} / ${run.testStart}〜${run.testEnd}")
    Text("baseline ${run.baselineReturn.percent()} optimized ${run.optimizedReturn.percent()} ChatGPT ${run.chatGptReturn.percent()}")
}

@Composable
private fun TradeRow(item: TradeHistoryItem) {
    Text("#${item.id} ${item.tradedAt} ${item.code} ${item.side} ${item.quantity}株 ${item.price.number()}円")
}

@Composable
private fun BuyCheckHistoryCard(items: List<BuyCheckHistoryItem>) {
    AppCard("買い確認履歴") {
        if (items.isEmpty()) Text("保存済み買い確認はありません。")
        items.forEach {
            Text("#${it.id} ${it.checkedAt} ${it.code} ${it.quantity}株 ${it.price.number()}円")
            Text(it.result)
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
