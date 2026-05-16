package com.kista.application.service;

import com.kista.domain.model.*;
import com.kista.domain.port.in.RegisterAccountUseCase;
import com.kista.domain.port.in.UpdateAccountUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.KisTokenPort;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService 단위 테스트")
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock UserRepository userRepository;
    @Mock UserNotificationPort notificationPort;
    @Mock KisTokenPort kisTokenPort;
    @InjectMocks AccountService accountService;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private Account activeAccount(UUID ownerId) {
        return new Account(accountId, ownerId, "테스트계좌",
                "74420614", "appKey", "appSecret", "01",
                StrategyType.INFINITE, StrategyStatus.ACTIVE,
                null, null, Ticker.SOXL, Instant.now(), Instant.now());
    }

    private Account pausedAccount(UUID ownerId) {
        return new Account(accountId, ownerId, "테스트계좌",
                "74420614", "appKey", "appSecret", "01",
                StrategyType.INFINITE, StrategyStatus.PAUSED,
                null, null, Ticker.SOXL, Instant.now(), Instant.now());
    }

    private User activeUser(UUID id) {
        return new User(id, "kakao-123", "홍길동", UserStatus.ACTIVE,
                null, null, Instant.now(), Instant.now(), null);
    }

    private RegisterAccountUseCase.Command registerCmd() {
        return new RegisterAccountUseCase.Command(
                "테스트계좌", "74420614", "appKey", "appSecret",
                "01", StrategyType.INFINITE, null, null, Ticker.SOXL
        );
    }

    @Test
    @DisplayName("계좌 등록 성공")
    void register_success() {
        doNothing().when(kisTokenPort).testToken(any(), any()); // KIS 키 검증 통과
        when(accountRepository.countByUserId(userId)).thenReturn(0);
        when(accountRepository.save(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            return new Account(UUID.randomUUID(), a.userId(), a.nickname(),
                    a.accountNo(), a.kisAppKey(), a.kisSecretKey(),
                    a.kisAccountType(), a.strategyType(), a.strategyStatus(),
                    null, null, a.ticker(), a.createdAt(), a.updatedAt());
        });

        Account result = accountService.register(userId, registerCmd());

        assertThat(result.strategyType()).isEqualTo(StrategyType.INFINITE);
        assertThat(result.strategyStatus()).isEqualTo(StrategyStatus.ACTIVE);
        verify(accountRepository).save(any());
    }

    @Test
    @DisplayName("계좌 10개 초과 시 IllegalStateException 발생")
    void register_exceeds_limit_throws() {
        doNothing().when(kisTokenPort).testToken(any(), any()); // KIS 키 검증 통과
        when(accountRepository.countByUserId(userId)).thenReturn(10);

        assertThatThrownBy(() -> accountService.register(userId, registerCmd()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("타 사용자 계좌 수정 시 SecurityException 발생 (→ 403)")
    void update_by_non_owner_throws_forbidden() {
        UUID otherId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(otherId)));

        UpdateAccountUseCase.Command cmd = new UpdateAccountUseCase.Command("변경닉네임", null, null, null, null, null);

        assertThatThrownBy(() -> accountService.update(accountId, userId, cmd))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("본인 계좌 수정 성공")
    void update_by_owner_success() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(userId)));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateAccountUseCase.Command cmd = new UpdateAccountUseCase.Command("변경닉네임", null, null, null, null, null);
        Account result = accountService.update(accountId, userId, cmd);

        assertThat(result.nickname()).isEqualTo("변경닉네임");
    }

    @Test
    @DisplayName("타 사용자 계좌 삭제 시 SecurityException 발생 (→ 403)")
    void delete_by_non_owner_throws_forbidden() {
        UUID otherId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(otherId)));

        assertThatThrownBy(() -> accountService.delete(accountId, userId))
                .isInstanceOf(SecurityException.class);

        verify(accountRepository, never()).delete(any(UUID.class));
    }

    @Test
    @DisplayName("본인 계좌 삭제 성공")
    void delete_by_owner_success() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(userId)));

        accountService.delete(accountId, userId);

        verify(accountRepository).delete(accountId);
    }

    @Test
    @DisplayName("존재하지 않는 계좌 수정 시 NoSuchElementException 발생 (→ 404)")
    void update_not_found_throws() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.update(accountId, userId,
                new UpdateAccountUseCase.Command("닉", null, null, null, null, null)))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("pause: ACTIVE → PAUSED 저장 + 관리자 알림")
    void pause_changes_status_and_notifies() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(userId)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.pause(accountId, userId);

        verify(accountRepository).save(argThat(a -> a.strategyStatus() == StrategyStatus.PAUSED));
        verify(notificationPort).notifyStrategyChanged(any(), any(), eq("중지"));
    }

    @Test
    @DisplayName("resume: PAUSED → ACTIVE 저장 + 관리자 알림")
    void resume_changes_status_and_notifies() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(pausedAccount(userId)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.resume(accountId, userId);

        verify(accountRepository).save(argThat(a -> a.strategyStatus() == StrategyStatus.ACTIVE));
        verify(notificationPort).notifyStrategyChanged(any(), any(), eq("재개"));
    }

    @Test
    @DisplayName("pause: 타 사용자 계좌 시 SecurityException(→403)")
    void pause_by_non_owner_throws_forbidden() {
        UUID otherId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(otherId)));

        assertThatThrownBy(() -> accountService.pause(accountId, userId))
                .isInstanceOf(SecurityException.class);

        verify(accountRepository, never()).save(any());
        verify(notificationPort, never()).notifyStrategyChanged(any(), any(), any());
    }

    @Test
    @DisplayName("update: kisAppKey 변경 시 testToken 호출")
    void update_키변경시_testToken호출() {
        // given: 기존 계좌
        Account existing = activeAccount(userId); // appKey="appKey", appSecret="appSecret"
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(existing));
        doNothing().when(kisTokenPort).testToken(any(), any());
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // when: kisAppKey만 변경
        UpdateAccountUseCase.Command cmd = new UpdateAccountUseCase.Command(
                "새닉네임", "newAppKey", null, null, null, null
        );
        accountService.update(accountId, userId, cmd);

        // then: 새 appKey + 기존 secretKey 조합으로 testToken 호출
        verify(kisTokenPort).testToken("newAppKey", existing.kisSecretKey());
    }
}
