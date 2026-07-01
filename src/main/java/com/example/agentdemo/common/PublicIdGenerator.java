package com.example.agentdemo.common;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Component
public class PublicIdGenerator {

    public String next(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        return prefix.trim() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    public String nextUuid() {
        return UUID.randomUUID().toString();
    }

}
