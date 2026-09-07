package com.kista.finance.application.service;

import com.kista.finance.domain.model.AssetClass;
import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.BulkFinanceRegisterResult;
import com.kista.finance.domain.model.FinanceTransactionCommand;
import com.kista.finance.domain.model.Market;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.usecase.AssetSnapshotUseCase;
import com.kista.finance.application.usecase.FinanceTransactionUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BulkFinanceRegisterServiceTest {

    private AssetSnapshotUseCase assetSnapshotUseCase;
    private FinanceTransactionUseCase transactionUseCase;
    private FinanceGroupPort financeGroupPort;
    private BulkFinanceRegisterService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        assetSnapshotUseCase = mock(AssetSnapshotUseCase.class);
        transactionUseCase = mock(FinanceTransactionUseCase.class);
        financeGroupPort = mock(FinanceGroupPort.class);
        service = new BulkFinanceRegisterService(assetSnapshotUseCase, transactionUseCase, financeGroupPort);
    }

    private AssetSnapshotCommand asset(String memo) {
        return new AssetSnapshotCommand(
                UUID.randomUUID(), null, LocalDate.of(2026, 8, 1), AssetClass.CASH, Market.DOMESTIC, null, memo, 1000L);
    }

    private FinanceTransactionCommand tx(String memo) {
        return new FinanceTransactionCommand(UUID.randomUUID(), LocalDate.of(2026, 8, 1), 1000L, memo);
    }

    @Test
    void 항목_하나가_실패해도_나머지는_등록된다() {
        AssetSnapshotCommand asset1 = asset("메모1");
        AssetSnapshotCommand asset2 = asset("메모2");

        doThrow(new IllegalArgumentException("카테고리 없음"))
                .when(assetSnapshotUseCase).create(eq(userId), eq(false), argThat(c -> c.memo().equals("메모1")));

        BulkFinanceRegisterResult result = service.register(userId, false, List.of(asset1, asset2), List.of());

        assertThat(result.assetSuccessCount()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0)).contains("메모1", "카테고리 없음");
        verify(assetSnapshotUseCase, times(1)).create(eq(userId), eq(false), argThat(c -> c.memo().equals("메모2")));
    }

    @Test
    void shareToGroup_true_그룹소속이면_각_항목을_shareToGroup_true로_생성한다() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        BulkFinanceRegisterResult result = service.register(
                userId, true, List.of(asset("자산1")), List.of(tx("거래1")));

        assertThat(result.assetSuccessCount()).isEqualTo(1);
        assertThat(result.transactionSuccessCount()).isEqualTo(1);
        assertThat(result.failures()).isEmpty();
        verify(assetSnapshotUseCase, times(1)).create(eq(userId), eq(true), any());
        verify(transactionUseCase, times(1)).create(eq(userId), eq(true), any());
    }

    @Test
    void shareToGroup_true_무그룹유저면_아무것도_생성하지_않고_예외() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(userId, true, List.of(asset("자산1")), List.of(tx("거래1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("소속된 그룹이 없습니다");

        verify(assetSnapshotUseCase, never()).create(any(), anyBoolean(), any());
        verify(transactionUseCase, never()).create(any(), anyBoolean(), any());
    }

    @Test
    void shareToGroup_false면_그룹조회없이_개인소유로_등록된다() {
        BulkFinanceRegisterResult result = service.register(userId, false, List.of(asset("자산1")), List.of());

        assertThat(result.assetSuccessCount()).isEqualTo(1);
        verify(assetSnapshotUseCase, times(1)).create(eq(userId), eq(false), any());
        verify(financeGroupPort, never()).findCurrentGroupId(any());
    }

    @Test
    void 한_항목의_create가_실패해도_나머지_거래는_등록되고_failures로_수집된다() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        doThrow(new IllegalStateException("이미 다른 그룹에 공유된 항목입니다"))
                .when(transactionUseCase).create(eq(userId), eq(true), argThat(c -> c.memo().equals("거래1")));

        BulkFinanceRegisterResult result = service.register(
                userId, true, List.of(), List.of(tx("거래1"), tx("거래2")));

        assertThat(result.transactionSuccessCount()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0)).contains("거래1");
    }
}
