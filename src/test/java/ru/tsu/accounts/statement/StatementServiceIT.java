package ru.tsu.accounts.statement;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.tsu.accounts.identity.UserCommandException;

class StatementServiceIT extends StatementIntegrationSupport {
    @Test void historicalOpeningAndBothCorrectionsReconcileWithIndependentSql() {
        var f=control();
        var draft=prepare(f.account().id(),"CHARGE","9999",today());
        var statement=generate(f.account().id(),today(),today());
        assertThat(statement.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(statement.increase()).isEqualByComparingTo("50.00");
        assertThat(statement.decrease()).isEqualByComparingTo("500.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("550.00");
        assertThat(statement.currency()).isEqualTo("RUB");
        assertThat(statement.ownerId()).isEqualTo(ownClient());
        assertThat(statement.ownerName()).isEqualTo("Учебный клиент");
        assertThat(statement.generatedAt()).isNotNull();
        assertThat(statement.operations()).extracting(StatementLine::id)
            .containsExactly(f.payment().id(),f.chargeCorrection().id(),f.paymentCorrection().id())
            .doesNotContain(draft.id(),f.charge().id());
        assertThat(statement.operations()).extracting(StatementLine::balanceAfter)
            .containsExactly(new BigDecimal("600.00"),new BigDecimal("500.00"),new BigDecimal("550.00"));
        assertThat(statement.operations()).filteredOn(line->line.kind().equals("CORRECTION"))
            .extracting(StatementLine::originalOperationId).containsExactly(f.charge().id(),f.payment().id());

        var independent=jdbc.queryForMap("""
            select a.opening_balance + coalesce(sum(o.delta) filter(where o.operation_date < ?),0) as opening,
              coalesce(sum(o.delta) filter(where o.operation_date between ? and ? and o.delta>0),0) as increase,
              coalesce(sum(-o.delta) filter(where o.operation_date between ? and ? and o.delta<0),0) as decrease,
              a.opening_balance + coalesce(sum(o.delta) filter(where o.operation_date <= ?),0) as closing
            from personal_account a left join financial_operation o
              on o.account_id=a.id and o.storage_status='POSTED' and o.operation_date>=a.history_start_on
            where a.id=? group by a.id
            """,today(),today(),today(),today(),today(),today(),f.account().id());
        assertThat(statement.openingBalance()).isEqualByComparingTo((BigDecimal)independent.get("opening"));
        assertThat(statement.increase()).isEqualByComparingTo((BigDecimal)independent.get("increase"));
        assertThat(statement.decrease()).isEqualByComparingTo((BigDecimal)independent.get("decrease"));
        assertThat(statement.closingBalance()).isEqualByComparingTo((BigDecimal)independent.get("closing"));
        assertThat(jdbc.queryForObject("select amount from financial_operation where id=?",BigDecimal.class,f.charge().id()))
            .isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("select amount from financial_operation where id=?",BigDecimal.class,f.payment().id()))
            .isEqualByComparingTo("400.00");
    }

    @Test void completePeriodIncludesChargeAndReturns550FromZero() {
        var f=control();
        var statement=generate(f.account().id(),today().minusDays(1),today());
        assertThat(statement.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(statement.increase()).isEqualByComparingTo("1050.00");
        assertThat(statement.decrease()).isEqualByComparingTo("500.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("550.00");
        assertThat(statement.operations()).extracting(StatementLine::kind)
            .containsExactly("CHARGE","PAYMENT","CORRECTION","CORRECTION");
        assertThat(statement.operations()).extracting(StatementLine::amount)
            .containsExactly(new BigDecimal("1000.00"),new BigDecimal("400.00"),new BigDecimal("100.00"),new BigDecimal("50.00"));
    }

    @Test void bothDatesAreInclusiveAndLaterMovementsDoNotReplaceHistoricalBalance() {
        var a=openOwn();
        posted(a.id(),"CHARGE","100",today().minusDays(3));
        var first=posted(a.id(),"CHARGE","20",today().minusDays(2));
        var last=posted(a.id(),"CHARGE","30",today().minusDays(1));
        posted(a.id(),"CHARGE","40",today());
        prepare(a.id(),"CHARGE","9000",today().minusDays(2));
        var statement=generate(a.id(),today().minusDays(2),today().minusDays(1));
        assertThat(statement.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("150.00");
        assertThat(statement.increase()).isEqualByComparingTo("50.00");
        assertThat(statement.decrease()).isEqualByComparingTo("0.00");
        assertThat(statement.operations()).extracting(StatementLine::id).containsExactly(first.id(),last.id());
        assertThat(jdbc.queryForObject("select current_balance from personal_account where id=?",BigDecimal.class,a.id()))
            .isEqualByComparingTo("190.00");
    }

    @Test void emptyPeriodPreservesOpeningBalanceAndOverpayment() {
        UUID id=UUID.randomUUID();
        Long number=jdbc.queryForObject("select nextval('personal_account_number_seq')",Long.class);
        jdbc.update("""
            insert into personal_account(id,account_number,client_id,status,opened_on,history_start_on,
              opening_balance,current_balance,currency)
            values(?,?,?,'OPEN',?,?,37.50,37.50,'RUB')
            """,id,"LS-"+String.format("%010d",number),ownClient(),today().minusDays(40),today().minusDays(40));
        posted(id,"PAYMENT","50",today().minusDays(2));
        var statement=generate(id,today().minusDays(1),today());
        assertThat(statement.operations()).isEmpty();
        assertThat(statement.openingBalance()).isEqualByComparingTo("-12.50");
        assertThat(statement.closingBalance()).isEqualByComparingTo("-12.50");
        assertThat(statement.increase()).isEqualByComparingTo("0.00");
        assertThat(statement.decrease()).isEqualByComparingTo("0.00");
    }

    @Test void strictCalendarDatesHistoryBoundaryAndInclusive366DayLimit() {
        var a=openOwn();as("demo-client");
        assertThat(statements.generate(a.id(),today().minusDays(365).toString(),today().toString()).operations()).isEmpty();
        for(String[] pair:new String[][]{
            {null,today().toString()},{today().toString(),null},{"",today().toString()},
            {"garbage",today().toString()},{"2026-02-30",today().toString()},
            {"2025-02-29",today().toString()},{"2026-1-01",today().toString()},
            {today().toString(),today().minusDays(1).toString()},
            {today().minusDays(366).toString(),today().toString()},
            {a.historyStartOn().minusDays(1).toString(),a.historyStartOn().toString()}}) {
            assertThatThrownBy(()->statements.generate(a.id(),pair[0],pair[1]))
                .isInstanceOfSatisfying(UserCommandException.class,e->assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        // A real leap day is accepted when the account's history covers it.
        var leap=open(ownClient(),LocalDate.of(2024,2,1));as("demo-client");
        assertThat(statements.generate(leap.id(),"2024-02-29","2024-02-29").operations()).isEmpty();
        as("demo-admin");
        assertThatThrownBy(()->statements.generate(UUID.randomUUID(),today().toString(),today().toString()))
            .isInstanceOfSatisfying(UserCommandException.class,e->assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test void correctionBelongsToMovementDateAndSuspendedOrClosedAccountsRemainReadable() {
        var f=control();
        var historical=generate(f.account().id(),today().minusDays(1),today().minusDays(1));
        assertThat(historical.operations()).extracting(StatementLine::id).containsExactly(f.charge().id());
        assertThat(historical.closingBalance()).isEqualByComparingTo("1000.00");
        as("demo-operator");var actor=principal("demo-operator");
        var current=accounts.get(f.account().id());
        accounts.changeStatus(current.id(),current.version(),"SUSPENDED","Чтение выписки при паузе",
            actor.id(),actor.username(),UUID.randomUUID());
        assertThat(generate(current.id(),today(),today()).closingBalance()).isEqualByComparingTo("550.00");
        var closed=openOwn();
        posted(closed.id(),"CHARGE","20",today());posted(closed.id(),"PAYMENT","20",today());
        as("demo-operator");actor=principal("demo-operator");current=accounts.get(closed.id());
        accounts.changeStatus(current.id(),current.version(),"CLOSED","Закрытый счет доступен для чтения",
            actor.id(),actor.username(),UUID.randomUUID());
        var statement=generate(current.id(),today(),today());
        assertThat(statement.accountStatus()).isEqualTo("CLOSED");
        assertThat(statement.operations()).hasSize(2);
        assertThat(statement.closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test void repeatableReadSnapshotExcludesPostingCommittedBetweenSummaryAndRows() throws Exception {
        var a=openOwn();var charge=posted(a.id(),"CHARGE","100",today());
        var payment=prepare(a.id(),"PAYMENT","25",today());
        var snapshotReady=new CountDownLatch(1);var writerCommitted=new CountDownLatch(1);
        var lines=new ArrayList<StatementLine>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reader=pool.submit(()->{
                as("demo-client");
                try {
                    return statements.scan(a.id(),today().toString(),today().toString(),new StatementService.StatementSink(){
                        @Override public void start(Statement metadata) {
                            assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("repeatable read");
                            assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");
                            snapshotReady.countDown();
                            try {assertThat(writerCommitted.await(15,TimeUnit.SECONDS)).isTrue();}
                            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                        }
                        @Override public void line(StatementLine line) { lines.add(line); }
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            var writer=pool.submit(()->{
                try {
                    assertThat(snapshotReady.await(15,TimeUnit.SECONDS)).isTrue();
                    return post(payment);
                } finally {writerCommitted.countDown();SecurityContextHolder.clearContext();}
            });
            assertThat(writer.get(25,TimeUnit.SECONDS).id()).isEqualTo(payment.id());
            var during=reader.get(25,TimeUnit.SECONDS);
            assertThat(during.openingBalance()).isEqualByComparingTo("0.00");
            assertThat(during.increase()).isEqualByComparingTo("100.00");
            assertThat(during.decrease()).isEqualByComparingTo("0.00");
            assertThat(during.closingBalance()).isEqualByComparingTo("100.00");
            assertThat(lines).extracting(StatementLine::id).containsExactly(charge.id());
        }
        var after=generate(a.id(),today(),today());
        assertThat(after.operations()).extracting(StatementLine::id).containsExactly(charge.id(),payment.id());
        assertThat(after.closingBalance()).isEqualByComparingTo("75.00");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
}
