package com.kista.api.finance.application.service;

import com.kista.api.finance.domain.model.AssetSnapshotCommand;
import com.kista.api.finance.domain.model.BulkFinanceRegisterResult;
import com.kista.api.finance.domain.model.FinanceTransactionCommand;
import com.kista.api.finance.application.port.output.AssetSnapshotPort;
import com.kista.api.finance.application.port.output.FinanceAccountPort;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
import com.kista.api.finance.application.port.output.FinanceGroupPort;
import com.kista.api.finance.application.port.output.FinanceTransactionPort;
import com.kista.api.finance.domain.model.AssetSnapshot;
import com.kista.api.finance.domain.model.BulkRegisterPreview;
import com.kista.api.finance.domain.model.BulkRegisterPreview.GroupNode;
import com.kista.api.finance.domain.model.BulkRegisterPreview.Item;
import com.kista.api.finance.domain.model.BulkRegisterPreview.PathSegment;
import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FinanceTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

// 항목별 독립 처리 — AssetSnapshotService/FinanceTransactionService의 create()가 이미 자체 트랜잭션 경계라
// 여기서 전체를 하나의 @Transactional로 묶지 않는다. 한 항목 실패가 나머지 항목 등록을 막지 않기 위함.
// shareToGroup=true면 각 create가 원자적으로 그룹 소유로 생성한다(별도 share 전환 단계 없음).
@Service
@RequiredArgsConstructor
public class BulkFinanceRegisterService {

    private final AssetSnapshotService assetSnapshotService;
    private final FinanceTransactionService financeTransactionService;
    private final FinanceGroupPort financeGroupPort;
    private final AssetSnapshotPort assetSnapshotPort; // 소스월 자산 스냅샷 조회
    private final FinanceTransactionPort transactionPort; // 소스월 거래 조회
    private final FinanceCategoryPort categoryPort; // 카테고리 인덱스·삭제 카테고리 폴백 조회
    private final FinanceAccountPort accountPort; // 스냅샷 계좌 조인

    private static final Collator KO = Collator.getInstance(Locale.KOREAN); // 가나다순 비교

    // shareToGroup=true면 각 항목을 등록자의 현재 그룹 소유로 원자적 생성한다.
    // 무그룹 유저가 true를 넘기면 IllegalStateException(400).
    public BulkFinanceRegisterResult register(UUID userId, boolean shareToGroup,
                                               List<AssetSnapshotCommand> assets,
                                               List<FinanceTransactionCommand> transactions) {
        // 무그룹 유저의 공유 요청은 항목마다 실패시키지 않고 진입에서 차단 (kista-ui가 토글을 숨기므로 방어용)
        // shareToGroup=false면 그룹 조회 자체를 하지 않는다 — GroupShareSupport.requireGroupIfSharing은
        // 인자로 이미 조회된 UUID를 받아 이 단락평가를 못 하므로 여기서는 직접 조건문을 유지한다.
        if (shareToGroup) {
            GroupShareSupport.requireCurrentGroup(financeGroupPort.findCurrentGroupId(userId));
        }

        List<String> failures = new ArrayList<>();
        int assetSuccess = processAll(assets, c -> assetSnapshotService.create(userId, shareToGroup, c),
                AssetSnapshotCommand::memo, "자산", failures);
        int txSuccess = processAll(transactions, c -> financeTransactionService.create(userId, shareToGroup, c),
                FinanceTransactionCommand::memo, "거래", failures);

        return new BulkFinanceRegisterResult(assetSuccess, txSuccess, failures);
    }

    // 항목별 독립 처리 — 하나 실패해도 나머지는 계속 진행하고 실패 사유만 수집
    private <T> int processAll(List<T> items, Consumer<T> create, Function<T, String> labelOf,
                                String kind, List<String> failures) {
        int success = 0;
        for (T item : items) {
            try {
                create.accept(item);
                success++;
            } catch (Exception e) {
                failures.add(kind + "(" + labelOf.apply(item) + "): " + e.getMessage());
            }
        }
        return success;
    }

