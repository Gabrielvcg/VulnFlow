package com.vulnflow.ui.admin;

import com.vulnflow.aws.ingestion.AwsPublicationOutbox;
import com.vulnflow.aws.ingestion.AwsPublicationOutboxRepository;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import com.vulnflow.ui.audit.UiAuditService;
import com.vulnflow.ui.auth.UiPrincipal;
import com.vulnflow.ui.auth.UiUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/ui/v1/admin/publications")
public class UiPublicationAdminController {
    private static final Logger LOGGER=LoggerFactory.getLogger(UiPublicationAdminController.class);
    private final AwsPublicationOutboxRepository publications; private final UiAuditService audit; private final UiUserRepository users;
    public UiPublicationAdminController(AwsPublicationOutboxRepository publications,UiAuditService audit,UiUserRepository users){this.publications=publications;this.audit=audit;this.users=users;}
    @PostMapping("/{eventId}/retry") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void retry(@PathVariable UUID eventId,@AuthenticationPrincipal UiPrincipal principal){AwsPublicationOutbox event=publications.findByEventIdForUpdate(eventId).orElseThrow(()->new ResourceNotFoundException("Publication event",eventId));event.retryFailed(Instant.now());audit.record(users.getReferenceById(principal.id()),principal.username(),"PUBLICATION_RETRIED","PUBLICATION",eventId.toString(),"SUCCESS",null,"Failed outbox publication queued again");LOGGER.info("Se reintentó una publicación fallida de la outbox");}
}
