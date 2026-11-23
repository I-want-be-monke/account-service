package ru.tsu.accounts.web;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Presentation only: export contracts and financial values keep their original precision. */
@Component("ui")
public class UiFormat {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");
    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("ADMIN", "Администратор"), Map.entry("OPERATOR", "Оператор"),
        Map.entry("ACCOUNTANT", "Бухгалтер-расчётчик"), Map.entry("MANAGER", "Руководитель"),
        Map.entry("CLIENT", "Клиент"), Map.entry("ACTIVE", "Активен"),
        Map.entry("ARCHIVED", "В архиве"), Map.entry("OPEN", "Открыт"),
        Map.entry("SUSPENDED", "Приостановлен"), Map.entry("CLOSED", "Закрыт"),
        Map.entry("PREPARED", "Черновик"), Map.entry("POSTED", "Проведено"),
        Map.entry("CORRECTED", "Исправлено"), Map.entry("DISCARDED", "Отброшено"),
        Map.entry("CHARGE", "Начисление"), Map.entry("PAYMENT", "Платёж"),
        Map.entry("CORRECTION", "Корректировка"), Map.entry("SUCCESS", "Успех"),
        Map.entry("DENIED", "Отказ"), Map.entry("FAILED", "Ошибка"),
        Map.entry("INDIVIDUAL", "Физическое лицо"), Map.entry("ORGANIZATION", "Организация"),
        Map.entry("PERSON", "Физическое лицо"), Map.entry("LEGAL", "Организация"),
        Map.entry("CASH", "Наличные"), Map.entry("BANK", "Банковский перевод"),
        Map.entry("CARD", "Банковская карта"), Map.entry("BLOCKED", "Заблокирован"),
        Map.entry("MANUAL_TRANSFER", "Банковский перевод"), Map.entry("CASH_REGISTRATION", "Внесение наличных"));

    public String money(Object value) {
        if (value == null || value.toString().isBlank()) return "—";
        BigDecimal amount = value instanceof BigDecimal number ? number : new BigDecimal(value.toString());
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru-RU"));
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.00", symbols).format(amount) + " ₽";
    }

    public String date(Object value) {
        if (value == null || value.toString().isBlank()) return "—";
        if (value instanceof Instant instant) return TIME.format(instant.atZone(ZONE));
        if (value instanceof OffsetDateTime offset) return TIME.format(offset.atZoneSameInstant(ZONE));
        if (value instanceof LocalDateTime local) return TIME.format(local);
        if (value instanceof LocalDate local) return DATE.format(local);
        String text = value.toString();
        try { return DATE.format(LocalDate.parse(text)); } catch (java.time.format.DateTimeParseException ignored) { }
        try { return TIME.format(Instant.parse(text).atZone(ZONE)); } catch (java.time.format.DateTimeParseException ignored) { }
        return text;
    }

    public String status(Object value) { return value == null ? "—" : LABELS.getOrDefault(value.toString(), value.toString()); }
}
