package ru.tsu.accounts.statement;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditWriter;

class ExportIT extends StatementIntegrationSupport {
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DocumentExportService exports;
    @SpyBean AuditWriter auditWriter;
    @SpyBean DocumentAuditService documentAudit;

    private String range(LocalDate from,LocalDate to) { return "?from="+from+"&to="+to; }
    private MvcResult document(String path,String login) throws Exception {
        return mvc.perform(get(path).secure(true).with(authentication(auth(login))))
            .andExpect(status().isOk()).andReturn();
    }
    private String html(MvcResult result) throws Exception { return result.getResponse().getContentAsString(StandardCharsets.UTF_8); }

    @Test void htmlPrintCsvAndJsonContainTheSameAccountPeriodOperationsAndTotals() throws Exception {
        var f=control();String period=range(today(),today());String base="/accounts/"+f.account().id();
        var html=document(base+"/statement/result"+period,"demo-client");
        var print=document(base+"/statement/print"+period,"demo-client");
        var csv=document(base+"/statement.csv"+period,"demo-client");
        var api=document("/api/v1/accounts/"+f.account().id()+"/statement"+period,"demo-client");
        JsonNode dto=json.readTree(api.getResponse().getContentAsByteArray());
        assertThat(dto.path("accountId").asText()).isEqualTo(f.account().id().toString());
        assertThat(dto.path("formatVersion").asText()).isEqualTo("1");
        assertThat(dto.path("from").asText()).isEqualTo(today().toString());
        assertThat(dto.path("generatedAt").asText()).matches(".*T.*Z");
        for(String[] expected:new String[][]{{"openingBalance","1000.00","opening-balance"},{"increase","50.00","increase"},
            {"decrease","500.00","decrease"},{"closingBalance","550.00","closing-balance"}}) {
            assertThat(dto.path(expected[0]).isTextual()).isTrue();
            assertThat(dto.path(expected[0]).asText()).isEqualTo(expected[1]);
            String display=String.format(Locale.US,"%,.2f",new BigDecimal(expected[1]))
                .replace(',',' ').replace('.',',')+" ₽";
            String amount="id=\""+expected[2]+"\"[^>]*data-value=\""+Pattern.quote(expected[1])
                +"\"[^>]*>\\s*"+Pattern.quote(display)+"\\s*</div>";
            assertThat(html(html)).containsPattern(amount);
            assertThat(html(print)).containsPattern(amount);
        }
        assertThat(html(print)).contains("/css/print.css").contains("/js/app.js");
        assertThat(dto.toString()).doesNotContain("password","SecurityContext","authVersion","idempotencyKey","requestFingerprint");
        List<Map<String,String>> records=csv(csv.getResponse().getContentAsByteArray());
        assertThat(records).extracting(r->r.get("record_type"))
            .containsExactly("HEADER","OPENING","OPERATION","OPERATION","OPERATION","TOTAL");
        var header=records.getFirst();var total=records.getLast();
        assertThat(header.get("account_id")).isEqualTo(dto.path("accountId").asText());
        assertThat(header.get("account_number")).isEqualTo(dto.path("accountNumber").asText());
        assertThat(header.get("owner_name")).isEqualTo(dto.path("ownerName").asText());
        assertThat(header.get("from")).isEqualTo(dto.path("from").asText());
        for(String[] mapping:new String[][]{{"opening_balance","openingBalance"},{"increase","increase"},
            {"decrease","decrease"},{"closing_balance","closingBalance"}})
            assertThat(total.get(mapping[0])).isEqualTo(dto.path(mapping[1]).asText());
        var rows=records.stream().filter(r->r.get("record_type").equals("OPERATION")).toList();
        assertThat(rows).hasSize(dto.path("operations").size());
        for(int index=0;index<rows.size();index++) {
            var row=rows.get(index);var operation=dto.path("operations").get(index);
            assertThat(row.get("operation_id")).isEqualTo(operation.path("id").asText());
            assertThat(row.get("operation_date")).isEqualTo(operation.path("date").asText());
            assertThat(row.get("delta")).isEqualTo(operation.path("delta").asText());
            assertThat(row.get("amount")).isEqualTo(operation.path("amount").asText());
            assertThat(row.get("balance_after")).isEqualTo(operation.path("balanceAfter").asText());
            assertThat(row.get("original_operation_id")).isEqualTo(operation.path("originalOperationId").isNull()?"":operation.path("originalOperationId").asText());
            assertThat(html(html)).contains("data-operation-id=\""+row.get("operation_id")+"\"");
            assertThat(html(print)).contains("data-operation-id=\""+row.get("operation_id")+"\"");
        }
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".csv");
        for(var result:List.of(html,print,csv,api))
            assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test void csvRoundTripsQuotedMultilineFieldsAndNeutralizesAllFormulaPrefixes() throws Exception {
        var a=foreign();
        String owner="\u00a0\u200b=HYPERLINK(\"x\";\"y\")";
        jdbc.update("update client set display_name=? where id=?",owner,a.clientId());
        List<String> bases=List.of("=SUM(1;2)\r\n\"кавычки\"", "+cmd;\"quoted\"\nnext",
            "-2+3;строка\rnext", "\t @formula;\"text\"\r\nlast");
        var ids=new ArrayList<UUID>();var documents=new LinkedHashMap<UUID,String>();
        for(String basis:bases) {
            String doc="Документ;\"номер\"\r\n"+UUID.randomUUID();
            UUID id=rawCharge(a.id(),basis,doc);ids.add(id);documents.put(id,doc);
        }
        var response=document("/accounts/"+a.id()+"/statement.csv"+range(today(),today()),"demo-accountant");
        byte[] bytes=response.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte)0xef,(byte)0xbb,(byte)0xbf);
        var records=csv(bytes);
        assertThat(records.getFirst().get("owner_name")).isEqualTo("'"+owner);
        var rows=records.stream().filter(r->r.get("record_type").equals("OPERATION")).toList();
        assertThat(rows).hasSize(4);
        for(int i=0;i<4;i++) {
            assertThat(rows.get(i).get("operation_id")).isEqualTo(ids.get(i).toString());
            assertThat(rows.get(i).get("basis")).isEqualTo("'"+bases.get(i));
            assertThat(rows.get(i).get("document_number")).isEqualTo(documents.get(ids.get(i)));
            assertThat(rows.get(i).get("delta")).isEqualTo("1.25");
        }
        // Existing monetary input rules reject controls. This isolated database fixture
        // exercises exporter safety for legacy/imported text allowed by the SQL schema.
        assertThat(new String(bytes,StandardCharsets.UTF_8)).contains("\"\"кавычки\"\"").contains("\r\n");
        as("demo-accountant");
        var exported=exports.createCsv(a.id(),today().toString(),today().toString());
        var path=exported.path();assertThat(Files.exists(path)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        exported.close();assertThat(Files.exists(path)).isFalse();
    }

    @Test void clientOwnsEveryFormatAndForeignUuidIsDeniedBeforeAnyClientData() throws Exception {
        var own=openOwn();var other=foreign();posted(other.id(),"CHARGE","987.65",today());
        String suffix=range(today(),today());
        for(String ending:List.of("/statement","/statement/result","/statement/print","/statement.csv")) {
            document("/accounts/"+own.id()+ending+suffix,"demo-client");
            var denied=mvc.perform(get("/accounts/"+other.id()+ending+suffix).secure(true)
                .with(authentication(auth("demo-client"))))
                .andExpect(status().isForbidden()).andReturn().getResponse();
            assertThat(denied.getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(other.accountNumber(),"Секретное имя чужого клиента E8","987.65");
            assertThat(denied.getHeader("Content-Disposition")).isNull();
        }
        document("/api/v1/accounts/"+own.id()+"/statement"+suffix,"demo-client");
        mvc.perform(get("/api/v1/accounts/"+other.id()+"/statement"+suffix).secure(true)
            .with(authentication(auth("demo-client"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_OWNED"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(other.accountNumber()))));
        // Access checking must also precede validation when the forged URL has bad dates.
        mvc.perform(get("/api/v1/accounts/"+other.id()+"/statement?from=bad&to=bad").secure(true)
            .with(authentication(auth("demo-client")))).andExpect(status().isForbidden());
    }

    @Test void everyPermittedRoleCanReadClosedStatementsWithoutGainingFinancialCommands() throws Exception {
        var a=openOwn();as("demo-operator");var actor=principal("demo-operator");
        accounts.changeStatus(a.id(),a.version(),"CLOSED","Проверка доступа к архивной выписке",
            actor.id(),actor.username(),UUID.randomUUID());
        String suffix=range(today(),today());
        for(String role:List.of("admin","operator","accountant","manager","client")) {
            String login="demo-"+role;
            for(String ending:List.of("/statement","/statement/result","/statement/print","/statement.csv"))
                document("/accounts/"+a.id()+ending+suffix,login);
            mvc.perform(get("/api/v1/accounts/"+a.id()+"/statement"+suffix).secure(true)
                .with(authentication(auth(login)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("CLOSED"))
                .andExpect(jsonPath("$.closingBalance").value("0.00"));
        }
        mvc.perform(get("/accounts/"+a.id()+"/operations/new").secure(true)
            .with(authentication(auth("demo-admin")))).andExpect(status().isForbidden());
        mvc.perform(get("/accounts/"+a.id()+"/operations/new").secure(true)
            .with(authentication(auth("demo-manager")))).andExpect(status().isForbidden());
    }

    @Test void invalidMissingAndOverlongPeriodsAre400AndAnonymousApiIs401() throws Exception {
        var a=openOwn();String base="/accounts/"+a.id();
        for(String query:List.of("", "?from="+today(), "?to="+today(),
            "?from=2026-02-30&to="+today(), range(today(),today().minusDays(1)),
            range(today().minusDays(366),today()),range(a.historyStartOn().minusDays(1),a.historyStartOn()))) {
            for(String path:List.of(base+"/statement/result",base+"/statement/print",base+"/statement.csv",
                "/api/v1/accounts/"+a.id()+"/statement")) {
                var result=mvc.perform(get(path+query).secure(true).with(authentication(auth("demo-client"))))
                    .andExpect(status().isBadRequest()).andReturn().getResponse();
                assertThat(result.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SELECT ","SQLException","stackTrace");
                if(path.startsWith("/api/")) {
                    var problem=json.readTree(result.getContentAsByteArray());
                    assertThat(problem.path("code").asText()).isNotBlank();
                    assertThat(problem.path("requestId").asText()).isNotBlank();
                }
            }
        }
        mvc.perform(get("/api/v1/accounts/"+a.id()+"/statement"+range(today(),today())).secure(true)
            .with(anonymous())).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/v1/accounts/not-a-uuid/statement"+range(today(),today())).secure(true)
            .with(authentication(auth("demo-client")))).andExpect(status().isBadRequest());
    }

    @Test void documentAuditUsesItsOwnWriteTransactionAndFailureReturns503BeforeDocumentBytes() throws Exception {
        var f=control();String suffix=range(today(),today());var audited=new AtomicInteger();
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(documentAudit).record(any(Statement.class),any(String.class),any(UUID.class));
        doAnswer(call->{
            AuditEntry entry=call.getArgument(0);
            if("STATEMENT_GENERATED".equals(entry.action())) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("off");
                audited.incrementAndGet();
            }
            return call.callRealMethod();
        }).when(auditWriter).insert(any(AuditEntry.class));
        var paths=List.of("/accounts/"+f.account().id()+"/statement/result",
            "/accounts/"+f.account().id()+"/statement/print","/accounts/"+f.account().id()+"/statement.csv",
            "/api/v1/accounts/"+f.account().id()+"/statement");
        for(String path:paths)document(path+suffix,"demo-client");
        assertThat(audited.get()).isEqualTo(4);
        assertThat(jdbc.queryForList("select details->>'format' from audit_event where action='STATEMENT_GENERATED' and object_id=?",
            String.class,f.account().id().toString())).containsExactlyInAnyOrder("HTML","PRINT","CSV","JSON");
        doAnswer(call->{
            AuditEntry entry=call.getArgument(0);
            if("STATEMENT_GENERATED".equals(entry.action()))throw new DataAccessResourceFailureException("simulated document audit outage");
            return call.callRealMethod();
        }).when(auditWriter).insert(any(AuditEntry.class));
        for(String path:paths) {
            var response=mvc.perform(get(path+suffix).secure(true).with(authentication(auth("demo-client"))))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
            assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("\ufeff","record_type","550.00",f.account().accountNumber());
            assertThat(response.getHeader("Content-Disposition")).isNull();
        }
        assertThat(jdbc.queryForObject("select current_balance from personal_account where id=?",BigDecimal.class,f.account().id()))
            .isEqualByComparingTo("550.00");
    }

    @Test void csvAndCursorIncludeAll300RowsBeyondTheJdbcFetchWindow() throws Exception {
        var a=openOwn();UUID actor=principal("demo-accountant").id();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            jdbc.update("""
                insert into financial_operation(id,account_id,kind,storage_status,operation_date,amount,
                  effect_sign,period_start,basis,created_by)
                select gen_random_uuid(),?,'CHARGE','PREPARED',?,0.01,1,?,'Строка полного CSV',?
                from generate_series(1,300)
                """,a.id(),today(),today().withDayOfMonth(1),actor);
            jdbc.update("""
                update financial_operation set storage_status='POSTED',posted_by=?,posted_at=current_timestamp,
                  idempotency_key=gen_random_uuid(),request_fingerprint=repeat('0',64) where account_id=?
                """,actor,a.id());
            jdbc.update("update personal_account set current_balance=3.00 where id=?",a.id());
        });
        as("demo-client");var seen=new HashSet<UUID>();
        var metadata=statements.scan(a.id(),today().toString(),today().toString(),new StatementService.StatementSink(){
            @Override public void line(StatementLine line){assertThat(seen.add(line.id())).isTrue();}
        });
        assertThat(seen).hasSize(300);assertThat(metadata.closingBalance()).isEqualByComparingTo("3.00");
        var result=document("/accounts/"+a.id()+"/statement.csv"+range(today(),today()),"demo-client");
        var records=csv(result.getResponse().getContentAsByteArray());
        assertThat(records.stream().filter(r->r.get("record_type").equals("OPERATION")))
            .hasSize(300).extracting(r->UUID.fromString(r.get("operation_id"))).containsExactlyInAnyOrderElementsOf(seen);
        assertThat(records.getLast().get("closing_balance")).isEqualTo("3.00");
    }

    private UUID rawCharge(UUID account,String basis,String document) {
        UUID id=UUID.randomUUID();UUID actor=principal("demo-accountant").id();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            jdbc.update("""
                insert into financial_operation(id,account_id,kind,storage_status,operation_date,amount,effect_sign,
                  period_start,basis,external_source,external_number,external_number_key,created_by)
                values(?,?,'CHARGE','PREPARED',?,1.25,1,?,?,'MANUAL',?,?,?)
                """,id,account,today(),today().withDayOfMonth(1),basis,document,document.toLowerCase(Locale.ROOT),actor);
            jdbc.update("""
                update financial_operation set storage_status='POSTED',posted_by=?,posted_at=current_timestamp,
                  idempotency_key=?,request_fingerprint=repeat('0',64) where id=?
                """,actor,UUID.randomUUID(),id);
            jdbc.update("update personal_account set current_balance=current_balance+1.25 where id=?",account);
        });
        return id;
    }

    /** Independent CSV reader: handles quoted delimiters, escaped quotes and embedded CR/LF. */
    private static List<Map<String,String>> csv(byte[] bytes) {
        String text=new String(bytes,StandardCharsets.UTF_8);
        assertThat(text).startsWith("\ufeff");text=text.substring(1);
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();
        boolean quoted=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) {
                if(c=='"' && i+1<text.length() && text.charAt(i+1)=='"'){cell.append('"');i++;}
                else if(c=='"')quoted=false;
                else cell.append(c);
            } else if(c=='"') {assertThat(cell).isEmpty();quoted=true;}
            else if(c==';') {row.add(cell.toString());cell.setLength(0);}
            else if(c=='\r'||c=='\n') {
                if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;
                row.add(cell.toString());cell.setLength(0);rows.add(row);row=new ArrayList<>();
            } else cell.append(c);
        }
        assertThat(quoted).isFalse();assertThat(cell).isEmpty();assertThat(row).isEmpty();
        var names=rows.removeFirst();assertThat(names).hasSize(31);
        var result=new ArrayList<Map<String,String>>();
        for(var values:rows) {
            assertThat(values).hasSameSizeAs(names);var record=new LinkedHashMap<String,String>();
            for(int index=0;index<names.size();index++)record.put(names.get(index),values.get(index));
            result.add(record);
        }
        return result;
    }
}
