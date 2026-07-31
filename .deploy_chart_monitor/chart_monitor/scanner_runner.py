from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from level_scanner import (
    Pivot,
    Trendline,
    avg_touch_error_pct,
    fetch_klines,
    fetch_klines_history,
    find_pivots,
    find_trendlines,
    max_touch_error_pct,
    plot_trendlines,
)
from followup_store import upsert_signal
from market_filter import filter_symbols, preset_for_interval
from telegram_publisher import TelegramPublisher


PRESETS = {
    "1m_scalp": {
        "interval": "1m",
        "days": 3,
        "limit_candles": 0,
        "pivot_window": 4,
        "min_bars_between_touches": 80,
        "min_span_bars": 360,
        "max_distance_pct": 1.2,
        "filter_preset": "scalp_1m",
    },
    "5m_scalp": {
        "interval": "5m",
        "days": 10,
        "limit_candles": 0,
        "pivot_window": 4,
        "min_bars_between_touches": 48,
        "min_span_bars": 288,
        "max_distance_pct": 2.0,
        "filter_preset": "scalp_5m",
        "horizons": [
            {
                "name": "intraday",
                "days": 0,
                "limit_candles": 240,
                "min_bars_between_touches": 24,
                "min_span_bars": 96,
                "max_distance_pct": 1.6,
            },
            {
                "name": "swing",
                "days": 10,
                "limit_candles": 0,
                "min_bars_between_touches": 48,
                "min_span_bars": 288,
                "max_distance_pct": 2.0,
            },
        ],
    },
    "15m_swing": {
        "interval": "15m",
        "days": 30,
        "limit_candles": 0,
        "pivot_window": 4,
        "min_bars_between_touches": 24,
        "min_span_bars": 96,
        "max_distance_pct": 2.5,
        "filter_preset": "swing_1h",
    },
    "30m_swing": {
        "interval": "30m",
        "days": 60,
        "limit_candles": 0,
        "pivot_window": 4,
        "min_bars_between_touches": 20,
        "min_span_bars": 96,
        "max_distance_pct": 2.8,
        "filter_preset": "swing_1h",
    },
    "1h_swing": {
        "interval": "1h",
        "days": 90,
        "limit_candles": 0,
        "pivot_window": 4,
        "min_bars_between_touches": 24,
        "min_span_bars": 160,
        "max_distance_pct": 3.0,
        "filter_preset": "swing_1h",
    },
    "2h_swing": {
        "interval": "2h",
        "days": 180,
        "limit_candles": 0,
        "pivot_window": 4,
        "min_bars_between_touches": 16,
        "min_span_bars": 96,
        "max_distance_pct": 3.5,
        "filter_preset": "global",
    },
    "4h_swing": {
        "interval": "4h",
        "days": 365,
        "limit_candles": 0,
        "pivot_window": 4,
        "min_bars_between_touches": 12,
        "min_span_bars": 96,
        "max_distance_pct": 4.0,
        "filter_preset": "global",
    },
}


def load_sent_keys(path: Path) -> set[str]:
    if not path.exists():
        return set()
    keys = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        key = item.get("dedupe_key")
        if key:
            keys.add(key)
    return keys


def load_sent_events(path: Path, max_events: int = 1000) -> list[dict]:
    if not path.exists():
        return []
    events = []
    for line in path.read_text(encoding="utf-8").splitlines()[-max_events:]:
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return events


def append_jsonl(path: Path, item: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(item, ensure_ascii=False) + "\n")


def parse_dt(value: str) -> datetime | None:
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
    except (TypeError, ValueError):
        return None


def dedupe_key(symbol: str, interval: str, kind: str, line, candles) -> str:
    if kind == "ath_resistance":
        ath_time = candles[line.pivots[0].index].open_time.isoformat()
        return f"{symbol}:{interval}:{kind}:{line.pivots[0].price:.12g}:{ath_time}"
    touch_indexes = line.all_touch_indexes()
    if not touch_indexes:
        return f"{symbol}:{interval}:{kind}:none"
    touch_times = [candles[index].open_time.isoformat() for index in touch_indexes[-3:]]
    anchor = "|".join(touch_times)
    return f"{symbol}:{interval}:{kind}:{anchor}"


def safe_chart_name(item: dict, key: str) -> str:
    digest = hashlib.sha1(key.encode("utf-8")).hexdigest()[:10]
    return f"{item['symbol']}_{item['interval']}_{item['kind']}_{digest}.png"


def signal_id_for(key: str) -> str:
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:16]


def followup_button(signal_id: str) -> dict:
    return {
        "inline_keyboard": [
            [{"text": "Отследить", "callback_data": f"track:{signal_id}"}],
        ]
    }


