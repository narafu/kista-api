package com.kista.adapter.out.persistence;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.Account;
import com.kista.domain.model.StrategyType;
import com.kista.domain.model.StrategyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPersistenceAdapter 단위 테스트")
class AccountPersistenceAdapterTest {

    @Mock AccountJpaRepository accountJpaRepository;
    @Mock StrategyJpaRepository strategyJpaRepository;
    @Mock AesCryptoService crypto;
    @InjectMocks AccountPersistenceAdapter adapter;

    private final UUID accountId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // 암호화/복호화 lenient stub — 테스트별 필요 여부가 달라 strict 모드 완화
        lenient().when(crypto.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        lenient().when(crypto.decrypt(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).replace("enc:", ""));
    }

    private AccountEntity accountEntityWithId(UUID id) {
        AccountEntity e = new AccountEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setNickname("테스트계좌");
        e.setAccountNo("enc:74420614");
        e.setKisAppKey("enc:appKey");
        e.setKisSecretKey("enc:appSecret");
        e.setKisAccountType("01");
        e.setSymbol("SOXL");
        e.setExchangeCode("AMS");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private StrategyEntity strategyEntity(UUID accId, StrategyType type, StrategyStatus status) {
        StrategyEntity s = new StrategyEntity();
        s.setAccountId(accId);
        s.setType(type);
        s.setStatus(status);
        return s;
    }

    @Test
    @DisplayName("신규 계좌 저장 시 StrategyEntity도 함께 생성")
    void save_new_account_creates_strategy() {
        // given: id=null 신규 계좌
        Account newAccount = new Account(null, userId, "테스트계좌",
                "74420614", "appKey", "appSecret", "01",
                StrategyType.INFINITE, StrategyStatus.ACTIVE,
                null, null, "SOXL", "AMS", null, null);

        AccountEntity saved = accountEntityWithId(accountId);
        when(accountJpaRepository.save(any())).thenReturn(saved);
        // strategyJpaRepository.findByAccountId stub 제거 (buildDomain으로 이중 쿼리 제거됨)

        // when
        adapter.save(newAccount);

        // then: StrategyEntity가 올바른 값으로 저장됨
        ArgumentCaptor<StrategyEntity> captor = ArgumentCaptor.forClass(StrategyEntity.class);
        verify(strategyJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(accountId);
        assertThat(captor.getValue().getType()).isEqualTo(StrategyType.INFINITE);
        assertThat(captor.getValue().getStatus()).isEqualTo(StrategyStatus.ACTIVE);
    }

    @Test
    @DisplayName("기존 계좌 저장 시 StrategyEntity status 업데이트")
    void save_existing_account_updates_strategy_status() {
        // given: id 존재 기존 계좌 (status: ACTIVE → PAUSED로 변경)
        Account existingAccount = new Account(accountId, userId, "테스트계좌",
                "74420614", "appKey", "appSecret", "01",
                StrategyType.INFINITE, StrategyStatus.PAUSED,
                null, null, "SOXL", "AMS", Instant.now(), Instant.now());

        AccountEntity entity = accountEntityWithId(accountId);
        StrategyEntity strategy = strategyEntity(accountId, StrategyType.INFINITE, StrategyStatus.ACTIVE);

        when(accountJpaRepository.save(any())).thenReturn(entity);
        when(strategyJpaRepository.findByAccountId(accountId))
                .thenReturn(Optional.of(strategy));

        // when
        adapter.save(existingAccount);

        // then: 기존 StrategyEntity의 status가 PAUSED로 업데이트됨
        assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.PAUSED);
        verify(strategyJpaRepository).save(strategy);
    }

    @Test
    @DisplayName("findById: strategy 정보가 StrategyEntity에서 로드됨")
    void findById_loads_strategy_from_strategies_table() {
        // given
        AccountEntity entity = accountEntityWithId(accountId);
        StrategyEntity strategy = strategyEntity(accountId, StrategyType.INFINITE, StrategyStatus.ACTIVE);

        when(accountJpaRepository.findById(accountId)).thenReturn(Optional.of(entity));
        when(strategyJpaRepository.findByAccountId(accountId)).thenReturn(Optional.of(strategy));

        // when
        Optional<Account> result = adapter.findById(accountId);

        // then: strategy 정보가 strategies 테이블에서 정상 로드됨
        assertThat(result).isPresent();
        assertThat(result.get().strategyType()).isEqualTo(StrategyType.INFINITE);
        assertThat(result.get().strategyStatus()).isEqualTo(StrategyStatus.ACTIVE);
    }
}
