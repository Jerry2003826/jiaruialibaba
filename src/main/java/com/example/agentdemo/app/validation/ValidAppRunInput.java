package com.example.agentdemo.app.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AppRunInputValidator.class)
public @interface ValidAppRunInput {

    String message() default "must be a valid app run input payload";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
