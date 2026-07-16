package com.example.agentdemo.workflow.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCredentialCipherTest {

    @Test
    void encryptsCredentialPayloadWithoutPersistingPlaintext() {
        HttpCredentialCipher cipher = new HttpCredentialCipher(new ObjectMapper(),
                "test-http-credential-master-key-at-least-32-bytes");
        Map<String, String> payload = Map.of("token", "bearer-super-secret");

        String encrypted = cipher.encrypt(payload);

        assertThat(encrypted).startsWith("v1:").doesNotContain("bearer-super-secret");
        assertThat(cipher.decrypt(encrypted)).isEqualTo(payload);
    }
}
