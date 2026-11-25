package com.kista.api.finance.application.service;

import com.kista.api.finance.application.port.output.AssetSnapshotPort;
import com.kista.api.finance.application.port.output.FinanceAccountPort;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
import com.kista.api.finance.application.port.output.FinanceGroupPort;
import com.kista.api.finance.application.port.output.FinanceTransactionPort;
import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.AssetSnapshot;
import com.kista.api.finance.domain.model.BulkRegisterPreview;
import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FinanceCategory.Type;
import com.kista.api.finance.domain.model.FinanceTransaction;
import com.kista.api.finance.domain.model.Market;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// kista-ui bulkRegisterPreview.test.ts / assetSort.test.ts(compareAssetDetail) 이식
class BulkRegisterPreviewTest {

    private static final UUID USER = UUID.randomUUID();
    private static final YearMonth MONTH = YearMonth.of(2026, 7);

    private final FinanceGroupPort groupPort = mock(FinanceGroupPort.class);
    private final AssetSnapshotPort snapshotPort = mock(AssetSnapshotPort.class);
    private final FinanceTransactionPort txPort = mock(FinanceTransactionPort.class);
    private final FinanceCategoryPort categoryPort = mock(FinanceCategoryPort.class);
    private final FinanceAccountPort accountPort = mock(FinanceAccountPort.class);
    private final List<FinanceCategory> categories = new ArrayList<>();
    private BulkFinanceRegisterService service;

