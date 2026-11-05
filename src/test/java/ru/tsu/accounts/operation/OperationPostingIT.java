package ru.tsu.accounts.operation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
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
import ru.tsu.accounts.account.AccountRepository;
import ru.tsu.accounts.account.AccountSummary;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditWriter;
import ru.tsu.accounts.client.ClientForm;
import ru.tsu.accounts.client.ClientService;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;

@SpringBootTest(properties={"server.ssl.enabled=false","account.http.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("dev") @Testcontainers
class OperationPostingIT {
 @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11-bookworm")
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
 @Autowired OperationService drafts;
 @Autowired OperationPostingService posting;
 @SpyBean AccountRepository accountRepository;
 @Autowired ClientService clients;
 @Autowired AccountService accounts;
 @Autowired JdbcTemplate jdbc;
 @Autowired DatabaseUserDetailsService users;
 @Autowired MockMvc mvc;
 @SpyBean AuditWriter auditWriter;
 private static LocalDate today(){return LocalDate.now(ZoneId.of("Europe/Moscow"));}
 private AccountPrincipal principal(String login){return (AccountPrincipal)users.loadUserByUsername(login);}
 private void as(String login){
  var p=principal(login);
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,p.getAuthorities()));
 }
 private UsernamePasswordAuthenticationToken auth(String login){
  var p=principal(login);return new UsernamePasswordAuthenticationToken(p,null,p.getAuthorities());
 }
 private AccountSummary account(){
  as("demo-operator");
  var actor=principal("demo-operator");
  ClientForm f=new ClientForm();
  f.setClientType("PERSON");f.setDisplayName("Финансовый тест");
  f.setIdentifierType("DEMO");f.setIdentifierValue(UUID.randomUUID().toString());
  UUID clientId=clients.create(f,actor.id(),actor.username(),UUID.randomUUID()).id();
  return accounts.open(clientId,UUID.randomUUID(),today(),actor.id(),actor.username(),UUID.randomUUID());
 }
 private OperationForm form(String kind,String value,String number){
  OperationForm f=new OperationForm();f.setKind(kind);f.setAmount(value);f.setBasis("Проверка расчета");
  f.setOperationDate(today().toString());
  f.setPeriodStart(today().withDayOfMonth(1).toString());
  f.setExternalNumber(number);
  if(kind.equals("PAYMENT"))f.setPaymentMethod("MANUAL_TRANSFER");
  return f;
 }
 private OperationSummary draft(UUID id,String kind,String value,String number){
  as("demo-accountant");var actor=principal("demo-accountant");
  return drafts.prepare(id,form(kind,value,number),actor.id(),actor.username(),UUID.randomUUID());
 }
 private OperationSummary posted(OperationSummary draft,UUID key){
  as("demo-accountant");var actor=principal("demo-accountant");
  String fingerprint=drafts.readyFingerprint(draft.id());
  return posting.post(draft.id(),draft.version(),key,fingerprint,actor.id(),actor.username(),UUID.randomUUID());
 }
 private String balance(UUID id){return jdbc.queryForObject("select current_balance::text from personal_account where id=?",String.class,id);}
 @Test void chargePaymentBalanceAdvanceAndReconcile(){
  var a=account();
  var charge=draft(a.id(),"CHARGE","1000.00","inv-"+UUID.randomUUID());
  assertThat(balance(a.id())).isEqualTo("0.00");
  posted(charge,UUID.randomUUID());
  assertThat(balance(a.id())).isEqualTo("1000.00");
  posted(draft(a.id(),"PAYMENT","400","pay-"+UUID.randomUUID()),UUID.randomUUID());
  assertThat(balance(a.id())).isEqualTo("600.00");
  var b=account();
  posted(draft(b.id(),"CHARGE","400",null),UUID.randomUUID());
  posted(draft(b.id(),"PAYMENT","500","pay-"+UUID.randomUUID()),UUID.randomUUID());
  assertThat(balance(b.id())).isEqualTo("-100.00");
  for(UUID id:new UUID[]{a.id(),b.id()}){
   var difference=jdbc.queryForObject("select a.current_balance-a.opening_balance-"
    +"coalesce((select sum(o.delta) from financial_operation o where o.account_id=a.id and o.storage_status='POSTED'),0)"
    +" from personal_account a where a.id=?",java.math.BigDecimal.class,id);
   assertThat(difference).isEqualByComparingTo("0");
  }
  assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Integer.class)).isEqualTo(4);
 }
 @Test void replayFingerprintDocumentGlobalAndImmutablePostedRow(){
  var a=account();var b=account();
  String number="DOC-"+UUID.randomUUID();
  var first=draft(a.id(),"CHARGE","1000",number);
  var second=draft(b.id(),"CHARGE","1000",number.toLowerCase());
  as("demo-accountant");var actor=principal("demo-accountant");
  UUID key=UUID.randomUUID();String fingerprint=drafts.readyFingerprint(first.id());
  posted(first,key);
  assertThat(posting.post(first.id(),first.version(),key,fingerprint,actor.id(),actor.username(),UUID.randomUUID()).id())
   .isEqualTo(first.id());
  assertThatThrownBy(()->posting.post(first.id(),first.version(),key,"0".repeat(64),
   actor.id(),actor.username(),UUID.randomUUID())).isInstanceOf(UserCommandException.class);
  assertThatThrownBy(()->posted(second,UUID.randomUUID()))
   .isInstanceOf(UserCommandException.class).hasMessageContaining("Документ");
  assertThat(jdbc.queryForObject("select count(*) from financial_operation where storage_status='POSTED' and external_number_key=?",
   Integer.class,number.toLowerCase())).isEqualTo(1);
  assertThat(balance(b.id())).isEqualTo("0.00");
  assertThatThrownBy(()->jdbc.update("update financial_operation set amount=2 where id=?",first.id()))
   .isInstanceOf(DataIntegrityViolationException.class);
  assertThatThrownBy(()->jdbc.update("delete from financial_operation where id=?",first.id()))
   .isInstanceOf(DataIntegrityViolationException.class);
  assertThat(jdbc.queryForObject("select amount::text from financial_operation where id=?",String.class,first.id())).isEqualTo("1000.00");
 }
 @Test void draftEditDiscardAndAccountClosingGuard(){
  var a=account();var prepared=draft(a.id(),"CHARGE","100","edit-"+UUID.randomUUID());
  as("demo-accountant");var accountant=principal("demo-accountant");
  OperationForm edit=form("CHARGE","150","edit-new-"+UUID.randomUUID());
  edit.setVersion(prepared.version());
  var changed=drafts.edit(prepared.id(),edit,accountant.id(),accountant.username(),UUID.randomUUID());
  assertThat(changed.amount()).isEqualByComparingTo("150.00");
  assertThat(balance(a.id())).isEqualTo("0.00");
  as("demo-operator");var operator=principal("demo-operator");
  assertThatThrownBy(()->accounts.changeStatus(a.id(),a.version(),"CLOSED","Проверка черновика",
   operator.id(),operator.username(),UUID.randomUUID())).isInstanceOf(UserCommandException.class).hasMessageContaining("черновик");
  as("demo-accountant");
  drafts.discard(prepared.id(),changed.version(),accountant.id(),accountant.username(),UUID.randomUUID());
  assertThat(jdbc.queryForObject("select count(*) from financial_operation where id=?",Integer.class,prepared.id())).isZero();
  assertThat(jdbc.queryForObject("select count(*) from audit_event where action='OPERATION_DRAFT_DISCARDED' and object_id=?",
   Integer.class,prepared.id().toString())).isEqualTo(1);
  as("demo-operator");
  var closed=accounts.changeStatus(a.id(),a.version(),"CLOSED","После удаления черновика",
   operator.id(),operator.username(),UUID.randomUUID());
  assertThat(closed.status()).isEqualTo("CLOSED");
  as("demo-accountant");
  assertThatThrownBy(()->draft(a.id(),"PAYMENT","10","late-"+UUID.randomUUID()))
   .isInstanceOf(UserCommandException.class);
 }
 @Test void suspendedAccountCannotPostAndDeniedActionDoesNotChangeBalance(){
  var a=account();var prepared=draft(a.id(),"CHARGE","100",null);
  as("demo-operator");var actor=principal("demo-operator");
  var suspended=accounts.changeStatus(a.id(),a.version(),"SUSPENDED","Пауза",
   actor.id(),actor.username(),UUID.randomUUID());
  as("demo-accountant");
  assertThatThrownBy(()->posted(prepared,UUID.randomUUID())).isInstanceOf(UserCommandException.class);
  assertThat(balance(a.id())).isEqualTo("0.00");
  as("demo-operator");
  accounts.changeStatus(a.id(),suspended.version(),"OPEN","Возобновление",
   actor.id(),actor.username(),UUID.randomUUID());
  posted(prepared,UUID.randomUUID());
  assertThat(balance(a.id())).isEqualTo("100.00");
 }
 @Test void failureWritingMandatoryAuditRollsBackPostingAndBalance(){
  var a=account();var prepared=draft(a.id(),"CHARGE","1000","fault-"+UUID.randomUUID());
  doAnswer(inv->{
   AuditEntry entry=inv.getArgument(0);
   if("OPERATION_POSTED".equals(entry.action()))throw new DataAccessResourceFailureException("simulated audit failure");
   return inv.callRealMethod();
  }).when(auditWriter).insert(any(AuditEntry.class));
  assertThatThrownBy(()->posted(prepared,UUID.randomUUID())).isInstanceOf(DataAccessResourceFailureException.class);
  assertThat(balance(a.id())).isEqualTo("0.00");
  assertThat(jdbc.queryForObject("select storage_status from financial_operation where id=?",String.class,prepared.id()))
   .isEqualTo("PREPARED");
  assertThat(jdbc.queryForObject("select count(*) from audit_event where action='OPERATION_POSTED' and object_id=?",
   Integer.class,prepared.id().toString())).isZero();
 }
 @Test void concurrentChargePaymentAndHundredSmallCharges() throws Exception {
  var a=account();
  var charge=draft(a.id(),"CHARGE","1000",null);
  var payment=draft(a.id(),"PAYMENT","400","parallel-"+UUID.randomUUID());
  var gate=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->{as("demo-accountant");gate.await(5,TimeUnit.SECONDS);
    try{return posted(charge,UUID.randomUUID()).id();}finally{SecurityContextHolder.clearContext();}});
   var two=pool.submit(()->{as("demo-accountant");gate.await(5,TimeUnit.SECONDS);
    try{return posted(payment,UUID.randomUUID()).id();}finally{SecurityContextHolder.clearContext();}});
   gate.countDown();
   assertThat(one.get(20,TimeUnit.SECONDS)).isEqualTo(charge.id());
   assertThat(two.get(20,TimeUnit.SECONDS)).isEqualTo(payment.id());
  }
  assertThat(balance(a.id())).isEqualTo("600.00");
  for(int i=0;i<100;i++)posted(draft(a.id(),"CHARGE","0.01",null),UUID.randomUUID());
  assertThat(balance(a.id())).isEqualTo("601.00");
  assertThat(jdbc.queryForObject("select count(*) from financial_operation where account_id=? and storage_status='POSTED'",
   Integer.class,a.id())).isEqualTo(102);
  assertThat(jdbc.queryForObject("select current_balance-opening_balance-sum(delta) from personal_account join financial_operation on personal_account.id=account_id where personal_account.id=? group by personal_account.id",java.math.BigDecimal.class,a.id()))
   .isEqualByComparingTo("0");
 }
 @Test void roleCsrfConfirmationAndReplayOverRealJdbcSession() throws Exception {
  var a=account();var prepared=draft(a.id(),"CHARGE","50",null);
  mvc.perform(get("/accounts/"+a.id()+"/operations/new").secure(true).with(authentication(auth("demo-operator"))))
   .andExpect(status().isForbidden());
  mvc.perform(get("/accounts/"+a.id()+"/operations/new").secure(true).with(authentication(auth("demo-admin"))))
   .andExpect(status().isForbidden());
  mvc.perform(post("/accounts/"+a.id()+"/operations/new").secure(true)
   .with(authentication(auth("demo-accountant"))).param("kind","CHARGE").param("amount","10"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/operations/"+prepared.id()+"/confirm").secure(true)
   .with(authentication(auth("demo-accountant"))).with(csrf()).param("token","invented"))
   .andExpect(status().isConflict());
  var login=mvc.perform(post("/login").secure(true).with(csrf()).param("username","demo-accountant")
   .param("password","accountant-password-for-e1")).andExpect(status().is3xxRedirection()).andReturn();
  Cookie cookie=new Cookie("ACCOUNT_SESSION",login.getResponse().getHeader("Set-Cookie")
   .split("ACCOUNT_SESSION=",2)[1].split(";",2)[0]);
  String html=mvc.perform(get("/operations/"+prepared.id()+"/confirm").secure(true).cookie(cookie))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  var matcher=java.util.regex.Pattern.compile("name=\"token\" value=\"([^\"]+)\"").matcher(html);
  assertThat(matcher.find()).isTrue();
  String token=matcher.group(1);
  mvc.perform(post("/operations/"+prepared.id()+"/confirm").secure(true).cookie(cookie).with(csrf())
   .param("token",token)).andExpect(status().is3xxRedirection());
  mvc.perform(post("/operations/"+prepared.id()+"/confirm").secure(true).cookie(cookie).with(csrf())
   .param("token",token)).andExpect(status().is3xxRedirection());
  assertThat(balance(a.id())).isEqualTo("50.00");
  assertThat(jdbc.queryForObject("select count(*) from audit_event where action='OPERATION_POSTED' and object_id=?",
   Integer.class,prepared.id().toString())).isEqualTo(1);
 }
 @Test void concurrentSameKeyReturnsOnePostedResult() throws Exception {
  var a=account();var draft=draft(a.id(),"CHARGE","90",null);
  as("demo-accountant");var actor=principal("demo-accountant");
  String fingerprint=drafts.readyFingerprint(draft.id());UUID key=UUID.randomUUID();
  var gate=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->{as("demo-accountant");gate.await(5,TimeUnit.SECONDS);
    try{return posting.post(draft.id(),draft.version(),key,fingerprint,actor.id(),actor.username(),UUID.randomUUID()).id();}
    finally{SecurityContextHolder.clearContext();}});
   var two=pool.submit(()->{as("demo-accountant");gate.await(5,TimeUnit.SECONDS);
    try{return posting.post(draft.id(),draft.version(),key,fingerprint,actor.id(),actor.username(),UUID.randomUUID()).id();}
    finally{SecurityContextHolder.clearContext();}});
   gate.countDown();
   assertThat(one.get(20,TimeUnit.SECONDS)).isEqualTo(draft.id());
   assertThat(two.get(20,TimeUnit.SECONDS)).isEqualTo(draft.id());
  }
  assertThat(balance(a.id())).isEqualTo("90.00");
  assertThat(jdbc.queryForObject("select count(*) from audit_event where action='OPERATION_POSTED' and object_id=?",
   Integer.class,draft.id().toString())).isEqualTo(1);
 }
 @Test void failureAfterOperationFlushRollsBackTheWholePosting(){
  var a=account();var prepared=draft(a.id(),"CHARGE","777",null);
  doAnswer(inv->{throw new DataAccessResourceFailureException("simulated account flush failure");})
   .when(accountRepository).flush();
  assertThatThrownBy(()->posted(prepared,UUID.randomUUID()))
   .isInstanceOf(DataAccessResourceFailureException.class);
  assertThat(balance(a.id())).isEqualTo("0.00");
  assertThat(jdbc.queryForObject("select storage_status from financial_operation where id=?",String.class,prepared.id()))
   .isEqualTo("PREPARED");
 }
 @Test void mandatoryAuditAlsoProtectsDraftCreation(){
  var a=account();int before=jdbc.queryForObject(
   "select count(*) from financial_operation where account_id=?",Integer.class,a.id());
  doAnswer(inv->{
   AuditEntry event=inv.getArgument(0);
   if("OPERATION_PREPARED".equals(event.action()))throw new DataAccessResourceFailureException("audit failure");
   return inv.callRealMethod();
  }).when(auditWriter).insert(any(AuditEntry.class));
  assertThatThrownBy(()->draft(a.id(),"CHARGE","10",null))
   .isInstanceOf(DataAccessResourceFailureException.class);
  assertThat(jdbc.queryForObject("select count(*) from financial_operation where account_id=?",
   Integer.class,a.id())).isEqualTo(before);
 }
 @Test void concurrentSameDocumentAcrossAccountsAllowsOnlyOneEffect() throws Exception {
  var a=account();var b=account();
  String document="shared-"+UUID.randomUUID();
  var left=draft(a.id(),"PAYMENT","20",document);
  var right=draft(b.id(),"PAYMENT","20",document);
  var gate=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->{
    as("demo-accountant");gate.await(5,TimeUnit.SECONDS);
    try{posted(left,UUID.randomUUID());return true;}
    catch(UserCommandException|DataIntegrityViolationException conflict){return false;}
    finally{SecurityContextHolder.clearContext();}
   });
   var two=pool.submit(()->{
    as("demo-accountant");gate.await(5,TimeUnit.SECONDS);
    try{posted(right,UUID.randomUUID());return true;}
    catch(UserCommandException|DataIntegrityViolationException conflict){return false;}
    finally{SecurityContextHolder.clearContext();}
   });
   gate.countDown();
   assertThat(one.get(20,TimeUnit.SECONDS)^two.get(20,TimeUnit.SECONDS)).isTrue();
  }
  assertThat(jdbc.queryForObject("select count(*) from financial_operation where external_number_key=? and storage_status='POSTED'",
   Integer.class,document.toLowerCase())).isEqualTo(1);
  assertThat(new java.math.BigDecimal(balance(a.id())).add(new java.math.BigDecimal(balance(b.id()))))
   .isEqualByComparingTo("-20.00");
 }
 @Test void simultaneousPreparationAndClosingCannotLeaveDraftOnClosedAccount() throws Exception {
  var a=account();
  var operator=principal("demo-operator");var accountant=principal("demo-accountant");
  var gate=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->{
    as("demo-accountant");gate.await(5,TimeUnit.SECONDS);
    try{drafts.prepare(a.id(),form("CHARGE","20",null),accountant.id(),accountant.username(),UUID.randomUUID());}
    catch(UserCommandException ignored){} finally{SecurityContextHolder.clearContext();}
    return true;
   });
   var two=pool.submit(()->{
    as("demo-operator");gate.await(5,TimeUnit.SECONDS);
    try{accounts.changeStatus(a.id(),a.version(),"CLOSED","Конкурентное закрытие",
     operator.id(),operator.username(),UUID.randomUUID());}
    catch(UserCommandException ignored){} finally{SecurityContextHolder.clearContext();}
    return true;
   });
   gate.countDown();
   assertThat(one.get(20,TimeUnit.SECONDS)).isTrue();
   assertThat(two.get(20,TimeUnit.SECONDS)).isTrue();
  }
  assertThat(jdbc.queryForObject("select count(*) from personal_account a join financial_operation o on o.account_id=a.id"
   +" where a.id=? and a.status='CLOSED' and o.storage_status='PREPARED'",Integer.class,a.id())).isZero();
 }
 @Test void clientCannotSeeDraftOrForeignFinancialData() throws Exception {
  var foreignAccount=account();
  var foreign=posted(draft(foreignAccount.id(),"CHARGE","123",null),UUID.randomUUID());
  UUID ownClient=jdbc.queryForObject("select client_id from app_user where login='demo-client'",UUID.class);
  as("demo-operator");var operator=principal("demo-operator");
  var ownAccount=accounts.open(ownClient,UUID.randomUUID(),today(),
   operator.id(),operator.username(),UUID.randomUUID());
  var prepared=draft(ownAccount.id(),"PAYMENT","12","my-"+UUID.randomUUID());
  mvc.perform(get("/operations/"+prepared.id()).secure(true).with(authentication(auth("demo-client"))))
   .andExpect(status().isForbidden());
  posted(prepared,UUID.randomUUID());
  mvc.perform(get("/operations/"+prepared.id()).secure(true).with(authentication(auth("demo-client"))))
   .andExpect(status().isOk());
  String denied=mvc.perform(get("/operations/"+foreign.id()).secure(true)
   .with(authentication(auth("demo-client")))).andExpect(status().isForbidden())
   .andReturn().getResponse().getContentAsString();
  assertThat(denied).doesNotContain(foreignAccount.accountNumber()).doesNotContain("123.00");
  mvc.perform(get("/operations/"+foreign.id()).secure(true).with(authentication(auth("demo-admin"))))
   .andExpect(status().isForbidden());
 }
}
