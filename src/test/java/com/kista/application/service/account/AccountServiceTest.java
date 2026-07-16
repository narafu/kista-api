package com.kista.application.service.account;

import com.kista.application.service.broker.BrokerConnectionTesters;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.model.account.UpdateAccountCommand;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.RuntimeSettingsPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.broker.BrokerConnectionTestPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.EnumMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService 단위 테스트")
class AccountServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort cyclePort;
    @Mock BrokerConnectionTesters connectionTesters; // 증권사별 연결테스트 라우터
    @Mock BrokerConnectionTestPort connectionTester;  // 라우터가 반환하는 실제 포트 mock
    @Mock RuntimeSettingsPort runtimeSettingsPort; // 증권사 신규 등록 허용 설정 저장소
    @InjectMocks AccountService accountService;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    // Account 10개 필드 생성자 (strategyType/strategyStatus/ticker/multiple 제거)
    private Account activeAccount(UUID ownerId) {
        return new Account(accountId, ownerId, "테스트계좌",
                "74420614", "appKey", "appSecret", null,
                Account.Broker.KIS, null);
    }

    private RegisterAccountCommand registerCmd() {
        return new RegisterAccountCommand(
                "테스트계좌", "74420614", "appKey", "appSecret", "01", Account.Broker.KIS
        );
    }

    @Test
    @DisplayName("계좌 등록 성공")
    void register_success() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.KIS, true));
        when(connectionTesters.of(any())).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("appKey", "appSecret", "74420614")).thenReturn(null);
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            return new Account(UUID.randomUUID(), a.userId(), a.nickname(),
                    a.accountNo(), a.appKey(), a.secretKey(),
                    a.brokerAccountCode(), a.broker(), null);
        });

        Account result = accountService.register(userId, registerCmd());

        assertThat(result.id()).isNotNull();
        assertThat(result.broker()).isEqualTo(Account.Broker.KIS);
        verify(accountPort).save(any());
        verify(connectionTester).verifyAccount("appKey", "appSecret", "74420614");
    }

    @Test
    @DisplayName("계좌 10개 초과 시 IllegalStateException 발생")
    void register_exceeds_limit_throws() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.KIS, true));
        when(accountPort.countByUserId(userId)).thenReturn(10);

        assertThatThrownBy(() -> accountService.register(userId, registerCmd()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("다른 사용자가 이미 등록한 계좌번호 재등록 시 DuplicateAccountException 발생 (→ 409)")
    void register_duplicateAccountNo_crossUser_throws() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.KIS, true));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.existsByAccountNo("74420614")).thenReturn(true);

        assertThatThrownBy(() -> accountService.register(userId, registerCmd()))
                .isInstanceOf(Account.DuplicateAccountException.class)
                .hasMessageContaining("74420614");
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

    @Test
    @DisplayName("Toss 계좌 등록: verifyAccount 호출 후 반환된 accountSeq 저장")
    void register_tossAccount_callsVerifyAccountAndStoresSeq() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.TOSS, true));
        when(connectionTesters.of(any())).thenReturn(connectionTester);
        // Toss API 연결 성공 → accountSeq "42" 반환
        when(connectionTester.verifyAccount("cid", "csecret", "12345678901")).thenReturn("42");
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterAccountCommand cmd = new RegisterAccountCommand(
                "토스테스트", "12345678901", "cid", "csecret", null, Account.Broker.TOSS
        );

        Account result = accountService.register(userId, cmd);

        verify(connectionTester).verifyAccount("cid", "csecret", "12345678901");
        assertThat(result.brokerAccountCode()).isEqualTo("42"); // accountSeq 저장
        assertThat(result.broker()).isEqualTo(Account.Broker.TOSS);
        assertThat(result.accountNo()).isEqualTo("12345678901");
    }

    @Test
    @DisplayName("broker null이면 KIS 기본값 적용")
    void register_nullBroker_defaultsToKis() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.KIS, true));
        when(connectionTesters.of(any())).thenReturn(connectionTester);
        when(connectionTester.verifyAccount(any(), any(), any())).thenReturn(null);
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterAccountCommand cmd = new RegisterAccountCommand(
                "테스트계좌", "74420614-01", "appKey", "appSecret", null, null
        );

        Account result = accountService.register(userId, cmd);

        assertThat(result.broker()).isEqualTo(Account.Broker.KIS);
        assertThat(result.accountNo()).isEqualTo("74420614-01"); // 전체 형식 그대로 저장
        assertThat(result.brokerAccountCode()).isNull(); // KIS는 null (accountNo에 통합)
        // KIS 라우터 경유 verifyAccount 호출 확인
        verify(connectionTesters).of(Account.Broker.KIS);
    }

    @Test
    @DisplayName("비활성 KIS는 신규 계좌 등록을 거부")
    void register_disabledKis_throwsValidationException() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.KIS, false));

        assertThatThrownBy(() -> accountService.register(userId, registerCmd()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KIS");

        verifyNoInteractions(connectionTesters);
        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("비활성 Toss는 신규 계좌 등록을 거부")
    void register_disabledToss_throwsValidationException() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.TOSS, false));
        RegisterAccountCommand cmd = new RegisterAccountCommand(
                "토스테스트", "12345678901", "cid", "csecret", null, Account.Broker.TOSS
        );

        assertThatThrownBy(() -> accountService.register(userId, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TOSS");

        verifyNoInteractions(connectionTesters);
        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("활성 KIS는 연결 테스트를 실행")
    void test_enabledKis_verifiesCredentials() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.KIS, true));
        when(connectionTesters.of(Account.Broker.KIS)).thenReturn(connectionTester);

        accountService.test(Account.Broker.KIS, "key", "secret", accountId);

        verify(connectionTester).verifyCredentials("key", "secret", accountId);
    }

    @Test
    @DisplayName("활성 Toss는 연결 테스트를 실행")
    void test_enabledToss_verifiesCredentials() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.TOSS, true));
        when(connectionTesters.of(Account.Broker.TOSS)).thenReturn(connectionTester);

        accountService.test(Account.Broker.TOSS, "key", "secret", accountId);

        verify(connectionTester).verifyCredentials("key", "secret", accountId);
    }

    @Test
    @DisplayName("비활성 KIS는 연결 테스트를 거부")
    void test_disabledKis_throwsValidationException() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.KIS, false));

        assertThatThrownBy(() -> accountService.test(Account.Broker.KIS, "key", "secret", accountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KIS");

        verifyNoInteractions(connectionTesters);
    }

    @Test
    @DisplayName("비활성 Toss는 연결 테스트를 거부")
    void test_disabledToss_throwsValidationException() {
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.TOSS, false));

        assertThatThrownBy(() -> accountService.test(Account.Broker.TOSS, "key", "secret", accountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TOSS");

        verifyNoInteractions(connectionTesters);
    }

    @Test
    @DisplayName("기존 계좌 조회와 수정은 증권사 등록 설정을 조회하지 않음")
    void existingAccountOperations_doNotLoadRuntimeSettings() {
        when(accountPort.findByUserId(userId)).thenReturn(java.util.List.of(activeAccount(userId)));
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(activeAccount(userId));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.listByUser(userId);
        accountService.update(accountId, userId, new UpdateAccountCommand("변경닉네임"));

        verifyNoInteractions(runtimeSettingsPort);
    }

    private RuntimeSettings settingsWith(Account.Broker broker, boolean enabled) {
        RuntimeSettings defaults = RuntimeSettings.defaults();
        EnumMap<Account.Broker, RuntimeSettings.BrokerSettings> brokers = new EnumMap<>(defaults.brokers());
        brokers.put(broker, new RuntimeSettings.BrokerSettings(enabled));
        return new RuntimeSettings(defaults.approvalRequired(), brokers, defaults.strategies());
    }

}
