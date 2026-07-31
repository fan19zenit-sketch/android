from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable

import matplotlib.dates as mdates
import matplotlib.pyplot as plt
import requests


BINANCE_FUTURES_KLINES = "https://fapi.binance.com/fapi/v1/klines"
INTERVAL_MS = {
    "1m": 60_000,
    "3m": 3 * 60_000,
    "5m": 5 * 60_000,
    "15m": 15 * 60_000,
    "30m": 30 * 60_000,
    "1h": 60 * 60_000,
    "2h": 2 * 60 * 60_000,
    "4h": 4 * 60 * 60_000,
    "6h": 6 * 60 * 60_000,
    "8h": 8 * 60 * 60_000,
    "12h": 12 * 60 * 60_000,
    "1d": 24 * 60 * 60_000,
    "3d": 3 * 24 * 60 * 60_000,
    "1w": 7 * 24 * 60 * 60_000,
}


@dataclass(frozen=True)
class Candle:
    open_time: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float


@dataclass(frozen=True)
class Pivot:
    index: int
    price: float
    kind: str


@dataclass
class Level:
    price: float
    kind: str
    pivots: list[Pivot]

    @property
    def first_index(self) -> int:
        return min(p.index for p in self.pivots)

    @property
    def last_index(self) -> int:
        return max(p.index for p in self.pivots)

    @property
    def touches(self) -> int:
        return len(self.pivots)


@dataclass
class Trendline:
    kind: str
    start_index: int
    end_index: int
    slope: float
    intercept: float
    pivots: list[Pivot]
    candle_touches: list[int] | None = None

    @property
    def touches(self) -> int:
        return len(self.pivots)

    @property
    def span_bars(self) -> int:
        return self.end_index - self.start_index

    def price_at(self, index: int) -> float:
        return self.slope * index + self.intercept

    def all_touch_indexes(self) -> list[int]:
        indexes = {pivot.index for pivot in self.pivots}
        if self.candle_touches:
            indexes.update(self.candle_touches)
        return sorted(indexes)


def fetch_klines(symbol: str, interval: str, limit: int) -> list[Candle]:
    response = requests.get(
        BINANCE_FUTURES_KLINES,
        params={"symbol": symbol.upper(), "interval": interval, "limit": limit},
        timeout=20,
    )
    response.raise_for_status()
    candles: list[Candle] = []
    for row in response.json():
        candles.append(
            Candle(
                open_time=datetime.fromtimestamp(row[0] / 1000, tz=timezone.utc),
                open=float(row[1]),
                high=float(row[2]),
                low=float(row[3]),
                close=float(row[4]),
                volume=float(row[5]),
            )
        )
    return candles


def fetch_klines_history(symbol: str, interval: str, days: int, limit: int | None = None) -> list[Candle]:
    if interval not in INTERVAL_MS:
        raise ValueError(f"Unsupported interval for history pagination: {interval}")

    end_ms = int(datetime.now(tz=timezone.utc).timestamp() * 1000)
    start_ms = int((datetime.now(tz=timezone.utc) - timedelta(days=days)).timestamp() * 1000)
    step_ms = INTERVAL_MS[interval]
    rows = []

    while start_ms < end_ms:
        response = requests.get(
            BINANCE_FUTURES_KLINES,
            params={
                "symbol": symbol.upper(),
                "interval": interval,
                "limit": 1500,
                "startTime": start_ms,
                "endTime": end_ms,
            },
            timeout=20,
        )
        response.raise_for_status()
        batch = response.json()
        if not batch:
            break
        rows.extend(batch)
        next_start = int(batch[-1][0]) + step_ms
        if next_start <= start_ms:
            break
        start_ms = next_start
        if limit and len(rows) >= limit:
            rows = rows[-limit:]
            break

    candles = [
        Candle(
            open_time=datetime.fromtimestamp(row[0] / 1000, tz=timezone.utc),
            open=float(row[1]),
            high=float(row[2]),
            low=float(row[3]),
            close=float(row[4]),
            volume=float(row[5]),
        )
        for row in rows
    ]
    return candles[-limit:] if limit else candles


def find_pivots(candles: list[Candle], window: int) -> list[Pivot]:
    pivots: list[Pivot] = []
    for index in range(window, len(candles) - window):
        chunk = candles[index - window : index + window + 1]
        candle = candles[index]
        if candle.high == max(item.high for item in chunk):
            pivots.append(Pivot(index=index, price=candle.high, kind="resistance"))
        if candle.low == min(item.low for item in chunk):
            pivots.append(Pivot(index=index, price=candle.low, kind="support"))
    return pivots


