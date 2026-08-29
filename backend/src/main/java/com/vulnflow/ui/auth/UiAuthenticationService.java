package com.vulnflow.ui.auth;

import com.vulnflow.ui.UiProperties;
import com.vulnflow.ui.audit.UiAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UiAuthenticationService implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(UiAuthenticationService.class);
    private final UiUserRepository users;
    private final PasswordEncoder encoder;
    private final UiProperties properties;
    private final UiAuditService audit;

    public UiAuthenticationService(UiUserRepository users, PasswordEncoder encoder,
                                   UiProperties properties, UiAuditService audit) {
        this.users = users; this.encoder = encoder; this.properties = properties; this.audit = audit;
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled() || users.count() != 0) return;
        String username = properties.bootstrapUsername();
        String hash = properties.bootstrapPasswordHash();
        if (username == null || username.isBlank() || hash == null || !hash.startsWith("$2")) {
            LOGGER.warn("No se inicializó el administrador de la consola: faltan variables de bootstrap válidas");
            return;
        }
        UiUser user = users.save(new UiUser(username, hash, UiRole.ADMIN, true));
        audit.record(user, user.getUsername(), "USER_BOOTSTRAPPED", "USER", user.getId().toString(),
                "SUCCESS", null, "Initial administrator created");
        LOGGER.info("Se inicializó el primer administrador de la consola; elimine las variables de bootstrap");
    }

    @Transactional(noRollbackFor = UiAuthenticationException.class)
    public UiPrincipal login(String rawUsername, String password, HttpServletRequest request) {
        String username = rawUsername == null ? "" : rawUsername.trim().toLowerCase(Locale.ROOT);
        UiUser user = users.findByUsernameIgnoreCase(username).orElse(null);
        Instant now = Instant.now();
        if (user == null || !user.isEnabled() || user.isLocked(now) || !encoder.matches(password, user.getPasswordHash())) {
            if (user != null && user.isEnabled() && !user.isLocked(now)) user.recordFailure(now);
            audit.record(user, username, user != null && user.isLocked(now) ? "LOGIN_LOCKED" : "LOGIN_FAILED",
                    "SESSION", null, "FAILURE", request.getRemoteAddr(), "Credentials rejected");
            LOGGER.warn("Acceso rechazado a la consola para el usuario indicado");
            throw new UiAuthenticationException("INVALID_CREDENTIALS", "Invalid username or password");
        }
        user.recordLogin(now);
        UiPrincipal principal = principal(user);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        audit.record(user, username, "LOGIN", "SESSION", session.getId(), "SUCCESS",
                request.getRemoteAddr(), "Interactive login");
        LOGGER.info("Inicio de sesión correcto en la consola");
        return principal;
    }

    @Transactional
    public UiPrincipal changePassword(UiPrincipal principal, String currentPassword, String newPassword,
                                      HttpServletRequest request) {
        UiUser user = users.findById(principal.id()).orElseThrow(() -> new UiAuthenticationException(
                "INVALID_SESSION", "The authenticated user no longer exists"));
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new UiAuthenticationException("INVALID_PASSWORD", "The current password is invalid");
        }
        validatePassword(newPassword);
        user.changePassword(encoder.encode(newPassword));
        audit.record(user, user.getUsername(), "PASSWORD_CHANGED", "USER", user.getId().toString(),
                "SUCCESS", null, "Password changed by user");
        UiPrincipal updated=principal(user);
        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                updated,null,List.of(new SimpleGrantedAuthority("ROLE_"+user.getRole().name()))));
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }
        return updated;
    }

    public UiPrincipal principal(UiUser user) {
        return new UiPrincipal(user.getId(), user.getUsername(), user.getRole(), user.isPasswordChangeRequired());
    }

    public void validatePassword(String password) {
        if (password == null || password.length() < 14 || password.length() > 128
                || !password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*")
                || !password.matches(".*[0-9].*")) {
            throw new IllegalArgumentException("Password must be 14-128 characters and include upper, lower, and numeric characters");
        }
    }
}
