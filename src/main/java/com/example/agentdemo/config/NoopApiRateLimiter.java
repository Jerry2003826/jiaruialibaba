package com.example.agentdemo.config;

enum NoopApiRateLimiter implements ApiRateLimiter {

    INSTANCE;

    @Override
    public boolean allow(String key) {
        return true;
    }

}
