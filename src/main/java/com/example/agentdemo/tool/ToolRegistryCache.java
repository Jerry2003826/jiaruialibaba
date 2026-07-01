package com.example.agentdemo.tool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicReference;

public class ToolRegistryCache {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final List<ToolProvider> providers;
    private final Clock clock;
    private final Duration ttl;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public ToolRegistryCache(List<ToolProvider> providers) {
        this(providers, Clock.systemUTC(), DEFAULT_TTL);
    }

    ToolRegistryCache(List<ToolProvider> providers, Clock clock, Duration ttl) {
        this.providers = List.copyOf(providers);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ttl = ttl == null || ttl.isNegative() ? DEFAULT_TTL : ttl;
    }

    public Optional<ToolDescriptor> find(String toolName) {
        return Optional.ofNullable(snapshot().descriptorsByName().get(toolName));
    }

    public List<ToolDescriptor> list() {
        return snapshot().descriptors();
    }

    public List<ToolView> views(Predicate<ToolDescriptor> executableChecker) {
        Predicate<ToolDescriptor> checker = executableChecker == null ? descriptor -> true : executableChecker;
        return list().stream()
                .map(descriptor -> new ToolView(descriptor.name(), descriptor.description(), descriptor.provider(),
                        descriptor.remote(), descriptor.serverName(), descriptor.inputSchema(),
                        checker.test(descriptor)))
                .toList();
    }

    Optional<ToolProvider> findProvider(String toolName) {
        ToolProvider provider = snapshot().providersByName().get(toolName);
        if (provider != null) {
            return Optional.of(provider);
        }
        return providers.stream()
                .filter(candidate -> candidate.supports(toolName))
                .findFirst();
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot.get();
        Instant now = clock.instant();
        if (current != null && !current.isExpired(now)) {
            return current;
        }
        synchronized (this) {
            current = snapshot.get();
            now = clock.instant();
            if (current != null && !current.isExpired(now)) {
                return current;
            }
            Snapshot refreshed = refresh(now);
            snapshot.set(refreshed);
            return refreshed;
        }
    }

    private Snapshot refresh(Instant now) {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        Map<String, ToolDescriptor> descriptorsByName = new LinkedHashMap<>();
        Map<String, ToolProvider> providersByName = new LinkedHashMap<>();
        for (ToolProvider provider : providers) {
            for (ToolDescriptor descriptor : provider.tools()) {
                descriptors.add(descriptor);
                descriptorsByName.putIfAbsent(descriptor.name(), descriptor);
                providersByName.putIfAbsent(descriptor.name(), provider);
            }
        }
        return new Snapshot(List.copyOf(descriptors), Map.copyOf(descriptorsByName), Map.copyOf(providersByName),
                now.plus(ttl));
    }

    private record Snapshot(List<ToolDescriptor> descriptors, Map<String, ToolDescriptor> descriptorsByName,
            Map<String, ToolProvider> providersByName, Instant expiresAt) {

        private boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }

    }

}
