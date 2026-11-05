package ru.tsu.accounts.report;

import java.util.List;

public enum ReportType {
    CHARGES("Начисления за месяц", List.of(
        new Column("originalCharges", "Исходные начисления"),
        new Column("chargeCorrections", "Исправления начислений"),
        new Column("netCharges", "Чистые начисления"))),
    PAYMENTS("Платежи за месяц", List.of(
        new Column("originalPayments", "Исходные зарегистрированные платежи"),
        new Column("paymentCorrections", "Исправления зарегистрированных платежей"),
        new Column("netPayments", "Чистые зарегистрированные платежи"))),
    DEBT("Задолженность на дату", List.of(
        new Column("debt", "Задолженность"),
        new Column("advance", "Аванс"),
        new Column("balance", "Чистое сальдо"))),
    MOVEMENT("Движение по счетам за период", List.of(
        new Column("openingBalance", "Входящее сальдо"),
        new Column("originalCharges", "Исходные начисления"),
        new Column("originalPayments", "Исходные зарегистрированные платежи"),
        new Column("corrections", "Влияние исправлений на сальдо"),
        new Column("increase", "Увеличение задолженности"),
        new Column("decrease", "Уменьшение задолженности"),
        new Column("change", "Изменение сальдо"),
        new Column("closingBalance", "Исходящее сальдо")));

    private final String title;
    private final List<Column> columns;

    ReportType(String title, List<Column> columns) {
        this.title = title;
        this.columns = columns;
    }

    public String getTitle() { return title; }
    public List<Column> getColumns() { return columns; }

    public record Column(String key, String label) {}
}
