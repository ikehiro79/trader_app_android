# Trader App Android

Standalone Android paper trading app built with Kotlin, Jetpack Compose, Material3, MVVM, and Hilt.

## Features

- TOPIX Core30 seed watchlist
- Device-local SQLite storage
- Locally generated price history for offline use
- Portfolio summary
- Manual buy check, buy, and sell
- Momentum score list
- Simple local backtest and saved run history
- In-app ChatGPT / OpenAI API key and model settings
- GitHub Actions debug APK build

## Tech Stack

- Kotlin
- Jetpack Compose
- Material3
- MVVM
- Hilt
- SQLite
- GitHub Actions

## Build Locally

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
gradle :androidApp:assembleDebug
```

Debug APK:

```text
androidApp\build\outputs\apk\debug\androidApp-debug.apk
```

## CI

GitHub Actions builds a debug APK on pushes and pull requests to `main`.

Artifact name:

```text
trader-app-debug-apk
```

## Notes

The app is standalone and does not require the original Python/FastAPI service. Current market data is generated locally for offline paper-trading workflows. Real market APIs such as J-Quants can be added later as Android-side data providers.
