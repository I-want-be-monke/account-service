package ru.tsu.accounts.migration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.stereotype.Component;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.migration.MigrationPackage.Account;
import ru.tsu.accounts.migration.MigrationPackage.Client;
import ru.tsu.accounts.migration.MigrationPackage.Operation;
import ru.tsu.accounts.migration.MigrationValidationReport.Issue;
import ru.tsu.accounts.operation.CorrectionRules;
import ru.tsu.accounts.operation.MoneyRules;
import ru.tsu.accounts.operation.OperationForm;

@Component
class MigrationPackageReader {
    static final String MANIFEST = "manifest.json";
    static final String CLIENTS = "clients.csv";
    static final String ACCOUNTS = "accounts.csv";
    static final String OPERATIONS = "operations.csv";
    private static final List<String> FILES = List.of(CLIENTS, ACCOUNTS, OPERATIONS);
    private static final List<String> CLIENT_HEADER = List.of("id", "client_type", "display_name",
        "identifier_type", "identifier_value", "phone", "email", "status");
    private static final List<String> ACCOUNT_HEADER = List.of("id", "account_number", "client_id",
        "status", "opened_on", "closed_on", "history_start_on", "opening_balance", "currency");
    private static final List<String> OPERATION_HEADER = List.of("id", "account_id", "kind",
        "operation_date", "amount", "period_start", "basis", "payment_method", "external_number",
        "corrects_operation_id", "target_amount", "correction_reason", "delta");
    private static final int MAX_ROWS = 20000;
    private static final int MAX_CSV_BYTES = 8 * 1024 * 1024;
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final CSVFormat CSV = CSVFormat.RFC4180.builder().setDelimiter(';').setHeader()
        .setSkipHeaderRecord(true).setIgnoreEmptyLines(false)
        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
    private final ObjectMapper json;

    MigrationPackageReader(ObjectMapper json) {
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    Result read(Path directory) {
        List<Issue> issues = new ArrayList<>();
        Manifest manifest;
        try {
            if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw invalid("Укажите каталог пакета.");
            manifest = manifest(json.readTree(decode(readFile(directory, MANIFEST, 2 * 1024 * 1024))));
        } catch (IOException ex) {
            return new Result(null, null, List.of(new Issue(MANIFEST, 0, "Не удалось прочитать manifest.json как UTF-8 JSON.")));
        } catch (InvalidValue ex) {
            return new Result(null, null, List.of(new Issue(MANIFEST, 0, ex.getMessage())));
        }

        Map<String, byte[]> content = new HashMap<>();
        StringBuilder canonical = new StringBuilder("account-service-import-v1\n");
        for (String file : FILES) {
            try {
                byte[] bytes = readFile(directory, file, MAX_CSV_BYTES);
                content.put(file, bytes);
                String actualHash = sha256(bytes);
                canonical.append(file).append(':').append(actualHash).append('\n');
                if (!actualHash.equals(manifest.files().get(file).sha256()))
                    issue(issues, file, 0, "SHA-256 файла не совпадает с манифестом.");
            } catch (IOException | InvalidValue ex) {
                issue(issues, file, 0, "Файл отсутствует, превышает 8 МиБ или недоступен для чтения.");
            }
        }
        String hash = content.size() == FILES.size()
            ? sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)) : null;
        if (hash != null && !hash.equals(manifest.sha256()))
            issue(issues, MANIFEST, 0, "Контрольная сумма пакета не совпадает.");
        if (!issues.isEmpty()) return new Result(null, hash, issues);

        State state = new State();
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Moscow"));
        var clients = rows(content.get(CLIENTS), CLIENTS, CLIENT_HEADER, manifest, issues,
            r -> client(r, state));
        var accounts = rows(content.get(ACCOUNTS), ACCOUNTS, ACCOUNT_HEADER, manifest, issues,
            r -> account(r, state, today));
        var operations = rows(content.get(OPERATIONS), OPERATIONS, OPERATION_HEADER, manifest, issues,
            r -> operation(r, state, today));
        if (clients.isEmpty() && accounts.isEmpty() && operations.isEmpty() && issues.isEmpty())
            issue(issues, MANIFEST, 0, "Пакет должен содержать хотя бы одну запись.");

