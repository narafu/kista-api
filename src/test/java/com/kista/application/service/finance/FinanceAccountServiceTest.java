package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceAccount;
import com.kista.domain.model.finance.FinanceAccountCommand;
import com.kista.domain.port.out.FinanceAccountPort;
import com.kista.domain.port.out.FinanceGroupPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinanceAccountService 단위 테스트")
class FinanceAccountServiceTest {

    @Mock FinanceAccountPort accountPort;
    @Mock FinanceGroupPort financeGroupPort;
    @InjectMocks FinanceAccountService accountService;

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private FinanceAccount existingAccount() {
        return new FinanceAccount(accountId, groupId, userId, FinanceAccount.Type.SECURITIES,
                "토스증권", "1234", null, null);
    }

    private FinanceAccountCommand command() {
        return new FinanceAccountCommand(FinanceAccount.Type.BANK, "카카오뱅크", "5678", "메모");
    }

    @Test
    @DisplayName("list는 resolveGroupId로 얻은 groupId로 조회")
    void list_resolvesGroupId() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(accountPort.findByGroupId(groupId)).thenReturn(List.of(existingAccount()));

        List<FinanceAccount> result = accountService.list(userId, null);

        assertThat(result).hasSize(1);
        verify(financeGroupPort).resolveGroupId(userId, null);
        verify(accountPort).findByGroupId(groupId);
    }

    @Test
    @DisplayName("create는 resolveGroupId 호출 후 계좌 저장")
    void create_resolvesGroupIdThenSaves() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceAccount result = accountService.create(userId, null, command());

        assertThat(result.groupId()).isEqualTo(groupId);
        assertThat(result.createdBy()).isEqualTo(userId);
        assertThat(result.name()).isEqualTo("카카오뱅크");
        verify(financeGroupPort).resolveGroupId(userId, null);
    }

    @Test
    @DisplayName("update는 load-then-verify-membership 패턴 — 기존 계좌의 groupId로 멤버십 검증")
    void update_loadsThenVerifiesMembership() {
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(existingAccount());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceAccount result = accountService.update(accountId, userId, command());

        assertThat(result.name()).isEqualTo("카카오뱅크");
        assertThat(result.id()).isEqualTo(accountId);
        assertThat(result.createdBy()).isEqualTo(userId); // 기존 createdBy 유지
        verify(accountPort).findByIdOrThrow(accountId);
        verify(financeGroupPort).resolveGroupId(userId, groupId);
    }

    @Test
    @DisplayName("delete는 load-then-verify-membership 후 softDelete 호출")
    void delete_loadsThenVerifiesMembershipThenSoftDeletes() {
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(existingAccount());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);

        accountService.delete(accountId, userId);

        verify(financeGroupPort).resolveGroupId(userId, groupId);
        verify(accountPort).softDelete(accountId);
    }

    @Test
    @DisplayName("create 중 계좌명 중복 시 DuplicateNameException이 그대로 전파됨")
    void create_duplicateName_propagatesUntouched() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(accountPort.save(any())).thenThrow(new FinanceAccount.DuplicateNameException("카카오뱅크"));

        assertThatThrownBy(() -> accountService.create(userId, null, command()))
                .isInstanceOf(FinanceAccount.DuplicateNameException.class)
                .hasMessageContaining("카카오뱅크");
    }

    @Test
    @DisplayName("update 중 계좌명 중복 시 DuplicateNameException이 그대로 전파됨")
    void update_duplicateName_propagatesUntouched() {
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(existingAccount());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);
        when(accountPort.save(any())).thenThrow(new FinanceAccount.DuplicateNameException("카카오뱅크"));

        assertThatThrownBy(() -> accountService.update(accountId, userId, command()))
                .isInstanceOf(FinanceAccount.DuplicateNameException.class)
                .hasMessageContaining("카카오뱅크");
    }
}
