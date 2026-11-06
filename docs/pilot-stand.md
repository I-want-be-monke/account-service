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

Используйте выделенную Windows-машину или Windows VM с локальным Docker в режиме Linux containers, Docker Compose, Git и Java 21. Команды рассчитаны на интерактивный CMD. В файле `.cmd` переменные циклов `%G` и `%A` записываются с удвоенным знаком процента. Перед каждым следующим шагом проверьте код завершения предыдущего.

На основном компьютере в корне проекта проверьте пустой вывод `git status --porcelain` и сверьте список отслеживаемых файлов. Экспортируйте дерево одного зафиксированного коммита. Перенесите только полученный TAR и отдельно сверьте его SHA-256.

```bat
git status --porcelain
if not exist .local mkdir .local
for /f %G in ('git rev-parse HEAD') do @set "E11_SOURCE_SHA=%G"
git ls-tree -r --name-only %E11_SOURCE_SHA%
git archive --format=tar --output=.local\e11-pilot-source.tar %E11_SOURCE_SHA%
certutil -hashfile .local\e11-pilot-source.tar SHA256
```

На выделенной машине создайте пустой каталог для исходников. После сверки SHA-256 используйте `git get-tar-commit-id` для независимой сверки коммита внутри архива. В следующих командах TAR находится на один каталог выше.

```bat
certutil -hashfile ..\e11-pilot-source.tar SHA256
git get-tar-commit-id < ..\e11-pilot-source.tar
tar.exe -xf ..\e11-pilot-source.tar
mkdir .local\e11-pilot
git get-tar-commit-id < ..\e11-pilot-source.tar > .local\e11-pilot\source-commit.txt
docker context show
```

Убедитесь, что Docker context локальный, а проект `account-service-e11` и том `account-service-e11_account-data` свободны для новой поставки. Стенд создаёт БД `account_service_e11`. HTTPS, HTTP и PostgreSQL доступны через `127.0.0.1` на портах `18448`, `18048` и `55438`. Порт `18449` предназначен для второго экземпляра. Генератор обращается к localhost и проверяет локальные контейнеры. Запускайте его на хосте стенда и учитывайте его CPU и heap отдельно.