def line_snapshot(item: dict, key: str, chart_path: Path) -> dict:
    line = item["line"]
    candles = item["candles"]
    pivots = sorted(line.pivots, key=lambda pivot: pivot.index)
    return {
        "id": signal_id_for(key),
        "dedupe_key": key,
        "symbol": item["symbol"],
        "interval": item["interval"],
        "horizon": item.get("horizon", ""),
        "kind": item["kind"],
        "created_at": datetime.now(tz=timezone.utc).isoformat(),
        "current_price": item["current_price"],
        "current_line_price": item["current_line_price"],
        "chart": str(chart_path),
        "touches": [
            {
                "open_time": candles[pivot.index].open_time.isoformat(),
                "price": pivot.price,
                "kind": pivot.kind,
            }
            for pivot in pivots
        ],
        "candle_touches": [
            candles[index].open_time.isoformat()
            for index in line.all_touch_indexes()
            if index < len(candles)
        ],
        "source": {
            "days": item.get("source_days", 0),
            "limit_candles": item.get("source_limit_candles", 0),
        },
    }


def store_followup_signal(path: Path, item: dict, key: str, chart_path: Path) -> str:
    signal_id = signal_id_for(key)
    upsert_signal(path, signal_id, line_snapshot(item, key, chart_path))
    return signal_id


def caption_for(item: dict) -> str:
    if item.get("pattern") == "ath_resistance":
        return f"<b>{item['symbol']} - {item['interval']}</b>\nATH"
    return f"<b>{item['symbol']} - {item['interval']}</b>"


def ath_signal(symbol: str, interval: str, current_candles: list, args: argparse.Namespace) -> dict | None:
    daily = fetch_klines_history(symbol, "1d", args.ath_lookback_days)
    if len(daily) < args.ath_min_age_days + 5:
        return None

    current_price = current_candles[-1].close
    cutoff = datetime.now(tz=timezone.utc) - timedelta(days=args.ath_min_age_days)
    old_candles = [candle for candle in daily if candle.open_time <= cutoff]
    if not old_candles:
        return None

    ath_candle = max(old_candles, key=lambda candle: candle.high)
    ath_price = ath_candle.high
    if current_price > ath_price:
        return None

    distance_pct = (ath_price - current_price) / current_price * 100
    if distance_pct > args.ath_max_distance_pct:
        return None

    ath_index = next((index for index, candle in enumerate(current_candles) if candle.open_time >= ath_candle.open_time), 0)
    current_index = len(current_candles) - 1
    line = Trendline(
        kind="resistance",
        start_index=ath_index,
        end_index=current_index,
        slope=0.0,
        intercept=ath_price,
        pivots=[
            Pivot(index=ath_index, price=ath_price, kind="resistance"),
            Pivot(index=current_index, price=ath_price, kind="resistance"),
        ],
        candle_touches=[ath_index],
    )
    return {
        "symbol": symbol,
        "interval": interval,
        "horizon": "ath",
        "source_days": args.ath_lookback_days,
        "source_limit_candles": 0,
        "pattern": "ath_resistance",
        "kind": "ath_resistance",
        "touches": 1,
        "visual_touches": 1,
        "span_bars": current_index - ath_index,
        "distance_pct": distance_pct,
        "current_price": current_price,
        "current_line_price": ath_price,
        "ath_price": ath_price,
        "ath_age_days": (datetime.now(tz=timezone.utc) - ath_candle.open_time).days,
        "avg_touch_error_pct": 0.0,
        "max_touch_error_pct": 0.0,
        "line": line,
        "candles": current_candles,
    }


def scan_horizons(args: argparse.Namespace, preset: dict) -> list[dict]:
    has_cli_override = any(
        value is not None
        for value in (
            args.days,
            args.limit_candles,
            args.min_bars_between_touches,
            args.min_span_bars,
            args.max_distance_pct,
        )
    )
    if has_cli_override:
        return [
            {
                "name": "custom",
                "days": args.days if args.days is not None else preset["days"],
                "limit_candles": args.limit_candles if args.limit_candles is not None else preset["limit_candles"],
                "min_bars_between_touches": args.min_bars_between_touches
                if args.min_bars_between_touches is not None
                else preset["min_bars_between_touches"],
                "min_span_bars": args.min_span_bars if args.min_span_bars is not None else preset["min_span_bars"],
                "max_distance_pct": args.max_distance_pct if args.max_distance_pct is not None else preset["max_distance_pct"],
            }
        ]
    return preset.get(
        "horizons",
        [
            {
                "name": "default",
                "days": preset["days"],
                "limit_candles": preset["limit_candles"],
                "min_bars_between_touches": preset["min_bars_between_touches"],
                "min_span_bars": preset["min_span_bars"],
                "max_distance_pct": preset["max_distance_pct"],
            }
        ],
    )


