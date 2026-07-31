from __future__ import annotations

import argparse
import json
from dataclasses import dataclass

import requests


BINANCE_FUTURES_EXCHANGE_INFO = "https://fapi.binance.com/fapi/v1/exchangeInfo"
BINANCE_FUTURES_24H = "https://fapi.binance.com/fapi/v1/ticker/24hr"
BINANCE_FUTURES_BOOK = "https://fapi.binance.com/fapi/v1/ticker/bookTicker"
BINANCE_FUTURES_KLINES = "https://fapi.binance.com/fapi/v1/klines"


@dataclass(frozen=True)
class MarketFilterPreset:
    min_quote_volume: float
    min_recent_volume: float
    recent_minutes: int
    min_change_pct: float
    change_minutes: int
    max_spread_pct: float
    max_symbols: int


PRESETS = {
    "scalp_1m": MarketFilterPreset(
        min_quote_volume=50_000_000,
        min_recent_volume=500_000,
        recent_minutes=15,
        min_change_pct=0.8,
        change_minutes=60,
        max_spread_pct=0.08,
        max_symbols=80,
    ),
    "scalp_5m": MarketFilterPreset(
        min_quote_volume=30_000_000,
        min_recent_volume=1_000_000,
        recent_minutes=30,
        min_change_pct=1.2,
        change_minutes=240,
        max_spread_pct=0.10,
        max_symbols=100,
    ),
    "swing_1h": MarketFilterPreset(
        min_quote_volume=10_000_000,
        min_recent_volume=0,
        recent_minutes=60,
        min_change_pct=0,
        change_minutes=240,
        max_spread_pct=0.20,
        max_symbols=150,
    ),
    "global": MarketFilterPreset(
        min_quote_volume=5_000_000,
        min_recent_volume=0,
        recent_minutes=240,
        min_change_pct=0,
        change_minutes=1440,
        max_spread_pct=0.30,
        max_symbols=250,
    ),
}


def preset_for_interval(interval: str) -> str:
    if interval == "1m":
        return "scalp_1m"
    if interval == "5m":
        return "scalp_5m"
    if interval in {"15m", "30m", "1h"}:
        return "swing_1h"
    return "global"


def fetch_usdt_perpetual_symbols() -> set[str]:
    response = requests.get(BINANCE_FUTURES_EXCHANGE_INFO, timeout=20)
    response.raise_for_status()
    return {
        item["symbol"]
        for item in response.json()["symbols"]
        if item.get("contractType") == "PERPETUAL"
        and item.get("quoteAsset") == "USDT"
        and item.get("status") == "TRADING"
    }


def fetch_24h_stats() -> dict[str, dict]:
    response = requests.get(BINANCE_FUTURES_24H, timeout=20)
    response.raise_for_status()
    return {item["symbol"]: item for item in response.json()}


def fetch_book_tickers() -> dict[str, dict]:
    response = requests.get(BINANCE_FUTURES_BOOK, timeout=20)
    response.raise_for_status()
    return {item["symbol"]: item for item in response.json()}


def recent_metrics(symbol: str, minutes: int) -> tuple[float, float]:
    limit = max(2, min(1500, minutes))
    response = requests.get(
        BINANCE_FUTURES_KLINES,
        params={"symbol": symbol, "interval": "1m", "limit": limit},
        timeout=20,
    )
    response.raise_for_status()
    rows = response.json()
    if len(rows) < 2:
        return 0.0, 0.0

    quote_volume = sum(float(row[7]) for row in rows)
    first_open = float(rows[0][1])
    last_close = float(rows[-1][4])
    change_pct = abs(last_close - first_open) / first_open * 100
    return quote_volume, change_pct


def spread_pct(symbol: str, books: dict[str, dict]) -> float:
    item = books.get(symbol)
    if not item:
        return 999.0
    bid = float(item["bidPrice"])
    ask = float(item["askPrice"])
    if bid <= 0 or ask <= 0:
        return 999.0
    mid = (bid + ask) / 2
    return (ask - bid) / mid * 100


