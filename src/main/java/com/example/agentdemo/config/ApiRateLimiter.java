package com.example.agentdemo.config;

interface ApiRateLimiter {

    boolean allow(String key);

}
