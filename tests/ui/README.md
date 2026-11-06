# UI-01. Проверка в Chrome, Edge, Firefox и Яндекс Браузере

`scripts\ui-test.cmd` запускает headless Selenium WebDriver. `--browser all` последовательно проверяет Chrome, Edge и Firefox. Для Яндекс Браузера предусмотрен отдельный запуск с официальным Яндекс Драйвером. Нужны Node.js 20 или новее, Docker Desktop, запущенный изолированный стенд E11 и настоящие исполняемые файлы браузеров. Установку браузеров через Selenium Manager сценарий запрещает. Укажите совместимые драйверы явно, чтобы запуск использовал локальные файлы.

Сценарий проверяет Docker Compose-проект `account-service-e11`, имя БД `account_service_e11`, сеть и том проекта, порты `127.0.0.1:18448` и `127.0.0.1:55438`, а затем проверяет готовность `https://localhost:18448` по HTTPS. Он использует тестовые пароли ролей из `.local\e11\stand.env` и существующего пользователя `e11-client-001`. Параметр `--client` позволяет выбрать другого пользователя из набора `e11-client-NNN`. Каждый браузер создаёт отдельного синтетического клиента, счёт и проводки. Они сохраняются в тестовой БД для сверки с протоколом.

## Запуск из CMD

Запустите изолированный стенд и подготовьте его сертификат для браузеров. На чистом стенде выполните однократную [генерацию профиля `smoke`](../performance/README.md). Если синтетический набор уже есть, используйте его.

```cmd
tests\performance\stand.cmd start
tests\ui\prepare-stand.cmd
```

Для команды подготовки нужен OpenSSL 1.1.1 или новее, например из Git for Windows. Она поднимает только приложение в `account-service-e11` и сохраняет тестовые TLS-файлы в `.local\e11-continuation\ui-tls`. Для Firefox распакуйте отдельную копию официального дистрибутива и создайте `distribution\policies.json` рядом с `firefox.exe`. Укажите абсолютный путь к тестовому CA с экранированными обратными косыми чертами. [Политика Certificates в Firefox](https://firefox-admin-docs.mozilla.org/reference/policies/certificates/)

```json
{
  "policies": {
    "Certificates": {
      "Install": ["C:\\vyz\\incheneria\\account-service\\.local\\e11-continuation\\ui-tls\\ca.pem"]
    }
  }
}
```

Установите зафиксированные зависимости из каталога `tests\ui`.

```cmd
cd /d C:\vyz\incheneria\account-service\tests\ui
npm.cmd ci --ignore-scripts
cd /d C:\vyz\incheneria\account-service
```

Укажите пути для подготовленных браузеров, драйверов и TLS-файлов. Для каждого запуска используйте новый каталог под `.local`.

```cmd
scripts\ui-test.cmd --browser all ^
  --chrome ".local\e11-continuation\browsers\chrome\chrome-win64\chrome.exe" ^
  --chrome-driver ".local\e11-continuation\browsers\chromedriver\chromedriver-win64\chromedriver.exe" ^
  --edge "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" ^
  --edge-driver ".local\e11-continuation\browsers\edgedriver\msedgedriver.exe" ^
  --firefox ".local\e11-continuation\browsers\firefox\core\firefox.exe" ^
  --firefox-driver ".local\e11-continuation\browsers\geckodriver\geckodriver.exe" ^
  --ca ".local\e11-continuation\ui-tls\ca.pem" ^
  --spki ".local\e11-continuation\ui-tls\spki.txt" ^
  --output ".local\e11-continuation\ui-repeat-01"
```

Для дополнительного прогона используйте Node.js 22.4 или новее, установленный Яндекс Браузер и официальный драйвер соответствующей версии. [Релизы Яндекс Драйвера](https://github.com/yandex/YandexDriver/releases)

```cmd
scripts\ui-test.cmd --browser yandex ^
  --yandex "%LOCALAPPDATA%\Yandex\YandexBrowser\Application\browser.exe" ^
  --yandex-driver ".local\e11-continuation\browsers\yandex-driver\yandexdriver.exe" ^
  --ca ".local\e11-continuation\ui-tls\ca.pem" ^
  --spki ".local\e11-continuation\ui-tls\spki.txt" ^
  --output ".local\e11-continuation\ui-yandex-repeat-01"
```

Параметр `--ca` задаёт доверенный сертификат для предварительной проверки Node.js. Браузерам требуется доверие к тому же тестовому сертификату. Для изолированного Firefox подходит импорт тестового CA политикой частной установки. В Chrome и Edge `--spki` ограничивает исключение одним ключом тестового сервера и временными профилями прогона. До запуска браузеров Node.js проверяет цепочку, имя `localhost` и совпадение хеша SPKI с фактическим ключом. В WebDriver задано `acceptInsecureCerts=false`.

Яндекс Драйвер подключается к отдельному процессу Яндекс Браузера с чистым профилем и пустой стартовой вкладкой. При `ERR_CERT_AUTHORITY_INVALID` сценарий повторно проверяет сертификат и пробует штатное исключение сайта. Если в headless-режиме предупреждение остаётся, сценарий обрабатывает запросы сертификата в этой вкладке через DevTools. Продолжение разрешено только для `https://localhost:18448` после повторной проверки цепочки, имени и SPKI. Причина и число проверенных событий записываются в протокол. Настройки доверия Windows и основной профиль браузера остаются за пределами сценария. [Исключение для тестового сайта в справке Яндекса](https://browser.yandex.ru/help/ru/security/untrusted-sertificates)

## Покрытие и результаты

- Вход во все пять ролей с переходами по Tab и подтверждением по Enter. Оператор создаёт клиента и счёт, бухгалтер проводит начисление и оплату, затем корректирует обе суммы. История, выписка и отчёты сверяются с остатком 550,00 ₽. Руководителю запрещено проводить операции. Клиент видит собственный счёт и получает отказ при обращении к чужому.
- Выписка и четыре вида отчётов проверяются через формы. Сценарий проверяет ошибки формы суммы и периода, пустой результат отчёта и фактически скачанные CSV с UTF‑8 BOM, разделителем `;` и ожидаемыми строками.
- Скрипт проверяет правило CSS для печати и вызов `window.print()` кнопкой. WebDriver отдельно формирует PDF печатной страницы выписки и отчёта. Визуально проверьте все страницы PDF. Интерактивное окно выбора принтера находится за пределами сценария.
- Для выписки и отчёта сохраняются снимки на широком и узком экране. Проверяются горизонтальный выход страницы и элементов, пересечения элементов управления, выход текста за пределы ячеек и ширина ключевых столбцов. На узком экране сценарий проверяет переход Tab к таблице, видимую рамку фокуса и горизонтальную прокрутку стрелкой.

`summary.json` содержит общий итог, а `result.json` в каталоге каждого браузера содержит фактические версии, время, результаты шагов, размеры, UUID созданных данных и причины ошибок. Для Яндекс Браузера дополнительно записываются версия продукта из Windows, путь запущенного файла, User-Agent и версия драйвера. Снимки `*-desktop.png` и `*-narrow.png` предназначены для отдельной ручной оценки. Скачанные файлы сохраняются в `downloads`, печатные документы в `statement-print.pdf` и `report-print.pdf`. Код выхода `0` означает успешное прохождение, `1` означает ошибку подготовки стенда или аргументов, `2` означает сбой одного или нескольких сценариев.
