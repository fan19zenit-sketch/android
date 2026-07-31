# Simple Photo Uploader

Проект уже рабочий, но не надо романтизировать его текущее состояние: это не SaaS-платформа, а прикладной инструмент под ваши телефоны и ваши чаты. Его сила в простоте, а не в “магии”.

## Что умеет

- Android-приложение снимает фото и сразу отправляет его на backend.
- Backend определяет город по устройству и пересылает фото в нужный канал.
- Поддерживаются два транспорта:
  - `Telegram` в группу/подтему через `chat_id` и `message_thread_id`
  - `MAX` в чат через `chat_id`
- К одному городу можно привязать несколько телефонов.

## Структура

- [`app`](d:/neiroq/android/app) — Android-клиент.
- [`backend`](d:/neiroq/android/backend) — FastAPI backend.
- [`backend/data/cities.json`](d:/neiroq/android/backend/data/cities.json) — города и маршруты доставки.
- [`backend/data/devices.json`](d:/neiroq/android/backend/data/devices.json) — устройства и их города.
- [`backend/data/upload_log.json`](d:/neiroq/android/backend/data/upload_log.json) — журнал отправок.

## Как запустить backend

1. Установить зависимости:

```powershell
cd d:\neiroq\android\backend
pip install -r requirements.txt
```

2. Создать локальный файл токенов:

```powershell
Copy-Item .env.example .env.local
```

3. Открыть [`backend/.env.local`](d:/neiroq/android/backend/.env.local) и заполнить:

```text
TELEGRAM_BOT_TOKEN=...
MAX_BOT_TOKEN=...
KEEP_DELIVERED_FILES=false
FAILED_UPLOAD_RETENTION_HOURS=24
UPLOAD_LOG_RETENTION_DAYS=30
MAX_UPLOAD_LOG_ENTRIES=500
```

4. Запустить backend:

```powershell
cd d:\neiroq\android\backend
.\start-backend.ps1
```

Backend поднимается на `http://127.0.0.1:8000`.

## Как выкатить на сервер

Нормальный путь для сервера здесь один: `Docker Compose`. Всё остальное сейчас будет лишней ручной магией.

Что должно быть на сервере:

- Linux VPS
- Docker
- Docker Compose
- открытый порт `8000` или проксирование через Nginx/Caddy

Файлы для деплоя уже готовы:

- [`backend/Dockerfile`](d:/neiroq/android/backend/Dockerfile)
- [`docker-compose.yml`](d:/neiroq/android/docker-compose.yml)
- [`deploy-backend.ps1`](d:/neiroq/android/deploy-backend.ps1)

Порядок:

1. Скопировать проект на сервер.
2. Создать [`backend/.env.local`](d:/neiroq/android/backend/.env.local) по образцу [`backend/.env.example`](d:/neiroq/android/backend/.env.example).
3. Запустить:

```bash
docker compose up -d --build
```

4. Проверить:

```bash
curl http://127.0.0.1:8000/health
curl http://127.0.0.1:8000/admin/summary
```

Если сервер будет доступен из интернета, лучше вешать домен и HTTPS через reverse proxy. Прямой голый `http://IP:8000` для боевой эксплуатации слабоват.

## Как собрать APK

Если `gradlew` ругается на отсутствие Java, не надо гадать. Используй helper:

```powershell
cd d:\neiroq\android
.\build-debug.ps1
```

Готовые APK теперь два:

- старая параллельная сборка: [`app-legacy-debug.apk`](d:/neiroq/android/app/build/outputs/apk/legacy/debug/app-legacy-debug.apk)
- новая параллельная сборка `Фото в чистоту`: [`app-clean-debug.apk`](d:/neiroq/android/app/build/outputs/apk/clean/debug/app-clean-debug.apk)

## Как настроить телефон

В приложении на первом запуске нужны:

- `Backend URL`
  - для эмулятора: `http://10.0.2.2:8000`
  - для телефона в одной сети с сервером: `http://<IP_твоего_компьютера>:8000`
- `City`
- `Send photos to`
  - `MAX` и `MAX chat ID`
  - или `Telegram`, `Telegram group ID`, `Telegram topic ID`

Если город уже существует, новый телефон можно просто привязать к нему. Дубли городов руками плодить не надо.

## Как раскатать на все телефоны

1. Подними backend один раз и проверь, что открываются `http://<IP_сервера>:8000/health` и `http://<IP_сервера>:8000/admin`.
2. На первом телефоне каждого нового города введи город и нужные ID канала.
3. На втором и следующих телефонах этого же города введи тот же город. Если маршрут уже сохранён, город просто привяжется к существующей записи.
4. После настройки каждого телефона сделай одно тестовое фото. Не верь “мы всё ввели правильно” на слово.
5. Раз в неделю открывай `http://<IP_сервера>:8000/admin` и проверяй:
   - не расплодились ли дубли городов
   - не копятся ли файлы на диске
   - все ли устройства действительно зарегистрированы

## Полезные endpoint'ы

- `GET /health`
- `GET /cities`
- `GET /devices`
- `GET /admin`
- `GET /admin/summary`
- `POST /register-device`
- `POST /upload`

## Важные замечания

- Токены ботов нельзя слать в переписку. Те, что уже засвечены, лучше перевыпустить.
- `Build.MODEL` как идентификатор устройства был бы плохой идеей. Здесь используется `device_uuid`.
- Если backend не запущен, приложение честно будет сыпать ошибки. Это не баг Android-клиента, а отсутствие сервера.
- PowerShell на Windows иногда уродует кириллицу в ручных HTTP-запросах. Для приложения это не критично, но руками лучше не дебажить JSON через “голый” `Invoke-WebRequest`, если хочешь видеть русский текст без мусора.
- По умолчанию backend не хранит успешно доставленные фото. Иначе сервер очень быстро превратится в помойку.
- Лог отправок тоже ограничен: старые записи режутся по сроку и по максимальному количеству, чтобы JSON не рос бесконечно.
