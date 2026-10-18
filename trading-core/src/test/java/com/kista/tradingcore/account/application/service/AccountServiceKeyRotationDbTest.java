package com.kista.tradingcore.account.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.platform.crypto.AccountNoHasher;
import com.kista.shared.platform.crypto.AesCryptoService;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.account.adapter.out.persistence.AccountPersistenceAdapter;
import com.kista.tradingcore.account.application.port.output.AccountOpenOrderCancelPort;
import com.kista.tradingcore.account.application.port.output.BrokerEnabledPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand;
import com.kista.tradingcore.broker.application.port.output.BrokerConnectionTestPort;
import com.kista.tradingcore.broker.application.port.output.BrokerTokenCachePort;
import com.kista.tradingcore.broker.application.service.BrokerConnectionTesters;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

// 키 교체 경합 409 — 실 PostgreSQL unique 위반이 실제 저장 경로(트랜잭션 없는 JPA auto-commit)에서 DuplicateAccountException으로 변환되는지 확인
@Import({AccountPersistenceAdapter.class, AesCryptoService.class, AccountNoHasher.class})
@Execution(ExecutionMode.SAME_THREAD)
class AccountServiceKeyRotationDbTest extends DataJpaTestBase {

    @Autowired AccountPersistenceAdapter adapter;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 서비스 운영 경로와 같이 트랜잭션 밖 — 커밋 시점에 Spring 예외 번역이 일어난다
    void rotationRaceOnRealUniqueIndexMapsToDuplicate() {
        UUID userId = UUID.randomUUID();
        String keyA = "race-ak-a-" + userId; // 잔존 행·병렬 DB 테스트와 충돌하지 않도록 실행마다 고유
        try {
            adapter.save(new Account(null, userId, "업비트A", null, keyA, "sk", null, Broker.UPBIT, null, null));
            Account b = adapter.save(new Account(null, userId, "업비트B", null, "race-ak-b-" + userId, "sk", null, Broker.UPBIT, null, null));
            // 사전 중복 검사(existsByDedupeKey)가 통과한 뒤 다른 계좌가 먼저 같은 키를 쓴 경합을 재현 — 검사만 false로 고정
            AccountPersistenceAdapter racing = spy(adapter);
            doReturn(false).when(racing).existsByDedupeKey(keyA);
            BrokerConnectionTesters testers = mock(BrokerConnectionTesters.class);
            BrokerConnectionTestPort tester = mock(BrokerConnectionTestPort.class);
            when(testers.of(Broker.UPBIT)).thenReturn(tester);
            when(tester.verifyAccount(any(), any(), any())).thenReturn(new BrokerAccountVerification(null, null));
            AccountService service = new AccountService(racing, testers, mock(BrokerEnabledPort.class),
                    mock(BrokerTokenCachePort.class), mock(AccountOpenOrderCancelPort.class), mock(AccountDeletionWriter.class));

            assertThatThrownBy(() -> service.updateCredentials(b.id(), userId, new UpdateCredentialsCommand(keyA, "sk2")))
                    .isInstanceOf(Account.DuplicateAccountException.class);
        } finally {
            // 커밋된 행 정리(테스트 트랜잭션 롤백이 없다) — 저장 도중 실패해도 user_id로 모두 지운다
            jdbcTemplate.update("DELETE FROM accounts WHERE user_id = ?", userId);
        }
    }
}