    // 소스월의 거래·스냅샷을 카테고리 계층 그룹 트리로 묶는다 (kista-ui bulkRegisterPreview.ts 이식)
    @Transactional(readOnly = true)
    public BulkRegisterPreview preview(UUID userId, YearMonth sourceMonth) {
        UUID groupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        var from = sourceMonth.atDay(1);
        var to = sourceMonth.atEndOfMonth();

        // 4유형 selectable 카테고리 인덱스 (삭제된 카테고리는 없음)
        Map<UUID, FinanceCategory> byId = new HashMap<>();
        for (FinanceCategory.Type type : FinanceCategory.Type.values()) {
            categoryPort.findSelectable(userId, groupId, type).forEach(c -> byId.put(c.id(), c));
        }

        List<Item> income = new ArrayList<>();
        List<Item> expense = new ArrayList<>();
        List<Item> saving = new ArrayList<>();
        for (FinanceTransaction t : transactionPort.findMyScope(userId, groupId, from, to, null, null)) {
            FinanceCategory category = byId.get(t.categoryId());
            List<PathSegment> path = category == null ? null : indexedPath(category, byId);
            if (path == null) continue; // 삭제된 카테고리의 거래는 어느 섹션에도 넣지 않는다
            Item item = new Item(t.id(), t.categoryId(), category.name(), path, t.memo(), t.amount(),
                    null, null, null, null, null, null, null);
            switch (category.type()) {
                case INCOME -> income.add(item);
                case EXPENSE -> expense.add(item);
                case SAVING -> saving.add(item);
                default -> { }
            }
        }

        Map<UUID, FinanceAccount> accounts = new HashMap<>();
        // 삭제 카테고리 폴백 조회 메모 — byId로 시드, 없으면 findByIdOrThrow로 채운다
        Map<UUID, FinanceCategory> memo = new HashMap<>(byId);
        List<Item> asset = new ArrayList<>();
        for (AssetSnapshot s : assetSnapshotPort.findMyScope(userId, groupId, from, to, null)) {
            FinanceCategory category = byId.get(s.categoryId());
            List<PathSegment> path = category == null ? null : indexedPath(category, byId);
            if (path == null) {
                // 삭제된 카테고리라도 자산은 루트·자기 이름으로 1~2단 경로를 만들어 포함한다 — sortOrder 미상이라 맨 뒤
                category = memo.computeIfAbsent(s.categoryId(), categoryPort::findByIdOrThrow);
                FinanceCategory root = category;
                while (root.parentId() != null) root = memo.computeIfAbsent(root.parentId(), categoryPort::findByIdOrThrow);
                path = new ArrayList<>(List.of(new PathSegment(root.id(), root.name(), Integer.MAX_VALUE)));
                if (!category.id().equals(root.id())) {
                    path.add(new PathSegment(category.id(), category.name(), Integer.MAX_VALUE));
                }
            }
            // 계좌는 서버에서 조인 — 그룹 공유 스냅샷이 다른 멤버 계좌를 가리켜도 채워진다
            FinanceAccount account = s.accountId() == null ? null
                    : accounts.computeIfAbsent(s.accountId(), accountPort::findByIdOrThrow);
            asset.add(new Item(s.id(), s.categoryId(), category.name(), path, s.memo(), s.amount(),
                    s.assetClass(), s.market(), s.strategy(), s.accountId(),
                    account == null ? null : account.name(),
                    account == null ? null : account.institution(),
                    account == null ? null : account.owner()));
        }

        return new BulkRegisterPreview(
                buildGroupTree(asset, BulkFinanceRegisterService::compareAssetDetail),
                buildGroupTree(income, BulkFinanceRegisterService::compareFlowItem),
                buildGroupTree(expense, BulkFinanceRegisterService::compareFlowItem),
                buildGroupTree(saving, BulkFinanceRegisterService::compareFlowItem));
    }

