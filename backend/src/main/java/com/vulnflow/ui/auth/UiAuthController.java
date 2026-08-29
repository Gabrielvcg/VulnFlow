package com.vulnflow.ui.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ui/v1/auth")
public class UiAuthController {
    private final UiAuthenticationService authentication;
    public UiAuthController(UiAuthenticationService authentication) { this.authentication = authentication; }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken()); }

    @PostMapping("/login")
    public UiPrincipal login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return authentication.login(body.username(), body.password(), request);
    }

    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public UiPrincipal me(@AuthenticationPrincipal UiPrincipal principal) { return principal; }

    @PostMapping("/change-password")
    public UiPrincipal changePassword(@AuthenticationPrincipal UiPrincipal principal,
                                      @Valid @RequestBody ChangePasswordRequest body) {
        return authentication.changePassword(principal, body.currentPassword(), body.newPassword());
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String username,
                               @NotBlank @Size(max = 128) String password) {}
    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {}
    public record CsrfResponse(String token) {}
}
