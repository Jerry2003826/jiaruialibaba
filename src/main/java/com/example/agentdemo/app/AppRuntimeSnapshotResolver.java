package com.example.agentdemo.app;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.JsonPayloadCodec;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AppRuntimeSnapshotResolver {

    private final AppRepository appRepository;
    private final AppRevisionRepository appRevisionRepository;
    private final AppProperties appProperties;
    private final JsonPayloadCodec jsonPayloadCodec;

    public AppRuntimeSnapshotResolver(AppRepository appRepository, AppRevisionRepository appRevisionRepository,
            AppProperties appProperties, JsonPayloadCodec jsonPayloadCodec) {
        this.appRepository = appRepository;
        this.appRevisionRepository = appRevisionRepository;
        this.appProperties = appProperties;
        this.jsonPayloadCodec = jsonPayloadCodec;
    }

    public AppSnapshot resolve(String appId) {
        AppEntity app = appRepository.findByAppIdAndOwnerId(appId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("APP_NOT_FOUND", "App not found: " + appId));
        if (app.getStatus() == AppStatus.ARCHIVED) {
            throw new BusinessException("APP_ARCHIVED", "App is archived and cannot be invoked: " + appId);
        }
        if (appProperties.isRequirePublishedForRun()) {
            if (app.getPublishedVersion() == null) {
                throw new BusinessException("APP_NOT_PUBLISHED",
                        "App must be published before it can be invoked: " + appId);
            }
            return snapshotForVersion(appId, app.getPublishedVersion());
        }
        if (app.getPublishedVersion() != null) {
            return snapshotForVersion(appId, app.getPublishedVersion());
        }
        return currentDraftSnapshot(app);
    }

    private AppSnapshot snapshotForVersion(String appId, Integer version) {
        return fromJson(appRevisionRepository
                .findByAppIdAndVersionAndOwnerId(appId, version, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("APP_REVISION_NOT_FOUND",
                        "App revision not found: " + appId + ":" + version))
                .getSnapshotJson());
    }

    private AppSnapshot currentDraftSnapshot(AppEntity app) {
        return new AppSnapshot(app.getName(), app.getDescription(), app.getType(),
                configFromJson(app.getConfigJson()), app.getWorkflowDefinitionId(),
                app.getWorkflowDefinitionVersion());
    }

    private AppConfig configFromJson(String configJson) {
        if (!StringUtils.hasText(configJson)) {
            return AppConfig.empty();
        }
        return jsonPayloadCodec.read(configJson, AppConfig.class, "APP_CONFIG_DESERIALIZATION_FAILED",
                "Failed to read app config");
    }

    private AppSnapshot fromJson(String snapshotJson) {
        return jsonPayloadCodec.read(snapshotJson, AppSnapshot.class, "APP_SNAPSHOT_DESERIALIZATION_FAILED",
                "Failed to read app snapshot");
    }

}
