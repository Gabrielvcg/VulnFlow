package com.vulnflow.ui.admin;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetRepository;
import com.vulnflow.asset.AssetType;
import com.vulnflow.ui.audit.UiAuditEvent;
import com.vulnflow.ui.audit.UiAuditRepository;
import com.vulnflow.ui.audit.UiAuditService;
import com.vulnflow.ui.auth.UiAuthenticationService;
import com.vulnflow.ui.auth.UiPrincipal;
import com.vulnflow.ui.auth.UiRole;
import com.vulnflow.ui.auth.UiUser;
import com.vulnflow.ui.auth.UiUserRepository;
import com.vulnflow.ui.target.UiTarget;
import com.vulnflow.ui.target.UiTargetRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController @RequestMapping("/api/ui/v1/admin")
public class UiAdminController {
    private static final Logger LOGGER=LoggerFactory.getLogger(UiAdminController.class);
    private final UiUserRepository users; private final UiTargetRepository targets; private final AssetRepository assets;
    private final UiAuditRepository audits; private final UiAuditService audit; private final PasswordEncoder encoder;
    private final UiAuthenticationService authentication; private final SecureRandom random = new SecureRandom();
    public UiAdminController(UiUserRepository users, UiTargetRepository targets, AssetRepository assets,
                             UiAuditRepository audits, UiAuditService audit, PasswordEncoder encoder,
                             UiAuthenticationService authentication) {
        this.users=users; this.targets=targets; this.assets=assets; this.audits=audits; this.audit=audit;
        this.encoder=encoder; this.authentication=authentication;
    }

    @GetMapping("/users") public List<UserResponse> users() { return users.findAll().stream().map(UserResponse::from).toList(); }
    @PostMapping("/users") @Transactional
    public CreatedUser createUser(@AuthenticationPrincipal UiPrincipal principal, @Valid @RequestBody CreateUser body) {
        if (users.findByUsernameIgnoreCase(body.username()).isPresent()) throw new IllegalArgumentException("Username already exists");
        String password = temporaryPassword(); authentication.validatePassword(password);
        UiUser created = users.save(new UiUser(body.username(), encoder.encode(password), body.role(), true));
        audit.record(users.getReferenceById(principal.id()), principal.username(), "USER_CREATED", "USER",
                created.getId().toString(), "SUCCESS", null, "Role " + body.role());
        LOGGER.info("Se creó un usuario de consola con rol {}",body.role());
        return new CreatedUser(UserResponse.from(created), password);
    }
    @PatchMapping("/users") @Transactional
    public UserResponse updateUser(@AuthenticationPrincipal UiPrincipal principal, @Valid @RequestBody UpdateUser body) {
        UiUser user = users.findById(body.id()).orElseThrow();
        user.setEnabled(body.enabled());
        String password = null;
        if (body.rotatePassword()) { password = temporaryPassword(); user.requirePasswordChange(encoder.encode(password)); }
        audit.record(users.getReferenceById(principal.id()), principal.username(), "USER_UPDATED", "USER",
                user.getId().toString(), "SUCCESS", null, body.rotatePassword() ? "Password rotated" : "Enabled state changed");
        return UserResponse.from(user).withTemporaryPassword(password);
    }

    @GetMapping("/targets") public List<TargetAdminResponse> targets() { return targets.findAllByOrderByNameAsc().stream().map(TargetAdminResponse::from).toList(); }
    @PostMapping("/targets") @Transactional
    public TargetAdminResponse createTarget(@AuthenticationPrincipal UiPrincipal principal, @Valid @RequestBody TargetBody body) {
        if (targets.findByTypeAndExternalReference(AssetType.CONTAINER_IMAGE, body.reference()).isPresent()) throw new IllegalArgumentException("Target already exists");
        Asset asset = assets.findByTypeAndExternalReference(AssetType.CONTAINER_IMAGE, body.reference()).orElse(null);
        UiTarget target = targets.save(new UiTarget(body.name(), body.reference(), asset, users.getReferenceById(principal.id())));
        audit.record(users.getReferenceById(principal.id()), principal.username(), "TARGET_CREATED", "TARGET", target.getId().toString(), "SUCCESS", null, "Container image target created");
        LOGGER.info("Se creó un target permitido: targetId={}",target.getId());
        return TargetAdminResponse.from(target);
    }
    @PatchMapping("/targets") @Transactional
    public TargetAdminResponse updateTarget(@AuthenticationPrincipal UiPrincipal principal, @Valid @RequestBody TargetUpdate body) {
        UiTarget target = targets.findById(body.id()).orElseThrow(); target.update(body.name(), body.reference(), body.enabled());
        audit.record(users.getReferenceById(principal.id()), principal.username(), "TARGET_UPDATED", "TARGET", target.getId().toString(), "SUCCESS", null, "Target catalog entry changed");
        LOGGER.info("Se actualizó un target permitido: targetId={}, activo={}",target.getId(),target.isEnabled());
        return TargetAdminResponse.from(target);
    }
    @GetMapping("/audit") public Page<AuditResponse> audit(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return audits.findAllByOrderByCreatedAtDesc(PageRequest.of(page, Math.min(size,100))).map(AuditResponse::from);
    }
    private String temporaryPassword() { byte[] bytes = new byte[18]; random.nextBytes(bytes); return "Vf1A" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    public record CreateUser(@NotBlank @Size(max=100) String username, @NotNull UiRole role) {}
    public record UpdateUser(@NotNull UUID id, boolean enabled, boolean rotatePassword) {}
    public record TargetBody(@NotBlank @Size(max=255) String name, @NotBlank @Size(max=500) String reference) {}
    public record TargetUpdate(@NotNull UUID id, @NotBlank @Size(max=255) String name, @NotBlank @Size(max=500) String reference, boolean enabled) {}
    public record UserResponse(UUID id,String username,UiRole role,boolean enabled,boolean passwordChangeRequired,Instant lockedUntil,Instant lastLoginAt,String temporaryPassword) {
        static UserResponse from(UiUser u){return new UserResponse(u.getId(),u.getUsername(),u.getRole(),u.isEnabled(),u.isPasswordChangeRequired(),u.getLockedUntil(),u.getLastLoginAt(),null);} UserResponse withTemporaryPassword(String p){return new UserResponse(id,username,role,enabled,passwordChangeRequired,lockedUntil,lastLoginAt,p);}}
    public record CreatedUser(UserResponse user,String temporaryPassword) {}
    public record TargetAdminResponse(UUID id,String name,String type,String reference,boolean enabled,UUID assetId,Instant updatedAt){static TargetAdminResponse from(UiTarget t){return new TargetAdminResponse(t.getId(),t.getName(),t.getType().name(),t.getExternalReference(),t.isEnabled(),t.getAsset()==null?null:t.getAsset().getId(),t.getUpdatedAt());}}
    public record AuditResponse(UUID id,String actor,String action,String subjectType,String subjectId,String outcome,String details,Instant createdAt){static AuditResponse from(UiAuditEvent e){return new AuditResponse(e.getId(),e.getActorUsername(),e.getAction(),e.getSubjectType(),e.getSubjectId(),e.getOutcome(),e.getDetails(),e.getCreatedAt());}}
}
