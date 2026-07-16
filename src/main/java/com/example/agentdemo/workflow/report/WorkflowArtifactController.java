package com.example.agentdemo.workflow.report;

import com.example.agentdemo.common.ApiResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class WorkflowArtifactController {

    private final WorkflowArtifactAccessService accessService;

    public WorkflowArtifactController(WorkflowArtifactAccessService accessService) {
        this.accessService = accessService;
    }

    @GetMapping("/api/workflow-artifacts/{artifactId}/content")
    public ResponseEntity<FileSystemResource> content(@PathVariable String artifactId,
            Authentication authentication) {
        WorkflowArtifactDownload download = accessService.open(artifactId, authentication);
        ContentDisposition disposition = (download.inline() ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.sizeBytes())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(download.path()));
    }

    @GetMapping("/api/workflows/runs/{runId}/artifacts")
    public ApiResponse<List<WorkflowArtifactGroupResponse>> listRunArtifacts(@PathVariable String runId,
            Authentication authentication) {
        return ApiResponse.ok(accessService.listRunArtifacts(runId, authentication));
    }
}
