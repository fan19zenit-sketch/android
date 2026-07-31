from __future__ import annotations

from telegram_publisher import TelegramPublisher


def main() -> None:
    publisher = TelegramPublisher()
    updates = publisher.get_updates(timeout=1)
    for update in updates[-20:]:
        message = update.get("message") or update.get("channel_post") or update.get("edited_message") or {}
        chat = message.get("chat") or {}
        thread_id = message.get("message_thread_id")
        text = message.get("text") or message.get("caption") or ""
        title = chat.get("title") or chat.get("username") or chat.get("first_name") or ""
        print(
            f"update_id={update.get('update_id')} "
            f"chat_id={chat.get('id')} "
            f"thread_id={thread_id} "
            f"title={title!r} "
            f"text={text!r}"
        )


if __name__ == "__main__":
    main()
