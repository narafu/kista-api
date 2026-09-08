package com.kista.finance.application.service;

import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.model.FinanceTransaction;
import com.kista.finance.domain.model.FinanceTransactionCommand;
import com.kista.finance.domain.model.MonthlyClosing;
import com.kista.finance.application.port.output.FinanceCategoryPort;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.port.output.FinanceTransactionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinanceTransactionService 단위 테스트")
class FinanceTransactionServiceTest {

    @Mock FinanceTransactionPort transactionPort;
    @Mock FinanceGroupPort financeGroupPort;
    @Mock FinanceCategoryPort financeCategoryPort;
    @Mock MonthlyClosingGuard monthlyClosingGuard;
    @InjectMocks FinanceTransactionService transactionService;

    private static final LocalDate CLOSED_DATE = LocalDate.of(2026, 1, 15);  // personalTransaction의 transactionDate
    private static final LocalDate COMMAND_DATE = LocalDate.of(2026, 2, 1);  // command()의 transactionDate

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    private FinanceTransaction personalTransaction() {
        return new FinanceTransaction(transactionId, null, categoryId, userId,
                LocalDate.of(2026, 1, 15), 50_000L, "점심", null);
    }

    private FinanceTransactionCommand command() {
        return new FinanceTransactionCommand(categoryId, LocalDate.of(2026, 2, 1), 30_000L, "저녁");
    }

    private FinanceCategory usableCategory() {
        return new FinanceCategory(categoryId, null, null, userId, FinanceCategory.Type.EXPENSE, "식비", 0, null);
    }

    @Test
    @DisplayName("list는 findCurrentGroupId로 얻은 currentGroupId로 조회")
    void list_queriesWithCurrentGroupId() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(transactionPort.findMyScope(userId, groupId, null, null, null, null))
                .thenReturn(List.of(personalTransaction()));

        List<FinanceTransaction> result = transactionService.list(userId, null, null, null, null, null);

