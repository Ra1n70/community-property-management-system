package com.cpms.community;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import java.util.Map;

/** Selects an independently authenticated portal in the shared browser session. */
public class PortalSecurityContextRepository implements SecurityContextRepository {
    private final HttpSessionSecurityContextRepository legacy = new HttpSessionSecurityContextRepository();
    private final Map<String, HttpSessionSecurityContextRepository> portals = Map.of(
        "RESIDENT", repository("RESIDENT"), "MANAGER", repository("MANAGER"), "PROVIDER", repository("PROVIDER"));

    private static HttpSessionSecurityContextRepository repository(String role) {
        var repository = new HttpSessionSecurityContextRepository();
        repository.setSpringSecurityContextKey("SPRING_SECURITY_CONTEXT_" + role);
        return repository;
    }
    static String portal(HttpServletRequest request) {
        String role = request.getHeader("X-Community-Portal");
        // Images and links cannot supply a custom header. The selector is not a credential.
        if (role == null && "GET".equals(request.getMethod())) role = request.getParameter("portal");
        return role == null ? "" : role;
    }
    static String versionKey(HttpServletRequest request) {
        String portal = portal(request);
        return portal.isEmpty() ? "accountVersion" : "accountVersion_" + portal;
    }
    private HttpSessionSecurityContextRepository delegate(HttpServletRequest request) {
        String role = portal(request);
        if (role.isEmpty()) return legacy;
        // Unknown selectors never fall back to an authenticated legacy identity.
        return portals.getOrDefault(role, repository("INVALID"));
    }
    @Override public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return delegate(holder.getRequest()).loadContext(holder);
    }
    @Override public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return delegate(request).loadDeferredContext(request);
    }
    @Override public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        delegate(request).saveContext(context, request, response);
    }
    @Override public boolean containsContext(HttpServletRequest request) {
        return delegate(request).containsContext(request);
    }
}
