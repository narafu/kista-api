package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.model.finance.FinanceTransaction;
import com.kista.domain.model.finance.FinanceTransactionCommand;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import com.kista.domain.port.out.FinanceTransactionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
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
    @InjectMocks FinanceTransactionService transactionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    private FinanceTransaction existingTransaction() {
        return new FinanceTransaction(transactionId, groupId, categoryId, userId,
                LocalDate.of(2026, 1, 15), 50_000L, "점심", null);
    }

    private FinanceTransactionCommand command() {
        return new FinanceTransactionCommand(categoryId, LocalDate.of(2026, 2, 1), 30_000L, "저녁");
    }

    // create/update는 저장 전에 categoryId 소유권·타입을 검증하므로 그 경로를 타는 테스트는 이 그룹 소유의
    // 비-ASSET(EXPENSE) 카테고리를 stub해야 한다.
    private FinanceCategory usableCategory() {
        return new FinanceCategory(categoryId, groupId, null, userId, FinanceCategory.Type.EXPENSE, "식비", 0, null);
    }

    @Test
    @DisplayName("list는 resolveGroupId로 얻은 groupId로 조회")
    void list_resolvesGroupId() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(transactionPort.findByGroupId(groupId, null, null, null, null))
                .thenReturn(List.of(existingTransaction()));

        List<FinanceTransaction> result = transactionService.list(userId, null, null, null, null, null);

        assertThat(result).hasSize(1);
        verify(financeGroupPort).resolveGroupId(userId, null);
    }

    @Test
    @DisplayName("create는 resolveGroupId 호출 후 거래내역 저장")
    void create_resolvesGroupIdThenSaves() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(transactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceTransaction result = transactionService.create(userId, null, command());

        assertThat(result.groupId()).isEqualTo(groupId);
        assertThat(result.createdBy()).isEqualTo(userId);
        assertThat(result.amount()).isEqualTo(30_000L);
        verify(financeGroupPort).resolveGroupId(userId, null);
    }

    @Test
    @DisplayName("update는 load-then-verify-membership 패턴")
    void update_loadsThenVerifiesMembership() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(existingTransaction());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(transactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceTransaction result = transactionService.update(transactionId, userId, command());

        assertThat(result.amount()).isEqualTo(30_000L);
        assertThat(result.createdBy()).isEqualTo(userId); // 기존 createdBy 유지
        verify(financeGroupPort).resolveGroupId(userId, groupId);
    }

    @Test
    @DisplayName("ASSET 타입 카테고리는 거래내역에 사용할 수 없음")
    void create_assetTypeCategory_rejected() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        FinanceCategory assetCategory = new FinanceCategory(categoryId, groupId, null, userId,
                FinanceCategory.Type.ASSET, "투자", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(assetCategory);

        assertThatThrownBy(() -> transactionService.create(userId, null, command()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("다른 그룹 소유 카테고리를 지정하면 SecurityException")
    void create_categoryFromOtherGroup_rejected() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        FinanceCategory otherGroupCategory = new FinanceCategory(categoryId, UUID.randomUUID(), null, userId,
                FinanceCategory.Type.EXPENSE, "식비", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(otherGroupCategory);

        assertThatThrownBy(() -> transactionService.create(userId, null, command()))
                .isInstanceOf(SecurityException.class);
        verify(transactionPort, never()).save(any());
    }

    @Test
    @DisplayName("delete는 load-then-verify-membership 후 softDelete 호출")
    void delete_callsSoftDelete() {
        when(transactionPort.findByIdOrThrow(transactionId)).thenReturn(existingTransaction());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);

        transactionService.delete(transactionId, userId);

        verify(financeGroupPort).resolveGroupId(userId, groupId);
        verify(transactionPort).softDelete(transactionId);
    }
}
