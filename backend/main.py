from __future__ import annotations

import hashlib
import json
import logging
import mimetypes
import os
import re
import shutil
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import requests
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator

app = FastAPI()

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("photo-chat-backend")

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
UPLOAD_DIR = BASE_DIR / "uploads"
CITIES_FILE = DATA_DIR / "cities.json"
DEVICES_FILE = DATA_DIR / "devices.json"
UPLOAD_LOG_FILE = DATA_DIR / "upload_log.json"
UPLOAD_JOBS_FILE = DATA_DIR / "upload_jobs.json"
PHOTO_REVIEWS_FILE = DATA_DIR / "photo_reviews.json"
PENDING_REVIEW_REASONS_FILE = DATA_DIR / "pending_review_reasons.json"
PENDING_WEIGHT_REPORTS_FILE = DATA_DIR / "pending_weight_reports.json"
MANUAL_MAX_SYNC_STATE_FILE = DATA_DIR / "manual_max_sync_state.json"
WEBHOOK_EVENT_STATE_FILE = DATA_DIR / "webhook_event_state.json"
PENDING_MAX_DELETIONS_FILE = DATA_DIR / "pending_max_deletions.json"
ML_DATASET_DIR = DATA_DIR / "ml_dataset"
ML_DATASET_IMAGES_DIR = ML_DATASET_DIR / "images"
ML_DATASET_INDEX_FILE = ML_DATASET_DIR / "index.json"
ML_DATASET_SHEETS_SYNC_STATE_FILE = DATA_DIR / "ml_dataset_sheets_sync_state.json"
ML_DATASET_SHEETS_SPREADSHEET_ID = os.getenv("ML_DATASET_SHEETS_SPREADSHEET_ID", "").strip()
ML_DATASET_SHEETS_SERVICE_ACCOUNT_FILE = os.getenv(
    "ML_DATASET_SHEETS_SERVICE_ACCOUNT_FILE",
    "/root/image_counter_bot/service_account.json",
).strip()
WEIGHT_REPORT_SHEETS_SPREADSHEET_ID = os.getenv(
    "WEIGHT_REPORT_SHEETS_SPREADSHEET_ID",
    "1EZqlWjSEcXGpV36ODWyLwRxMw73T1s6BeJBIoIYR5OU",
).strip()
WEIGHT_REPORT_SHEETS_SERVICE_ACCOUNT_FILE = os.getenv(
    "WEIGHT_REPORT_SHEETS_SERVICE_ACCOUNT_FILE",
    ML_DATASET_SHEETS_SERVICE_ACCOUNT_FILE,
).strip()
WEIGHT_REPORT_SHEET_NAME = os.getenv("WEIGHT_REPORT_SHEET_NAME", "Отчет по весам").strip()
MAX_WEIGHT_ALLOWED_USER_IDS = {
    item.strip()
    for item in os.getenv("MAX_WEIGHT_ALLOWED_USER_IDS", "").split(",")
    if item.strip()
}
MAX_WEIGHT_ALLOWED_USER_NAMES = {
    item.strip().casefold()
    for item in os.getenv(
        "MAX_WEIGHT_ALLOWED_USER_NAMES",
        "Алёна Воронова,Алена Воронова,Аделина Емельянова",
    ).split(",")
    if item.strip()
}
ML_DATASET_PUBLIC_BASE_URL = os.getenv(
    "ML_DATASET_PUBLIC_BASE_URL",
    "https://194-55-235-241.sslip.io",
).strip().rstrip("/")
ML_DATASET_CITY_SHEET_ORDER = [
    "Бабаево",
    "Череповец",
    "Кириллов",
    "Кадуй",
    "Шексна",
    "Чагода",
    "Белозерск",
    "Пошехонье",
    "Вытегра",
    "Федотово",
    "Харовск",
    "Вохтога",
    "Вожега",
    "Сямжа",
    "Устье",
    "Нюксеница",
    "Верховажье",
    "Никольск",
]
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN")
MAX_BOT_TOKEN = os.getenv("MAX_BOT_TOKEN")
MAX_WEBHOOK_URL = os.getenv("MAX_WEBHOOK_URL", "").strip()
MAX_WEBHOOK_SECRET = os.getenv("MAX_WEBHOOK_SECRET", "").strip()
MAX_WEBHOOK_PATH = os.getenv("MAX_WEBHOOK_PATH", "/photo-bot/webhook").strip() or "/photo-bot/webhook"
REPORT_DB_PATH = os.getenv("REPORT_DB_PATH", "").strip()
REPORT_DB_TIMEZONE = os.getenv("REPORT_DB_TIMEZONE", "Europe/Moscow").strip() or "Europe/Moscow"
REPORT_DAY_CUTOFF_HOUR = max(0, min(23, int(os.getenv("REPORT_DAY_CUTOFF_HOUR", "10"))))
REPORT_FORCE_CURRENT_DATE = os.getenv("REPORT_FORCE_CURRENT_DATE", "").strip()
KEEP_DELIVERED_FILES = os.getenv("KEEP_DELIVERED_FILES", "false").strip().lower() in {"1", "true", "yes", "on"}
FAILED_UPLOAD_RETENTION_HOURS = max(1, int(os.getenv("FAILED_UPLOAD_RETENTION_HOURS", "24")))
UPLOAD_LOG_RETENTION_DAYS = max(1, int(os.getenv("UPLOAD_LOG_RETENTION_DAYS", "30")))
MAX_UPLOAD_LOG_ENTRIES = max(50, int(os.getenv("MAX_UPLOAD_LOG_ENTRIES", "500")))
MAX_UPLOAD_JOB_ENTRIES = max(100, int(os.getenv("MAX_UPLOAD_JOB_ENTRIES", "2000")))
UPLOAD_DEDUP_WINDOW_SECONDS = max(10, int(os.getenv("UPLOAD_DEDUP_WINDOW_SECONDS", "180")))
MAX_MANUAL_POLL_ENABLED = os.getenv("MAX_MANUAL_POLL_ENABLED", "true").strip().lower() in {
    "1",
    "true",
    "yes",
    "on",
}
MAX_MANUAL_POLL_INTERVAL_SECONDS = max(
    30, int(os.getenv("MAX_MANUAL_POLL_INTERVAL_SECONDS", "120"))
)
MAX_MANUAL_STATE_RETENTION_DAYS = max(
    1, int(os.getenv("MAX_MANUAL_STATE_RETENTION_DAYS", "7"))
)
WEBHOOK_EVENT_RETENTION_DAYS = max(1, int(os.getenv("WEBHOOK_EVENT_RETENTION_DAYS", "7")))

REVIEW_STATUS_PENDING = "pending"
REVIEW_STATUS_PENDING_REASON = "pending_reason"
REVIEW_STATUS_ACCEPTED = "accepted"
REVIEW_STATUS_REJECTED = "rejected"
REVIEW_PAYLOAD_ACCEPT_PREFIX = "pr:a:"
REVIEW_PAYLOAD_REJECT_PREFIX = "pr:r:"
REVIEW_PAYLOAD_CHANGE_PREFIX = "pr:c:"
REVIEW_PAYLOAD_CHANGE_REASON_PREFIX = "pr:cr:"
REVIEW_PAYLOAD_WEIGHT_PREFIX = "pr:w:"
REVIEW_REJECTED_PREFIX = "👎"
REVIEW_CHANGED_ACCEPTED_TEXT = "Оценка изменена 👍"
REVIEW_CHANGED_REJECTED_PREFIX = "Оценка изменена 👎"
REVIEW_CHANGE_BUTTON_TEXT = "Изменить оценку"
REVIEW_CHANGE_REASON_BUTTON_TEXT = "Изменить причину"
REVIEW_WEIGHT_BUTTON_TEXT = "Указать вес"
STORE_LOCK = threading.Lock()
REPORT_DB_SCHEMA_LOCK = threading.Lock()
MANUAL_MAX_POLL_LOCK = threading.Lock()
ML_DATASET_SHEETS_SYNC_LOCK = threading.Lock()
PENDING_MAX_DELETIONS_LOCK = threading.Lock()

DATA_DIR.mkdir(exist_ok=True)
UPLOAD_DIR.mkdir(exist_ok=True)
ML_DATASET_DIR.mkdir(exist_ok=True)
ML_DATASET_IMAGES_DIR.mkdir(exist_ok=True)
app.mount("/ml-dataset/images", StaticFiles(directory=str(ML_DATASET_IMAGES_DIR)), name="ml_dataset_images")


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with path.open("r", encoding="utf-8") as file:
                return json.load(file)
        except json.JSONDecodeError as exc:
            last_error = exc
            time.sleep(0.05 * (attempt + 1))
    logger.error("Failed to read JSON store %s: %s", path, last_error)
    return default


def save_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(f"{path.name}.tmp-{uuid.uuid4().hex}")
    with tmp_path.open("w", encoding="utf-8") as file:
        json.dump(data, file, ensure_ascii=False, indent=2)
        file.flush()
        os.fsync(file.fileno())
    os.replace(tmp_path, path)


def ml_dataset_sheets_enabled() -> bool:
    return bool(ML_DATASET_SHEETS_SPREADSHEET_ID and ML_DATASET_SHEETS_SERVICE_ACCOUNT_FILE)


def normalize_city(city: str) -> str:
    cleaned = city.strip().lower()
    cleaned = " ".join(cleaned.split())
    slug_chars: list[str] = []
    for char in cleaned:
        if char.isalnum():
            slug_chars.append(char)
        elif char in {" ", "-", "_"}:
            slug_chars.append("-")

    slug = "".join(slug_chars)
    slug = re.sub(r"-{2,}", "-", slug).strip("-")
    if not slug:
        digest = hashlib.sha1(cleaned.encode("utf-8")).hexdigest()[:12]
        slug = f"city-{digest}"
    return slug


def repair_text(value: str | None) -> str | None:
    if value is None or not isinstance(value, str):
        return value
    if "Р" in value or "С" in value:
        try:
            repaired = value.encode("latin1").decode("utf-8")
            if repaired:
                return repaired
        except Exception:
            return value
    return value


def clean_city_name(value: str | None) -> str:
    repaired = repair_text(value) or ""
    cleaned = " ".join(repaired.replace("\u00a0", " ").split()).strip()
    return cleaned


def parse_iso_datetime(value: str | None) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    candidate = value.strip()
    if not candidate:
        return None
    try:
        if candidate.endswith("Z"):
            candidate = candidate[:-1] + "+00:00"
        return datetime.fromisoformat(candidate)
    except ValueError:
        return None


def format_sheet_date(value: str | None) -> str:
    dt = parse_iso_datetime(value)
    if dt is not None:
        return dt.astimezone(ZoneInfo("Europe/Moscow")).strftime("%d.%m.%Y")
    if isinstance(value, str):
        try:
            return datetime.strptime(value.strip(), "%Y-%m-%d").strftime("%d.%m.%Y")
        except ValueError:
            return ""
    return ""


def build_ml_dataset_public_image_url(entry: dict[str, Any]) -> str:
    stored_path = str(entry.get("stored_path") or "").strip()
    if not stored_path:
        return ""
    filename = Path(stored_path).name
    if not filename:
        return ""
    return f"{ML_DATASET_PUBLIC_BASE_URL}/ml-dataset/images/{filename}"


def load_ml_dataset_sheets_sync_state() -> dict[str, Any]:
    data = load_mapping(ML_DATASET_SHEETS_SYNC_STATE_FILE)
    records = data.get("records")
    if not isinstance(records, dict):
        data["records"] = {}
    return data


def save_ml_dataset_sheets_sync_state(state: dict[str, Any]) -> None:
    save_json(ML_DATASET_SHEETS_SYNC_STATE_FILE, state)


def build_ml_dataset_sheet_signature(row: list[str]) -> str:
    return json.dumps(row, ensure_ascii=False, separators=(",", ":"))


def build_ml_dataset_sheet_items() -> dict[str, list[dict[str, Any]]]:
    items = load_ml_dataset_index()
    rows_by_city: dict[str, list[dict[str, Any]]] = {
        city: [] for city in ML_DATASET_CITY_SHEET_ORDER
    }

    for dataset_id, entry in items.items():
        if entry.get("review_status") != REVIEW_STATUS_REJECTED:
            continue

        reason = normalize_review_text(repair_text(entry.get("review_reason")) or "")
        if not reason:
            continue

        city_name = clean_city_name(entry.get("city_name"))
        if city_name not in rows_by_city:
            logger.warning("Skipping ML dataset row with unknown city for sheets sync: %s", city_name)
            continue

        image_url = build_ml_dataset_public_image_url(entry)
        review_date = (
            entry.get("photo_date")
            or entry.get("uploaded_at")
            or entry.get("reviewed_at")
        )
        sort_dt = parse_iso_datetime(entry.get("reviewed_at")) or parse_iso_datetime(
            entry.get("uploaded_at")
        ) or datetime.min.replace(tzinfo=timezone.utc)
        row = [
            format_sheet_date(review_date),
            f'=HYPERLINK("{image_url}";"ссылка")' if image_url else "",
            reason,
            normalize_review_text(repair_text(entry.get("reviewed_by_name")) or ""),
        ]
        rows_by_city[city_name].append(
            {
                "dataset_id": str(dataset_id),
                "sort_dt": sort_dt,
                "row": row,
                "signature": build_ml_dataset_sheet_signature(row),
            }
        )

    result: dict[str, list[dict[str, Any]]] = {}
    for city_name in ML_DATASET_CITY_SHEET_ORDER:
        result[city_name] = sorted(rows_by_city[city_name], key=lambda item: item["sort_dt"])
    return result


def initialize_ml_dataset_sheets_sync_state(
    sheet_items: dict[str, list[dict[str, Any]]],
) -> dict[str, Any]:
    records: dict[str, dict[str, Any]] = {}
    for city_name in ML_DATASET_CITY_SHEET_ORDER:
        city_items_desc = sorted(
            sheet_items[city_name],
            key=lambda item: item["sort_dt"],
            reverse=True,
        )
        for row_number, item in enumerate(city_items_desc, start=2):
            records[item["dataset_id"]] = {
                "sheet_title": city_name,
                "row": row_number,
                "signature": item["signature"],
            }
    return {"records": records}