        assertThat(result).hasSize(1);
        verify(transactionPort).findMyScope(userId, groupId, null, null, null, null);
    }

    @Test
    @DisplayName("create는 shareToGroup=false면 개인 소유(groupId=null)로 저장")
    void create_shareToGroupFalse_savesAsPersonalOwnership() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(transactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceTransaction result = transactionService.create(userId, false, command());

        assertThat(result.groupId()).isNull();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.amount()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("create는 shareToGroup=true면 현재 그룹 소유(groupId=currentGroupId)로 저장")
    void create_shareToGroupTrue_savesAsGroupOwnership() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(transactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceTransaction result = transactionService.create(userId, true, command());

        assertThat(result.groupId()).isEqualTo(groupId);
        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("create는 shareToGroup=true인데 무그룹 유저면 IllegalStateException")
    void create_shareToGroupTrue_noGroup_throwsIllegalState() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());

        assertThatThrownBy(() -> transactionService.create(userId, true, command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("소속된 그룹이 없습니다");
        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("update는 load-then-verify 패턴")
    void update_loadsThenVerifiesAccess() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(personalTransaction());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(transactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceTransaction result = transactionService.update(transactionId, userId, command());

        assertThat(result.amount()).isEqualTo(30_000L);
        assertThat(result.userId()).isEqualTo(userId); // 기존 소유자 유지
    }

    @Test
    @DisplayName("ASSET 타입 카테고리는 거래내역에 사용할 수 없음")
    void create_assetTypeCategory_rejected() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        FinanceCategory assetCategory = new FinanceCategory(categoryId, null, null, userId,
                FinanceCategory.Type.ASSET, "투자", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(assetCategory);

        assertThatThrownBy(() -> transactionService.create(userId, false, command()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("접근 불가한 카테고리를 지정하면 SecurityException")
    void create_inaccessibleCategory_rejected() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        FinanceCategory othersPersonalCategory = new FinanceCategory(categoryId, null, null, UUID.randomUUID(),
                FinanceCategory.Type.EXPENSE, "식비", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(othersPersonalCategory);

        assertThatThrownBy(() -> transactionService.create(userId, false, command()))
                .isInstanceOf(SecurityException.class);
        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("delete는 load-then-verify 후 softDelete 호출")
    void delete_callsSoftDelete() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(personalTransaction());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        transactionService.delete(transactionId, userId);

        verify(transactionPort).softDelete(transactionId);
    }

    @Test
    @DisplayName("delete 시 접근 불가한 거래내역이면 SecurityException")
    void delete_notAccessible_throwsSecurityException() {
        FinanceTransaction othersTransaction = new FinanceTransaction(transactionId, null, categoryId, UUID.randomUUID(),
                LocalDate.of(2026, 1, 15), 50_000L, "점심", null);
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(othersTransaction);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.delete(transactionId, userId))
                .isInstanceOf(SecurityException.class);

        verify(transactionPort, never()).softDelete(any());
    }

    // ----- shareToGroup -----

    @Test
    @DisplayName("shareToGroup은 본인 소유 개인 거래내역을 현재 그룹으로 전환")
    void shareToGroup_ownedPersonalTransaction_movesToCurrentGroup() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(personalTransaction());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(transactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceTransaction result = transactionService.shareToGroup(transactionId, userId);

        assertThat(result.groupId()).isEqualTo(groupId);
    }

    @Test
    @DisplayName("shareToGroup은 본인 소유가 아니면 SecurityException")
    void shareToGroup_notOwner_throwsSecurityException() {
        FinanceTransaction othersTransaction = new FinanceTransaction(transactionId, null, categoryId, UUID.randomUUID(),
                LocalDate.of(2026, 1, 15), 50_000L, "점심", null);
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(othersTransaction);

        assertThatThrownBy(() -> transactionService.shareToGroup(transactionId, userId))
                .isInstanceOf(SecurityException.class);

        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("shareToGroup은 무그룹 유저면 IllegalStateException")
    void shareToGroup_noCurrentGroup_throwsIllegalState() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(personalTransaction());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.shareToGroup(transactionId, userId))
                .isInstanceOf(IllegalStateException.class);

        verify(transactionPort, never()).save(any());
    }

    // ----- unshare -----

    @Test
    @DisplayName("unshare는 그룹 공유 거래내역을 개인 소유로 되돌리고 소유자는 유지")
    void unshare_groupSharedTransaction_movesToPersonalKeepingOwner() {
        UUID ownerId = UUID.randomUUID();
        FinanceTransaction sharedTransaction = new FinanceTransaction(transactionId, groupId, categoryId, ownerId,
                LocalDate.of(2026, 1, 15), 50_000L, "점심", null);
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(sharedTransaction);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(transactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceTransaction result = transactionService.unshare(transactionId, userId);

        assertThat(result.groupId()).isNull();
        assertThat(result.userId()).isEqualTo(ownerId);
    }

    @Test
    @DisplayName("unshare는 같은 그룹 소속이면 소유자가 아니어도 허용")
    void unshare_nonOwnerSameGroupMember_allowed() {
        UUID ownerId = UUID.randomUUID();
        FinanceTransaction sharedTransaction = new FinanceTransaction(transactionId, groupId, categoryId, ownerId,
                LocalDate.of(2026, 1, 15), 50_000L, "점심", null);
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(sharedTransaction);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(transactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceTransaction result = transactionService.unshare(transactionId, userId);

        assertThat(result.groupId()).isNull();
    }

    @Test
    @DisplayName("unshare는 소유자도 아니고 같은 그룹도 아니면 SecurityException")
    void unshare_notOwnerAndNotSameGroup_throwsSecurityException() {
        FinanceTransaction sharedTransaction = new FinanceTransaction(transactionId, UUID.randomUUID(), categoryId, UUID.randomUUID(),
                LocalDate.of(2026, 1, 15), 50_000L, "점심", null);
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(sharedTransaction);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        assertThatThrownBy(() -> transactionService.unshare(transactionId, userId))
                .isInstanceOf(SecurityException.class);

        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("unshare는 이미 개인 소유면 그대로 반환(멱등)")
    void unshare_alreadyPersonal_isIdempotent() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(personalTransaction());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        FinanceTransaction result = transactionService.unshare(transactionId, userId);

        assertThat(result.groupId()).isNull();
        verify(transactionPort, never()).save(any());
    }

    // ----- 기록 점검 완료월 쓰기 차단 -----

    @Test
    @DisplayName("create는 대상 월이 마감됐으면 MonthClosedException")
    void create_closedMonth_throwsMonthClosed() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        doThrow(new MonthlyClosing.MonthClosedException("2026-02"))
                .when(monthlyClosingGuard).verifyMonthOpen(any(), any(), eq(COMMAND_DATE));

        assertThatThrownBy(() -> transactionService.create(userId, false, command()))
                .isInstanceOf(MonthlyClosing.MonthClosedException.class);
        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("update는 기존 거래가 마감월에 있으면 차단(마감월에서 빼내기 방지)")
    void update_existingInClosedMonth_throwsMonthClosed() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(personalTransaction());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        doThrow(new MonthlyClosing.MonthClosedException("2026-01"))
                .when(monthlyClosingGuard).verifyMonthOpen(any(), any(), eq(CLOSED_DATE));

        assertThatThrownBy(() -> transactionService.update(transactionId, userId, command()))
                .isInstanceOf(MonthlyClosing.MonthClosedException.class);
        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("update는 대상 월(command)이 마감됐으면 차단(마감월로 넣기 방지)")
    void update_commandTargetsClosedMonth_throwsMonthClosed() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(personalTransaction());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        doNothing().when(monthlyClosingGuard).verifyMonthOpen(any(), any(), eq(CLOSED_DATE)); // 기존 거래 월은 정상
        doThrow(new MonthlyClosing.MonthClosedException("2026-02"))
                .when(monthlyClosingGuard).verifyMonthOpen(any(), any(), eq(COMMAND_DATE));

        assertThatThrownBy(() -> transactionService.update(transactionId, userId, command()))
                .isInstanceOf(MonthlyClosing.MonthClosedException.class);
        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("delete는 거래가 마감월에 있으면 차단")
    void delete_closedMonth_throwsMonthClosed() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(personalTransaction());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        doThrow(new MonthlyClosing.MonthClosedException("2026-01"))
                .when(monthlyClosingGuard).verifyMonthOpen(any(), any(), eq(CLOSED_DATE));

        assertThatThrownBy(() -> transactionService.delete(transactionId, userId))
                .isInstanceOf(MonthlyClosing.MonthClosedException.class);
        verify(transactionPort, never()).softDelete(any());
    }

    @Test
    @DisplayName("shareToGroup은 거래가 마감월에 있으면 차단")
    void shareToGroup_closedMonth_throwsMonthClosed() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(personalTransaction());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        doThrow(new MonthlyClosing.MonthClosedException("2026-01"))
                .when(monthlyClosingGuard).verifyMonthOpen(any(), any(), eq(CLOSED_DATE));

        assertThatThrownBy(() -> transactionService.shareToGroup(transactionId, userId))
                .isInstanceOf(MonthlyClosing.MonthClosedException.class);
        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("unshare는 거래가 마감월에 있으면 차단")
    void unshare_closedMonth_throwsMonthClosed() {
        FinanceTransaction shared = new FinanceTransaction(transactionId, groupId, categoryId, userId,
                CLOSED_DATE, 50_000L, "점심", null);
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(shared);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        doThrow(new MonthlyClosing.MonthClosedException("2026-01"))
                .when(monthlyClosingGuard).verifyMonthOpen(any(), any(), eq(CLOSED_DATE));

        assertThatThrownBy(() -> transactionService.unshare(transactionId, userId))
                .isInstanceOf(MonthlyClosing.MonthClosedException.class);
        verify(transactionPort, never()).save(any());
    }
}
