package com.example.agentdemo.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class PageRequestValidator {

    public PageRequest build(int page, int size, int maxSize, String errorCode, Sort sort) {
        if (page < 0) {
            throw new BusinessException(errorCode, "page must be greater than or equal to 0");
        }
        if (size < 1 || size > maxSize) {
            throw new BusinessException(errorCode, "size must be between 1 and " + maxSize);
        }
        return PageRequest.of(page, size, sort);
    }

    public PageRequest build(int page, int size, String errorCode, Sort sort) {
        return build(page, size, 100, errorCode, sort);
    }

}
