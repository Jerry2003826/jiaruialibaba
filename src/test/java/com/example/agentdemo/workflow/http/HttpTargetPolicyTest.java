package com.example.agentdemo.workflow.http;

import com.example.agentdemo.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTargetPolicyTest {

    @Test
    void blocksPrivateMetadataAndUnsupportedTargets() throws Exception {
        WorkflowHttpProperties properties = new WorkflowHttpProperties();
        HttpTargetPolicy policy = new HttpTargetPolicy(properties, host -> switch (host) {
            case "private.test" -> new InetAddress[] { InetAddress.getByName("10.0.0.8") };
            case "metadata.test" -> new InetAddress[] { InetAddress.getByName("169.254.169.254") };
            default -> new InetAddress[] { InetAddress.getByName("93.184.216.34") };
        });

        assertBlocked(policy, "http://localhost/test");
        assertBlocked(policy, "http://private.test/test");
        assertBlocked(policy, "http://metadata.test/latest/meta-data");
        assertBlocked(policy, "file:///etc/passwd");
        assertBlocked(policy, "http://user:password@example.test/path");
    }

    @Test
    void allowsPublicTargetsAndExplicitInternalHostAllowlist() throws Exception {
        WorkflowHttpProperties properties = new WorkflowHttpProperties();
        properties.setAllowedHosts(List.of("internal.example", "*.trusted.local"));
        HttpTargetPolicy policy = new HttpTargetPolicy(properties, host -> switch (host) {
            case "internal.example", "api.trusted.local" ->
                    new InetAddress[] { InetAddress.getByName("192.168.1.10") };
            default -> new InetAddress[] { InetAddress.getByName("93.184.216.34") };
        });

        assertThatCode(() -> policy.validate(URI.create("https://public.example/path"))).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate(URI.create("https://internal.example/path"))).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate(URI.create("https://api.trusted.local/path"))).doesNotThrowAnyException();
    }

    private void assertBlocked(HttpTargetPolicy policy, String target) {
        assertThatThrownBy(() -> policy.validate(URI.create(target)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode())
                                .isEqualTo("WORKFLOW_HTTP_TARGET_BLOCKED"));
    }
}
