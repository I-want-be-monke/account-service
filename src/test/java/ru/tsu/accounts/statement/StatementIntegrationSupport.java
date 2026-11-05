package ru.tsu.accounts.statement;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.account.AccountSummary;
import ru.tsu.accounts.client.ClientForm;
import ru.tsu.accounts.client.ClientService;
import ru.tsu.accounts.operation.CorrectionForm;
import ru.tsu.accounts.operation.CorrectionService;
import ru.tsu.accounts.operation.OperationForm;
import ru.tsu.accounts.operation.OperationPostingService;
import ru.tsu.accounts.operation.OperationService;
import ru.tsu.accounts.operation.OperationSummary;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;
import ru.tsu.accounts.support.LocalPostgres;
import ru.tsu.accounts.support.PostgresContextLifecycle;

@SpringBootTest(properties={"server.ssl.enabled=false","account.http.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
abstract class StatementIntegrationSupport extends PostgresContextLifecycle {
    @Container static final PostgreSQLContainer<?> postgres = LocalPostgres.create()
        .withDatabaseName("accounts").withUsername("account_owner")
        .withPassword("test-owner-password").withInitScript("db/test-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",postgres::getJdbcUrl);
        p.add("spring.datasource.username",postgres::getUsername);
        p.add("spring.datasource.password",postgres::getPassword);
        p.add("spring.flyway.user",postgres::getUsername);
        p.add("spring.flyway.password",postgres::getPassword);
        p.add("account.demo.passwords.admin",()->"correct-password-for-e1");
        p.add("account.demo.passwords.operator",()->"operator-password-for-e1");
        p.add("account.demo.passwords.accountant",()->"accountant-password-for-e1");
        p.add("account.demo.passwords.manager",()->"manager-password-for-e1");
        p.add("account.demo.passwords.client",()->"client-password-for-e1");
    }

    @Autowired StatementService statements;
    @Autowired AccountService accounts;
    @Autowired ClientService clients;
    @Autowired OperationService drafts;
    @Autowired OperationPostingService posting;
    @Autowired CorrectionService corrections;
    @Autowired DatabaseUserDetailsService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    static LocalDate today() { return LocalDate.now(ZoneId.of("Europe/Moscow")); }
    AccountPrincipal principal(String login) { return (AccountPrincipal)users.loadUserByUsername(login); }
    UsernamePasswordAuthenticationToken auth(String login) {
        var p=principal(login);
        return new UsernamePasswordAuthenticationToken(p,null,p.getAuthorities());
    }
    void as(String login) { SecurityContextHolder.getContext().setAuthentication(auth(login)); }
    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }
    UUID ownClient() {
        return jdbc.queryForObject("select client_id from app_user where login='demo-client'",UUID.class);
    }
    AccountSummary openOwn() { return open(ownClient(),today().minusDays(400)); }
    AccountSummary open(UUID clientId,LocalDate day) {
        as("demo-operator");var actor=principal("demo-operator");
        return accounts.open(clientId,UUID.randomUUID(),day,actor.id(),actor.username(),UUID.randomUUID());
    }
    AccountSummary foreign() {
        as("demo-operator");var actor=principal("demo-operator");
        var form=new ClientForm();form.setClientType("PERSON");
        form.setDisplayName("Секретное имя чужого клиента E8");
        form.setIdentifierType("DEMO");form.setIdentifierValue(UUID.randomUUID().toString());
        var client=clients.create(form,actor.id(),actor.username(),UUID.randomUUID());
        return open(client.id(),today().minusDays(400));
    }
    OperationSummary prepare(UUID account,String kind,String amount,LocalDate day) {
        as("demo-accountant");var actor=principal("demo-accountant");
        var form=new OperationForm();form.setKind(kind);form.setAmount(amount);
        form.setOperationDate(day.toString());form.setPeriodStart(day.withDayOfMonth(1).toString());
        form.setBasis("Проверка настоящей выписки E8");
        if("PAYMENT".equals(kind)) {
            form.setPaymentMethod("MANUAL_TRANSFER");form.setExternalNumber("E8-"+UUID.randomUUID());
        }
        return drafts.prepare(account,form,actor.id(),actor.username(),UUID.randomUUID());
    }
    OperationSummary post(OperationSummary draft) {
        as("demo-accountant");var actor=principal("demo-accountant");
        return posting.post(draft.id(),draft.version(),UUID.randomUUID(),drafts.readyFingerprint(draft.id()),
            actor.id(),actor.username(),UUID.randomUUID());
    }
    OperationSummary posted(UUID account,String kind,String amount,LocalDate day) {
        return post(prepare(account,kind,amount,day));
    }
    OperationSummary correct(UUID original,String target) {
        as("demo-accountant");var actor=principal("demo-accountant");
        var form=new CorrectionForm();form.setOperationDate(today().toString());
        form.setTargetAmount(target);form.setReason("Уточнение исходного документа E8");
        form.setBasis("Синтетическая проверка выписки E8");
        return post(corrections.prepare(original,form,actor.id(),actor.username(),UUID.randomUUID()));
    }
    record Control(AccountSummary account,OperationSummary charge,OperationSummary payment,
                   OperationSummary chargeCorrection,OperationSummary paymentCorrection) {}
    Control control() {
        var account=openOwn();
        var charge=posted(account.id(),"CHARGE","1000",today().minusDays(1));
        var payment=posted(account.id(),"PAYMENT","400",today());
        return new Control(account,charge,payment,correct(charge.id(),"900"),correct(payment.id(),"350"));
    }
    Statement generate(UUID account,LocalDate from,LocalDate to) {
        as("demo-client");return statements.generate(account,from.toString(),to.toString());
    }
}
