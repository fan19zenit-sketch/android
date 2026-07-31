from __future__ import annotations

import json
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Any


EMPTY_STATE = {"signals": {}, "jobs": {}, "last_update_id": None}


def load_state(path: Path) -> dict[str, Any]:
    if not path.exists():
        return dict(EMPTY_STATE)
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return dict(EMPTY_STATE)
    for key, default in EMPTY_STATE.items():
        state.setdefault(key, default)
    return state


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        json.dump(state, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
        temp_path = Path(handle.name)
    temp_path.replace(path)


def upsert_signal(path: Path, signal_id: str, signal: dict[str, Any]) -> None:
    state = load_state(path)
    state["signals"][signal_id] = signal
    save_state(path, state)
