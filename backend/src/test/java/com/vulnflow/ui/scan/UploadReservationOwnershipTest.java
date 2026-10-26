package com.vulnflow.ui.scan;

import static org.assertj.core.api.Assertions.*;
import com.vulnflow.asset.*;
import com.vulnflow.scan.*;
import com.vulnflow.ui.auth.*;
import com.vulnflow.ui.target.UiTarget;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UploadReservationOwnershipTest {
    @Test
    void releaseAndAssociationRecheckExactOperationAndNeverOverwriteAcceptance() {
        Asset asset = new Asset("demo", AssetType.HOST, "demo-host");
        UiScanRequest request = new UiScanRequest(new UiTarget("demo","demo-host",asset,null),
                new UiUser("operator","hash",UiRole.OPERATOR,false));
        Duration lease = Duration.ofMinutes(2);
        UUID token = request.claim(new UiAgent("agent-a"),lease);
        request.start(token,lease);
        String hash = "a".repeat(64);
        var first = request.reserveUpload(token,asset.getId(),hash,lease);
        request.releaseUpload(UUID.randomUUID(),first.attemptId());
        assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.UPLOADING);
        request.releaseUpload(token,UUID.randomUUID());
        assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.UPLOADING);
        request.releaseUpload(token,first.attemptId());
        assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.RUNNING);
        var second = request.reserveUpload(token,asset.getId(),hash,lease);
        request.releaseUpload(token,first.attemptId());
        assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.UPLOADING);
        Scan scan = new Scan(asset,ScannerType.TRIVY,"report.json",hash);
        UUID event = UUID.randomUUID();
        assertThatThrownBy(() -> request.associateUpload(token,first.attemptId(),scan,event))
                .isInstanceOf(StaleScanClaimException.class);
        Scan wrongHash = new Scan(asset,ScannerType.TRIVY,"other.json","b".repeat(64));
        assertThatThrownBy(() -> request.associateUpload(token,second.attemptId(),wrongHash,event))
                .isInstanceOf(StaleScanClaimException.class);
        request.associateUpload(token,second.attemptId(),scan,event);
        Instant uploaded = request.getUploadedAt();
        Object updated = ReflectionTestUtils.getField(request,"updatedAt");
        request.releaseUpload(token,second.attemptId());
        request.associateUpload(token,second.attemptId(),scan,event);
        assertThat(request.getStatus()).isEqualTo(UiScanRequestStatus.PROCESSING);
        assertThat(request.getClaimToken()).isEqualTo(token);
        assertThat(request.getClaimExpiresAt()).isNull();
        assertThat(request.getUploadedAt()).isEqualTo(uploaded);
        assertThat(ReflectionTestUtils.getField(request,"updatedAt")).isEqualTo(updated);
        Scan otherScan = new Scan(asset,ScannerType.TRIVY,"same.json",hash);
        assertThatThrownBy(() -> request.associateUpload(token,second.attemptId(),otherScan,event))
                .isInstanceOf(StaleScanClaimException.class);
    }
}
