package com.kista.api.finance.application.service;

import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.AssetSnapshot;
import com.kista.api.finance.domain.model.AssetSnapshotAggregates.*;
import com.kista.api.finance.application.port.output.FinanceAccountPort;
import com.kista.api.finance.domain.model.AssetSnapshotCommand;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.application.port.output.AssetSnapshotPort;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
import com.kista.api.finance.application.port.output.FinanceGroupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// 구 AssetService 대체 — category/subcategory enum+자유텍스트가 categoryId FK로 승격됐다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AssetSnapshotService {

    private final AssetSnapshotPort assetSnapshotPort;
    private final FinanceGroupPort financeGroupPort;
    private final FinanceCategoryPort financeCategoryPort;
    private final FinanceAccountPort financeAccountPort; // 스냅샷 계좌명 조회
    private final MonthlyClosingGuard monthlyClosingGuard;

    @Transactional(readOnly = true)
    public List<AssetSnapshot> list(UUID userId, LocalDate from, LocalDate to, UUID filterUserId) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return assetSnapshotPort.findMyScope(userId, currentGroupId, from, to, filterUserId);
    }

    // shareToGroup=true면 소유자의 현재 그룹 소유로, false면 개인 소유로 원자적 생성한다.
    public AssetSnapshot create(UUID userId, boolean shareToGroup, AssetSnapshotCommand command) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        verifyAssetCategory(userId, currentGroupId, command.categoryId());
        // 기록 점검 완료월에는 신규 등록 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, command.entryDate());
        // 그룹 공유를 요청했는데 소속 그룹이 없으면 거부
        GroupShareSupport.requireGroupIfSharing(shareToGroup, currentGroupId);
        UUID ownerGroupId = shareToGroup ? currentGroupId : null;
        AssetSnapshot snapshot = new AssetSnapshot(null, ownerGroupId, command.categoryId(), command.accountId(), userId,
                command.entryDate(), command.assetClass(), command.market(), command.strategy(), command.memo(), command.amount(), null);
        AssetSnapshot saved = assetSnapshotPort.save(snapshot);
        log.info("[userId={}] 자산 스냅샷 등록: snapshotId={}", userId, saved.id());
        return saved;
    }

    public AssetSnapshot update(UUID snapshotId, UUID userId, AssetSnapshotCommand command) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        UUID currentGroupId = FinanceAccessSupport.verifyAccessible(existing, userId, financeGroupPort);
        verifyAssetCategory(userId, currentGroupId, command.categoryId());
        // 마감월에서 빼내기(existing) + 마감월로 넣기(command) 둘 다 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.entryDate());
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, command.entryDate());
        AssetSnapshot updated = new AssetSnapshot(existing.id(), existing.groupId(), command.categoryId(),
                command.accountId(), existing.userId(), command.entryDate(), command.assetClass(),
                command.market(), command.strategy(), command.memo(), command.amount(), existing.createdAt());
        return assetSnapshotPort.save(updated);
    }

    // categoryId가 실제로 접근 가능하고(시스템이거나 본인/내 그룹 소유) type=ASSET인지 확인한다.
    private void verifyAssetCategory(UUID userId, UUID currentGroupId, UUID categoryId) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(categoryId);
        category.verifyAccessibleBy(userId, currentGroupId);
        if (category.type() != FinanceCategory.Type.ASSET) {
            throw new IllegalArgumentException("자산 카테고리만 자산 스냅샷에 사용할 수 있습니다.");
        }
    }

    public void delete(UUID snapshotId, UUID userId) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        UUID currentGroupId = FinanceAccessSupport.verifyAccessible(existing, userId, financeGroupPort);
        // 마감월 기록은 삭제도 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.entryDate());
        assetSnapshotPort.softDelete(snapshotId);
        log.info("[userId={}] 자산 스냅샷 삭제: snapshotId={}", userId, snapshotId);
    }

    // 개인 소유 자산 기록을 소유자가 자신의 현재 그룹으로 전환한다. 본인 것만 전환 가능(그룹 멤버 전체 아님).
    public AssetSnapshot shareToGroup(UUID snapshotId, UUID userId) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 마감월 기록은 그룹 공유 전환도 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.entryDate());
        return GroupShareSupport.shareToGroup(existing, userId, Optional.ofNullable(currentGroupId),
                        "본인 소유 자산 기록만 그룹에 공유할 수 있습니다")
                .map(shared -> {
                    AssetSnapshot saved = assetSnapshotPort.save(shared);
                    log.info("자산 기록 그룹 공유 전환: snapshotId={}, groupId={}", snapshotId, saved.groupId());
                    return saved;
                })
                .orElse(existing);
    }

    // 그룹 공유 자산 기록을 개인 소유로 되돌린다. 소유자는 그대로 유지, groupId만 null로.
    // 같은 그룹 멤버면 누구든 가능(소유자 한정 아님).
    public AssetSnapshot unshare(UUID snapshotId, UUID userId) {
        AssetSnapshot existing = assetSnapshotPort.findByIdOrThrow(snapshotId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 마감월 기록은 귀속 해제도 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.entryDate());
        return GroupShareSupport.unshare(existing, userId, currentGroupId)
                .map(personal -> {
                    AssetSnapshot saved = assetSnapshotPort.save(personal);
                    log.info("[userId={}] 자산 기록 그룹 공유 해제: snapshotId={}", userId, snapshotId);
                    return saved;
                })
                .orElse(existing);
    }

    // ---- 집계 (UI entities/finance/lib/aggregate.ts와 같은 값) ----

    // 스냅샷 + 루트(L1) 카테고리 id + 자기 카테고리명 — 집계 입력 단위
    private record Row(AssetSnapshot snapshot, UUID rootId, String categoryName) {
        boolean liability() {
            return FinanceCategory.SYSTEM_LOAN_ID.equals(rootId);
        }

        YearMonth month() {
            return YearMonth.from(snapshot.entryDate());
        }

        long amount() {
            return snapshot.amount();
        }
    }

    private record AccountKey(String accountName, String categoryName, AssetClass assetClass) {}

    @Transactional(readOnly = true)
    public AssetSummaryResult summary(UUID userId, YearMonth month) {
        Map<YearMonth, List<Row>> byMonth = rowsByMonth(userId);
        // 내림차순 기록 월
        List<YearMonth> available = new ArrayList<>(byMonth.keySet());
        available.sort(Comparator.reverseOrder());
        YearMonth target = month != null ? month : available.stream().findFirst().orElse(null);
        if (target == null) {
            return new AssetSummaryResult(null, available, null, null);
        }
        YearMonth previous = previousMonthOf(available, target);
        return new AssetSummaryResult(target, available,
                summarize(target, byMonth.getOrDefault(target, List.of())),
                previous != null ? summarize(previous, byMonth.get(previous)) : null);
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trend(UUID userId, Integer months, YearMonth endMonth) {
        Map<YearMonth, List<Row>> byMonth = rowsByMonth(userId);
        return selectMonths(byMonth, months, endMonth).stream().map(m -> {
            List<Row> rows = byMonth.get(m);
            long totalAssets = sum(rows, r -> !r.liability());
            return new TrendPoint(m, totalAssets - sum(rows, Row::liability), totalAssets,
                    categoryAmounts(rows), assetClassAmounts(rows, true));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<CompositionColumn> composition(UUID userId, Basis basis, Integer months, YearMonth endMonth) {
        Map<YearMonth, List<Row>> byMonth = rowsByMonth(userId);
        return selectMonths(byMonth, months, endMonth).stream().map(m -> {
            List<Row> rows = byMonth.get(m);
            // 항목별 금액 — 카테고리는 대출 포함, 자산군은 대출 제외
            Map<String, Long> amounts = new LinkedHashMap<>();
            if (basis == Basis.CATEGORY) {
                categoryAmounts(rows).forEach(c -> amounts.put(c.categoryId().toString(), c.amount()));
            } else {
                assetClassAmounts(rows, true).forEach(c -> amounts.put(c.assetClass().name(), c.amount()));
            }
            long total = amounts.values().stream().mapToLong(Long::longValue).sum();
            List<CompositionEntry> entries = amounts.entrySet().stream()
                    .map(e -> new CompositionEntry(e.getKey(), e.getValue(), percent(e.getValue(), total)))
                    .toList();
            return new CompositionColumn(m, total, entries);
        }).toList();
    }

    @Transactional(readOnly = true)
    public AssetCheckResult check(UUID userId, YearMonth month) {
        Map<YearMonth, List<Row>> byMonth = rowsByMonth(userId);
        List<Row> rows = byMonth.getOrDefault(month, List.of());
        YearMonth previous = previousMonthOf(new ArrayList<>(byMonth.keySet()), month);

        // 이번 달 기록이 없는 L1
        List<UUID> present = rows.stream().map(Row::rootId).distinct().toList();
        List<UUID> missingCategoryIds = FinanceCategory.ASSET_L1_DISPLAY_ORDER.stream()
                .filter(id -> !present.contains(id)).toList();

        // 전월에는 있었지만 이번 달에 없는 (계좌명, 카테고리명, 자산군) — 키별 한 건, 전월 등장 순서
        List<MissingAccount> missingAccounts = List.of();
        if (previous != null) {
            Map<UUID, String> accountNames = new HashMap<>();
            Set<AccountKey> current = rows.stream().map(r -> accountKey(r, accountNames)).collect(Collectors.toSet());
            Map<AccountKey, Row> missing = new LinkedHashMap<>();
            for (Row r : byMonth.get(previous)) {
                AccountKey key = accountKey(r, accountNames);
                if (!current.contains(key)) {
                    missing.put(key, r);
                }
            }
            missingAccounts = missing.keySet().stream()
                    .map(k -> new MissingAccount(k.accountName(), k.categoryName(), k.assetClass())).toList();
        }

        // 날짜 오름차순 건수
        Map<LocalDate, Long> counts = new TreeMap<>();
        rows.forEach(r -> counts.merge(r.snapshot().entryDate(), 1L, Long::sum));
        List<DateGroup> dateGroups = counts.entrySet().stream().map(e -> new DateGroup(e.getKey(), e.getValue())).toList();
        return new AssetCheckResult(month, previous, missingCategoryIds, missingAccounts, dateGroups);
    }

    private AccountKey accountKey(Row row, Map<UUID, String> accountNames) {
        UUID accountId = row.snapshot().accountId();
        String name = null;
        if (accountId != null) {
            name = accountNames.computeIfAbsent(accountId, id -> financeAccountPort.findByIdOrThrow(id).name());
        }
        return new AccountKey(name, row.categoryName(), row.snapshot().assetClass());
    }

    // 스코프 전체 스냅샷을 루트 판정해 기록 월별로 묶는다(월 오름차순)
    private Map<YearMonth, List<Row>> rowsByMonth(UUID userId) {
        UUID groupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 선택 가능 카테고리로 시드하고, 삭제된 카테고리는 findByIdOrThrow로 보충하는 요청 단위 메모
        Map<UUID, FinanceCategory> memo = new HashMap<>();
        financeCategoryPort.findSelectable(userId, groupId, FinanceCategory.Type.ASSET)
                .forEach(c -> memo.put(c.id(), c));
        Map<YearMonth, List<Row>> byMonth = new TreeMap<>();
        for (AssetSnapshot s : assetSnapshotPort.findMyScope(userId, groupId, null, null, null)) {
            FinanceCategory category = category(memo, s.categoryId());
            FinanceCategory root = category;
            // 임의 depth 트리 루트까지 거슬러 올라감(AssetSnapshotController.enrich와 같은 규칙)
            while (root.parentId() != null) {
                root = category(memo, root.parentId());
            }
            Row row = new Row(s, root.id(), category.name());
            byMonth.computeIfAbsent(row.month(), m -> new ArrayList<>()).add(row);
        }
        return byMonth;
    }

    private FinanceCategory category(Map<UUID, FinanceCategory> memo, UUID id) {
        FinanceCategory found = memo.get(id);
        if (found == null) {
            found = financeCategoryPort.findByIdOrThrow(id);
            memo.put(id, found);
        }
        return found;
    }

    private static MonthlyAssetSummary summarize(YearMonth month, List<Row> rows) {
        long totalAssets = sum(rows, r -> !r.liability());
        long totalLiabilities = sum(rows, Row::liability);
        // 대출 제외 자산군 최대 — 동액이면 먼저 나온 자산군(엄격한 > 비교)
        Map<AssetClass, Long> byClass = new LinkedHashMap<>();
        rows.stream().filter(r -> !r.liability())
                .forEach(r -> byClass.merge(r.snapshot().assetClass(), r.amount(), Long::sum));
        AssetClassAmount largest = null;
        for (Map.Entry<AssetClass, Long> e : byClass.entrySet()) {
            if (largest == null || e.getValue() > largest.amount()) {
                largest = new AssetClassAmount(e.getKey(), e.getValue());
            }
        }
        return new MonthlyAssetSummary(month, totalAssets - totalLiabilities, totalAssets, totalLiabilities, largest,
                rows.size(), categoryAmounts(rows), assetClassAmounts(rows, false));
    }

    // L1 4개 고정 순서, 0 포함
    private static List<CategoryAmount> categoryAmounts(List<Row> rows) {
        return FinanceCategory.ASSET_L1_DISPLAY_ORDER.stream()
                .map(id -> new CategoryAmount(id, sum(rows, r -> id.equals(r.rootId())))).toList();
    }

    // 부채 제외, enum 순서. includeZero=false면 0원 제외
    private static List<AssetClassAmount> assetClassAmounts(List<Row> rows, boolean includeZero) {
        return Arrays.stream(AssetClass.values())
                .map(c -> new AssetClassAmount(c, sum(rows, r -> !r.liability() && r.snapshot().assetClass() == c)))
                .filter(a -> includeZero || a.amount() > 0).toList();
    }

    private static long sum(List<Row> rows, Predicate<Row> filter) {
        return rows.stream().filter(filter).mapToLong(Row::amount).sum();
    }

    private static BigDecimal percent(long amount, long total) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    // month보다 작은 월 중 가장 최근(UI previousMonthOf)
    private static YearMonth previousMonthOf(List<YearMonth> months, YearMonth month) {
        return months.stream().filter(m -> m.isBefore(month)).max(Comparator.naturalOrder()).orElse(null);
    }

    // 기록 월 중 endMonth 이하의 최근 months개(오름차순). months<1이면 400
    private static List<YearMonth> selectMonths(Map<YearMonth, List<Row>> byMonth, Integer months, YearMonth endMonth) {
        if (months != null && months < 1) {
            throw new IllegalArgumentException("조회 개월 수는 1 이상이어야 합니다.");
        }
        List<YearMonth> selected = byMonth.keySet().stream()
                .filter(m -> endMonth == null || !m.isAfter(endMonth)).toList();
        return months == null || months >= selected.size() ? selected : selected.subList(selected.size() - months, selected.size());
    }
}
