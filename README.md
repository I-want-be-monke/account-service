# Сервис лицевых счетов клиентов — E1

Исполняемый фундамент на Java 21, Spring Boot, PostgreSQL 17, Flyway, Spring Security и Spring Session JDBC. На этом этапе работают вход, главная страница и выход. Управление счетами и денежные операции появятся на следующих этапах.

## Требования

- Windows CMD, JDK **21** (`java -version`), Docker Desktop с Linux Engine (`docker info`), свободные порты 55432, 8080, 8443.
- Интернет при первой сборке для Maven 3.9.11 и зависимостей. Maven скачивается локальным Wrapper и проверяется по SHA-512.
- `JAVA_HOME` должен указывать на JDK 21. Например, в текущем CMD: `set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21"` и `set "PATH=%JAVA_HOME%\bin;%PATH%"` (подставьте свой фактический путь).

Если `docker info` в обычном CMD возвращает `Access is denied`, откройте Docker Desktop и убедитесь, что используется Linux Engine. Проверьте `whoami /groups | findstr /i docker-users`. Если пользователь не состоит в группе, администратор Windows может добавить его в `docker-users`, после чего нужно выйти из учетной записи Windows и войти снова. Ограниченный терминал внутри ассистента может показывать эту группу как `Deny only` даже при ее наличии у обычного пользовательского сеанса.

## Первый и повторный запуск

Откройте CMD:

```bat
cd /d C:\vyz\incheneria\account-service
scripts\dev-up.cmd
```

Скрипт при первом запуске создает `.env` со случайными паролями и `.local\dev.p12` с самоподписанным сертификатом, поднимает PostgreSQL, собирает JAR и запускает приложение. При повторном запуске использует прежние секреты и тот же Docker volume. **Не копируйте пустой `.env.example` поверх созданного `.env`.** Не удаляйте `.env`, пока используете существующую БД: пароли БД и хэши учебных пользователей сохраняются.

Откройте `https://localhost:8443/login`. Подтвердите исключение для локального самоподписанного сертификата только в этой учебной среде. Логины: `demo-admin`, `demo-operator`, `demo-accountant`, `demo-manager`, `demo-client`. Соответствующие пароли находятся в локальном `.env` в переменных `DEMO_ADMIN_PASSWORD` и т. д. Файл исключен из Git. Запускайте профиль `dev` только на своей машине; у него есть предсказуемые логины.

Остановка в CMD:

```bat
scripts\dev-down.cmd
```

Она останавливает приложение и PostgreSQL, **не удаляя volume с данными**. Повторный `dev-up.cmd` поднимает их снова. Логи приложения — `.local\logs\stdout.log`, `.local\logs\stderr.log` и `.local\logs\account-service.log`.

## Сборка и тесты

При запущенном Docker Desktop:

```bat
mvnw.cmd test
mvnw.cmd verify
```

`test` запускает JUnit для BCrypt. `verify` дополнительно запускает `AuthenticationIT` в **настоящем PostgreSQL Testcontainers**, проверяет V1/V2, роли, хэши, повторную миграцию, вход, отказ, CSRF, JDBC-сеанс и выход. H2 не используется. Отчеты — `target\surefire-reports` и `target\failsafe-reports`. Сборка для запуска: `mvnw.cmd -DskipTests package`.

## Ручная проверка E1

1. После `dev-up.cmd` проверьте `https://localhost:8443/actuator/health/readiness` — статус `UP`. `http://localhost:8080/login` должен перенаправить на HTTPS.
2. Откройте страницу входа, введите `demo-admin` и пароль из `.env`: появится главная страница с ролью ADMIN. Неверный пароль дает сообщение без входа. У остальных четырех пользователей отображаются свои роли.
3. Нажмите «Выйти». Обновление `https://localhost:8443/` ведет к форме входа; POST без CSRF отклоняется.
4. Остановите и повторно запустите проект. Войдите тем же паролем; данные не теряются. Проверить миграции и пользователей можно командой (пароль владельца БД берется из локального `.env`):

```bat
docker compose --env-file .env exec db psql -U account_owner -d accounts -c "select version, success from flyway_schema_history order by installed_rank"
docker compose --env-file .env exec db psql -U account_owner -d accounts -c "select role_code, count(*) from app_user group by role_code order by role_code"
```

## Безопасность и границы

`.env`, сертификат, кеш Wrapper, сборка и логи исключены из Git. Пароли хранятся в БД только как BCrypt-хэши. В dev TLS самоподписан; рабочий сертификат и внешний секрет нужны при развертывании. Профиль без `dev` не создает учетные записи: процедура безопасного начального администрирования запланирована на E2. Отдельные права для операций и интерфейс предметных модулей также относятся к E2–E12. Состояние этапа и фактические проверки — в `docs\project-status.md`.
