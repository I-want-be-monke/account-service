# Изолированный стенд полного пилота E11

Полный профиль проверяет PERF-01, PERF-02 и нагрузочный сценарий AT-09. Набор содержит 200 000 лицевых счетов и не менее 1 000 000 проведённых операций. Каждый из трёх прогонов включает 300 отдельных HTTPS-сеансов, 5 минут разгона, 5 минут прогрева и 20 минут стабильного измерения. Для сравнения прогонов подготовьте один исходный синтетический снимок и начинайте каждый прогон с его копии.

## Ресурсы и допуск к запуску

Порог SR-04 проверяется по ресурсам, доступным Docker, включая минимум 4 vCPU, 8 000 000 000 байт памяти и SSD ёмкостью от 100 GB. Значение 8 GB равно 8 000 000 000 байт, 8 GiB равно 8 589 934 592 байт. Используйте целые байты из `docker info`. Генератор данных требует минимум 20 GiB свободного места на диске checkout и 10 GiB в контейнере PostgreSQL. Дополнительно проверьте физический диск с Docker VHDX. Выделите больше места, если сохраняете исходный снимок, все три базы прогонов и резервные копии.

На `DESKTOP-LSF4GK5` 6 ноября 2026 года Windows видела 16 469 520 384 байта памяти при установленных 16 GiB. Движку Docker были доступны 12 vCPU и 7 976 300 544 байта памяти, то есть 7,976 GB или 7,429 GiB. Значение в `/proc/meminfo` WSL2 совпало с показанием Docker. До порога 8 GB не хватало 23 699 456 байт. На SSD было свободно 66 413 756 416 байт. Файл `.wslconfig` отсутствовал. На момент замера Windows показывала 3 382 706 176 доступных байт памяти.

При сокращённой нагрузке 60 сеансов на 50 000 счетов и 250 000 проведённых операций CPU хоста достиг 97,7% в стабильной фазе первого прогона, а доступная память в этой фазе опустилась до 1 855 922 176 байт. Пик 100% и минимум памяти 1 025 056 768 байт зафиксированы при разгоне до появления активных сеансов. CPU генератора в том же прогоне достигал 7,4%. Полный профиль на этом хосте остаётся непроверенным. [Ограничения локальной проверки](limitations.md)

