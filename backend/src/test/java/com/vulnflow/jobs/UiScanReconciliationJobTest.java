package com.vulnflow.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.vulnflow.ui.scan.UiScanRequestRepository;
import com.vulnflow.ui.scan.UiScanRequestService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class UiScanReconciliationJobTest {
    @Test
    void unavailableResultDoesNotBlockOtherRequestsOrLaterPages() {
        UiScanRequestRepository repository = mock(UiScanRequestRepository.class);
        UiScanRequestService service = mock(UiScanRequestService.class);
        UUID unavailable = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        when(repository.findProcessingIds(any())).thenReturn(
                new PageImpl<>(List.of(unavailable, completed), PageRequest.of(0, 25), 26),
                new PageImpl<>(List.of(completed), PageRequest.of(1, 25), 26),
                new PageImpl<>(List.of()));
        doThrow(new IllegalStateException("Unavailable")).when(service).reconcile(unavailable);
        UiScanReconciliationJob job = new UiScanReconciliationJob(repository, service);
        job.reconcile();
        job.reconcile();
        job.reconcile();
        verify(service, times(2)).reconcile(completed);
        verify(repository, times(2)).findProcessingIds(PageRequest.of(0, 25));
        verify(repository).findProcessingIds(PageRequest.of(1, 25));
    }
}
