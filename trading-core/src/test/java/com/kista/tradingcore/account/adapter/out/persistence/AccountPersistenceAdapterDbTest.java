package com.kista.tradingcore.account.adapter.out.persistence;

import com.kista.shared.kernel.Broker;
import com.kista.shared.platform.crypto.AccountNoHasher;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.account.domain.model.Account;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V10 — account_no NULL 허용·api_key_expires_at·access key 해시가 기존 unique 인덱스를 재사용하는지 실 DB로 확인
@Import({AccountPersistenceAdapter.class, AccountNoHasher.class})
@Execution(ExecutionMode.SAME_THREAD)
class AccountPersistenceAdapterDbTest extends DataJpaTestBase {

    @Autowired AccountPersistenceAdapter adapter;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test
    void upbitAccountRoundTripsAndDuplicateAccessKeyIsRejectedByIndex() {
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2027-10-17T00:00:00Z");
        Account saved = adapter.save(new Account(null, userId, "업비트", null, "dup-ak", "sk", null, Broker.UPBIT, null, expiresAt));
        em.flush();
        em.clear();

        Account loaded = adapter.findById(saved.id()).orElseThrow();
        assertThat(loaded.accountNo()).isNull();
        assertThat(loaded.apiKeyExpiresAt()).isEqualTo(expiresAt);
        assertThat(adapter.existsByDedupeKey("dup-ak")).isTrue();

        // save는 INSERT를 flush까지 미루므로 같은 람다 안에서 flush해야 unique 위반이 드러난다(EntityManager flush는 Hibernate 예외 그대로)
        assertThatThrownBy(() -> {
            adapter.save(new Account(null, UUID.randomUUID(), "업비트2", null, "dup-ak", "sk2", null, Broker.UPBIT, null, null));
            em.flush();
        }).isInstanceOf(PersistenceException.class).hasMessageContaining("uq_accounts_account_no_hash");
    }

    // 컨버터 적용 증명 — 컬럼 원문은 평문이 아니고, 어댑터로 읽으면 평문이 복원된다
    @Test
    void credentialsAreEncryptedAtRestAndDecryptedOnRead() {
        Account saved = adapter.save(new Account(null, UUID.randomUUID(), "KIS", "74420614-01", "plain-app", "plain-secret", null, Broker.KIS, null));
        em.flush();
        em.clear();

        var raw = jdbc.queryForMap("SELECT account_no, app_key, secret_key FROM accounts WHERE id = ?", saved.id());
        assertThat(raw.values()).noneMatch(v -> v.toString().contains("74420614") || v.toString().contains("plain-"));

        Account loaded = adapter.findById(saved.id()).orElseThrow();
        assertThat(loaded.accountNo()).isEqualTo("74420614-01");
        assertThat(loaded.appKey()).isEqualTo("plain-app");
        assertThat(loaded.secretKey()).isEqualTo("plain-secret");
    }
}
