from __future__ import annotations

import argparse
import json
from pathlib import Path

from level_scanner import avg_touch_error_pct, fetch_klines, fetch_klines_history, find_pivots, find_trendlines, max_touch_error_pct, plot_trendlines


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Draw clean diagonal support/resistance trendlines.")
    parser.add_argument("--symbol", default="BTCUSDT")
    parser.add_argument("--interval", default="1h")
    parser.add_argument("--limit", type=int, default=1500)
    parser.add_argument("--days", type=int, default=0, help="Load historical candles by days using Binance pagination")
    parser.add_argument("--pivot-window", type=int, default=4)
    parser.add_argument("--min-touches", type=int, default=3)
    parser.add_argument("--min-bars-between-touches", type=int, default=24)
    parser.add_argument("--min-span-bars", type=int, default=160)
    parser.add_argument("--touch-tolerance-pct", type=float, default=0.18)
    parser.add_argument("--max-avg-touch-error-pct", type=float, default=0.08)
    parser.add_argument("--break-tolerance-pct", type=float, default=0.12)
    parser.add_argument("--min-reaction-pct", type=float, default=2.0)
    parser.add_argument("--min-slope-pct", type=float, default=1.0)
    parser.add_argument("--max-slope-pct", type=float, default=45.0)
    parser.add_argument("--max-results", type=int, default=1)
    parser.add_argument("--context-bars", type=int, default=80)
    parser.add_argument("--right-padding-bars", type=int, default=20)
    parser.add_argument("--output", default="market_scanner/output/trendline_scan.png")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    candles = (
        fetch_klines_history(args.symbol, args.interval, args.days, limit=args.limit if args.limit > 0 else None)
        if args.days > 0
        else fetch_klines(args.symbol, args.interval, args.limit)
    )
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
                max_results=args.max_results,
            )
        )
    lines = sorted(lines, key=lambda item: (item.touches, item.span_bars), reverse=True)[: args.max_results]
    if not lines:
        raise SystemExit("No clean trendlines found. Try increasing --limit or relaxing tolerances.")

    output = Path(args.output)
    plot_trendlines(
        candles,
        lines,
        args.symbol,
        args.interval,
        output,
        context_bars=args.context_bars,
        right_padding_bars=args.right_padding_bars,
    )
    print(
        json.dumps(
            {
                "symbol": args.symbol.upper(),
                "interval": args.interval,
                "lines": [
                    {
                        "kind": line.kind,
                        "touches": line.touches,
                        "span_bars": line.span_bars,
                        "pivot_touch_indexes": [pivot.index for pivot in line.pivots],
                        "all_touch_indexes": line.all_touch_indexes(),
                        "current_line_price": line.price_at(len(candles) - 1),
                        "current_price": candles[-1].close,
                        "avg_touch_error_pct": avg_touch_error_pct(line),
                        "max_touch_error_pct": max_touch_error_pct(line),
                    }
                    for line in lines
                ],
            },
            indent=2,
        )
    )
    print(f"Saved chart: {output.resolve()}")


if __name__ == "__main__":
    main()
