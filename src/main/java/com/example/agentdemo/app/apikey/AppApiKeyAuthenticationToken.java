package com.example.agentdemo.app.apikey;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Authentication produced by a valid runtime app API key. The principal name is the app's owner id
 * so downstream owner-scoped queries resolve the app correctly.
 */
public class AppApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String ownerId;
    private final String appId;
    private final String keyId;

    public AppApiKeyAuthenticationToken(String ownerId, String appId, String keyId,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.ownerId = ownerId;
        this.appId = appId;
        this.keyId = keyId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return ownerId;
    }

    @Override
    public String getName() {
        return ownerId;
    }

    public String getAppId() {
        return appId;
    }

    public String getKeyId() {
        return keyId;
    }

}
