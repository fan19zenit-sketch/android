from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import requests


TELEGRAM_API = "https://api.telegram.org/bot{token}/{method}"


class TelegramPublisher:
    def __init__(self, token: str | None = None, chat_id: str | None = None) -> None:
        self.token = (token or os.getenv("TELEGRAM_BOT_TOKEN", "")).strip()
        self.chat_id = (chat_id or os.getenv("TELEGRAM_CHAT_ID", "")).strip()
        if not self.token:
            raise ValueError("TELEGRAM_BOT_TOKEN is required")
        if not self.chat_id:
            raise ValueError("TELEGRAM_CHAT_ID is required")

    def request(self, method: str, **kwargs: Any) -> dict:
        url = TELEGRAM_API.format(token=self.token, method=method)
        response = requests.post(url, timeout=35, **kwargs)
        response.raise_for_status()
        payload = response.json()
        if not payload.get("ok"):
            raise RuntimeError(f"Telegram {method} failed: {payload}")
        return payload

    def send_photo(
        self,
        image_path: Path,
        caption: str,
        reply_markup: dict | None = None,
        message_thread_id: int | str | None = None,
    ) -> dict:
        url = TELEGRAM_API.format(token=self.token, method="sendPhoto")
        data = {"chat_id": self.chat_id, "caption": caption, "parse_mode": "HTML"}
        if message_thread_id:
            data["message_thread_id"] = str(message_thread_id)
        if reply_markup:
            data["reply_markup"] = json_dumps(reply_markup)
        with image_path.open("rb") as image_file:
            response = requests.post(
                url,
                data=data,
                files={"photo": image_file},
                timeout=30,
            )
        response.raise_for_status()
        payload = response.json()
        if not payload.get("ok"):
            raise RuntimeError(f"Telegram sendPhoto failed: {payload}")
        return payload

    def get_updates(self, offset: int | None = None, timeout: int = 25) -> list[dict]:
        data: dict[str, Any] = {"timeout": timeout, "allowed_updates": json_dumps(["message", "callback_query"])}
        if offset is not None:
            data["offset"] = offset
        return self.request("getUpdates", data=data)["result"]

    def answer_callback_query(self, callback_query_id: str, text: str, show_alert: bool = False) -> dict:
        return self.request(
            "answerCallbackQuery",
            data={"callback_query_id": callback_query_id, "text": text, "show_alert": json_dumps(show_alert)},
        )

    def send_message(
        self,
        text: str,
        message_thread_id: int | str | None = None,
        reply_to_message_id: int | None = None,
    ) -> dict:
        data: dict[str, Any] = {"chat_id": self.chat_id, "text": text, "parse_mode": "HTML"}
        if message_thread_id:
            data["message_thread_id"] = str(message_thread_id)
        if reply_to_message_id:
            data["reply_to_message_id"] = reply_to_message_id
            data["allow_sending_without_reply"] = "true"
        return self.request("sendMessage", data=data)


def json_dumps(payload: dict) -> str:
    import json

    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