def top_movers(
    stats: dict[str, dict],
    books: dict[str, dict],
    symbols: set[str],
    quote_volume_threshold: float,
    spread_threshold: float,
    per_side: int,
) -> list[dict]:
    eligible = []
    for symbol in symbols:
        stat = stats.get(symbol)
        if not stat:
            continue
        quote_volume = float(stat.get("quoteVolume", 0))
        if quote_volume < quote_volume_threshold:
            continue
        current_spread = spread_pct(symbol, books)
        if current_spread > spread_threshold:
            continue
        change_24h = float(stat.get("priceChangePercent", 0))
        eligible.append(
            {
                "symbol": symbol,
                "quote_volume_24h": quote_volume,
                "recent_volume": 0.0,
                "change_pct": abs(change_24h),
                "change_24h_pct": change_24h,
                "spread_pct": current_spread,
            }
        )

    gainers = sorted(eligible, key=lambda item: item["change_24h_pct"], reverse=True)[:per_side]
    losers = sorted(eligible, key=lambda item: item["change_24h_pct"])[:per_side]
    merged = []
    seen = set()
    for source, rows in (("top_gainer_24h", gainers), ("top_loser_24h", losers)):
        for row in rows:
            if row["symbol"] in seen:
                continue
            item = row.copy()
            item["source"] = source
            merged.append(item)
            seen.add(item["symbol"])
    return merged


def filter_symbols(
    preset_name: str,
    limit: int = 0,
    min_quote_volume: float | None = None,
    min_recent_volume: float | None = None,
    min_change_pct: float | None = None,
    max_spread_pct: float | None = None,
    include_top_movers: bool = True,
    top_movers_per_side: int = 10,
) -> list[dict]:
    preset = PRESETS[preset_name]
    quote_volume_threshold = preset.min_quote_volume if min_quote_volume is None else min_quote_volume
    recent_volume_threshold = preset.min_recent_volume if min_recent_volume is None else min_recent_volume
    change_threshold = preset.min_change_pct if min_change_pct is None else min_change_pct
    spread_threshold = preset.max_spread_pct if max_spread_pct is None else max_spread_pct

    symbols = fetch_usdt_perpetual_symbols()
    stats = fetch_24h_stats()
    books = fetch_book_tickers()

    candidates = []
    for symbol in symbols:
        stat = stats.get(symbol)
        if not stat:
            continue
        quote_volume = float(stat.get("quoteVolume", 0))
        if quote_volume < quote_volume_threshold:
            continue
        current_spread = spread_pct(symbol, books)
        if current_spread > spread_threshold:
            continue
        candidates.append((symbol, quote_volume, current_spread))

    candidates.sort(key=lambda item: item[1], reverse=True)
    max_count = limit or preset.max_symbols
    filtered = []
    seen = set()
    if include_top_movers and top_movers_per_side > 0:
        for item in top_movers(
            stats,
            books,
            symbols,
            quote_volume_threshold=quote_volume_threshold,
            spread_threshold=spread_threshold,
            per_side=top_movers_per_side,
        ):
            filtered.append(item)
            seen.add(item["symbol"])

    for symbol, quote_volume, current_spread in candidates[: max_count * 3]:
        if symbol in seen:
            continue
        recent_volume, change_pct = recent_metrics(symbol, max(preset.recent_minutes, preset.change_minutes))
        if recent_volume < recent_volume_threshold:
            continue
        if change_pct < change_threshold:
            continue
        filtered.append(
            {
                "symbol": symbol,
                "quote_volume_24h": quote_volume,
                "recent_volume": recent_volume,
                "change_pct": change_pct,
                "change_24h_pct": float(stats[symbol].get("priceChangePercent", 0)),
                "spread_pct": current_spread,
                "source": "liquid_mover",
            }
        )
        seen.add(symbol)
        if len(filtered) >= max_count:
            break

    return filtered


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Filter Binance Futures symbols by liquidity and movement.")
    parser.add_argument("--preset", choices=sorted(PRESETS), default="")
    parser.add_argument("--interval", default="5m")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--min-quote-volume", type=float, default=None)
    parser.add_argument("--min-recent-volume", type=float, default=None)
    parser.add_argument("--min-change-pct", type=float, default=None)
    parser.add_argument("--max-spread-pct", type=float, default=None)
    parser.add_argument("--no-top-movers", action="store_true")
    parser.add_argument("--top-movers-per-side", type=int, default=10)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    preset_name = args.preset or preset_for_interval(args.interval)
    symbols = filter_symbols(
        preset_name,
        limit=args.limit,
        min_quote_volume=args.min_quote_volume,
        min_recent_volume=args.min_recent_volume,
        min_change_pct=args.min_change_pct,
        max_spread_pct=args.max_spread_pct,
        include_top_movers=not args.no_top_movers,
        top_movers_per_side=args.top_movers_per_side,
    )
    print(json.dumps({"preset": preset_name, "symbols": symbols}, indent=2))


if __name__ == "__main__":
    main()
