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

    // V16에서 uq_finance_accounts_group_name을 DROP — 같은 그룹 안에서도 계좌명 중복이 허용된다.
    @Test
    void save_duplicateNameSameGroup_allowsBothAccounts() {
        FinanceAccount first = adapter.save(account("중복계좌명", "111"));
        FinanceAccount second = adapter.save(account("중복계좌명", "222"));

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(adapter.findByGroupId(groupId))
                .extracting(FinanceAccount::name)
                .containsExactlyInAnyOrder("중복계좌명", "중복계좌명");
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

    // V16에서 uq_finance_accounts_group_name을 DROP — reassignGroup도 같은 제약에 의존했으므로
    // 이관 대상 그룹에 동명 계좌가 있어도 더 이상 막히지 않고 중복 이름 상태로 이관된다.
    @Test
    void reassignGroup_nameCollisionInTargetGroup_throwsDuplicateNameException() {
        // 신규 등록 시 계좌명 중복은 V16부터 허용되지만(uq_finance_accounts_group_name DROP), 그룹 이관은
        // 서로 다른 두 그룹의 계좌가 우연히 이름이 겹쳐 사용자도 모르게 합쳐지는 걸 막는 별도 안전장치라
        // 애플리케이션 레벨에서 여전히 차단한다(FinanceGroupService.leaveGroup() 계약).
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '그룹2', false, now(), now())",
                otherGroupId, userId);
        // 이관 대상 그룹에 같은 이름의 계좌가 이미 있다
        FinanceAccount conflicting = new FinanceAccount(null, otherGroupId, userId,
                FinanceAccount.Type.SECURITIES, "겹치는계좌명", null, null, null);
        adapter.save(conflicting);
        adapter.save(account("겹치는계좌명", null));

        assertThatThrownBy(() -> adapter.reassignGroup(groupId, otherGroupId, userId))
                .isInstanceOf(FinanceAccount.DuplicateNameException.class);
    }
}
