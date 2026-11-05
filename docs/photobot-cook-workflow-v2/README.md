# Фотобот V2: вход для Android-разработки

**Версия:** PHOTOBOT_COOK_WORKFLOW_V2_20261105. **Статус:** документация; изменения приложения ещё не реализованы.

Задача общая для двух репозиториев. Канонический пакет находится в frontpad-parsers; его ветка до слияния — codex/photobot-cook-workflow-v2-spec. Не ограничивать работу только экраном выбора повара.

## Что прочитать

- [Полная задача TASK.md](https://github.com/fan19zenit-sketch/frontpad-parsers/blob/codex/photobot-cook-workflow-v2-spec/operations/photo_platform/docs/cook_workflow_v2/TASK.md).
- [Этапы S0–S8](https://github.com/fan19zenit-sketch/frontpad-parsers/blob/codex/photobot-cook-workflow-v2-spec/operations/photo_platform/docs/cook_workflow_v2/IMPLEMENTATION_PLAN.md).
- [Общий контракт данных](https://github.com/fan19zenit-sketch/frontpad-parsers/blob/codex/photobot-cook-workflow-v2-spec/operations/photo_platform/docs/cook_workflow_v2/DATA_CONTRACT.md).
- [Android, локальная копия](ANDROID.md).
- [Проверки](https://github.com/fan19zenit-sketch/frontpad-parsers/blob/codex/photobot-cook-workflow-v2-spec/operations/photo_platform/docs/cook_workflow_v2/TEST_MATRIX.md).
- [Runtime и выпуск](https://github.com/fan19zenit-sketch/frontpad-parsers/blob/codex/photobot-cook-workflow-v2-spec/operations/photo_platform/docs/cook_workflow_v2/RUNTIME_AND_RELEASE.md).
- [Прогресс всей задачи](https://github.com/fan19zenit-sketch/frontpad-parsers/blob/codex/photobot-cook-workflow-v2-spec/operations/photo_platform/docs/cook_workflow_v2/PROGRESS.md).
- [Один текст для запуска Codex](https://github.com/fan19zenit-sketch/frontpad-parsers/blob/codex/photobot-cook-workflow-v2-spec/operations/photo_platform/docs/cook_workflow_v2/START_CODEX.md).

ANDROID.md здесь — точная копия канонического технического раздела той же версии. Если меняется контракт/поведение, обновить оба документа и общий DATA_CONTRACT, не создавать расходящиеся требования. Ссылка DATA_CONTRACT.md внутри технического текста означает документ backend-пакета.

## Ключевые решения

После 11:00 один раз подтвердить поваров, операторов и основного повара. Дополнительный выбран на один успешно сохранённый снимок; основной возвращается после локальной записи. Сведения о сотруднике фиксируются у фото до отправки, переживают retry и читаются обоими отправителями. Приложение не вводит номера заказов и не выбирает уроки: категория/продукт/причина относятся к QC в MAX.

Целевой flavor — clean, package com.example.photovchistotu. Обычная Bluetooth-съёмка остаётся одним действием. Очередь, история, worker/service и legacy flavor сохраняются. Нужны сохраняющая Room 1→2 и совместимый API с подтверждением метаданных.

Активный backend расположен в **frontpad-parsers/operations/photo_platform/apps/photo_backend**. Копия android/backend не является местом новой серверной реализации. Старое ограничение «APK не менять, остановиться на M0–M3» отменено; весь объём — S0–S8.

## Сборка и результат

Штатная проверочная команда после проверки SDK/JDK и доступных Gradle tasks: ./gradlew :app:assembleCleanDebug. Windows: .\gradlew.bat :app:assembleCleanDebug. Ожидаемый стандартный путь: app/build/outputs/apk/clean/debug/app-clean-debug.apk; фактический путь проверить по результату сборки.

Сборка в ходе подготовки документации не выполнялась. Реализующий Codex должен представить фактические тесты, APK/версию/хеш/тип подписи, либо конкретный блокер среды. Debug APK не считать готовым обновлением рабочих телефонов без проверки совместимой подписи и versionCode. Не удалять установленное приложение ради обхода подписи.