def find_trendlines(
    pivots: list[Pivot],
    candles: list[Candle],
    kind: str,
    min_touches: int,
    min_span_bars: int,
    min_bars_between_touches: int,
    touch_tolerance_pct: float,
    max_avg_touch_error_pct: float,
    break_tolerance_pct: float,
    min_reaction_pct: float,
    min_slope_pct: float,
    max_slope_pct: float,
    max_results: int,
) -> list[Trendline]:
    side_pivots = sorted([pivot for pivot in pivots if pivot.kind == kind], key=lambda item: item.index)
    candidates: list[Trendline] = []

    for left_index, first in enumerate(side_pivots):
        for last in side_pivots[left_index + 1 :]:
            span = last.index - first.index
            if span < min_span_bars:
                continue

            slope = (last.price - first.price) / span
            intercept = first.price - slope * first.index
            line = Trendline(kind=kind, start_index=first.index, end_index=last.index, slope=slope, intercept=intercept, pivots=[])

            total_slope_pct = abs(line.price_at(last.index) - line.price_at(first.index)) / line.price_at(first.index) * 100
            if total_slope_pct < min_slope_pct or total_slope_pct > max_slope_pct:
                continue
            if kind == "support" and slope <= 0:
                continue
            if kind == "resistance" and slope >= 0:
                continue

            touches: list[Pivot] = []
            for pivot in side_pivots:
                if pivot.index < first.index or pivot.index > last.index:
                    continue
                expected = line.price_at(pivot.index)
                distance_pct = abs(pivot.price - expected) / expected * 100
                if distance_pct <= touch_tolerance_pct and all(
                    abs(pivot.index - existing.index) >= min_bars_between_touches for existing in touches
                ):
                    touches.append(pivot)

            if len(touches) < min_touches:
                continue

            line.pivots = touches
            line.start_index = min(pivot.index for pivot in touches)
            line.end_index = max(pivot.index for pivot in touches)
            if line.span_bars < min_span_bars:
                continue
            if avg_touch_error_pct(line) > max_avg_touch_error_pct:
                continue
            if not trendline_has_reaction(line, candles, min_reaction_pct):
                continue
            if trendline_is_crossed(line, candles, break_tolerance_pct, from_index=line.start_index):
                continue
            if not trendline_is_active_approach(line, candles, break_tolerance_pct):
                continue
            line.candle_touches = find_candle_touches_for_trendline(
                line,
                candles,
                touch_tolerance_pct=touch_tolerance_pct,
                min_bars_between_touches=min_bars_between_touches,
            )
            candidates.append(line)

    deduped = dedupe_trendlines(candidates, touch_tolerance_pct)
    return sorted(deduped, key=lambda item: (item.touches, item.span_bars), reverse=True)[:max_results]


def touch_error_pct(line: Trendline, pivot: Pivot) -> float:
    expected = line.price_at(pivot.index)
    return abs(pivot.price - expected) / expected * 100


def avg_touch_error_pct(line: Trendline) -> float:
    if not line.pivots:
        return 0.0
    return sum(touch_error_pct(line, pivot) for pivot in line.pivots) / len(line.pivots)


def max_touch_error_pct(line: Trendline) -> float:
    return max((touch_error_pct(line, pivot) for pivot in line.pivots), default=0.0)


def find_candle_touches_for_trendline(
    line: Trendline,
    candles: list[Candle],
    touch_tolerance_pct: float,
    min_bars_between_touches: int,
) -> list[int]:
    touches = line.all_touch_indexes()
    for index in range(line.start_index, len(candles)):
        if any(abs(index - existing) < min_bars_between_touches for existing in touches):
            continue
        expected = line.price_at(index)
        candle = candles[index]
        if line.kind == "support":
            touched = candle.low <= expected * (1 + touch_tolerance_pct / 100) and candle.close >= expected
        else:
            touched = candle.high >= expected * (1 - touch_tolerance_pct / 100) and candle.close <= expected
        if touched:
            touches.append(index)
    pivot_indexes = {pivot.index for pivot in line.pivots}
    return sorted(index for index in touches if index not in pivot_indexes)


