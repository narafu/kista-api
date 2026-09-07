package com.kista.finance.application.service;

import com.kista.finance.domain.model.AssetClass;
import com.kista.finance.domain.model.AssetSnapshot;
import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.BulkFinanceRegisterResult;
import com.kista.finance.domain.model.FinanceTransaction;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    private void stubAssetCreateReturnsId() {
        AssetSnapshot saved = mock(AssetSnapshot.class);
        when(saved.id()).thenReturn(UUID.randomUUID());
        when(assetSnapshotUseCase.create(eq(userId), isNull(), any())).thenReturn(saved);
    }

    private void stubTxCreateReturnsId() {
        FinanceTransaction saved = mock(FinanceTransaction.class);
        when(saved.id()).thenReturn(UUID.randomUUID());
        when(transactionUseCase.create(eq(userId), isNull(), any())).thenReturn(saved);
    }

    @Test
    void 항목_하나가_실패해도_나머지는_등록된다() {
        stubAssetCreateReturnsId();
        AssetSnapshotCommand asset1 = asset("메모1");
        AssetSnapshotCommand asset2 = asset("메모2");

        doThrow(new IllegalArgumentException("카테고리 없음"))
                .when(assetSnapshotUseCase).create(eq(userId), isNull(), argThat(c -> c.memo().equals("메모1")));

        BulkFinanceRegisterResult result = service.register(userId, false, List.of(asset1, asset2), List.of());

        assertThat(result.assetSuccessCount()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        verify(assetSnapshotUseCase, times(1)).create(eq(userId), isNull(), argThat(c -> c.memo().equals("메모2")));
        verify(assetSnapshotUseCase, never()).shareToGroup(any(), any());
    }

    @Test
    void shareToGroup_true_그룹소속이면_생성후_공유전환한다() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        stubAssetCreateReturnsId();
        stubTxCreateReturnsId();

        BulkFinanceRegisterResult result = service.register(
                userId, true, List.of(asset("자산1")), List.of(tx("거래1")));

        assertThat(result.assetSuccessCount()).isEqualTo(1);
        assertThat(result.transactionSuccessCount()).isEqualTo(1);
        assertThat(result.failures()).isEmpty();
        verify(assetSnapshotUseCase, times(1)).shareToGroup(any(), eq(userId));
        verify(transactionUseCase, times(1)).shareToGroup(any(), eq(userId));
    }

    @Test
    void shareToGroup_true_무그룹유저면_아무것도_생성하지_않고_400() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(userId, true, List.of(asset("자산1")), List.of(tx("거래1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("소속된 그룹이 없습니다");

        verify(assetSnapshotUseCase, never()).create(any(), any(), any());
        verify(transactionUseCase, never()).create(any(), any(), any());
    }

    @Test
    void shareToGroup_false면_공유전환없이_개인소유로_등록된다() {
        stubAssetCreateReturnsId();

        BulkFinanceRegisterResult result = service.register(userId, false, List.of(asset("자산1")), List.of());

        assertThat(result.assetSuccessCount()).isEqualTo(1);
        verify(assetSnapshotUseCase, never()).shareToGroup(any(), any());
        verify(financeGroupPort, never()).findCurrentGroupId(any());
    }

    @Test
    void 공유전환_실패시_개인레코드를_삭제하고_failures로_카운트한다() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        AssetSnapshot saved = mock(AssetSnapshot.class);
        UUID savedId = UUID.randomUUID();
        when(saved.id()).thenReturn(savedId);
        when(assetSnapshotUseCase.create(eq(userId), isNull(), any())).thenReturn(saved);
        doThrow(new IllegalStateException("이미 다른 그룹에 공유된 항목입니다"))
                .when(assetSnapshotUseCase).shareToGroup(eq(savedId), eq(userId));

        BulkFinanceRegisterResult result = service.register(userId, true, List.of(asset("자산1")), List.of());

        assertThat(result.assetSuccessCount()).isZero();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0)).contains("등록 취소");
        verify(assetSnapshotUseCase, times(1)).delete(savedId, userId);
    }

    @Test
    void 거래_공유전환_실패시_방금만든_거래를_id로_삭제한다() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        FinanceTransaction saved = mock(FinanceTransaction.class);
        UUID savedId = UUID.randomUUID();
        when(saved.id()).thenReturn(savedId);
        when(transactionUseCase.create(eq(userId), isNull(), any())).thenReturn(saved);
        doThrow(new IllegalStateException("이미 다른 그룹에 공유된 항목입니다"))
                .when(transactionUseCase).shareToGroup(eq(savedId), eq(userId));

        BulkFinanceRegisterResult result = service.register(userId, true, List.of(), List.of(tx("거래1")));

        assertThat(result.transactionSuccessCount()).isZero();
        assertThat(result.failures()).hasSize(1);
        verify(transactionUseCase, times(1)).delete(savedId, userId);
    }

    @Test
    void 공유전환_실패후_삭제마저_실패하면_best_effort로_남기고_수동확인_뉘앙스를_남긴다() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        AssetSnapshot saved = mock(AssetSnapshot.class);
        UUID savedId = UUID.randomUUID();
        when(saved.id()).thenReturn(savedId);
        when(assetSnapshotUseCase.create(eq(userId), isNull(), any())).thenReturn(saved);
        doThrow(new IllegalStateException("공유 전환 실패"))
                .when(assetSnapshotUseCase).shareToGroup(eq(savedId), eq(userId));
        doThrow(new RuntimeException("삭제 실패"))
                .when(assetSnapshotUseCase).delete(savedId, userId);

        BulkFinanceRegisterResult result = service.register(userId, true, List.of(asset("자산1")), List.of());

        assertThat(result.assetSuccessCount()).isZero();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0)).contains("수동 확인 필요");
    }
}
