package com.kista.api.finance.application.service;

import com.kista.api.finance.application.port.output.AssetSnapshotPort;
import com.kista.api.finance.application.port.output.FinanceAccountPort;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
import com.kista.api.finance.application.port.output.FinanceGroupPort;
import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.AssetSnapshot;
import com.kista.api.finance.domain.model.AssetSnapshotAggregates.*;
import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.Market;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetSnapshotService 집계 단위 테스트 (UI aggregate.test.ts 짝)")
class AssetSnapshotAggregateTest {

    @Mock AssetSnapshotPort assetSnapshotPort;
    @Mock FinanceGroupPort financeGroupPort;
    @Mock FinanceCategoryPort financeCategoryPort;
    @Mock FinanceAccountPort financeAccountPort;
    @Mock MonthlyClosingGuard monthlyClosingGuard;
    @InjectMocks AssetSnapshotService service;

    private static final UUID INVEST = FinanceCategory.ASSET_L1_DISPLAY_ORDER.get(0);
    private static final UUID SAVINGS = FinanceCategory.ASSET_L1_DISPLAY_ORDER.get(1);
    private static final UUID LOAN = FinanceCategory.SYSTEM_LOAN_ID;
    private static final UUID REAL_ESTATE = FinanceCategory.ASSET_L1_DISPLAY_ORDER.get(3);

