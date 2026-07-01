package com.example.agentdemo.tool;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryCacheTest {

    @Test
    void reusesCachedSnapshotUntilTtlExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T12:00:00Z"));
        CountingToolProvider provider = new CountingToolProvider();
        ToolRegistryCache cache = new ToolRegistryCache(List.of(provider), clock, Duration.ofSeconds(30));

        assertThat(cache.list()).extracting(ToolDescriptor::name).containsExactly("cached_tool");
        assertThat(cache.find("cached_tool")).isPresent();
        assertThat(provider.toolsCalls()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(29));
        assertThat(cache.list()).extracting(ToolDescriptor::name).containsExactly("cached_tool");
        assertThat(provider.toolsCalls()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        assertThat(cache.list()).extracting(ToolDescriptor::name).containsExactly("cached_tool");
        assertThat(provider.toolsCalls()).isEqualTo(2);
    }

    @Test
    void buildsToolViewsFromCachedDescriptors() {
        ToolRegistryCache cache = new ToolRegistryCache(List.of(new CountingToolProvider()));

        List<ToolView> views = cache.views(descriptor -> descriptor.name().startsWith("cached"));

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.name()).isEqualTo("cached_tool");
            assertThat(view.executable()).isTrue();
        });
    }

    @Test
    void keepsSameToolNameDescriptorsFromDifferentServersInListsAndViews() {
        ToolRegistryCache cache = new ToolRegistryCache(List.of(
                new DuplicateRemoteToolProvider("github"),
                new DuplicateRemoteToolProvider("slack")));

        assertThat(cache.list())
                .extracting(ToolDescriptor::serverName)
                .containsExactly("github", "slack");

        assertThat(cache.views(descriptor -> true))
                .extracting(ToolView::serverName)
                .containsExactly("github", "slack");
    }

    private static final class CountingToolProvider implements ToolProvider {

        private final AtomicInteger toolsCalls = new AtomicInteger();

        @Override
        public String providerName() {
            return "counting";
        }

        @Override
        public boolean supports(String toolName) {
            return "cached_tool".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            return ToolExecutionLog.success(toolName, arguments, "ok", Instant.now(), Instant.now(),
                    new ToolDescriptor(toolName, "cached", providerName(), false));
        }

        @Override
        public List<ToolDescriptor> tools() {
            toolsCalls.incrementAndGet();
            return List.of(new ToolDescriptor("cached_tool", "cached", providerName(), false));
        }

        int toolsCalls() {
            return toolsCalls.get();
        }

    }

    private static final class DuplicateRemoteToolProvider implements ToolProvider {

        private final String serverName;

        private DuplicateRemoteToolProvider(String serverName) {
            this.serverName = serverName;
        }

        @Override
        public String providerName() {
            return "mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "shared_tool".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            return ToolExecutionLog.success(toolName, arguments, serverName, now, now,
                    tools().getFirst());
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("shared_tool", "shared remote tool", providerName(), true, serverName,
                    "{}"));
        }

    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

    }

}
