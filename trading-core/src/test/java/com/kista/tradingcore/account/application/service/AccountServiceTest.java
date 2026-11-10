package com.kista.tradingcore.account.application.service;

import org.junit.jupiter.api.BeforeEach;
import com.kista.tradingcore.account.application.port.output.AccountOpenOrderCancelPort;
import com.kista.tradingcore.account.application.event.AccountDeletedEvent;
import com.kista.tradingcore.broker.application.service.BrokerConnectionTesters;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.account.domain.model.RegisterAccountCommand;
import com.kista.tradingcore.account.domain.model.UpdateAccountCommand;
import com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.application.port.output.BrokerEnabledPort;
import com.kista.tradingcore.broker.application.port.output.BrokerConnectionTestPort;
import com.kista.tradingcore.broker.application.port.output.AccountTokenEvictionPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.TimeZones;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
    @Mock BrokerConnectionTesters connectionTesters; // 증권사별 연결테스트 라우터
    @Mock BrokerConnectionTestPort connectionTester;  // 라우터가 반환하는 실제 포트 mock
    @Mock BrokerEnabledPort brokerEnabledPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AccountTokenEvictionPort accountTokenEvictionPort;
    @Mock AccountOpenOrderCancelPort openOrderCancelPort; // 삭제 전 미체결 주문 취소 (trading 구현)
    AccountService accountService;
    // 2026-11-10T15:30Z = KST 2026-11-11 00:30 — UTC 날짜와 KST 날짜가 갈리는 시각
    Clock clock = Clock.fixed(Instant.parse("2026-11-10T15:30:00Z"), TimeZones.KST);

    @BeforeEach
    void setUpService() {
        // deletionWriter는 실제 인스턴스에 mock 포트를 위임 — 삭제 DB 쓰기·이벤트 발행을 그대로 검증
        accountService = new AccountService(accountPort, connectionTesters, brokerEnabledPort, accountTokenEvictionPort,
                openOrderCancelPort, new AccountDeletionWriter(accountPort, accountTokenEvictionPort, eventPublisher), clock);
    }

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    // Account 10개 필드 생성자 (strategyType/strategyStatus/ticker/multiple 제거)
    private Account activeAccount(UUID ownerId) {
        return new Account(accountId, ownerId, "테스트계좌",
                "74420614", "appKey", "appSecret", null,
                Broker.KIS, null, null);
    }

    private RegisterAccountCommand registerCmd() {
        return new RegisterAccountCommand(
                "테스트계좌", "74420614", "appKey", "appSecret", Broker.KIS, null
        );
    }

    @Test
    @DisplayName("업비트 등록: 계좌번호를 무시하고 access key 해시로 중복 판정, 만료일 저장")
    void register_upbit_ignoresAccountNoAndStoresExpiry() {
        Instant expiresAt = Instant.parse("2027-10-17T00:00:00Z");
        when(brokerEnabledPort.enabled(Broker.UPBIT)).thenReturn(true);
        when(connectionTesters.of(Broker.UPBIT)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("ak", "sk", null)).thenReturn(new BrokerAccountVerification(null, expiresAt));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.register(userId,
                new RegisterAccountCommand("업비트", "74420614-01", "ak", "sk", Broker.UPBIT, null));

        assertThat(saved.accountNo()).isNull();
        assertThat(saved.apiKeyExpiresAt()).isEqualTo(expiresAt);
        verify(accountPort).existsByDedupeKey("ak");
    }

    @Test
    @DisplayName("업비트 등록: 같은 access key가 이미 등록돼 있으면 409")
    void register_upbit_duplicateAccessKey() {
        when(brokerEnabledPort.enabled(Broker.UPBIT)).thenReturn(true);
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.existsByDedupeKey("ak")).thenReturn(true);

        assertThatThrownBy(() -> accountService.register(userId,
                new RegisterAccountCommand("업비트", null, "ak", "sk", Broker.UPBIT, null)))
                .isInstanceOf(Account.DuplicateAccountException.class);
        verifyNoInteractions(connectionTesters);
    }

    @Test
    @DisplayName("업비트 등록: access key가 비면 400")
    void register_upbit_requiresKeys() {
        when(brokerEnabledPort.enabled(Broker.UPBIT)).thenReturn(true);

        assertThatThrownBy(() -> accountService.register(userId,
                new RegisterAccountCommand("업비트", null, " ", "sk", Broker.UPBIT, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("메리츠 등록: 계좌번호를 무시하고 키만 받으며 키가 비면 400")
    void register_meritz_ignores_account_no_and_requires_keys() {
        when(brokerEnabledPort.enabled(Broker.MERITZ)).thenReturn(true);
        when(connectionTesters.of(Broker.MERITZ)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("ak", "sk", null)).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.register(userId,
                new RegisterAccountCommand("메리츠", "123", "ak", "sk", Broker.MERITZ, null));

        assertThat(saved.accountNo()).isNull();
        verify(connectionTester).verifyAccount("ak", "sk", null);
        verify(accountPort).existsByDedupeKey("ak");
        assertThatThrownBy(() -> accountService.register(userId,
                new RegisterAccountCommand("메리츠", null, " ", "sk", Broker.MERITZ, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("API 키와 시크릿은 필수입니다.");
    }

    @Test
    @DisplayName("바이낸스 등록: 계좌번호를 무시하고 키만 받으며 키가 비면 400")
    void register_binance_ignores_account_no_and_requires_keys() {
        when(brokerEnabledPort.enabled(Broker.BINANCE)).thenReturn(true);
        when(connectionTesters.of(Broker.BINANCE)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("ak", "sk", null)).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.register(userId,
                new RegisterAccountCommand("바이낸스", "123", "ak", "sk", Broker.BINANCE, null));

        assertThat(saved.accountNo()).isNull();
        verify(connectionTester).verifyAccount("ak", "sk", null);
        verify(accountPort).existsByDedupeKey("ak");
        assertThatThrownBy(() -> accountService.register(userId,
                new RegisterAccountCommand("바이낸스", null, " ", "sk", Broker.BINANCE, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("API 키와 시크릿은 필수입니다.");
    }

    @Test
    @DisplayName("키 수정: 메리츠 새 키가 다른 계좌에 있으면 409")
    void update_credentials_meritz_rejects_key_used_by_other_account() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(
                new Account(accountId, userId, "메리츠", null, "old", "sk", null, Broker.MERITZ, null, null));
        when(accountPort.existsByDedupeKey("taken")).thenReturn(true);

        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("taken", "sk", null)))
                .isInstanceOf(Account.DuplicateAccountException.class);
    }

    @Test
    @DisplayName("업비트 계좌(계좌번호 null)를 가진 사용자가 KIS 계좌를 등록해도 NPE가 나지 않는다")
    void register_kis_whenUserAlreadyHasUpbitAccount_doesNotNpe() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(connectionTesters.of(any())).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("appKey", "appSecret", "74420614")).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(1);
        when(accountPort.findByUserId(userId)).thenReturn(List.of(TradingFixtures.upbitAccount(UUID.randomUUID(), userId)));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.register(userId, registerCmd());

        assertThat(saved.accountNo()).isEqualTo("74420614");
    }

    @Test
    @DisplayName("업비트 연결 테스트: UPBIT 라우터로 위임")
    void test_upbit_routesToUpbitTester() {
        when(brokerEnabledPort.enabled(Broker.UPBIT)).thenReturn(true);
        when(connectionTesters.of(Broker.UPBIT)).thenReturn(connectionTester);

        accountService.test(Broker.UPBIT, "ak", "sk");

        verify(connectionTester).verifyCredentials("ak", "sk");
    }

    @Test
    @DisplayName("연결 테스트: broker 미지정이면 KIS 라우터로 위임")
    void test_nullBroker_defaultsToKis() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(connectionTesters.of(Broker.KIS)).thenReturn(connectionTester);

        accountService.test(null, "ak", "sk");

        verify(connectionTester).verifyCredentials("ak", "sk");
    }

    @Test
    @DisplayName("계좌 등록 성공")
    void register_success() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(connectionTesters.of(any())).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("appKey", "appSecret", "74420614")).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            return new Account(UUID.randomUUID(), a.userId(), a.nickname(),
                    a.accountNo(), a.appKey(), a.secretKey(),
                    a.brokerAccountCode(), a.broker(), null, null);
        });

        Account result = accountService.register(userId, registerCmd());

        assertThat(result.id()).isNotNull();
        assertThat(result.broker()).isEqualTo(Broker.KIS);
        verify(accountPort).save(any());
        verify(connectionTester).verifyAccount("appKey", "appSecret", "74420614");
    }

    @Test
    @DisplayName("계좌 10개 초과 시 IllegalStateException 발생")
    void register_exceeds_limit_throws() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(accountPort.countByUserId(userId)).thenReturn(10);

        assertThatThrownBy(() -> accountService.register(userId, registerCmd()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("다른 사용자가 이미 등록한 계좌번호 재등록 시 DuplicateAccountException 발생 (→ 409)")
    void register_duplicateAccountNo_crossUser_throws() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.existsByDedupeKey("74420614")).thenReturn(true);

        assertThatThrownBy(() -> accountService.register(userId, registerCmd()))
                .isInstanceOf(Account.DuplicateAccountException.class)
                .hasMessage("이미 등록된 계좌입니다.");
    }

    @Test
    @DisplayName("타 사용자 계좌 수정 시 SecurityException 발생 (→ 403)")
    void update_by_non_owner_throws_forbidden() {
        when(accountPort.requireOwnedAccount(accountId, userId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        UpdateAccountCommand cmd = new UpdateAccountCommand("변경닉네임", null);

        assertThatThrownBy(() -> accountService.update(accountId, userId, cmd))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("본인 계좌 수정 성공")
    void update_by_owner_success() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(activeAccount(userId));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateAccountCommand cmd = new UpdateAccountCommand("변경닉네임", null);
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
    @DisplayName("빈 별명으로 수정하면 IllegalArgumentException (→ 400)")
    void update_blank_nickname_throws() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(activeAccount(userId));

        assertThatThrownBy(() -> accountService.update(accountId, userId, new UpdateAccountCommand("  ", null)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("본인 계좌 삭제 성공")
    void delete_by_owner_success() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(activeAccount(userId));

        accountService.delete(accountId, userId);

        verify(accountPort).delete(accountId);
        verify(accountTokenEvictionPort).evict(List.of(accountId));
        verify(eventPublisher).publishEvent(new AccountDeletedEvent(accountId, userId));
    }

    @Test
    @DisplayName("증권사 주문 취소에 실패한 주문이 있으면 계좌를 삭제하지 않는다")
    void delete_aborts_when_open_order_cancel_fails() {
        Account account = activeAccount(userId);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(account);
        when(openOrderCancelPort.cancelOpenOrders(account)).thenReturn(1);

        assertThatThrownBy(() -> accountService.delete(accountId, userId))
                .isInstanceOf(IllegalStateException.class);

        verify(accountPort, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class)); // any()는 ApplicationEvent 오버로드로 해석돼 record 이벤트를 놓친다
    }

    @Test
    @DisplayName("존재하지 않는 계좌 수정 시 NoSuchElementException 발생 (→ 404)")
    void update_not_found_throws() {
        when(accountPort.requireOwnedAccount(accountId, userId))
                .thenThrow(new NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));

        assertThatThrownBy(() -> accountService.update(accountId, userId,
                new UpdateAccountCommand("닉", null)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("Toss 계좌 등록: verifyAccount 호출 후 반환된 accountSeq 저장")
    void register_tossAccount_callsVerifyAccountAndStoresSeq() {
        when(brokerEnabledPort.enabled(Broker.TOSS)).thenReturn(true);
        when(connectionTesters.of(any())).thenReturn(connectionTester);
        // Toss API 연결 성공 → accountSeq "42" 반환
        when(connectionTester.verifyAccount("cid", "csecret", "12345678901")).thenReturn(new BrokerAccountVerification("42", null));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterAccountCommand cmd = new RegisterAccountCommand(
                "토스테스트", "12345678901", "cid", "csecret", Broker.TOSS, null
        );

        Account result = accountService.register(userId, cmd);

        verify(connectionTester).verifyAccount("cid", "csecret", "12345678901");
        assertThat(result.brokerAccountCode()).isEqualTo("42"); // accountSeq 저장
        assertThat(result.broker()).isEqualTo(Broker.TOSS);
        assertThat(result.accountNo()).isEqualTo("12345678901");
    }

    @Test
    @DisplayName("broker null이면 KIS 기본값 적용")
    void register_nullBroker_defaultsToKis() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(connectionTesters.of(any())).thenReturn(connectionTester);
        when(connectionTester.verifyAccount(any(), any(), any())).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterAccountCommand cmd = new RegisterAccountCommand(
                "테스트계좌", "74420614-01", "appKey", "appSecret", null, null
        );

        Account result = accountService.register(userId, cmd);

        assertThat(result.broker()).isEqualTo(Broker.KIS);
        assertThat(result.accountNo()).isEqualTo("74420614-01"); // 전체 형식 그대로 저장
        assertThat(result.brokerAccountCode()).isNull(); // KIS는 null (accountNo에 통합)
        // KIS 라우터 경유 verifyAccount 호출 확인
        verify(connectionTesters).of(Broker.KIS);
    }

    @Test
    @DisplayName("비활성 KIS는 신규 계좌 등록을 거부")
    void register_disabledKis_throwsValidationException() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(false);

        assertThatThrownBy(() -> accountService.register(userId, registerCmd()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("한국투자증권 신규 계좌 등록이 비활성화되어 있습니다.");

        verifyNoInteractions(connectionTesters);
        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("비활성 Toss는 신규 계좌 등록을 거부")
    void register_disabledToss_throwsValidationException() {
        when(brokerEnabledPort.enabled(Broker.TOSS)).thenReturn(false);
        RegisterAccountCommand cmd = new RegisterAccountCommand(
                "토스테스트", "12345678901", "cid", "csecret", Broker.TOSS, null
        );

        assertThatThrownBy(() -> accountService.register(userId, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("토스증권 신규 계좌 등록이 비활성화되어 있습니다.");

        verifyNoInteractions(connectionTesters);
        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("활성 KIS는 연결 테스트를 실행")
    void test_enabledKis_verifiesCredentials() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(connectionTesters.of(Broker.KIS)).thenReturn(connectionTester);

        accountService.test(Broker.KIS, "key", "secret");

        verify(connectionTester).verifyCredentials("key", "secret");
    }

    @Test
    @DisplayName("활성 Toss는 연결 테스트를 실행")
    void test_enabledToss_verifiesCredentials() {
        when(brokerEnabledPort.enabled(Broker.TOSS)).thenReturn(true);
        when(connectionTesters.of(Broker.TOSS)).thenReturn(connectionTester);

        accountService.test(Broker.TOSS, "key", "secret");

        verify(connectionTester).verifyCredentials("key", "secret");
    }

    @Test
    @DisplayName("비활성 KIS는 연결 테스트를 거부")
    void test_disabledKis_throwsValidationException() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(false);

        assertThatThrownBy(() -> accountService.test(Broker.KIS, "key", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("한국투자증권 신규 계좌 등록이 비활성화되어 있습니다.");

        verifyNoInteractions(connectionTesters);
    }

    @Test
    @DisplayName("비활성 Toss는 연결 테스트를 거부")
    void test_disabledToss_throwsValidationException() {
        when(brokerEnabledPort.enabled(Broker.TOSS)).thenReturn(false);

        assertThatThrownBy(() -> accountService.test(Broker.TOSS, "key", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("토스증권 신규 계좌 등록이 비활성화되어 있습니다.");

        verifyNoInteractions(connectionTesters);
    }

    @Test
    @DisplayName("KIS 등록 시 accountNo가 비어있으면 IllegalArgumentException 발생 (→ 400) — @NotBlank 제거로 서비스가 대신 검증")
    void register_blankAccountNo_nonMockBroker_throws() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        RegisterAccountCommand cmd = new RegisterAccountCommand(
                "테스트계좌", null, "appKey", "appSecret", Broker.KIS, null
        );

        assertThatThrownBy(() -> accountService.register(userId, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("계좌번호");

        verifyNoInteractions(connectionTesters);
        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("MOCK 등록 시 accountNo/appKey/secretKey가 비어있으면 서버가 합성 값을 생성해 등록한다")
    void register_mockBroker_synthesizesMissingCredentials() {
        when(brokerEnabledPort.enabled(Broker.MOCK)).thenReturn(true);
        when(connectionTesters.of(Broker.MOCK)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount(any(), any(), any())).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.existsByDedupeKey(any())).thenReturn(false);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterAccountCommand cmd = new RegisterAccountCommand(
                "모의계좌", null, null, null, Broker.MOCK, null
        );

        Account result = accountService.register(userId, cmd);

        assertThat(result.broker()).isEqualTo(Broker.MOCK);
        assertThat(result.accountNo()).matches("\\d{8}-\\d{2}");
        assertThat(result.appKey()).isEqualTo("MOCK");
        assertThat(result.secretKey()).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("기존 계좌 조회와 수정은 증권사 등록 설정을 조회하지 않음")
    void existingAccountOperations_doNotLoadRuntimeSettings() {
        when(accountPort.findByUserId(userId)).thenReturn(java.util.List.of(activeAccount(userId)));
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(activeAccount(userId));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.listByUser(userId);
        accountService.update(accountId, userId, new UpdateAccountCommand("변경닉네임", null));

        verifyNoInteractions(brokerEnabledPort);
    }

    @Test
    void deleteAllByUserId_deletesTokensBeforeSoftDeletingAccounts() {
        UUID userId = UUID.randomUUID();
        Account a1 = TradingFixtures.kisAccount(UUID.randomUUID(), userId);
        Account a2 = TradingFixtures.kisAccount(UUID.randomUUID(), userId);
        when(accountPort.findByUserId(userId)).thenReturn(List.of(a1, a2));

        accountService.deleteAllByUserId(userId);

        InOrder inOrder = inOrder(accountPort, accountTokenEvictionPort);
        inOrder.verify(accountPort).findByUserId(userId);
        inOrder.verify(accountTokenEvictionPort).evict(List.of(a1.id(), a2.id()));
        inOrder.verify(accountPort).deleteByUserId(userId);
    }

    @Test
    @DisplayName("키 수정: 연결 테스트 통과 시 저장하고 만료일을 갱신하며 토큰 캐시를 지운다")
    void updateCredentials_upbit_savesAndEvictsTokenCache() {
        Account upbit = TradingFixtures.upbitAccount(accountId, userId);
        Instant expiresAt = Instant.parse("2027-10-17T00:00:00Z");
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(upbit);
        when(accountPort.existsByDedupeKey("new-ak")).thenReturn(false);
        when(connectionTesters.of(Broker.UPBIT)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("new-ak", "new-sk", null)).thenReturn(new BrokerAccountVerification(null, expiresAt));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("new-ak", "new-sk", null));

        assertThat(saved.appKey()).isEqualTo("new-ak");
        assertThat(saved.apiKeyExpiresAt()).isEqualTo(expiresAt);
        InOrder order = inOrder(accountPort, accountTokenEvictionPort);
        order.verify(accountPort).save(any());
        order.verify(accountTokenEvictionPort).evict(List.of(accountId));
        verifyNoInteractions(brokerEnabledPort); // 키 교체는 신규 등록 정책 대상이 아니다
    }

    @Test
    @DisplayName("키 수정: 다른 무결성 위반(길이 초과 등)은 409로 바꾸지 않고 그대로 던진다")
    void rotationOtherIntegrityViolationPropagates() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.upbitAccount(accountId, userId));
        when(accountPort.existsByDedupeKey("new-ak")).thenReturn(false);
        when(connectionTesters.of(Broker.UPBIT)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("new-ak", "new-sk", null)).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("x", new RuntimeException("value too long for type character varying(512)")));

        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("new-ak", "new-sk", null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verifyNoInteractions(accountTokenEvictionPort);
    }

    @Test
    @DisplayName("키 수정: 저장 시 unique 위반(경합)은 DuplicateAccountException으로 변환한다")
    void rotationUniqueViolationIsDuplicate() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.upbitAccount(accountId, userId));
        when(accountPort.existsByDedupeKey("new-ak")).thenReturn(false);
        when(connectionTesters.of(Broker.UPBIT)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("new-ak", "new-sk", null)).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup", new RuntimeException("duplicate key value violates unique constraint \"uq_accounts_account_no_hash\"")));

        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("new-ak", "new-sk", null)))
                .isInstanceOf(Account.DuplicateAccountException.class);
        verifyNoInteractions(accountTokenEvictionPort);
    }

    @Test
    @DisplayName("키 수정: 업비트 같은 access key 재입력은 중복 검사를 건너뛰고 저장한다")
    void updateCredentials_upbit_sameAccessKey_skipsDuplicateCheck() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.upbitAccount(accountId, userId));
        when(connectionTesters.of(Broker.UPBIT)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("access-key", "rotated-sk", null)).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("access-key", "rotated-sk", null));

        verify(accountPort, never()).existsByDedupeKey(any());
        verify(accountPort).save(any());
    }

    @Test
    @DisplayName("키 수정: 연결 테스트 실패면 저장하지 않는다")
    void updateCredentials_verificationFails_doesNotSave() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(activeAccount(userId));
        when(connectionTesters.of(Broker.KIS)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("bad", "bad", "74420614"))
                .thenThrow(new com.kista.tradingcore.broker.domain.model.BrokerCredentialException());

        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("bad", "bad", null)))
                .isInstanceOf(com.kista.tradingcore.broker.domain.model.BrokerCredentialException.class);
        verify(accountPort, never()).save(any());
        verifyNoInteractions(accountTokenEvictionPort);
    }

    @Test
    @DisplayName("키 수정: Toss 계좌도 저장 뒤 토큰 캐시(Redis 포함)를 지운다")
    void updateCredentials_toss_savesAndEvictsTokenCache() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.tossAccount(accountId, userId)); // brokerAccountCode "1"
        when(connectionTesters.of(Broker.TOSS)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("cid", "cs", "123-45-678901")).thenReturn(new BrokerAccountVerification("1", null));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("cid", "cs", null));

        InOrder order = inOrder(accountPort, accountTokenEvictionPort);
        order.verify(accountPort).save(any());
        order.verify(accountTokenEvictionPort).evict(List.of(accountId));
    }

    @Test
    @DisplayName("키 수정: Toss 새 키가 다른 계좌를 가리키면 거부한다")
    void updateCredentials_toss_differentAccountSeq_rejected() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.tossAccount(accountId, userId)); // brokerAccountCode "1"
        when(connectionTesters.of(Broker.TOSS)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("cid", "cs", "123-45-678901")).thenReturn(new BrokerAccountVerification("2", null));

        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("cid", "cs", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("다른 계좌");
        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("키 수정: 업비트 새 access key가 다른 계좌에 있으면 409, MOCK은 400, 남의 계좌는 403")
    void updateCredentials_guards() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.upbitAccount(accountId, userId));
        when(accountPort.existsByDedupeKey("taken")).thenReturn(true);
        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("taken", "sk", null)))
                .isInstanceOf(Account.DuplicateAccountException.class);

        UUID mockId = UUID.randomUUID();
        when(accountPort.requireOwnedAccount(mockId, userId)).thenReturn(
                new Account(mockId, userId, "모의", "12345678-01", "MOCK", "MOCK", null, Broker.MOCK, null, null));
        assertThatThrownBy(() -> accountService.updateCredentials(mockId, userId, new UpdateCredentialsCommand("a", "b", null)))
                .isInstanceOf(IllegalArgumentException.class);

        UUID foreignId = UUID.randomUUID();
        when(accountPort.requireOwnedAccount(foreignId, userId)).thenThrow(new SecurityException("계좌에 대한 접근 권한이 없습니다"));
        assertThatThrownBy(() -> accountService.updateCredentials(foreignId, userId, new UpdateCredentialsCommand("a", "b", null)))
                .isInstanceOf(SecurityException.class);
        verify(accountPort, never()).save(any());
    }

    // ---- API 키 만료일 결정 (KIS·토스·메리츠 추정/사용자 입력, 업비트 API 값) ----

    private static final String MANUAL_REJECTED = "이 거래소는 API 키 만료일을 직접 바꿀 수 없습니다.";
    private static final String PAST_REJECTED = "API 키 만료일은 오늘 이후여야 합니다.";

    // KIS 등록 공통 stub — 검증은 만료일 없이 통과
    private void stubKisRegister() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(connectionTesters.of(Broker.KIS)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount(any(), any(), any())).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RegisterAccountCommand kisRegister(LocalDate expiresOn) {
        return new RegisterAccountCommand("테스트계좌", "74420614", "appKey", "appSecret", Broker.KIS, expiresOn);
    }

    // 만료일이 있는 KIS 계좌 — 키 교체·수정 테스트용
    private Account kisAccountExpiring(Instant expiresAt) {
        return new Account(accountId, userId, "테스트계좌", "74420614", "appKey", "appSecret", null,
                Broker.KIS, null, expiresAt);
    }

    private void stubKisRotation(Account account) {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(account);
        when(connectionTesters.of(Broker.KIS)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount(any(), any(), any())).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registerKisEstimatesOneYear() {
        stubKisRegister();

        Account saved = accountService.register(userId, kisRegister(null));

        assertThat(saved.apiKeyExpiresAt()).isEqualTo(Instant.parse("2027-11-10T15:30:00Z"));
    }

    @Test
    void registerKisWithManualExpiry() {
        stubKisRegister();

        Account saved = accountService.register(userId, kisRegister(LocalDate.of(2027, 3, 1)));

        assertThat(saved.apiKeyExpiresAt()).isEqualTo(Instant.parse("2027-02-28T15:00:00Z")); // KST 자정
    }

    @Test
    void registerUpbitUsesBrokerValueAndRejectsManual() {
        Instant expiresAt = Instant.parse("2027-10-17T00:00:00Z");
        when(brokerEnabledPort.enabled(Broker.UPBIT)).thenReturn(true);
        when(connectionTesters.of(Broker.UPBIT)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("ak", "sk", null)).thenReturn(new BrokerAccountVerification(null, expiresAt));
        when(connectionTester.verifyAccount("ak2", "sk", null)).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(accountService.register(userId,
                new RegisterAccountCommand("업비트", null, "ak", "sk", Broker.UPBIT, null)).apiKeyExpiresAt()).isEqualTo(expiresAt);
        assertThat(accountService.register(userId,
                new RegisterAccountCommand("업비트", null, "ak2", "sk", Broker.UPBIT, null)).apiKeyExpiresAt()).isNull();
        assertThatThrownBy(() -> accountService.register(userId,
                new RegisterAccountCommand("업비트", null, "ak3", "sk", Broker.UPBIT, LocalDate.of(2027, 3, 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(MANUAL_REJECTED);
    }

    @Test
    void registerBinanceHasNoExpiry() {
        when(brokerEnabledPort.enabled(Broker.BINANCE)).thenReturn(true);
        when(connectionTesters.of(Broker.BINANCE)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("ak", "sk", null)).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.register(userId,
                new RegisterAccountCommand("바이낸스", null, "ak", "sk", Broker.BINANCE, null));

        assertThat(saved.apiKeyExpiresAt()).isNull();
    }

    @Test
    void estimateFromLeapDay() {
        clock = Clock.fixed(Instant.parse("2028-02-29T03:00:00Z"), TimeZones.KST);
        setUpService();
        stubKisRegister();

        Account saved = accountService.register(userId, kisRegister(null));

        assertThat(saved.apiKeyExpiresAt()).isEqualTo(Instant.parse("2029-02-28T03:00:00Z"));
    }

    @Test
    void updateCredentialsNewAppKeyReestimates() {
        stubKisRotation(kisAccountExpiring(Instant.parse("2026-12-01T00:00:00Z")));

        Account saved = accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("newKey", "sk", null));

        assertThat(saved.apiKeyExpiresAt()).isEqualTo(Instant.parse("2027-11-10T15:30:00Z"));
    }

    @Test
    void updateCredentialsSameAppKeyKeepsExpiry() {
        Instant existing = Instant.parse("2026-12-01T00:00:00Z");
        stubKisRotation(kisAccountExpiring(existing));

        Account saved = accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("appKey", "sk", null));

        assertThat(saved.apiKeyExpiresAt()).isEqualTo(existing);
    }

    @Test
    void updateCredentialsSameAppKeyWithManualExpiryReplaces() {
        stubKisRotation(kisAccountExpiring(Instant.parse("2026-12-01T00:00:00Z")));

        Account saved = accountService.updateCredentials(accountId, userId,
                new UpdateCredentialsCommand("appKey", "sk", LocalDate.of(2027, 3, 1)));

        assertThat(saved.apiKeyExpiresAt()).isEqualTo(Instant.parse("2027-02-28T15:00:00Z"));
    }

    @Test
    void manualExpiryUsesKstToday() {
        // 고정 시각은 UTC 11-10이지만 KST로는 11-11 — 11-10은 어제라 거부, 11-11(오늘)은 허용
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(kisAccountExpiring(null));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> accountService.update(accountId, userId, new UpdateAccountCommand("닉", LocalDate.of(2026, 11, 10))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(PAST_REJECTED);
        Account saved = accountService.update(accountId, userId, new UpdateAccountCommand("닉", LocalDate.of(2026, 11, 11)));
        assertThat(saved.apiKeyExpiresAt()).isEqualTo(Instant.parse("2026-11-10T15:00:00Z"));
    }

    @Test
    void invalidExpiryRejectedBeforeBrokerCall() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(kisAccountExpiring(null));
        LocalDate yesterday = LocalDate.of(2026, 11, 10);

        assertThatThrownBy(() -> accountService.register(userId, kisRegister(yesterday)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(PAST_REJECTED);
        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("k", "s", yesterday)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(PAST_REJECTED);
        verify(connectionTesters, never()).of(any());
        verify(accountPort, never()).save(any());
    }

    @Test
    void updateSetsManualExpiry() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(kisAccountExpiring(Instant.parse("2026-12-01T00:00:00Z")));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.update(accountId, userId, new UpdateAccountCommand("닉", LocalDate.of(2027, 3, 1)));

        assertThat(saved.apiKeyExpiresAt()).isEqualTo(Instant.parse("2027-02-28T15:00:00Z"));
        assertThat(saved.nickname()).isEqualTo("닉");
    }

    @Test
    void updateWithNullExpiryKeepsExisting() {
        Instant existing = Instant.parse("2026-12-01T00:00:00Z");
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(kisAccountExpiring(existing));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.update(accountId, userId, new UpdateAccountCommand("닉", null));

        assertThat(saved.apiKeyExpiresAt()).isEqualTo(existing);
    }

    @Test
    void updateUpbitManualRejected() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.upbitAccount(accountId, userId));

        assertThatThrownBy(() -> accountService.update(accountId, userId, new UpdateAccountCommand("닉", LocalDate.of(2027, 3, 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(MANUAL_REJECTED);
        verify(accountPort, never()).save(any());
    }
}
