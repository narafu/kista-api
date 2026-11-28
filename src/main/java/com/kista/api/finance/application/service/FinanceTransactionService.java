package com.kista.api.finance.application.service;

import com.kista.api.finance.domain.model.BudgetProgress;
import com.kista.api.finance.domain.model.FinanceBudget;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FlowPeriod;
import com.kista.api.finance.domain.model.FlowPeriod.DateRange;
import com.kista.api.finance.domain.model.FlowSummary;
import com.kista.api.finance.domain.model.FlowTrendPoint;
import com.kista.api.finance.application.port.output.FinanceBudgetPort;
import com.kista.api.finance.domain.model.FinanceTransaction;
import com.kista.api.finance.domain.model.FinanceTransactionCommand;
import com.kista.api.finance.domain.model.FinanceTransactionCursor;
import com.kista.api.finance.domain.model.FinanceTransactionListFilter;
import com.kista.api.finance.domain.model.FinanceTransactionPageQuery;
import com.kista.api.finance.domain.model.FinanceTransactionPageRow;
import com.kista.api.finance.domain.model.FinanceTransactionSort;
import com.kista.shared.platform.page.CursorPage;
import com.kista.shared.platform.page.PageQuery;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
import com.kista.api.finance.application.port.output.FinanceGroupPort;
import com.kista.api.finance.application.port.output.FinanceTransactionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FinanceTransactionService {

    private final FinanceTransactionPort transactionPort;
    private final FinanceGroupPort financeGroupPort;
    private final FinanceCategoryPort financeCategoryPort;
    private final MonthlyClosingGuard monthlyClosingGuard;
    private final FinanceBudgetPort budgetPort; // 예산 조회(예산 대비 실적)
    private final Clock clock; // 집계의 "오늘" 기준 — 테스트에서 Clock.fixed 주입

    // 거래 집계용 카테고리 인덱스 항목 — 루트 정보는 추이 byCategory 정렬에 쓴다
    private record CategoryEntry(FinanceCategory.Type type, UUID rootId, String rootName, int rootSortOrder, String name) {}

    @Transactional(readOnly = true)
    public CursorPage<FinanceTransaction> list(UUID userId, FinanceTransactionListFilter filter,
                                               FinanceTransactionSort sort, PageQuery page) {
        if (filter.type() == FinanceCategory.Type.ASSET) {
            throw new IllegalArgumentException("자산 유형은 거래 목록에 사용할 수 없습니다.");
        }
        UUID groupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 커서는 같은 sort로 만든 것만 받는다
        FinanceTransactionCursor cursor = page.cursor() == null ? null : FinanceTransactionCursor.decode(page.cursor(), sort);
        Set<UUID> categoryIds = resolveCategoryIds(userId, groupId, filter);
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new CursorPage<>(List.of(), null, false);
        }
        String memo = filter.memo() == null || filter.memo().isBlank() ? null : filter.memo().trim();
        FinanceTransactionPageQuery query = new FinanceTransactionPageQuery(userId, groupId, filter.from(), filter.to(),
                categoryIds, filter.filterUserId(), memo, sort, cursor, page.fetchSize());
        return CursorPage.of(transactionPort.findPage(query), page.size(), r -> cursorOf(sort, r).encode())
                .map(FinanceTransactionPageRow::transaction);
    }

    // type·categoryId(하위 트리 포함) 교집합 — 둘 다 없으면 null(미분류 거래 포함 제한 없음)
    private Set<UUID> resolveCategoryIds(UUID userId, UUID groupId, FinanceTransactionListFilter filter) {
        if (filter.type() == null && filter.categoryId() == null) {
            return null;
        }
        List<FinanceCategory> selectable = financeCategoryPort.findSelectable(userId, groupId, null);
        Map<UUID, CategoryEntry> index = flowIndex(selectable);
        Set<UUID> ids = new HashSet<>(index.keySet());
        if (filter.categoryId() != null) {
            ids.retainAll(FinanceCategory.subtreeIds(selectable, filter.categoryId()));
        }
        if (filter.type() != null) {
            ids.removeIf(id -> index.get(id).type() != filter.type());
        }
        if (filter.categoryId() != null && !index.containsKey(filter.categoryId())) {
            // 삭제됐거나 타 멤버 개인 카테고리처럼 선택 목록 밖이어도 지정한 카테고리 자체는 포함(목록 안이면 type 교집합 유지)
            ids.add(filter.categoryId());
        }
        return ids;
    }

    // 마지막 행의 정렬 값 — 값 없음(null·빈 메모, 이름 없는 카테고리)은 null
    private FinanceTransactionCursor cursorOf(FinanceTransactionSort sort, FinanceTransactionPageRow row) {
        FinanceTransaction t = row.transaction();
        String value = switch (sort.key()) {
            case TRANSACTION_DATE -> t.transactionDate().toString();
            case AMOUNT -> String.valueOf(t.amount());
            case MEMO -> t.memo() == null || t.memo().isEmpty() ? null : t.memo();
            case CATEGORY -> row.categoryName();
        };
        return new FinanceTransactionCursor(sort, value, t.id());
    }

    // shareToGroup=true면 소유자의 현재 그룹 소유로, false면 개인 소유로 원자적 생성한다.
    public FinanceTransaction create(UUID userId, boolean shareToGroup, FinanceTransactionCommand command) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        verifyCategory(userId, currentGroupId, command.categoryId());
        // 기록 점검 완료월에는 신규 등록 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, command.transactionDate());
        // 그룹 공유 생성을 요청했는데 소속 그룹이 없으면 거부
        GroupShareSupport.requireGroupIfSharing(shareToGroup, currentGroupId);
        UUID ownerGroupId = shareToGroup ? currentGroupId : null;
        FinanceTransaction transaction = new FinanceTransaction(null, ownerGroupId, command.categoryId(), userId,
                command.transactionDate(), command.amount(), command.memo(), null);
        FinanceTransaction saved = transactionPort.save(transaction);
        log.info("[userId={}] 거래내역 등록: transactionId={}", userId, saved.id());
        return saved;
    }

    public FinanceTransaction update(UUID transactionId, UUID userId, FinanceTransactionCommand command) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        UUID currentGroupId = FinanceAccessSupport.verifyAccessible(existing, userId, financeGroupPort);
        verifyCategory(userId, currentGroupId, command.categoryId());
        // 마감월에서 빼내기(existing) + 마감월로 넣기(command) 둘 다 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.transactionDate());
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, command.transactionDate());
        FinanceTransaction updated = new FinanceTransaction(existing.id(), existing.groupId(), command.categoryId(),
                existing.userId(), command.transactionDate(), command.amount(), command.memo(), existing.createdAt());
        return transactionPort.save(updated);
    }

    // categoryId가 실제로 접근 가능한지(시스템이거나 본인/내 그룹 소유)와, 자산 스냅샷 전용 타입이 아닌지 확인한다.
    private void verifyCategory(UUID userId, UUID currentGroupId, UUID categoryId) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(categoryId);
        category.verifyAccessibleBy(userId, currentGroupId);
        if (category.type() == FinanceCategory.Type.ASSET) {
            throw new IllegalArgumentException("자산 카테고리는 거래내역에 사용할 수 없습니다.");
        }
    }

    public void delete(UUID transactionId, UUID userId) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        UUID currentGroupId = FinanceAccessSupport.verifyAccessible(existing, userId, financeGroupPort);
        // 마감월 거래는 삭제도 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.transactionDate());
        transactionPort.softDelete(transactionId);
        log.info("[userId={}] 거래내역 삭제: transactionId={}", userId, transactionId);
    }

    // 수입·소비·저축 기간 요약. 월간은 전월 비교, 연간은 전년 비교를 함께 계산한다.
    @Transactional(readOnly = true)
    public FlowSummary summary(UUID userId, FinanceCategory.Type type, FlowPeriod period) {
        requireFlowType(type);
        LocalDate today = LocalDate.now(clock);
        boolean monthly = period.mode() == FlowPeriod.Mode.MONTHLY;
        DateRange range = period.range(today);
        // 연 평균 기준일: 월간은 선택월 말일, 연간은 오늘
        LocalDate refDate = monthly ? period.month().atEndOfMonth() : today;
        FlowPeriod yearPeriod = new FlowPeriod(YearMonth.of(period.month().getYear(), 1), FlowPeriod.Mode.YEARLY);
        DateRange yearRange = yearPeriod.range(refDate);
        DateRange previous = monthly
                ? new FlowPeriod(period.month().minusMonths(1), FlowPeriod.Mode.MONTHLY).range(today)
                : period.previousYearRange(today);

        // 필요한 범위를 한 번에 조회한 뒤 메모리에서 자른다
        List<DateRange> ranges = List.of(range, yearRange, previous);
        UUID groupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        Map<UUID, CategoryEntry> index = flowIndex(financeCategoryPort.findSelectable(userId, groupId, null));
        List<FinanceTransaction> all = transactionPort.findMyScope(userId, groupId,
                ranges.stream().map(DateRange::from).min(Comparator.naturalOrder()).orElseThrow(),
                ranges.stream().map(DateRange::to).max(Comparator.naturalOrder()).orElseThrow(), null, null);

        long total = sum(all, index, type, range);
        int count = (int) inRange(all, index, type, range).count();
        long previousSum = sum(all, index, type, previous);
        long yearTotal = sum(all, index, type, yearRange);
        long average = BigDecimal.valueOf(yearTotal)
                .divide(BigDecimal.valueOf(yearPeriod.elapsedMonthsInYear(refDate)), 0, RoundingMode.HALF_UP).longValue();
        boolean income = type == FinanceCategory.Type.INCOME;
        Long remaining = income ? total - sum(all, index, FinanceCategory.Type.EXPENSE, range)
                - sum(all, index, FinanceCategory.Type.SAVING, range) : null;
        long incomeTotal = income ? total : sum(all, index, FinanceCategory.Type.INCOME, range);
        BigDecimal incomeRatio = !income && incomeTotal > 0 ? ratio(total, incomeTotal) : null;
        return new FlowSummary(total, count, monthly ? previousSum : null, monthly ? null : previousSum,
                average, remaining, incomeRatio);
    }

    // 수입·소비·저축 추이. range(버킷 수)가 null이면 가장 오래된 거래가 속한 월/연도부터 전체.
    @Transactional(readOnly = true)
    public List<FlowTrendPoint> trend(UUID userId, FinanceCategory.Type type, FlowPeriod period, Integer range) {
        requireFlowType(type);
        if (range != null && (range < 1 || range > 120)) {
            throw new IllegalArgumentException("조회 기간은 1 이상 120 이하여야 합니다.");
        }
        LocalDate today = LocalDate.now(clock);
        UUID groupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        Map<UUID, CategoryEntry> index = flowIndex(financeCategoryPort.findSelectable(userId, groupId, null));
        LocalDate last = period.buckets(today, 1).getFirst().range().to();
        List<FlowPeriod.Bucket> buckets = range != null ? period.buckets(today, range) : null;
        // 전체 조회는 하한 없이 가져와 가장 오래된 그 유형 거래로 버킷 수를 정한다
        List<FinanceTransaction> typed = transactionPort.findMyScope(userId, groupId,
                        buckets != null ? buckets.getFirst().range().from() : null, last, null, null)
                .stream().filter(typeIs(index, type)).toList();
        if (buckets == null) {
            int limit = typed.stream().map(FinanceTransaction::transactionDate).min(Comparator.naturalOrder())
                    .map(period::bucketCountSince).orElse(1);
            buckets = period.buckets(today, limit);
        }
        Collator collator = Collator.getInstance(Locale.KOREAN);
        // 루트 정렬순서(sortOrder, 동순위 가나다) 순
        Comparator<UUID> rootOrder = Comparator.<UUID>comparingInt(id -> index.get(id).rootSortOrder())
                .thenComparing(id -> index.get(id).rootName(), collator);
        return buckets.stream().map(bucket -> {
            List<FinanceTransaction> inBucket = typed.stream().filter(t -> bucket.range().contains(t.transactionDate())).toList();
            Map<UUID, Long> byRoot = inBucket.stream().collect(Collectors.groupingBy(
                    t -> index.get(t.categoryId()).rootId(), Collectors.summingLong(FinanceTransaction::amount)));
            List<FlowTrendPoint.RootAmount> byCategory = byRoot.keySet().stream().sorted(rootOrder)
                    .map(id -> new FlowTrendPoint.RootAmount(id, byRoot.get(id))).toList();
            return new FlowTrendPoint(bucket.label(), inBucket.stream().mapToLong(FinanceTransaction::amount).sum(), byCategory);
        }).toList();
    }

    // 예산 대비 실적 트리 + 예산 없는 실적 카테고리
    @Transactional(readOnly = true)
    public BudgetProgress budgetProgress(UUID userId, FinanceCategory.Type type, FlowPeriod period) {
        requireFlowType(type);
        LocalDate today = LocalDate.now(clock);
        DateRange range = period.range(today);
        UUID groupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        List<FinanceCategory> categories = financeCategoryPort.findSelectable(userId, groupId, null);
        Map<UUID, CategoryEntry> index = flowIndex(categories);
        // 그 유형 카테고리 트리 — 형제 정렬은 sortOrder 오름차순, 동순위 가나다
        Collator collator = Collator.getInstance(Locale.KOREAN);
        Comparator<FinanceCategory> order = Comparator.comparingInt(FinanceCategory::sortOrder)
                .thenComparing(FinanceCategory::name, collator);
        List<FinanceCategory> ofType = categories.stream().filter(c -> c.type() == type).toList();
        Map<UUID, List<FinanceCategory>> childrenOf = ofType.stream().filter(c -> c.parentId() != null)
                .sorted(order).collect(Collectors.groupingBy(FinanceCategory::parentId));
        List<FinanceCategory> roots = ofType.stream().filter(c -> c.parentId() == null).sorted(order).toList();

        Map<UUID, Long> amountOf = transactionPort.findMyScope(userId, groupId, range.from(), range.to(), null, null)
                .stream().filter(typeIs(index, type)).collect(
                        Collectors.groupingBy(FinanceTransaction::categoryId, Collectors.summingLong(FinanceTransaction::amount)));

        // 예산 유효 개월 수 구간 — 연간은 1월부터, 끝은 기간 끝의 월(올해면 오늘 달)
        YearMonth fromMonth = period.mode() == FlowPeriod.Mode.YEARLY ? YearMonth.of(period.month().getYear(), 1) : period.month();
        YearMonth toMonth = YearMonth.from(range.to());
        // 카테고리별 배정액 = 유효 예산마다 월 금액 × 유효 개월 수의 합(유효 개월 0이면 제외), 대표 예산은 적용 시작일이 가장 늦은 것
        Map<UUID, FinanceBudget> budgetOf = new HashMap<>();
        Map<UUID, Long> allocatedOf = new HashMap<>();
        for (FinanceBudget b : budgetPort.findMyScope(userId, groupId, null, null)) {
            CategoryEntry entry = index.get(b.categoryId());
            if (entry == null || entry.type() != type) continue;
            int months = validMonths(b, fromMonth, toMonth);
            if (months == 0) continue;
            budgetOf.merge(b.categoryId(), b, (a, c) -> c.applyStartDate().isAfter(a.applyStartDate()) ? c : a);
            allocatedOf.merge(b.categoryId(), b.amount() * months, Long::sum);
        }
        // 예산이 덮는 카테고리 = 유효 예산 카테고리의 서브트리 전체
        Set<UUID> covered = new HashSet<>();
        budgetOf.keySet().forEach(id -> collectSubtree(id, childrenOf, covered));

        List<BudgetProgress.Node> nodes = roots.stream()
                .map(r -> buildNode(r, 0, childrenOf, budgetOf, allocatedOf, amountOf)).filter(Objects::nonNull).toList();
        // 표시 순서(전위 순회)대로 예산에 덮이지 않은 실적
        List<BudgetProgress.Unbudgeted> unbudgeted = new ArrayList<>();
        roots.forEach(r -> collectUnbudgeted(r, childrenOf, covered, amountOf, unbudgeted));
        return new BudgetProgress(nodes, unbudgeted);
    }

    // 자체 예산 노드는 그 값 + 하위 중첩, 예산 없고 하위 예산 있으면 소계 그룹, 둘 다 없으면 가지치기
    private BudgetProgress.Node buildNode(FinanceCategory category, int depth, Map<UUID, List<FinanceCategory>> childrenOf,
                                          Map<UUID, FinanceBudget> budgetOf, Map<UUID, Long> allocatedOf, Map<UUID, Long> amountOf) {
        List<BudgetProgress.Node> children = childrenOf.getOrDefault(category.id(), List.of()).stream()
                .map(c -> buildNode(c, depth + 1, childrenOf, budgetOf, allocatedOf, amountOf)).filter(Objects::nonNull).toList();
        FinanceBudget own = budgetOf.get(category.id());
        if (own == null && children.isEmpty()) return null;
        long allocated;
        long actual;
        if (own != null) {
            allocated = allocatedOf.get(category.id());
            Set<UUID> subtree = new HashSet<>();
            collectSubtree(category.id(), childrenOf, subtree);
            actual = subtree.stream().mapToLong(id -> amountOf.getOrDefault(id, 0L)).sum();
        } else {
            allocated = children.stream().mapToLong(BudgetProgress.Node::allocated).sum();
            actual = children.stream().mapToLong(BudgetProgress.Node::actual).sum();
        }
        BigDecimal usage = allocated > 0 ? ratio(actual, allocated) : BigDecimal.ZERO.setScale(4);
        return new BudgetProgress.Node(category.id(), category.name(), depth, own == null, own == null ? null : own.id(),
                allocated, actual, allocated - actual, usage, children);
    }

    private void collectUnbudgeted(FinanceCategory category, Map<UUID, List<FinanceCategory>> childrenOf, Set<UUID> covered,
                                   Map<UUID, Long> amountOf, List<BudgetProgress.Unbudgeted> out) {
        if (!covered.contains(category.id()) && amountOf.containsKey(category.id())) {
            out.add(new BudgetProgress.Unbudgeted(category.id(), category.name(), amountOf.get(category.id())));
        }
        childrenOf.getOrDefault(category.id(), List.of()).forEach(c -> collectUnbudgeted(c, childrenOf, covered, amountOf, out));
    }

    private void collectSubtree(UUID id, Map<UUID, List<FinanceCategory>> childrenOf, Set<UUID> out) {
        out.add(id);
        childrenOf.getOrDefault(id, List.of()).forEach(c -> collectSubtree(c.id(), childrenOf, out));
    }

    // [fromMonth, toMonth] 중 예산이 유효한 월 수
    private int validMonths(FinanceBudget budget, YearMonth fromMonth, YearMonth toMonth) {
        int count = 0;
        for (YearMonth m = fromMonth; !m.isAfter(toMonth); m = m.plusMonths(1)) {
            boolean valid = !budget.applyStartDate().isAfter(m.atEndOfMonth())
                    && (budget.applyEndDate() == null || !budget.applyEndDate().isBefore(m.atDay(1)));
            if (valid) count++;
        }
        return count;
    }

    private void requireFlowType(FinanceCategory.Type type) {
        if (type == FinanceCategory.Type.ASSET) {
            throw new IllegalArgumentException("자산 유형은 거래 집계에 사용할 수 없습니다.");
        }
    }

    // 수입·소비·저축 카테고리 인덱스 — 거래의 유형·루트 판정용(삭제된 카테고리는 없다)
    private Map<UUID, CategoryEntry> flowIndex(List<FinanceCategory> categories) {
        Map<UUID, FinanceCategory> byId = categories.stream().filter(c -> c.type() != FinanceCategory.Type.ASSET)
                .collect(Collectors.toMap(FinanceCategory::id, Function.identity()));
        Map<UUID, CategoryEntry> index = new HashMap<>();
        byId.values().forEach(c -> {
            FinanceCategory root = c;
            while (root.parentId() != null && byId.containsKey(root.parentId())) {
                root = byId.get(root.parentId());
            }
            // 조상 사슬이 루트까지 이어지지 않는 고아는 제외(UI는 루트에서 내려가며 인덱싱)
            if (root.parentId() != null) return;
            index.put(c.id(), new CategoryEntry(c.type(), root.id(), root.name(), root.sortOrder(), c.name()));
        });
        return index;
    }

    private Predicate<FinanceTransaction> typeIs(Map<UUID, CategoryEntry> index, FinanceCategory.Type type) {
        return t -> {
            CategoryEntry entry = index.get(t.categoryId());
            return entry != null && entry.type() == type;
        };
    }

    private Stream<FinanceTransaction> inRange(List<FinanceTransaction> all, Map<UUID, CategoryEntry> index,
                                               FinanceCategory.Type type, DateRange range) {
        return all.stream().filter(typeIs(index, type)).filter(t -> range.contains(t.transactionDate()));
    }

    private long sum(List<FinanceTransaction> all, Map<UUID, CategoryEntry> index, FinanceCategory.Type type, DateRange range) {
        return inRange(all, index, type, range).mapToLong(FinanceTransaction::amount).sum();
    }

    private BigDecimal ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    // 개인 소유 거래를 소유자가 자신의 현재 그룹으로 전환한다. 본인 것만 전환 가능(그룹 멤버 전체 아님).
    public FinanceTransaction shareToGroup(UUID transactionId, UUID userId) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 마감월 거래는 그룹 공유 전환도 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.transactionDate());
        return GroupShareSupport.shareToGroup(existing, userId, Optional.ofNullable(currentGroupId),
                        "본인 소유 거래내역만 그룹에 공유할 수 있습니다")
                .map(shared -> {
                    FinanceTransaction saved = transactionPort.save(shared);
                    log.info("거래내역 그룹 공유 전환: transactionId={}, groupId={}", transactionId, saved.groupId());
                    return saved;
                })
                .orElse(existing);
    }

    // 그룹 공유 거래내역을 개인 소유로 되돌린다. 소유자는 그대로 유지, groupId만 null로.
    public FinanceTransaction unshare(UUID transactionId, UUID userId) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 마감월 거래는 귀속 해제도 차단
        monthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, existing.transactionDate());
        return GroupShareSupport.unshare(existing, userId, currentGroupId)
                .map(personal -> {
                    FinanceTransaction saved = transactionPort.save(personal);
                    log.info("[userId={}] 거래내역 그룹 공유 해제: transactionId={}", userId, transactionId);
                    return saved;
                })
                .orElse(existing);
    }
}
