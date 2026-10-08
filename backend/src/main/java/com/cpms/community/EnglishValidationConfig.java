package com.cpms.community;

import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validation;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@Configuration
public class EnglishValidationConfig {
    @Bean
    public LocalValidatorFactoryBean validator() {
        MessageInterpolator delegate = Validation.byDefaultProvider().configure().getDefaultMessageInterpolator();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new MessageInterpolator() {
            @Override
            public String interpolate(String template, Context context) {
                return delegate.interpolate(template, context, Locale.ENGLISH);
            }

            @Override
            public String interpolate(String template, Context context, Locale locale) {
                return delegate.interpolate(template, context, Locale.ENGLISH);
            }
        });
        return validator;
    }
}
