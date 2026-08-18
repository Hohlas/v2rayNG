# Контекст форка v2rayNG

Продолжай работу с форком **v2rayNG** — Android-клиентом для проксирования. Локальный репозиторий: `/home/hohla/git/v2rayNG`.

## Контекст

- Ветка `speed-test-2.2.6` — форк официального v2rayNG (upstream: https://github.com/2dust/v2rayNG) версии 2.2.6.
- В неё портирован авторский патч «speed test» (проверка скорости загрузки серверов + сортировка по скорости) с ветки `speed-test-2.1.5`. История ветки: коммиты `744dea31`, `e8b65d1d`, `25a4686d`, `fb917ea7` (перенос патча), затем `83cfaf14` (обновлён `libv2ray.aar` + импорт `CoroutineScope` для совместимости с 2.2.6), `0395c466` (хардкод 4 подписок по умолчанию при чистой установке), `3ca0a596` (перегенерирован `patches/speed-test.patch`).
- `patches/speed-test.patch` — файл с разницей между чистым 2.2.6 и патченой веткой.
- Управление подписками: 4 группы (URL в `AppConfig.DEFAULT_SUBSCRIPTION_URLS`), приложение должно быть готово к использованию сразу после установки.

## Как собрать

`cd /home/hohla/git/v2rayNG/V2rayNG && ./gradlew :app:assemblePlaystoreRelease`. Готовые APK в `V2rayNG/app/build/outputs/apk/playstore/release/`. Сборка подписана debug-ключом, подходит для установки поверх существующей версии без потери данных.

## Правила работы

Перед изменениями ознакомься с кодом (`AppConfig.kt`, `SettingsManager.kt`, `MainViewModel.kt`, `SpeedTestWorkerService.kt`) и следуй стилю существующего кода. После правок — пересобери и проверь компиляцию.
