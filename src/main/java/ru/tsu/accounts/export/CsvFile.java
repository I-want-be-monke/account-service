package ru.tsu.accounts.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import ru.tsu.accounts.identity.UserCommandException;

/** Spools an export so that delivery does not keep a database cursor or transaction open. */
public record CsvFile<T>(Path path, String filename, long contentLength, T metadata)
    implements AutoCloseable {

    public static <T> CsvFile<T> create(String prefix, String header,
        Function<BufferedWriter, T> body, Function<T, String> filename) {
        Path file = null;
        boolean complete = false;
        try {
            file = Files.createTempFile(prefix, ".csv");
            T metadata;
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write('\ufeff');
                writer.write(header);
                writer.write("\r\n");
                metadata = body.apply(writer);
            }
            CsvFile<T> result = new CsvFile<>(file, filename.apply(metadata), Files.size(file), metadata);
            complete = true;
            return result;
        } catch (IOException | UncheckedIOException ex) {
            throw new UserCommandException(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_EXPORT_UNAVAILABLE",
                "Выгрузка временно недоступна. Повторите позже.");
        } finally {
            if (!complete) delete(file);
        }
    }

    private static void delete(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (IOException ex) { file.toFile().deleteOnExit(); }
    }

    @Override public void close() { delete(path); }
}