def apply_ml_dataset_sheet_format(spreadsheet: Any, worksheet_id: int) -> None:
    spreadsheet.batch_update(
        {
            "requests": [
                {
                    "updateSheetProperties": {
                        "properties": {
                            "sheetId": worksheet_id,
                            "gridProperties": {"frozenRowCount": 1},
                        },
                        "fields": "gridProperties.frozenRowCount",
                    }
                },
                {
                    "repeatCell": {
                        "range": {
                            "sheetId": worksheet_id,
                            "startRowIndex": 0,
                            "endRowIndex": 1,
                            "startColumnIndex": 0,
                            "endColumnIndex": 4,
                        },
                        "cell": {
                            "userEnteredFormat": {
                                "backgroundColor": {
                                    "red": 0.16,
                                    "green": 0.38,
                                    "blue": 0.84,
                                },
                                "horizontalAlignment": "CENTER",
                                "verticalAlignment": "MIDDLE",
                                "textFormat": {
                                    "bold": True,
                                    "foregroundColor": {
                                        "red": 1.0,
                                        "green": 1.0,
                                        "blue": 1.0,
                                    },
                                },
                            }
                        },
                        "fields": (
                            "userEnteredFormat(backgroundColor,textFormat,"
                            "horizontalAlignment,verticalAlignment)"
                        ),
                    }
                },
                {
                    "repeatCell": {
                        "range": {
                            "sheetId": worksheet_id,
                            "startRowIndex": 1,
                            "startColumnIndex": 0,
                            "endColumnIndex": 1,
                        },
                        "cell": {
                            "userEnteredFormat": {
                                "numberFormat": {
                                    "type": "DATE",
                                    "pattern": "dd.mm.yyyy",
                                }
                            }
                        },
                        "fields": "userEnteredFormat.numberFormat",
                    }
                },
                {
                    "repeatCell": {
                        "range": {
                            "sheetId": worksheet_id,
                            "startRowIndex": 1,
                            "startColumnIndex": 0,
                            "endColumnIndex": 4,
                        },
                        "cell": {
                            "userEnteredFormat": {
                                "wrapStrategy": "WRAP",
                                "verticalAlignment": "TOP",
                            }
                        },
                        "fields": "userEnteredFormat(wrapStrategy,verticalAlignment)",
                    }
                },
                {
                    "updateDimensionProperties": {
                        "range": {
                            "sheetId": worksheet_id,
                            "dimension": "COLUMNS",
                            "startIndex": 0,
                            "endIndex": 1,
                        },
                        "properties": {"pixelSize": 110},
                        "fields": "pixelSize",
                    }
                },
                {
                    "updateDimensionProperties": {
                        "range": {
                            "sheetId": worksheet_id,
                            "dimension": "COLUMNS",
                            "startIndex": 1,
                            "endIndex": 2,
                        },
                        "properties": {"pixelSize": 90},
                        "fields": "pixelSize",
                    }
                },
                {
                    "updateDimensionProperties": {
                        "range": {
                            "sheetId": worksheet_id,
                            "dimension": "COLUMNS",
                            "startIndex": 2,
                            "endIndex": 3,
                        },
                        "properties": {"pixelSize": 420},
                        "fields": "pixelSize",
                    }
                },
                {
                    "updateDimensionProperties": {
                        "range": {
                            "sheetId": worksheet_id,
                            "dimension": "COLUMNS",
                            "startIndex": 3,
                            "endIndex": 4,
                        },
                        "properties": {"pixelSize": 190},
                        "fields": "pixelSize",
                    }
                },
            ]
        }
    )


def sync_ml_dataset_google_sheets() -> None:
    if not ml_dataset_sheets_enabled():
        return
    if not ML_DATASET_SHEETS_SYNC_LOCK.acquire(blocking=False):
        return

    try:
        import gspread
        from google.oauth2.service_account import Credentials

        creds = Credentials.from_service_account_file(
            ML_DATASET_SHEETS_SERVICE_ACCOUNT_FILE,
            scopes=[
                "https://www.googleapis.com/auth/spreadsheets",
                "https://www.googleapis.com/auth/drive",
            ],
        )
        client = gspread.authorize(creds)
        spreadsheet = client.open_by_key(ML_DATASET_SHEETS_SPREADSHEET_ID)
        existing = {sheet.title: sheet for sheet in spreadsheet.worksheets()}
        sheet_items = build_ml_dataset_sheet_items()
        state = load_ml_dataset_sheets_sync_state()
        state_records = state.setdefault("records", {})
        if not state_records:
            state = initialize_ml_dataset_sheets_sync_state(sheet_items)
            state_records = state["records"]
            save_ml_dataset_sheets_sync_state(state)
        header = ["Дата", "Ссылка", "Причина", "Кто поставил"]

        for index, city_name in enumerate(ML_DATASET_CITY_SHEET_ORDER):
            worksheet = existing.get(city_name)
            created = False
            if worksheet is None:
                worksheet = spreadsheet.add_worksheet(title=city_name, rows=10, cols=4)
                existing[city_name] = worksheet
                created = True

            if worksheet.index != index:
                worksheet.update_index(index)

            city_state_rows = [
                int(meta.get("row", 1))
                for meta in state_records.values()
                if meta.get("sheet_title") == city_name
            ]
            next_row = max(city_state_rows, default=1) + 1
            current_city_items = sheet_items[city_name]
            pending_appends: list[dict[str, Any]] = []
            pending_updates: list[tuple[int, list[str]]] = []

            for item in current_city_items:
                dataset_id = item["dataset_id"]
                existing_meta = state_records.get(dataset_id)
                if existing_meta and existing_meta.get("sheet_title") == city_name:
                    row_number = int(existing_meta.get("row", 1))
                    if existing_meta.get("signature") != item["signature"]:
                        pending_updates.append((row_number, item["row"]))
                        existing_meta["signature"] = item["signature"]
                    continue
                pending_appends.append(item)

            target_rows = max(10, next_row + len(pending_appends) + 2)
            if worksheet.row_count < target_rows or worksheet.col_count != 4:
                worksheet.resize(rows=target_rows, cols=4)

            if created:
                worksheet.update("A1:D1", [header], value_input_option="USER_ENTERED")
                apply_ml_dataset_sheet_format(spreadsheet, worksheet.id)

            if pending_updates:
                for row_number, row_values in pending_updates:
                    worksheet.update(
                        f"A{row_number}:D{row_number}",
                        [row_values],
                        value_input_option="USER_ENTERED",
                    )

            if pending_appends:
                new_rows = [item["row"] for item in pending_appends]
                start_row = next_row
                end_row = start_row + len(new_rows) - 1
                worksheet.update(
                    f"A{start_row}:D{end_row}",
                    new_rows,
                    value_input_option="USER_ENTERED",
                )
                for offset, item in enumerate(pending_appends):
                    state_records[item["dataset_id"]] = {
                        "sheet_title": city_name,
                        "row": start_row + offset,
                        "signature": item["signature"],
                    }

        save_ml_dataset_sheets_sync_state(state)
        logger.info("ML dataset Google Sheets sync completed")
    except Exception:
        logger.exception("ML dataset Google Sheets sync failed")
    finally:
        ML_DATASET_SHEETS_SYNC_LOCK.release()


def schedule_ml_dataset_google_sheets_sync() -> None:
    if not ml_dataset_sheets_enabled():
        return
    threading.Thread(
        target=sync_ml_dataset_google_sheets,
        name="ml-dataset-google-sheets-sync",
        daemon=True,
    ).start()


def is_placeholder_city_name(value: str | None) -> bool:
    cleaned = clean_city_name(value)
    if not cleaned:
        return True
    return re.search(r"[A-Za-z0-9\u0400-\u04FF]", cleaned) is None


def route_signature(city: dict[str, Any]) -> tuple[str | None, str | None, str | None, str | None]:
    return (
        city.get("transport_type"),
        city.get("max_chat_id"),
        city.get("telegram_chat_id"),
        city.get("telegram_thread_id"),
    )


def format_bytes(size: int) -> str:
    units = ["B", "KB", "MB", "GB"]
    value = float(size)
    for unit in units:
        if value < 1024 or unit == units[-1]:
            return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} {unit}"
        value /= 1024
    return f"{int(size)} B"


class DeviceRegistration(BaseModel):
    device_uuid: str = Field(min_length=4)
    city: str = Field(min_length=2)
    transport_type: str | None = None
    max_chat_id: str | None = None
    telegram_chat_id: str | None = None
    telegram_thread_id: str | None = None

    @field_validator("city")
    @classmethod
    def validate_city(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("city is required")
        return value.strip()

    @field_validator("transport_type")
    @classmethod
    def validate_transport_type(cls, value: str | None) -> str | None:
        if value is None:
            return value
        normalized = value.strip().lower()
        if normalized not in {"max", "telegram"}:
            raise ValueError("transport_type must be 'max' or 'telegram'")
        return normalized


class UploadJobResponse(BaseModel):
    job_id: str
    status: str
    device_uuid: str
    city_name: str
    city_slug: str
    transport_type: str
    accepted_at: str
    uploaded_at: str | None = None
    sent_at: str | None = None
    file_received_at: str | None = None
    error: str | None = None
    file_deleted: bool = False


def read_cities() -> list[dict[str, Any]]:
    return load_json(CITIES_FILE, [])


def write_cities(cities: list[dict[str, Any]]) -> None:
    save_json(CITIES_FILE, cities)


def read_devices() -> list[dict[str, Any]]:
    return load_json(DEVICES_FILE, [])


def write_devices(devices: list[dict[str, Any]]) -> None:
    save_json(DEVICES_FILE, devices)


def append_upload_log(entry: dict[str, Any]) -> None:
    current = load_json(UPLOAD_LOG_FILE, [])
    current.append(entry)
    save_json(UPLOAD_LOG_FILE, current)


def read_upload_jobs() -> list[dict[str, Any]]:
    return load_json(UPLOAD_JOBS_FILE, [])


def write_upload_jobs(jobs: list[dict[str, Any]]) -> None:
    if len(jobs) > MAX_UPLOAD_JOB_ENTRIES:
        jobs = jobs[-MAX_UPLOAD_JOB_ENTRIES:]
    save_json(UPLOAD_JOBS_FILE, jobs)


def append_upload_job(entry: dict[str, Any]) -> None:
    with STORE_LOCK:
        jobs = read_upload_jobs()
        jobs.append(entry)
        write_upload_jobs(jobs)


def update_upload_job(job_id: str, values: dict[str, Any]) -> dict[str, Any] | None:
    with STORE_LOCK:
        jobs = read_upload_jobs()
        updated: dict[str, Any] | None = None
        for index, item in enumerate(jobs):
            if item.get("job_id") == job_id:
                item.update(values)
                jobs[index] = item
                updated = item
                break
        if updated is not None:
            write_upload_jobs(jobs)
        return updated


def get_upload_job(job_id: str) -> dict[str, Any] | None:
    jobs = read_upload_jobs()
    return next((item for item in jobs if item.get("job_id") == job_id), None)


def find_upload_job_by_client_key(device_uuid: str, client_upload_id: str) -> dict[str, Any] | None:
    if not device_uuid or not client_upload_id:
        return None
    jobs = read_upload_jobs()
    for item in jobs:
        if item.get("device_uuid") == device_uuid and item.get("client_upload_id") == client_upload_id:
            return item
    return None


def compute_file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_recent_upload_job_by_fingerprint(
    device_uuid: str,
    city_slug: str,
    file_sha256: str,
    *,
    within_seconds: int = UPLOAD_DEDUP_WINDOW_SECONDS,
) -> dict[str, Any] | None:
    if not device_uuid or not city_slug or not file_sha256:
        return None

    now = datetime.now(timezone.utc)
    for item in reversed(read_upload_jobs()):
        if item.get("device_uuid") != device_uuid:
            continue
        if item.get("city_slug") != city_slug:
            continue
        if item.get("file_sha256") != file_sha256:
            continue
        accepted_at = parse_iso_datetime(item.get("accepted_at"))
        if accepted_at is None:
            continue
        if abs((now - accepted_at).total_seconds()) > within_seconds:
            continue
        if str(item.get("status") or "") in {"failed"}:
            continue
        return item
    return None


def claim_upload_job(job_id: str) -> dict[str, Any] | None:
    with STORE_LOCK:
        jobs = read_upload_jobs()
        for index, item in enumerate(jobs):
            if item.get("job_id") != job_id:
                continue
            current_status = str(item.get("status") or "")
            if current_status in {"sent", "chat_deleted"}:
                return None
            if current_status == "sending_to_chat":
                return None
            item.update(
                {
                    "status": "sending_to_chat",
                    "sending_started_at": datetime.now(timezone.utc).isoformat(),
                    "error": None,
                }
            )
            jobs[index] = item
            write_upload_jobs(jobs)
            return item
        return None


def list_recent_upload_jobs(device_uuid: str, limit: int = 10) -> list[dict[str, Any]]:
    jobs = [item for item in read_upload_jobs() if item.get("device_uuid") == device_uuid]
    jobs.sort(key=lambda item: item.get("accepted_at") or "", reverse=True)
    return jobs[:limit]


def delete_upload_job_chat_message(job_id: str) -> dict[str, Any]:
    job = get_upload_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Upload job not found")

    if job.get("status") != "sent":
        raise HTTPException(status_code=400, detail="Only sent jobs can be deleted from chat")

    if job.get("chat_deleted"):
        return {
            "status": "ok",
            "job_id": job_id,
            "message_id": None,
            "already_deleted": True,
        }

    transport_type = str(job.get("transport_type") or "")
    message_id = str(job.get("message_id") or "").strip()
    if not message_id:
        raise HTTPException(status_code=409, detail="Chat message id is missing for this job")

    if transport_type != "max":
        raise HTTPException(status_code=400, detail="Delete from chat is supported only for MAX right now")

    delete_max_message(message_id)
    update_upload_job(
        job_id,
        {
            "chat_deleted_at": datetime.now(timezone.utc).isoformat(),
            "chat_deleted": True,
            "message_id": None,
        },
    )
    return {"status": "ok", "job_id": job_id, "message_id": message_id}


def load_ml_dataset_index() -> dict[str, dict[str, Any]]:
    data = load_mapping(ML_DATASET_INDEX_FILE)
    return {str(key): value for key, value in data.items() if isinstance(value, dict)}


def save_ml_dataset_index(items: dict[str, dict[str, Any]]) -> None:
    save_json(ML_DATASET_INDEX_FILE, items)


def upsert_ml_dataset_entry(dataset_id: str, values: dict[str, Any]) -> dict[str, Any]:
    with STORE_LOCK:
        items = load_ml_dataset_index()
        existing = items.get(dataset_id, {})
        existing.update(values)
        items[dataset_id] = existing
        save_ml_dataset_index(items)
        return existing


def update_ml_dataset_by_photo_message_id(
    photo_message_id: str,
    values: dict[str, Any],
) -> dict[str, Any] | None:
    with STORE_LOCK:
        items = load_ml_dataset_index()
        for dataset_id, entry in items.items():
            if entry.get("photo_message_id") == photo_message_id:
                entry.update(values)
                items[dataset_id] = entry
                save_ml_dataset_index(items)
                return entry
    return None


def capture_ml_dataset_photo(
    source_path: Path,
    *,
    device_uuid: str,
    city: dict[str, Any],
    uploaded_at: str,
) -> dict[str, Any]:
    dataset_id = uuid.uuid4().hex
    suffix = source_path.suffix or ".jpg"
    stored_path = ML_DATASET_IMAGES_DIR / f"{dataset_id}{suffix.lower()}"
    shutil.copy2(source_path, stored_path)

    digest = hashlib.sha256()
    with stored_path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)

    entry = {
        "dataset_id": dataset_id,
        "source": "app_upload",
        "uploaded_at": uploaded_at,
        "device_uuid": device_uuid,
        "city_name": city.get("city_name"),
        "city_slug": city.get("city_slug"),
        "transport_type": city.get("transport_type"),
        "max_chat_id": city.get("max_chat_id"),
        "telegram_chat_id": city.get("telegram_chat_id"),
        "telegram_thread_id": city.get("telegram_thread_id"),
        "original_filename": source_path.name,
        "stored_path": str(stored_path),
        "size_bytes": stored_path.stat().st_size,
        "mime_type": mimetypes.guess_type(stored_path.name)[0] or "application/octet-stream",
        "sha256": digest.hexdigest(),
        "delivery_status": "pending",
        "delivery_result": None,
        "photo_message_id": None,
        "review_status": "pending",
        "review_reason": "",
        "reviewed_at": None,
        "reviewed_by": None,
        "reviewed_by_name": None,
        "final_message_id": None,
    }
    upsert_ml_dataset_entry(dataset_id, entry)
    return entry


