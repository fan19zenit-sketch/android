from __future__ import annotations

import argparse
import hashlib
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from followup_store import load_state, save_state
from level_scanner import Candle, Pivot, Trendline, fetch_klines, fetch_klines_history, plot_trendlines
from telegram_publisher import TelegramPublisher


def parse_dt(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def interval_minutes(interval: str) -> int:
    if interval.endswith("m"):
        return int(interval[:-1])
    if interval.endswith("h"):
        return int(interval[:-1]) * 60
    if interval.endswith("d"):
        return int(interval[:-1]) * 24 * 60
    return 5


def callback_action(data: str, default_delay_seconds: int) -> tuple[str, int] | None:
    if ":" not in data:
        return None
    action, signal_id = data.split(":", 1)
    signal_id = signal_id.strip()
    if not signal_id:
        return None
    delays = {
        "follow": default_delay_seconds,
        "follow60": 3600,
        "follow30": 1800,
        "after15": 900,
        "after30": 1800,
        "after60": 3600,
        "now": 0,
    }
    if action not in delays:
        return None
    return signal_id, delays[action]


def callback_track_id(data: str) -> str | None:
    if not data.startswith("track:"):
        return None
    signal_id = data.split(":", 1)[1].strip()
    return signal_id or None


def schedule_job(state_path: Path, signal_id: str, delay_seconds: int) -> bool:
    state = load_state(state_path)
    if signal_id not in state["signals"]:
        return False
    if signal_id in state["jobs"] and state["jobs"][signal_id].get("status") in {"waiting", "running"}:
        return True

    now = datetime.now(tz=timezone.utc)
    state["jobs"][signal_id] = {
        "signal_id": signal_id,
        "status": "waiting",
        "requested_at": now.isoformat(),
        "due_at": (now + timedelta(seconds=delay_seconds)).isoformat(),
        "delay_seconds": delay_seconds,
    }
    save_state(state_path, state)
    return True


def schedule_watch(state_path: Path, signal_id: str) -> bool:
    state = load_state(state_path)
    if signal_id not in state["signals"]:
        return False
    watches = state.setdefault("watches", {})
    if signal_id in watches and watches[signal_id].get("status") == "watching":
        return True
    now = datetime.now(tz=timezone.utc)
    watches[signal_id] = {
        "signal_id": signal_id,
        "status": "watching",
        "created_at": now.isoformat(),
        "last_checked_at": None,
    }
    save_state(state_path, state)
    return True


def signal_title(signal: dict) -> str:
    return f"{signal['symbol']} - {signal['interval']}"


def visible_schedule_text(signal: dict, delay_seconds: int) -> str:
    if delay_seconds <= 0:
        return f"\u041f\u0440\u043e\u0432\u0435\u0440\u044f\u044e \u0441\u0435\u0439\u0447\u0430\u0441: <b>{signal_title(signal)}</b>"
    minutes = max(1, delay_seconds // 60)
    return f"\u041e\u0442\u0440\u0430\u0431\u043e\u0442\u043a\u0430 \u0437\u0430\u043f\u043b\u0430\u043d\u0438\u0440\u043e\u0432\u0430\u043d\u0430: <b>{signal_title(signal)}</b> \u0447\u0435\u0440\u0435\u0437 {minutes}\u043c"


def visible_watch_text(signal: dict) -> str:
    return f"\u041e\u0442\u0441\u043b\u0435\u0436\u0438\u0432\u0430\u044e \u043a\u0430\u0441\u0430\u043d\u0438\u0435: <b>{signal_title(signal)}</b>"


def find_candle_index(candles: list[Candle], open_time: datetime) -> int | None:
    for index, candle in enumerate(candles):
        if candle.open_time == open_time:
            return index
    return None


def rebuild_line(signal: dict, candles: list[Candle]) -> Trendline:
    pivots: list[Pivot] = []
    for touch in signal["touches"]:
        index = find_candle_index(candles, parse_dt(touch["open_time"]))
        if index is not None:
            pivots.append(Pivot(index=index, price=float(touch["price"]), kind=touch.get("kind", signal["kind"])))
    if len(pivots) < 2:
        raise RuntimeError("Not enough original touches in fetched candles")

    pivots = sorted(pivots, key=lambda pivot: pivot.index)
    first = pivots[0]
    last = pivots[-1]
    slope = (last.price - first.price) / max(1, last.index - first.index)
    intercept = first.price - slope * first.index

    candle_touches = []
    for value in signal.get("candle_touches", []):
        index = find_candle_index(candles, parse_dt(value))
        if index is not None:
            candle_touches.append(index)

    return Trendline(
        kind="resistance" if signal["kind"] == "ath_resistance" else signal["kind"],
        start_index=first.index,
        end_index=last.index,
        slope=slope,
        intercept=intercept,
        pivots=pivots,
        candle_touches=candle_touches,
    )


def fetch_followup_candles(signal: dict, delay_seconds: int) -> list[Candle]:
    interval = signal["interval"]
    extra_bars = max(24, int(delay_seconds / 60 / max(1, interval_minutes(interval))) + 24)
    return fetch_klines(signal["symbol"], interval, max(extra_bars, 320))


def line_params_by_time(signal: dict) -> tuple[str, float, float]:
    touches = sorted(signal["touches"], key=lambda item: parse_dt(item["open_time"]))
    first = touches[0]
    last = touches[-1]
    first_time = parse_dt(first["open_time"]).timestamp()
    last_time = parse_dt(last["open_time"]).timestamp()
    first_price = float(first["price"])
    last_price = float(last["price"])
    if last_time == first_time:
        slope = 0.0
    else:
        slope = (last_price - first_price) / (last_time - first_time)
    intercept = first_price - slope * first_time
    kind = "resistance" if signal["kind"] == "ath_resistance" else signal["kind"]
    return kind, slope, intercept


def line_price_at_time(signal: dict, open_time: datetime) -> float:
    _, slope, intercept = line_params_by_time(signal)
    return slope * open_time.timestamp() + intercept


def line_from_signal_on_candles(signal: dict, candles: list[Candle]) -> Trendline:
    kind, slope_by_time, intercept_by_time = line_params_by_time(signal)
    first_price = slope_by_time * candles[0].open_time.timestamp() + intercept_by_time
    last_price = slope_by_time * candles[-1].open_time.timestamp() + intercept_by_time
    span = max(1, len(candles) - 1)
    slope = (last_price - first_price) / span
    intercept = first_price
    return Trendline(
        kind=kind,
        start_index=0,
        end_index=len(candles) - 1,
        slope=slope,
        intercept=intercept,
        pivots=[
            Pivot(index=0, price=first_price, kind=kind),
            Pivot(index=len(candles) - 1, price=last_price, kind=kind),
        ],
        candle_touches=[],
    )


def line_touched_since(candles: list[Candle], line: Trendline, since: datetime) -> bool:
    for index, candle in enumerate(candles):
        if candle.open_time < since:
            continue
        line_price = line.price_at(index)
        if candle.low <= line_price <= candle.high:
            return True
        buffer = line_price * 0.001
        if line.kind == "support" and candle.low <= line_price + buffer:
            return True
        if line.kind != "support" and candle.high >= line_price - buffer:
            return True
    return False


def signal_touched_since(signal: dict, candles: list[Candle], since: datetime) -> bool:
    kind, _, _ = line_params_by_time(signal)
    for candle in candles:
        if candle.open_time < since:
            continue
        line_price = line_price_at_time(signal, candle.open_time)
        if candle.low <= line_price <= candle.high:
            return True
        buffer = line_price * 0.001
        if kind == "support" and candle.low <= line_price + buffer:
            return True
        if kind != "support" and candle.high >= line_price - buffer:
            return True
    return False


def followup_stats(signal: dict, candles: list[Candle], line: Trendline, requested_at: datetime, finished_at: datetime) -> dict:
    start_index = find_candle_index(candles, parse_dt(signal["created_at"])) or line.end_index
    requested_index = next((i for i, candle in enumerate(candles) if candle.open_time >= requested_at), start_index)
    end_index = max(requested_index, next((i for i, candle in enumerate(candles) if candle.open_time >= finished_at), len(candles) - 1))
    window = candles[requested_index : end_index + 1] or candles[start_index : end_index + 1]
    start_price = float(signal.get("current_price") or candles[start_index].close)

    if line.kind == "support":
        broken = any(candle.low < line.price_at(index) for index, candle in enumerate(candles[requested_index : end_index + 1], requested_index))
    else:
        broken = any(candle.high > line.price_at(index) for index, candle in enumerate(candles[requested_index : end_index + 1], requested_index))

    max_up = (max(candle.high for candle in window) - start_price) / start_price * 100
    max_down = (min(candle.low for candle in window) - start_price) / start_price * 100
    return {
        "broken": broken,
        "max_up_pct": max_up,
        "max_down_pct": max_down,
        "last_price": candles[-1].close,
        "line_price": line.price_at(len(candles) - 1),
    }


def caption(signal: dict, stats: dict) -> str:
    status = "\u043f\u0440\u043e\u0431\u0438\u0442\u0438\u0435: \u0434\u0430" if stats["broken"] else "\u043f\u0440\u043e\u0431\u0438\u0442\u0438\u0435: \u043d\u0435\u0442"
    return (
        f"<b>\u041e\u0442\u0440\u0430\u0431\u043e\u0442\u043a\u0430 {signal['symbol']} - {signal['interval']}</b>\n"
        f"{status}\n"
        f"max \u0432\u0432\u0435\u0440\u0445: {stats['max_up_pct']:.2f}%\n"
        f"max \u0432\u043d\u0438\u0437: {stats['max_down_pct']:.2f}%"
    )


def continue_buttons(signal_id: str) -> dict:
    return {
        "inline_keyboard": [
            [{"text": "\u0415\u0449\u0435 30\u043c", "callback_data": f"after30:{signal_id}"}],
            [{"text": "\u041f\u0440\u043e\u0432\u0435\u0440\u0438\u0442\u044c \u0441\u0435\u0439\u0447\u0430\u0441", "callback_data": f"now:{signal_id}"}],
        ]
    }


def touch_buttons(signal_id: str) -> dict:
    return {
        "inline_keyboard": [
            [
                {"text": "15\u043c", "callback_data": f"after15:{signal_id}"},
                {"text": "30\u043c", "callback_data": f"after30:{signal_id}"},
                {"text": "60\u043c", "callback_data": f"after60:{signal_id}"},
            ]
        ]
    }


def touch_caption(signal: dict) -> str:
    return f"<b>\u041a\u0430\u0441\u0430\u043d\u0438\u0435 {signal['symbol']} - {signal['interval']}</b>"


def run_due_job(state_path: Path, output_dir: Path, publisher: TelegramPublisher, signal_id: str, delay_seconds: int) -> None:
    state = load_state(state_path)
    signal = state["signals"][signal_id]
    job = state["jobs"][signal_id]
    job["status"] = "running"
    save_state(state_path, state)

    requested_at = parse_dt(job["requested_at"])
    finished_at = datetime.now(tz=timezone.utc)
    effective_delay = int(job.get("delay_seconds") or delay_seconds)
    candles = fetch_followup_candles(signal, effective_delay)
    line = line_from_signal_on_candles(signal, candles)
    stats = followup_stats(signal, candles, line, requested_at, finished_at)

    digest = hashlib.sha1(f"{signal_id}:{finished_at.isoformat()}".encode("utf-8")).hexdigest()[:10]
    chart_path = output_dir / f"{signal['symbol']}_{signal['interval']}_followup_{digest}.png"
    plot_trendlines(candles, [line], signal["symbol"], signal["interval"], chart_path, context_bars=80, right_padding_bars=20)
    followups_thread_id = os.getenv("TELEGRAM_FOLLOWUPS_THREAD_ID", "").strip()
    publisher.send_photo(
        chart_path,
        caption(signal, stats),
        reply_markup=continue_buttons(signal_id),
        message_thread_id=followups_thread_id or None,
    )

    state = load_state(state_path)
    state["jobs"][signal_id].update(
        {
            "status": "done",
            "finished_at": finished_at.isoformat(),
            "chart": str(chart_path),
            "stats": stats,
        }
    )
    save_state(state_path, state)


def process_updates(state_path: Path, publisher: TelegramPublisher, delay_seconds: int) -> None:
    state = load_state(state_path)
    offset = state.get("last_update_id")
    updates = publisher.get_updates(offset=offset + 1 if offset is not None else None)
    if not updates:
        return

    for update in updates:
        state["last_update_id"] = update["update_id"]
        callback = update.get("callback_query")
        if not callback:
            save_state(state_path, state)
            continue

        track_id = callback_track_id(callback.get("data", ""))
        if track_id:
            print(f"callback track {track_id}", flush=True)
            ok = schedule_watch(state_path, track_id)
            latest = load_state(state_path)
            signal = latest.get("signals", {}).get(track_id)
            if ok and signal:
                publisher.answer_callback_query(
                    callback["id"],
                    "\u041f\u0440\u0438\u043d\u044f\u043b, \u0436\u0434\u0443 \u043a\u0430\u0441\u0430\u043d\u0438\u044f",
                    show_alert=True,
                )
                message = callback.get("message") or {}
                try:
                    publisher.send_message(
                        visible_watch_text(signal),
                        message_thread_id=message.get("message_thread_id"),
                        reply_to_message_id=message.get("message_id"),
                    )
                except Exception as exc:
                    print(f"track confirmation message failed: {exc}", flush=True)
            else:
                publisher.answer_callback_query(
                    callback["id"],
                    "\u041d\u0435 \u043d\u0430\u0448\u0435\u043b \u044d\u0442\u043e\u0442 \u0441\u0438\u0433\u043d\u0430\u043b \u0432 \u043f\u0430\u043c\u044f\u0442\u0438",
                    show_alert=True,
                )
            latest = load_state(state_path)
            latest["last_update_id"] = update["update_id"]
            save_state(state_path, latest)
            state = latest
            continue

        action = callback_action(callback.get("data", ""), delay_seconds)
        if not action:
            save_state(state_path, state)
            continue

        signal_id, selected_delay = action
        print(f"callback schedule {signal_id} delay={selected_delay}", flush=True)
        ok = schedule_job(state_path, signal_id, selected_delay)
        latest = load_state(state_path)
        signal = latest.get("signals", {}).get(signal_id)
        if ok and signal:
            publisher.answer_callback_query(
                callback["id"],
                "\u041f\u0440\u0438\u043d\u044f\u043b, \u0437\u0430\u0434\u0430\u0447\u0430 \u043f\u043e\u0441\u0442\u0430\u0432\u043b\u0435\u043d\u0430",
                show_alert=True,
            )
            message = callback.get("message") or {}
            try:
                publisher.send_message(
                    visible_schedule_text(signal, selected_delay),
                    message_thread_id=message.get("message_thread_id"),
                    reply_to_message_id=message.get("message_id"),
                )
            except Exception as exc:
                print(f"schedule confirmation message failed: {exc}", flush=True)
        else:
            publisher.answer_callback_query(
                callback["id"],
                "\u041d\u0435 \u043d\u0430\u0448\u0435\u043b \u044d\u0442\u043e\u0442 \u0441\u0438\u0433\u043d\u0430\u043b \u0432 \u043f\u0430\u043c\u044f\u0442\u0438",
                show_alert=True,
            )

        latest = load_state(state_path)
        latest["last_update_id"] = update["update_id"]
        save_state(state_path, latest)
        state = latest


def process_due_jobs(state_path: Path, output_dir: Path, publisher: TelegramPublisher, delay_seconds: int) -> None:
    state = load_state(state_path)
    now = datetime.now(tz=timezone.utc)
    due_ids = [
        signal_id
        for signal_id, job in state["jobs"].items()
        if job.get("status") == "waiting" and parse_dt(job["due_at"]) <= now
    ]
    for signal_id in due_ids:
        try:
            run_due_job(state_path, output_dir, publisher, signal_id, delay_seconds)
        except Exception as exc:
            state = load_state(state_path)
            state["jobs"][signal_id]["status"] = "failed"
            state["jobs"][signal_id]["error"] = str(exc)
            save_state(state_path, state)


def process_watches(state_path: Path, output_dir: Path, publisher: TelegramPublisher, watch_check_seconds: int) -> None:
    state = load_state(state_path)
    now = datetime.now(tz=timezone.utc)
    watches = state.setdefault("watches", {})
    active_ids = [signal_id for signal_id, watch in watches.items() if watch.get("status") == "watching"]
    for signal_id in active_ids:
        state = load_state(state_path)
        signal = state["signals"].get(signal_id)
        watch = state["watches"].get(signal_id)
        if not signal or not watch:
            continue
        try:
            last_checked = parse_dt(watch["last_checked_at"]) if watch.get("last_checked_at") else parse_dt(watch["created_at"])
            if now - last_checked < timedelta(seconds=watch_check_seconds):
                continue
            candles = fetch_klines(signal["symbol"], signal["interval"], 120)
            if not signal_touched_since(signal, candles, last_checked):
                watch["last_checked_at"] = now.isoformat()
                save_state(state_path, state)
                continue

            digest = hashlib.sha1(f"touch:{signal_id}:{now.isoformat()}".encode("utf-8")).hexdigest()[:10]
            chart_path = output_dir / f"{signal['symbol']}_{signal['interval']}_touch_{digest}.png"
            line = line_from_signal_on_candles(signal, candles)
            plot_trendlines(candles, [line], signal["symbol"], signal["interval"], chart_path, context_bars=80, right_padding_bars=20)
            tracking_thread_id = os.getenv("TELEGRAM_TRACKING_THREAD_ID", "").strip()
            publisher.send_photo(
                chart_path,
                touch_caption(signal),
                reply_markup=touch_buttons(signal_id),
                message_thread_id=tracking_thread_id or None,
            )
            state = load_state(state_path)
            state["watches"][signal_id].update(
                {
                    "status": "touched",
                    "touched_at": now.isoformat(),
                    "touch_chart": str(chart_path),
                }
            )
            save_state(state_path, state)
        except Exception as exc:
            state = load_state(state_path)
            state["watches"][signal_id]["last_error"] = str(exc)
            state["watches"][signal_id]["last_checked_at"] = now.isoformat()
            save_state(state_path, state)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Listen Telegram buttons and send delayed follow-up charts.")
    parser.add_argument("--state-path", default="chart_monitor/output/followups/state.json")
    parser.add_argument("--output-dir", default="chart_monitor/output/followups")
    parser.add_argument("--delay-seconds", type=int, default=3600)
    parser.add_argument("--poll-seconds", type=int, default=5)
    parser.add_argument("--watch-check-seconds", type=int, default=60)
    parser.add_argument("--once", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    state_path = Path(args.state_path)
    output_dir = Path(args.output_dir)
    publisher = TelegramPublisher()
    while True:
        try:
            process_updates(state_path, publisher, args.delay_seconds)
            process_watches(state_path, output_dir, publisher, args.watch_check_seconds)
            process_due_jobs(state_path, output_dir, publisher, args.delay_seconds)
        except Exception as exc:
            print(f"followups loop skipped after error: {exc}", flush=True)
        if args.once:
            break
        time.sleep(args.poll_seconds)


if __name__ == "__main__":
    main()
