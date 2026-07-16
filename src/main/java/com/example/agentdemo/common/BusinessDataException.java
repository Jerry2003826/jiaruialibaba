package com.example.agentdemo.common;

public class BusinessDataException extends BusinessException {

    private final Object data;

    public BusinessDataException(String code, String message, Object data) {
        super(code, message);
        this.data = data;
    }

    public Object getData() {
        return data;
    }
}
