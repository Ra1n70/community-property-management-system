package com.cpms.community;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;

public class AccountSecurityFilter extends OncePerRequestFilter {
    private final AccountRepository accounts;private final AttemptLimiter limiter;
    public AccountSecurityFilter(AccountRepository accounts,AttemptLimiter limiter){this.accounts=accounts;this.limiter=limiter;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        res.setHeader("Cache-Control","no-store");res.setHeader("Referrer-Policy","no-referrer");
        String path=req.getServletPath();
        if("POST".equals(req.getMethod()) && Set.of("/api/auth/login","/api/auth/recover","/api/auth/recovery-link","/api/auth/recovery-link/status","/api/auth/password","/api/auth/recovery-code").contains(path)) {
            // Do not trust caller-supplied forwarding headers on this local server.
            if(!limiter.allow(path+":"+req.getRemoteAddr(),20)){error(res,429,"Too many attempts. Try again in 15 minutes.");return;}
        }
        var auth=SecurityContextHolder.getContext().getAuthentication();
        HttpSession session=req.getSession(false);
        if(auth!=null && auth.isAuthenticated() && session!=null && session.getAttribute(PortalSecurityContextRepository.versionKey(req))!=null) {
            Account a=accounts.findByEmail(auth.getName()).orElse(null);
            if(a==null || !Objects.equals(session.getAttribute(PortalSecurityContextRepository.versionKey(req)),Objects.toString(a.sessionVersion,""))) {
                SecurityConfig.clearPortal(req,res,new PortalSecurityContextRepository());error(res,401,"Your password changed. Please sign in again.");return;
            }
        }
        chain.doFilter(req,res);
    }
    static void error(HttpServletResponse res,int status,String message)throws IOException {
        res.setStatus(status);res.setContentType("application/json");res.getWriter().write("{\"message\":\""+message+"\"}");
    }
}
