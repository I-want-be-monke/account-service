package ru.tsu.accounts.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.tsu.accounts.migration.MigrationValidationReport.Issue;

@Component
@Profile("migration")
class MigrationRunner implements ApplicationRunner, ExitCodeGenerator {
    private final MigrationImportService importer;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private int exitCode = 2;

    MigrationRunner(MigrationImportService importer, JdbcTemplate jdbc, ObjectMapper json) {
        this.importer = importer;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        MigrationValidationReport report;
        try {
            String mode = option(args, "import.mode");
            if (!mode.equals("dry-run") && !mode.equals("apply"))
                throw new IllegalArgumentException("Укажите --import.mode=dry-run или --import.mode=apply.");
            String target = option(args, "import.expected-database");
            String actor = option(args, "import.actor");
            Path directory = Path.of(option(args, "import.directory"));
            Target actual = jdbc.queryForObject("select current_database(), to_regclass('public.import_run') is not null",
                (rs, index) -> new Target(rs.getString(1), rs.getBoolean(2)));
            if (!target.equals(actual.name()))
                throw new IllegalArgumentException("Имя подключённой БД не совпадает с ожидаемой целью.");
            if (!actual.migrated())
                throw new IllegalArgumentException("Целевая БД должна быть предварительно обновлена до Flyway V6.");
            report = mode.equals("apply") ? importer.apply(directory, actor) : importer.dryRun(directory, actor);
        } catch (InvalidPathException ex) {
            report = invalid("Некорректный путь к каталогу пакета.");
        } catch (IllegalArgumentException ex) {
            report = invalid(ex.getMessage());
        } catch (DataAccessException ex) {
            report = invalid("Не удалось подключиться к целевой БД.");
        }
        exitCode = report.valid() ? 0 : 2;
        System.out.println(json.writeValueAsString(report));
    }

    @Override
    public int getExitCode() { return exitCode; }

    private static String option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank())
            throw new IllegalArgumentException("Укажите ровно одно значение --" + name + ".");
        return values.getFirst();
    }

    private static MigrationValidationReport invalid(String message) {
        return MigrationValidationReport.invalid(null, List.of(new Issue("cli", 0, message)));
    }

    private record Target(String name, boolean migrated) {}
}
