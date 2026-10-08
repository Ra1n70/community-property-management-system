package com.cpms.community;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DemoSeed {
    @Bean CommandLineRunner seed(AccountRepository accounts,PasswordEncoder encoder,@Value("${demo.manager-password}") String password) {
        return args -> {
            if(password.isBlank()) return;
            if(password.length()<10) throw new IllegalArgumentException("DEMO_MANAGER_PASSWORD must contain at least 10 characters.");
            if(accounts.findByEmail("manager@cpms.local").isPresent())return;
            Account a=new Account();a.name="Community Manager";a.email="manager@cpms.local";
            a.passwordHash=encoder.encode(password);a.community="Demo Community";a.role=Account.Role.MANAGER;a.status=Account.Status.APPROVED;accounts.save(a);
        };
    }
}
