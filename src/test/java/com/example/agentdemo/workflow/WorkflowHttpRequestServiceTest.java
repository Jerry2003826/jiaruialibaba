package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.workflow.http.HttpCredentialService;
import com.example.agentdemo.workflow.http.HttpResolvedCredential;
import com.example.agentdemo.workflow.http.HttpTargetPolicy;
import com.example.agentdemo.workflow.http.WorkflowHttpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowHttpRequestServiceTest {

    @Test
    void injectsOwnerCredentialRendersJsonAndReturnsStructured4xx() throws Exception {
        HttpCredentialService credentials = mock(HttpCredentialService.class);
        when(credentials.resolveForOwner("cred_orders", "owner-a"))
                .thenReturn(new HttpResolvedCredential("cred_orders", "bearer", Map.of("token", "secret-token")));
        HttpTargetPolicy policy = mock(HttpTargetPolicy.class);
        WorkflowHttpProperties properties = new WorkflowHttpProperties();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = response(422,
                "{\"error\":\"invalid order\"}", "application/json; charset=UTF-8");
        doReturn(response).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        WorkflowHttpRequestService service = service(credentials, policy, properties, client);

        WorkflowNode node = new WorkflowNode("http_orders", "http_request", Map.of(
                "method", "POST",
                "url", "https://api.example.com/orders",
                "authorization", Map.of("type", "credential", "credentialId", "cred_orders"),
                "body", Map.of("type", "json", "value", Map.of(
                        "message", "{{input.message}}",
                        "metadata", Map.of("count", "{{input.count}}"))),
                "timeoutMs", 5000));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello", "count", 2), "owner-a");

        Map<String, Object> output = service.execute(node, state);

        assertThat(output)
                .containsEntry("statusCode", 422)
                .containsEntry("body", "{\"error\":\"invalid order\"}")
                .containsEntry("succeeded", false);
        assertThat(output.get("json")).isEqualTo(Map.of("error", "invalid order"));
        verify(policy).validate(URI.create("https://api.example.com/orders"));
        verify(credentials).resolveForOwner("cred_orders", "owner-a");

        org.mockito.ArgumentCaptor<HttpRequest> requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer secret-token");
        assertThat(new ObjectMapper().readTree(readRequestBody(request)))
                .isEqualTo(new ObjectMapper().readTree("{\"message\":\"hello\",\"metadata\":{\"count\":2}}"));
    }

    @Test
    void rejectsResponsesOverConfiguredLimit() throws Exception {
        HttpCredentialService credentials = mock(HttpCredentialService.class);
        HttpTargetPolicy policy = mock(HttpTargetPolicy.class);
        WorkflowHttpProperties properties = new WorkflowHttpProperties();
        properties.setMaxResponseBytes(4);
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200, "12345", "text/plain")).when(client)
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        WorkflowHttpRequestService service = service(credentials, policy, properties, client);

        WorkflowNode node = new WorkflowNode("http_small", "http_request", Map.of(
                "method", "GET", "url", "https://api.example.com/data"));

        assertThatThrownBy(() -> service.execute(node,
                new WorkflowExecutionState(Map.of("message", "hello"), "owner-a")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_HTTP_RESPONSE_TOO_LARGE"));
    }

    private WorkflowHttpRequestService service(HttpCredentialService credentials, HttpTargetPolicy policy,
            WorkflowHttpProperties properties, HttpClient client) {
        return new WorkflowHttpRequestService(new WorkflowVariableResolver(), credentials, policy, properties,
                new ObjectMapper(), client);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<java.io.InputStream> response(int status, String body, String contentType) {
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of(contentType)),
                (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private String readRequestBody(HttpRequest request) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.get();
        return output.toString(StandardCharsets.UTF_8);
    }
}
