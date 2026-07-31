from __future__ import annotations

import argparse
import json
from pathlib import Path

import requests

from level_scanner import (
    fetch_klines,
    fetch_klines_history,
    find_pivots,
    find_trendlines,
    avg_touch_error_pct,
    max_touch_error_pct,
    plot_trendlines,
)
from market_filter import filter_symbols, preset_for_interval


BINANCE_FUTURES_EXCHANGE_INFO = "https://fapi.binance.com/fapi/v1/exchangeInfo"


def fetch_usdt_symbols(limit: int, symbols: str) -> list[str]:
    if symbols:
        return [item.strip().upper() for item in symbols.split(",") if item.strip()]

    response = requests.get(BINANCE_FUTURES_EXCHANGE_INFO, timeout=20)
    response.raise_for_status()
    result = []
    for item in response.json()["symbols"]:
        if (
            item.get("contractType") == "PERPETUAL"
            and item.get("quoteAsset") == "USDT"
            and item.get("status") == "TRADING"
        ):
            result.append(item["symbol"])
    result.sort()
    return result[:limit] if limit else result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scan Binance Futures for clean diagonal trendline approaches.")
    parser.add_argument("--symbols", default="", help="Comma-separated symbols. If empty, scans Binance USDT perpetuals.")
    parser.add_argument("--auto-filter", action="store_true", help="Select symbols by liquidity/movement preset.")
    parser.add_argument("--filter-preset", default="", help="Market filter preset. Defaults by interval.")
    parser.add_argument("--min-quote-volume", type=float, default=None)
    parser.add_argument("--min-recent-volume", type=float, default=None)
    parser.add_argument("--min-change-pct", type=float, default=None)
    parser.add_argument("--max-spread-pct", type=float, default=None)
    parser.add_argument("--interval", default="5m")
    parser.add_argument("--days", type=int, default=10)
    parser.add_argument("--limit-candles", type=int, default=0)
    parser.add_argument("--limit-symbols", type=int, default=30)
    parser.add_argument("--pivot-window", type=int, default=4)
    parser.add_argument("--min-touches", type=int, default=3)
    parser.add_argument("--min-bars-between-touches", type=int, default=48)
    parser.add_argument("--min-span-bars", type=int, default=288)
    parser.add_argument("--touch-tolerance-pct", type=float, default=0.18)
    parser.add_argument("--max-avg-touch-error-pct", type=float, default=0.08)
    parser.add_argument("--break-tolerance-pct", type=float, default=0.12)
    parser.add_argument("--min-reaction-pct", type=float, default=2.0)
    parser.add_argument("--min-slope-pct", type=float, default=1.0)
    parser.add_argument("--max-slope-pct", type=float, default=45.0)
    parser.add_argument("--max-distance-pct", type=float, default=2.0)
    parser.add_argument("--max-results", type=int, default=10)
    parser.add_argument("--context-bars", type=int, default=80)
    parser.add_argument("--right-padding-bars", type=int, default=20)
    parser.add_argument("--output-dir", default="market_scanner/output/trendline_scan")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    results = []

    if args.auto_filter and not args.symbols:
        preset_name = args.filter_preset or preset_for_interval(args.interval)
        market_rows = filter_symbols(
            preset_name,
            limit=args.limit_symbols,
            min_quote_volume=args.min_quote_volume,
            min_recent_volume=args.min_recent_volume,
            min_change_pct=args.min_change_pct,
            max_spread_pct=args.max_spread_pct,
        )
        symbols = [item["symbol"] for item in market_rows]
        print(f"auto_filter={preset_name} symbols={','.join(symbols)}")
    else:
        symbols = fetch_usdt_symbols(args.limit_symbols, args.symbols)

    for symbol in symbols:
        try:
            candles = (
                fetch_klines_history(symbol, args.interval, args.days, limit=args.limit_candles if args.limit_candles > 0 else None)
                if args.days > 0
                else fetch_klines(symbol, args.interval, args.limit_candles)
            )
            if len(candles) < args.min_span_bars + args.pivot_window * 2 + 10:
                continue

            pivots = find_pivots(candles, args.pivot_window)
            lines = []
            for kind in ("support", "resistance"):
                lines.extend(
                    find_trendlines(
                        pivots=pivots,
                        candles=candles,
                        kind=kind,
                        min_touches=args.min_touches,
                        min_span_bars=args.min_span_bars,
                        min_bars_between_touches=args.min_bars_between_touches,
                        touch_tolerance_pct=args.touch_tolerance_pct,
                        max_avg_touch_error_pct=args.max_avg_touch_error_pct,
                        break_tolerance_pct=args.break_tolerance_pct,
                        min_reaction_pct=args.min_reaction_pct,
                        min_slope_pct=args.min_slope_pct,
                        max_slope_pct=args.max_slope_pct,
                        max_results=1,
                    )
                )
            if not lines:
                continue

            current_price = candles[-1].close
            filtered = []
            for line in lines:
                current_line_price = line.price_at(len(candles) - 1)
                distance_pct = abs(current_price - current_line_price) / current_price * 100
                if distance_pct <= args.max_distance_pct:
                    filtered.append((line, distance_pct))
            if not filtered:
                continue

            line, distance_pct = sorted(filtered, key=lambda item: (item[0].touches, item[0].span_bars, -item[1]), reverse=True)[0]
            results.append(
                {
                    "symbol": symbol,
                    "interval": args.interval,
                    "kind": line.kind,
                    "touches": line.touches,
                    "visual_touches": len(line.all_touch_indexes()),
                    "span_bars": line.span_bars,
                    "distance_pct": distance_pct,
                    "current_price": current_price,
                    "current_line_price": line.price_at(len(candles) - 1),
                    "avg_touch_error_pct": avg_touch_error_pct(line),
                    "max_touch_error_pct": max_touch_error_pct(line),
                    "line": line,
                    "candles": candles,
                }
            )
        except Exception as exc:
            print(f"{symbol}: skipped ({exc})")

    results.sort(key=lambda item: (item["touches"], item["span_bars"], -item["distance_pct"]), reverse=True)
    selected = results[: args.max_results]
    public_results = []

    for item in selected:
        chart = output_dir / f"{item['symbol']}_{args.interval}_trend.png"
        plot_trendlines(
            item["candles"],
            [item["line"]],
            item["symbol"],
            args.interval,
            chart,
            context_bars=args.context_bars,
            right_padding_bars=args.right_padding_bars,
        )
        public_results.append({key: value for key, value in item.items() if key not in {"line", "candles"}} | {"chart": str(chart)})

    print(json.dumps(public_results, indent=2))


if __name__ == "__main__":
    main()
