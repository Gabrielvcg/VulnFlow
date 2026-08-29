package com.vulnflow.ui.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnflow.ui.auth.UiRole;
import com.vulnflow.ui.auth.UiUser;
import com.vulnflow.ui.target.UiTarget;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UiScanRequestTest {
    @Test void rejectsAStaleFencingToken(){UiScanRequest request=new UiScanRequest(new UiTarget("Alpine","alpine:3.20",null,null),new UiUser("operator","hash",UiRole.OPERATOR,false));UUID token=request.claim(new UiAgent("agent-a"),Duration.ofMinutes(2));assertThatThrownBy(()->request.start(UUID.randomUUID(),Duration.ofMinutes(2))).isInstanceOf(StaleScanClaimException.class);request.start(token,Duration.ofMinutes(2));assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.RUNNING);}
    @Test void recoversAnAbandonedClaimAtMostTwice(){UiScanRequest request=new UiScanRequest(new UiTarget("Alpine","alpine:3.20",null,null),new UiUser("operator","hash",UiRole.OPERATOR,false));request.claim(new UiAgent("agent-a"),Duration.ofMinutes(2));ReflectionTestUtils.setField(request,"claimExpiresAt",java.time.Instant.now().minusSeconds(1));assertThat(request.recover(Duration.ofMinutes(30),2)).isTrue();assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.REQUESTED);assertThat(request.getRecoveryAttempts()).isEqualTo(1);}
}