    private final UUID userId = UUID.randomUUID();
    private final List<AssetSnapshot> snapshots = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        List<FinanceCategory> roots = FinanceCategory.ASSET_L1_DISPLAY_ORDER.stream().map(this::root).toList();
        lenient().when(financeCategoryPort.findSelectable(userId, null, FinanceCategory.Type.ASSET)).thenReturn(roots);
        lenient().when(assetSnapshotPort.findMyScope(userId, null, null, null, null)).thenReturn(snapshots);
    }

    private FinanceCategory root(UUID id) {
        return new FinanceCategory(id, null, null, null, FinanceCategory.Type.ASSET, "투자", 0, null);
    }

    private void add(String date, UUID categoryId, AssetClass assetClass, long amount) {
        add(date, categoryId, assetClass, amount, null);
    }

    private void add(String date, UUID categoryId, AssetClass assetClass, long amount, UUID accountId) {
        snapshots.add(new AssetSnapshot(UUID.randomUUID(), null, categoryId, accountId, userId,
                LocalDate.parse(date), assetClass, Market.GLOBAL, null, null, amount, null));
    }

    @Test
    @DisplayName("[aggregate.test] 순자산에서 대출을 실제로 차감한다")
    void summary_netWorthSubtractsLoan() {
        add("2026-08-01", INVEST, AssetClass.EQUITY, 10_000_000);
        add("2026-08-01", LOAN, AssetClass.CASH, 3_000_000);

        MonthlyAssetSummary current = service.summary(userId, YearMonth.of(2026, 8)).current();

        assertThat(current.totalAssets()).isEqualTo(10_000_000);
        assertThat(current.totalLiabilities()).isEqualTo(3_000_000);
        assertThat(current.netWorth()).isEqualTo(7_000_000);
        assertThat(current.recordCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[aggregate.test] 가장 큰 자산군을 대출 제외 기준으로 계산한다")
    void summary_largestAssetClassExcludesLoan() {
        add("2026-08-01", INVEST, AssetClass.EQUITY, 5_000_000);
        add("2026-08-01", INVEST, AssetClass.CRYPTO, 2_000_000);
        add("2026-08-01", LOAN, AssetClass.CASH, 100_000_000);

        AssetClassAmount largest = service.summary(userId, YearMonth.of(2026, 8)).current().largestAssetClass();

        assertThat(largest).isEqualTo(new AssetClassAmount(AssetClass.EQUITY, 5_000_000));
    }

    @Test
    @DisplayName("[aggregate.test] 가장 큰 자산군 동액이면 먼저 나온 자산군")
    void summary_largestAssetClassTieFirstWins() {
        add("2026-08-01", INVEST, AssetClass.CRYPTO, 1_000);
        add("2026-08-01", INVEST, AssetClass.EQUITY, 1_000);

        assertThat(service.summary(userId, YearMonth.of(2026, 8)).current().largestAssetClass().assetClass())
                .isEqualTo(AssetClass.CRYPTO);
    }

    @Test
    @DisplayName("[aggregate.test] 기록이 없으면 month·current·previous가 null이고 availableMonths는 빈 목록")
    void summary_noRecords() {
        AssetSummaryResult result = service.summary(userId, null);

        assertThat(result.month()).isNull();
        assertThat(result.current()).isNull();
        assertThat(result.previous()).isNull();
        assertThat(result.availableMonths()).isEmpty();
    }

    @Test
    @DisplayName("[aggregate.test] 기록 있는 월은 중복 없이 최신순, month 생략 시 최신 월, previous는 직전 기록 월")
    void summary_availableMonthsDescAndPrevious() {
        add("2026-05-01", INVEST, AssetClass.EQUITY, 1);
        add("2026-08-01", INVEST, AssetClass.EQUITY, 3);
        add("2026-08-15", INVEST, AssetClass.EQUITY, 3);
        add("2026-07-01", INVEST, AssetClass.EQUITY, 2);

        AssetSummaryResult result = service.summary(userId, null);

        assertThat(result.availableMonths()).containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 7), YearMonth.of(2026, 5));
        assertThat(result.month()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(result.previous().month()).isEqualTo(YearMonth.of(2026, 7));
        // previousMonthOf: 가장 오래된 월은 직전 월이 없다
        assertThat(service.summary(userId, YearMonth.of(2026, 5)).previous()).isNull();
    }

    @Test
    @DisplayName("[aggregate.test] L1 카테고리 4개를 고정 순서로 반환한다(0 포함)")
    void summary_categoryBreakdownFixedOrder() {
        add("2026-08-01", INVEST, AssetClass.EQUITY, 1_000_000);
        add("2026-08-01", LOAN, AssetClass.CASH, 2_000_000);

        List<CategoryAmount> breakdown = service.summary(userId, YearMonth.of(2026, 8)).current().categoryBreakdown();

        assertThat(breakdown).extracting(CategoryAmount::categoryId).containsExactly(INVEST, SAVINGS, LOAN, REAL_ESTATE);
        assertThat(breakdown).extracting(CategoryAmount::amount).containsExactly(1_000_000L, 0L, 2_000_000L, 0L);
    }

    @Test
    @DisplayName("[aggregate.test] 자산군은 enum 순서, 대출·0원 제외")
    void summary_assetClassBreakdown() {
        add("2026-08-01", INVEST, AssetClass.CRYPTO, 500_000);
        add("2026-08-01", INVEST, AssetClass.EQUITY, 1_000_000);
        add("2026-08-01", LOAN, AssetClass.CASH, 9_000_000);

        assertThat(service.summary(userId, YearMonth.of(2026, 8)).current().assetClassBreakdown())
                .extracting(AssetClassAmount::assetClass).containsExactly(AssetClass.EQUITY, AssetClass.CRYPTO);
    }

    @Test
    @DisplayName("[서버 고유] L2 카테고리는 부모를 거슬러 루트로 판정하고, 삭제된 카테고리는 findByIdOrThrow로 보충한다")
    void summary_resolvesRootThroughParentsIncludingDeleted() {
        UUID l2 = UUID.randomUUID();
        UUID deletedL3 = UUID.randomUUID();
        // L2는 선택 목록에 없어(삭제됨) 포트에서 직접 조회한다
        when(financeCategoryPort.findByIdOrThrow(deletedL3)).thenReturn(
                new FinanceCategory(deletedL3, null, l2, null, FinanceCategory.Type.ASSET, "삭제됨", 0, null));
        when(financeCategoryPort.findByIdOrThrow(l2)).thenReturn(
                new FinanceCategory(l2, null, LOAN, null, FinanceCategory.Type.ASSET, "전세대출", 0, null));
        add("2026-08-01", deletedL3, AssetClass.CASH, 700);

        MonthlyAssetSummary current = service.summary(userId, YearMonth.of(2026, 8)).current();

        assertThat(current.totalLiabilities()).isEqualTo(700);
        assertThat(current.totalAssets()).isZero();
    }

    @Test
    @DisplayName("[aggregate.test] 추이: 월별 순자산·총자산을 오름차순, 자산군은 6개 전부 대출 제외")
    void trend_ascendingWithAllItems() {
        add("2026-07-01", INVEST, AssetClass.EQUITY, 1_000_000);
        add("2026-08-01", INVEST, AssetClass.EQUITY, 3_000_000);
        add("2026-08-01", LOAN, AssetClass.CASH, 1_000_000);

        List<TrendPoint> trend = service.trend(userId, null, null);

        assertThat(trend).extracting(TrendPoint::month).containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8));
        assertThat(trend.get(0).netWorth()).isEqualTo(1_000_000);
        assertThat(trend.get(1).netWorth()).isEqualTo(2_000_000);
        assertThat(trend.get(1).totalAssets()).isEqualTo(3_000_000);
        assertThat(trend.get(1).byCategory()).hasSize(4);
        assertThat(trend.get(1).byAssetClass()).hasSize(6);
        assertThat(trend.get(1).byAssetClass()).filteredOn(a -> a.assetClass() == AssetClass.CASH)
                .extracting(AssetClassAmount::amount).containsExactly(0L);
    }

    @Test
    @DisplayName("[aggregate.test] 추이: endMonth 이하 최근 months개, months<1이면 IAE")
    void trend_limitAndEndMonth() {
        add("2026-06-01", INVEST, AssetClass.EQUITY, 1);
        add("2026-07-01", INVEST, AssetClass.EQUITY, 1);
        add("2026-08-01", INVEST, AssetClass.EQUITY, 1);

        assertThat(service.trend(userId, 1, YearMonth.of(2026, 7))).extracting(TrendPoint::month)
                .containsExactly(YearMonth.of(2026, 7));
        assertThat(service.trend(userId, 6, null)).hasSize(3);
        assertThatThrownBy(() -> service.trend(userId, 0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[aggregate.test] 구성비: 카테고리는 대출 포함 4개, 합 100%")
    void composition_categoryIncludesLoan() {
        add("2026-08-01", INVEST, AssetClass.EQUITY, 3_000_000);
        add("2026-08-01", SAVINGS, AssetClass.CASH, 1_000_000);

        CompositionColumn column = service.composition(userId, Basis.CATEGORY, null, null).get(0);

        assertThat(column.month()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(column.total()).isEqualTo(4_000_000);
        assertThat(column.entries()).hasSize(4);
        assertThat(column.entries().get(0).item()).isEqualTo(INVEST.toString());
        assertThat(column.entries().get(0).percent()).isEqualByComparingTo("75.00");
        assertThat(column.entries().get(1).percent()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("[aggregate.test] 구성비: 자산군은 대출 제외 6개, 분모에 대출이 섞이지 않는다")
    void composition_assetClassExcludesLoan() {
        add("2026-08-01", INVEST, AssetClass.FIXED_INCOME, 7_000_000);
        add("2026-08-01", INVEST, AssetClass.EQUITY, 3_000_000);
        add("2026-08-01", LOAN, AssetClass.CASH, 5_000_000);

        CompositionColumn column = service.composition(userId, Basis.ASSET_CLASS, null, null).get(0);

        assertThat(column.total()).isEqualTo(10_000_000);
        assertThat(column.entries()).hasSize(6);
        assertThat(column.entries()).filteredOn(e -> e.item().equals("FIXED_INCOME"))
                .extracting(CompositionEntry::percent).containsExactly(new BigDecimal("70.00"));
        assertThat(column.entries()).filteredOn(e -> e.item().equals("EQUITY"))
                .extracting(CompositionEntry::percent).containsExactly(new BigDecimal("30.00"));
    }

    @Test
    @DisplayName("[aggregate.test] 구성비: 기록이 없으면 빈 columns")
    void composition_noRecords() {
        assertThat(service.composition(userId, Basis.CATEGORY, null, null)).isEmpty();
    }

    @Test
    @DisplayName("[aggregate.test] calcMissingCategories: 이번 달 기록이 없는 L1을 고정 순서로")
    void check_missingCategories() {
        add("2026-08-01", INVEST, AssetClass.EQUITY, 1);

        assertThat(service.check(userId, YearMonth.of(2026, 8)).missingCategoryIds())
                .containsExactly(SAVINGS, LOAN, REAL_ESTATE);
    }

    @Test
    @DisplayName("[aggregate.test] calcMissingAccounts: 전월에는 있었지만 이번 달에 없는 계좌")
    void check_missingAccounts() {
        UUID bank = UUID.randomUUID();
        UUID broker = UUID.randomUUID();
        when(financeAccountPort.findByIdOrThrow(bank)).thenReturn(account("국민은행"));
        when(financeAccountPort.findByIdOrThrow(broker)).thenReturn(account("미래에셋증권"));
        UUID loanL2 = UUID.randomUUID();
        when(financeCategoryPort.findByIdOrThrow(loanL2)).thenReturn(
                new FinanceCategory(loanL2, null, LOAN, null, FinanceCategory.Type.ASSET, "전세자금대출", 0, null));
        add("2026-07-01", loanL2, AssetClass.CASH, 1, bank);
        add("2026-08-01", INVEST, AssetClass.EQUITY, 1, broker);

        AssetCheckResult result = service.check(userId, YearMonth.of(2026, 8));

        assertThat(result.previousMonth()).isEqualTo(YearMonth.of(2026, 7));
        assertThat(result.missingAccounts()).containsExactly(new MissingAccount("국민은행", "전세자금대출", AssetClass.CASH));
    }

    @Test
    @DisplayName("[aggregate.test] calcMissingAccounts: 자산군 코드가 다르면 다른 계좌, 계좌 없으면 accountName null")
    void check_missingAccounts_differentAssetClassAndNullAccount() {
        add("2026-07-01", INVEST, AssetClass.EQUITY, 1);
        add("2026-07-01", INVEST, AssetClass.CRYPTO, 1);
        add("2026-08-01", INVEST, AssetClass.EQUITY, 1);

        assertThat(service.check(userId, YearMonth.of(2026, 8)).missingAccounts())
                .containsExactly(new MissingAccount(null, "투자", AssetClass.CRYPTO));
    }

    @Test
    @DisplayName("[aggregate.test] calcMissingAccounts: 이전 달이 없으면 빈 목록")
    void check_noPreviousMonth() {
        add("2026-08-01", INVEST, AssetClass.EQUITY, 1);

        AssetCheckResult result = service.check(userId, YearMonth.of(2026, 8));

        assertThat(result.previousMonth()).isNull();
        assertThat(result.missingAccounts()).isEmpty();
    }

    @Test
    @DisplayName("[aggregate.test] calcDateGroups: 기준일별 건수를 날짜 오름차순으로")
    void check_dateGroups() {
        add("2026-08-15", INVEST, AssetClass.EQUITY, 1);
        add("2026-08-01", INVEST, AssetClass.EQUITY, 1);
        add("2026-08-01", INVEST, AssetClass.CRYPTO, 1);

        assertThat(service.check(userId, YearMonth.of(2026, 8)).dateGroups()).containsExactly(
                new DateGroup(LocalDate.of(2026, 8, 1), 2), new DateGroup(LocalDate.of(2026, 8, 15), 1));
    }

    private FinanceAccount account(String name) {
        return new FinanceAccount(UUID.randomUUID(), null, userId, FinanceAccount.Type.BANK, name, null, null, null, null, null);
    }
}
