package com.kista.finance.adapter.out.persistence;

import com.kista.platform.crypto.AccountNoHasher;
import com.kista.platform.crypto.AesCryptoService;
import com.kista.finance.domain.model.FinanceAccount;
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

// accountNo AES-256 암/복호화 round-trip 검증이 핵심 — 평문이 컬럼에 그대로 저장되면 안 된다
@Import({FinanceAccountPersistenceAdapter.class, AesCryptoService.class, AccountNoHasher.class})
@Execution(ExecutionMode.SAME_THREAD)
class FinanceAccountPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired FinanceAccountPersistenceAdapter adapter;

    private UUID userId;
    private UUID otherUserId;
    private UUID groupId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        groupId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                otherUserId, "kakao_" + otherUserId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, created_at, updated_at) VALUES (?, ?, now(), now())",
                groupId, userId);
    }

    private FinanceAccount groupAccount(String name, String accountNo) {
        return new FinanceAccount(null, groupId, userId, FinanceAccount.Type.SECURITIES, name, accountNo, null, null);
    }

    private FinanceAccount personalAccount(UUID owner, String name, String accountNo) {
        return new FinanceAccount(null, null, owner, FinanceAccount.Type.SECURITIES, name, accountNo, null, null);
    }

    @Test
    void save_encryptsAccountNoAtRest_andDecryptsOnRead() {
        String plainAccountNo = "110-123-456789";
        FinanceAccount saved = adapter.save(groupAccount("토스증권 일반계좌", plainAccountNo));

        String rawColumnValue = jdbcTemplate.queryForObject(
                "SELECT account_no FROM finance_accounts WHERE id = ?", String.class, saved.id());
        assertThat(rawColumnValue).isNotEqualTo(plainAccountNo);

        FinanceAccount found = adapter.findById(saved.id()).orElseThrow();
        assertThat(found.accountNo()).isEqualTo(plainAccountNo);
    }

    // V16에서 uq_finance_accounts_group_name을 DROP — 같은 그룹 안에서도 계좌명 중복이 허용된다.
    @Test
    void save_duplicateNameSameGroup_allowsBothAccounts() {
        FinanceAccount first = adapter.save(groupAccount("중복계좌명", "111"));
        FinanceAccount second = adapter.save(groupAccount("중복계좌명", "222"));

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(adapter.findMyScope(userId, groupId))
                .extracting(FinanceAccount::name)
                .containsExactlyInAnyOrder("중복계좌명", "중복계좌명");
    }

    @Test
    void save_nullAccountNo_savesAndLoadsWithoutCrashing() {
        FinanceAccount saved = adapter.save(groupAccount("계좌번호없는계좌", null));

        FinanceAccount found = adapter.findById(saved.id()).orElseThrow();
        assertThat(found.accountNo()).isNull();
    }

    // 운영 장애 재현(2026-08-19): 삭제된 계좌를 여전히 참조하는 자산 스냅샷이 있으면 enrich()가
    // findByIdOrThrow로 계좌명을 조회하는데, 클래스 레벨 @SQLRestriction이 있으면 여기서 404가 나
    // 목록 조회 전체가 깨진다. findById는 삭제된 계좌도 찾아야 하고, findMyScope는 계속 제외해야 한다.
    @Test
    void softDeletedAccount_stillFindableById_butExcludedFromMyScope() {
        FinanceAccount saved = adapter.save(groupAccount("삭제예정계좌", null));

        adapter.softDelete(saved.id());

        assertThat(adapter.findById(saved.id())).isPresent();
        assertThat(adapter.findMyScope(userId, groupId)).isEmpty();
    }

    @Test
    void findActiveById_onSoftDeletedAccount_returnsEmpty() {
        FinanceAccount saved = adapter.save(groupAccount("삭제예정계좌", null));

        adapter.softDelete(saved.id());

        assertThat(adapter.findActiveById(saved.id())).isEmpty();
        assertThat(adapter.findById(saved.id())).isPresent();
    }

    // 회귀(플랜 항목 4): findMyScope는 (내 개인) ∪ (내 그룹)만 반환 — 타인의 개인 계좌는 유출되면 안 된다.
    @Test
    void findMyScope_returnsPersonalUnionGroup_excludingOthersPersonalData() {
        FinanceAccount myPersonal = adapter.save(personalAccount(userId, "내개인계좌", null));
        FinanceAccount myGroup = adapter.save(groupAccount("내그룹계좌", null));
        FinanceAccount othersPersonal = adapter.save(personalAccount(otherUserId, "타인개인계좌", null));

        var result = adapter.findMyScope(userId, groupId);

        assertThat(result).extracting(FinanceAccount::id)
                .contains(myPersonal.id(), myGroup.id())
                .doesNotContain(othersPersonal.id());
    }

    @Test
    void findMyScope_noGroup_returnsOnlyPersonalAccounts() {
        FinanceAccount myPersonal = adapter.save(personalAccount(userId, "내개인계좌", null));
        FinanceAccount myGroup = adapter.save(groupAccount("내그룹계좌", null));

        var result = adapter.findMyScope(userId, null);

        assertThat(result).extracting(FinanceAccount::id)
                .contains(myPersonal.id())
                .doesNotContain(myGroup.id());
    }

    @Test
    void existsByAccountNo_activeDuplicate_returnsTrue() {
        adapter.save(groupAccount("토스증권 일반계좌", "999-888-777"));

        assertThat(adapter.existsByAccountNo("999-888-777", null)).isTrue();
        assertThat(adapter.existsByAccountNo("000-000-000", null)).isFalse();
    }

    @Test
    void existsByAccountNo_excludesGivenId_forSelfUpdate() {
        FinanceAccount saved = adapter.save(groupAccount("토스증권 일반계좌", "999-888-777"));

        assertThat(adapter.existsByAccountNo("999-888-777", saved.id())).isFalse();
        assertThat(adapter.existsByAccountNo("999-888-777", UUID.randomUUID())).isTrue();
    }

    @Test
    void existsByAccountNo_softDeletedAccount_isExcluded() {
        FinanceAccount saved = adapter.save(groupAccount("토스증권 일반계좌", "999-888-777"));
        adapter.softDelete(saved.id());

        assertThat(adapter.existsByAccountNo("999-888-777", null)).isFalse();
    }

    @Test
    void softDeleteByUserId_softDeletesOnlyThatUsersOwnedAccounts() {
        FinanceAccount mine = adapter.save(personalAccount(userId, "내계좌", null));
        FinanceAccount others = adapter.save(personalAccount(otherUserId, "타인계좌", null));

        adapter.softDeleteByUserId(userId);

        assertThat(adapter.findActiveById(mine.id())).isEmpty();
        assertThat(adapter.findActiveById(others.id())).isPresent();
    }
}
