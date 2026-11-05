package ru.tsu.accounts.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import ru.tsu.accounts.identity.UserCommandException;

public record ReportQuery(ReportType type, LocalDate from, LocalDate to, UUID clientId, String status,
                          int page, int size) {
    private static final Set<String> STATUSES = Set.of("OPEN", "SUSPENDED", "CLOSED");
    private static final Set<String> COMMON_PARAMETERS = Set.of("clientId", "status", "page", "size");

    public ReportQuery {
        if (type == null) throw invalid("Выберите вид отчёта.");
        if (from == null || to == null || from.getYear() < 1 || to.getYear() > 9999)
            throw invalid("Укажите даты в формате ГГГГ-ММ-ДД.");
        if (from.isAfter(to)) throw invalid("Начало периода не должно быть позже его окончания.");
        if (ChronoUnit.DAYS.between(from, to) >= 366)
            throw invalid("Период отчёта не должен превышать 366 дней включительно.");
        if (type == ReportType.DEBT && !from.equals(to))
            throw invalid("Для задолженности укажите одну дату расчёта.");
        if (type == ReportType.CHARGES || type == ReportType.PAYMENTS) {
            YearMonth month = YearMonth.from(from);
            if (!from.equals(month.atDay(1)) || !to.equals(month.atEndOfMonth()))
                throw invalid("Для этого отчёта выберите один календарный месяц.");
        }
        status = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (status != null && !STATUSES.contains(status))
            throw invalid("Выберите действительное состояние счёта.");
        if (page < 0 || page > 100000) throw invalid("Номер страницы должен быть от 0 до 100000.");
        if (size < 1 || size > 100) throw invalid("Размер страницы должен быть от 1 до 100.");
    }

    public static ReportQuery parse(String type, Map<String, String> parameters) {
        ReportType selected = parseType(type);
        Set<String> periodParameters = switch (selected) {
            case CHARGES, PAYMENTS -> Set.of("month");
            case DEBT -> Set.of("asOf");
            case MOVEMENT -> Set.of("from", "to");
        };
        if (parameters.keySet().stream().anyMatch(key -> !COMMON_PARAMETERS.contains(key)
            && !periodParameters.contains(key))) {
            throw invalid("Запрос содержит параметры, недопустимые для выбранного отчёта.");
        }
        LocalDate from;
        LocalDate to;
        switch (selected) {
            case CHARGES, PAYMENTS -> {
                YearMonth month = parseMonth(parameters.get("month"));
                from = month.atDay(1);
                to = month.atEndOfMonth();
            }
            case DEBT -> {
                from = parseDate(parameters.get("asOf"), "Укажите дату расчёта в формате ГГГГ-ММ-ДД.");
                to = from;
            }
            case MOVEMENT -> {
                from = parseDate(parameters.get("from"), "Укажите начало периода в формате ГГГГ-ММ-ДД.");
                to = parseDate(parameters.get("to"), "Укажите окончание периода в формате ГГГГ-ММ-ДД.");
            }
            default -> throw new IllegalStateException("Unknown report type");
        }
        return new ReportQuery(selected, from, to, parseClientId(parameters.get("clientId")),
            parameters.get("status"), parseNumber(parameters.get("page"), 0, "номер страницы"),
            parseNumber(parameters.get("size"), 20, "размер страницы"));
    }

    private static ReportType parseType(String value) {
        if (value == null || value.isBlank()) throw invalid("Выберите вид отчёта.");
        try { return ReportType.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw invalid("Неизвестный вид отчёта."); }
    }

    private static YearMonth parseMonth(String value) {
        String message = "Укажите календарный месяц в формате ГГГГ-ММ.";
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}")) throw invalid(message);
        try { return YearMonth.parse(value); }
        catch (DateTimeParseException ex) { throw invalid(message); }
    }

    private static LocalDate parseDate(String value, String message) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid(message);
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw invalid(message); }
    }

    private static UUID parseClientId(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        try {
            UUID id = UUID.fromString(text);
            if (id.toString().equalsIgnoreCase(text)) return id;
        } catch (IllegalArgumentException ex) {
            // Return one safe validation message for malformed and noncanonical IDs.
        }
        throw invalid("Идентификатор клиента должен быть UUID.");
    }

    private static int parseNumber(String value, int defaultValue, String label) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw invalid("Укажите целый " + label + "."); }
    }

    private static UserCommandException invalid(String message) {
        return new UserCommandException(HttpStatus.BAD_REQUEST, "REPORT_VALIDATION", message);
    }
}