def trendline_is_crossed(
    line: Trendline,
    candles: list[Candle],
    break_tolerance_pct: float,
    from_index: int,
) -> bool:
    for index in range(from_index, len(candles)):
        expected = line.price_at(index)
        buffer = expected * break_tolerance_pct / 100
        candle = candles[index]
        if line.kind == "support":
            if candle.low < expected - buffer or candle.close < expected - buffer:
                return True
        else:
            if candle.high > expected + buffer or candle.close > expected + buffer:
                return True
    return False


def trendline_has_reaction(line: Trendline, candles: list[Candle], min_reaction_pct: float) -> bool:
    return any(
        reaction_from_trendline_touch(line, candles, pivot.index) >= min_reaction_pct
        for pivot in line.pivots[:-1]
    )


def reaction_from_trendline_touch(line: Trendline, candles: list[Candle], touch_index: int, lookahead: int = 24) -> float:
    start = touch_index + 1
    end = min(len(candles), start + lookahead)
    if start >= end:
        return 0.0

    touch_price = line.price_at(touch_index)
    future = candles[start:end]
    if line.kind == "support":
        best_move = max(item.high for item in future) - touch_price
    else:
        best_move = touch_price - min(item.low for item in future)
    return max(0.0, best_move / touch_price * 100)


def trendline_is_active_approach(line: Trendline, candles: list[Candle], break_tolerance_pct: float) -> bool:
    current = candles[-1].close
    current_line = line.price_at(len(candles) - 1)
    buffer = current_line * break_tolerance_pct / 100
    if line.kind == "support":
        return current >= current_line - buffer
    return current <= current_line + buffer


def dedupe_trendlines(lines: list[Trendline], tolerance_pct: float) -> list[Trendline]:
    kept: list[Trendline] = []
    for line in sorted(lines, key=lambda item: (item.touches, item.span_bars), reverse=True):
        duplicate = False
        for existing in kept:
            start_distance = abs(line.price_at(line.start_index) - existing.price_at(line.start_index)) / line.price_at(line.start_index) * 100
            end_distance = abs(line.price_at(line.end_index) - existing.price_at(line.end_index)) / line.price_at(line.end_index) * 100
            if line.kind == existing.kind and start_distance <= tolerance_pct and end_distance <= tolerance_pct:
                duplicate = True
                break
        if not duplicate:
            kept.append(line)
    return kept


def cluster_levels(
    pivots: Iterable[Pivot],
    tolerance_pct: float,
    min_touches: int,
    min_bars_between_touches: int,
    min_level_span_bars: int,
    min_reaction_pct: float,
    candles: list[Candle],
) -> list[Level]:
    grouped: dict[str, list[Level]] = {"support": [], "resistance": []}
    for pivot in sorted(pivots, key=lambda item: item.price):
        levels = grouped[pivot.kind]
        matched = None
        for level in levels:
            if abs(pivot.price - level.price) / level.price * 100 <= tolerance_pct:
                matched = level
                break
        if matched is None:
            levels.append(Level(price=pivot.price, kind=pivot.kind, pivots=[pivot]))
            continue

        if all(abs(pivot.index - existing.index) >= min_bars_between_touches for existing in matched.pivots):
            matched.pivots.append(pivot)
            matched.price = sum(item.price for item in matched.pivots) / len(matched.pivots)

    return [
        level
        for levels in grouped.values()
        for level in levels
        if level.touches >= min_touches
        and level.last_index - level.first_index >= min_level_span_bars
        and max_reaction_from_touches(level, candles) >= min_reaction_pct
    ]


def max_reaction_from_touches(level: Level, candles: list[Candle], lookahead: int = 24) -> float:
    reactions = [reaction_from_touch(level, candles, pivot.index, lookahead) for pivot in level.pivots]
    return max(reactions, default=0.0)


def reaction_from_touch(level: Level, candles: list[Candle], touch_index: int, lookahead: int = 24) -> float:
    start = touch_index + 1
    end = min(len(candles), start + lookahead)
    if start >= end:
        return 0.0

    price = level.price
    future = candles[start:end]
    if level.kind == "resistance":
        best_move = price - min(item.low for item in future)
    else:
        best_move = max(item.high for item in future) - price
    return max(0.0, best_move / price * 100)


