package com.vulnflow.ui.auth;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Revokes persisted sessions only after the account change commits. */
@Component
public class UiSessionRevocation {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public UiSessionRevocation(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void revoke(AccountCredentialsChanged event) {
        sessions.findByPrincipalName(event.username()).values().stream()
                .filter(session -> !session.getId().equals(event.retainedSessionId()))
                .forEach(session -> sessions.deleteById(session.getId()));
    }

    public record AccountCredentialsChanged(String username, String retainedSessionId) {}
}