Базовый тестовый Compose задаёт приложению 1 GiB, `-Xms128m -Xmx640m`, PostgreSQL 1,5 GiB. Hikari использует до 10 соединений с таймаутом 10 000 мс. Используйте одинаковую конфигурацию во всех трёх прогонах. Если измерения обоснуют изменение ресурсов, сохраните отдельный тестовый слой и начните сопоставимую серию заново. `work_mem` учитывается на операцию и параллельного работника. [PostgreSQL 17](https://www.postgresql.org/docs/17/runtime-config-resource.html)

Первый запуск создаёт локальные `stand.env` и TLS, поднимает пустой контур и применяет миграции. Затем соберите испытательный образ без кэша слоёв и поднимите эту сборку. Переменные из свежего файла нужны только для команд Compose в этой сессии CMD.

```bat
set "BOOTSTRAP_ADMIN_PASSWORD="
tests\performance\stand.cmd start
for /f "usebackq tokens=1,* delims==" %A in (".local\e11\stand.env") do @set "%A=%B"
set "APP_TLS_FILE=%CD%\.local\e11\tls.p12"
docker compose -p account-service-e11 --env-file .local\e11\stand.env -f compose.yaml -f tests\performance\compose.e11.yaml --profile full build --no-cache app > .local\e11-pilot\build.log 2>&1
docker compose -p account-service-e11 --env-file .local\e11\stand.env -f compose.yaml -f tests\performance\compose.e11.yaml --profile full up -d --no-build --wait --wait-timeout 160 app
curl.exe --cacert .local\e11\tls.pem --fail --silent --show-error https://localhost:18448/actuator/health/readiness
docker exec account-service-e11-app-1 sha256sum /app/account-service.jar > .local\e11-pilot\jar.sha256
docker inspect --format "{{.Name}} {{.Image}} {{index .Config.Labels \"com.docker.compose.config-hash\"}} {{.HostConfig.Memory}} {{.HostConfig.NanoCpus}}" account-service-e11-app-1 account-service-e11-db-1 > .local\e11-pilot\containers.txt
powershell.exe -NoProfile -Command "Get-FileHash Dockerfile,pom.xml,compose.yaml,tests/performance/compose.e11.yaml,tests/performance/seed.sql,tests/performance/LoadHarness.java -Algorithm SHA256 | Select-Object Algorithm,Hash,Path | Export-Csv -NoTypeInformation .local/e11-pilot/config-sha256.csv"
docker port account-service-e11-db-1 5432/tcp > .local\e11-pilot\db-port.txt
docker inspect --format "{{range .Mounts}}{{.Name}} {{.Destination}}{{println}}{{end}}" account-service-e11-db-1 > .local\e11-pilot\db-mounts.txt
docker exec account-service-e11-db-1 df -B1 /var/lib/postgresql/data > .local\e11-pilot\db-disk.txt
docker exec account-service-e11-db-1 psql -X --no-password -v ON_ERROR_STOP=1 -U account_owner -d account_service_e11 -c "SELECT current_database(), version(); SHOW shared_buffers; SHOW work_mem; SHOW maintenance_work_mem; SHOW max_connections; SELECT installed_rank,version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;" > .local\e11-pilot\postgres-settings.txt
type .local\e11-pilot\postgres-settings.txt
```

Сверьте `UP`, порт и том, версии, лимиты, хеши JAR и Compose и шесть успешных строк V1–V6. Полный вывод `docker compose config` и `docker inspect` содержит секреты. Для протокола сохраните указанные выборки. Закройте эту сессию CMD после завершения испытаний.

## Синтетический набор и исходный снимок

`seed.cmd` проверяет изоляцию, пустой журнал проводок, Flyway V1–V6 и включённые ограничения. После генерации выполните FIN-09.

```bat
tests\performance\seed.cmd -Profile pilot -Seed 1101 -ReportMonth 2026-10 -EvidenceDirectory .local\e11-pilot
tests\performance\reconcile.cmd -ReportMonth 2026-10 -Label seeded -EvidenceDirectory .local\e11-pilot
scripts\load-test.cmd --check --users 300 --report-month 2026-10 --fixtures .local\e11-pilot\fixtures.csv
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests\performance\pilot-snapshot.ps1 -Action Capture > .local\e11-pilot\snapshot-before-01.log 2>&1
```

Сверьте в `seed.json` 200 000 счетов, 150 000 синтетических клиентов, 1 000 000 проведённых операций за 12 месяцев, 50 000 корректировок, размер БД и время заполнения. Данные для входа дают 2 ADMIN, 60 OPERATOR, 40 ACCOUNTANT, 18 MANAGER и 180 CLIENT. Октябрь 2026 служит насыщенным отчётным месяцем стандартного seed. `--check` подтверждает маршрутизацию, HTTPS readiness, выделенные счета, роли и данные месяца. До запуска убедитесь, что FIN-09 дала ноль расхождений и аппаратный допуск выполнен.

`Capture` сверяет контейнер, порт и том, останавливает приложения проекта и требует отсутствия подключений к выделенной БД. Он сохраняет `pilot-baseline.dump`, SHA-256 и оглавление архива, создаёт физический шаблон `account_service_e11_pilot_base` и сверяет SHA-256 его логического содержимого с исходной БД. Текстовый дамп со стабильным ключом используется для сравнения. Приложения остаются остановленными. [PostgreSQL pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html) · [PostgreSQL CREATE DATABASE](https://www.postgresql.org/docs/17/sql-createdatabase.html)

## Три прогона с общей исходной базой

Перед вторым и третьим прогоном используйте `Reset` с метками завершённых прогонов `run01` и `run02`. Скрипт сверяет архив и шаблон, создаёт и сверяет копию до переключения, затем транзакционно переименовывает обе БД. Прежняя сохраняется под `account_service_e11_run01` или `account_service_e11_run02`. При ошибке проверки, существующем имени или недостатке места выясните причину, прежде чем запускать приложение. [PostgreSQL ALTER DATABASE](https://www.postgresql.org/docs/17/sql-alterdatabase.html)

В каждом прогоне запускайте тот же контейнер приложения. Проверяйте хеш JAR и readiness, затем FIN-09. Выполняйте нагрузку с `--runs 1`. Пример команд для прогона 01 приведён ниже. Параметр `--report-tail 0` ограничивает взятие новых отчётов стабильной фазой.

```bat
docker start account-service-e11-app-1
docker exec account-service-e11-app-1 sha256sum /app/account-service.jar
curl.exe --cacert .local\e11\tls.pem --fail --silent --show-error --retry 30 --retry-delay 2 --retry-all-errors https://localhost:18448/actuator/health/readiness
tests\performance\reconcile.cmd -ReportMonth 2026-10 -Label before-01 -EvidenceDirectory .local\e11-pilot
scripts\load-test.cmd --users 300 --runs 1 --ramp 300 --warmup 300 --steady 1200 --report-month 2026-10 --reports-per-type 30 --report-tail 0 --telemetry 15 --seed 1101 --fixtures .local\e11-pilot\fixtures.csv --output .local\e11-pilot\load-01
echo %ERRORLEVEL% > .local\e11-pilot\load-01.exit
tests\performance\reconcile.cmd -ReportMonth 2026-10 -Label after-01 -EvidenceDirectory .local\e11-pilot
findstr /b "runId=" .local\e11-pilot\load-01\run-01\run.properties
```

Сверку FIN-09 выполняйте и при коде нагрузки 2, чтобы сохранить фактическое состояние. Подставьте точное значение `runId` из последней команды и проверьте зафиксированные проводки и итоги отчётов. Для следующих измерений используйте `load-02` и `load-03`, а также соответствующие метки `before-NN` и `after-NN`. [Генератор и выходные файлы](../tests/performance/README.md)

```bat
for /f "tokens=1,* delims==" %A in ('findstr /b "runId=" .local\e11-pilot\load-01\run-01\run.properties') do @set "E11_RUN_ID=%B"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests\performance\data.ps1 -Command Ledger -RunId %E11_RUN_ID% -Label after-01 -EvidenceDirectory .local\e11-pilot
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests\performance\review-run.ps1 -EvidenceDirectory .local\e11-pilot -Run 01
echo %ERRORLEVEL% > .local\e11-pilot\review-01.exit
```

Перед каждым `Reset` сохраните исходный JTL, журналы приложения и PostgreSQL, `pg_stat_statements` и вывод сверок. Планы SQL снимайте в отдельной диагностической копии после измерений.

```bat
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests\performance\pilot-snapshot.ps1 -Action Reset -ArchiveTag run01 > .local\e11-pilot\snapshot-before-02.log 2>&1
```

Перед третьим прогоном повторите `Reset` с `-ArchiveTag run02` и журналом `snapshot-before-03.log`. Проверьте одинаковый SHA-256 содержимого БД во всех трёх журналах снимков.

## Отдельная серия первых отчётов

Для каждого из CHARGES, PAYMENTS, DEBT и MOVEMENT восстановите тот же исходный снимок и перезапустите PostgreSQL и приложение. Первый `Reset` после нагрузки используйте с `-ArchiveTag run03`. В следующих `Reset` сохраняйте предыдущее состояние под `cold_charges`, `cold_payments` или `cold_debt`.

В протоколе укажите, какие кэши сброшены. Перезапуск PostgreSQL и JVM сбрасывает кэши процессов, но может оставить файловый кэш Windows и WSL. Если нужен замер с очищенным файловым кэшем, перезагрузите выделенный хост после подготовки копии и до её запуска, зафиксируйте время и действия после загрузки. Проводите первый запрос выбранного типа до просмотра этого отчёта, финансовой нагрузки и EXPLAIN. Результаты серии записывайте отдельно от 30 стабильных измерений каждого вида.



```bat
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests\performance\pilot-snapshot.ps1 -Action Reset -ArchiveTag run03
docker restart account-service-e11-db-1
set "APP_TLS_FILE=%CD%\.local\e11\tls.p12"
docker compose -p account-service-e11 --env-file .local\e11\stand.env -f compose.yaml -f tests\performance\compose.e11.yaml --profile full up -d --no-build --wait --wait-timeout 160 app
scripts\load-test.cmd --cold-report CHARGES --report-month 2026-10 --fixtures .local\e11-pilot\fixtures.csv --output .local\e11-pilot\cold-CHARGES
echo %ERRORLEVEL% > .local\e11-pilot\cold-CHARGES.exit
tests\performance\reconcile.cmd -ReportMonth 2026-10 -Label cold-CHARGES -EvidenceDirectory .local\e11-pilot
```

Повторите с типами PAYMENTS, DEBT и MOVEMENT и отдельными каталогами, кодами возврата и метками. `--cold-report` измеряет первый реальный HTTPS-запрос отчёта через один выделенный аутентифицированный сеанс. Сохраните длительность, код ответа и `report-totals.csv`. Сверьте итоги с полем `reports` соответствующего файла `reconciliation-cold-ТИП.json`. Проверка каждого отчёта в пределах 10 секунд и совпадение итогов обязательны для этой серии.

## Диагностика SQL после измерений

Перед очередным `Reset` сохраните серверные журналы и статистику текущей базы. Следующие команды используют номер прогона 01. Замените его для остальных прогонов.

```bat
docker logs --timestamps account-service-e11-app-1 > .local\e11-pilot\app-01.log 2>&1
docker logs --timestamps account-service-e11-db-1 > .local\e11-pilot\db-01.log 2>&1
docker exec account-service-e11-db-1 psql -X --csv --no-password -v ON_ERROR_STOP=1 -U account_owner -d account_service_e11 -c "SELECT calls,rows,min_exec_time,mean_exec_time,max_exec_time,shared_blks_hit,shared_blks_read,temp_blks_written,query FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database()) ORDER BY total_exec_time DESC LIMIT 25;" > .local\e11-pilot\slow-sql-01.csv
```

Чтобы получить планы фактических SQL приложения, после всех измерений восстановите отдельную копию исходной БД. При вызове `Reset` укажите `-ArchiveTag cold_movement`, чтобы сохранить последнюю базу холодной серии. Включите `auto_explain` для новых подключений `account_app` к этой копии. Сбор планов с фактическими строками влияет на время выполнения. Сохраняйте диагностические замеры отдельно от KPI. [PostgreSQL auto_explain](https://www.postgresql.org/docs/17/auto-explain.html)

```bat
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests\performance\pilot-snapshot.ps1 -Action Reset -ArchiveTag cold_movement
docker exec account-service-e11-db-1 psql -X --no-password -v ON_ERROR_STOP=1 -U account_owner -d postgres -c "ALTER ROLE account_app IN DATABASE account_service_e11 SET session_preload_libraries='auto_explain'; ALTER ROLE account_app IN DATABASE account_service_e11 SET auto_explain.log_min_duration='0ms'; ALTER ROLE account_app IN DATABASE account_service_e11 SET auto_explain.log_analyze=on; ALTER ROLE account_app IN DATABASE account_service_e11 SET auto_explain.log_buffers=on; ALTER ROLE account_app IN DATABASE account_service_e11 SET auto_explain.log_timing=off; ALTER ROLE account_app IN DATABASE account_service_e11 SET auto_explain.log_format='json';"
docker start account-service-e11-app-1
curl.exe --cacert .local\e11\tls.pem --fail --silent --show-error --retry 30 --retry-delay 2 --retry-all-errors https://localhost:18448/actuator/health/readiness
scripts\load-test.cmd --cold-report CHARGES --report-month 2026-10 --fixtures .local\e11-pilot\fixtures.csv --output .local\e11-pilot\plans-CHARGES
```

Повторите последний вызов для PAYMENTS, DEBT и MOVEMENT с отдельными каталогами `plans-ТИП`. Сохраните планы из журнала PostgreSQL и отключите диагностические настройки. Для каждого типа найдите планы итогового запроса и страницы деталей. Фактическое число обработанных строк проверяйте в планах, число строк отчёта в `report-totals.csv`.

```bat
docker logs --timestamps account-service-e11-db-1 > .local\e11-pilot\report-plans.log 2>&1
docker stop account-service-e11-app-1
docker exec account-service-e11-db-1 psql -X --no-password -v ON_ERROR_STOP=1 -U account_owner -d postgres -c "ALTER ROLE account_app IN DATABASE account_service_e11 RESET session_preload_libraries; ALTER ROLE account_app IN DATABASE account_service_e11 RESET auto_explain.log_min_duration; ALTER ROLE account_app IN DATABASE account_service_e11 RESET auto_explain.log_analyze; ALTER ROLE account_app IN DATABASE account_service_e11 RESET auto_explain.log_buffers; ALTER ROLE account_app IN DATABASE account_service_e11 RESET auto_explain.log_timing; ALTER ROLE account_app IN DATABASE account_service_e11 RESET auto_explain.log_format;"
```

## Комплект результатов

- Версии хоста, Java, приложения, Docker, Compose и PostgreSQL, SHA исходников и тестового Compose, фактические ресурсы и параметры, время запуска и номера прогонов.
- `seed.json`, сводка набора, FIN-09 до и после каждого прогона, проверка количества успешных финансовых действий по `runId`, независимая сверка итогов четырёх отчётов. Храните общий синтетический снимок и протокол его восстановления.
- Все `raw-results.jtl`, `scenario-results.csv`, `summary.csv`, `run.properties`, `report-totals.csv`, `telemetry.csv`, `actuator-metrics.jsonl`. Сверьте число активных сеансов и фазы, p50/p95/p99 экранных запросов по каждому маршруту, фактические 5xx и неожиданные ответы, максимальную длительность каждого типа отчёта и число успешных измерений.
- Docker CPU/RAM, доступная память Windows и WSL2, свободный диск, CPU и heap генератора, heap приложения, активные/ожидающие соединения Hikari, выборки долгих запросов и блокировок PostgreSQL. Сохраните журналы приложения и PostgreSQL и реальные `EXPLAIN (ANALYZE, BUFFERS)` на пилотном наборе после измерений. Планы измеряйте отдельно от основного профиля.

Для PERF-01 проверяйте экранный p95 ≤ 2 секунд при 300 пользователях по общему набору экранных запросов и по каждому типовому маршруту. Для PERF-02 проверяйте максимум ≤ 10 секунд по каждому из четырёх типов отчёта при том же профиле, с фактическим пиком из пяти параллельных запросов и минимум 30 завершёнными отчётами каждого типа в каждом прогоне. Для AT-09 нужны полные фазы, 300 сеансов, отсутствие неожиданных ответов и 5xx, финансовая и отчётная сверки. Сохраните три кода 0 от `review-run.ps1`, подтверждение одинакового снимка перед каждым прогоном и все четыре сверки холодных отчётов с SQL. Если замер нарушил порог или оказался неполным, зафиксируйте фактический результат.

Для итоговой приёмки E11 отдельно измерьте SR-24 на пилотном объёме. Используйте восстановление проверенной копии синтетической БД в отдельный тестовый PostgreSQL и фиксируйте RTO от начала восстановления до HTTPS readiness, входа ролей и FIN-09. [Порядок восстановления](backup.md)

После испытаний `tests\performance\stand.cmd stop` останавливает изолированные сервисы и сохраняет их том и протоколы.
