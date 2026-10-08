package com.cpms.community;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    private static class AccountUser extends User {
        final String sessionVersion;
        AccountUser(Account a) {
            super(a.email,a.passwordHash,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+a.role.name())));
            sessionVersion=java.util.Objects.toString(a.sessionVersion,"");
        }
    }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(AccountRepository accounts) {
        return email -> accounts.findByEmail(AccountService.normalize(email))
            .map(AccountUser::new)
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http,AccountRepository accounts,AttemptLimiter limiter) throws Exception {
        var contexts = new PortalSecurityContextRepository();
        return http.securityContext(context -> context.securityContextRepository(contexts))
            .addFilterBefore(new AccountSecurityFilter(accounts,limiter),org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/csrf", "/api/auth/register", "/api/auth/login", "/api/auth/recover", "/api/auth/recovery-link", "/api/auth/recovery-link/status").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/locker-panel/locations").permitAll()
                // Kiosk: residents use a pickup code, couriers use their personal store code; neither signs in.
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/locker-panel/*/pickup", "/api/locker-panel/*/courier/**", "/api/locker-panel/*/intake/**").permitAll()
                .requestMatchers("/api/manager/**").hasRole("MANAGER")
                .anyRequest().authenticated())
            .formLogin(login -> login.loginProcessingUrl("/api/auth/login").usernameParameter("email")
                .successHandler((req,res,a) -> {
                    Account account=accounts.findByEmail(a.getName()).orElseThrow();
                    if(!((AccountUser)a.getPrincipal()).sessionVersion.equals(java.util.Objects.toString(account.sessionVersion,""))) {
                        clearPortal(req,res,contexts);
                        AccountSecurityFilter.error(res,401,"Credentials changed. Please sign in again.");return;
                    }
                    String role=req.getParameter("role");
                    if(role==null)role="RESIDENT";
                    String portal=PortalSecurityContextRepository.portal(req);
                    if(!account.role.name().equals(role) || (!portal.isEmpty() && !portal.equals(role))) {
                        clearPortal(req,res,contexts);
                        AccountSecurityFilter.error(res,403,"Use the login entrance for your account role.");return;
                    }
                    req.getSession().setAttribute(PortalSecurityContextRepository.versionKey(req),java.util.Objects.toString(account.sessionVersion,""));res.setStatus(204);
                })
                .failureHandler((req,res,e) -> { res.setStatus(401); res.setContentType("application/json"); res.getWriter().write("{\"message\":\"Invalid email or password.\"}"); }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout").invalidateHttpSession(false).clearAuthentication(false)
                .addLogoutHandler((req,res,a) -> clearPortal(req,res,contexts)).logoutSuccessHandler((req,res,a) -> res.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> {res.setStatus(401); res.setContentType("application/json"); res.getWriter().write("{\"message\":\"Please sign in.\"}");})
                .accessDeniedHandler((req,res,ex) -> {res.setStatus(403); res.setContentType("application/json"); res.getWriter().write("{\"message\":\"Access denied. Refresh and try again.\"}");}))
            .requestCache(cache -> cache.disable()).build();
    }
    static void clearPortal(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res,
                            PortalSecurityContextRepository contexts) {
        var session=req.getSession(false);
        if(session!=null) {
            if(PortalSecurityContextRepository.portal(req).isEmpty()) session.invalidate();
            else session.removeAttribute(PortalSecurityContextRepository.versionKey(req));
        }
        var empty=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        contexts.saveContext(empty,req,res);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
}
