package ru.tsu.accounts.report;

import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** One set of SQL projections for summary, page and full export. */
@Repository
class ReportReadRepository {
    private static final String ACCOUNT_SCOPE = """
        select a.id as account_id,a.account_number,a.client_id,c.display_name as client_name,
               a.status,a.history_start_on,a.opening_balance
        from personal_account a join client c on c.id=a.client_id
        where a.history_start_on<=:endDate
        """;

    private static final String MONTHLY = """
        select account_id,account_number,client_id,client_name,status,
               original_amount as "%s",correction_amount as "%s",
               original_amount+correction_amount as "%s"
        from (
          select a.account_id,a.account_number,a.client_id,a.client_name,a.status,
                 coalesce(sum(o.delta*:sign) filter (where o.kind=:originalKind),0) as original_amount,
                 coalesce(sum(o.delta*:sign) filter (where o.kind='CORRECTION'),0) as correction_amount
          from scoped_accounts a
          join financial_operation o on o.account_id=a.account_id and o.storage_status='POSTED'
               and o.operation_date>=a.history_start_on
               and o.operation_date between :startDate and :endDate
          left join financial_operation source on source.id=o.corrects_operation_id
               and source.account_id=o.account_id and source.storage_status='POSTED'
          where o.kind=:originalKind or (o.kind='CORRECTION' and source.kind=:originalKind)
          group by a.account_id,a.account_number,a.client_id,a.client_name,a.status
        ) monthly_amounts
        """;

    private static final String DEBT = """
        select account_id,account_number,client_id,client_name,status,
               greatest(balance,0) as debt,greatest(-balance,0) as advance,balance
        from (
          select a.account_id,a.account_number,a.client_id,a.client_name,a.status,
                 a.opening_balance+coalesce(sum(o.delta),0) as balance
          from scoped_accounts a
          left join financial_operation o on o.account_id=a.account_id and o.storage_status='POSTED'
               and o.operation_date between a.history_start_on and :endDate
          group by a.account_id,a.account_number,a.client_id,a.client_name,a.status,a.opening_balance
        ) balances
        """;

    private static final String MOVEMENT = """
        select account_id,account_number,client_id,client_name,status,
               "openingBalance","originalCharges","originalPayments",corrections,increase,decrease,change,
               "openingBalance"+change as "closingBalance"
        from (
          select a.account_id,a.account_number,a.client_id,a.client_name,a.status,
                 a.opening_balance+coalesce(sum(o.delta) filter (where o.operation_date<:startDate),0)
                    as "openingBalance",
                 coalesce(sum(o.delta) filter (where o.operation_date>=:startDate and o.kind='CHARGE'),0)
                    as "originalCharges",
                 coalesce(sum(-o.delta) filter (where o.operation_date>=:startDate and o.kind='PAYMENT'),0)
                    as "originalPayments",
                 coalesce(sum(o.delta) filter (where o.operation_date>=:startDate and o.kind='CORRECTION'),0)
                    as corrections,
                 coalesce(sum(o.delta) filter (where o.operation_date>=:startDate and o.delta>0),0)
                    as increase,
                 coalesce(sum(-o.delta) filter (where o.operation_date>=:startDate and o.delta<0),0)
                    as decrease,
                 coalesce(sum(o.delta) filter (where o.operation_date>=:startDate),0) as change
          from scoped_accounts a
          left join financial_operation o on o.account_id=a.account_id and o.storage_status='POSTED'
               and o.operation_date between a.history_start_on and :endDate
          group by a.account_id,a.account_number,a.client_id,a.client_name,a.status,a.opening_balance
        ) movement
        """;

    private final NamedParameterJdbcTemplate jdbc;

    ReportReadRepository(DataSource dataSource) {
        // A separate template keeps the cursor fetch size local to reports.
        JdbcTemplate cursor = new JdbcTemplate(dataSource);
        cursor.setFetchSize(256);
        this.jdbc = new NamedParameterJdbcTemplate(cursor);
    }

    Projection projection(ReportQuery query) {
        var parameters = new MapSqlParameterSource("startDate", query.from())
            .addValue("endDate", query.to());
        StringBuilder scope = new StringBuilder(ACCOUNT_SCOPE);
        if (query.clientId() != null) {
            scope.append(" and a.client_id=:clientId\n");
            parameters.addValue("clientId", query.clientId());
        }
        if (query.status() != null) {
            scope.append(" and a.status=:status\n");
            parameters.addValue("status", query.status());
        }

        String rows = switch (query.type()) {
            case CHARGES -> {
                parameters.addValue("originalKind", "CHARGE").addValue("sign", 1);
                yield MONTHLY.formatted("originalCharges", "chargeCorrections", "netCharges");
            }
            case PAYMENTS -> {
                parameters.addValue("originalKind", "PAYMENT").addValue("sign", -1);
                yield MONTHLY.formatted("originalPayments", "paymentCorrections", "netPayments");
            }
            case DEBT -> DEBT;
            case MOVEMENT -> MOVEMENT;
        };
        String cte = "with scoped_accounts as (\n" + scope + "), report_rows as (\n" + rows + ")\n";
        return new Projection(cte, parameters, query.type().getColumns());
    }

    Summary summarize(Projection projection) {
        String aggregateColumns = projection.columns().stream()
            .map(column -> "coalesce(sum(\"" + column.key() + "\"),0) as \"" + column.key() + "\"")
            .collect(Collectors.joining(","));
        return jdbc.queryForObject(projection.cte() + "select count(*) as total_rows," + aggregateColumns
            + " from report_rows", projection.parameters(),
            (rs, index) -> new Summary(rs.getLong("total_rows"), amounts(rs, projection.columns())));
    }

    List<ReportRow> page(Projection projection, int limit, long offset) {
        var parameters = new MapSqlParameterSource(projection.parameters().getValues())
            .addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(orderedRows(projection) + " limit :limit offset :offset", parameters,
            (rs, index) -> row(rs, projection.columns()));
    }

    void visit(Projection projection, Consumer<ReportRow> sink) {
        jdbc.query(orderedRows(projection), projection.parameters(),
            (RowCallbackHandler) rs -> sink.accept(row(rs, projection.columns())));
    }

    private static String orderedRows(Projection projection) {
        return projection.cte() + "select * from report_rows order by account_number,account_id";
    }

    private static ReportRow row(ResultSet rs, List<ReportType.Column> columns) throws SQLException {
        return new ReportRow(rs.getObject("account_id", UUID.class), rs.getString("account_number"),
            rs.getObject("client_id", UUID.class), rs.getString("client_name"), rs.getString("status"),
            amounts(rs, columns));
    }

    private static Map<String, String> amounts(ResultSet rs, List<ReportType.Column> columns)
        throws SQLException {
        var amounts = new LinkedHashMap<String, String>();
        for (ReportType.Column column : columns) {
            amounts.put(column.key(), rs.getBigDecimal(column.key())
                .setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        }
        return amounts;
    }

    record Projection(String cte, MapSqlParameterSource parameters, List<ReportType.Column> columns) {}
    record Summary(long totalRows, Map<String, String> totals) {}
}