def score_level(level: Level, candles: list[Candle], current_price: float) -> float:
    distance_pct = abs(current_price - level.price) / current_price * 100
    age_bonus = level.last_index / max(1, len(candles) - 1)
    reaction = reaction_after_last_touch(level, candles)
    return level.touches * 10 + age_bonus * 3 + min(reaction, 20) * 0.3 - distance_pct * 2


def reaction_after_last_touch(level: Level, candles: list[Candle], lookahead: int = 12) -> float:
    start = level.last_index
    end = min(len(candles), start + lookahead + 1)
    if start >= len(candles) - 1:
        return 0.0

    price = level.price
    future = candles[start + 1 : end]
    if level.kind == "resistance":
        best_move = price - min(item.low for item in future)
    else:
        best_move = max(item.high for item in future) - price
    return max(0.0, best_move / price * 100)


def select_levels(
    levels: list[Level],
    candles: list[Candle],
    max_distance_pct: float,
    max_levels: int,
    break_tolerance_pct: float,
    recent_bars: int,
) -> list[Level]:
    current_price = candles[-1].close
    nearby = [
        level
        for level in levels
        if is_active_approach(level, candles, break_tolerance_pct, recent_bars)
        and abs(current_price - level.price) / current_price * 100 <= max_distance_pct
    ]
    return sorted(
        nearby,
        key=lambda level: score_level(level, candles, current_price),
        reverse=True,
    )[:max_levels]


def is_active_approach(
    level: Level,
    candles: list[Candle],
    break_tolerance_pct: float,
    recent_bars: int,
) -> bool:
    current_price = candles[-1].close
    buffer = level.price * break_tolerance_pct / 100

    if level.kind == "support" and current_price < level.price - buffer:
        return False
    if level.kind == "resistance" and current_price > level.price + buffer:
        return False

    start = level.last_index + 1
    if start >= len(candles):
        return True

    after_touch = candles[start:]
    if level.kind == "support":
        if any(candle.close < level.price - buffer or candle.low < level.price - buffer for candle in after_touch):
            return False
    else:
        if any(candle.close > level.price + buffer or candle.high > level.price + buffer for candle in after_touch):
            return False

    if recent_bars <= 0 or len(candles) < recent_bars + 1:
        return True

    recent = candles[-recent_bars:]
    if level.kind == "support":
        # For support we want the last move to be coming down toward the level.
        return recent[0].close >= recent[-1].close
    # For resistance we want the last move to be coming up toward the level.
    return recent[0].close <= recent[-1].close


def plot_levels(
    candles: list[Candle],
    levels: list[Level],
    symbol: str,
    interval: str,
    output: Path,
) -> None:
    dates = [mdates.date2num(candle.open_time) for candle in candles]
    candle_width = (dates[1] - dates[0]) * 0.65 if len(dates) > 1 else 0.01

    fig, ax = plt.subplots(figsize=(12, 6.4), dpi=140)
    fig.patch.set_facecolor("white")
    ax.set_facecolor("white")
    ax.grid(True, color="#e8eef2", linewidth=0.8)

    for x_value, candle in zip(dates, candles):
        color = "#089981" if candle.close >= candle.open else "#f23645"
        ax.vlines(x_value, candle.low, candle.high, color=color, linewidth=0.8, alpha=0.9)
        body_low = min(candle.open, candle.close)
        body_height = max(abs(candle.close - candle.open), candle.close * 0.0001)
        ax.add_patch(
            plt.Rectangle(
                (x_value - candle_width / 2, body_low),
                candle_width,
                body_height,
                facecolor=color,
                edgecolor=color,
                linewidth=0.6,
                alpha=0.88,
            )
        )

    palette = {"support": "#1f77b4", "resistance": "#f0a202"}
    label_offsets = _label_offsets(levels)
    for level in levels:
        color = palette[level.kind]
        x_start = dates[max(0, level.first_index - 2)]
        x_end = dates[-1] + candle_width * 8
        ax.hlines(level.price, x_start, x_end, color=color, linewidth=2.1)
        touch_dates = [dates[pivot.index] for pivot in level.pivots]
        touch_prices = [pivot.price for pivot in level.pivots]
        ax.scatter(touch_dates, touch_prices, s=34, facecolors="white", edgecolors=color, linewidths=1.6, zorder=5)
        label = f"{level.kind.upper()} | touches {level.touches} | {level.price:.8g}"
        label_y = level.price + label_offsets.get(id(level), 0.0)
        ax.text(
            x_end,
            label_y,
            " " + label,
            va="center",
            ha="left",
            fontsize=8,
            color="#1f2933",
            bbox={"facecolor": "white", "edgecolor": color, "boxstyle": "round,pad=0.25", "alpha": 0.9},
        )

    ax.set_title(f"{symbol.upper()} / USDT - {interval} - Binance Futures", loc="left", fontsize=14, fontweight="bold")
    ax.set_ylabel("Price")
    ax.xaxis_date()
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%b %d %H:%M", tz=timezone.utc))
    fig.autofmt_xdate(rotation=35, ha="right")
    ax.margins(x=0.02, y=0.08)
    fig.tight_layout()
    output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output)
    plt.close(fig)


