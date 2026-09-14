package com.vulnflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnflow.ui.auth.*;
import com.vulnflow.ui.scan.*;
import com.vulnflow.ui.target.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"vulnflow.security.api-key.value=test-api-key", "vulnflow.worker.enabled=false",
        "vulnflow.ui.enabled=false", "vulnflow.ui.scans-enabled=true", "vulnflow.ui.queue-capacity=1"})
@Testcontainers
class UiScanAdmissionIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Autowired UiUserRepository users;
    @Autowired UiTargetRepository targets;
    @Autowired UiAgentRepository agents;
    @Autowired UiScanRequestService service;
    @Autowired UiScanRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UiAdmissionLock admissionLock;

    @Test
    void admissionWaitsForAnotherTransactionsLock() throws Exception {
        UiUser user = user();
        UUID targetId = target(user).getId();
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<?> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                admissionLock.acquire();
                Future<?> submitted = executor.submit(() -> {
                    started.countDown();
                    return service.create(targetId, principal(user));
                });
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> submitted.get(300, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return submitted;
            });
            pending.get(10, TimeUnit.SECONDS);
            assertThat(requests.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @BeforeEach
    void clearRequestsAndRefreshAgent() {
        jdbc.update("DELETE FROM ui_audit_events");
        requests.deleteAll();
        UiAgent agent = new UiAgent("admission-test");
        agent.heartbeat("IDLE", null, 0, 0, 0, 2_000_000_000L, null);
        agents.save(agent);
    }

    @Test
    void concurrentUsersCannotExceedGlobalCapacity() throws Exception {
        UiUser first = user();
        UiUser second = user();
        List<Boolean> accepted = race(target(first).getId(), principal(first), target(second).getId(), principal(second));
        assertThat(accepted).containsExactlyInAnyOrder(true, false);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsFromOneUserCannotBothBeAccepted() throws Exception {
        UiUser user = user();
        List<Boolean> accepted = race(target(user).getId(), principal(user), target(user).getId(), principal(user));
        assertThat(accepted).containsExactlyInAnyOrder(true, false);
        assertThat(requests.count()).isEqualTo(1);
    }

    private List<Boolean> race(UUID firstTarget, UiPrincipal first, UUID secondTarget, UiPrincipal second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = executor.submit(() -> submit(firstTarget, first, ready, start));
            Future<Boolean> b = executor.submit(() -> submit(secondTarget, second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private boolean submit(UUID target, UiPrincipal principal, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            service.create(target, principal);
            return true;
        } catch (ScanRequestRejectedException expected) {
            return false;
        }
    }

    private UiUser user() {
        return users.save(new UiUser(UUID.randomUUID().toString(), "unused-test-hash", UiRole.OPERATOR, false));
    }

    private UiTarget target(UiUser user) {
        return targets.save(new UiTarget("test", "test:" + UUID.randomUUID(), null, user));
    }

    private UiPrincipal principal(UiUser user) {
        return new UiPrincipal(user.getId(), user.getUsername(), user.getRole(), false);
    }
}
