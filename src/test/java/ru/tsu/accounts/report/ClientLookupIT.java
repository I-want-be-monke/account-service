package ru.tsu.accounts.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ClientLookupIT extends ReportIntegrationSupport {
    @Autowired ObjectMapper json;

    @Test
    void staffSearchIsBoundedOrderedAndProjectsOnlyPickerFields() throws Exception {
        String prefix = "Lookup " + UUID.randomUUID();
        for (int index = 0; index < 25; index++) {
            insertClient(prefix + String.format(" %02d", index));
        }
        long operationsBefore = operationCount();
        for (String login : List.of("demo-admin", "demo-operator", "demo-accountant", "demo-manager")) {
            var response = mvc.perform(get("/ui/clients/search").secure(true)
                    .with(authentication(auth(login))).param("q", prefix))
                .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            var options = json.readTree(response.getContentAsByteArray());
            assertThat(options.size()).isEqualTo(20);
            for (int index = 0; index < options.size(); index++) {
                var option = options.get(index);
                var fields = new ArrayList<String>();
                option.fieldNames().forEachRemaining(fields::add);
                assertThat(fields).containsExactlyInAnyOrder("id", "name", "identifier");
                assertThat(UUID.fromString(option.path("id").asText())).isNotNull();
                assertThat(option.path("name").asText()).isEqualTo(prefix + String.format(" %02d", index));
            }
            assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("private-lookup@example.invalid", "+79990001122");
        }
        assertThat(operationCount()).isEqualTo(operationsBefore);
    }

    @Test
    void clientAndAnonymousCannotReadLookupAndBlankQueryReturnsNoDirectory() throws Exception {
        mvc.perform(get("/ui/clients/search").secure(true).param("q", "E9")
                .with(authentication(auth("demo-client"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/ui/clients/search").secure(true).param("q", "E9"))
            .andExpect(status().is3xxRedirection());
        var empty = mvc.perform(get("/ui/clients/search").secure(true)
                .with(authentication(auth("demo-manager"))))
            .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(empty.getResponse().getContentAsByteArray()).size()).isZero();
    }

    @Test
    void fallbackEscapesClientNamesAndSelectedNameSurvivesStrictReportParsing() throws Exception {
        String prefix = "Lookup " + UUID.randomUUID();
        String name = prefix + " <img src=x onerror=alert(1)> & \"Клиент\"";
        UUID id = insertClient(name);
        var search = mvc.perform(get("/reports").secure(true).with(authentication(auth("demo-manager")))
                .param("type", "DEBT").param("asOf", "2026-10-31")
                .param("clientQuery", prefix).param("searchClients", "1"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(search).contains("&lt;img", "name=\"clientId\"", id.toString()).doesNotContain("<img src=x");
        var result = mvc.perform(get("/reports/result").secure(true).with(authentication(auth("demo-manager")))
                .param("type", "DEBT").param("asOf", "2026-10-31")
                .param("clientId", id.toString()).param("clientQuery", name))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result).contains("&lt;img").doesNotContain("<img src=x", "UUID клиента");
        mvc.perform(get("/reports/result").secure(true).with(authentication(auth("demo-manager")))
                .param("type", "DEBT").param("asOf", "2026-10-31").param("clientQuery", name))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/reports/result").secure(true).with(authentication(auth("demo-manager")))
                .param("type", "DEBT").param("asOf", "2026-10-31")
                .param("clientId", id.toString()).param("clientQuery", "Другое имя"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/reports/result").secure(true).with(authentication(auth("demo-manager")))
                .param("type", "DEBT").param("asOf", "2026-10-31").param("unknown", "1"))
            .andExpect(status().isBadRequest());
    }

    private UUID insertClient(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO client(id, client_type, display_name, identifier_type, identifier_value, identifier_key, phone, email) "
            + "VALUES (?, 'PERSON', ?, 'DEMO', ?, ?, '+79990001122', 'private-lookup@example.invalid')",
            id, name, id.toString(), id.toString());
        return id;
    }
}
