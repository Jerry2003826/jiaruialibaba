package com.example.agentdemo.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigOpenAiCompatibleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOpenAiCompatibleRequestAndParsesResponse() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            captureRequest(exchange, requestPath, authorization, requestBody);
            byte[] response = """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "created": 1,
                      "model": "glm-5.2",
                      "choices": [{
                        "index": 0,
                        "message": {"role": "assistant", "content": "ok"},
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.dashscope.api-key", "test-secret")
                .withProperty("spring.ai.dashscope.base-url", baseUrl())
                .withProperty("spring.ai.dashscope.chat.completions-path", "/chat/completions")
                .withProperty("spring.ai.dashscope.chat.options.model", "glm-5.2")
                .withProperty("demo.ai.chat-protocol", "openai-compatible");

        ChatClient chatClient = new AiConfig().chatClient(environment);

        assertThat(chatClient.prompt().user("hi").call().content()).isEqualTo("ok");
        assertThat(requestPath.get()).isEqualTo("/compatible-mode/v1/chat/completions");
        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
        assertThat(requestBody.get().path("model").asText()).isEqualTo("glm-5.2");
        assertThat(requestBody.get().path("messages").isArray()).isTrue();
        assertThat(requestBody.get().has("enable_thinking")).isTrue();
        assertThat(requestBody.get().path("enable_thinking").asBoolean()).isFalse();
        assertThat(requestBody.get().has("input")).isFalse();
    }

    private void captureRequest(HttpExchange exchange, AtomicReference<String> requestPath,
            AtomicReference<String> authorization, AtomicReference<JsonNode> requestBody) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1";
    }
}
