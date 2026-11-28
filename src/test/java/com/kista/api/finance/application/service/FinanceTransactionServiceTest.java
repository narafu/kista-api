package com.kista.api.finance.application.service;

import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FinanceTransaction;
import com.kista.api.finance.domain.model.FinanceTransactionCommand;
import com.kista.api.finance.domain.model.FinanceTransactionCursor;
import com.kista.api.finance.domain.model.FinanceTransactionListFilter;
import com.kista.api.finance.domain.model.FinanceTransactionPageQuery;
import com.kista.api.finance.domain.model.FinanceTransactionPageRow;
import com.kista.api.finance.domain.model.FinanceTransactionSort;
import com.kista.shared.platform.page.CursorPage;
import com.kista.shared.platform.page.PageQuery;
import com.kista.api.finance.domain.model.MonthlyClosing;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
import com.kista.api.finance.application.port.output.FinanceGroupPort;
import com.kista.api.finance.application.port.output.FinanceTransactionPort;
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

    private static FinanceTransactionListFilter filter(FinanceCategory.Type type, UUID categoryId, String memo) {
        return new FinanceTransactionListFilter(null, null, type, categoryId, memo, null);
    }

    private FinanceCategory category(UUID id, UUID parentId, FinanceCategory.Type type, String name) {
        return new FinanceCategory(id, null, parentId, userId, type, name, 0, null);
    }

    @Test
    @DisplayName("list: type·categoryId 없으면 categoryIds=null, memo는 trim, 한 건 더 읽어 hasMore 판정")
    void list_noCategoryFilter_passesNullAndBuildsNextCursor() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        FinanceTransaction t1 = new FinanceTransaction(UUID.randomUUID(), null, categoryId, userId, LocalDate.of(2026, 1, 15), 10L, "a", null);
        FinanceTransaction t2 = new FinanceTransaction(UUID.randomUUID(), null, categoryId, userId, LocalDate.of(2026, 1, 14), 20L, "b", null);
        when(transactionPort.findPage(any())).thenReturn(List.of(
                new FinanceTransactionPageRow(t1, "식비"), new FinanceTransactionPageRow(t2, "식비")));

        CursorPage<FinanceTransaction> page = transactionService.list(userId, filter(null, null, "  점심 "),
                FinanceTransactionSort.DEFAULT, new PageQuery(null, 1));

        var captor = org.mockito.ArgumentCaptor.forClass(FinanceTransactionPageQuery.class);
        verify(transactionPort).findPage(captor.capture());
        assertThat(captor.getValue().categoryIds()).isNull();
        assertThat(captor.getValue().memo()).isEqualTo("점심");
        assertThat(captor.getValue().limit()).isEqualTo(2);
        assertThat(captor.getValue().groupId()).isEqualTo(groupId);
        assertThat(page.items()).containsExactly(t1);
        assertThat(page.hasMore()).isTrue();
        // 다음 커서는 마지막 반환 행(t1)의 정렬 값
        assertThat(FinanceTransactionCursor.decode(page.nextCursor(), FinanceTransactionSort.DEFAULT))
                .isEqualTo(new FinanceTransactionCursor(FinanceTransactionSort.DEFAULT, "2026-01-15", t1.id()));
    }

    @Test
    @DisplayName("list: 정렬 키별 커서 값 — amount·memo(빈 메모는 null)·category")
    void list_cursorValuePerSortKey() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        FinanceTransaction t = new FinanceTransaction(UUID.randomUUID(), null, categoryId, userId, LocalDate.of(2026, 1, 15), 777L, "", null);
        when(transactionPort.findPage(any())).thenReturn(List.of(new FinanceTransactionPageRow(t, "식비"), new FinanceTransactionPageRow(t, "식비")));
        var amount = new FinanceTransactionSort(FinanceTransactionSort.Key.AMOUNT, true);
        var memo = new FinanceTransactionSort(FinanceTransactionSort.Key.MEMO, true);
        var cat = new FinanceTransactionSort(FinanceTransactionSort.Key.CATEGORY, false);

        assertThat(FinanceTransactionCursor.decode(transactionService.list(userId, filter(null, null, null), amount, new PageQuery(null, 1)).nextCursor(), amount).value()).isEqualTo("777");
        assertThat(FinanceTransactionCursor.decode(transactionService.list(userId, filter(null, null, null), memo, new PageQuery(null, 1)).nextCursor(), memo).value()).isNull();
        assertThat(FinanceTransactionCursor.decode(transactionService.list(userId, filter(null, null, null), cat, new PageQuery(null, 1)).nextCursor(), cat).value()).isEqualTo("식비");
    }

    @Test
    @DisplayName("list: categoryId는 하위 트리 포함·형제 제외, type 교집합")
    void list_categoryIdSubtree_andTypeIntersection() {
        UUID l1 = UUID.randomUUID(), l2 = UUID.randomUUID(), l3 = UUID.randomUUID(), sibling = UUID.randomUUID(), income = UUID.randomUUID();
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findSelectable(userId, null, null)).thenReturn(List.of(
                category(l1, null, FinanceCategory.Type.EXPENSE, "L1"), category(l2, l1, FinanceCategory.Type.EXPENSE, "L2"),
                category(l3, l2, FinanceCategory.Type.EXPENSE, "L3"), category(sibling, null, FinanceCategory.Type.EXPENSE, "S"),
                category(income, null, FinanceCategory.Type.INCOME, "I")));
        when(transactionPort.findPage(any())).thenReturn(List.of());

        transactionService.list(userId, filter(null, l1, null), FinanceTransactionSort.DEFAULT, new PageQuery(null, 10));
        transactionService.list(userId, filter(FinanceCategory.Type.EXPENSE, null, null), FinanceTransactionSort.DEFAULT, new PageQuery(null, 10));

        var captor = org.mockito.ArgumentCaptor.forClass(FinanceTransactionPageQuery.class);
        verify(transactionPort, times(2)).findPage(captor.capture());
        assertThat(captor.getAllValues().get(0).categoryIds()).containsExactlyInAnyOrder(l1, l2, l3);
        assertThat(captor.getAllValues().get(1).categoryIds()).containsExactlyInAnyOrder(l1, l2, l3, sibling);
    }

    @Test
    @DisplayName("list: categoryId가 선택 목록 밖이어도 자기 자신은 포트로 전달")
    void list_categoryIdOutsideSelectable_stillPassed() {
        UUID outside = UUID.randomUUID();
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findSelectable(userId, null, null)).thenReturn(List.of());
        when(transactionPort.findPage(any())).thenReturn(List.of());

        transactionService.list(userId, filter(null, outside, null), FinanceTransactionSort.DEFAULT, new PageQuery(null, 10));

        var captor = org.mockito.ArgumentCaptor.forClass(FinanceTransactionPageQuery.class);
        verify(transactionPort).findPage(captor.capture());
        assertThat(captor.getValue().categoryIds()).containsExactly(outside);
    }

    @Test
    @DisplayName("list: 교집합이 비면 포트를 부르지 않고 빈 페이지")
    void list_emptyIntersection_skipsPort() {
        UUID income = UUID.randomUUID();
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findSelectable(userId, null, null)).thenReturn(List.of(category(income, null, FinanceCategory.Type.INCOME, "I")));

        CursorPage<FinanceTransaction> page = transactionService.list(userId, filter(FinanceCategory.Type.EXPENSE, income, null),
                FinanceTransactionSort.DEFAULT, new PageQuery(null, 10));

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        verify(transactionPort, never()).findPage(any());
    }

    @Test
    @DisplayName("list: type=ASSET이면 IAE")
    void list_assetType_throws() {
        assertThatThrownBy(() -> transactionService.list(userId, filter(FinanceCategory.Type.ASSET, null, null),
                FinanceTransactionSort.DEFAULT, new PageQuery(null, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("자산 유형은 거래 목록에 사용할 수 없습니다.");
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
                .hasMessage("소속된 그룹이 없습니다.");
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
