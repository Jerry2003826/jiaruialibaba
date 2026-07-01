package com.example.agentdemo.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

public final class SecurityIdentity {

    public static final String DEFAULT_OWNER_ID = "workbench-dev";

    private SecurityIdentity() {
    }

    public static String currentOwnerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return DEFAULT_OWNER_ID;
        }
        String name = authentication.getName();
        return StringUtils.hasText(name) ? name.trim() : DEFAULT_OWNER_ID;
    }

    public static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(candidate -> authority.equals(candidate.getAuthority()));
    }

}
