# Market Scanner MVP

MVP for drawing support/resistance levels by touches on Binance Futures candles.

Current focus:

- load OHLCV candles from Binance Futures;
- find local pivot highs/lows;
- cluster nearby pivots into horizontal levels;
- count independent touches;
- draw candles, level lines, and touch markers.

No TP/SL logic is included yet.

## Run

```powershell
python market_scanner/level_scanner.py --symbol GWEIUSDT --interval 15m --limit 300
```

The chart is saved to:

```text
market_scanner/output/level_scan.png
```

Useful knobs:

```powershell
python market_scanner/level_scanner.py `
  --symbol BTCUSDT `
  --interval 15m `
  --limit 300 `
  --pivot-window 3 `
  --tolerance-pct 0.45 `
  --min-touches 2 `
  --min-bars-between-touches 8 `
  --max-distance-pct 1.2
```

If the bot draws too many fake levels, lower `--tolerance-pct` or increase `--min-touches`.
If it misses obvious levels, increase `--tolerance-pct` or lower `--pivot-window`.
If it draws old levels instead of real approaches, lower `--max-distance-pct`.

## Scan Market

Scan all Binance USDT perpetuals and save charts for active approaches:

```powershell
python market_scanner/scan_market.py --interval 15m --limit-candles 500 --max-results 10
```

Stronger level filters:

```powershell
--min-bars-between-touches 16
--min-level-span-bars 48
--min-reaction-pct 1.5
```

## Diagonal Trendlines

Draw clean diagonal support/resistance lines:

```powershell
python market_scanner/trendline_scanner.py --symbol BTCUSDT --interval 1h --limit 1500
```

For lower timeframes with deeper history, use paginated loading:

```powershell
python market_scanner/trendline_scanner.py --symbol BTCUSDT --interval 1m --days 7 --limit 0
python market_scanner/trendline_scanner.py --symbol BTCUSDT --interval 5m --days 60 --limit 0
```

Defaults are intentionally strict:

```text
--min-touches 3
--min-span-bars 160
--min-bars-between-touches 24
--break-tolerance-pct 0.12
```

By default the scanner draws only the best clean trendline (`--max-results 1`) to avoid noisy overlapping lines.

The trendline is rejected if price crosses it after the line starts.

Notes:

- `1m` for a full year is more than 500k candles per symbol, so scan it selectively.
- `5m` for several months is realistic, but full-market scans will be slow.

## Scan Diagonal Trendlines

Scan selected symbols:

```powershell
python market_scanner/scan_trendlines.py --symbols BTCUSDT,ETHUSDT,SOLUSDT --interval 5m --days 10 --max-results 10
```

Scan Binance USDT perpetuals:

```powershell
python market_scanner/scan_trendlines.py --interval 5m --days 10 --limit-symbols 50 --max-results 10
```

For 1m, keep the symbol list smaller:

```powershell
python market_scanner/scan_trendlines.py --symbols BTCUSDT,ETHUSDT,SOLUSDT --interval 1m --days 3 --min-span-bars 360 --min-bars-between-touches 80
```

Auto-select liquid and moving symbols for lower timeframes:

```powershell
python market_scanner/market_filter.py --interval 5m --limit 30
python market_scanner/scan_trendlines.py --auto-filter --interval 5m --days 10 --limit-symbols 30 --max-results 10
```

Published charts are cropped around the detected formation by default. Tune the visible left/right context:

```powershell
--context-bars 80
--right-padding-bars 20
```

Preset mapping:

```text
1m -> scalp_1m
5m -> scalp_5m
15m/30m/1h -> swing_1h
4h/1d -> global
```

## Telegram Runner

One-shot test:

```powershell
$env:TELEGRAM_BOT_TOKEN="..."
$env:TELEGRAM_CHAT_ID="-100xxxxxxxxxx"
python market_scanner/scanner_runner.py --preset 5m_scalp --send-telegram --once --limit-symbols 30 --max-results 5
```

Continuous local run:

```powershell
python market_scanner/scanner_runner.py --preset 5m_scalp --send-telegram --sleep-seconds 300
```

For Linux/systemd, use `market-scanner.service.example` as a template.
