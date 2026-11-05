# API чтения E7–E9

Базовый адрес: `https://localhost:8443/api/v1`. Используется существующая HTTPS-авторизация с серверным сеансом `ACCOUNT_SESSION`. В браузере сначала войдите через `/login`. API не принимает пароль, токен или роль в параметрах; CORS не открыт. Ответы UTF-8 JSON. Любой ID проверяется на сервере с учетом текущего пользователя и привязки клиента.

## Счет

`GET /api/v1/accounts/{id}` доступен ADMIN, OPERATOR, ACCOUNTANT, MANAGER и владельцу CLIENT. Пример полей:

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "number": "LS-0000000004",
  "status": "OPEN",
  "openedOn": "2026-11-05",
  "closedOn": null,
  "historyStartOn": "2026-11-05",
  "currency": "RUB",
  "balance": "550.00"
}
```

`balance` — полное текущее сальдо счета, строка с двумя десятичными знаками. Положительное значение означает долг, отрицательное — аванс. Пароль, логины сотрудников, внутренние ключи и данные другого клиента не возвращаются.

## Проведенные движения

`GET /api/v1/accounts/{id}/operations?from=&to=&kind=&page=&size=` доступен OPERATOR, ACCOUNTANT, MANAGER и владельцу CLIENT. ADMIN не получает финансовую историю через этот маршрут. Параметры:

| Параметр | Значение |
|---|---|
| `from`, `to` | Необязательные даты `YYYY-MM-DD`, включительно; даты не раньше `historyStartOn`; начало не позже конца. |
| `kind` | Пусто, `CHARGE`, `PAYMENT` или `CORRECTION`. |
| `page` | От 0 до 100000; по умолчанию 0. |
| `size` | От 1 до 100; по умолчанию 20. |

Порядок: `operation_date DESC, posted_at DESC, id DESC`. Одинаковый неизменный набор данных дает страницы без повторов и пропусков. Новое проведение между запросами может изменить смещения страниц; моментальный снимок нескольких разных HTTP-запросов не обещается. В список входят только POSTED, в том числе отдельные исправления. Состояние исходной записи `CORRECTED` вычисляется при наличии проведенного исправления. Фильтр не меняет `accountBalance`.

```json
{
  "accountId": "11111111-1111-1111-1111-111111111111",
  "accountBalance": "550.00",
  "currency": "RUB",
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "items": [{
    "id": "22222222-2222-2222-2222-222222222222",
    "kind": "CORRECTION",
    "status": "POSTED",
    "date": "2026-11-05",
    "amount": "100.00",
    "delta": "-100.00",
    "basis": "Акт проверки",
    "documentNumber": null,
    "originalOperationId": "33333333-3333-3333-3333-333333333333",
    "targetAmount": "900.00",
    "correctionReason": "Уточнение суммы"
  }]
}
```

Неверные даты/параметры дают 400, чужой счет — 403, отсутствие входа — 401. Ошибки JSON имеют `application/problem+json`, поля `status`, `detail`, `code`, `requestId`. Например:

```json
{"status":403,"detail":"Счет недоступен.","code":"ACCOUNT_NOT_OWNED","requestId":"44444444-4444-4444-4444-444444444444"}
```

`X-Request-ID` содержит тот же ID запроса. Изменяющий финансовый API не добавлен. Выписка и сводные отчёты описаны далее.

## Выписка E8: JSON и HTML

`GET /api/v1/accounts/{id}/statement?from=2026-11-04&to=2026-11-05`

Доступ: ADMIN, OPERATOR, ACCOUNTANT, MANAGER и владелец CLIENT через существующий HTTPS-сеанс. Общая `AccountAccessPolicy` проверяет UUID и текущую привязку клиента. Право ADMIN на выписку не открывает ему отдельную историю `/operations` E7. UUID чужого/недоступного счёта для CLIENT всегда даёт 403 без реквизитов.

Оба параметра обязательны: календарные ISO-даты `YYYY-MM-DD`, `from <= to`, не более **366 дней включительно**, `from >= historyStartOn`. Статусы OPEN/SUSPENDED/CLOSED не ограничивают разрешённое чтение. Пустой период успешен. Неверная/отсутствующая дата, обратный или слишком длинный период — 400 `STATEMENT_VALIDATION`; отсутствующий счёт для сотрудника — 404 `ACCOUNT_NOT_FOUND`.

HTML использует тот же документ:

- `/accounts/{id}/statement` — форма периода.
- `/accounts/{id}/statement/result?from=&to=` — результат.
- `/accounts/{id}/statement/print?from=&to=` — печатное представление; кнопка вызывает диалог печати, `print.css` скрывает навигацию.
- `/accounts/{id}/statement.csv?from=&to=` — скачивание CSV.

Успешный JSON — явный `Statement`, без JPA Entity и security-объектов. `formatVersion` равен строке `"1"`; UUID и даты стабильны, `generatedAt` и `postedAt` — ISO-8601 Instant UTC с `Z`, даты — `YYYY-MM-DD`. **Все деньги представлены строками с точкой и двумя знаками**, включая отрицательные значения. Валюта — RUB.

Пример структуры с сокращённым списком операций (показано одно из трёх движений контрольного периода):

```json
{
  "formatVersion": "1",
  "accountId": "11111111-1111-1111-1111-111111111111",
  "accountNumber": "LS-0000000007",
  "ownerId": "22222222-2222-2222-2222-222222222222",
  "ownerName": "Учебный клиент",
  "accountStatus": "OPEN",
  "historyStartOn": "2026-10-01",
  "from": "2026-11-04",
  "to": "2026-11-05",
  "generatedAt": "2026-11-05T19:00:00Z",
  "currency": "RUB",
  "openingBalance": "1000.00",
  "increase": "50.00",
  "decrease": "500.00",
  "closingBalance": "550.00",
  "operations": [{
    "id": "33333333-3333-3333-3333-333333333333",
    "kind": "PAYMENT",
    "status": "CORRECTED",
    "date": "2026-11-04",
    "postedAt": "2026-11-04T12:00:00Z",
    "amount": "400.00",
    "delta": "-400.00",
    "documentNumber": "DEMO-PAYMENT-1",
    "basis": "Синтетический платёж",
    "periodStart": null,
    "paymentMethod": "MANUAL_TRANSFER",
    "originalOperationId": null,
    "targetAmount": null,
    "correctionReason": null,
    "balanceAfter": "600.00"
  }]
}
```

В действительном ответе `operations` содержит **все** проведённые строки периода, без пагинации и PREPARED. У CORRECTION заполнены `originalOperationId`, `targetAmount`, `correctionReason`, а `delta` содержит собственный знаковый эффект. `CORRECTED` у исходной строки вычисляется по проведённым исправлениям не позже `to`, исходный документ не меняется. Порядок: `date`, `postedAt`, `id` по возрастанию; `balanceAfter` — остаток после строки.

Входящее = `opening_balance` счёта + сумма POSTED delta с `history_start_on` строго до `from`. Увеличение = сумма положительных delta периода; уменьшение = абсолютная сумма отрицательных. Исходящее = входящее + увеличение − уменьшение. Движения после `to` не учитываются, текущее `current_balance` не подставляется.

Каждый документ читается отдельной короткой read-only транзакцией `REQUIRES_NEW / REPEATABLE READ`. Метаданные, агрегаты и строки видят один PostgreSQL snapshot. Разные HTTP-запросы HTML/CSV/JSON имеют собственные снимки: после нового проведения они могут законно различаться.

Успешное формирование/экспорт журналируется `STATEMENT_GENERATED` отдельной транзакцией **после чтения и до первого байта ответа**. Событие содержит формат, период, UUID счёта и requestId; оно означает разрешённое формирование, а не подтверждённую доставку человеку. Сбой аудита — 503 `DOCUMENT_AUDIT_UNAVAILABLE`, документ не выдаётся. Недоступная БД — 503 `DATABASE_UNAVAILABLE`; невозможность выгрузки — 503 `DOCUMENT_EXPORT_UNAVAILABLE`. Ошибки JSON сохраняют существующий `application/problem+json`, `code`, `requestId`. Ответы документов имеют `Cache-Control: no-store`.

## CSV выписки: формат версии 1

`GET /accounts/{id}/statement.csv?from=&to=` имеет те же права и параметры. Успех: `text/csv;charset=UTF-8`, `Content-Disposition: attachment; filename="statement-LS-…-from-to.csv"`. Ошибки этого UI-маршрута — обычные безопасные HTML-страницы 400/403/404/503, **не частичный CSV**. Защищённого изменяющего API нет.

Байты: UTF-8, в начале BOM `EF BB BF`; разделитель `;`, конец записи CRLF. Первая запись — имена 31 колонки:

```text
record_type;format_version;account_id;account_number;owner_id;owner_name;account_status;history_start_on;from;to;generated_at;currency;operation_id;operation_date;posted_at;kind;status;document_number;basis;period_start;payment_method;original_operation_id;target_amount;correction_reason;amount;delta;balance_after;opening_balance;increase;decrease;closing_balance
```

Далее HEADER, OPENING, ноль или более OPERATION и TOTAL. В каждой записи одинаково 31 поле; неиспользуемые пусты. `record_type`, `format_version="1"`, `account_id` и `currency` заполнены во всех служебных/финансовых записях.

| record_type | Дополнительно заполненные колонки |
|---|---|
| HEADER | account_number, owner_id, owner_name, account_status, history_start_on, from, to, generated_at |
| OPENING | opening_balance и balance_after с входящим сальдо |
| OPERATION | operation_id, operation_date, posted_at, kind, status, document_number, basis, period_start, payment_method, original_operation_id, target_amount, correction_reason, amount, delta, balance_after — nullable поля пусты |
| TOTAL | opening_balance, increase, decrease, closing_balance |

Текстовые значения всегда заключаются в двойные кавычки, внутренняя кавычка удваивается. `;`, CR и LF остаются частью правильно экранированного поля, поэтому строки нужно читать CSV-парсером, а не `split` по строке/разделителю. Денежные поля — числа с точкой и двумя знаками, без кавычек; отрицательная числовая delta не превращается в текст.

У текстов с опасным формульным префиксом `=`, `+`, `-`, `@` (в том числе после начальных пробелов/управляющих символов) перед всем значением добавляется апостроф. Это намеренное отличие безопасного CSV от исходного текста в JSON/HTML, не изменение базы. Пример: основание `=1+1; "проверка"` экспортируется как `"'=1+1; ""проверка"""`. Обычные текстовые поля сохраняются при обратном чтении без потерь.

CSV не ограничен страницей истории. Сервис читает строки нужного счёта/периода курсором `fetchSize=256` в том же согласованном снимке, используя общие `Statement`/`StatementLine` и алгоритм расчёта. Строки последовательно пишутся во временный файл, затем транзакция завершается, выполняется аудит и начинается передача готового файла. Файл удаляется после передачи либо ошибки, ресурсы курсора закрываются. Медленное скачивание не удерживает соединение PostgreSQL.


Пример пустого периода с входящим остатком 550.00 (после строки названий колонок; BOM не изображён):

```csv
"HEADER";"1";"11111111-1111-1111-1111-111111111111";"LS-0000000007";"22222222-2222-2222-2222-222222222222";"Учебный клиент";"OPEN";"2026-10-01";"2026-11-04";"2026-11-05";"2026-11-05T19:00:00Z";"RUB";;;;;;;;;;;;;;;;;;;
"OPENING";"1";"11111111-1111-1111-1111-111111111111";;;;;;;;;"RUB";;;;;;;;;;;;;;;550.00;550.00;;;
"TOTAL";"1";"11111111-1111-1111-1111-111111111111";;;;;;;;;"RUB";;;;;;;;;;;;;;;;550.00;0.00;0.00;550.00
```

## Сводные отчёты E9

`GET /api/v1/reports/{type}` доступен через HTTPS-сессию ролям ADMIN, ACCOUNTANT и MANAGER. Типы принимаются без учёта регистра. В HTML используются `/reports`, `/reports/result` и `/reports/print`. На этих маршрутах вид отчёта передаётся как `type`.

| Тип | Обязательные параметры | Денежные поля |
|---|---|---|
| CHARGES | `month=2026-10`, ГГГГ-ММ | `originalCharges`, `chargeCorrections`, `netCharges` |
| PAYMENTS | `month=2026-10`, ГГГГ-ММ | `originalPayments`, `paymentCorrections`, `netPayments` |
| DEBT | `asOf=2026-10-31`, ГГГГ-ММ-ДД | `debt`, `advance`, `balance` |
| MOVEMENT | `from=2026-10-01&to=2026-10-31`, обе даты включительно | `openingBalance`, `originalCharges`, `originalPayments`, `corrections`, `increase`, `decrease`, `change`, `closingBalance` |

Необязательные поля — `clientId` (UUID клиента), `status` (текущий статус `OPEN`, `SUSPENDED` или `CLOSED`), `page` (0–100000, по умолчанию 0) и `size` (1–100, по умолчанию 20). Период движения ограничен 366 днями включительно. Параметры других видов отчётов, неизвестные и повторяющиеся параметры вызывают 400. За границами выборки список строк пуст, общие итоги и число счетов доступны.

Ответ содержит `type`, `parameters`, UTC-время `generatedAt`, `currency`, `totalRows`, `totalPages`, `page`, `size`, `columns` с ключами и названиями, `totals` по всей выборке и `rows` запрошенной страницы. Строка содержит `accountId`, `accountNumber`, `clientId`, `clientName`, `status`, `amounts` и `accountUrl`. Денежные поля — строки с точкой и двумя знаками.

Запрос `GET /api/v1/reports/DEBT?asOf=2026-10-31&page=0&size=1` на наборе A/B/C возвращает одну строку и `{"debt":"550.00","advance":"300.00","balance":"250.00"}` в `totals` при `totalRows=3`.

Учитываются проведённые движения начиная с доступной истории счёта. Месячные отчёты включают счета с подходящими начислениями, платежами или их исправлениями. Исправление относится к виду исходного документа и месяцу собственного движения. Эффект исправления платежа в колонке зарегистрированных сумм равен минус `delta`. Долг и аванс вычисляются по каждому счёту на дату, затем суммируются отдельно. В движении используются знаковые `delta`, начальный остаток и обороты по знаку движения. Счёт, история которого началась в течение периода, входит с остатком на начало своей доступной истории. Счета с началом истории позже даты расчёта исключаются.

Ошибки используют `application/problem+json` с `code` и `requestId`. Некорректный запрос возвращает 400 `REPORT_VALIDATION`, отсутствующая сессия — 401, запрещённая роль — 403. Недоступная БД или обязательный аудит дают 503.

### CSV отчётов версии 1

`GET /reports.csv?type=DEBT&asOf=2026-10-31` принимает те же параметры и выгружает полную выборку. Параметры `page` и `size` валидируются и не ограничивают выгрузку. Ответ использует `text/csv;charset=UTF-8` и `Content-Disposition: attachment`. Файл начинается с BOM и использует разделитель `;` и CRLF между записями.

Порядок общих колонок приведён ниже.

```text
record_type;format_version;report_type;from;to;generated_at;currency;client_filter;status_filter;account_id;account_number;client_id;client_name;status
```

Далее идут денежные колонки в порядке таблицы. Сначала идут записи `DETAIL`, затем запись `TOTAL` с общими суммами и пустыми полями счёта и клиента. Для задолженности `from` и `to` совпадают с датой расчёта. Текст экранируется по правилам выписки E8. Опасный формульный префикс экранируется апострофом. Денежные поля передаются без кавычек.
