package com.kista.adapter.out.persistence.finance;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.finance.FinanceAccount;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// accountNo AES-256-GCM 암/복호화 round-trip 검증이 핵심 — 평문이 컬럼에 그대로 저장되면 안 된다
@Import({FinanceAccountPersistenceAdapter.class, AesCryptoService.class})
@Execution(ExecutionMode.SAME_THREAD)
class FinanceAccountPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired FinanceAccountPersistenceAdapter adapter;

    private UUID userId;
    private UUID groupId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        groupId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '개인', true, now(), now())",
                groupId, userId);
    }

    private FinanceAccount account(String name, String accountNo) {
        return new FinanceAccount(null, groupId, userId, FinanceAccount.Type.SECURITIES, name, accountNo, null, null);
    }

    @Test
    void save_encryptsAccountNoAtRest_andDecryptsOnRead() {
        String plainAccountNo = "110-123-456789";
        FinanceAccount saved = adapter.save(account("토스증권 일반계좌", plainAccountNo));

        String rawColumnValue = jdbcTemplate.queryForObject(
                "SELECT account_no FROM finance_accounts WHERE id = ?", String.class, saved.id());
        assertThat(rawColumnValue).isNotEqualTo(plainAccountNo);

        FinanceAccount found = adapter.findById(saved.id()).orElseThrow();
        assertThat(found.accountNo()).isEqualTo(plainAccountNo);
    }

    @Test
    void save_duplicateNameSameGroup_throwsDuplicateNameException() {
        adapter.save(account("중복계좌명", "111"));

        assertThatThrownBy(() -> adapter.save(account("중복계좌명", "222")))
                .isInstanceOf(FinanceAccount.DuplicateNameException.class);
    }

    @Test
    void save_nullAccountNo_savesAndLoadsWithoutCrashing() {
        FinanceAccount saved = adapter.save(account("계좌번호없는계좌", null));

        FinanceAccount found = adapter.findById(saved.id()).orElseThrow();
        assertThat(found.accountNo()).isNull();
    }

    @Test
    void reassignGroup_movesCreatedByOwnedRowsToTargetGroup() {
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '그룹2', false, now(), now())",
                otherGroupId, userId);
        FinanceAccount saved = adapter.save(account("이관대상계좌", "999"));

        adapter.reassignGroup(groupId, otherGroupId, userId);

        FinanceAccount reassigned = adapter.findById(saved.id()).orElseThrow();
        assertThat(reassigned.groupId()).isEqualTo(otherGroupId);
    }

    @Test
    void reassignGroup_nameCollisionInTargetGroup_throwsDuplicateNameException() {
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '그룹2', false, now(), now())",
                otherGroupId, userId);
        // 이관 대상 그룹에 같은 이름의 계좌가 이미 있다
        FinanceAccount conflicting = new FinanceAccount(null, otherGroupId, userId,
                FinanceAccount.Type.SECURITIES, "겹치는계좌명", null, null, null);
        adapter.save(conflicting);
        adapter.save(account("겹치는계좌명", null));

        // reassignGroup 호출은 마지막 DB 접촉이어야 한다 (aborted transaction 규칙)
        assertThatThrownBy(() -> adapter.reassignGroup(groupId, otherGroupId, userId))
                .isInstanceOf(FinanceAccount.DuplicateNameException.class);
    }
}
