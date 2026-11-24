package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.domain.model.StrategyAction.BlockReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.kista.tradingcore.trading.domain.model.StrategyAction.*;
import static org.assertj.core.api.Assertions.assertThat;

class StrategyActionJudgmentTest {

    // 종류별 지원 행위 — 판정 함수와 별도로 손으로 적은 기대값(서비스 종류 거절 지점 기준)
    private static final Map<StrategyType, Set<StrategyAction>> SUPPORTED = Map.of(
            StrategyType.INFINITE, EnumSet.of(PAUSE, RESUME, ARCHIVE, DELETE, EDIT_SEED, INJECT_CAPITAL, WITHDRAW_CAPITAL),
            StrategyType.PRIVACY, EnumSet.of(PAUSE, RESUME, ARCHIVE, DELETE, EDIT_SEED, INJECT_CAPITAL, WITHDRAW_CAPITAL),
            StrategyType.VR, EnumSet.of(PAUSE, RESUME, ARCHIVE, DELETE, RESET_VR),
            StrategyType.DCA, EnumSet.of(PAUSE, RESUME, ARCHIVE, DELETE, INJECT_CAPITAL, WITHDRAW_CAPITAL, EDIT_DCA_CONFIG));

    private static final Integer[] HOLDINGS = {null, 0, 3};
    private static final BigDecimal[] LEDGERS = {null, BigDecimal.ZERO, new BigDecimal("500.00")};

    @Test
    void invariant_union_is_supported_set_and_lists_are_disjoint_for_every_type_and_state() {
        for (StrategyType type : StrategyType.values()) {
            for (StrategyStatus status : StrategyStatus.values()) {
                for (boolean inProgress : new boolean[]{true, false}) {
                    for (Integer holdings : HOLDINGS) {
                        for (BigDecimal ledger : LEDGERS) {
                            StrategyActionJudgment j = StrategyActionJudgment.of(detail(type, status, inProgress, holdings, ledger));
                            Set<StrategyAction> blocked = j.blocked().stream().map(StrategyActionJudgment.Blocked::action).collect(Collectors.toSet());
                            Set<StrategyAction> union = new HashSet<>(j.allowed());
                            union.addAll(blocked);
                            String ctx = type + "/" + status + "/" + inProgress + "/" + holdings + "/" + ledger;
                            assertThat(union).as(ctx).isEqualTo(SUPPORTED.get(type));
                            assertThat(j.allowed()).as(ctx).filteredOn(blocked::contains).isEmpty(); // 막힌 목록이 비어도 성립하는 교집합 검사
                            assertThat(j.allowed().size() + j.blocked().size()).as(ctx).isEqualTo(SUPPORTED.get(type).size()); // 중복 없음
                            assertThat(j.blocked()).as(ctx).allSatisfy(b -> assertThat(b.reason()).isNotNull());
                            // 목록은 enum 선언순 — JSON 순서 안정
                            assertThat(j.allowed()).as(ctx).isSortedAccordingTo(Enum::compareTo);
                            assertThat(j.blocked().stream().map(StrategyActionJudgment.Blocked::action).toList()).as(ctx).isSortedAccordingTo(Enum::compareTo);
                            // 삭제는 어떤 상태에서도 가능
                            assertThat(j.allowed()).as(ctx).contains(DELETE);
                        }
                    }
                }
            }
        }
    }

    @Test
    void archived_blocks_everything_but_delete_with_ARCHIVED() {
        for (StrategyType type : StrategyType.values()) {
            // 사이클 종료·보유 수량·예수금 0이 겹쳐도 보관 사유가 먼저다(서비스 검사 순서)
            StrategyActionJudgment j = StrategyActionJudgment.of(detail(type, StrategyStatus.ARCHIVED, false, 3, BigDecimal.ZERO));
            assertThat(j.allowed()).containsExactly(DELETE);
            assertThat(j.blocked()).allSatisfy(b -> assertThat(b.reason()).isEqualTo(BlockReason.ARCHIVED));
        }
    }

    @Test
    void active_blocks_resume_and_paused_blocks_pause() {
        assertThat(reasonOf(detail(StrategyType.VR, StrategyStatus.ACTIVE, true, 0, null), RESUME)).isEqualTo(BlockReason.ALREADY_ACTIVE);
        assertThat(reasonOf(detail(StrategyType.VR, StrategyStatus.ACTIVE, true, 0, null), PAUSE)).isNull();
        assertThat(reasonOf(detail(StrategyType.VR, StrategyStatus.PAUSED, true, 0, null), PAUSE)).isEqualTo(BlockReason.ALREADY_PAUSED);
        assertThat(reasonOf(detail(StrategyType.VR, StrategyStatus.PAUSED, true, 0, null), RESUME)).isNull();
    }