Для переноса пилота запланируйте отдельную Windows-машину с 32 GiB физической памяти, не менее 8 vCPU для Docker, не менее 16 GiB фактически доступной ему памяти и SSD ёмкостью от 200 GB с примерно 80 GB свободного места перед подготовкой. Это стартовый бюджет, который нужно подтвердить измерениями на целевом хосте. Для WSL2 предел памяти задаётся в WSL Settings или `.wslconfig`. После применения настроек на выделенной машине снова проверьте `docker info` и доступную память Windows. Настройка WSL2 распространяется на все его дистрибутивы на этой машине. [Microsoft WSL](https://learn.microsoft.com/en-us/windows/wsl/wsl-config) · [Docker Desktop](https://docs.docker.com/desktop/settings-and-maintenance/settings/#advanced)

Зафиксируйте вывод на пилотном хосте. В колонках вывода Docker значения CPU и памяти разделяются пробелом, память приведена в байтах.

```bat
hostname
java -version
docker version --format "{{.Server.Version}}"
docker compose version
docker info --format "{{.NCPU}} {{.MemTotal}} {{.KernelVersion}}"
wsl --version
wsl --list --verbose
powershell -NoProfile -Command "Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory,NumberOfLogicalProcessors; Get-CimInstance Win32_OperatingSystem | Select-Object TotalVisibleMemorySize,FreePhysicalMemory; Get-PhysicalDisk | Select-Object FriendlyName,MediaType,Size,HealthStatus; Get-Volume | Select-Object DriveLetter,Size,SizeRemaining"
```

Поля `TotalVisibleMemorySize` и `FreePhysicalMemory` из CIM приведены в KiB. На хосте с WSL2 дополните протокол `MemTotal`, `MemAvailable` и `SwapTotal` из `/proc/meminfo` реально используемого дистрибутива. Укажите загрузку хоста перед началом, а при совместном размещении генератора и сервиса сохраните CPU и heap генератора отдельно.

## Подготовка чистой среды

1. Перенесите репозиторий на выделенный хост через Git и сверьте SHA коммита. В перенос включите исходники, `compose.yaml`, `scripts` и `tests/performance`. Сгенерируйте локальные учётные данные и TLS заново. Источник данных для пилота — `tests/performance/seed.sql`.
2. Проверьте доступность Java 21, Docker Desktop, Docker Compose и свободных портов. Стенд использует проект Compose `account-service-e11`, БД `account_service_e11`, том `account-service-e11_account-data`, HTTPS `127.0.0.1:18448`, HTTP `127.0.0.1:18048` и PostgreSQL `127.0.0.1:55438`. Порт `18449` предназначен для отдельной проверки второго экземпляра. Текущий генератор запускается на хосте стенда и обращается к `localhost`.
3. Сохраните версию приложения и PostgreSQL, фактические лимиты памяти/CPU контейнеров, JVM heap, параметры Hikari и PostgreSQL. Базовый тестовый Compose задаёт приложению 1 GiB, `-Xms128m -Xmx640m`, PostgreSQL 1,5 GiB, Hikari — 10 соединений с таймаутом 10 000 мс из конфигурации приложения. Если на выделенном хосте требуется увеличить бюджет, используйте только тестовый слой Compose и приложите его конфигурацию. Возможная стартовая конфигурация для измерений — приложение 4 GiB с `-Xms512m -Xmx2g`, Hikari до 20 соединений, PostgreSQL 6 GiB с `shared_buffers=1GB`, `work_mem=8MB`, `maintenance_work_mem=256MB`, `max_connections=100`. `work_mem` учитывается отдельно для каждой операции сортировки или хеширования и для каждого параллельного работника. Сохраните `pg_stat_statements` и логирование долгих запросов из тестового слоя. [PostgreSQL 17](https://www.postgresql.org/docs/17/runtime-config-resource.html)
4. Запустите изолированный стенд, проверьте его идентичность и только затем заполняйте. `stand.cmd` создаёт `.local\e11\stand.env` и `.local\e11\tls.p12` в текущем checkout. Команды ниже рассчитаны на CMD из корня репозитория.

```bat
tests\performance\stand.cmd start
docker inspect --format "{{index .Config.Labels \"com.docker.compose.project\"}} {{index .Config.Labels \"com.docker.compose.service\"}} {{.HostConfig.Memory}} {{.HostConfig.NanoCpus}}" account-service-e11-app-1 account-service-e11-db-1
docker port account-service-e11-db-1 5432/tcp
docker inspect --format "{{range .Mounts}}{{.Name}} {{.Destination}}{{println}}{{end}}" account-service-e11-db-1
docker exec account-service-e11-db-1 df -B1 /var/lib/postgresql/data
docker exec account-service-e11-db-1 psql -X --no-password -U account_owner -d account_service_e11 -c "SELECT current_database(), version(); SHOW shared_buffers; SHOW work_mem; SHOW maintenance_work_mem; SHOW max_connections;"
tests\performance\seed.cmd -Profile pilot -Seed 1101 -ReportMonth 2026-10 -EvidenceDirectory .local\e11-pilot
tests\performance\reconcile.cmd -ReportMonth 2026-10 -Label before-01 -EvidenceDirectory .local\e11-pilot
scripts\load-test.cmd --check --users 300 --report-month 2026-10 --fixtures .local\e11-pilot\fixtures.csv
```

Сверьте в `seed.json` 200 000 счетов, 150 000 синтетических клиентов, 1 000 000 проведённых операций за 12 месяцев, 50 000 корректировок, размер БД и время заполнения. Данные для входа должны дать 2 ADMIN, 60 OPERATOR, 40 ACCOUNTANT, 18 MANAGER и 180 CLIENT. Октябрь 2026 служит насыщенным отчётным месяцем для стандартного seed. `--check` подтверждает маршрутизацию, HTTPS readiness, существование выделенных счетов, роли, владение и наличие данных за отчётный месяц. Перед запуском полного профиля отдельно проверьте аппаратный допуск и фактический объём. Запуск нагрузки с 300 пользователями повторно проверяет минимум CPU, памяти и объёма набора.

## Три прогона с общей исходной базой

Финансовые сценарии создают реальные проводки. Используйте `--runs 1` для каждого измерения, чтобы между прогонами снова открыть исходную копию. На выделенном PostgreSQL сохраните проверяемый логический `pg_dump -Fc` синтетической базы. Проверьте SHA-256, каталог `pg_restore --list` и восстановление. FIN-09 запускается штатной командой `reconcile.cmd`, когда восстановленная копия снова доступна по имени `account_service_e11` в выделенном стенде. Для быстрого повтора можно также сохранить исходную БД как шаблон. Выполняйте эти операции после сверки меток Compose, порта, тома и имени БД.

1. Остановите только приложения из проекта `account-service-e11` и проверьте отсутствие подключений к `account_service_e11` в `pg_stat_activity`. Подключившись к служебной БД `postgres` того же контейнера от `account_owner`, выполните `CREATE DATABASE account_service_e11_seed_template WITH TEMPLATE account_service_e11 OWNER account_owner` отдельной командой. Снова запустите приложение через `stand.cmd start`. [PostgreSQL 17](https://www.postgresql.org/docs/17/sql-createdatabase.html)
2. Выполните первый прогон и посленагрузочную сверку. Затем снова остановите приложения и проверьте подключения. Из БД `postgres` отдельными командами переименуйте `account_service_e11` в `account_service_e11_run01` и создайте `account_service_e11` из `account_service_e11_seed_template`. Откройте приложение и повторите сверку FIN-09, размеры и сравнение итогов с `before-01` до следующего запуска. Перед третьим прогоном сохраните вторую базу под именем `account_service_e11_run02` и повторите восстановление. Если база с именем архива уже существует, выясните её происхождение до продолжения. [PostgreSQL 17](https://www.postgresql.org/docs/17/sql-alterdatabase.html)
3. Сохраните журналы и копии баз всех прогонов до завершения приёмки. Между прогонами придерживайтесь согласованной процедуры запуска и фиксируйте фактическое состояние кэшей. Метка `first-of-run` в генераторе обозначает первую запланированную попытку каждого типа отчёта. Для измерения после перезапуска соберите отдельную серию и запишите, какие кэши были сброшены. Перезапуск PostgreSQL и JVM сам по себе оставляет возможный файловый кэш ОС.

```bat
scripts\load-test.cmd --users 300 --runs 1 --ramp 300 --warmup 300 --steady 1200 --report-month 2026-10 --reports-per-type 30 --report-tail 0 --telemetry 15 --seed 1101 --fixtures .local\e11-pilot\fixtures.csv --output .local\e11-pilot\load-01
tests\performance\reconcile.cmd -ReportMonth 2026-10 -Label after-01 -EvidenceDirectory .local\e11-pilot
```

Для следующих измерений используйте новые каталоги `load-02`, `load-03` и метки `before-02`, `after-02`, `before-03`, `after-03`. При `--report-tail 0` генератор прекращает брать новые задания из очереди после стабильной фазы. Проверьте в `raw-results.jtl`, `report-totals.csv` и `run.properties` 300 сеансов, по 30 успешных запросов каждого вида отчёта в `STEADY` и отсутствие незавершённых заданий. Сохраняйте код возврата каждой команды вместе с результатами. Код 0 сокращённого запуска относится к его фактическому профилю. [Генератор и выходные файлы](../tests/performance/README.md)

## Комплект результатов

- Версии хоста, Java, приложения, Docker, Compose и PostgreSQL, SHA исходников и тестового Compose, фактические ресурсы и параметры, время запуска и номера прогонов.
- `seed.json`, сводка набора, FIN-09 до и после каждого прогона, проверка количества успешных финансовых действий по `runId`, независимая сверка итогов четырёх отчётов. Храните общий синтетический снимок и протокол его восстановления.
- Все `raw-results.jtl`, `scenario-results.csv`, `summary.csv`, `run.properties`, `report-totals.csv`, `telemetry.csv`, `actuator-metrics.jsonl`. Сверьте число активных сеансов и фазы, p50/p95/p99 экранных запросов по каждому маршруту, фактические 5xx и неожиданные ответы, максимальную длительность каждого типа отчёта и число успешных измерений.
- Docker CPU/RAM, доступная память Windows и WSL2, свободный диск, CPU и heap генератора, heap приложения, активные/ожидающие соединения Hikari, выборки долгих запросов и блокировок PostgreSQL. Сохраните журналы приложения и PostgreSQL и реальные `EXPLAIN (ANALYZE, BUFFERS)` на пилотном наборе после измерений. Планы измеряйте отдельно от основного профиля.

Для PERF-01 проверяйте экранный p95 ≤ 2 секунд при 300 пользователях по общему набору экранных запросов и по каждому типовому маршруту. Для PERF-02 проверяйте максимум ≤ 10 секунд по каждому из четырёх типов отчёта при том же профиле, с очередью из 1–5 параллельных запросов и минимум 30 завершёнными отчётами каждого типа в каждом прогоне. Для нагрузочной части AT-09 добавьте результат финансовой и отчётной сверок, полноту фаз и отсутствие неожиданных ответов и 5xx. Профиль, выходящий за заданные пороги или оставляющий обязательные замеры неполными, сохраняйте с фактическими результатами. После завершения `tests\performance\stand.cmd stop` останавливает только изолированные сервисы и сохраняет их том и материалы приёмки.
