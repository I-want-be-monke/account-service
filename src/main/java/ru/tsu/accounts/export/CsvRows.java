package ru.tsu.accounts.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;

public final class CsvRows {
    private CsvRows() {}

    public static String text(Object value) {
        if (value == null) return "";
        String text = value.toString();
        int first = 0;
        while (first < text.length() && isIgnoredPrefix(text.charAt(first))) first++;
        if (first < text.length() && "=+-@".indexOf(text.charAt(first)) >= 0) text = "'" + text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static boolean isIgnoredPrefix(char ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch)
            || Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT;
    }

    public static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2).toPlainString();
    }

    public static void write(BufferedWriter writer, String... cells) {
        try {
            writer.write(String.join(";", cells));
            writer.write("\r\n");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
