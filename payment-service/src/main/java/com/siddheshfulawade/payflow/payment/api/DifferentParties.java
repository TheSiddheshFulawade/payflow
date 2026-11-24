package com.siddheshfulawade.payflow.payment.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Payer and payee differ (REQ-PF-004). Its own constraint instead of an {@code @AssertTrue isX()} method on the record:
 * Jackson could take such a method for a property and tolerate the field "x" in the request.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DifferentParties.Validator.class)
@interface DifferentParties {

    String message() default "payer and payee must differ";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<DifferentParties, CreatePaymentRequest> {

        @Override
        public boolean isValid(CreatePaymentRequest request, ConstraintValidatorContext context) {
            // a missing payer is @NotNull's error, not ours
            if (request.payer() == null || !request.payer().equals(request.payee())) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("payee")
                    .addConstraintViolation();
            return false;
        }
    }
}