    @Test
    void archive_blocked_only_by_holdings_and_no_position_passes() {
        assertThat(reasonOf(detail(StrategyType.INFINITE, StrategyStatus.ACTIVE, true, 3, null), ARCHIVE)).isEqualTo(BlockReason.HAS_HOLDINGS);
        assertThat(reasonOf(detail(StrategyType.INFINITE, StrategyStatus.ACTIVE, true, 0, null), ARCHIVE)).isNull();
        assertThat(reasonOf(detail(StrategyType.INFINITE, StrategyStatus.PAUSED, false, null, null), ARCHIVE)).isNull();
    }

    @Test
    void edit_seed_checks_cycle_before_holdings() {
        assertThat(reasonOf(detail(StrategyType.PRIVACY, StrategyStatus.ACTIVE, false, 3, null), EDIT_SEED)).isEqualTo(BlockReason.NO_ACTIVE_CYCLE);
        assertThat(reasonOf(detail(StrategyType.PRIVACY, StrategyStatus.ACTIVE, true, 3, null), EDIT_SEED)).isEqualTo(BlockReason.HAS_HOLDINGS);
        assertThat(reasonOf(detail(StrategyType.PRIVACY, StrategyStatus.PAUSED, true, 0, null), EDIT_SEED)).isNull();
    }

    @Test
    void capital_adjustment_follows_kista_ui_capitalAdjustment_rules() {
        // supportsCapitalAdjustment: 진행 중 사이클이 있어야 한다
        assertThat(reasonOf(detail(StrategyType.DCA, StrategyStatus.ACTIVE, false, 0, new BigDecimal("500")), INJECT_CAPITAL)).isEqualTo(BlockReason.NO_ACTIVE_CYCLE);
        assertThat(reasonOf(detail(StrategyType.DCA, StrategyStatus.ACTIVE, false, 0, new BigDecimal("500")), WITHDRAW_CAPITAL)).isEqualTo(BlockReason.NO_ACTIVE_CYCLE);
        assertThat(reasonOf(detail(StrategyType.DCA, StrategyStatus.ACTIVE, true, 0, new BigDecimal("500")), INJECT_CAPITAL)).isNull();
        // canWithdrawCapital: 장부 예수금 0 이하면 불가, 값을 모르면 허용
        assertThat(reasonOf(detail(StrategyType.INFINITE, StrategyStatus.ACTIVE, true, 0, BigDecimal.ZERO), WITHDRAW_CAPITAL)).isEqualTo(BlockReason.NO_WITHDRAWABLE_BALANCE);
        assertThat(reasonOf(detail(StrategyType.INFINITE, StrategyStatus.ACTIVE, true, 0, new BigDecimal("0.01")), WITHDRAW_CAPITAL)).isNull();
        assertThat(reasonOf(detail(StrategyType.INFINITE, StrategyStatus.ACTIVE, true, 0, null), WITHDRAW_CAPITAL)).isNull();
        // 예수금 0이어도 추가는 가능
        assertThat(reasonOf(detail(StrategyType.INFINITE, StrategyStatus.ACTIVE, true, 0, BigDecimal.ZERO), INJECT_CAPITAL)).isNull();
    }

    @Test
    void vr_reset_and_dca_config_blocked_only_when_archived() {
        assertThat(reasonOf(detail(StrategyType.VR, StrategyStatus.PAUSED, false, 3, BigDecimal.ZERO), RESET_VR)).isNull();
        assertThat(reasonOf(detail(StrategyType.DCA, StrategyStatus.PAUSED, false, 3, BigDecimal.ZERO), EDIT_DCA_CONFIG)).isNull();
    }

    @Test
    void every_action_is_supported_by_some_type() {
        // 지원 종류가 하나도 없는 행위는 응답에 영영 나오지 않는다 — enum 정리 신호
        assertThat(Arrays.stream(StrategyAction.values()))
                .allSatisfy(a -> assertThat(Arrays.stream(StrategyType.values()).anyMatch(a::supports)).as(a.name()).isTrue());
    }

    private static BlockReason reasonOf(StrategyDetail detail, StrategyAction action) {
        StrategyActionJudgment j = StrategyActionJudgment.of(detail);
        assertThat(j.allowed().contains(action) || j.blocked().stream().anyMatch(b -> b.action() == action)).as(action.name()).isTrue();
        return j.blocked().stream().filter(b -> b.action() == action).map(StrategyActionJudgment.Blocked::reason).findFirst().orElse(null);
    }

    private static StrategyDetail detail(StrategyType type, StrategyStatus status, boolean cycleInProgress, Integer holdings, BigDecimal ledgerDeposit) {
        Strategy strategy = new Strategy(UUID.randomUUID(), UUID.randomUUID(), type, status, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        return new StrategyDetail(strategy, new BigDecimal("1000"), null, null, false, null, holdings, null, null, cycleInProgress, ledgerDeposit);
    }
}
