package com.vulnflow.ui.audit;

import com.vulnflow.ui.auth.UiUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

@Service
public class UiAuditService {
    private final UiAuditRepository repository;
    public UiAuditService(UiAuditRepository repository) { this.repository = repository; }
    public void record(UiUser actor, String username, String action, String subjectType,
                       String subjectId, String outcome, String address, String details) {
        repository.save(new UiAuditEvent(actor, username, action, subjectType, subjectId,
                outcome, hash(address), limit(details)));
    }
    private String hash(String value) {
        if (value == null || value.isBlank()) return null;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private String limit(String value) { return value == null || value.length() <= 500 ? value : value.substring(0, 500); }
}
