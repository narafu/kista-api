package com.kista.tradingcore.account.adapter.out.persistence;

import com.kista.shared.platform.crypto.AccountNoHasher;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPersistenceAdapter 단위 테스트")
class AccountPersistenceAdapterTest {

    @Mock AccountJpaRepository accountJpaRepository;
    @Mock AccountNoHasher hasher;
    @InjectMocks AccountPersistenceAdapter adapter;

    private final UUID accountId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // 해시 lenient stub
        lenient().when(hasher.hash(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0));
    }

    private AccountEntity accountEntityWithId(UUID id) {
        AccountEntity e = new AccountEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setNickname("테스트계좌");
        e.setAccountNo("74420614");
        e.setAppKey("appKey");
        e.setSecretKey("appSecret");
        e.setBrokerAccountCode("01");
        e.setBroker(Broker.KIS);
        return e;
    }

    @Test
    void save_upbitWithoutAccountNo_hashesAccessKeyWithNullAccountNo() {
        Account upbit = new Account(null, UUID.randomUUID(), "업비트", null, "ak", "sk", null, Broker.UPBIT, null,
                Instant.parse("2027-01-01T00:00:00Z"));
        when(accountJpaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = adapter.save(upbit);

        verify(accountJpaRepository).save(argThat(e -> e.getAccountNo() == null && "hash:ak".equals(e.getAccountNoHash())
                && Instant.parse("2027-01-01T00:00:00Z").equals(e.getApiKeyExpiresAt())));
        assertThat(saved.accountNo()).isNull();
        assertThat(saved.apiKeyExpiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("save: 신규 계좌 저장 시 JPA save 호출")
    void save_new_account_delegates_to_jpa() {
        // given: id=null 신규 계좌 (10개 필드)
        Account newAccount = new Account(null, userId, "테스트계좌",
                "74420614", "appKey", "appSecret", null,
                Broker.KIS, null);

        AccountEntity saved = accountEntityWithId(accountId);
        when(accountJpaRepository.save(any())).thenReturn(saved);

        // when
        Account result = adapter.save(newAccount);

        // then
        verify(accountJpaRepository).save(any(AccountEntity.class));
        assertThat(result.id()).isEqualTo(accountId);
        assertThat(result.nickname()).isEqualTo("테스트계좌");
    }

    @Test
    @DisplayName("findById: 존재하는 계좌 반환")
    void findById_returns_account_when_exists() {
        // given
        AccountEntity entity = accountEntityWithId(accountId);
        when(accountJpaRepository.findById(accountId)).thenReturn(Optional.of(entity));

        // when
        Optional<Account> result = adapter.findById(accountId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(accountId);
        assertThat(result.get().accountNo()).isEqualTo("74420614");
    }

    @Test
    @DisplayName("findById: 없는 계좌 empty 반환")
    void findById_returns_empty_when_not_found() {
        when(accountJpaRepository.findById(accountId)).thenReturn(Optional.empty());

        Optional<Account> result = adapter.findById(accountId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByUserId: userId로 계좌 목록 반환")
    void findByUserId_returns_list() {
        AccountEntity entity = accountEntityWithId(accountId);
        when(accountJpaRepository.findByUserId(userId)).thenReturn(List.of(entity));

        List<Account> result = adapter.findByUserId(userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("delete: accountId 소프트 삭제 호출")
    void delete_delegates_to_jpa() {
        adapter.delete(accountId);

        verify(accountJpaRepository).softDeleteById(eq(accountId), any());
    }

    @Test
    @DisplayName("countByUserId: 계좌 수 반환")
    void countByUserId_returns_count() {
        when(accountJpaRepository.countByUserId(userId)).thenReturn(3);

        assertThat(adapter.countByUserId(userId)).isEqualTo(3);
    }
}
