package com.example.agentdemo.workflow.http;

import com.example.agentdemo.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/settings/http-credentials")
public class HttpCredentialController {

    private final HttpCredentialService service;

    public HttpCredentialController(HttpCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<HttpCredentialResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<HttpCredentialResponse> create(@Valid @RequestBody HttpCredentialRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PatchMapping("/{credentialId}")
    public ApiResponse<HttpCredentialResponse> update(@PathVariable String credentialId,
            @Valid @RequestBody HttpCredentialRequest request) {
        return ApiResponse.ok(service.update(credentialId, request));
    }

    @DeleteMapping("/{credentialId}")
    public ApiResponse<Void> delete(@PathVariable String credentialId) {
        service.delete(credentialId);
        return ApiResponse.ok(null);
    }
}
