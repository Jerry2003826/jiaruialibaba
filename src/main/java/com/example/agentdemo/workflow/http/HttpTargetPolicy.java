package com.example.agentdemo.workflow.http;

import com.example.agentdemo.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class HttpTargetPolicy {

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final WorkflowHttpProperties properties;
    private final AddressResolver addressResolver;

    @Autowired
    public HttpTargetPolicy(WorkflowHttpProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    HttpTargetPolicy(WorkflowHttpProperties properties, AddressResolver addressResolver) {
        this.properties = properties;
        this.addressResolver = addressResolver;
    }

    public void validate(URI target) {
        if (target == null || !StringUtils.hasText(target.getScheme()) || !StringUtils.hasText(target.getHost())) {
            throw blocked("HTTP target must include a valid scheme and host");
        }
        String scheme = target.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw blocked("Only HTTP and HTTPS targets are allowed");
        }
        if (StringUtils.hasText(target.getUserInfo())) {
            throw blocked("Credentials must not be embedded in the HTTP URL");
        }
        String host = normalizeHost(target.getHost());
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            throw blocked("Localhost targets are blocked");
        }
        InetAddress[] addresses;
        try {
            addresses = addressResolver.resolve(host);
        }
        catch (UnknownHostException ex) {
            throw new BusinessException("WORKFLOW_HTTP_DNS_FAILED", "HTTP target host could not be resolved", ex);
        }
        if (addresses.length == 0) {
            throw new BusinessException("WORKFLOW_HTTP_DNS_FAILED", "HTTP target host resolved to no addresses");
        }
        if (!isAllowedHost(host) && Arrays.stream(addresses).anyMatch(this::isBlockedAddress)) {
            throw blocked("HTTP target resolves to a private or restricted address");
        }
    }

    private boolean isAllowedHost(String host) {
        List<String> configured = properties.getAllowedHosts();
        for (String rawPattern : configured) {
            if (!StringUtils.hasText(rawPattern)) {
                continue;
            }
            String pattern = normalizeHost(rawPattern.trim());
            if (pattern.startsWith("*.") && host.endsWith(pattern.substring(1))
                    && host.length() > pattern.length() - 1) {
                return true;
            }
            if (host.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || first == 10
                    || first == 127
                    || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first == 100 && second >= 64 && second <= 127
                    || first >= 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) == 0xfc;
        }
        return true;
    }

    private String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private BusinessException blocked(String reason) {
        return new BusinessException("WORKFLOW_HTTP_TARGET_BLOCKED", reason);
    }
}