def plot_trendlines(
    candles: list[Candle],
    lines: list[Trendline],
    symbol: str,
    interval: str,
    output: Path,
    context_bars: int = 80,
    right_padding_bars: int = 20,
    show_touch_markers: bool = False,
) -> None:
    visible_start = 0
    if lines:
        visible_start = max(0, min(line.start_index for line in lines) - context_bars)
    visible_end = len(candles) - 1

    dates = [mdates.date2num(candle.open_time) for candle in candles]
    candle_width = (dates[1] - dates[0]) * 0.65 if len(dates) > 1 else 0.01

    fig, ax = plt.subplots(figsize=(10.8, 5.8), dpi=150)
    fig.patch.set_facecolor("#0b0f14")
    ax.set_facecolor("#0b0f14")
    ax.grid(True, color="#1f2937", linewidth=0.8, alpha=0.75)

    for index in range(visible_start, visible_end + 1):
        x_value = dates[index]
        candle = candles[index]
        color = "#00c087" if candle.close >= candle.open else "#ff4d5a"
        ax.vlines(x_value, candle.low, candle.high, color=color, linewidth=0.8, alpha=0.9)
        body_low = min(candle.open, candle.close)
        body_height = max(abs(candle.close - candle.open), candle.close * 0.0001)
        ax.add_patch(
            plt.Rectangle(
                (x_value - candle_width / 2, body_low),
                candle_width,
                body_height,
                facecolor=color,
                edgecolor=color,
                linewidth=0.6,
                alpha=0.88,
            )
        )

    palette = {"support": "#2f9bff", "resistance": "#f7a600", "ath_resistance": "#f7a600"}
    for line in lines:
        color = palette[line.kind]
        draw_start = max(visible_start, line.start_index)
        draw_end = visible_end
        x_values = [dates[draw_start], dates[draw_end] + candle_width * 8]
        y_values = [line.price_at(draw_start), line.price_at(draw_end + 4)]
        ax.plot(x_values, y_values, color=color, linewidth=2.1)

        if show_touch_markers:
            touch_indexes = [index for index in line.all_touch_indexes() if visible_start <= index <= visible_end]
            touch_dates = [dates[index] for index in touch_indexes]
            touch_prices = [line.price_at(index) for index in touch_indexes]
            ax.scatter(touch_dates, touch_prices, s=28, facecolors="white", edgecolors=color, linewidths=1.4, zorder=5)

    ax.set_title(f"{symbol.upper()} - {interval}", loc="left", fontsize=15, fontweight="bold", pad=10, color="#f8fafc")
    ax.set_ylabel("")
    tick_count = min(5, max(2, visible_end - visible_start + 1))
    tick_indexes = sorted(
        {
            min(visible_end, visible_start + round(i * (visible_end - visible_start) / max(1, tick_count - 1)))
            for i in range(tick_count)
        }
    )
    visible_hours = (candles[visible_end].open_time - candles[visible_start].open_time).total_seconds() / 3600
    if visible_hours <= 30:
        tick_labels = [candles[index].open_time.strftime("%H:%M") for index in tick_indexes]
    elif visible_hours <= 96:
        tick_labels = [candles[index].open_time.strftime("%b %d\n%H:%M") for index in tick_indexes]
    else:
        tick_labels = [candles[index].open_time.strftime("%b %d") for index in tick_indexes]
    ax.set_xticks([dates[index] for index in tick_indexes])
    ax.set_xticklabels(tick_labels)
    ax.set_xlim(dates[visible_start], dates[visible_end] + candle_width * right_padding_bars)
    ax.tick_params(axis="x", labelsize=8, colors="#9ca3af", pad=4)
    ax.tick_params(axis="y", labelsize=8, colors="#9ca3af", pad=4)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.spines["left"].set_color("#334155")
    ax.spines["bottom"].set_color("#334155")
    fig.autofmt_xdate(rotation=0, ha="center")
    ax.margins(x=0.02, y=0.08)
    fig.tight_layout()
    output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output)
    plt.close(fig)


