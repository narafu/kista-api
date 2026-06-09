package com.kista.application.service.account;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.model.account.UpdateAccountCommand;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.TradingCyclePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService 단위 테스트")
class AccountServiceTest {

    @Mock AccountPort accountPort;
    @Mock TradingCyclePort cyclePort;
    @InjectMocks AccountService accountService;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    // Account 10개 필드 생성자 (strategyType/strategyStatus/ticker/multiple 제거)
    private Account activeAccount(UUID ownerId) {
        return new Account(accountId, ownerId, "테스트계좌",
                "74420614", "appKey", "appSecret", "01",
                Account.Broker.KIS);
    }

    private RegisterAccountCommand registerCmd() {
        return new RegisterAccountCommand(
                "테스트계좌", "74420614", "appKey", "appSecret", "01"
        );
    }

    @Test
    @DisplayName("계좌 등록 성공")
    void register_success() {
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            return new Account(UUID.randomUUID(), a.userId(), a.nickname(),
                    a.accountNo(), a.kisAppKey(), a.kisSecretKey(),
                    a.kisAccountType(), a.broker());
        });

        Account result = accountService.register(userId, registerCmd());

        assertThat(result.id()).isNotNull();
        assertThat(result.broker()).isEqualTo(Account.Broker.KIS);
        verify(accountPort).save(any());
    }

    @Test
    @DisplayName("계좌 10개 초과 시 IllegalStateException 발생")
    void register_exceeds_limit_throws() {
        when(accountPort.countByUserId(userId)).thenReturn(10);

        assertThatThrownBy(() -> accountService.register(userId, registerCmd()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("타 사용자 계좌 수정 시 SecurityException 발생 (→ 403)")
    void update_by_non_owner_throws_forbidden() {
        when(accountPort.requireOwnedAccount(accountId, userId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        UpdateAccountCommand cmd = new UpdateAccountCommand("변경닉네임");

        assertThatThrownBy(() -> accountService.update(accountId, userId, cmd))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("본인 계좌 수정 성공")
    void update_by_owner_success() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(activeAccount(userId));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateAccountCommand cmd = new UpdateAccountCommand("변경닉네임");
        Account result = accountService.update(accountId, userId, cmd);

        assertThat(result.nickname()).isEqualTo("변경닉네임");
    }

    @Test
    @DisplayName("타 사용자 계좌 삭제 시 SecurityException 발생 (→ 403)")
    void delete_by_non_owner_throws_forbidden() {
        when(accountPort.requireOwnedAccount(accountId, userId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> accountService.delete(accountId, userId))
                .isInstanceOf(SecurityException.class);

        verify(accountPort, never()).delete(any(UUID.class));
    }

    @Test
    @DisplayName("본인 계좌 삭제 성공")
    void delete_by_owner_success() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(activeAccount(userId));

        accountService.delete(accountId, userId);

        verify(accountPort).delete(accountId);
    }

    @Test
    @DisplayName("존재하지 않는 계좌 수정 시 NoSuchElementException 발생 (→ 404)")
    void update_not_found_throws() {
        when(accountPort.requireOwnedAccount(accountId, userId))
                .thenThrow(new NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));

        assertThatThrownBy(() -> accountService.update(accountId, userId,
                new UpdateAccountCommand("닉")))
                .isInstanceOf(NoSuchElementException.class);
    }


}
