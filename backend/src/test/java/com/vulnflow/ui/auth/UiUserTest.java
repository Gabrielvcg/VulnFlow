package com.vulnflow.ui.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UiUserTest {
    @Test void locksForFifteenMinutesAfterFiveFailures() {
        UiUser user=new UiUser("operator","$2a$12$placeholder",UiRole.OPERATOR,true);
        Instant now=Instant.parse("2026-08-29T10:00:00Z");
        for(int attempt=0;attempt<5;attempt++)user.recordFailure(now);
        assertThat(user.isLocked(now.plusSeconds(899))).isTrue();
        assertThat(user.isLocked(now.plusSeconds(901))).isFalse();
    }
    @Test void successfulPasswordChangeClearsFirstAccessRequirement(){UiUser user=new UiUser("admin","old",UiRole.ADMIN,true);user.changePassword("new");assertThat(user.isPasswordChangeRequired()).isFalse();assertThat(user.getPasswordHash()).isEqualTo("new");}
}