def _label_offsets(levels: list[Level]) -> dict[int, float]:
    if len(levels) <= 1:
        return {}
    prices = [level.price for level in levels]
    span = max(prices) - min(prices)
    if span <= 0:
        span = max(prices) * 0.01
    sorted_levels = sorted(levels, key=lambda level: level.price)
    offsets: dict[int, float] = {}
    last_label_y = -float("inf")
    min_gap = max(span * 0.16, (sum(prices) / len(prices)) * 0.006)
    for level in sorted_levels:
        label_y = level.price
        if label_y - last_label_y < min_gap:
            label_y = last_label_y + min_gap
        offsets[id(level)] = label_y - level.price
        last_label_y = label_y
    return offsets


def build_summary(levels: list[Level], candles: list[Candle], symbol: str, interval: str) -> dict:
    current = candles[-1].close
    return {
        "symbol": symbol.upper(),
        "interval": interval,
        "current_price": current,
        "levels": [
            {
                "kind": level.kind,
                "price": level.price,
                "touches": level.touches,
                "distance_pct": abs(current - level.price) / current * 100,
                "reaction_after_last_touch_pct": reaction_after_last_touch(level, candles),
                "touch_indexes": [pivot.index for pivot in sorted(level.pivots, key=lambda item: item.index)],
            }
            for level in levels
        ],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Draw support/resistance levels by pivot touches.")
    parser.add_argument("--symbol", default="GWEIUSDT", help="Binance Futures symbol, e.g. BTCUSDT")
    parser.add_argument("--interval", default="15m", help="Binance kline interval, e.g. 1m, 5m, 15m, 1h")
    parser.add_argument("--limit", type=int, default=300, help="Number of candles to load")
    parser.add_argument("--pivot-window", type=int, default=3, help="Candles on each side required for a pivot")
    parser.add_argument("--tolerance-pct", type=float, default=0.45, help="Max percent distance between touches")
    parser.add_argument("--min-touches", type=int, default=2, help="Minimum touches required for a level")
    parser.add_argument("--min-bars-between-touches", type=int, default=16, help="Ignore repeated touches too close in time")
    parser.add_argument("--min-level-span-bars", type=int, default=48, help="Minimum bars between first and last touch")
    parser.add_argument("--min-reaction-pct", type=float, default=1.5, help="Minimum reaction from at least one touch")
    parser.add_argument("--max-distance-pct", type=float, default=1.2, help="Draw only levels near current price")
    parser.add_argument("--max-levels", type=int, default=4, help="Max levels to draw")
    parser.add_argument("--break-tolerance-pct", type=float, default=0.15, help="Invalidate a level after a close/wick crosses it")
    parser.add_argument("--recent-bars", type=int, default=8, help="Require recent price movement toward the level")
    parser.add_argument("--output", default="market_scanner/output/level_scan.png", help="Output PNG path")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    candles = fetch_klines(args.symbol, args.interval, args.limit)
    if len(candles) < args.pivot_window * 2 + 10:
        raise SystemExit("Not enough candles for the selected pivot window.")

    pivots = find_pivots(candles, args.pivot_window)
    levels = cluster_levels(
        pivots,
        tolerance_pct=args.tolerance_pct,
        min_touches=args.min_touches,
        min_bars_between_touches=args.min_bars_between_touches,
        min_level_span_bars=args.min_level_span_bars,
        min_reaction_pct=args.min_reaction_pct,
        candles=candles,
    )
    selected = select_levels(
        levels,
        candles,
        args.max_distance_pct,
        args.max_levels,
        args.break_tolerance_pct,
        args.recent_bars,
    )
    if not selected:
        raise SystemExit("No levels found. Try increasing --tolerance-pct or --max-distance-pct.")

    output = Path(args.output)
    plot_levels(candles, selected, args.symbol, args.interval, output)
    print(json.dumps(build_summary(selected, candles, args.symbol, args.interval), indent=2))
    print(f"Saved chart: {output.resolve()}")


if __name__ == "__main__":
    main()