def load_mapping(path: Path) -> dict[str, Any]:
    data = load_json(path, {})
    return data if isinstance(data, dict) else {}


def load_photo_reviews() -> dict[str, dict[str, Any]]:
    data = load_mapping(PHOTO_REVIEWS_FILE)
    return {str(key): value for key, value in data.items() if isinstance(value, dict)}


def save_photo_reviews(reviews: dict[str, dict[str, Any]]) -> None:
    save_json(PHOTO_REVIEWS_FILE, reviews)


def load_pending_review_reasons() -> dict[str, dict[str, Any]]:
    data = load_mapping(PENDING_REVIEW_REASONS_FILE)
    return {str(key): value for key, value in data.items() if isinstance(value, dict)}


def save_pending_review_reasons(items: dict[str, dict[str, Any]]) -> None:
    save_json(PENDING_REVIEW_REASONS_FILE, items)


def load_pending_weight_reports() -> dict[str, dict[str, Any]]:
    data = load_mapping(PENDING_WEIGHT_REPORTS_FILE)
    return {str(key): value for key, value in data.items() if isinstance(value, dict)}


def save_pending_weight_reports(items: dict[str, dict[str, Any]]) -> None:
    save_json(PENDING_WEIGHT_REPORTS_FILE, items)


def pending_reason_key(user_id: int | str) -> str:
    return str(user_id)


def load_manual_max_sync_state() -> dict[str, dict[str, Any]]:
    data = load_mapping(MANUAL_MAX_SYNC_STATE_FILE)
    messages = data.get("messages") if isinstance(data, dict) else {}
    if not isinstance(messages, dict):
        return {}
    return {str(key): value for key, value in messages.items() if isinstance(value, dict)}


def save_manual_max_sync_state(messages: dict[str, dict[str, Any]]) -> None:
    save_json(MANUAL_MAX_SYNC_STATE_FILE, {"messages": messages})


def prune_manual_max_sync_state(
    messages: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    cutoff_ms = int(
        (
            datetime.now(timezone.utc) - timedelta(days=MAX_MANUAL_STATE_RETENTION_DAYS)
        ).timestamp()
        * 1000
    )
    pruned: dict[str, dict[str, Any]] = {}
    for message_id, payload in messages.items():
        timestamp = payload.get("timestamp")
        try:
            timestamp_int = int(timestamp)
        except (TypeError, ValueError):
            continue
        if timestamp_int >= cutoff_ms:
            pruned[message_id] = payload
    return pruned


def load_webhook_event_state() -> dict[str, dict[str, Any]]:
    data = load_mapping(WEBHOOK_EVENT_STATE_FILE)
    events = data.get("events") if isinstance(data, dict) else {}
    if not isinstance(events, dict):
        return {}
    return {str(key): value for key, value in events.items() if isinstance(value, dict)}


def save_webhook_event_state(events: dict[str, dict[str, Any]]) -> None:
    save_json(WEBHOOK_EVENT_STATE_FILE, {"events": events})


def prune_webhook_event_state(
    events: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    cutoff_ms = int(
        (
            datetime.now(timezone.utc) - timedelta(days=WEBHOOK_EVENT_RETENTION_DAYS)
        ).timestamp()
        * 1000
    )
    pruned: dict[str, dict[str, Any]] = {}
    for event_key, payload in events.items():
        timestamp = payload.get("timestamp")
        try:
            timestamp_int = int(timestamp)
        except (TypeError, ValueError):
            continue
        if timestamp_int >= cutoff_ms:
            pruned[event_key] = payload
    return pruned


def build_webhook_event_key(payload: dict[str, Any]) -> str | None:
    update_type = str(payload.get("update_type") or "").strip()
    if update_type == "message_created":
        body = ((payload.get("message") or {}).get("body") or {})
        mid = str(body.get("mid") or "").strip()
        if mid:
            return f"message_created:{mid}"
        return None
    if update_type == "message_callback":
        callback = payload.get("callback") or {}
        callback_id = str(callback.get("callback_id") or "").strip()
        if callback_id:
            return f"message_callback:{callback_id}"
        return None
    return None


def claim_webhook_event(payload: dict[str, Any]) -> tuple[bool, str | None]:
    event_key = build_webhook_event_key(payload)
    if not event_key:
        return True, None
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    with STORE_LOCK:
        events = prune_webhook_event_state(load_webhook_event_state())
        if event_key in events:
            save_webhook_event_state(events)
            return False, event_key
        events[event_key] = {"timestamp": now_ms}
        save_webhook_event_state(events)
    return True, event_key


def release_webhook_event(event_key: str | None) -> None:
    if not event_key:
        return
    with STORE_LOCK:
        events = load_webhook_event_state()
        if event_key in events:
            events.pop(event_key, None)
            save_webhook_event_state(events)


def load_pending_max_deletions() -> list[dict[str, Any]]:
    data = load_json(PENDING_MAX_DELETIONS_FILE, [])
    return [item for item in data if isinstance(item, dict)]


def save_pending_max_deletions(items: list[dict[str, Any]]) -> None:
    save_json(PENDING_MAX_DELETIONS_FILE, items)


def enqueue_pending_max_deletion(
    message_id: str,
    *,
    reason: str,
    related_photo_message_id: str | None = None,
) -> None:
    if not message_id:
        return
    with STORE_LOCK:
        items = load_pending_max_deletions()
        if any(str(item.get("message_id") or "") == message_id for item in items):
            return
        items.append(
            {
                "message_id": message_id,
                "reason": reason,
                "related_photo_message_id": related_photo_message_id,
                "created_at": datetime.now(timezone.utc).isoformat(),
                "attempt_count": 0,
                "last_error": None,
                "last_attempt_at": None,
            }
        )
        save_pending_max_deletions(items)


def process_pending_max_deletions_once() -> int:
    if not MAX_BOT_TOKEN:
        return 0
    if not PENDING_MAX_DELETIONS_LOCK.acquire(blocking=False):
        return 0
    processed = 0
    try:
        items = load_pending_max_deletions()
        remaining: list[dict[str, Any]] = []
        for item in items:
            message_id = str(item.get("message_id") or "").strip()
            if not message_id:
                continue
            try:
                delete_max_message(message_id)
                processed += 1
            except Exception as exc:
                item["attempt_count"] = int(item.get("attempt_count") or 0) + 1
                item["last_error"] = str(exc)
                item["last_attempt_at"] = datetime.now(timezone.utc).isoformat()
                remaining.append(item)
        if remaining or items:
            save_pending_max_deletions(remaining)
        return processed
    finally:
        PENDING_MAX_DELETIONS_LOCK.release()


def schedule_pending_max_deletions_processing() -> None:
    threading.Thread(
        target=process_pending_max_deletions_once,
        name="pending-max-deletions",
        daemon=True,
    ).start()


def current_report_date() -> str:
    now = datetime.now(ZoneInfo(REPORT_DB_TIMEZONE))
    return now.strftime("%Y-%m-%d")


def report_date_from_timestamp_ms(timestamp_ms: int | None) -> str:
    if timestamp_ms is None:
        return current_report_date()
    dt = datetime.fromtimestamp(int(timestamp_ms) / 1000, tz=ZoneInfo(REPORT_DB_TIMEZONE))
    return dt.strftime("%Y-%m-%d")


def current_report_timestamp() -> str:
    return datetime.now(ZoneInfo(REPORT_DB_TIMEZONE)).isoformat(timespec="seconds")


def resolve_report_db_path() -> Path | None:
    if REPORT_DB_PATH:
        return Path(REPORT_DB_PATH)

    candidates: list[Path] = []
    candidates.append(BASE_DIR.parent / "image_counter_bot" / "data" / "bot.db")
    candidates.append(BASE_DIR.parent / "max_like_counter_bot" / "data" / "bot.db")

    seen: set[str] = set()
    unique_candidates: list[Path] = []
    for candidate in candidates:
        key = str(candidate)
        if key in seen:
            continue
        seen.add(key)
        unique_candidates.append(candidate)

    for candidate in unique_candidates:
        if candidate.exists():
            return candidate
    return unique_candidates[0] if unique_candidates else None


def get_review_photo_date(review: dict[str, Any] | None) -> str:
    if review:
        photo_date = (review.get("photo_date") or "").strip()
        if photo_date:
            return photo_date
        created_at = parse_iso_datetime(review.get("created_at"))
        if created_at is not None:
            return created_at.astimezone(ZoneInfo(REPORT_DB_TIMEZONE)).strftime("%Y-%m-%d")
    return current_report_date()


def _ensure_report_db_photo_reviews_schema(conn: sqlite3.Connection) -> None:
    with REPORT_DB_SCHEMA_LOCK:
        cursor = conn.cursor()
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS active_chats (
                chat_id INTEGER PRIMARY KEY,
                created_at TEXT NOT NULL,
                city TEXT NOT NULL DEFAULT 'Не указан'
            )
            """
        )
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS topic_titles (
                chat_id INTEGER NOT NULL,
                topic_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'Не указан',
                PRIMARY KEY (chat_id, topic_id)
            )
            """
        )
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_titles (
                chat_id INTEGER PRIMARY KEY,
                title TEXT NOT NULL
            )
            """
        )
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS photo_reviews (
                chat_id INTEGER NOT NULL,
                photo_message_id TEXT NOT NULL,
                review_message_id TEXT NOT NULL,
                review_mode TEXT NOT NULL DEFAULT 'reply',
                status TEXT NOT NULL DEFAULT 'pending',
                reason_text TEXT NOT NULL DEFAULT '',
                photo_date TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                reviewed_by INTEGER,
                reviewed_by_name TEXT,
                PRIMARY KEY (chat_id, photo_message_id),
                UNIQUE (chat_id, review_message_id)
            )
            """
        )
        cursor.execute("PRAGMA table_info(photo_reviews)")
        columns = {row[1] for row in cursor.fetchall()}
        if "photo_date" not in columns:
            cursor.execute(
                "ALTER TABLE photo_reviews ADD COLUMN photo_date TEXT NOT NULL DEFAULT ''"
            )
        conn.commit()


def sync_report_city_binding(city: dict[str, Any]) -> None:
    chat_id_raw = city.get("max_chat_id")
    city_name = clean_city_name(city.get("city_name"))
    if not chat_id_raw or not city_name:
        return

    try:
        chat_id = int(chat_id_raw)
    except (TypeError, ValueError):
        logger.warning("Invalid max_chat_id for report sync: %s", chat_id_raw)
        return

    report_db_path = resolve_report_db_path()
    if report_db_path is None:
        logger.warning("Report DB path is not resolved; skipping city binding sync")
        return

    report_db_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(report_db_path)
    try:
        _ensure_report_db_photo_reviews_schema(connection)
        cursor = connection.cursor()
        created_at = current_report_date()
        cursor.execute(
            """
            INSERT INTO active_chats (chat_id, created_at, city)
            VALUES (?, ?, ?)
            ON CONFLICT(chat_id) DO UPDATE SET city = excluded.city
            """,
            (chat_id, created_at, city_name),
        )
        cursor.execute(
            """
            INSERT INTO topic_titles (chat_id, topic_id, title, type)
            VALUES (?, 0, ?, 'Продукция')
            ON CONFLICT(chat_id, topic_id) DO UPDATE SET
                title = excluded.title,
                type = excluded.type
            """,
            (chat_id, "Продукция"),
        )
        cursor.execute(
            """
            INSERT INTO chat_titles (chat_id, title)
            VALUES (?, ?)
            ON CONFLICT(chat_id) DO UPDATE SET title = excluded.title
            """,
            (chat_id, f"Чистота и качество {city_name}"),
        )
        connection.commit()
    except Exception:
        logger.exception(
            "Failed to sync city binding to report db city=%s chat=%s",
            city_name,
            chat_id_raw,
        )
    finally:
        connection.close()


