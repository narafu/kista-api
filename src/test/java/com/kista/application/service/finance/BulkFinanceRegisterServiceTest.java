package com.kista.application.service.finance;

import com.kista.domain.model.finance.AssetClass;
import com.kista.domain.model.finance.AssetSnapshotCommand;
import com.kista.domain.model.finance.BulkFinanceRegisterResult;
import com.kista.domain.model.finance.Market;
import com.kista.domain.port.in.AssetSnapshotUseCase;
import com.kista.domain.port.in.FinanceTransactionUseCase;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BulkFinanceRegisterServiceTest {

    @Test
    void 항목_하나가_실패해도_나머지는_등록된다() {
        AssetSnapshotUseCase assetSnapshotUseCase = mock(AssetSnapshotUseCase.class);
        FinanceTransactionUseCase transactionUseCase = mock(FinanceTransactionUseCase.class);
        BulkFinanceRegisterService service = new BulkFinanceRegisterService(assetSnapshotUseCase, transactionUseCase);

        UUID userId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();

        AssetSnapshotCommand asset1 = new AssetSnapshotCommand(
                UUID.randomUUID(), null, LocalDate.of(2026, 8, 1), AssetClass.CASH, Market.DOMESTIC, null, "메모1", 1000L);
        AssetSnapshotCommand asset2 = new AssetSnapshotCommand(
                UUID.randomUUID(), null, LocalDate.of(2026, 8, 1), AssetClass.CASH, Market.DOMESTIC, null, "메모2", 2000L);

        doThrow(new IllegalArgumentException("카테고리 없음"))
                .when(assetSnapshotUseCase).create(eq(userId), eq(groupId), argThat(c -> c.memo().equals("메모1")));

        BulkFinanceRegisterResult result = service.register(userId, groupId, List.of(asset1, asset2), List.of());

        assertThat(result.assetSuccessCount()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        verify(assetSnapshotUseCase, times(1)).create(eq(userId), eq(groupId), argThat(c -> c.memo().equals("메모2")));
    }
}
