package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.TimeZones;
import com.kista.shared.support.FixedClockTestConfig;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.stats.application.InvestmentIndexCalculator;
import com.kista.tradingcore.stats.domain.model.EquityPoint;
import com.kista.tradingcore.stats.domain.model.InvestmentPoint;
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// 곡선(TradingStatsService.getEquityCurve)과 지수(InvestmentIndexCalculator.calculate)에 같은 사이클·스냅샷을 넣어
// 날짜별로 "그날 어떤 사이클의 값이 쓰였는가"를 대조하는 특성화 테스트 — 코드를 고치지 않고 지금 갈리는 지점을 고정한다.
// 사이클마다 평가액 대역이 다르다(A=1000대, B=5000대, T=20000대)라 곡선 금액으로 기준 사이클을 식별한다.
// 지수는 값이 아니라 누적지수·일수익률만 나오므로, 사이클 교체 흐름이 상쇄되는 구조(구간 내 일정 증가)로 점 유무·일수익률을 고정한다.
@ExtendWith(MockitoExtension.class)
class StatsCurveIndexDivergenceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock CycleCapitalEventPort cycleCapitalEventPort;
    TradingStatsService statsService;
    final InvestmentIndexCalculator calculator = new InvestmentIndexCalculator();

    static final UUID USER_ID = UUID.randomUUID();
    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID STRATEGY_ID = UUID.randomUUID();
    static final UUID OTHER_STRATEGY_ID = UUID.randomUUID();
    static final LocalDate TO = LocalDate.of(2026, 3, 20);

    @BeforeEach
    void setUp() {
        statsService = new TradingStatsService(accountPort, strategyPort, strategyCyclePort, cyclePositionPort,
                cycleCapitalEventPort, FixedClockTestConfig.FIXED);
    }

    // 두 계산의 날짜별 결과 — 곡선은 스냅샷이 있는 날만 점이 나온다
    record Both(Map<LocalDate, String> curve, Map<LocalDate, String> index) {
        String curveOn(String date) { return curve.get(LocalDate.parse(date)); }
        String indexOn(String date) { return index.get(LocalDate.parse(date)); }
    }

    private Both run(List<StrategyCycle> cycles, List<CyclePosition> positions, UUID... strategyIds) {
        List<Strategy> strategies = new ArrayList<>();
        for (UUID id : strategyIds) {
            strategies.add(new Strategy(id, ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ACTIVE,
                    StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        }
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(new Account(ACCOUNT_ID, USER_ID, "테스트계좌",
                "74420614", "appKey", "appSecret", null, Broker.TOSS, null, null)));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, strategies));
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(cycles);
        when(cyclePositionPort.findByCycleIdsAndRange(any(), any(), any())).thenReturn(positions);

        Map<LocalDate, String> curve = new TreeMap<>();
        for (EquityPoint p : statsService.getEquityCurve(USER_ID, Currency.USD, null, null, TO).points()) {
            curve.put(p.date(), p.totalAsset().stripTrailingZeros().toPlainString());
        }
        Map<LocalDate, String> index = new TreeMap<>();
        for (InvestmentPoint p : calculator.calculate(cycles, positions, List.of(), LocalDate.of(2026, 3, 1), TO)) {
            index.put(p.baseDate(), p.investmentIndex().stripTrailingZeros().toPlainString()
                    + "(" + p.periodReturn().stripTrailingZeros().toPlainString() + ")");
        }
        return new Both(curve, index);
    }

    // 날짜 d의 지수 일수익률 문자열 — 점이 없으면 null
    private static String ret(Both r, String date) {
        String v = r.indexOn(date);
        return v == null ? null : v.substring(v.indexOf('(') + 1, v.length() - 1);
    }

    // 1. 정상 롤오버 — 교체일(3/10)에 새 사이클 스냅샷이 있다
    // 사이클: A(start 3/1, end 3/10, 1000대) → B(start 3/10, 5000대). 스냅샷: A 3/5·3/9·3/10, B 3/10·3/11·3/12
    // 곡선: 3/10 = B(5000) (스냅샷 있는 가장 나중 사이클). 지수: 3/10 = B 선택(startDate 도달), 교체 흐름 3900(=5000-A마지막 1100)을 빼 일수익률 1100/1050-1
    // → 같은 사이클(B)을 기준으로 삼는다. 근거: TradingStatsService.java:345-359,378, InvestmentIndexCalculator.java:79-86,267-272
    @Test
    @DisplayName("같음: 정상 롤오버 — 교체일에 곡선도 지수도 새 사이클 B를 쓴다")
    void 정상_롤오버는_교체일에_두_계산이_같은_사이클을_쓴다() {
        UUID s = STRATEGY_ID;
        StrategyCycle a = cycle(s, "1000", "1100", "2026-03-01", "2026-03-10", "2026-03-01T00:00");
        StrategyCycle b = cycle(s, "5000", null, "2026-03-10", null, "2026-03-10T00:00");
        Both r = run(List.of(a, b), List.of(
                snap(a, "2026-03-05T12:00", "1000"), snap(a, "2026-03-09T12:00", "1050"), snap(a, "2026-03-10T09:00", "1100"),
                snap(b, "2026-03-10T12:00", "5000"), snap(b, "2026-03-11T12:00", "5100"), snap(b, "2026-03-12T12:00", "5200")), s);

        assertThat(r.curveOn("2026-03-09")).isEqualTo("1050");
        assertThat(r.curveOn("2026-03-10")).isEqualTo("5000"); // B
        assertThat(r.curveOn("2026-03-11")).isEqualTo("5100");
        assertThat(ret(r, "2026-03-09")).isEqualTo("0.05");
        assertThat(ret(r, "2026-03-10")).isEqualTo("0.0476190476"); // 1100/1050-1: B 5000에서 교체 흐름 3900 차감
        assertThat(ret(r, "2026-03-11")).isEqualTo("0.02"); // 5100/5000-1: B
    }

    // 2a. 지연 롤오버 단일 전략 — B(start 3/10)의 첫 스냅샷이 3/12. 3/10·3/11엔 전략 스냅샷이 아예 없다
    // 곡선: 점이 없다(스냅샷 있는 날만 점). 지수: B를 고르고 값이 없어 3/10·3/11을 건너뛴다. 3/12 일수익률 0(교체 흐름이 A마지막 1050과 상쇄)
    // → 점 유무까지 같다. 근거: TradingStatsService.java:338-343, InvestmentIndexCalculator.java:88-100
    @Test
    @DisplayName("같음: 지연 롤오버(단일 전략) — 3/10·3/11에 곡선·지수 모두 점이 없다")
    void 지연_롤오버_단일_전략은_두_계산_모두_점이_없다() {
        UUID s = STRATEGY_ID;
        StrategyCycle a = cycle(s, "1000", "1100", "2026-03-01", "2026-03-10", "2026-03-01T00:00");
        StrategyCycle b = cycle(s, "5000", null, "2026-03-10", null, "2026-03-10T00:00");
        Both r = run(List.of(a, b), List.of(
                snap(a, "2026-03-05T12:00", "1000"), snap(a, "2026-03-09T12:00", "1050"),
                snap(b, "2026-03-12T12:00", "5000"), snap(b, "2026-03-13T12:00", "5100")), s);

        assertThat(r.curve()).doesNotContainKeys(LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-11"));
        assertThat(r.index()).doesNotContainKeys(LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-11"));
        assertThat(r.curveOn("2026-03-12")).isEqualTo("5000");
        assertThat(ret(r, "2026-03-12")).isEqualTo("0");
    }

    // 2b. 지연 롤오버 + 다른 전략 T(20000대)가 매일 스냅샷을 남기는 사용자 — 3/10·3/11
    // 곡선: 앞 사이클 A의 마지막 값(1050)을 이어 써 21050(=1050+20000). 지수: B를 고르고 값이 없어 포트폴리오 전체를 건너뛴다(점 없음)
    // → 갈린다. 근거: TradingStatsService.java:345-359(chain은 스냅샷이 들어온 사이클만), InvestmentIndexCalculator.java:79-100
    @Test
    @DisplayName("갈림: 지연 롤오버(두 전략) — 곡선은 3/10·3/11에 앞 사이클 값을 이어 점을 내고 지수는 그 두 날을 건너뛴다")
    void 지연_롤오버는_다른_전략_스냅샷이_있으면_곡선만_점을_낸다() {
        UUID s = STRATEGY_ID;
        StrategyCycle a = cycle(s, "1000", "1100", "2026-03-01", "2026-03-10", "2026-03-01T00:00");
        StrategyCycle b = cycle(s, "5000", null, "2026-03-10", null, "2026-03-10T00:00");
        StrategyCycle t = cycle(OTHER_STRATEGY_ID, "20000", null, "2026-03-01", null, "2026-03-01T00:00");
        List<CyclePosition> ps = new ArrayList<>(List.of(
                snap(a, "2026-03-05T12:00", "1000"), snap(a, "2026-03-09T12:00", "1050"),
                snap(b, "2026-03-12T12:00", "5000"), snap(b, "2026-03-13T12:00", "5100")));
        for (int d = 5; d <= 14; d++) ps.add(snap(t, "2026-03-%02dT12:00".formatted(d), "20000"));
        Both r = run(List.of(a, b, t), ps, s, OTHER_STRATEGY_ID);

        assertThat(r.curveOn("2026-03-09")).isEqualTo("21050");
        assertThat(r.curveOn("2026-03-10")).isEqualTo("21050"); // A 마지막 값 이월
        assertThat(r.curveOn("2026-03-11")).isEqualTo("21050");
        assertThat(r.curveOn("2026-03-12")).isEqualTo("25000"); // B 첫 스냅샷
        assertThat(r.indexOn("2026-03-10")).isNull(); // B 선택 + 값 없음 → 건너뜀
        assertThat(r.indexOn("2026-03-11")).isNull();
        assertThat(ret(r, "2026-03-12")).isEqualTo("0"); // 교체 흐름이 상쇄돼 곡선의 누적 값과 이어진다
    }

    // 3. 같은 날 같은 사이클 스냅샷 둘(10:00=1000, 15:00=1200). 서비스 진입점은 CapitalLedgerTimeline.restore가 createdAt으로 정렬해
    // 곡선도 입력 순서에 의존하지 않는다(buildPoints 단독의 put 의존은 공개 경로로는 닿지 않음). 3/6 스냅샷(1200)으로 지수가 3/5에 쓴 값을 식별한다
    // 근거: CapitalLedgerTimeline.java:48, TradingStatsService.java:340-343, InvestmentIndexCalculator.java:66,330
    @Test
    @DisplayName("같음: 같은 날 스냅샷 둘 — 입력 순서와 무관하게 곡선·지수 모두 마지막(createdAt) 값 1200")
    void 같은_날_스냅샷_둘은_입력_순서와_무관하게_마지막_값을_쓴다() {
        UUID s = STRATEGY_ID;
        StrategyCycle a = cycle(s, "1000", null, "2026-03-01", null, "2026-03-01T00:00");
        CyclePosition older = snap(a, "2026-03-05T10:00", "1000");
        CyclePosition newer = snap(a, "2026-03-05T15:00", "1200");
        CyclePosition next = snap(a, "2026-03-06T12:00", "1200");

        Both olderFirst = run(List.of(a), List.of(older, newer, next), s);
        setUp(); // 서비스를 새로 만든다(결과 캐시 분리)
        Both newerFirst = run(List.of(a), List.of(newer, older, next), s);

        for (Both r : List.of(olderFirst, newerFirst)) {
            assertThat(r.curveOn("2026-03-05")).isEqualTo("1200");
            assertThat(r.curveOn("2026-03-06")).isEqualTo("1200");
            assertThat(ret(r, "2026-03-06")).isEqualTo("0"); // 3/5가 1200이어야 0. 1000이었다면 0.2
        }
    }

    // 4. 시작예정일 전 재설정 — A(start 3/1, end 3/8), B(start 3/8, createdAt 3/5). A는 3/8까지 스냅샷을 남기고 B는 3/5에 등록 스냅샷(5000)을 남긴다
    // 곡선: 3/5부터 B(5000)가 체인 마지막이라 A는 3/5~3/8 탈락 → 3/5~3/8 모두 5000(A의 1010~1040 사라짐).
    // 지수: B.startDate(3/8) 전엔 A를 고른다 → 3/5~3/7은 A값(일수익률 +1%대), 3/8부터 B. 3/5~3/7 기준 사이클이 갈린다
    // 근거: TradingStatsService.java:345-359,378, InvestmentIndexCalculator.java:267-272(startDate 도달 전 continue)
    @Test
    @DisplayName("갈림: 시작예정일 전 재설정 — 곡선은 B 등록 스냅샷 날(3/5)부터 B(5000), 지수는 B.startDate(3/8)까지 A를 쓴다")
    void 시작예정일_전_재설정은_곡선이_먼저_새_사이클로_넘어간다() {
        UUID s = STRATEGY_ID;
        StrategyCycle a = cycle(s, "1000", "1100", "2026-03-01", "2026-03-08", "2026-03-01T00:00");
        StrategyCycle b = cycle(s, "5000", null, "2026-03-08", null, "2026-03-05T00:00");
        Both r = run(List.of(a, b), List.of(
                snap(a, "2026-03-04T12:00", "1000"), snap(a, "2026-03-05T10:00", "1010"), snap(a, "2026-03-06T12:00", "1020"),
                snap(a, "2026-03-07T12:00", "1030"), snap(a, "2026-03-08T09:00", "1040"),
                snap(b, "2026-03-05T12:00", "5000"), snap(b, "2026-03-08T12:00", "5000"), snap(b, "2026-03-09T12:00", "5100")), s);

        assertThat(r.curveOn("2026-03-04")).isEqualTo("1000"); // A
        for (String d : List.of("2026-03-05", "2026-03-06", "2026-03-07", "2026-03-08")) {
            assertThat(r.curveOn(d)).isEqualTo("5000"); // B — A의 계속되는 스냅샷은 무시
        }
        assertThat(r.curveOn("2026-03-09")).isEqualTo("5100");
        // 지수: 3/5~3/7은 A(1010/1000, 1020/1010, 1030/1020), 3/8은 교체 흐름을 빼 A 1040/1030
        assertThat(ret(r, "2026-03-05")).isEqualTo("0.01");
        assertThat(ret(r, "2026-03-06")).isEqualTo("0.0099009901");
        assertThat(ret(r, "2026-03-07")).isEqualTo("0.0098039216");
        assertThat(ret(r, "2026-03-08")).isEqualTo("0.0097087379");
        assertThat(ret(r, "2026-03-09")).isEqualTo("0.02"); // 5100/5000-1: B
    }

    // 5. 후속 없이 종료 — A(start 3/1, end 3/8)만 있는 전략 + 다른 전략 T가 3/12까지 매일 스냅샷
    // 곡선: 종료일(3/8)까지 A 포함(21100=1100+20000), 3/9부터 A 탈락(20000). 지수: 종료일엔 A를 활성에서 제외(date<endDate가 아님)하지만
    // 종료 흐름(-1100)을 3/8에 넣어 일수익률은 21100/21050-1로 곡선의 3/8 증가율과 같고 3/9 이후 0으로 이월한다
    // → 종료일의 값 구성(곡선 A 포함 / 지수 A 제외+종료 흐름)이 다르고 일수익률 결과는 같다. 근거: TradingStatsService.java:370-377, InvestmentIndexCalculator.java:283-288
    @Test
    @DisplayName("갈림: 후속 없이 종료 — 곡선은 종료일(3/8)까지 A를 포함하고 지수는 종료일에 A를 제외하되 종료 흐름으로 수익률을 이어 간다")
    void 후속_없이_종료한_사이클은_종료일의_값_구성이_다르다() {
        UUID s = STRATEGY_ID;
        StrategyCycle a = cycle(s, "1000", "1100", "2026-03-01", "2026-03-08", "2026-03-01T00:00");
        StrategyCycle t = cycle(OTHER_STRATEGY_ID, "20000", null, "2026-03-01", null, "2026-03-01T00:00");
        List<CyclePosition> ps = new ArrayList<>(List.of(
                snap(a, "2026-03-05T12:00", "1000"), snap(a, "2026-03-07T12:00", "1050"), snap(a, "2026-03-08T12:00", "1100")));
        for (int d = 5; d <= 12; d++) ps.add(snap(t, "2026-03-%02dT12:00".formatted(d), "20000"));
        Both r = run(List.of(a, t), ps, s, OTHER_STRATEGY_ID);

        assertThat(r.curveOn("2026-03-07")).isEqualTo("21050");
        assertThat(r.curveOn("2026-03-08")).isEqualTo("21100"); // 종료일엔 A 포함
        assertThat(r.curveOn("2026-03-09")).isEqualTo("20000"); // 다음 날부터 탈락
        assertThat(r.curveOn("2026-03-12")).isEqualTo("20000");
        assertThat(ret(r, "2026-03-07")).isEqualTo("0.0023809524"); // 21050/21000-1
        assertThat(ret(r, "2026-03-08")).isEqualTo("0.0023752969"); // 21100/21050-1: 종료 흐름 -1100이 A 제외분을 되돌림
        assertThat(ret(r, "2026-03-09")).isEqualTo("0");
        assertThat(ret(r, "2026-03-12")).isEqualTo("0");
    }

    // 6. createdAt이 같은 두 사이클 — 두 순서 비교 모두 id(UUIDv7)로 마지막을 가려 같은 사이클(B, 큰 id)을 고른다
    // createdAt null은 도메인상 저장 전(DB DEFAULT)에만 가능하고 영속 조회 결과엔 없다. 지수 compareCycles는 nullsFirst라 허용하지만
    // 곡선 NEWER_CYCLE은 null이면 NPE라 입력으로 구성하지 않는다. 근거: TradingStatsService.java:72-73, InvestmentIndexCalculator.java:320-328
    @Test
    @DisplayName("같음: createdAt이 같은 두 사이클 — 곡선·지수 모두 id가 큰 B를 새 사이클로 본다")
    void createdAt이_같은_사이클은_둘_다_id로_가려_같다() {
        UUID s = STRATEGY_ID;
        UUID lo = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID hi = UUID.fromString("00000000-0000-7000-8000-000000000002");
        Instant same = kst("2026-03-01T00:00");
        StrategyCycle a = new StrategyCycle(lo, s, null, new BigDecimal("1000"), new BigDecimal("1100"),
                LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-10"), same, null);
        StrategyCycle b = new StrategyCycle(hi, s, null, new BigDecimal("5000"), null,
                LocalDate.parse("2026-03-10"), null, same, null);
        Both r = run(List.of(b, a), List.of( // 사이클 목록 순서도 뒤집어 입력 순서 의존이 없음을 같이 본다
                snap(a, "2026-03-09T12:00", "1050"), snap(b, "2026-03-10T12:00", "5000"), snap(b, "2026-03-11T12:00", "5100")), s);

        assertThat(r.curveOn("2026-03-10")).isEqualTo("5000"); // B
        assertThat(ret(r, "2026-03-10")).isEqualTo("0"); // 교체 흐름(5000-1050) 상쇄 — B 선택
        assertThat(ret(r, "2026-03-11")).isEqualTo("0.02"); // 5100/5000-1: B
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    static StrategyCycle cycle(UUID strategyId, String open, String end, String startDate, String endDate, String createdAtKst) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, null, new BigDecimal(open),
                end == null ? null : new BigDecimal(end), LocalDate.parse(startDate),
                endDate == null ? null : LocalDate.parse(endDate), kst(createdAtKst), null);
    }

    // holdings=0 스냅샷 — 종가가 없어도 완전평가(자산 = 예수금)라 곡선·지수 평가액이 같다
    static CyclePosition snap(StrategyCycle cycle, String atKst, String deposit) {
        return new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal(deposit),
                null, null, BigDecimal.ZERO, kst(atKst), null);
    }

    static Instant kst(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(TimeZones.KST).toInstant();
    }
}
