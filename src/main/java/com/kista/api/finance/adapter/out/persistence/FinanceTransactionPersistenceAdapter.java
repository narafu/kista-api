package com.kista.api.finance.adapter.out.persistence;

import com.kista.api.finance.domain.model.FinanceTransaction;
import com.kista.api.finance.domain.model.FinanceTransactionCursor;
import com.kista.api.finance.domain.model.FinanceTransactionPageQuery;
import com.kista.api.finance.domain.model.FinanceTransactionPageRow;
import com.kista.api.finance.domain.model.FinanceTransactionSort;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;
import com.kista.api.finance.application.port.output.FinanceTransactionPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FinanceTransactionPersistenceAdapter implements FinanceTransactionPort {

    private final FinanceTransactionJpaRepository jpaRepository;
    private final EntityManager entityManager; // keyset 동적 native 조회용
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    @Override
    public List<FinanceTransaction> findMyScope(UUID userId, UUID currentGroupId, LocalDate from, LocalDate to, UUID categoryId, UUID filterUserId) {
        return jpaRepository.findMyScope(userId, currentGroupId, from, to, categoryId, filterUserId).stream()
                .map(FinanceTransactionEntity::toDomain)
                .toList();
    }

    @Override
    public List<FinanceTransactionPageRow> findPage(FinanceTransactionPageQuery q) {
        FinanceTransactionSort sort = q.sort();
        // 정렬 표현식은 enum switch로만 만든다(사용자 문자열 SQL 삽입 금지)
        String x = switch (sort.key()) {
            case TRANSACTION_DATE -> "t.transaction_date";
            case AMOUNT -> "t.amount";
            case MEMO -> "NULLIF(t.memo, '')";
            case CATEGORY -> "c.name";
        };
        String dir = sort.ascending() ? "ASC" : "DESC";
        String op = sort.ascending() ? ">" : "<";
        Map<String, Object> params = new java.util.HashMap<>();
        List<String> where = new ArrayList<>();
        // 개인 ∪ 현재 그룹 스코프
        params.put("userId", q.userId());
        if (q.groupId() != null) {
            where.add("((t.user_id = :userId AND t.group_id IS NULL) OR t.group_id = :groupId)");
            params.put("groupId", q.groupId());
        } else {
            where.add("(t.user_id = :userId AND t.group_id IS NULL)");
        }
        if (q.from() != null) {
            where.add("t.transaction_date >= :from");
            params.put("from", q.from());
        }
        if (q.to() != null) {
            where.add("t.transaction_date <= :to");
            params.put("to", q.to());
        }
        if (q.categoryIds() != null) {
            where.add("t.category_id IN (:categoryIds)");
            params.put("categoryIds", q.categoryIds());
        }
        if (q.filterUserId() != null) {
            where.add("t.user_id = :filterUserId");
            params.put("filterUserId", q.filterUserId());
        }
        if (q.memo() != null) {
            where.add("strpos(lower(t.memo), lower(:memo)) > 0");
            params.put("memo", q.memo());
        }
        // keyset: 정렬 순서상 커서 행 다음만 — 값 없는 행은 항상 맨 뒤
        FinanceTransactionCursor cursor = q.cursor();
        if (cursor != null) {
            params.put("cid", cursor.id().toString());
            if (cursor.value() == null) {
                where.add("(" + x + " IS NULL AND t.id " + op + " CAST(:cid AS uuid))");
            } else {
                String cast = switch (sort.key()) {
                    case TRANSACTION_DATE -> "CAST(:cv AS date)";
                    case AMOUNT -> "CAST(:cv AS bigint)";
                    case MEMO, CATEGORY -> "CAST(:cv AS varchar)";
                };
                params.put("cv", cursor.value());
                where.add("((" + x + " IS NULL) OR " + x + " " + op + " " + cast
                        + " OR (" + x + " = " + cast + " AND t.id " + op + " CAST(:cid AS uuid)))");
            }
        }
        String sql = "SELECT t.*, c.name AS category_name FROM finance.finance_transactions t "
                + "LEFT JOIN finance.finance_categories c ON c.id = t.category_id "
                + "WHERE t.deleted_at IS NULL AND " + String.join(" AND ", where)
                + " ORDER BY (" + x + " IS NULL), " + x + " " + dir + ", t.id " + dir
                + " LIMIT :limit";
        NativeQuery<?> query = entityManager.createNativeQuery(sql, Object[].class).unwrap(NativeQuery.class)
                .addEntity("t", FinanceTransactionEntity.class)
                .addScalar("category_name", String.class);
        params.forEach((k, v) -> {
            if (v instanceof java.util.Collection<?> c) {
                query.setParameterList(k, c);
            } else {
                query.setParameter(k, v);
            }
        });
        query.setParameter("limit", q.limit());
        return query.getResultList().stream()
                .map(r -> (Object[]) r)
                .map(r -> new FinanceTransactionPageRow(FinanceTransactionEntity.toDomain((FinanceTransactionEntity) r[0]), (String) r[1]))
                .toList();
    }

    @Override
    public Optional<FinanceTransaction> findById(UUID id) {
        return jpaRepository.findById(id).map(FinanceTransactionEntity::toDomain);
    }

    @Override
    public FinanceTransaction save(FinanceTransaction transaction) {
        FinanceTransactionEntity entity = FinanceTransactionEntity.fromModel(transaction);
        return FinanceTransactionEntity.toDomain(jpaRepository.save(entity));
    }

    @Override
    public void softDelete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now(clock));
    }

    @Override
    public void softDeleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now(clock));
    }
}
