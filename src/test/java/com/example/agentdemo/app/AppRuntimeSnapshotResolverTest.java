package com.example.agentdemo.app;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.JsonPayloadCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppRuntimeSnapshotResolverTest {

    private final AppRepository appRepository = mock(AppRepository.class);
    private final AppRevisionRepository appRevisionRepository = mock(AppRevisionRepository.class);
    private final AppProperties appProperties = new AppProperties();
    private final AppRuntimeSnapshotResolver resolver = new AppRuntimeSnapshotResolver(appRepository,
            appRevisionRepository, appProperties, new JsonPayloadCodec(new ObjectMapper()));

    @Test
    void resolvesPublishedSnapshotWhenPublishedRunsAreRequired() {
        AppEntity app = new AppEntity("app-1", "Draft", null, AppType.CHAT, "{}", null, null);
        app.publish(null);
        String snapshotJson = """
                {"name":"Published","type":"CHAT","config":{"systemPrompt":"published"}}
                """;
        when(appRepository.findByAppIdAndOwnerId("app-1", "workbench-dev")).thenReturn(Optional.of(app));
        when(appRevisionRepository.findByAppIdAndVersionAndOwnerId("app-1", 1, "workbench-dev"))
                .thenReturn(Optional.of(new AppRevisionEntity("app-1", 1, AppStatus.PUBLISHED, snapshotJson)));

        AppSnapshot snapshot = resolver.resolve("app-1");

        assertThat(snapshot.name()).isEqualTo("Published");
        assertThat(snapshot.config().systemPrompt()).isEqualTo("published");
    }

    @Test
    void resolvesDraftSnapshotWhenPublishedRunsAreNotRequiredAndNoPublishedVersionExists() {
        appProperties.setRequirePublishedForRun(false);
        AppEntity app = new AppEntity("app-1", "Draft", null, AppType.CHAT,
                "{\"systemPrompt\":\"draft\"}", null, null);
        when(appRepository.findByAppIdAndOwnerId("app-1", "workbench-dev")).thenReturn(Optional.of(app));

        AppSnapshot snapshot = resolver.resolve("app-1");

        assertThat(snapshot.name()).isEqualTo("Draft");
        assertThat(snapshot.config().systemPrompt()).isEqualTo("draft");
        verifyNoInteractions(appRevisionRepository);
    }

    @Test
    void rejectsArchivedApps() {
        AppEntity app = new AppEntity("app-1", "Archived", null, AppType.CHAT, "{}", null, null);
        app.archive();
        when(appRepository.findByAppIdAndOwnerId(anyString(), anyString())).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> resolver.resolve("app-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("APP_ARCHIVED"));
    }

}
