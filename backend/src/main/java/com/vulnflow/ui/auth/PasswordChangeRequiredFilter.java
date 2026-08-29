package com.vulnflow.ui.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String path = request.getRequestURI();
        if (auth != null && auth.getPrincipal() instanceof UiPrincipal principal
                && principal.passwordChangeRequired()
                && path.startsWith("/api/ui/v1/")
                && !path.equals("/api/ui/v1/auth/me")
                && !path.equals("/api/ui/v1/auth/change-password")
                && !path.equals("/api/ui/v1/auth/logout")) {
            response.sendError(428, "Password change required");
            return;
        }
        chain.doFilter(request, response);
    }
}
