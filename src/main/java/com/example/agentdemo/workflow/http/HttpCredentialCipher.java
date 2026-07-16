package com.example.agentdemo.workflow.http;

import com.example.agentdemo.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@Component
public class HttpCredentialCipher {

    private static final byte[] AAD = "agent-workflow-http-credential-v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final SecretKeySpec key;

    public HttpCredentialCipher(ObjectMapper objectMapper,
            @Value("${demo.workflow.http.credentials-master-key:}") String masterKey) {
        this.objectMapper = objectMapper;
        if (!StringUtils.hasText(masterKey)) {
            throw new IllegalStateException("demo.workflow.http.credentials-master-key must be configured");
        }
        this.key = new SecretKeySpec(sha256(masterKey), "AES");
    }

    public String encrypt(Map<String, String> payload) {
        try {
            byte[] iv = new byte[IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            byte[] plaintext = objectMapper.writeValueAsBytes(payload);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        }
        catch (Exception ex) {
            throw new BusinessException("HTTP_CREDENTIAL_ENCRYPTION_FAILED", "Failed to encrypt HTTP credential", ex);
        }
    }

    public Map<String, String> decrypt(String encryptedPayload) {
        try {
            String[] parts = encryptedPayload == null ? new String[0] : encryptedPayload.split(":", 3);
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException("Unsupported encrypted credential format");
            }
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            byte[] plaintext = cipher.doFinal(ciphertext);
            return objectMapper.readValue(plaintext, new TypeReference<>() { });
        }
        catch (Exception ex) {
            throw new BusinessException("HTTP_CREDENTIAL_DECRYPTION_FAILED", "Failed to decrypt HTTP credential", ex);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