def result_score(item: dict) -> tuple[float, float, float, float, float]:
    capped_span = min(item["span_bars"], 288)
    touches_score = 3.5 if item.get("pattern") == "ath_resistance" else item["touches"]
    return (
        touches_score,
        -item["avg_touch_error_pct"],
        -item["max_touch_error_pct"],
        -item["distance_pct"],
        capped_span,
    )


def is_similar_recent_event(item: dict, event: dict, now: datetime, cooldown_minutes: int, line_tolerance_pct: float) -> bool:
    created_at = parse_dt(event.get("created_at", ""))
    if created_at is None or now - created_at > timedelta(minutes=cooldown_minutes):
        return False
    if event.get("symbol") != item["symbol"] or event.get("interval") != item["interval"]:
        return False
    if event.get("kind") != item["kind"]:
        return False

    old_line_price = float(event.get("current_line_price") or 0)
    new_line_price = float(item.get("current_line_price") or 0)
    if old_line_price <= 0 or new_line_price <= 0:
        return False
    line_distance_pct = abs(new_line_price - old_line_price) / new_line_price * 100
    return line_distance_pct <= line_tolerance_pct


def should_skip_recent_duplicate(item: dict, sent_events: list[dict], now: datetime, args: argparse.Namespace) -> bool:
    return any(
        is_similar_recent_event(
            item,
            event,
            now,
            cooldown_minutes=args.symbol_cooldown_minutes,
            line_tolerance_pct=args.line_dedupe_tolerance_pct,
        )
        for event in sent_events
    )


def scan_once(args: argparse.Namespace) -> list[dict]:
    preset = PRESETS[args.preset].copy()
    interval = args.interval or preset["interval"]
    filter_preset = args.filter_preset or preset["filter_preset"] or preset_for_interval(interval)
    horizons = scan_horizons(args, preset)

    if args.symbols:
        symbols = [item.strip().upper() for item in args.symbols.split(",") if item.strip()]
    else:
        market_rows = filter_symbols(filter_preset, limit=args.limit_symbols)
        symbols = [item["symbol"] for item in market_rows]
        print(f"market_filter={filter_preset} symbols={','.join(symbols)}")

    results = []
    for symbol in symbols:
        ath_candles = None
        for horizon in horizons:
            try:
                limit_candles = horizon["limit_candles"]
                candles = (
                    fetch_klines_history(symbol, interval, horizon["days"], limit=limit_candles if limit_candles > 0 else None)
                    if horizon["days"] > 0
                    else fetch_klines(symbol, interval, limit_candles)
                )
                if horizon["name"] in {"swing", "default", "custom"}:
                    ath_candles = candles
                pivots = find_pivots(candles, args.pivot_window or preset["pivot_window"])
                lines = []
                for kind in ("support", "resistance"):
                    lines.extend(
                        find_trendlines(
                            pivots=pivots,
                            candles=candles,
                            kind=kind,
                            min_touches=args.min_touches,
                            min_span_bars=horizon["min_span_bars"],
                            min_bars_between_touches=horizon["min_bars_between_touches"],
                            touch_tolerance_pct=args.touch_tolerance_pct,
                            max_avg_touch_error_pct=args.max_avg_touch_error_pct,
                            break_tolerance_pct=args.break_tolerance_pct,
                            min_reaction_pct=args.min_reaction_pct,
                            min_slope_pct=args.min_slope_pct,
                            max_slope_pct=args.max_slope_pct,
                            max_results=2,
                        )
                    )
                for line in lines:
                    current_price = candles[-1].close
                    current_line_price = line.price_at(len(candles) - 1)
                    distance_pct = abs(current_price - current_line_price) / current_price * 100
                    if distance_pct > horizon["max_distance_pct"]:
                        continue
                    results.append(
                        {
                            "symbol": symbol,
                            "interval": interval,
                            "horizon": horizon["name"],
                            "source_days": horizon["days"],
                            "source_limit_candles": horizon["limit_candles"],
                            "kind": line.kind,
                            "touches": line.touches,
                            "visual_touches": len(line.all_touch_indexes()),
                            "span_bars": line.span_bars,
                            "distance_pct": distance_pct,
                            "current_price": current_price,
                            "current_line_price": current_line_price,
                            "avg_touch_error_pct": avg_touch_error_pct(line),
                            "max_touch_error_pct": max_touch_error_pct(line),
                            "line": line,
                            "candles": candles,
                        }
                    )
            except Exception as exc:
                print(f"{symbol}/{horizon['name']}: skipped ({exc})")
        if args.scan_ath:
            try:
                if ath_candles is None:
                    ath_candles = fetch_klines_history(symbol, interval, max(10, args.days or preset["days"]))
                item = ath_signal(symbol, interval, ath_candles, args)
                if item:
                    results.append(item)
            except Exception as exc:
                print(f"{symbol}/ath: skipped ({exc})")

    results.sort(key=result_score, reverse=True)
    return results[: args.max_results]


