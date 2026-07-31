from __future__ import annotations

import argparse
import json
from pathlib import Path

import requests

from level_scanner import (
    cluster_levels,
    fetch_klines,
    fetch_klines_history,
    find_pivots,
    plot_levels,
    reaction_after_last_touch,
    select_levels,
)


BINANCE_FUTURES_EXCHANGE_INFO = "https://fapi.binance.com/fapi/v1/exchangeInfo"


def fetch_usdt_symbols(limit: int) -> list[str]:
    response = requests.get(BINANCE_FUTURES_EXCHANGE_INFO, timeout=20)
    response.raise_for_status()
    symbols = []
    for item in response.json()["symbols"]:
        if (
            item.get("contractType") == "PERPETUAL"
            and item.get("quoteAsset") == "USDT"
            and item.get("status") == "TRADING"
        ):
            symbols.append(item["symbol"])
    symbols.sort()
    return symbols[:limit] if limit else symbols


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scan Binance Futures symbols for active level approaches.")
    parser.add_argument("--interval", default="15m")
    parser.add_argument("--limit-candles", type=int, default=500)
    parser.add_argument("--days", type=int, default=0, help="Load historical candles by days using Binance pagination")
    parser.add_argument("--limit-symbols", type=int, default=0)
    parser.add_argument("--pivot-window", type=int, default=3)
    parser.add_argument("--tolerance-pct", type=float, default=0.45)
    parser.add_argument("--min-touches", type=int, default=2)
    parser.add_argument("--min-bars-between-touches", type=int, default=16)
    parser.add_argument("--min-level-span-bars", type=int, default=48)
    parser.add_argument("--min-reaction-pct", type=float, default=1.5)
    parser.add_argument("--max-distance-pct", type=float, default=1.2)
    parser.add_argument("--break-tolerance-pct", type=float, default=0.15)
    parser.add_argument("--recent-bars", type=int, default=8)
    parser.add_argument("--max-results", type=int, default=10)
    parser.add_argument("--output-dir", default="market_scanner/output/market_scan")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    results = []

    for symbol in fetch_usdt_symbols(args.limit_symbols):
        try:
            candles = (
                fetch_klines_history(symbol, args.interval, args.days, limit=args.limit_candles if args.limit_candles > 0 else None)
                if args.days > 0
                else fetch_klines(symbol, args.interval, args.limit_candles)
            )
            pivots = find_pivots(candles, args.pivot_window)
            levels = cluster_levels(
                pivots,
                args.tolerance_pct,
                args.min_touches,
                args.min_bars_between_touches,
                args.min_level_span_bars,
                args.min_reaction_pct,
                candles,
            )
            selected = select_levels(
                levels,
                candles,
                args.max_distance_pct,
                1,
                args.break_tolerance_pct,
                args.recent_bars,
            )
            if not selected:
                continue

            level = selected[0]
            current = candles[-1].close
            results.append(
                {
                    "symbol": symbol,
                    "current_price": current,
                    "kind": level.kind,
                    "level_price": level.price,
                    "touches": level.touches,
                    "span_bars": level.last_index - level.first_index,
                    "distance_pct": abs(current - level.price) / current * 100,
                    "reaction_after_last_touch_pct": reaction_after_last_touch(level, candles),
                    "level": level,
                    "candles": candles,
                }
            )
        except Exception as exc:
            print(f"{symbol}: skipped ({exc})")

    results.sort(key=lambda item: (item["touches"], item["span_bars"], -item["distance_pct"]), reverse=True)
    selected_results = results[: args.max_results]

    public_results = []
    for item in selected_results:
        chart_path = output_dir / f"{item['symbol']}_{args.interval}.png"
        plot_levels(item["candles"], [item["level"]], item["symbol"], args.interval, chart_path)
        public_results.append({key: value for key, value in item.items() if key not in {"level", "candles"}} | {"chart": str(chart_path)})

    print(json.dumps(public_results, indent=2))


if __name__ == "__main__":
    main()
