# Android app

このフォルダには、PC 側の FastAPI サーバーなしで動くスタンドアロン Android アプリを追加しています。

## 構成

- Android モジュール: `androidApp`
- パッケージ名: `com.example.jppapertrader`
- 端末内 DB: Android SQLite
- UI: Kotlin / Jetpack Compose / Material3
- アーキテクチャ: MVVM
- DI: Hilt
- CI: GitHub Actions による APK 自動生成
- ネットワーク権限: なし

## 現在入っている機能

- TOPIX Core30 の初期ウォッチリスト
- 端末内 SQLite への保存
- ローカル生成価格履歴
- ポートフォリオ表示
- 買付チェック
- 手動買い
- 手動売り
- モメンタムスコア表示
- 簡易バックテスト
- バックテスト履歴保存
- ローカル DB リセット
- ChatGPT / OpenAI API キーのアプリ内保存
- ChatGPT モデル名のアプリ内保存

## 注意

現在のスタンドアロン版は、外部サーバーなしで動作するため、株価はアプリ内で生成したローカル価格履歴を使います。実際の最新株価、J-Quants、OpenAI、yfinance、TradingAgents の処理は Android 内にはまだ移植していません。

実株価を端末だけで扱う場合は、Android から直接 J-Quants などの API を呼ぶ機能を追加してください。

ChatGPT API キーは端末内のアプリ設定に保存されます。画面上では保存済みキーを省略表示し、入力欄にはキー全文を再表示しません。

## APK ビルド

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
gradle :androidApp:assembleDebug
```

出力先:

```text
androidApp\build\outputs\apk\debug\androidApp-debug.apk
```