    @BeforeEach
    void setUp() {
        service = new BulkFinanceRegisterService(mock(AssetSnapshotService.class), mock(FinanceTransactionService.class),
                groupPort, snapshotPort, txPort, categoryPort, accountPort);
        when(groupPort.findCurrentGroupId(USER)).thenReturn(Optional.empty());
        when(categoryPort.findSelectable(any(), any(), any())).thenAnswer(inv -> {
            Type type = inv.getArgument(2);
            return categories.stream().filter(c -> c.type() == type).toList();
        });
        when(snapshotPort.findMyScope(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(txPort.findMyScope(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    }

    private FinanceCategory cat(Type type, String name, int sortOrder, UUID parent) {
        FinanceCategory c = new FinanceCategory(UUID.randomUUID(), null, parent, null, type, name, sortOrder, null);
        categories.add(c);
        when(categoryPort.findByIdOrThrow(c.id())).thenReturn(c);
        return c;
    }

    private FinanceTransaction tx(UUID categoryId, String memo, long amount) {
        return new FinanceTransaction(UUID.randomUUID(), null, categoryId, USER, LocalDate.of(2026, 7, 5), amount, memo, null);
    }

    private AssetSnapshot snap(UUID id, UUID categoryId, UUID accountId, AssetClass ac, Market m, String strategy, String memo, long amount) {
        return new AssetSnapshot(id, null, categoryId, accountId, USER, LocalDate.of(2026, 7, 1), ac, m, strategy, memo, amount, null);
    }

    private FinanceAccount account(String name, String institution, String owner) {
        FinanceAccount a = new FinanceAccount(UUID.randomUUID(), null, USER, FinanceAccount.Type.BANK, name, null, null, institution, owner, null);
        when(accountPort.findByIdOrThrow(a.id())).thenReturn(a);
        return a;
    }

    @Test
    @DisplayName("[bulkRegisterPreview.test] 거래내역을 카테고리 타입(INCOME/EXPENSE/SAVING) 기준으로 분류한다")
    void 거래를_유형별로_분류한다() {
        FinanceCategory income = cat(Type.INCOME, "월급", 0, null);
        FinanceCategory expense = cat(Type.EXPENSE, "식비", 0, null);
        when(txPort.findMyScope(USER, null, MONTH.atDay(1), MONTH.atEndOfMonth(), null, null))
                .thenReturn(List.of(tx(income.id(), "7월급", 3_650_000), tx(expense.id(), "생활비카드", 480_000)));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        assertThat(r.income()).hasSize(1);
        assertThat(r.income().get(0).items()).hasSize(1);
        assertThat(r.expense()).hasSize(1);
        assertThat(r.income().get(0).items().get(0).memo()).isEqualTo("7월급");
        assertThat(r.income().get(0).items().get(0).categoryId()).isEqualTo(income.id());
    }

    @Test
    @DisplayName("[bulkRegisterPreview.test] 카테고리가 삭제돼 index에 없는 거래는 어느 섹션에도 넣지 않는다")
    void 삭제된_카테고리_거래는_제외한다() {
        cat(Type.INCOME, "월급", 0, null);
        when(txPort.findMyScope(any(), any(), any(), any(), any(), any())).thenReturn(List.of(tx(UUID.randomUUID(), null, 1000)));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        assertThat(r.income()).isEmpty();
        assertThat(r.expense()).isEmpty();
        assertThat(r.saving()).isEmpty();
    }

    @Test
    @DisplayName("[bulkRegisterPreview.test] 카테고리 트리가 3단이면 대/중/소분류 각 레벨의 그룹 노드로 중첩된다")
    void 삼단_트리는_중첩_노드가_된다() {
        FinanceCategory l1 = cat(Type.EXPENSE, "생활", 0, null);
        FinanceCategory l2 = cat(Type.EXPENSE, "식비", 0, l1.id());
        FinanceCategory l3 = cat(Type.EXPENSE, "외식", 0, l2.id());
        when(txPort.findMyScope(any(), any(), any(), any(), any(), any())).thenReturn(List.of(tx(l3.id(), "점심", 12_000)));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        assertThat(r.expense()).hasSize(1);
        assertThat(r.expense().get(0).id()).isEqualTo(l1.id());
        assertThat(r.expense().get(0).items()).isEmpty();
        var n2 = r.expense().get(0).children().get(0);
        assertThat(n2.id()).isEqualTo(l2.id());
        var n3 = n2.children().get(0);
        assertThat(n3.name()).isEqualTo("외식");
        assertThat(n3.items().get(0).memo()).isEqualTo("점심");
        assertThat(n3.items().get(0).categoryPath()).extracting(BulkRegisterPreview.PathSegment::id)
                .containsExactly(l1.id(), l2.id(), l3.id());
    }

    @Test
    @DisplayName("[bulkRegisterPreview.test] 자산 카테고리가 삭제돼 index에 없어도 루트·자기 이름으로 2단 경로를 만들어 포함한다")
    void 삭제된_자산_카테고리는_폴백_경로로_포함한다() {
        // 삭제된 카테고리는 selectable에 없고 findByIdOrThrow로만 조회된다
        FinanceCategory root = new FinanceCategory(UUID.randomUUID(), null, null, null, Type.ASSET, "예적금", 0, null);
        FinanceCategory leaf = new FinanceCategory(UUID.randomUUID(), null, root.id(), null, Type.ASSET, "예금", 0, null);
        when(categoryPort.findByIdOrThrow(root.id())).thenReturn(root);
        when(categoryPort.findByIdOrThrow(leaf.id())).thenReturn(leaf);
        FinanceAccount acc = account("정기예금", "KB국민은행", "홍길동");
        when(snapshotPort.findMyScope(any(), any(), any(), any(), any())).thenReturn(List.of(
                snap(UUID.randomUUID(), leaf.id(), acc.id(), AssetClass.CASH, Market.DOMESTIC, null, null, 5_000_000)));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        assertThat(r.asset()).hasSize(1);
        assertThat(r.asset().get(0).id()).isEqualTo(root.id());
        assertThat(r.asset().get(0).sortOrder()).isEqualTo(Integer.MAX_VALUE);
        var item = r.asset().get(0).children().get(0).items().get(0);
        assertThat(item.categoryName()).isEqualTo("예금");
        assertThat(item.amount()).isEqualTo(5_000_000);
        assertThat(item.accountName()).isEqualTo("정기예금");
        assertThat(item.accountInstitution()).isEqualTo("KB국민은행");
        assertThat(item.accountOwner()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("[bulkRegisterPreview.test] 자산 카테고리 트리가 실제로 있으면 그 트리 기준 전체 경로로 그룹핑한다")
    void 자산_트리가_있으면_인덱스_경로를_쓴다() {
        FinanceCategory root = cat(Type.ASSET, "예적금", 0, null);
        FinanceCategory leaf = cat(Type.ASSET, "예금", 0, root.id());
        when(snapshotPort.findMyScope(any(), any(), any(), any(), any())).thenReturn(List.of(
                snap(UUID.randomUUID(), leaf.id(), null, AssetClass.CASH, Market.DOMESTIC, null, null, 5_000_000)));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        assertThat(r.asset().get(0).id()).isEqualTo(root.id());
        assertThat(r.asset().get(0).sortOrder()).isZero();
        assertThat(r.asset().get(0).children().get(0).items().get(0).categoryId()).isEqualTo(leaf.id());
    }

    @Test
    @DisplayName("[bulkRegisterPreview.test] 같은 카테고리의 자산 항목은 compareAssetDetail 순서(소유자 우선, 같으면 금액 큰 순)로 정렬한다")
    void 자산_항목은_소유자_우선_금액_큰순() {
        FinanceCategory leaf = cat(Type.ASSET, "예금", 0, null);
        FinanceAccount wife = account("a", "i", "아내");
        FinanceAccount me = account("a", "i", "나");
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID(), s3 = UUID.randomUUID();
        when(snapshotPort.findMyScope(any(), any(), any(), any(), any())).thenReturn(List.of(
                snap(s1, leaf.id(), wife.id(), AssetClass.CASH, Market.DOMESTIC, null, null, 9),
                snap(s2, leaf.id(), me.id(), AssetClass.CASH, Market.DOMESTIC, null, null, 1),
                snap(s3, leaf.id(), me.id(), AssetClass.CASH, Market.DOMESTIC, null, null, 5)));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        assertThat(r.asset().get(0).items()).extracting(BulkRegisterPreview.Item::id).containsExactly(s3, s2, s1);
    }

    @Test
    @DisplayName("[assetSort.test] 키 우선순위: 소유자 → 기관 → 계좌명 → 전략 → 시장 → 자산군 → 금액 큰 순 → 메모 → id")
    void 자산_정렬_키_우선순위() {
        FinanceCategory leaf = cat(Type.ASSET, "예금", 0, null);
        FinanceAccount base = account("연금", "한투", "나");
        FinanceAccount owner = account("연금", "가", "아내");
        FinanceAccount inst = account("가", "현대", "나");
        FinanceAccount acct = account("일반", "한투", "나");
        UUID[] id = new UUID[10];
        for (int i = 0; i < id.length; i++) id[i] = UUID.fromString("00000000-0000-0000-0000-00000000000" + i);
        // 인덱스: 0 owner(아내), 1 institution(현대), 2 account(일반), 3 strategy(성장), 4 market, 5 assetClass, 6 small, 7 memo, 8 b-tie, 9 a-tie
        // UI 케이스와 달리 enum 순서가 서버(선언 순서 CASH<EQUITY, DOMESTIC<GLOBAL)라 market/assetClass 항목의 방향만 다르다
        when(snapshotPort.findMyScope(any(), any(), any(), any(), any())).thenReturn(List.of(
                snap(id[0], leaf.id(), owner.id(), AssetClass.EQUITY, Market.GLOBAL, "배당", "적립", 100),
                snap(id[1], leaf.id(), inst.id(), AssetClass.EQUITY, Market.GLOBAL, "배당", "적립", 100),
                snap(id[2], leaf.id(), acct.id(), AssetClass.EQUITY, Market.GLOBAL, "가", "적립", 100),
                snap(id[3], leaf.id(), base.id(), AssetClass.EQUITY, Market.GLOBAL, "성장", "적립", 100),
                snap(id[4], leaf.id(), base.id(), AssetClass.EQUITY, Market.GLOBAL, "배당", "적립", 100),
                snap(id[5], leaf.id(), base.id(), AssetClass.CASH, Market.DOMESTIC, "배당", "적립", 100),
                snap(id[6], leaf.id(), base.id(), AssetClass.EQUITY, Market.GLOBAL, "배당", "가", 50),
                snap(id[7], leaf.id(), base.id(), AssetClass.EQUITY, Market.GLOBAL, "배당", "하", 100),
                snap(id[8], leaf.id(), base.id(), AssetClass.EQUITY, Market.GLOBAL, "배당", "적립", 100),
                snap(id[9], leaf.id(), base.id(), AssetClass.EQUITY, Market.GLOBAL, "배당", "적립", 100)));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        // 나(base·inst·acct) 먼저, 아내 마지막. base(한투/연금) → 같은 기관 한투의 acct(일반) → inst(현대).
        // base 안에서 전략 배당 < 성장, 시장 DOMESTIC < GLOBAL, 금액 100 > 50, 메모 적립 < 하, id 오름차순
        List<UUID> ids = r.asset().get(0).items().stream().map(BulkRegisterPreview.Item::id).toList();
        assertThat(ids).containsExactly(id[5], id[4], id[8], id[9], id[7], id[6], id[3], id[2], id[1], id[0]);
    }

    @Test
    @DisplayName("[assetSort.test] 빈 값(null·빈 문자열)은 맨 뒤")
    void 빈_값은_맨_뒤() {
        FinanceCategory leaf = cat(Type.ASSET, "예금", 0, null);
        FinanceAccount filled = account("a", "i", "나");
        FinanceAccount empty = account("a", "i", "");
        FinanceAccount nul = account("a", "i", null);
        UUID f = UUID.randomUUID();
        when(snapshotPort.findMyScope(any(), any(), any(), any(), any())).thenReturn(List.of(
                snap(UUID.randomUUID(), leaf.id(), nul.id(), AssetClass.CASH, Market.DOMESTIC, null, null, 1),
                snap(UUID.randomUUID(), leaf.id(), empty.id(), AssetClass.CASH, Market.DOMESTIC, null, null, 1),
                snap(f, leaf.id(), filled.id(), AssetClass.CASH, Market.DOMESTIC, null, null, 1)));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        assertThat(r.asset().get(0).items().get(0).id()).isEqualTo(f);
    }

    @Test
    @DisplayName("[assetSort.test compareCategoryPath] 노드는 sortOrder 오름차순, 같으면 이름 가나다순")
    void 노드는_정렬순서_후_이름순() {
        FinanceCategory b = cat(Type.EXPENSE, "나", 0, null);
        FinanceCategory a = cat(Type.EXPENSE, "가", 0, null);
        FinanceCategory first = cat(Type.EXPENSE, "하", -1, null);
        when(txPort.findMyScope(any(), any(), any(), any(), any(), any())).thenReturn(List.of(
                tx(b.id(), null, 1), tx(a.id(), null, 1), tx(first.id(), null, 1)));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        assertThat(r.expense()).extracting(BulkRegisterPreview.GroupNode::name).containsExactly("하", "가", "나");
    }

    @Test
    @DisplayName("[bulkRegisterPreview] 거래 항목은 메모 가나다(null은 빈 문자열) → 금액 오름차순")
    void 거래_항목_정렬() {
        FinanceCategory c = cat(Type.EXPENSE, "식비", 0, null);
        FinanceTransaction nullMemo = tx(c.id(), null, 5);
        FinanceTransaction b = tx(c.id(), "나", 1);
        FinanceTransaction a2 = tx(c.id(), "가", 9);
        FinanceTransaction a1 = tx(c.id(), "가", 2);
        when(txPort.findMyScope(any(), any(), any(), any(), any(), any())).thenReturn(List.of(b, a2, nullMemo, a1));

        BulkRegisterPreview r = service.preview(USER, MONTH);

        assertThat(r.expense().get(0).items()).extracting(BulkRegisterPreview.Item::id)
                .containsExactly(nullMemo.id(), a1.id(), a2.id(), b.id());
    }
}
