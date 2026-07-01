package com.example.agentdemo.knowledge;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Knowledge base security: create requires rag.write, search requires rag.query, list requires
 * rag.read; plus a multipart file upload end-to-end over HTTP.
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_kb_sec_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class KnowledgeBaseSecurityTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createRequiresRagWriteScope() throws Exception {
        mockMvc.perform(post("/api/knowledge-bases")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("rag.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"KB\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("rag.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"KB\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void fileUploadAndSearchOverHttp() throws Exception {
        String kbId = createKb();
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "Warranty covers manufacturing defects for one year.".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/documents/files").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("rag.write"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexStatus").value("READY"));

        mockMvc.perform(post("/api/knowledge-bases/" + kbId + "/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("rag.query")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"warranty defects\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations[0].documentId").isNumber());

        // search requires rag.query, not rag.read
        mockMvc.perform(post("/api/knowledge-bases/" + kbId + "/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("rag.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"warranty\"}"))
                .andExpect(status().isForbidden());
    }

    private String createKb() throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("rag.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"KB\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int idx = body.indexOf("\"kbId\":\"");
        int start = idx + "\"kbId\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private String bearer(List<String> scopes) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-user")
                .issuer("agent-backend-demo")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", String.join(" ", scopes))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return "Bearer " + jwt.serialize();
    }

}