        Map<String, String> balances = new LinkedHashMap<>();
        BigDecimal debt = ZERO;
        BigDecimal advance = ZERO;
        for (Account account : accounts) {
            BigDecimal balance = state.balances.get(account.id());
            balances.put(account.number(), balance.toPlainString());
            if (balance.signum() > 0) debt = debt.add(balance);
            else advance = advance.subtract(balance);
            if (account.status().equals("CLOSED") && balance.signum() != 0)
                issue(issues, ACCOUNTS, account.row(), "Закрытый счёт должен иметь нулевое сальдо.");
            if (!balance.toPlainString().equals(manifest.balances().get(account.number())))
                issue(issues, ACCOUNTS, account.row(), "Расчётное сальдо не совпадает с контрольным.");
        }
        if (!balances.keySet().equals(manifest.balances().keySet()))
            issue(issues, MANIFEST, 0, "Контрольные сальдо должны покрывать ровно счета из accounts.csv.");
        if (!debt.toPlainString().equals(manifest.debt()) || !advance.toPlainString().equals(manifest.advance()))
            issue(issues, MANIFEST, 0, "Общий долг или аванс не совпадает с манифестом.");
        if (!issues.isEmpty()) return new Result(null, hash, issues);

        return new Result(new MigrationPackage(manifest.sourceName(), hash, clients, accounts, operations,
            balances, debt.toPlainString(), advance.toPlainString()), hash, List.of());
    }

    private static Manifest manifest(JsonNode root) {
        fields(root, Set.of("version", "sourceName", "packageSha256", "files", "controlBalances",
            "controlDebt", "controlAdvance"));
        JsonNode version = root.get("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1)
            throw invalid("Поддерживается только версия пакета 1.");
        String sourceName = required(text(root.get("sourceName")), 255, "Имя источника", false);
        if (sourceName.contains("/") || sourceName.contains("\\") || sourceName.contains(":")
            || sourceName.equals(".") || sourceName.equals(".."))
            throw invalid("Имя источника должно быть меткой пакета без пути.");
        JsonNode filesNode = root.get("files");
        fields(filesNode, Set.copyOf(FILES));
        Map<String, FileControl> files = new HashMap<>();
        for (String name : FILES) {
            JsonNode file = filesNode.get(name);
            fields(file, Set.of("rows", "sha256"));
            JsonNode rows = file.get("rows");
            if (!rows.isIntegralNumber() || !rows.canConvertToInt() || rows.intValue() < 0 || rows.intValue() > MAX_ROWS)
                throw invalid("Число записей файла должно быть от 0 до 20000.");
            files.put(name, new FileControl(rows.intValue(), hash(text(file.get("sha256")))));
        }
        JsonNode balanceNode = root.get("controlBalances");
        if (!balanceNode.isObject() || balanceNode.size() > MAX_ROWS)
            throw invalid("Некорректный объект контрольных сальдо.");
        Map<String, String> balances = new HashMap<>();
        balanceNode.fields().forEachRemaining(e -> balances.put(accountNumber(e.getKey()),
            money(text(e.getValue()), true, 17, "Контрольное сальдо").toPlainString()));
        return new Manifest(sourceName, hash(text(root.get("packageSha256"))), files, balances,
            money(text(root.get("controlDebt")), false, 24, "Контрольный долг").toPlainString(),
            money(text(root.get("controlAdvance")), false, 24, "Контрольный аванс").toPlainString());
    }

    private static Client client(CSVRecord row, State state) {
        UUID id = uuid(row.get("id"), "Идентификатор клиента");
        String type = choice(row.get("client_type"), Set.of("PERSON", "ORGANIZATION"), "Тип клиента");
        String name = required(row.get("display_name"), 200, "Имя клиента", false);
        String identifierType = row.get("identifier_type").strip().toUpperCase(Locale.ROOT);
        if (!identifierType.matches("[A-Z][A-Z0-9_-]{0,31}")) throw invalid("Некорректный тип идентификатора.");
        String identifier = required(row.get("identifier_value"), 100, "Идентификатор клиента", false);
        String key = identifier.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (key.isEmpty() || key.length() > 100) throw invalid("Некорректный нормализованный идентификатор.");
        String phone = optional(row.get("phone"), 32, "Телефон", false);
        if (phone != null && !phone.matches("^\\+?[0-9() .-]{5,32}$")) throw invalid("Некорректный телефон.");
        String email = optional(row.get("email"), 254, "Электронная почта", false);
        if (email != null && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw invalid("Некорректная электронная почта.");
        String status = choice(row.get("status"), Set.of("ACTIVE", "ARCHIVED"), "Статус клиента");
        if (state.clients.containsKey(id) || !state.clientKeys.add(List.of(type, identifierType, key)))
            throw invalid("Повтор UUID или нормализованного идентификатора клиента в пакете.");
        Client client = new Client(line(row), id, type, name, identifierType, identifier, key, phone, email, status);
        state.clients.put(id, client);
        return client;
    }

    private static Account account(CSVRecord row, State state, LocalDate today) {
        UUID id = uuid(row.get("id"), "Идентификатор счёта");
        String number = accountNumber(row.get("account_number").strip());
        UUID clientId = uuid(row.get("client_id"), "Ссылка на клиента");
        Client client = state.clients.get(clientId);
        if (client == null) throw invalid("Клиент счёта отсутствует в clients.csv.");
        String status = choice(row.get("status"), Set.of("OPEN", "SUSPENDED", "CLOSED"), "Статус счёта");
        if (client.status().equals("ARCHIVED") && !status.equals("CLOSED"))
            throw invalid("У архивного клиента все счета должны быть закрыты.");
        LocalDate openedOn = date(row.get("opened_on"), "Дата открытия");
        LocalDate history = date(row.get("history_start_on"), "Начало истории");
        LocalDate closedOn = row.get("closed_on").isBlank() ? null : date(row.get("closed_on"), "Дата закрытия");
        if (history.isBefore(openedOn) || history.isAfter(today)
            || status.equals("CLOSED") != (closedOn != null)
            || closedOn != null && (closedOn.isBefore(history) || closedOn.isAfter(today)))
            throw invalid("Проверьте порядок дат открытия, начала истории и закрытия счёта.");
        BigDecimal opening = money(row.get("opening_balance"), true, 17, "Входящее сальдо");
        if (opening.signum() != 0 && !history.isAfter(openedOn))
            throw invalid("Для полной истории с даты открытия входящее сальдо должно быть нулевым.");
        choice(row.get("currency"), Set.of("RUB"), "Валюта");
        if (state.accounts.containsKey(id) || !state.accountNumbers.add(number))
            throw invalid("Повтор UUID или номера счёта в пакете.");
        Account account = new Account(line(row), id, number, clientId, status, openedOn, closedOn, history, opening);
        state.accounts.put(id, account);
        state.balances.put(id, opening);
        return account;
    }

    private static Operation operation(CSVRecord row, State state, LocalDate today) {
        UUID id = uuid(row.get("id"), "Идентификатор операции");
        UUID accountId = uuid(row.get("account_id"), "Ссылка на счёт");
        Account account = state.accounts.get(accountId);
        if (account == null) throw invalid("Счёт операции отсутствует в accounts.csv.");
        if (state.operations.containsKey(id)) throw invalid("Повтор UUID операции в пакете.");
        String kind = choice(row.get("kind"), Set.of("CHARGE", "PAYMENT", "CORRECTION"), "Вид операции");
        LocalDate date = date(row.get("operation_date"), "Дата операции");
        LocalDate previous = state.lastDates.get(accountId);
        if (date.isBefore(account.historyStartOn()) || date.isAfter(today)
            || account.closedOn() != null && date.isAfter(account.closedOn())
            || previous != null && date.isBefore(previous))
            throw invalid("Операции должны идти по датам в доступной истории счёта до его закрытия.");
        BigDecimal amount = money(row.get("amount"), false, 17, "Сумма операции");
        if (amount.signum() <= 0) throw invalid("Сумма операции должна быть положительной.");
        BigDecimal delta = money(row.get("delta"), true, 17, "Финансовый эффект");
        String basis = required(row.get("basis"), 500, "Основание", true);
        String method = optional(row.get("payment_method"), 32, "Способ платежа", false);
        String number = optional(row.get("external_number"), 100, "Номер документа", false);
        String periodText = row.get("period_start");
        LocalDate period = periodText.isBlank() ? null : date(periodText, "Расчётный период");
        String originalText = row.get("corrects_operation_id");
        String targetText = row.get("target_amount");
        String reason = optional(row.get("correction_reason"), 1000, "Причина исправления", true);
        UUID originalId = null;
        BigDecimal target = null;
        Long correctionCount = null;
        CorrectionProgress progress = null;

        if (kind.equals("CORRECTION")) {
            if (method != null || number != null || period != null || reason == null)
                throw invalid("Проверьте реквизиты исправления. Требуется причина.");
            originalId = uuid(originalText, "Исходная операция");
            target = money(targetText, false, 17, "Целевая сумма");
            Operation original = state.operations.get(originalId);
            if (original == null || original.kind().equals("CORRECTION") || !original.accountId().equals(accountId))
                throw invalid("Исправление должно ссылаться на предшествующую операцию того же счёта.");
            progress = state.corrections.getOrDefault(originalId, new CorrectionProgress(ZERO, 0));
            CorrectionRules.Effect effect = CorrectionRules.calculate(original.amount(), original.delta().signum(),
                progress.delta(), progress.count(), target);
            if (effect.delta().compareTo(delta) != 0 || delta.abs().compareTo(amount) != 0)
                throw invalid("Сумма или эффект исправления не соответствует исходной и целевой сумме.");
            correctionCount = effect.correctionCount();
        } else {
            if (!originalText.isBlank() || !targetText.isBlank() || reason != null
                || kind.equals("PAYMENT") && period != null || kind.equals("CHARGE") && method != null)
                throw invalid("Для вида операции указаны лишние реквизиты.");
            var form = new OperationForm();
            form.setKind(kind);
            form.setOperationDate(date.toString());
            form.setAmount(amount.toPlainString());
            form.setBasis(basis.replace('\r', ' ').replace('\n', ' '));
            form.setExternalNumber(number);
            form.setPaymentMethod(method);
            form.setPeriodStart(period == null ? null : period.toString());
            MoneyRules.validate(form, account.historyStartOn());
            BigDecimal expected = kind.equals("CHARGE") ? amount : amount.negate();
            if (expected.compareTo(delta) != 0) throw invalid("Финансовый эффект операции не соответствует её виду и сумме.");
        }
        BigDecimal balance = MoneyRules.nextBalance(state.balances.get(accountId), delta);
        if (number != null && !state.documentKeys.add(List.of(kind, number.toLowerCase(Locale.ROOT))))
            throw invalid("Повтор нормализованного номера документа в пакете.");

        var operation = new Operation(line(row), id, accountId, kind, date, amount, period, basis,
            method, number, originalId, target, reason, delta, correctionCount);
        if (progress != null) state.corrections.put(originalId,
            new CorrectionProgress(progress.delta().add(delta), progress.count() + 1));
        state.operations.put(id, operation);
        state.lastDates.put(accountId, date);
        state.balances.put(accountId, balance);
        return operation;
    }

    private static <T> List<T> rows(byte[] bytes, String file, List<String> header, Manifest manifest,
                                    List<Issue> issues, Function<CSVRecord, T> parse) {
        List<T> result = new ArrayList<>();
        int count = 0;
        try (CSVParser parser = CSVParser.parse(decode(bytes), CSV)) {
            if (!parser.getHeaderNames().equals(header)) {
                issue(issues, file, 1, "Заголовок CSV не соответствует формату версии 1.");
                return result;
            }
            for (CSVRecord row : parser) {
                if (++count > MAX_ROWS) throw invalid("Превышен лимит 20000 записей файла.");
                if (!row.isConsistent()) {
                    issue(issues, file, line(row), "Количество полей строки не совпадает с заголовком.");
                    continue;
                }
                try { result.add(parse.apply(row)); }
                catch (InvalidValue | UserCommandException ex) { issue(issues, file, line(row), ex.getMessage()); }
            }
            if (count != manifest.files().get(file).rows())
                issue(issues, file, 0, "Число записей файла не совпадает с манифестом.");
        } catch (InvalidValue ex) {
            issue(issues, file, count + 1L, ex.getMessage());
        } catch (IOException | UncheckedIOException | IllegalArgumentException ex) {
            issue(issues, file, count + 1L, "Некорректный UTF-8 CSV или кавычки.");
        }
        return result;
    }

    private static byte[] readFile(Path directory, String file, int limit) throws IOException {
        Path path = directory.resolve(file);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("File missing");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("File size limit exceeded");
            return bytes;
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw invalid("Ожидается объект манифеста версии 1.");
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        if (!names.equals(expected)) throw invalid("Отсутствуют обязательные или присутствуют лишние поля манифеста.");
    }

    private static String text(JsonNode value) {
        if (!value.isTextual()) throw invalid("Ожидается строковое значение манифеста.");
        return value.textValue();
    }

    private static String hash(String value) {
        if (!value.matches("[0-9a-f]{64}")) throw invalid("SHA-256 должен состоять из 64 строчных шестнадцатеричных знаков.");
        return value;
    }

    private static String accountNumber(String value) {
        if (!value.matches("LS-[0-9]{10}") || value.equals("LS-0000000000")) throw invalid("Некорректный номер счёта.");
        return value;
    }

    private static String choice(String raw, Set<String> allowed, String label) {
        String value = raw.strip();
        if (!allowed.contains(value)) throw invalid(label + " содержит недопустимое значение.");
        return value;
    }

    private static String required(String raw, int max, String label, boolean multiline) {
        String value = optional(raw, max, label, multiline);
        if (value == null) throw invalid(label + " обязательно.");
        return value;
    }

    private static String optional(String raw, int max, String label, boolean multiline) {
        String value = raw.strip();
        if (value.isEmpty()) return null;
        if (value.length() > max || value.chars().anyMatch(c -> Character.isISOControl(c)
            && !(multiline && (c == '\r' || c == '\n'))))
            throw invalid(label + " содержит недопустимые символы или превышает допустимую длину.");
        return value;
    }

    private static UUID uuid(String raw, String label) {
        String value = raw.strip();
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) return id;
        } catch (IllegalArgumentException ex) {
            // Use a validation message without the input value.
        }
        throw invalid(label + " должен быть UUID.");
    }

    private static LocalDate date(String raw, String label) {
        String value = raw.strip();
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000-"))
            throw invalid(label + " должна иметь формат ГГГГ-ММ-ДД.");
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw invalid(label + " некорректна."); }
    }

    private static BigDecimal money(String raw, boolean signed, int digits, String label) {
        String value = raw.strip();
        if (!value.matches((signed ? "-?" : "") + "[0-9]{1," + digits + "}(\\.[0-9]{1,2})?"))
            throw invalid(label + " должна иметь не более двух дробных знаков и разделитель-точку.");
        return new BigDecimal(value).setScale(2);
    }

    private static long line(CSVRecord row) { return row.getRecordNumber() + 1; }

    static void issue(List<Issue> issues, String file, long row, String message) {
        if (issues.size() < 100) issues.add(new Issue(file, row, message));
    }

    private static InvalidValue invalid(String message) { return new InvalidValue(message); }

    private static class InvalidValue extends RuntimeException {
        InvalidValue(String message) { super(message); }
    }

    record Result(MigrationPackage data, String sha256, List<Issue> issues) {}
    private record FileControl(int rows, String sha256) {}
    private record Manifest(String sourceName, String sha256, Map<String, FileControl> files,
                            Map<String, String> balances, String debt, String advance) {}
    private record CorrectionProgress(BigDecimal delta, long count) {}

    private static class State {
        final Map<UUID, Client> clients = new HashMap<>();
        final Map<UUID, Account> accounts = new HashMap<>();
        final Map<UUID, Operation> operations = new HashMap<>();
        final Set<List<String>> clientKeys = new HashSet<>();
        final Set<String> accountNumbers = new HashSet<>();
        final Set<List<String>> documentKeys = new HashSet<>();
        final Map<UUID, BigDecimal> balances = new HashMap<>();
        final Map<UUID, LocalDate> lastDates = new HashMap<>();
        final Map<UUID, CorrectionProgress> corrections = new HashMap<>();
    }
}
