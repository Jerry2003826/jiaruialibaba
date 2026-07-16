package com.example.agentdemo.workflow.http;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.chat.AiModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:http_credentials_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.workflow.http.credentials-master-key=test-http-credential-master-key-at-least-32-bytes"
})
@AutoConfigureMockMvc
class HttpCredentialIntegrationTest {

    private static final String JWT_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AiModelService aiModelService;

    @Test
    void createsListsAndOwnerScopesEncryptedCredentialsWithoutReturningSecrets() throws Exception {
        String secret = "bearer-api-secret-value";
        String createdBody = mockMvc.perform(post("/api/settings/http-credentials")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner-a", "workflow.edit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"订单 API","type":"bearer","token":"%s"}
                                """.formatted(secret)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(createdBody).path("data");
        assertThat(created.path("credentialId").asText()).startsWith("cred_");
        assertThat(createdBody).doesNotContain(secret);
        String encrypted = jdbcTemplate.queryForObject(
                "select encrypted_payload from workflow_http_credentials where credential_id = ?",
                String.class, created.path("credentialId").asText());
        assertThat(encrypted).startsWith("v1:").doesNotContain(secret);

        String ownList = mockMvc.perform(get("/api/settings/http-credentials")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner-a", "workflow.read")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ownList).contains("订单 API").doesNotContain(secret);

        String otherList = mockMvc.perform(get("/api/settings/http-credentials")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner-b", "workflow.read")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(otherList).doesNotContain("订单 API").doesNotContain(secret);
    }

    private String bearer(String ownerId, String scope) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(ownerId)
                .issuer("agent-backend-demo")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", scope)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return "Bearer " + jwt.serialize();
    }
}
