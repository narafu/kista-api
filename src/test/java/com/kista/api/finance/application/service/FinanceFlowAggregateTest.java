package com.kista.api.finance.application.service;

import com.kista.api.finance.application.port.output.FinanceBudgetPort;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
import com.kista.api.finance.application.port.output.FinanceGroupPort;
import com.kista.api.finance.application.port.output.FinanceTransactionPort;
import com.kista.api.finance.domain.model.BudgetProgress;
import com.kista.api.finance.domain.model.FinanceBudget;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FinanceCategory.Type;
import com.kista.api.finance.domain.model.FinanceTransaction;
import com.kista.api.finance.domain.model.FlowPeriod;
import com.kista.api.finance.domain.model.FlowPeriod.Mode;
import com.kista.api.finance.domain.model.FlowSummary;
import com.kista.api.finance.domain.model.FlowTrendPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinanceTransactionService 거래 집계 단위 테스트")
class FinanceFlowAggregateTest {

    @Mock FinanceTransactionPort transactionPort;
    @Mock FinanceGroupPort financeGroupPort;
    @Mock FinanceCategoryPort financeCategoryPort;
    @Mock FinanceBudgetPort budgetPort;
    @Mock MonthlyClosingGuard monthlyClosingGuard;

    private FinanceTransactionService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 23);
    private final UUID userId = UUID.randomUUID();

    private final FinanceCategory food = cat("식비", null, Type.EXPENSE, 1);
    private final FinanceCategory dining = cat("외식", food.id(), Type.EXPENSE, 1);
    private final FinanceCategory lunch = cat("점심", dining.id(), Type.EXPENSE, 1);
    private final FinanceCategory dinner = cat("저녁", dining.id(), Type.EXPENSE, 2);
    private final FinanceCategory grocery = cat("장보기", food.id(), Type.EXPENSE, 2);
    private final FinanceCategory transport = cat("교통", null, Type.EXPENSE, 2);
    private final FinanceCategory salary = cat("급여", null, Type.INCOME, 1);
    private final FinanceCategory saving = cat("적금", null, Type.SAVING, 1);

    private static FinanceCategory cat(String name, UUID parentId, Type type, int sortOrder) {
        return new FinanceCategory(UUID.randomUUID(), null, parentId, null, type, name, sortOrder, null);
    }

    private FinanceTransaction tx(FinanceCategory c, String date, long amount) {
        return new FinanceTransaction(UUID.randomUUID(), null, c.id(), userId, LocalDate.parse(date), amount, null, null);
    }

    private FinanceBudget budget(FinanceCategory c, String start, long amount) {
        return new FinanceBudget(UUID.randomUUID(), null, c.id(), userId, LocalDate.parse(start), null, amount, null);
    }

    private FlowPeriod monthly(String month) {
        return new FlowPeriod(YearMonth.parse(month), Mode.MONTHLY);
    }

    private FlowPeriod yearly(String month) {
        return new FlowPeriod(YearMonth.parse(month), Mode.YEARLY);
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(12, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
        service = new FinanceTransactionService(transactionPort, financeGroupPort, financeCategoryPort,
                monthlyClosingGuard, budgetPort, clock);
        lenient().when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        lenient().when(financeCategoryPort.findSelectable(userId, null, null))
                .thenReturn(List.of(food, dining, lunch, dinner, grocery, transport, salary, saving));
    }

    private void transactions(FinanceTransaction... txs) {
        lenient().when(transactionPort.findMyScope(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(2);
            LocalDate to = inv.getArgument(3);
            return List.of(txs).stream()
                    .filter(t -> (from == null || !t.transactionDate().isBefore(from)) && !t.transactionDate().isAfter(to))
                    .toList();
        });
    }

    // ---- summary ----

    @Test
    @DisplayName("[flowAggregate.test] 연간 모드: 올해를 선택하면 1월~오늘까지(YTD)만 합산, previousTotal은 null")
    void summary_yearly_currentYear() {
        transactions(tx(lunch, "2026-02-01", 1000), tx(lunch, "2026-08-23", 2000), tx(lunch, "2026-09-01", 9999));

        FlowSummary result = service.summary(userId, Type.EXPENSE, yearly("2026-03"));

        assertThat(result.total()).isEqualTo(3000);
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.previousTotal()).isNull();
    }

    @Test
    @DisplayName("[flowAggregate.test] 연간 모드: 과거 연도는 mm과 무관하게 1~12월 전체, 전년 전체와 비교")
    void summary_yearly_pastYear() {
        transactions(tx(lunch, "2025-01-01", 1000), tx(lunch, "2025-12-31", 2000), tx(lunch, "2024-12-31", 700));

        FlowSummary result = service.summary(userId, Type.EXPENSE, yearly("2025-03"));

        assertThat(result.total()).isEqualTo(3000);
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.previousYearTotal()).isEqualTo(700);
        // [financeSummaryCalc] 과거 연도 월평균 = 연 합계 / 12
        assertThat(result.yearlyAverage()).isEqualTo(250);
    }

    @Test
    @DisplayName("[flowAggregate.test] 월간 모드: 전월 전체 합을 previousTotal로, previousYearTotal은 null")
    void summary_monthly_previousTotal() {
        transactions(tx(lunch, "2026-08-05", 2000), tx(lunch, "2026-07-31", 500), tx(lunch, "2026-06-30", 9999));

        FlowSummary result = service.summary(userId, Type.EXPENSE, monthly("2026-08"));

        assertThat(result.total()).isEqualTo(2000);
        assertThat(result.previousTotal()).isEqualTo(500);
        assertThat(result.previousYearTotal()).isNull();
    }

    @Test
    @DisplayName("[financeSummaryCalc] calcYearlyAverage: 월간 모드는 선택월 말일 기준(선택월 이후는 제외), 분모는 선택월 번호")
    void summary_monthly_yearlyAverageUsesSelectedMonth() {
        transactions(tx(lunch, "2026-01-10", 1000), tx(lunch, "2026-03-10", 2000), tx(lunch, "2026-05-01", 9999));

        FlowSummary result = service.summary(userId, Type.EXPENSE, monthly("2026-03"));

        // 1~3월 합 3000 / 3개월
        assertThat(result.yearlyAverage()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[financeSummaryCalc] calcYearlyAverage: 반올림은 HALF_UP")
    void summary_yearlyAverage_roundsHalfUp() {
        transactions(tx(lunch, "2026-01-10", 5));

        // 5 / 2개월 = 2.5 → 3
        assertThat(service.summary(userId, Type.EXPENSE, monthly("2026-02")).yearlyAverage()).isEqualTo(3);
    }

    @Test
    @DisplayName("[financeSummaryCalc] calcRemainingAmount: 수입 유형만 수입-소비-저축, incomeRatio는 null")
    void summary_income_remaining() {
        transactions(tx(salary, "2026-08-01", 1000), tx(lunch, "2026-08-02", 300), tx(saving, "2026-08-03", 200));

        FlowSummary result = service.summary(userId, Type.INCOME, monthly("2026-08"));

        assertThat(result.remaining()).isEqualTo(500);
        assertThat(result.incomeRatio()).isNull();
    }

    @Test
    @DisplayName("[FinanceSummary] incomeRatio: 수입 외 유형은 total/수입 합계(scale 4), remaining은 null")
    void summary_expense_incomeRatio() {
        transactions(tx(salary, "2026-08-01", 3000), tx(lunch, "2026-08-02", 1000));

        FlowSummary result = service.summary(userId, Type.EXPENSE, monthly("2026-08"));

        assertThat(result.incomeRatio()).isEqualByComparingTo(new BigDecimal("0.3333"));
        assertThat(result.incomeRatio().scale()).isEqualTo(4);
        assertThat(result.remaining()).isNull();
    }

    @Test
    @DisplayName("[FinanceSummary] incomeRatio: 같은 기간 수입이 0이면 null")
    void summary_noIncome_ratioNull() {
        transactions(tx(lunch, "2026-08-02", 1000));

        assertThat(service.summary(userId, Type.EXPENSE, monthly("2026-08")).incomeRatio()).isNull();
    }

    @Test
    @DisplayName("[flowAggregate.test] filterByType: 인덱스에 없는(삭제된) 카테고리 거래는 어느 유형에도 넣지 않는다")
    void summary_ignoresDeletedCategoryTransactions() {
        FinanceCategory deleted = cat("삭제", null, Type.EXPENSE, 9);
        transactions(tx(deleted, "2026-08-05", 500), tx(lunch, "2026-08-05", 100));

        FlowSummary result = service.summary(userId, Type.EXPENSE, monthly("2026-08"));

        assertThat(result.total()).isEqualTo(100);
        assertThat(result.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("type=ASSET은 IllegalArgumentException")
    void summary_assetType_rejected() {
        assertThatThrownBy(() -> service.summary(userId, Type.ASSET, monthly("2026-08")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- trend ----

    @Test
    @DisplayName("[flowAggregate.test] calcFlowTrend 월간: 선택월 기준 최근 limit개월 버킷별 합계·루트별 합계")
    void trend_monthly() {
        transactions(tx(lunch, "2026-07-10", 1000), tx(transport, "2026-07-15", 500), tx(lunch, "2026-08-01", 2000));

        List<FlowTrendPoint> result = service.trend(userId, Type.EXPENSE, monthly("2026-08"), 2);

        assertThat(result).extracting(FlowTrendPoint::period).containsExactly("2026-07", "2026-08");
        assertThat(result.get(0).amount()).isEqualTo(1500);
        // 루트(식비 sortOrder 1, 교통 2) 순서
        assertThat(result.get(0).byCategory()).extracting(FlowTrendPoint.RootAmount::categoryId, FlowTrendPoint.RootAmount::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(food.id(), 1000L), org.assertj.core.groups.Tuple.tuple(transport.id(), 500L));
        assertThat(result.get(1).byCategory()).hasSize(1);
    }

    @Test
    @DisplayName("[flowAggregate.test] calcFlowTrend 연간: 과거 연도 버킷은 12월까지, 올해 버킷은 오늘까지(YTD)")
    void trend_yearly() {
        transactions(tx(lunch, "2025-03-01", 1000), tx(lunch, "2025-12-31", 500),
                tx(lunch, "2026-05-01", 3000), tx(lunch, "2026-08-24", 9999));

        List<FlowTrendPoint> result = service.trend(userId, Type.EXPENSE, yearly("2026-08"), 2);

        assertThat(result).extracting(FlowTrendPoint::period).containsExactly("2025", "2026");
        assertThat(result).extracting(FlowTrendPoint::amount).containsExactly(1500L, 3000L);
    }

    @Test
    @DisplayName("[flowAggregate.test] calcFlowTrend 'all': range 생략 시 가장 오래된 거래의 월/연도부터")
    void trend_all() {
        transactions(tx(lunch, "2026-05-10", 1000), tx(lunch, "2024-03-01", 700));

        List<FlowTrendPoint> monthly = service.trend(userId, Type.EXPENSE, monthly("2026-08"), null);
        assertThat(monthly).hasSize(30);
        assertThat(monthly.getFirst().period()).isEqualTo("2024-03");
        assertThat(monthly.getFirst().amount()).isEqualTo(700);

        List<FlowTrendPoint> yearly = service.trend(userId, Type.EXPENSE, yearly("2026-08"), null);
        assertThat(yearly).extracting(FlowTrendPoint::period).containsExactly("2024", "2025", "2026");
    }

    @Test
    @DisplayName("거래가 없으면 'all'은 버킷 1개, range<1은 IllegalArgumentException")
    void trend_emptyAndInvalidRange() {
        transactions();

        assertThat(service.trend(userId, Type.EXPENSE, monthly("2026-08"), null)).hasSize(1);
        assertThatThrownBy(() -> service.trend(userId, Type.EXPENSE, monthly("2026-08"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.trend(userId, Type.EXPENSE, monthly("2026-08"), 121))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[flowAggregate.test] 삭제된 카테고리 거래는 추이에서 제외(유형 필터 단계)")
    void trend_deletedCategoryExcluded() {
        FinanceCategory deleted = cat("삭제", null, Type.EXPENSE, 9);
        transactions(tx(deleted, "2026-08-05", 500));

        List<FlowTrendPoint> result = service.trend(userId, Type.EXPENSE, monthly("2026-08"), 1);

        assertThat(result.getFirst().amount()).isZero();
        assertThat(result.getFirst().byCategory()).isEmpty();
    }

    // ---- budget-progress ----

    @Test
    @DisplayName("[flowAggregate.test] calcBudgetProgress: 카테고리 순번(sortOrder)으로 정렬한다")
    void budgetProgress_sortedBySortOrder() {
        lenient().when(budgetPort.findMyScope(userId, null, null, null))
                .thenReturn(List.of(budget(transport, "2026-01-01", 100), budget(food, "2026-01-01", 100)));
        transactions(tx(food, "2026-08-05", 10), tx(transport, "2026-08-05", 90));

        BudgetProgress result = service.budgetProgress(userId, Type.EXPENSE, monthly("2026-08"));

        assertThat(result.nodes()).extracting(BudgetProgress.Node::categoryId).containsExactly(food.id(), transport.id());
    }

    @Test
    @DisplayName("[flowAggregate.test] calcBudgetProgress 연간: 과거 연도는 mm과 무관하게 12개월을 할당")
    void budgetProgress_yearly_pastYear() {
        FinanceBudget b = budget(food, "2025-01-01", 100);
        lenient().when(budgetPort.findMyScope(userId, null, null, null)).thenReturn(List.of(b));
        transactions(tx(food, "2025-03-01", 50), tx(food, "2025-11-01", 50));

        BudgetProgress.Node node = service.budgetProgress(userId, Type.EXPENSE, yearly("2025-03")).nodes().getFirst();

        assertThat(node.allocated()).isEqualTo(1200);
        assertThat(node.actual()).isEqualTo(100);
        assertThat(node.remaining()).isEqualTo(1100);
        assertThat(node.usageRatio()).isEqualByComparingTo(new BigDecimal("0.0833"));
        assertThat(node.budgetId()).isEqualTo(b.id());
    }

    @Test
    @DisplayName("[flowAggregate.test] 연간 YTD: 예산 시작이 6월이면 올해 8월 기준 3개월(6~8월)만 할당")
    void budgetProgress_yearly_validMonthsFromStart() {
        lenient().when(budgetPort.findMyScope(userId, null, null, null)).thenReturn(List.of(budget(food, "2026-06-01", 100)));
        transactions();

        assertThat(service.budgetProgress(userId, Type.EXPENSE, yearly("2026-03")).nodes().getFirst().allocated()).isEqualTo(300);
    }

    @Test
    @DisplayName("[flowAggregate.test] 자체 예산 없는 부모는 하위 예산 합을 소계로 갖는 그룹 노드가 된다")
    void budgetProgress_groupNode() {
        lenient().when(budgetPort.findMyScope(userId, null, null, null)).thenReturn(List.of(
                budget(lunch, "2026-01-01", 200), budget(dinner, "2026-01-01", 150), budget(grocery, "2026-01-01", 200)));
        transactions(tx(lunch, "2026-08-02", 180), tx(dinner, "2026-08-02", 90), tx(grocery, "2026-08-03", 200));

        BudgetProgress.Node foodNode = service.budgetProgress(userId, Type.EXPENSE, monthly("2026-08")).nodes().getFirst();

        assertThat(foodNode.categoryId()).isEqualTo(food.id());
        assertThat(foodNode.isGroup()).isTrue();
        assertThat(foodNode.budgetId()).isNull();
        assertThat(foodNode.depth()).isZero();
        assertThat(foodNode.allocated()).isEqualTo(550);
        assertThat(foodNode.actual()).isEqualTo(470);
        assertThat(foodNode.remaining()).isEqualTo(80);
        BudgetProgress.Node diningNode = foodNode.children().get(0);
        assertThat(diningNode.isGroup()).isTrue();
        assertThat(diningNode.depth()).isEqualTo(1);
        assertThat(diningNode.allocated()).isEqualTo(350);
        assertThat(diningNode.actual()).isEqualTo(270);
        assertThat(diningNode.children()).extracting(BudgetProgress.Node::categoryId).containsExactly(lunch.id(), dinner.id());
        assertThat(foodNode.children().get(1).isGroup()).isFalse();
        assertThat(foodNode.children().get(1).budgetId()).isNotNull();
    }

    @Test
    @DisplayName("[flowAggregate.test] 부모에 자체 예산이 있으면 그 예산을 노드로 쓰고 하위를 중첩한다(실적은 서브트리 전체)")
    void budgetProgress_ownBudgetWithNestedChildren() {
        lenient().when(budgetPort.findMyScope(userId, null, null, null))
                .thenReturn(List.of(budget(food, "2026-01-01", 600), budget(dining, "2026-01-01", 400), budget(lunch, "2026-01-01", 200)));
        transactions(tx(lunch, "2026-08-02", 180), tx(dinner, "2026-08-02", 140), tx(grocery, "2026-08-03", 210));

        BudgetProgress.Node foodNode = service.budgetProgress(userId, Type.EXPENSE, monthly("2026-08")).nodes().getFirst();

        assertThat(foodNode.isGroup()).isFalse();
        assertThat(foodNode.allocated()).isEqualTo(600);
        assertThat(foodNode.actual()).isEqualTo(530);
        BudgetProgress.Node diningNode = foodNode.children().getFirst();
        assertThat(diningNode.isGroup()).isFalse();
        assertThat(diningNode.actual()).isEqualTo(320);
        assertThat(diningNode.children().getFirst().categoryId()).isEqualTo(lunch.id());
        assertThat(diningNode.children().getFirst().isGroup()).isFalse();
    }

    @Test
    @DisplayName("[flowAggregate.test] 예산이 하나도 없는 가지는 제외한다")
    void budgetProgress_prunesBranchesWithoutBudget() {
        lenient().when(budgetPort.findMyScope(userId, null, null, null)).thenReturn(List.of(budget(grocery, "2026-01-01", 200)));
        transactions();

        BudgetProgress result = service.budgetProgress(userId, Type.EXPENSE, monthly("2026-08"));

        assertThat(result.nodes()).extracting(BudgetProgress.Node::categoryId).containsExactly(food.id());
        assertThat(result.nodes().getFirst().children()).extracting(BudgetProgress.Node::categoryId).containsExactly(grocery.id());
    }

    @Test
    @DisplayName("[flowAggregate.test] 자체 예산 없고 하위가 1개뿐인 그룹 노드도 생략하지 않는다")
    void budgetProgress_singleChildGroupKept() {
        lenient().when(budgetPort.findMyScope(userId, null, null, null)).thenReturn(List.of(budget(lunch, "2026-01-01", 100)));
        transactions(tx(lunch, "2026-08-02", 50));

        BudgetProgress.Node foodNode = service.budgetProgress(userId, Type.EXPENSE, monthly("2026-08")).nodes().getFirst();

        assertThat(foodNode.isGroup()).isTrue();
        assertThat(foodNode.allocated()).isEqualTo(100);
        assertThat(foodNode.children().getFirst().isGroup()).isTrue();
        assertThat(foodNode.children().getFirst().children().getFirst().isGroup()).isFalse();
    }

    @Test
    @DisplayName("[flowAggregate.test] 유효하지 않은 기간의 예산·다른 유형 예산은 제외, 중복 예산은 적용 시작일이 늦은 것")
    void budgetProgress_filtersAndDedupes() {
        FinanceBudget expired = new FinanceBudget(UUID.randomUUID(), null, transport.id(), userId,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 100, null);
        FinanceBudget oldBudget = budget(food, "2026-01-01", 100);
        FinanceBudget newBudget = budget(food, "2026-06-01", 300);
        lenient().when(budgetPort.findMyScope(userId, null, null, null))
                .thenReturn(List.of(expired, oldBudget, newBudget, budget(salary, "2026-01-01", 999)));
        transactions();

        BudgetProgress result = service.budgetProgress(userId, Type.EXPENSE, monthly("2026-08"));

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().getFirst().budgetId()).isEqualTo(newBudget.id());
        // 8월에는 두 예산이 모두 유효 — 월 금액 합산
        assertThat(result.nodes().getFirst().allocated()).isEqualTo(400);
    }

    @Test
    @DisplayName("연간 YTD: 같은 카테고리 예산이 여럿이면 예산별 금액×유효 개월 합, 대표 id는 시작일이 늦은 것(포트 순서 무관)")
    void budgetProgress_yearly_sumsMultipleBudgets() {
        FinanceBudget a = new FinanceBudget(UUID.randomUUID(), null, food.id(), userId,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), 100_000, null);
        FinanceBudget b = budget(food, "2026-07-01", 200_000);
        for (List<FinanceBudget> order : List.of(List.of(a, b), List.of(b, a))) {
            lenient().when(budgetPort.findMyScope(userId, null, null, null)).thenReturn(order);
            transactions();

            BudgetProgress.Node node = service.budgetProgress(userId, Type.EXPENSE, yearly("2026-08")).nodes().getFirst();

            // 오늘 8월: A 6개월, B 7~8월 2개월
            assertThat(node.allocated()).isEqualTo(100_000L * 6 + 200_000L * 2);
            assertThat(node.budgetId()).isEqualTo(b.id());
        }
    }

    @Test
    @DisplayName("부모가 selectable에 없는 고아 카테고리 거래는 summary·trend에서 제외")
    void orphanCategoryExcluded() {
        FinanceCategory orphan = cat("고아", UUID.randomUUID(), Type.EXPENSE, 9);
        lenient().when(financeCategoryPort.findSelectable(userId, null, null))
                .thenReturn(List.of(food, dining, lunch, orphan));
        transactions(tx(orphan, "2026-08-05", 500), tx(lunch, "2026-08-05", 100));

        assertThat(service.summary(userId, Type.EXPENSE, monthly("2026-08")).total()).isEqualTo(100);
        assertThat(service.trend(userId, Type.EXPENSE, monthly("2026-08"), 1).getFirst().amount()).isEqualTo(100);
    }

    @Test
    @DisplayName("[flowAggregate.test] calcUnbudgetedCategories: 예산 서브트리에 덮이지 않은 실적만, 카테고리 표시 순서")
    void budgetProgress_unbudgeted() {
        lenient().when(budgetPort.findMyScope(userId, null, null, null)).thenReturn(List.of(budget(dining, "2026-01-01", 100)));
        transactions(tx(lunch, "2026-08-02", 10), tx(grocery, "2026-08-03", 20), tx(transport, "2026-08-04", 30),
                tx(transport, "2026-08-05", 5), tx(grocery, "2026-07-31", 999));

        BudgetProgress result = service.budgetProgress(userId, Type.EXPENSE, monthly("2026-08"));

        assertThat(result.unbudgeted()).extracting(BudgetProgress.Unbudgeted::categoryId, BudgetProgress.Unbudgeted::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(grocery.id(), 20L), org.assertj.core.groups.Tuple.tuple(transport.id(), 35L));
        assertThat(result.unbudgeted().getFirst().categoryName()).isEqualTo("장보기");
    }
}
