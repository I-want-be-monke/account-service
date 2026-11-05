package ru.tsu.accounts.operation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import ru.tsu.accounts.identity.UserManagementService;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;

@SpringBootTest(properties={"server.ssl.enabled=false","account.http.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("dev") @Testcontainers
class ClientOwnershipIT {
 @Container static final PostgreSQLContainer<?> postgres=ru.tsu.accounts.support.LocalPostgres.create()
  .withDatabaseName("accounts").withUsername("account_owner").withPassword("test-owner-password")
  .withInitScript("db/test-roles.sql");
 @DynamicPropertySource static void props(DynamicPropertyRegistry p){
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
 @Autowired ClientService clients;
 @Autowired UserManagementService users;
 @Autowired AccountService accounts;
 @Autowired OperationService drafts;
 @Autowired OperationPostingService posting;
 @Autowired CorrectionService corrections;
 @Autowired HistoryQueryService history;
 @Autowired DatabaseUserDetailsService details;
 @Autowired JdbcTemplate jdbc;
 @Autowired MockMvc mvc;
 private static LocalDate today(){return LocalDate.now(ZoneId.of("Europe/Moscow"));}
 private AccountPrincipal principal(String login){return (AccountPrincipal)details.loadUserByUsername(login);}
 private UsernamePasswordAuthenticationToken auth(String login){
  var p=principal(login);return new UsernamePasswordAuthenticationToken(p,null,p.getAuthorities());
 }
 private void as(String login){SecurityContextHolder.getContext().setAuthentication(auth(login));}
 private AccountSummary open(UUID clientId){
  as("demo-operator");var actor=principal("demo-operator");
  return accounts.open(clientId,UUID.randomUUID(),today().minusDays(40),actor.id(),actor.username(),UUID.randomUUID());
 }
 private UUID ownClient(){return jdbc.queryForObject("select client_id from app_user where login='demo-client'",UUID.class);}
 private record Fixture(AccountSummary first,AccountSummary second,AccountSummary foreign,String foreignLogin,UUID foreignClient){}
 private Fixture fixture(){
  var first=open(ownClient());var second=open(ownClient());
  as("demo-operator");var operator=principal("demo-operator");
  var form=new ClientForm();form.setClientType("PERSON");form.setDisplayName("Чужой тестовый клиент");
  form.setIdentifierType("DEMO");form.setIdentifierValue(UUID.randomUUID().toString());
  UUID foreignId=clients.create(form,operator.id(),operator.username(),UUID.randomUUID()).id();
  var foreign=open(foreignId);
  String login="client-"+UUID.randomUUID().toString().substring(0,12);
  as("demo-admin");var admin=principal("demo-admin");
  users.create(login,"Второй клиент","strong-test-password","CLIENT",foreignId,
   admin.id(),admin.username(),UUID.randomUUID());
  return new Fixture(first,second,foreign,login,foreignId);
 }
 private OperationForm form(String kind,String amount,String document,LocalDate date){
  var form=new OperationForm();form.setKind(kind);form.setAmount(amount);
  form.setOperationDate(date.toString());form.setPeriodStart(date.withDayOfMonth(1).toString());
  form.setBasis("Проверка истории E7");form.setExternalNumber(document);
  if("PAYMENT".equals(kind))form.setPaymentMethod("MANUAL_TRANSFER");
  return form;
 }
 private OperationSummary prepare(UUID account,String kind,String amount,LocalDate date){
  as("demo-accountant");var actor=principal("demo-accountant");
  return drafts.prepare(account,form(kind,amount,kind.equals("PAYMENT")?"pay-"+UUID.randomUUID():null,date),
   actor.id(),actor.username(),UUID.randomUUID());
 }
 private OperationSummary post(OperationSummary draft){
  as("demo-accountant");var actor=principal("demo-accountant");
  return posting.post(draft.id(),draft.version(),UUID.randomUUID(),drafts.readyFingerprint(draft.id()),
   actor.id(),actor.username(),UUID.randomUUID());
 }
 private OperationSummary correct(UUID source,String target){
  as("demo-accountant");var actor=principal("demo-accountant");
  var form=new CorrectionForm();form.setTargetAmount(target);form.setReason("Уточнение E7");
  form.setBasis("Акт проверки");
  return post(corrections.prepare(source,form,actor.id(),actor.username(),UUID.randomUUID()));
 }
 @Test void filteredHistoryAndJsonKeepFullBalanceAndStablePages() throws Exception {
  var f=fixture();var yesterday=today().minusDays(1);
  var charge=post(prepare(f.first.id(),"CHARGE","1000",yesterday));
  var payment=post(prepare(f.first.id(),"PAYMENT","400",today()));
  correct(charge.id(),"900");correct(payment.id(),"350");
  var extra=post(prepare(f.first.id(),"CHARGE","10",today()));
  assertThat(jdbc.queryForObject("select current_balance from personal_account where id=?",
   java.math.BigDecimal.class,f.first.id())).isEqualByComparingTo("560.00");
  as("demo-client");
  var filtered=history.search(f.first.id(),today().toString(),today().toString(),"CORRECTION",0,1);
  assertThat(filtered.getTotalElements()).isEqualTo(2);
  assertThat(filtered.getContent()).hasSize(1).allMatch(o->o.kind().equals("CORRECTION"));
  assertThat(history.search(f.first.id(),today().toString(),today().toString(),"CORRECTION",1,1)
   .getContent()).hasSize(1);
  assertThat(history.search(f.first.id(),yesterday.toString(),yesterday.toString(),"CHARGE",0,20)
   .getContent()).extracting(OperationView::id).containsExactly(charge.id());
  List<UUID> all=new ArrayList<>();
  for(int page=0;page<3;page++)
   history.search(f.first.id(),"","","",page,2).forEach(o->all.add(o.id()));
  assertThat(all).hasSize(5);
  assertThat(new HashSet<>(all)).hasSize(5);
  var expected=jdbc.query("select id from financial_operation where account_id=? and storage_status='POSTED' order by operation_date desc,posted_at desc,id desc",
   (rs,n)->rs.getObject(1,UUID.class),f.first.id());
  assertThat(all).containsExactlyElementsOf(expected);
  var html=mvc.perform(get("/accounts/"+f.first.id()+"/operations").secure(true)
   .with(authentication(auth("demo-client"))).param("kind","CORRECTION").param("size","1"))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(html).contains("560.00").contains("Найдено движений: 2")
   .contains("kind=CORRECTION").doesNotContain(extra.id().toString());
  mvc.perform(get("/api/v1/accounts/"+f.first.id()).secure(true)
   .with(authentication(auth("demo-client"))))
   .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value("560.00"))
   .andExpect(jsonPath("$.number").value(f.first.accountNumber()))
   .andExpect(jsonPath("$.password").doesNotExist());
  mvc.perform(get("/api/v1/accounts/"+f.first.id()+"/operations").secure(true)
   .with(authentication(auth("demo-client"))).param("kind","CORRECTION").param("size","1"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
   .andExpect(jsonPath("$.accountBalance").value("560.00"))
   .andExpect(jsonPath("$.items[0].amount").isString())
   .andExpect(jsonPath("$.items[0].kind").value("CORRECTION"))
   .andExpect(jsonPath("$.items[0].createdBy").doesNotExist());
 }
 @Test void clientOwnsTwoAccountsAndCannotReadForeignIdsOrDrafts() throws Exception {
  var f=fixture();
  var own=post(prepare(f.first.id(),"CHARGE","123",today()));
  var foreign=post(prepare(f.foreign.id(),"CHARGE","987",today()));
  var draft=prepare(f.first.id(),"CHARGE","9999",today());
  var foreignCorrection=correct(foreign.id(),"900");
  var ownCorrection=correct(own.id(),"100");
  as("demo-operator");var operator=principal("demo-operator");
  accounts.changeStatus(f.second.id(),f.second.version(),"CLOSED","Проверка чтения закрытого счета",
   operator.id(),operator.username(),UUID.randomUUID());
  as("demo-client");
  assertThat(accounts.own(0).getContent()).extracting(AccountSummary::id)
   .contains(f.first.id(),f.second.id()).doesNotContain(f.foreign.id());
  var ownPage=mvc.perform(get("/my/accounts").secure(true).with(authentication(auth("demo-client"))))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(ownPage).contains(f.first.accountNumber(),f.second.accountNumber())
   .doesNotContain(f.foreign.accountNumber());
  mvc.perform(get("/accounts/"+f.second.id()).secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isOk());
  mvc.perform(get("/accounts/"+f.second.id()+"/operations").secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isOk());
  mvc.perform(get("/operations/"+own.id()).secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isOk())
   .andExpect(content().string(org.hamcrest.Matchers.containsString("Проведенные исправления")));
  mvc.perform(get("/operations/"+ownCorrection.id()).secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isOk());
  mvc.perform(get("/accounts/"+f.first.id()+"/operations").secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isOk())
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(draft.id().toString()))));
  mvc.perform(get("/api/v1/accounts/"+f.first.id()+"/operations").secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isOk())
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(draft.id().toString()))));
  mvc.perform(get("/operations/"+draft.id()).secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isForbidden());
  for(String path:List.of("/accounts/"+f.foreign.id(),"/accounts/"+f.foreign.id()+"/operations",
     "/operations/"+foreign.id(),"/operations/"+foreignCorrection.id(),
     "/api/v1/accounts/"+f.foreign.id(),"/api/v1/accounts/"+f.foreign.id()+"/operations")){
   String body=mvc.perform(get(path).secure(true).with(authentication(auth("demo-client"))))
    .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
   assertThat(body).doesNotContain(f.foreign.accountNumber(),"987.00","Чужой тестовый клиент");
  }
  assertThat(jdbc.queryForObject("select count(*) from audit_event where actor_user_id=? and action='USER_COMMAND_DENIED' and reason_code='ACCOUNT_NOT_OWNED'",
   Integer.class,principal("demo-client").id())).isGreaterThanOrEqualTo(2);
  mvc.perform(get("/clients/"+f.foreignClient).secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isForbidden());
  as(f.foreignLogin);
  mvc.perform(get("/accounts/"+f.foreign.id()).secure(true)
   .with(authentication(auth(f.foreignLogin)))).andExpect(status().isOk());
  mvc.perform(get("/accounts/"+f.first.id()).secure(true)
   .with(authentication(auth(f.foreignLogin)))).andExpect(status().isForbidden());
 }
 @Test void preparedCorrectionCanBeDiscardedWithoutTouchingPostedHistory() throws Exception {
  var f=fixture();var source=post(prepare(f.first.id(),"CHARGE","200",today()));
  as("demo-accountant");var actor=principal("demo-accountant");
  var form=new CorrectionForm();form.setTargetAmount("150");form.setReason("Неверная сумма");
  form.setBasis("Акт проверки");
  var draft=corrections.prepare(source.id(),form,actor.id(),actor.username(),UUID.randomUUID());
  mvc.perform(get("/operations/"+draft.id()+"/discard").secure(true)
   .with(authentication(auth("demo-accountant")))).andExpect(status().isOk());
  as("demo-accountant");
  drafts.discard(draft.id(),draft.version(),actor.id(),actor.username(),UUID.randomUUID());
  assertThat(jdbc.queryForObject("select count(*) from financial_operation where id=?",
   Integer.class,draft.id())).isZero();
  assertThat(jdbc.queryForObject("select amount from financial_operation where id=?",
   java.math.BigDecimal.class,source.id())).isEqualByComparingTo("200.00");
 }
 @Test void invalidFiltersAndApiRolesReturnSafeErrors() throws Exception {
  var f=fixture();as("demo-client");
  for(String[] pair:new String[][]{{"from","garbage"},{"from","2026-02-30"},
      {"from",today().toString()+"&to="+today().minusDays(1)},{"kind","UNKNOWN"},
      {"size","0"},{"size","101"},{"page","-1"}}){
   String[] parts=pair[1].split("&to=");
   var req=get("/api/v1/accounts/"+f.first.id()+"/operations").secure(true)
    .with(authentication(auth("demo-client"))).param(pair[0],parts[0]);
   if(parts.length==2)req.param("to",parts[1]);
   mvc.perform(req).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists())
    .andExpect(jsonPath("$.requestId").exists());
  }
  mvc.perform(get("/accounts/"+f.first.id()+"/operations").secure(true)
   .with(authentication(auth("demo-client"))).param("from","2026-02-30"))
   .andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/accounts/"+f.first.id()+"/operations").secure(true)
   .with(authentication(auth("demo-admin")))).andExpect(status().isForbidden())
   .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  SecurityContextHolder.clearContext();
  mvc.perform(get("/api/v1/accounts/"+f.first.id()).secure(true).with(anonymous()))
   .andExpect(status().isUnauthorized())
   .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  mvc.perform(get("/api/v1/accounts/not-a-uuid").secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isBadRequest())
   .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  var current=principal("demo-client");
  var stale=new AccountPrincipal(current.id(),current.username(),current.password(),
   current.fullName(),current.role(),current.authVersion()+1);
  mvc.perform(get("/api/v1/accounts/"+f.first.id()).secure(true)
   .with(authentication(new UsernamePasswordAuthenticationToken(stale,null,stale.getAuthorities()))))
   .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  assertThat(jdbc.queryForObject("select count(*) from financial_operation where account_id=?",
   Integer.class,f.first.id())).isZero();
 }
}