    // 루트→자신 경로. 조상이 인덱스에 없으면 null(UI 트리에도 없는 카테고리)
    private static List<PathSegment> indexedPath(FinanceCategory category, Map<UUID, FinanceCategory> byId) {
        List<PathSegment> path = new ArrayList<>();
        for (FinanceCategory c = category; c != null; c = c.parentId() == null ? null : byId.get(c.parentId())) {
            path.add(0, new PathSegment(c.id(), c.name(), c.sortOrder()));
            if (c.parentId() != null && !byId.containsKey(c.parentId())) return null;
        }
        return path;
    }

    // 수입·소비·저축 항목 순서 — 메모 가나다(null은 "") → 금액 오름차순
    private static int compareFlowItem(Item a, Item b) {
        int c = KO.compare(a.memo() == null ? "" : a.memo(), b.memo() == null ? "" : b.memo());
        return c != 0 ? c : Long.compare(a.amount(), b.amount());
    }

    // 자산 항목 순서(assetSort.ts compareAssetDetail) — 소유자·기관·계좌명·전략(빈 값 뒤) → 시장·자산군(enum 순) → 금액 큰 순 → 메모 → id
    private static int compareAssetDetail(Item a, Item b) {
        int c = compareText(a.accountOwner(), b.accountOwner());
        if (c == 0) c = compareText(a.accountInstitution(), b.accountInstitution());
        if (c == 0) c = compareText(a.accountName(), b.accountName());
        if (c == 0) c = compareText(a.strategy(), b.strategy());
        if (c == 0) c = Integer.compare(rank(a.market()), rank(b.market()));
        if (c == 0) c = Integer.compare(rank(a.assetClass()), rank(b.assetClass()));
        if (c == 0) c = Long.compare(b.amount(), a.amount());
        if (c == 0) c = compareText(a.memo(), b.memo());
        return c != 0 ? c : a.id().toString().compareTo(b.id().toString()); // 코드 단위 비교
    }

    // 빈 값(null·"")은 맨 뒤
    private static int compareText(String a, String b) {
        boolean aBlank = a == null || a.isEmpty();
        boolean bBlank = b == null || b.isEmpty();
        if (aBlank || bBlank) return Boolean.compare(aBlank, bBlank);
        return KO.compare(a, b);
    }

    // enum 선언 순서, null은 맨 뒤
    private static int rank(Enum<?> e) {
        return e == null ? Integer.MAX_VALUE : e.ordinal();
    }

    private static final class MutableNode {
        final PathSegment seg;
        final List<Item> items = new ArrayList<>();
        final Map<UUID, MutableNode> children = new LinkedHashMap<>();

        MutableNode(PathSegment seg) {
            this.seg = seg;
        }
    }

    // item.categoryPath를 트리 경로로 삼아 항목을 중첩 그룹으로 묶는다
    private static List<GroupNode> buildGroupTree(List<Item> items, Comparator<Item> compare) {
        Map<UUID, MutableNode> roots = new LinkedHashMap<>();
        for (Item item : items) {
            Map<UUID, MutableNode> siblings = roots;
            MutableNode node = null;
            for (PathSegment seg : item.categoryPath()) {
                node = siblings.computeIfAbsent(seg.id(), id -> new MutableNode(seg));
                siblings = node.children;
            }
            if (node != null) node.items.add(item);
        }
        return finalizeTree(roots, compare);
    }

    // 노드 정렬: sortOrder 오름차순 → 이름 가나다
    private static List<GroupNode> finalizeTree(Map<UUID, MutableNode> map, Comparator<Item> compare) {
        return map.values().stream()
                .map(n -> new GroupNode(n.seg.id(), n.seg.name(), n.seg.sortOrder(),
                        n.items.stream().sorted(compare).toList(), finalizeTree(n.children, compare)))
                .sorted(Comparator.comparingInt(GroupNode::sortOrder).thenComparing(GroupNode::name, KO))
                .toList();
    }
}
