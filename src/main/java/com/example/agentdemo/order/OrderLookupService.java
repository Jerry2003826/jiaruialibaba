package com.example.agentdemo.order;

import java.util.Map;

@FunctionalInterface
public interface OrderLookupService {

    Map<String, Object> lookup(String userQuery);

}