def run(args: argparse.Namespace) -> None:
    output_dir = Path(args.output_dir)
    log_path = Path(args.log_path)
    sent_keys = load_sent_keys(log_path)
    sent_events = load_sent_events(log_path)
    publisher = TelegramPublisher() if args.send_telegram else None
    signals_thread_id = os.getenv("TELEGRAM_SIGNALS_THREAD_ID", "").strip()

    while True:
        results = scan_once(args)
        print(json.dumps([{k: v for k, v in item.items() if k not in {"line", "candles"}} for item in results], indent=2))
        for item in results:
            now = datetime.now(tz=timezone.utc)
            key = dedupe_key(item["symbol"], item["interval"], item["kind"], item["line"], item["candles"])
            if key in sent_keys:
                continue
            if should_skip_recent_duplicate(item, sent_events, now, args):
                print(f"{item['symbol']}: skipped recent similar {item['kind']} line", flush=True)
                continue
            chart_path = output_dir / safe_chart_name(item, key)
            plot_trendlines(
                item["candles"],
                [item["line"]],
                item["symbol"],
                item["interval"],
                chart_path,
                context_bars=args.context_bars,
                right_padding_bars=args.right_padding_bars,
            )
            event = {k: v for k, v in item.items() if k not in {"line", "candles"}}
            event["dedupe_key"] = key
            event["chart"] = str(chart_path)
            event["created_at"] = now.isoformat()
            if publisher:
                signal_id = store_followup_signal(Path(args.followup_state_path), item, key, chart_path)
                publisher.send_photo(
                    chart_path,
                    caption_for(item),
                    reply_markup=followup_button(signal_id),
                    message_thread_id=signals_thread_id or None,
                )
                event["signal_id"] = signal_id
                event["sent_telegram"] = True
            append_jsonl(log_path, event)
            sent_keys.add(key)
            sent_events.append(event)
            sent_events = sent_events[-1000:]

        if args.once:
            break
        time.sleep(args.sleep_seconds)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run market scanner and optionally publish charts to Telegram.")
    parser.add_argument("--preset", choices=sorted(PRESETS), default="5m_scalp")
    parser.add_argument("--symbols", default="")
    parser.add_argument("--interval", default="")
    parser.add_argument("--filter-preset", default="")
    parser.add_argument("--days", type=int, default=None)
    parser.add_argument("--limit-candles", type=int, default=None)
    parser.add_argument("--limit-symbols", type=int, default=30)
    parser.add_argument("--max-results", type=int, default=5)
    parser.add_argument("--min-touches", type=int, default=3)
    parser.add_argument("--pivot-window", type=int, default=None)
    parser.add_argument("--min-bars-between-touches", type=int, default=None)
    parser.add_argument("--min-span-bars", type=int, default=None)
    parser.add_argument("--touch-tolerance-pct", type=float, default=0.18)
    parser.add_argument("--max-avg-touch-error-pct", type=float, default=0.08)
    parser.add_argument("--break-tolerance-pct", type=float, default=0.12)
    parser.add_argument("--min-reaction-pct", type=float, default=2.0)
    parser.add_argument("--min-slope-pct", type=float, default=1.0)
    parser.add_argument("--max-slope-pct", type=float, default=45.0)
    parser.add_argument("--max-distance-pct", type=float, default=None)
    parser.add_argument("--scan-ath", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--ath-lookback-days", type=int, default=1500)
    parser.add_argument("--ath-min-age-days", type=int, default=60)
    parser.add_argument("--ath-max-distance-pct", type=float, default=3.0)
    parser.add_argument("--symbol-cooldown-minutes", type=int, default=90)
    parser.add_argument("--line-dedupe-tolerance-pct", type=float, default=2.0)
    parser.add_argument("--context-bars", type=int, default=80)
    parser.add_argument("--right-padding-bars", type=int, default=20)
    parser.add_argument("--output-dir", default="market_scanner/output/runner")
    parser.add_argument("--log-path", default="market_scanner/output/signals_log.jsonl")
    parser.add_argument("--followup-state-path", default="market_scanner/output/followups/state.json")
    parser.add_argument("--send-telegram", action="store_true")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--sleep-seconds", type=int, default=300)
    return parser.parse_args()


if __name__ == "__main__":
    run(parse_args())