def increment_report_image_count(
    chat_id: int | str | None,
    *,
    photo_date: str | None = None,
    topic_id: int = 0,
    count: int = 1,
) -> None:
    if chat_id is None:
        return

    report_db_path = resolve_report_db_path()
    if report_db_path is None:
        logger.warning("Report DB path is not resolved; skipping image count sync")
        return

    report_db_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(report_db_path)
    try:
        _ensure_report_db_photo_reviews_schema(connection)
        cursor = connection.cursor()
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS image_counts (
                chat_id INTEGER NOT NULL,
                topic_id INTEGER NOT NULL DEFAULT 0,
                date TEXT NOT NULL,
                messenger TEXT NOT NULL DEFAULT 'tg',
                count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (chat_id, topic_id, date, messenger)
            )
            """
        )
        cursor.execute(
            """
            INSERT INTO image_counts (chat_id, topic_id, date, messenger, count)
            VALUES (?, ?, ?, 'max', ?)
            ON CONFLICT(chat_id, topic_id, date, messenger)
            DO UPDATE SET count = count + excluded.count
            """,
            (
                int(chat_id),
                int(topic_id),
                (photo_date or current_report_date()).strip() or current_report_date(),
                int(count),
            ),
        )
        connection.commit()
    except Exception:
        logger.exception(
            "Failed to sync image count to report db chat=%s date=%s",
            chat_id,
            photo_date,
        )
    finally:
        connection.close()


def sync_report_photo_review(
    photo_message_id: str,
    *,
    chat_id: int | str | None,
    review_message_id: int | str | None = None,
    status: str,
    photo_date: str | None = None,
    review_mode: str = "same_message_inline",
    reason_text: str | None = None,
    reviewed_by: int | None = None,
    reviewed_by_name: str | None = None,
) -> None:
    if chat_id is None:
        return

    report_db_path = resolve_report_db_path()
    if report_db_path is None:
        logger.warning("Report DB path is not resolved; skipping review sync")
        return

    report_db_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(report_db_path)
    try:
        _ensure_report_db_photo_reviews_schema(connection)
        now = current_report_timestamp()
        normalized_photo_date = (photo_date or current_report_date()).strip() or current_report_date()
        normalized_reason = normalize_review_text(reason_text)
        cursor = connection.cursor()
        cursor.execute(
            """
            INSERT INTO photo_reviews (
                chat_id,
                photo_message_id,
                review_message_id,
                review_mode,
                status,
                reason_text,
                photo_date,
                created_at,
                updated_at,
                reviewed_by,
                reviewed_by_name
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(chat_id, photo_message_id) DO UPDATE SET
                review_message_id = excluded.review_message_id,
                review_mode = excluded.review_mode,
                status = excluded.status,
                reason_text = excluded.reason_text,
                photo_date = CASE
                    WHEN photo_reviews.photo_date IS NULL OR photo_reviews.photo_date = ''
                    THEN excluded.photo_date
                    ELSE photo_reviews.photo_date
                END,
                updated_at = excluded.updated_at,
                reviewed_by = COALESCE(excluded.reviewed_by, photo_reviews.reviewed_by),
                reviewed_by_name = COALESCE(excluded.reviewed_by_name, photo_reviews.reviewed_by_name)
            """
            ,
            (
                int(chat_id),
                str(photo_message_id),
                str(review_message_id or photo_message_id),
                review_mode,
                status,
                normalized_reason,
                normalized_photo_date,
                now,
                now,
                reviewed_by,
                reviewed_by_name,
            ),
        )
        connection.commit()
    except Exception:
        logger.exception(
            "Failed to sync photo review to report db photo=%s chat=%s",
            photo_message_id,
            chat_id,
        )
    finally:
        connection.close()


def build_photo_review_keyboard(photo_message_id: str) -> dict[str, Any]:
    return {
        "type": "inline_keyboard",
        "payload": {
            "buttons": [
                [
                    {
                        "type": "callback",
                        "text": "👍",
                        "payload": f"{REVIEW_PAYLOAD_ACCEPT_PREFIX}{photo_message_id}",
                        "intent": "default",
                    },
                    {
                        "type": "callback",
                        "text": "👎",
                        "payload": f"{REVIEW_PAYLOAD_REJECT_PREFIX}{photo_message_id}",
                        "intent": "default",
                    },
                ]
            ]
        },
    }


def build_change_review_keyboard(photo_message_id: str) -> dict[str, Any]:
    return {
        "type": "inline_keyboard",
        "payload": {
            "buttons": [
                [
                    {
                        "type": "callback",
                        "text": REVIEW_CHANGE_BUTTON_TEXT,
                        "payload": f"{REVIEW_PAYLOAD_CHANGE_PREFIX}{photo_message_id}",
                        "intent": "default",
                    }
                ],
                [
                    {
                        "type": "callback",
                        "text": REVIEW_WEIGHT_BUTTON_TEXT,
                        "payload": f"{REVIEW_PAYLOAD_WEIGHT_PREFIX}{photo_message_id}",
                        "intent": "default",
                    }
                ]
            ]
        },
    }


def build_rejected_review_keyboard(photo_message_id: str) -> dict[str, Any]:
    return {
        "type": "inline_keyboard",
        "payload": {
            "buttons": [
                [
                    {
                        "type": "callback",
                        "text": REVIEW_CHANGE_BUTTON_TEXT,
                        "payload": f"{REVIEW_PAYLOAD_CHANGE_PREFIX}{photo_message_id}",
                        "intent": "default",
                    }
                ],
                [
                    {
                        "type": "callback",
                        "text": REVIEW_CHANGE_REASON_BUTTON_TEXT,
                        "payload": f"{REVIEW_PAYLOAD_CHANGE_REASON_PREFIX}{photo_message_id}",
                        "intent": "default",
                    }
                ],
                [
                    {
                        "type": "callback",
                        "text": REVIEW_WEIGHT_BUTTON_TEXT,
                        "payload": f"{REVIEW_PAYLOAD_WEIGHT_PREFIX}{photo_message_id}",
                        "intent": "default",
                    }
                ],
            ]
        },
    }


def parse_review_callback_payload(payload: str | None) -> tuple[str, str] | None:
    if not payload:
        return None
    if payload.startswith(REVIEW_PAYLOAD_ACCEPT_PREFIX):
        return ("accept", payload[len(REVIEW_PAYLOAD_ACCEPT_PREFIX) :])
    if payload.startswith(REVIEW_PAYLOAD_REJECT_PREFIX):
        return ("reject", payload[len(REVIEW_PAYLOAD_REJECT_PREFIX) :])
    if payload.startswith(REVIEW_PAYLOAD_CHANGE_REASON_PREFIX):
        return ("change_reason", payload[len(REVIEW_PAYLOAD_CHANGE_REASON_PREFIX) :])
    if payload.startswith(REVIEW_PAYLOAD_WEIGHT_PREFIX):
        return ("weight", payload[len(REVIEW_PAYLOAD_WEIGHT_PREFIX) :])
    if payload.startswith(REVIEW_PAYLOAD_CHANGE_PREFIX):
        return ("change", payload[len(REVIEW_PAYLOAD_CHANGE_PREFIX) :])
    return None


def normalize_review_text(value: str | None) -> str:
    if value is None:
        return ""
    normalized = " ".join(value.split()).strip()
    return normalized


def get_photo_review(photo_message_id: str) -> dict[str, Any] | None:
    return load_photo_reviews().get(photo_message_id)


def get_review_display_message_id(photo_message_id: str, review: dict[str, Any] | None) -> str:
    if review:
        for key in ("reopened_message_id", "final_message_id", "review_message_id"):
            value = (review.get(key) or "").strip()
            if value:
                return value
    return photo_message_id


def upsert_photo_review(photo_message_id: str, values: dict[str, Any]) -> dict[str, Any]:
    with STORE_LOCK:
        reviews = load_photo_reviews()
        existing = reviews.get(photo_message_id, {})
        existing.update(values)
        reviews[photo_message_id] = existing
        save_photo_reviews(reviews)
        return existing


def set_pending_review_reason(user_id: int, values: dict[str, Any]) -> dict[str, Any]:
    key = pending_reason_key(user_id)
    with STORE_LOCK:
        items = load_pending_review_reasons()
        existing = items.get(key, {})
        existing.update(values)
        items[key] = existing
        save_pending_review_reasons(items)
        return existing


def get_pending_review_reason(user_id: int) -> dict[str, Any] | None:
    return load_pending_review_reasons().get(pending_reason_key(user_id))


def clear_pending_review_reason(user_id: int) -> None:
    key = pending_reason_key(user_id)
    with STORE_LOCK:
        items = load_pending_review_reasons()
        if key in items:
            del items[key]
            save_pending_review_reasons(items)


def set_pending_weight_report(user_id: int, values: dict[str, Any]) -> dict[str, Any]:
    key = pending_reason_key(user_id)
    with STORE_LOCK:
        items = load_pending_weight_reports()
        existing = items.get(key, {})
        existing.update(values)
        items[key] = existing
        save_pending_weight_reports(items)
        return existing


def get_pending_weight_report(user_id: int) -> dict[str, Any] | None:
    return load_pending_weight_reports().get(pending_reason_key(user_id))


def clear_pending_weight_report(user_id: int) -> None:
    key = pending_reason_key(user_id)
    with STORE_LOCK:
        items = load_pending_weight_reports()
        if key in items:
            del items[key]
            save_pending_weight_reports(items)


def can_submit_weight_report(user_id: int | str | None, user_name: str | None) -> bool:
    user_id_text = str(user_id or "").strip()
    if user_id_text and user_id_text in MAX_WEIGHT_ALLOWED_USER_IDS:
        return True
    normalized_name = normalize_review_text(user_name).casefold()
    return bool(normalized_name and normalized_name in MAX_WEIGHT_ALLOWED_USER_NAMES)


def max_headers(*, json_body: bool = False) -> dict[str, str]:
    headers = {"Authorization": MAX_BOT_TOKEN or ""}
    if json_body:
        headers["Content-Type"] = "application/json"
    return headers


def get_max_message(message_id: str) -> dict[str, Any]:
    response = request_with_retry(
        "GET",
        "https://platform-api.max.ru/messages",
        headers=max_headers(),
        params={"message_ids": message_id},
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f"MAX get message failed: {response.status_code} {response.text}")

    messages = response.json().get("messages") or []
    if not messages:
        raise RuntimeError(f"MAX message not found: {message_id}")
    return messages[0]


def answer_max_callback(callback_id: str, notification: str) -> None:
    response = request_with_retry(
        "POST",
        "https://platform-api.max.ru/answers",
        headers=max_headers(json_body=True),
        params={"callback_id": callback_id},
        json={"notification": notification},
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f"MAX callback answer failed: {response.status_code} {response.text}")


def delete_max_message(message_id: str) -> None:
    response = request_with_retry(
        "DELETE",
        "https://platform-api.max.ru/messages",
        headers=max_headers(),
        params={"message_id": message_id},
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f"MAX delete message failed: {response.status_code} {response.text}")

    payload = response.json()
    if not payload.get("success"):
        raise RuntimeError(f"MAX delete message returned error: {payload}")


def send_max_private_message(user_id: int, text: str) -> None:
    response = request_with_retry(
        "POST",
        "https://platform-api.max.ru/messages",
        headers=max_headers(json_body=True),
        params={"user_id": user_id},
        json={"text": text, "notify": False},
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f"MAX private message failed: {response.status_code} {response.text}")


def ensure_max_webhook_subscription() -> None:
    if not MAX_BOT_TOKEN or not MAX_WEBHOOK_URL:
        return

    try:
        current = request_with_retry(
            "GET",
            "https://platform-api.max.ru/subscriptions",
            headers=max_headers(),
            attempts=1,
            timeout=30,
        )
        if not current.ok:
            logger.warning("MAX subscriptions check failed: %s %s", current.status_code, current.text)
            return

        subscriptions = current.json().get("subscriptions") or []
        for item in subscriptions:
            if item.get("url") == MAX_WEBHOOK_URL:
                return

        body: dict[str, Any] = {
            "url": MAX_WEBHOOK_URL,
            "update_types": ["message_created", "message_callback"],
        }
        if MAX_WEBHOOK_SECRET:
            body["secret"] = MAX_WEBHOOK_SECRET

        subscribed = request_with_retry(
            "POST",
            "https://platform-api.max.ru/subscriptions",
            headers=max_headers(json_body=True),
            json=body,
            attempts=1,
            timeout=30,
        )
        if not subscribed.ok:
            logger.warning("MAX webhook subscribe failed: %s %s", subscribed.status_code, subscribed.text)
            return
        logger.info("MAX webhook subscribed for photo bot: %s", MAX_WEBHOOK_URL)
    except Exception:
        logger.exception("Failed to ensure MAX webhook subscription for photo bot")


def get_max_chats() -> list[dict[str, Any]]:
    response = request_with_retry(
        "GET",
        "https://platform-api.max.ru/chats",
        headers=max_headers(),
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f"MAX chats failed: {response.status_code} {response.text}")
    chats = response.json().get("chats") or []
    return [chat for chat in chats if isinstance(chat, dict)]


def get_max_my_chat_member(chat_id: int | str) -> dict[str, Any]:
    response = request_with_retry(
        "GET",
        f"https://platform-api.max.ru/chats/{int(chat_id)}/members/me",
        headers=max_headers(),
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(
            f"MAX chat member failed for {chat_id}: {response.status_code} {response.text}"
        )
    payload = response.json()
    return payload if isinstance(payload, dict) else {}


def get_recent_max_chat_messages(chat_id: int | str) -> list[dict[str, Any]]:
    response = request_with_retry(
        "GET",
        "https://platform-api.max.ru/messages",
        headers=max_headers(),
        params={"chat_id": int(chat_id)},
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(
            f"MAX chat messages failed for {chat_id}: {response.status_code} {response.text}"
        )
    messages = response.json().get("messages") or []
    return [message for message in messages if isinstance(message, dict)]


def count_message_images(message: dict[str, Any]) -> int:
    attachments = ((message.get("body") or {}).get("attachments") or [])
    return sum(1 for item in attachments if (item or {}).get("type") == "image")


def sync_manual_max_chat_messages(
    city: dict[str, Any],
    processed_messages: dict[str, dict[str, Any]],
) -> int:
    chat_id_raw = city.get("max_chat_id")
    if not chat_id_raw:
        return 0

    chat_id = int(chat_id_raw)
    messages = get_recent_max_chat_messages(chat_id)
    ignored_images = 0

    for message in sorted(messages, key=lambda item: int(item.get("timestamp") or 0)):
        message_id = str(((message.get("body") or {}).get("mid") or "")).strip()
        if not message_id or message_id in processed_messages:
            continue

        timestamp = message.get("timestamp")
        processed_messages[message_id] = {
            "chat_id": chat_id,
            "timestamp": int(timestamp or 0),
        }

        sender = message.get("sender") or {}
        recipient = message.get("recipient") or {}
        if sender.get("is_bot"):
            continue
        if recipient.get("chat_type") != "chat":
            continue

        image_count = count_message_images(message)
        if image_count <= 0:
            continue

        ignored_images += image_count

    if ignored_images:
        logger.info(
            "MAX manual poll ignored photos: city=%s chat_id=%s images=%s",
            city.get("name") or city.get("slug") or chat_id,
            chat_id,
            ignored_images,
        )
    return 0


def poll_manual_max_photos_once() -> int:
    if not MAX_BOT_TOKEN or not MAX_MANUAL_POLL_ENABLED:
        return 0

    with MANUAL_MAX_POLL_LOCK:
        processed_messages = prune_manual_max_sync_state(load_manual_max_sync_state())
        counted_images = 0
        admin_chats = 0

        for chat in get_max_chats():
            chat_id = chat.get("chat_id")
            if chat.get("type") != "chat" or chat_id is None:
                continue

            city = find_city_by_max_chat_id(chat_id)
            if city is None:
                continue

            member = get_max_my_chat_member(chat_id)
            if not member.get("is_admin"):
                continue

            admin_chats += 1
            counted_images += sync_manual_max_chat_messages(city, processed_messages)

        save_manual_max_sync_state(processed_messages)
        logger.info(
            "MAX manual poll finished: admin_chats=%s counted_images=%s",
            admin_chats,
            counted_images,
        )
        return counted_images


def manual_max_poll_loop() -> None:
    while True:
        try:
            poll_manual_max_photos_once()
        except Exception:
            logger.exception("MAX manual poll failed")
        time.sleep(MAX_MANUAL_POLL_INTERVAL_SECONDS)


def parse_iso_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


def file_size_bytes(path: Path) -> int:
    return path.stat().st_size if path.exists() and path.is_file() else 0


def remove_empty_upload_dirs() -> None:
    for directory in sorted((path for path in UPLOAD_DIR.rglob("*") if path.is_dir()), reverse=True):
        try:
            next(directory.iterdir())
        except StopIteration:
            directory.rmdir()


def cleanup_storage() -> dict[str, int]:
    now = datetime.now(timezone.utc)
    failed_cutoff = now - timedelta(hours=FAILED_UPLOAD_RETENTION_HOURS)
    log_cutoff = now - timedelta(days=UPLOAD_LOG_RETENTION_DAYS)

    logs = load_json(UPLOAD_LOG_FILE, [])
    if len(logs) > MAX_UPLOAD_LOG_ENTRIES:
        logs = logs[-MAX_UPLOAD_LOG_ENTRIES:]
    cleaned_logs: list[dict[str, Any]] = []
    keep_files: set[Path] = set()
    deleted_files = 0
    deleted_bytes = 0

    for job in read_upload_jobs():
        file_path = job.get("file_path")
        if not file_path or job.get("file_deleted"):
            continue
        status = str(job.get("status") or "")
        path = Path(file_path)
        if status in {"uploaded_to_server", "sending_to_chat"}:
            keep_files.add(path)
            continue
        if status == "failed":
            failed_at = parse_iso_datetime(job.get("failed_at"))
            uploaded_at = parse_iso_datetime(job.get("uploaded_at"))
            reference_at = failed_at or uploaded_at
            if reference_at and reference_at >= failed_cutoff:
                keep_files.add(path)

    for item in logs:
        uploaded_at = parse_iso_datetime(item.get("uploaded_at"))
        if uploaded_at and uploaded_at < log_cutoff:
            file_path = item.get("file_path")
            if file_path:
                path = Path(file_path)
                if path.exists():
                    deleted_bytes += file_size_bytes(path)
                    path.unlink()
                    deleted_files += 1
            continue

        if item.get("delivery_status") == "failed":
            file_path = item.get("file_path")
            if file_path:
                path = Path(file_path)
                if uploaded_at and uploaded_at >= failed_cutoff and path.exists():
                    keep_files.add(path)
                elif path.exists():
                    deleted_bytes += file_size_bytes(path)
                    path.unlink()
                    deleted_files += 1
                    item["file_path"] = None

        cleaned_logs.append(item)

    for path in UPLOAD_DIR.rglob("*"):
        if not path.is_file():
            continue
        if path in keep_files:
            continue
        deleted_bytes += file_size_bytes(path)
        path.unlink()
        deleted_files += 1

    remove_empty_upload_dirs()
    save_json(UPLOAD_LOG_FILE, cleaned_logs)

    return {
        "deleted_files": deleted_files,
        "deleted_bytes": deleted_bytes,
        "kept_failed_files": len(keep_files),
        "remaining_logs": len(cleaned_logs),
    }


def slim_delivery_result(result: dict[str, Any] | None) -> dict[str, Any] | None:
    if result is None:
        return None

    compact = {
        "provider": result.get("provider"),
        "chat_id": result.get("chat_id"),
        "message_id": result.get("message_id"),
    }
    if result.get("message_thread_id") is not None:
        compact["message_thread_id"] = result.get("message_thread_id")
    return compact


def request_with_retry(
    method: str,
    url: str,
    *,
    attempts: int = 3,
    retry_delay: float = 1.5,
    retry_on_text: tuple[str, ...] = (),
    timeout: int = 30,
    **kwargs: Any,
) -> requests.Response:
    last_error: Exception | None = None
    last_response: requests.Response | None = None

    for attempt in range(1, attempts + 1):
        try:
            response = requests.request(method, url, timeout=timeout, **kwargs)
            last_response = response
            if response.ok:
                return response
            if attempt < attempts and any(text in response.text for text in retry_on_text):
                time.sleep(retry_delay * attempt)
                continue
            return response
        except requests.RequestException as exc:
            last_error = exc
            if attempt < attempts:
                time.sleep(retry_delay * attempt)
                continue
            raise RuntimeError(f"Request failed for {method} {url}: {exc}") from exc

    if last_response is not None:
        return last_response
    raise RuntimeError(f"Request failed for {method} {url}: {last_error}")


def sanitize_data_files() -> None:
    cities_raw = read_cities()
    devices_raw = read_devices()
    logs_raw = load_json(UPLOAD_LOG_FILE, [])

    cities_by_slug: dict[str, dict[str, Any]] = {}
    slug_aliases: dict[str, str] = {}
    for city in cities_raw:
        city_name = clean_city_name(city.get("city_name")) or "Unknown"
        slug = normalize_city(city_name)
        normalized = {
            "city_name": city_name,
            "city_slug": slug,
            "transport_type": city.get("transport_type"),
            "max_chat_id": city.get("max_chat_id"),
            "telegram_chat_id": city.get("telegram_chat_id"),
            "telegram_thread_id": city.get("telegram_thread_id"),
            "created_at": city.get("created_at"),
            "updated_at": city.get("updated_at"),
        }
        existing = cities_by_slug.get(slug)
        if existing is None:
            cities_by_slug[slug] = normalized
        else:
            if is_placeholder_city_name(existing.get("city_name")) and not is_placeholder_city_name(city_name):
                existing["city_name"] = city_name
            for key in ("transport_type", "max_chat_id", "telegram_chat_id", "telegram_thread_id"):
                if normalized.get(key):
                    existing[key] = normalized[key]
            existing["updated_at"] = normalized.get("updated_at") or existing.get("updated_at")
        slug_aliases[city.get("city_slug", slug)] = slug

    canonical_by_route: dict[tuple[str | None, str | None, str | None, str | None], dict[str, Any]] = {}
    for slug, city in list(cities_by_slug.items()):
        signature = route_signature(city)
        existing = canonical_by_route.get(signature)
        if existing is None:
            canonical_by_route[signature] = city
            continue
        if is_placeholder_city_name(existing.get("city_name")) and not is_placeholder_city_name(city.get("city_name")):
            slug_aliases[existing["city_slug"]] = city["city_slug"]
            canonical_by_route[signature] = city
            del cities_by_slug[existing["city_slug"]]
            continue
        slug_aliases[city["city_slug"]] = existing["city_slug"]
        if is_placeholder_city_name(city.get("city_name")):
            del cities_by_slug[slug]

    devices_clean: list[dict[str, Any]] = []
    seen_devices: set[str] = set()
    for device in devices_raw:
        device_uuid = device.get("device_uuid")
        if not device_uuid or device_uuid in seen_devices:
            continue
        city_name = clean_city_name(device.get("city_name")) or "Unknown"
        raw_slug = device.get("city_slug") or normalize_city(city_name)
        city_slug = slug_aliases.get(raw_slug, normalize_city(city_name))
        canonical_city = cities_by_slug.get(city_slug)
        devices_clean.append(
            {
                "device_uuid": device_uuid,
                "city_slug": city_slug,
                "city_name": canonical_city["city_name"] if canonical_city else city_name,
                "updated_at": device.get("updated_at"),
            }
        )
        seen_devices.add(device_uuid)

    logs_clean: list[dict[str, Any]] = []
    for item in logs_raw:
        city_name = clean_city_name(item.get("city_name"))
        if city_name:
            item["city_name"] = city_name
        raw_slug = item.get("city_slug") or normalize_city(item.get("city_name") or "")
        city_slug = slug_aliases.get(raw_slug, normalize_city(item.get("city_name") or ""))
        canonical_city = cities_by_slug.get(city_slug)
        if canonical_city:
            item["city_name"] = canonical_city["city_name"]
            item["city_slug"] = canonical_city["city_slug"]
            item["transport_type"] = canonical_city.get("transport_type")
            item["max_chat_id"] = canonical_city.get("max_chat_id")
            item["telegram_chat_id"] = canonical_city.get("telegram_chat_id")
            item["telegram_thread_id"] = canonical_city.get("telegram_thread_id")
        logs_clean.append(item)

    write_cities(sorted(cities_by_slug.values(), key=lambda item: item["city_name"].lower()))
    write_devices(sorted(devices_clean, key=lambda item: item["device_uuid"]))
    save_json(UPLOAD_LOG_FILE, logs_clean)


def find_city(city_slug: str) -> dict[str, Any] | None:
    for city in read_cities():
        if city["city_slug"] == city_slug:
            return city
    return None


def find_device(device_uuid: str) -> dict[str, Any] | None:
    for device in read_devices():
        if device["device_uuid"] == device_uuid:
            return device
    return None


def find_city_by_max_chat_id(chat_id: int | str | None) -> dict[str, Any] | None:
    if chat_id is None:
        return None
    chat_id_str = str(chat_id)
    matches = [
        city
        for city in read_cities()
        if str(city.get("max_chat_id") or "").strip() == chat_id_str
    ]
    if not matches:
        return None

    def score(city: dict[str, Any]) -> tuple[int, int, int, str]:
        city_name = clean_city_name(city.get("city_name"))
        lowered = city_name.lower()
        return (
            1 if city.get("telegram_chat_id") else 0,
            0 if "диагност" in lowered else 1,
            1 if city.get("telegram_thread_id") else 0,
            city_name,
        )

    return max(matches, key=score)


@app.on_event("startup")
def on_startup() -> None:
    sanitize_data_files()
    cleanup_storage()
    ensure_max_webhook_subscription()
    schedule_pending_max_deletions_processing()
    schedule_ml_dataset_google_sheets_sync()
    if MAX_BOT_TOKEN and MAX_MANUAL_POLL_ENABLED:
        threading.Thread(
            target=manual_max_poll_loop,
            name="max-manual-photo-poller",
            daemon=True,
        ).start()


def send_to_telegram(city: dict[str, Any], file_path: Path) -> dict[str, Any]:
    if not TELEGRAM_BOT_TOKEN:
        raise RuntimeError("TELEGRAM_BOT_TOKEN is not configured on the server")

    url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendPhoto"
    data = {
        "chat_id": city["telegram_chat_id"],
        "caption": city["city_name"],
    }
    if city.get("telegram_thread_id"):
        data["message_thread_id"] = city["telegram_thread_id"]

    with file_path.open("rb") as photo_file:
        response = request_with_retry(
            "POST",
            url,
            data=data,
            files={"photo": photo_file},
            timeout=30,
        )

    if not response.ok:
        raise RuntimeError(f"Telegram sendPhoto failed: {response.status_code} {response.text}")

    payload = response.json()
    if not payload.get("ok"):
        raise RuntimeError(f"Telegram sendPhoto returned error: {payload}")

    result = payload.get("result", {})
    return {
        "provider": "telegram",
        "chat_id": city["telegram_chat_id"],
        "message_id": result.get("message_id"),
        "message_thread_id": city.get("telegram_thread_id"),
    }


def send_to_max(city: dict[str, Any], file_path: Path) -> dict[str, Any]:
    if not MAX_BOT_TOKEN:
        raise RuntimeError("MAX_BOT_TOKEN is not configured on the server")
    if not city.get("max_chat_id"):
        raise RuntimeError("MAX chat_id is not configured for this city")

    headers = {"Authorization": MAX_BOT_TOKEN}

    upload_response = request_with_retry(
        "POST",
        "https://platform-api.max.ru/uploads?type=image",
        headers=headers,
        attempts=3,
        timeout=30,
    )
    if not upload_response.ok:
        raise RuntimeError(f"MAX uploads init failed: {upload_response.status_code} {upload_response.text}")

    upload_url = upload_response.json().get("url")
    if not upload_url:
        raise RuntimeError("MAX uploads init did not return url")

    with file_path.open("rb") as photo_file:
        content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
        uploaded = request_with_retry(
            "POST",
            upload_url,
            headers=headers,
            files={"data": (file_path.name, photo_file, content_type)},
            attempts=3,
            timeout=60,
        )

    if not uploaded.ok:
        raise RuntimeError(f"MAX file upload failed: {uploaded.status_code} {uploaded.text}")

    attachment_payload = uploaded.json()
    attachment_token = attachment_payload.get("token")
    if not attachment_token:
        photos = attachment_payload.get("photos", {})
        first_photo = next(iter(photos.values()), None)
        if isinstance(first_photo, dict):
            attachment_token = first_photo.get("token")
    if not attachment_token:
        raise RuntimeError(f"MAX upload did not return attachment token: {attachment_payload}")

    message_body = {
        "attachments": [
            {
                "type": "image",
                "payload": {"token": attachment_token},
            }
        ],
    }

    last_error = None
    for attempt in range(4):
        message_response = request_with_retry(
            "POST",
            f"https://platform-api.max.ru/messages?chat_id={city['max_chat_id']}",
            headers={**headers, "Content-Type": "application/json"},
            json=message_body,
            attempts=1,
            timeout=30,
        )
        if message_response.ok:
            payload = message_response.json()
            message = payload.get("message", {})
            return {
                "provider": "max",
                "chat_id": city["max_chat_id"],
                "message_id": message.get("body", {}).get("mid"),
            }

        last_error = f"{message_response.status_code} {message_response.text}"
        if "attachment.not.ready" in message_response.text and attempt < 3:
            time.sleep(1.5 * (attempt + 1))
            continue
        break

    raise RuntimeError(f"MAX send message failed: {last_error}")


def attach_review_keyboard_to_max_photo(city: dict[str, Any], photo_message_id: str) -> None:
    if not MAX_BOT_TOKEN:
        return

    message = get_max_message(photo_message_id)
    body = message.get("body") or {}
    photo_date = current_report_date()
    attachments = list(body.get("attachments") or [])
    if any((item or {}).get("type") == "inline_keyboard" for item in attachments):
        upsert_photo_review(
            photo_message_id,
            {
                "photo_message_id": photo_message_id,
                "chat_id": city.get("max_chat_id"),
                "city_name": city.get("city_name"),
                "status": REVIEW_STATUS_PENDING,
                "photo_date": photo_date,
                "created_at": datetime.now(timezone.utc).isoformat(),
                "reason_reply_message_id": None,
            },
        )
        update_ml_dataset_by_photo_message_id(
            photo_message_id,
            {
                "review_status": REVIEW_STATUS_PENDING,
                "photo_date": photo_date,
            },
        )
        sync_report_photo_review(
            photo_message_id,
            chat_id=city.get("max_chat_id"),
            review_message_id=photo_message_id,
            status=REVIEW_STATUS_PENDING,
            photo_date=photo_date,
        )
        return

    attachments.append(build_photo_review_keyboard(photo_message_id))
    response = request_with_retry(
        "PUT",
        "https://platform-api.max.ru/messages",
        headers=max_headers(json_body=True),
        params={"message_id": photo_message_id},
        json={
            "text": body.get("text", ""),
            "attachments": attachments,
            "notify": False,
        },
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f"MAX edit message failed: {response.status_code} {response.text}")

    payload = response.json()
    if not payload.get("success"):
        raise RuntimeError(f"MAX edit message returned error: {payload}")

    upsert_photo_review(
        photo_message_id,
        {
            "photo_message_id": photo_message_id,
            "chat_id": city.get("max_chat_id"),
            "city_name": city.get("city_name"),
            "status": REVIEW_STATUS_PENDING,
            "photo_date": photo_date,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "review_prompt_mode": "same_message_inline",
            "reviewed_at": None,
            "reviewed_by": None,
            "reviewed_by_name": None,
            "reason_text": "",
            "reason_source": None,
            "reason_reply_message_id": None,
            "reason_requested_at": None,
            "reason_requested_by": None,
            "reason_requested_by_name": None,
        },
    )
    update_ml_dataset_by_photo_message_id(
        photo_message_id,
        {
            "review_status": REVIEW_STATUS_PENDING,
            "photo_date": photo_date,
        },
    )
    sync_report_photo_review(
        photo_message_id,
        chat_id=city.get("max_chat_id"),
        review_message_id=photo_message_id,
        status=REVIEW_STATUS_PENDING,
        photo_date=photo_date,
    )


def update_photo_review_message(
    photo_message_id: str,
    *,
    text: str | None = None,
    keyboard_kind: str | None = None,
    callback_photo_message_id: str | None = None,
) -> None:
    message = get_max_message(photo_message_id)
    body = message.get("body") or {}
    image_attachments = [
        item for item in (body.get("attachments") or []) if (item or {}).get("type") == "image"
    ]
    if not image_attachments:
        raise RuntimeError(f"Photo message has no image attachment: {photo_message_id}")

    attachments = list(image_attachments)
    callback_id = callback_photo_message_id or photo_message_id
    if keyboard_kind == "review":
        attachments.append(build_photo_review_keyboard(callback_id))
    elif keyboard_kind == "change":
        attachments.append(build_change_review_keyboard(callback_id))
    elif keyboard_kind == "rejected":
        attachments.append(build_rejected_review_keyboard(callback_id))

    response = request_with_retry(
        "PUT",
        "https://platform-api.max.ru/messages",
        headers=max_headers(json_body=True),
        params={"message_id": photo_message_id},
        json={
            "text": normalize_review_text(text),
            "attachments": attachments,
            "notify": False,
        },
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f"MAX edit message failed: {response.status_code} {response.text}")

    payload = response.json()
    if not payload.get("success"):
        raise RuntimeError(f"MAX edit message returned error: {payload}")


def send_max_photo_with_text(
    chat_id: str | int,
    image_token: str,
    text: str,
    *,
    keyboard_kind: str | None = None,
    callback_photo_message_id: str | None = None,
) -> str:
    attachments: list[dict[str, Any]] = [
        {
            "type": "image",
            "payload": {"token": image_token},
        }
    ]
    if keyboard_kind == "review" and callback_photo_message_id:
        attachments.append(build_photo_review_keyboard(callback_photo_message_id))
    elif keyboard_kind == "change" and callback_photo_message_id:
        attachments.append(build_change_review_keyboard(callback_photo_message_id))

    response = request_with_retry(
        "POST",
        "https://platform-api.max.ru/messages",
        headers=max_headers(json_body=True),
        params={"chat_id": chat_id},
        json={
            "text": normalize_review_text(text),
            "attachments": attachments,
            "notify": False,
        },
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f"MAX send message failed: {response.status_code} {response.text}")

    payload = response.json()
    message = payload.get("message") or {}
    mid = (message.get("body") or {}).get("mid")
    if not mid:
        raise RuntimeError(f"MAX send message returned no message id: {payload}")
    return mid


def send_max_text_reply(
    chat_id: str | int,
    reply_to_message_id: str,
    text: str,
) -> str:
    response = request_with_retry(
        "POST",
        "https://platform-api.max.ru/messages",
        headers=max_headers(json_body=True),
        params={"chat_id": chat_id},
        json={
            "text": normalize_review_text(text),
            "notify": False,
            "link": {
                "type": "reply",
                "mid": reply_to_message_id,
            },
        },
        attempts=1,
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f"MAX send reply failed: {response.status_code} {response.text}")

    payload = response.json()
    message = payload.get("message") or {}
    mid = (message.get("body") or {}).get("mid")
    if not mid:
        raise RuntimeError(f"MAX send reply returned no message id: {payload}")
    return mid


def parse_weight_report_text(text: str) -> tuple[str, str, str, str]:
    parts = [part.strip() for part in re.split(r"[;\n]", text) if part.strip()]
    if len(parts) < 3:
        raise ValueError("Need product, actual weight and recipe weight")
    product_name = parts[0]
    actual_weight = parts[1]
    recipe_weight = parts[2]
    comment = "; ".join(parts[3:]) if len(parts) > 3 else ""
    return product_name, actual_weight, recipe_weight, comment


def append_weight_report_row(
    *,
    review: dict[str, Any],
    photo_message_id: str,
    message_id: str,
    checked_by_name: str,
    product_name: str,
    actual_weight: str,
    recipe_weight: str,
    comment: str,
) -> None:
    if not WEIGHT_REPORT_SHEETS_SPREADSHEET_ID:
        raise RuntimeError("WEIGHT_REPORT_SHEETS_SPREADSHEET_ID is not configured")
    if not WEIGHT_REPORT_SHEETS_SERVICE_ACCOUNT_FILE:
        raise RuntimeError("WEIGHT_REPORT_SHEETS_SERVICE_ACCOUNT_FILE is not configured")

    import gspread
    from google.oauth2.service_account import Credentials

    now = datetime.now(ZoneInfo(REPORT_DB_TIMEZONE))
    photo_date = get_review_photo_date(review)
    row = [
        now.strftime("%d.%m.%Y %H:%M:%S"),
        format_sheet_date(current_report_date()),
        format_sheet_date(photo_date),
        review.get("city_name") or "",
        checked_by_name,
        review.get("status") or "",
        product_name,
        actual_weight,
        recipe_weight,
        str(review.get("chat_id") or ""),
        message_id,
        photo_message_id,
        "max_bot",
        comment,
    ]

    creds = Credentials.from_service_account_file(
        WEIGHT_REPORT_SHEETS_SERVICE_ACCOUNT_FILE,
        scopes=["https://www.googleapis.com/auth/spreadsheets"],
    )
    client = gspread.authorize(creds)
    spreadsheet = client.open_by_key(WEIGHT_REPORT_SHEETS_SPREADSHEET_ID)
    worksheet = spreadsheet.worksheet(WEIGHT_REPORT_SHEET_NAME)
    worksheet.append_row(row, value_input_option="USER_ENTERED")


def repost_photo_to_bottom(
    source_message_id: str,
    text: str,
    *,
    keyboard_kind: str | None = None,
    callback_photo_message_id: str | None = None,
) -> str:
    source = get_max_message(source_message_id)
    recipient = source.get("recipient") or {}
    body = source.get("body") or {}
    chat_id = recipient.get("chat_id")
    if chat_id is None:
        raise RuntimeError(f"Source photo has no chat_id: {source_message_id}")

    image_attachment = next(
        (item for item in (body.get("attachments") or []) if (item or {}).get("type") == "image"),
        None,
    )
    image_payload = (image_attachment or {}).get("payload") or {}
    image_token = image_payload.get("token")
    if not image_token:
        raise RuntimeError(f"Source photo has no reusable image token: {source_message_id}")

    return send_max_photo_with_text(
        chat_id,
        image_token,
        text,
        keyboard_kind=keyboard_kind,
        callback_photo_message_id=callback_photo_message_id,
    )


def delete_review_reason_reply_if_present(
    review: dict[str, Any] | None,
    *,
    photo_message_id: str,
    reason: str,
) -> None:
    if not review:
        return

    reason_reply_message_id = str(review.get("reason_reply_message_id") or "").strip()
    if not reason_reply_message_id:
        return

    try:
        delete_max_message(reason_reply_message_id)
    except Exception:
        enqueue_pending_max_deletion(
            reason_reply_message_id,
            reason=reason,
            related_photo_message_id=photo_message_id,
        )
        logger.exception(
            "Failed to delete reason reply mid=%s for photo=%s",
            reason_reply_message_id,
            photo_message_id,
        )


def handle_photo_review_callback(payload: dict[str, Any]) -> bool:
    callback = payload.get("callback") or {}
    user = callback.get("user") or {}
    callback_id = callback.get("callback_id")
    user_id = user.get("user_id")
    user_name = user.get("name") or user.get("first_name") or "Неизвестный пользователь"
    parsed = parse_review_callback_payload(callback.get("payload"))

    if callback_id is None or user_id is None or parsed is None:
        return False

    action, photo_message_id = parsed
    review = get_photo_review(photo_message_id)
    if review is None:
        answer_max_callback(callback_id, "Фото для проверки не найдено")
        return True

    current_message_id = get_review_display_message_id(photo_message_id, review)

    if action == "change":
        if review.get("status") not in {REVIEW_STATUS_ACCEPTED, REVIEW_STATUS_REJECTED}:
            answer_max_callback(callback_id, "Сначала завершите текущую оценку")
            return True
        # MAX can take seconds to delete an old reply. Confirm the action first
        # so the reviewer immediately sees the new rating buttons.
        answer_max_callback(callback_id, "Выберите новую оценку")
        update_photo_review_message(
            current_message_id,
            text="",
            keyboard_kind="review",
            callback_photo_message_id=photo_message_id,
        )
        upsert_photo_review(
            photo_message_id,
            {
                "status": REVIEW_STATUS_PENDING,
                "pending_change": True,
                "reopened_message_id": current_message_id,
                "reopened_from_status": review.get("status"),
                "reason_text": "",
                "reason_source": None,
                "reason_reply_message_id": None,
                "reason_requested_at": None,
                "reason_requested_by": None,
                "reason_requested_by_name": None,
            },
        )
        update_ml_dataset_by_photo_message_id(
            photo_message_id,
            {
                "review_status": REVIEW_STATUS_PENDING,
            },
        )
        schedule_ml_dataset_google_sheets_sync()
        clear_pending_review_reason(user_id)
        threading.Thread(
            target=delete_review_reason_reply_if_present,
            kwargs={
                "review": review,
                "photo_message_id": photo_message_id,
                "reason": "review_change_cleanup",
            },
            name="review-change-reason-cleanup",
            daemon=True,
        ).start()
        return True

    if action == "change_reason":
        if review.get("status") != REVIEW_STATUS_REJECTED:
            answer_max_callback(callback_id, "Сначала поставьте дизлайк")
            return True
        existing_pending = get_pending_review_reason(user_id)
        if existing_pending is not None and existing_pending.get("photo_message_id") != photo_message_id:
            answer_max_callback(callback_id, "Сначала завершите причину по предыдущему фото")
            return True

        prompt_message_id = send_max_text_reply(
            review.get("chat_id"),
            current_message_id,
            "Напишите новую причину следующим сообщением. Я прикреплю её ответом к фото.",
        )
        requested_at = datetime.now(timezone.utc).isoformat()
        set_pending_review_reason(
            user_id,
            {
                "photo_message_id": photo_message_id,
                "chat_id": review.get("chat_id"),
                "city_name": review.get("city_name"),
                "requested_at": requested_at,
                "reject_retry_count": 0,
                "mode": "change_reason",
                "prompt_message_id": prompt_message_id,
            },
        )
        upsert_photo_review(
            photo_message_id,
            {
                "reason_requested_at": requested_at,
                "reason_requested_by": user_id,
                "reason_requested_by_name": user_name,
                "reason_prompt_message_id": prompt_message_id,
            },
        )
        answer_max_callback(callback_id, "Напишите новую причину")
        return True

    if action == "weight":
        if not can_submit_weight_report(user_id, user_name):
            answer_max_callback(callback_id, "Указывать вес могут только Алёна и Аделина")
            return True
        if review.get("status") not in {REVIEW_STATUS_ACCEPTED, REVIEW_STATUS_REJECTED}:
            answer_max_callback(callback_id, "Сначала поставьте оценку фото")
            return True
        prompt_message_id = send_max_text_reply(
            review.get("chat_id"),
            current_message_id,
            "Напишите вес следующим сообщением: продукт; вес на фото; вес по раскладке; комментарий.",
        )
        set_pending_weight_report(
            user_id,
            {
                "photo_message_id": photo_message_id,
                "chat_id": review.get("chat_id"),
                "city_name": review.get("city_name"),
                "requested_at": datetime.now(timezone.utc).isoformat(),
                "prompt_message_id": prompt_message_id,
            },
        )
        answer_max_callback(callback_id, "Напишите данные веса")
        return True

    if review.get("status") == REVIEW_STATUS_ACCEPTED:
        answer_max_callback(callback_id, "Уже принято")
        return True

    if review.get("status") == REVIEW_STATUS_REJECTED:
        answer_max_callback(callback_id, "Уже не принято")
        return True

    if review.get("status") == REVIEW_STATUS_PENDING_REASON:
        requested_by = review.get("reason_requested_by")
        if requested_by == user_id:
            answer_max_callback(callback_id, "Причина уже ожидается, пришлите её следующим сообщением")
        else:
            answer_max_callback(callback_id, "Причина уже запрашивается другим пользователем")
        return True

    existing_pending = get_pending_review_reason(user_id)
    if (
        action == "reject"
        and existing_pending is not None
        and existing_pending.get("photo_message_id") != photo_message_id
    ):
        attempts = int(existing_pending.get("reject_retry_count") or 0) + 1
        set_pending_review_reason(
            user_id,
            {
                "reject_retry_count": attempts,
            },
        )
        notification = "Сначала укажите причину по предыдущему фото."
        if attempts >= 5:
            notification = "Сначала укажите причину для фото выше."
        answer_max_callback(
            callback_id,
            notification,
        )
        return True

    if action == "accept":
        reviewed_at = datetime.now(timezone.utc).isoformat()
        pending_change = bool(review.get("pending_change"))
        final_message_id = current_message_id
        review_message_id = current_message_id
        accepted_text = REVIEW_CHANGED_ACCEPTED_TEXT if pending_change else "👍"
        update_photo_review_message(
            current_message_id,
            text=accepted_text,
            keyboard_kind="change",
            callback_photo_message_id=photo_message_id,
        )
        review_message_id = final_message_id
        upsert_photo_review(
            photo_message_id,
            {
                "status": REVIEW_STATUS_ACCEPTED,
                "reviewed_at": reviewed_at,
                "reviewed_by": user_id,
                "reviewed_by_name": user_name,
                "reason_text": "",
                "reason_source": None,
                "reason_reply_message_id": None,
                "reason_requested_at": None,
                "reason_requested_by": None,
                "reason_requested_by_name": None,
                "pending_change": False,
                "reopened_message_id": None,
                "reopened_from_status": None,
                "final_message_id": final_message_id,
                "review_message_id": review_message_id,
            },
        )
        update_ml_dataset_by_photo_message_id(
            photo_message_id,
            {
                "review_status": REVIEW_STATUS_ACCEPTED,
                "review_reason": "",
                "reviewed_at": reviewed_at,
                "reviewed_by": user_id,
                "reviewed_by_name": user_name,
                "final_message_id": final_message_id,
            },
        )
        schedule_ml_dataset_google_sheets_sync()
        sync_report_photo_review(
            photo_message_id,
            chat_id=review.get("chat_id"),
            review_message_id=review_message_id,
            status=REVIEW_STATUS_ACCEPTED,
            photo_date=get_review_photo_date(review),
            reviewed_by=user_id,
            reviewed_by_name=user_name,
        )
        clear_pending_review_reason(user_id)
        answer_max_callback(callback_id, "Принято")
        return True

    pending = {
        "photo_message_id": photo_message_id,
        "chat_id": review.get("chat_id"),
        "city_name": review.get("city_name"),
        "requested_at": datetime.now(timezone.utc).isoformat(),
        "reject_retry_count": 0,
    }
    set_pending_review_reason(user_id, pending)
    update_photo_review_message(
        current_message_id,
        text="Не принято. Напишите причину следующим сообщением в чат.",
        keyboard_kind=None,
    )
    upsert_photo_review(
        photo_message_id,
        {
            "status": REVIEW_STATUS_PENDING_REASON,
            "reason_requested_at": pending["requested_at"],
            "reason_requested_by": user_id,
            "reason_requested_by_name": user_name,
        },
    )
    update_ml_dataset_by_photo_message_id(
        photo_message_id,
        {
            "review_status": REVIEW_STATUS_PENDING_REASON,
            "reviewed_by": user_id,
            "reviewed_by_name": user_name,
        },
    )
    schedule_ml_dataset_google_sheets_sync()
    sync_report_photo_review(
        photo_message_id,
        chat_id=review.get("chat_id"),
        review_message_id=photo_message_id,
        status=REVIEW_STATUS_PENDING_REASON,
        photo_date=get_review_photo_date(review),
        reviewed_by=user_id,
        reviewed_by_name=user_name,
    )
    answer_max_callback(callback_id, "Напишите причину следующим сообщением в этот чат")
    return True


def handle_pending_review_reason(payload: dict[str, Any]) -> bool:
    message = payload.get("message") or {}
    sender = message.get("sender") or {}
    body = message.get("body") or {}
    recipient = message.get("recipient") or {}
    reason_message_id = body.get("mid")

    if sender.get("is_bot"):
        return False

    user_id = sender.get("user_id")
    if user_id is None:
        return False

    pending = get_pending_review_reason(user_id)
    if pending is None:
        return False

    text = (body.get("text") or "").strip()
    if not text or body.get("attachments"):
        return False

    chat_type = recipient.get("chat_type")
    chat_id = recipient.get("chat_id")
    source_chat_id = pending.get("chat_id")
    if chat_type != "chat":
        return False
    if source_chat_id is not None and str(chat_id) != str(source_chat_id):
        return False

    photo_message_id = pending.get("photo_message_id")
    if not photo_message_id:
        clear_pending_review_reason(user_id)
        return False

    review = get_photo_review(photo_message_id)
    if review is None:
        clear_pending_review_reason(user_id)
        return False

    reason_text = normalize_review_text(text)
    source_message_id = get_review_display_message_id(photo_message_id, review)
    pending_change = bool(review.get("pending_change"))
    pending_mode = (pending.get("mode") or "reject").strip()
    final_message_id = source_message_id
    reviewed_at = datetime.now(timezone.utc).isoformat()
    review_chat_id = review.get("chat_id")
    if review_chat_id is None:
        clear_pending_review_reason(user_id)
        raise RuntimeError(f"Review chat_id is missing for photo={photo_message_id}")
    delete_review_reason_reply_if_present(
        review,
        photo_message_id=photo_message_id,
        reason="reject_reason_replace",
    )
    update_photo_review_message(
        source_message_id,
        text="Причина изменена 👎"
        if pending_mode == "change_reason"
        else (REVIEW_CHANGED_REJECTED_PREFIX if pending_change else REVIEW_REJECTED_PREFIX),
        keyboard_kind="rejected",
        callback_photo_message_id=photo_message_id,
    )
    reason_reply_message_id = send_max_text_reply(
        review_chat_id,
        source_message_id,
        reason_text,
    )
    upsert_photo_review(
        photo_message_id,
        {
            "status": REVIEW_STATUS_REJECTED,
            "reviewed_at": reviewed_at,
            "reviewed_by": user_id,
            "reviewed_by_name": sender.get("name") or sender.get("first_name") or "Неизвестный пользователь",
            "reason_text": reason_text,
            "reason_source": "group_message",
            "reason_reply_message_id": reason_reply_message_id,
            "reason_prompt_message_id": None,
            "reason_requested_at": None,
            "reason_requested_by": None,
            "reason_requested_by_name": None,
            "pending_change": False,
            "reopened_message_id": None,
            "reopened_from_status": None,
            "review_message_id": final_message_id,
            "final_message_id": final_message_id,
        },
    )
    update_ml_dataset_by_photo_message_id(
        photo_message_id,
        {
            "review_status": REVIEW_STATUS_REJECTED,
            "review_reason": reason_text,
            "reviewed_at": reviewed_at,
            "reviewed_by": user_id,
            "reviewed_by_name": sender.get("name") or sender.get("first_name") or "Неизвестный пользователь",
            "final_message_id": final_message_id,
        },
    )
    schedule_ml_dataset_google_sheets_sync()
    sync_report_photo_review(
        photo_message_id,
        chat_id=review.get("chat_id"),
        review_message_id=source_message_id,
        status=REVIEW_STATUS_REJECTED,
        photo_date=get_review_photo_date(review),
        reviewed_by=user_id,
        reviewed_by_name=sender.get("name") or sender.get("first_name") or "Неизвестный пользователь",
        reason_text=reason_text,
    )
    clear_pending_review_reason(user_id)
    if reason_message_id:
        try:
            delete_max_message(reason_message_id)
        except Exception:
            enqueue_pending_max_deletion(
                str(reason_message_id),
                reason="reject_reason_cleanup",
                related_photo_message_id=photo_message_id,
            )
            logger.exception(
                "Failed to delete reason message mid=%s for photo=%s",
                reason_message_id,
                photo_message_id,
            )
    prompt_message_id = str(pending.get("prompt_message_id") or review.get("reason_prompt_message_id") or "").strip()
    if prompt_message_id:
        try:
            delete_max_message(prompt_message_id)
        except Exception:
            enqueue_pending_max_deletion(
                prompt_message_id,
                reason="reject_prompt_cleanup",
                related_photo_message_id=photo_message_id,
            )
            logger.exception(
                "Failed to delete reason prompt mid=%s for photo=%s",
                prompt_message_id,
                photo_message_id,
            )
    return True


def handle_pending_weight_report(payload: dict[str, Any]) -> bool:
    message = payload.get("message") or {}
    sender = message.get("sender") or {}
    body = message.get("body") or {}
    recipient = message.get("recipient") or {}
    weight_message_id = body.get("mid")

    if sender.get("is_bot"):
        return False

    user_id = sender.get("user_id")
    if user_id is None:
        return False
    sender_name = sender.get("name") or sender.get("first_name") or "Неизвестный пользователь"
    if not can_submit_weight_report(user_id, sender_name):
        clear_pending_weight_report(user_id)
        return False

    pending = get_pending_weight_report(user_id)
    if pending is None:
        return False

    text = (body.get("text") or "").strip()
    if not text or body.get("attachments"):
        return False

    chat_type = recipient.get("chat_type")
    chat_id = recipient.get("chat_id")
    source_chat_id = pending.get("chat_id")
    if chat_type != "chat":
        return False
    if source_chat_id is not None and str(chat_id) != str(source_chat_id):
        return False

    photo_message_id = pending.get("photo_message_id")
    if not photo_message_id:
        clear_pending_weight_report(user_id)
        return False

    review = get_photo_review(photo_message_id)
    if review is None:
        clear_pending_weight_report(user_id)
        return False

    try:
        product_name, actual_weight, recipe_weight, comment = parse_weight_report_text(text)
    except ValueError:
        send_max_text_reply(
            chat_id,
            str(weight_message_id or photo_message_id),
            "Не понял вес. Напишите так: продукт; вес на фото; вес по раскладке; комментарий.",
        )
        return True

    source_message_id = get_review_display_message_id(photo_message_id, review)
    checked_by_name = sender.get("name") or sender.get("first_name") or "Неизвестный пользователь"
    append_weight_report_row(
        review=review,
        photo_message_id=photo_message_id,
        message_id=source_message_id,
        checked_by_name=checked_by_name,
        product_name=product_name,
        actual_weight=actual_weight,
        recipe_weight=recipe_weight,
        comment=comment,
    )
    upsert_photo_review(
        photo_message_id,
        {
            "weight_reported_at": datetime.now(timezone.utc).isoformat(),
            "weight_reported_by": user_id,
            "weight_reported_by_name": checked_by_name,
            "weight_product_name": product_name,
            "weight_actual": actual_weight,
            "weight_expected": recipe_weight,
            "weight_comment": comment,
        },
    )
    clear_pending_weight_report(user_id)

    prompt_message_id = str(pending.get("prompt_message_id") or "").strip()
    for stale_message_id, stale_reason in (
        (str(weight_message_id or "").strip(), "weight_input_cleanup"),
        (prompt_message_id, "weight_prompt_cleanup"),
    ):
        if not stale_message_id:
            continue
        try:
            delete_max_message(stale_message_id)
        except Exception:
            enqueue_pending_max_deletion(
                stale_message_id,
                reason=stale_reason,
                related_photo_message_id=photo_message_id,
            )
            logger.exception(
                "Failed to delete weight helper message mid=%s for photo=%s",
                stale_message_id,
                photo_message_id,
            )

    send_max_text_reply(
        chat_id,
        source_message_id,
        f"Вес записан: {product_name}; {actual_weight}; {recipe_weight}",
    )
    return True


def handle_manual_max_photo(payload: dict[str, Any]) -> bool:
    message = payload.get("message") or {}
    recipient = message.get("recipient") or {}
    body = message.get("body") or {}
    attachments = body.get("attachments") or []
    image_count = sum(1 for item in attachments if (item or {}).get("type") == "image")
    if image_count <= 0:
        return False
    if recipient.get("chat_type") != "chat":
        return False
    logger.info(
        "MAX webhook photo ignored for counting: chat_id=%s images=%s mid=%s",
        recipient.get("chat_id"),
        image_count,
        body.get("mid"),
    )
    return False


def handle_max_webhook_payload(payload: dict[str, Any]) -> bool:
    update_type = payload.get("update_type")
    if update_type == "message_callback":
        return handle_photo_review_callback(payload)
    if update_type == "message_created":
        if handle_pending_weight_report(payload):
            return True
        if handle_pending_review_reason(payload):
            return True
        return handle_manual_max_photo(payload)
    return False


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post(MAX_WEBHOOK_PATH)
async def max_webhook(request: Request) -> JSONResponse:
    if MAX_WEBHOOK_SECRET:
        incoming_secret = request.headers.get("X-Max-Bot-Api-Secret", "")
        if incoming_secret != MAX_WEBHOOK_SECRET:
            return JSONResponse({"ok": False, "error": "forbidden"}, status_code=403)

    try:
        payload = await request.json()
    except Exception:
        return JSONResponse({"ok": False, "error": "invalid_json"}, status_code=400)

    if not isinstance(payload, dict):
        return JSONResponse({"ok": False, "error": "invalid_payload"}, status_code=400)

    claimed, event_key = claim_webhook_event(payload)
    if not claimed:
        return JSONResponse({"ok": True, "handled": True, "duplicate": True})

    try:
        handled = handle_max_webhook_payload(payload)
    except Exception:
        release_webhook_event(event_key)
        logger.exception("Photo bot webhook processing failed")
        return JSONResponse({"ok": False, "error": "processing_failed"}, status_code=500)

    schedule_pending_max_deletions_processing()
    return JSONResponse({"ok": True, "handled": handled})


@app.post("/register-device")
def register_device(payload: DeviceRegistration) -> dict[str, Any]:
    city_name = clean_city_name(payload.city)
    if is_placeholder_city_name(city_name):
        raise HTTPException(status_code=400, detail="city must contain readable letters or digits")

    city_slug = normalize_city(city_name)
    cities = read_cities()
    devices = read_devices()
    now = datetime.now(timezone.utc).isoformat()

    city_record = next((item for item in cities if item["city_slug"] == city_slug), None)

    if city_record is None:
        if payload.transport_type is None:
            raise HTTPException(status_code=400, detail="transport_type is required for a new city")
        if payload.transport_type == "max" and not payload.max_chat_id:
            raise HTTPException(status_code=400, detail="max_chat_id is required for MAX")
        if payload.transport_type == "telegram":
            if not payload.telegram_chat_id:
                raise HTTPException(status_code=400, detail="telegram_chat_id is required for Telegram")
            if not payload.telegram_thread_id:
                raise HTTPException(status_code=400, detail="telegram_thread_id is required for Telegram")

        city_record = {
            "city_name": city_name,
            "city_slug": city_slug,
            "transport_type": payload.transport_type,
            "max_chat_id": payload.max_chat_id,
            "telegram_chat_id": payload.telegram_chat_id,
            "telegram_thread_id": payload.telegram_thread_id,
            "created_at": now,
            "updated_at": now,
        }
        cities.append(city_record)
    else:
        changed = False
        if payload.transport_type and payload.transport_type != city_record["transport_type"]:
            city_record["transport_type"] = payload.transport_type
            changed = True
        if payload.max_chat_id:
            city_record["max_chat_id"] = payload.max_chat_id
            changed = True
        if payload.telegram_chat_id:
            city_record["telegram_chat_id"] = payload.telegram_chat_id
            changed = True
        if payload.telegram_thread_id:
            city_record["telegram_thread_id"] = payload.telegram_thread_id
            changed = True
        if changed:
            city_record["updated_at"] = now
        for index, existing in enumerate(cities):
            if existing["city_slug"] == city_slug:
                cities[index] = city_record
                break

    device_record = {
        "device_uuid": payload.device_uuid,
        "city_slug": city_slug,
        "city_name": city_record["city_name"],
        "updated_at": now,
    }

    device_found = False
    for index, existing in enumerate(devices):
        if existing["device_uuid"] == payload.device_uuid:
            devices[index] = device_record
            device_found = True
            break

    if not device_found:
        devices.append(device_record)

    write_cities(cities)
    write_devices(devices)

    if city_record["transport_type"] == "max" and city_record.get("max_chat_id"):
        sync_report_city_binding(city_record)

    return {
        "status": "ok",
        "city_slug": city_slug,
        "city_name": city_record["city_name"],
        "device_uuid": payload.device_uuid,
        "transport_type": city_record["transport_type"],
        "max_chat_id": city_record["max_chat_id"],
        "telegram_chat_id": city_record["telegram_chat_id"],
        "telegram_thread_id": city_record["telegram_thread_id"],
    }


@app.get("/cities")
def list_cities() -> dict[str, Any]:
    return {"cities": read_cities()}


@app.get("/devices")
def list_devices() -> dict[str, Any]:
    return {"devices": read_devices()}


@app.get("/admin/summary")
def admin_summary() -> dict[str, Any]:
    cities = read_cities()
    devices = read_devices()
    logs = load_json(UPLOAD_LOG_FILE, [])
    reviews = load_photo_reviews()
    pending_reasons = load_pending_review_reasons()
    upload_files = [path for path in UPLOAD_DIR.rglob("*") if path.is_file()]
    upload_bytes = sum(path.stat().st_size for path in upload_files)
    return {
        "cities_count": len(cities),
        "devices_count": len(devices),
        "uploads_count": len(logs),
        "reviews_count": len(reviews),
        "pending_reasons_count": len(pending_reasons),
        "stored_files_count": len(upload_files),
        "stored_files_bytes": upload_bytes,
        "keep_delivered_files": KEEP_DELIVERED_FILES,
        "failed_upload_retention_hours": FAILED_UPLOAD_RETENTION_HOURS,
        "upload_log_retention_days": UPLOAD_LOG_RETENTION_DAYS,
        "max_upload_log_entries": MAX_UPLOAD_LOG_ENTRIES,
        "max_webhook_path": MAX_WEBHOOK_PATH,
        "max_webhook_enabled": bool(MAX_WEBHOOK_URL),
        "cities": cities,
        "devices": devices,
    }


@app.get("/admin", response_class=HTMLResponse)
def admin_dashboard() -> HTMLResponse:
    summary = admin_summary()
    cities_rows = "".join(
        (
            "<tr>"
            f"<td>{city['city_name']}</td>"
            f"<td>{city['transport_type']}</td>"
            f"<td>{city.get('max_chat_id') or '-'}</td>"
            f"<td>{city.get('telegram_chat_id') or '-'}</td>"
            f"<td>{city.get('telegram_thread_id') or '-'}</td>"
            "</tr>"
        )
        for city in summary["cities"]
    ) or "<tr><td colspan='5'>Нет городов</td></tr>"

    devices_rows = "".join(
        (
            "<tr>"
            f"<td>{device['device_uuid']}</td>"
            f"<td>{device['city_name']}</td>"
            f"<td>{device.get('updated_at') or '-'}</td>"
            "</tr>"
        )
        for device in summary["devices"]
    ) or "<tr><td colspan='3'>Нет устройств</td></tr>"

    html = f"""
    <!doctype html>
    <html lang="ru">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Админка Фото в чат</title>
      <style>
        :root {{
          color-scheme: light;
          --bg: #f4f1ea;
          --card: #fffdf8;
          --line: #d8cfc1;
          --text: #231f1a;
          --muted: #706556;
          --accent: #0d6d66;
        }}
        * {{ box-sizing: border-box; }}
        body {{
          margin: 0;
          font-family: "Segoe UI", sans-serif;
          background: linear-gradient(180deg, #f9f6ef 0%, var(--bg) 100%);
          color: var(--text);
        }}
        main {{
          max-width: 1100px;
          margin: 0 auto;
          padding: 24px;
        }}
        h1 {{ margin: 0 0 12px; }}
        p {{ color: var(--muted); }}
        .grid {{
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
          gap: 12px;
          margin: 20px 0 24px;
        }}
        .card {{
          background: var(--card);
          border: 1px solid var(--line);
          border-radius: 16px;
          padding: 16px;
          box-shadow: 0 8px 30px rgba(35, 31, 26, 0.05);
        }}
        .label {{
          font-size: 13px;
          color: var(--muted);
          margin-bottom: 6px;
        }}
        .value {{
          font-size: 24px;
          font-weight: 700;
        }}
        table {{
          width: 100%;
          border-collapse: collapse;
        }}
        th, td {{
          text-align: left;
          padding: 10px 12px;
          border-bottom: 1px solid var(--line);
          vertical-align: top;
        }}
        th {{
          color: var(--muted);
          font-size: 13px;
          font-weight: 600;
        }}
        h2 {{
          margin: 0 0 12px;
          font-size: 20px;
        }}
        .section {{
          margin-top: 18px;
        }}
        code {{
          background: #efe7d8;
          padding: 2px 6px;
          border-radius: 6px;
        }}
      </style>
    </head>
    <body>
      <main>
        <h1>Админка Фото в чат</h1>
        <p>Это не полноценная панель управления, а быстрый обзор того, что реально лежит на сервере.</p>

        <div class="grid">
          <section class="card"><div class="label">Городов</div><div class="value">{summary['cities_count']}</div></section>
          <section class="card"><div class="label">Устройств</div><div class="value">{summary['devices_count']}</div></section>
          <section class="card"><div class="label">Записей в логе</div><div class="value">{summary['uploads_count']}</div></section>
          <section class="card"><div class="label">Файлов на диске</div><div class="value">{summary['stored_files_count']}</div></section>
          <section class="card"><div class="label">Занято места</div><div class="value">{format_bytes(summary['stored_files_bytes'])}</div></section>
          <section class="card"><div class="label">Удалять успешные фото</div><div class="value">{'Да' if not summary['keep_delivered_files'] else 'Нет'}</div></section>
        </div>

        <section class="card section">
          <h2>Города</h2>
          <table>
            <thead>
              <tr>
                <th>Город</th>
                <th>Транспорт</th>
                <th>MAX chat ID</th>
                <th>Telegram chat ID</th>
                <th>Telegram topic ID</th>
              </tr>
            </thead>
            <tbody>{cities_rows}</tbody>
          </table>
        </section>

        <section class="card section">
          <h2>Устройства</h2>
          <table>
            <thead>
              <tr>
                <th>Device UUID</th>
                <th>Город</th>
                <th>Обновлено</th>
              </tr>
            </thead>
            <tbody>{devices_rows}</tbody>
          </table>
        </section>

        <section class="card section">
          <h2>Полезные ссылки</h2>
          <p><code>/health</code>, <code>/cities</code>, <code>/devices</code>, <code>/admin/summary</code></p>
        </section>
      </main>
    </body>
    </html>
    """
    return HTMLResponse(html)


def serialize_upload_job(job: dict[str, Any]) -> dict[str, Any]:
    return {
        "job_id": job.get("job_id"),
        "status": job.get("status"),
        "device_uuid": job.get("device_uuid"),
        "client_upload_id": job.get("client_upload_id"),
        "city_name": job.get("city_name"),
        "city_slug": job.get("city_slug"),
        "file_sha256": job.get("file_sha256"),
        "transport_type": job.get("transport_type"),
        "accepted_at": job.get("accepted_at"),
        "file_received_at": job.get("file_received_at"),
        "uploaded_at": job.get("uploaded_at"),
        "sent_at": job.get("sent_at"),
        "error": job.get("error"),
        "file_deleted": bool(job.get("file_deleted")),
        "message_id": job.get("message_id"),
        "chat_deleted": bool(job.get("chat_deleted")),
        "chat_deleted_at": job.get("chat_deleted_at"),
    }


def process_upload_job_async(job_id: str) -> None:
    threading.Thread(target=process_upload_job, args=(job_id,), daemon=True).start()


def process_upload_job(job_id: str) -> None:
    job = claim_upload_job(job_id)
    if not job:
        return

    path_value = job.get("file_path")
    if not path_value:
        update_upload_job(job_id, {"status": "failed", "error": "File path is missing"})
        return

    path = Path(path_value)
    if not path.exists():
        update_upload_job(job_id, {"status": "failed", "error": "File is missing"})
        return

    city = find_city(str(job.get("city_slug") or ""))
    if not city:
        update_upload_job(job_id, {"status": "failed", "error": "City is not configured"})
        return

    send_started_at = time.perf_counter()
    send_duration_ms = None

    try:
        if city["transport_type"] == "telegram":
            result = send_to_telegram(city, path)
        elif city["transport_type"] == "max":
            sync_report_city_binding(city)
            result = send_to_max(city, path)
            increment_report_image_count(city.get("max_chat_id"))
            photo_message_id = result.get("message_id") if isinstance(result, dict) else None
            if photo_message_id:
                upsert_ml_dataset_entry(
                    str(job.get("dataset_id") or ""),
                    {
                        "delivery_status": "sent",
                        "delivery_result": slim_delivery_result(result),
                        "photo_message_id": str(photo_message_id),
                        "chat_id": city.get("max_chat_id"),
                        "review_status": REVIEW_STATUS_PENDING,
                    },
                )
                try:
                    attach_review_keyboard_to_max_photo(city, str(photo_message_id))
                except Exception:
                    logger.exception(
                        "Failed to attach same-message review keyboard city=%s message_id=%s",
                        city["city_name"],
                        photo_message_id,
                    )
        else:
            raise RuntimeError(f"Unsupported transport_type: {city['transport_type']}")

        send_duration_ms = round((time.perf_counter() - send_started_at) * 1000, 1)
        delivery_result = slim_delivery_result(result)
        message_id = delivery_result.get("message_id") if isinstance(delivery_result, dict) else None
        update_upload_job(
            job_id,
            {
                "status": "sent",
                "sent_at": datetime.now(timezone.utc).isoformat(),
                "delivery_result": delivery_result,
                "message_id": message_id,
                "error": None,
            },
        )
        if city["transport_type"] != "max":
            upsert_ml_dataset_entry(
                str(job.get("dataset_id") or ""),
                {
                    "delivery_status": "sent",
                    "delivery_result": delivery_result,
                    "chat_id": city.get("telegram_chat_id"),
                },
            )
        if not KEEP_DELIVERED_FILES and path.exists():
            path.unlink()
            update_upload_job(job_id, {"file_deleted": True, "file_path": None})
        logger.info(
            "Upload-v2 job sent: city=%s device=%s transport=%s job_id=%s send_ms=%s message_id=%s",
            city["city_name"],
            job.get("device_uuid"),
            city["transport_type"],
            job_id,
            send_duration_ms,
            message_id,
        )
    except Exception as exc:
        send_duration_ms = round((time.perf_counter() - send_started_at) * 1000, 1)
        update_upload_job(
            job_id,
            {
                "status": "failed",
                "error": str(exc),
                "failed_at": datetime.now(timezone.utc).isoformat(),
            },
        )
        upsert_ml_dataset_entry(
            str(job.get("dataset_id") or ""),
            {
                "delivery_status": "failed",
                "delivery_error": str(exc),
            },
        )
        logger.warning(
            "Upload-v2 job failed: city=%s device=%s transport=%s job_id=%s send_ms=%s error=%s",
            city["city_name"],
            job.get("device_uuid"),
            city["transport_type"],
            job_id,
            send_duration_ms,
            exc,
        )
    finally:
        cleanup_storage()


@app.on_event("startup")
def resume_pending_upload_jobs() -> None:
    for job in read_upload_jobs():
        if job.get("status") in {"queued", "uploaded_to_server", "sending_to_chat"}:
            process_upload_job_async(str(job.get("job_id")))


@app.post("/upload")
async def upload_photo(
    photo: UploadFile = File(...),
    deviceUuid: str = Form(...),
) -> JSONResponse:
    request_started_at = time.perf_counter()
    device = find_device(deviceUuid)
    if not device:
        raise HTTPException(status_code=404, detail="Device is not registered")

    city = find_city(device["city_slug"])
    if not city:
        raise HTTPException(status_code=404, detail="City is not configured")

    city_dir = UPLOAD_DIR / city["city_slug"]
    city_dir.mkdir(exist_ok=True)

    filename = f"{uuid.uuid4()}_{photo.filename}"
    path = city_dir / filename

    with path.open("wb") as file:
        file.write(await photo.read())

    uploaded_at = datetime.now(timezone.utc).isoformat()
    dataset_entry = capture_ml_dataset_photo(
        path,
        device_uuid=deviceUuid,
        city=city,
        uploaded_at=uploaded_at,
    )

    log_entry = {
        "uploaded_at": uploaded_at,
        "device_uuid": deviceUuid,
        "dataset_id": dataset_entry["dataset_id"],
        "city_name": city["city_name"],
        "city_slug": city["city_slug"],
        "transport_type": city["transport_type"],
        "max_chat_id": city["max_chat_id"],
        "telegram_chat_id": city["telegram_chat_id"],
        "telegram_thread_id": city["telegram_thread_id"],
        "file_path": str(path),
        "file_deleted": False,
        "delivery_status": "pending",
        "delivery_result": None,
        "delivery_error": None,
    }
    send_started_at = None
    send_duration_ms = None

    try:
        if city["transport_type"] == "telegram":
            send_started_at = time.perf_counter()
            result = send_to_telegram(city, path)
        elif city["transport_type"] == "max":
            sync_report_city_binding(city)
            send_started_at = time.perf_counter()
            result = send_to_max(city, path)
            increment_report_image_count(city.get("max_chat_id"))
            photo_message_id = (
                result.get("message_id")
                if isinstance(result, dict)
                else None
            )
            if photo_message_id:
                upsert_ml_dataset_entry(
                    dataset_entry["dataset_id"],
                    {
                        "delivery_status": "sent",
                        "delivery_result": slim_delivery_result(result),
                        "photo_message_id": str(photo_message_id),
                        "chat_id": city.get("max_chat_id"),
                        "review_status": REVIEW_STATUS_PENDING,
                    },
                )
                try:
                    attach_review_keyboard_to_max_photo(city, str(photo_message_id))
                except Exception:
                    logger.exception(
                        "Failed to attach same-message review keyboard city=%s message_id=%s",
                        city["city_name"],
                        photo_message_id,
                    )
        else:
            raise RuntimeError(f"Unsupported transport_type: {city['transport_type']}")

        if send_started_at is not None:
            send_duration_ms = round((time.perf_counter() - send_started_at) * 1000, 1)
        log_entry["delivery_status"] = "sent"
        log_entry["delivery_result"] = slim_delivery_result(result)
        if city["transport_type"] != "max":
            upsert_ml_dataset_entry(
                dataset_entry["dataset_id"],
                {
                    "delivery_status": "sent",
                    "delivery_result": slim_delivery_result(result),
                    "chat_id": city.get("telegram_chat_id"),
                },
            )
        if not KEEP_DELIVERED_FILES and path.exists():
            path.unlink()
            log_entry["file_deleted"] = True
            log_entry["file_path"] = None
        total_duration_ms = round((time.perf_counter() - request_started_at) * 1000, 1)
        logger.info(
            "Upload processed: city=%s device=%s transport=%s status=sent total_ms=%s send_ms=%s message_id=%s",
            city["city_name"],
            deviceUuid,
            city["transport_type"],
            total_duration_ms,
            send_duration_ms,
            (log_entry.get("delivery_result") or {}).get("message_id"),
        )
    except Exception as exc:
        if send_started_at is not None:
            send_duration_ms = round((time.perf_counter() - send_started_at) * 1000, 1)
        log_entry["delivery_status"] = "failed"
        log_entry["delivery_error"] = str(exc)
        upsert_ml_dataset_entry(
            dataset_entry["dataset_id"],
            {
                "delivery_status": "failed",
                "delivery_error": str(exc),
            },
        )
        append_upload_log(log_entry)
        cleanup_storage()
        total_duration_ms = round((time.perf_counter() - request_started_at) * 1000, 1)
        logger.warning(
            "Upload failed: city=%s device=%s transport=%s total_ms=%s send_ms=%s error=%s",
            city["city_name"],
            deviceUuid,
            city["transport_type"],
            total_duration_ms,
            send_duration_ms,
            exc,
        )
        raise HTTPException(status_code=502, detail=str(exc))

    append_upload_log(log_entry)
    cleanup_storage()

    return JSONResponse(
        {
            "status": "ok",
            "stored": str(path) if path.exists() else None,
            "device_uuid": deviceUuid,
            "city": city["city_name"],
            "transport_type": city["transport_type"],
            "delivery_status": log_entry["delivery_status"],
        }
    )


@app.post("/upload-v2")
async def upload_photo_v2(
    photo: UploadFile = File(...),
    deviceUuid: str = Form(...),
    clientUploadId: str | None = Form(default=None),
) -> JSONResponse:
    device = find_device(deviceUuid)
    if not device:
        raise HTTPException(status_code=404, detail="Device is not registered")

    city = find_city(device["city_slug"])
    if not city:
        raise HTTPException(status_code=404, detail="City is not configured")

    client_upload_id = str(clientUploadId or "").strip()
    if client_upload_id:
        existing_job = find_upload_job_by_client_key(deviceUuid, client_upload_id)
        if existing_job:
            return JSONResponse(
                {
                    "status": "accepted",
                    "job": serialize_upload_job(existing_job),
                    "deduplicated": True,
                },
                status_code=202,
            )

    city_dir = UPLOAD_DIR / city["city_slug"]
    city_dir.mkdir(exist_ok=True)

    filename = f"{uuid.uuid4()}_{photo.filename}"
    path = city_dir / filename

    with path.open("wb") as file:
        file.write(await photo.read())

    accepted_at = datetime.now(timezone.utc).isoformat()
    file_sha256 = compute_file_sha256(path)
    existing_fingerprint_job = find_recent_upload_job_by_fingerprint(
        device_uuid=deviceUuid,
        city_slug=city["city_slug"],
        file_sha256=file_sha256,
    )
    if existing_fingerprint_job:
        try:
            path.unlink(missing_ok=True)
        except Exception:
            logger.warning("Failed to delete deduplicated temp upload file: %s", path)
        return JSONResponse(
            {
                "status": "accepted",
                "job": serialize_upload_job(existing_fingerprint_job),
                "deduplicated": True,
                "dedup_reason": "fingerprint",
            },
            status_code=202,
        )

    dataset_entry = capture_ml_dataset_photo(
        path,
        device_uuid=deviceUuid,
        city=city,
        uploaded_at=accepted_at,
    )

    job_id = str(uuid.uuid4())
    job = {
        "job_id": job_id,
        "status": "uploaded_to_server",
        "accepted_at": accepted_at,
        "file_received_at": accepted_at,
        "uploaded_at": accepted_at,
        "sent_at": None,
        "failed_at": None,
        "device_uuid": deviceUuid,
        "client_upload_id": client_upload_id or None,
        "file_sha256": file_sha256,
        "dataset_id": dataset_entry["dataset_id"],
        "city_name": city["city_name"],
        "city_slug": city["city_slug"],
        "transport_type": city["transport_type"],
        "max_chat_id": city.get("max_chat_id"),
        "telegram_chat_id": city.get("telegram_chat_id"),
        "telegram_thread_id": city.get("telegram_thread_id"),
        "file_path": str(path),
        "file_deleted": False,
        "delivery_result": None,
        "message_id": None,
        "error": None,
    }
    append_upload_job(job)
    process_upload_job_async(job_id)

    return JSONResponse(
        {
            "status": "accepted",
            "job": serialize_upload_job(job),
        },
        status_code=202,
    )


@app.get("/upload-jobs/{job_id}")
def get_upload_job_status(job_id: str) -> dict[str, Any]:
    job = get_upload_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Upload job not found")
    return {"status": "ok", "job": serialize_upload_job(job)}


@app.get("/devices/{device_uuid}/recent-jobs")
def get_recent_upload_jobs(device_uuid: str, limit: int = 10) -> dict[str, Any]:
    device = find_device(device_uuid)
    if not device:
        raise HTTPException(status_code=404, detail="Device is not registered")
    safe_limit = max(1, min(limit, 20))
    jobs = [serialize_upload_job(item) for item in list_recent_upload_jobs(device_uuid, safe_limit)]
    return {"status": "ok", "jobs": jobs}


@app.delete("/upload-jobs/{job_id}/chat-message")
def delete_upload_job_message(job_id: str) -> dict[str, Any]:
    return delete_upload_job_chat_message(job_id)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
