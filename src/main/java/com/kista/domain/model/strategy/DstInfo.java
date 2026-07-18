package com.kista.domain.model.strategy;

import com.kista.common.TimeZones;

import java.time.*;

public record DstInfo(
        boolean isDst,      // 미국 뉴욕 기준 일광절약시간(DST) 적용 여부
        Instant orderAt,    // 주문 시각 (KST): DST=04:30, 비DST=05:30
        Instant postClose,  // 시장 마감 후 체결 확인 시각 (KST): DST=05:10, 비DST=06:10
        Instant marketOpen  // 미 정규장 개장 시각 (KST): DST=22:30, 비DST=23:30
) {

    private static final ZoneId NY  = ZoneId.of("America/New_York");
    private static final ZoneId KST = TimeZones.KST;

    // --- KST 시각표 SSOT: DST 여부에 따른 각 이벤트 시각 ---

    // 마감 배치 주문 시각
    private static LocalTime orderTime(boolean isDst)      { return isDst ? LocalTime.of(4, 30)  : LocalTime.of(5, 30); }
    // 장마감 후 체결 확인 시각
    private static LocalTime postCloseTime(boolean isDst)  { return isDst ? LocalTime.of(5, 10)  : LocalTime.of(6, 10); }
    // 미 정규장 개장 시각 (NY 09:30)
    private static LocalTime marketOpenTime(boolean isDst) { return isDst ? LocalTime.of(22, 30) : LocalTime.of(23, 30); }
    // 장마감 시각 — 수동 실행 BLOCKED 구간 시작
    private static LocalTime marketCloseTime(boolean isDst)    { return isDst ? LocalTime.of(5, 0)  : LocalTime.of(6, 0); }
    // 프리마켓 시작 시각 — 수동 실행 BLOCKED 구간 끝
    private static LocalTime premarketStartTime(boolean isDst) { return isDst ? LocalTime.of(17, 0) : LocalTime.of(18, 0); }

    private static boolean resolveDst(Instant instant) {
        return NY.getRules().isDaylightSavings(instant);
    }

    private static Instant atKst(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(KST).toInstant();
    }

    // 수동 실행 시 주문 가능 시간대
    public enum MarketSession {
        DIRECT,  // 프리마켓+정규장: 주문 가능 (DST: 17:00~05:00, 비DST: 18:00~06:00)
        BLOCKED  // 장마감 후~프리마켓 전: 주문 불가 (DST: 05:00~17:00, 비DST: 06:00~18:00)
    }

    // 현재 KST 기준 주문 가능 시간대 판단 — 주말은 요일 무관 BLOCKED
    public MarketSession currentSession() {
        return sessionAt(LocalDate.now(KST).getDayOfWeek(), LocalTime.now(KST));
    }

    // 시각 주입식 판단 — 테스트 및 currentSession 공용
    MarketSession sessionAt(DayOfWeek day, LocalTime time) {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return MarketSession.BLOCKED;
        // blocked: [marketClose, premarketStart)
        if (!time.isBefore(marketCloseTime(isDst)) && time.isBefore(premarketStartTime(isDst))) {
            return MarketSession.BLOCKED;
        }
        return MarketSession.DIRECT;
    }

    // BLOCKED 시간대 범위 설명 (KST) — 수동 실행 거부 메시지용
    public String blockedRangeDescription() {
        return marketCloseTime(isDst) + "~" + premarketStartTime(isDst);
    }

    // 정규장(marketOpen~marketClose) 진행 중 여부 — DIRECT는 프리마켓+정규장을 모두 포함하므로
    // "장중이라 당일 봉이 미확정일 수 있는" 구간은 그중 정규장 진행 중일 때뿐 (프리마켓은 이미 확정된 직전 종가 상태)
    public boolean isRegularSessionActive() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        return isRegularSessionActiveAt(now.getDayOfWeek(), now.toLocalTime());
    }

    // 시각 주입식 판단 — 테스트 및 isRegularSessionActive 공용
    boolean isRegularSessionActiveAt(DayOfWeek day, LocalTime time) {
        if (sessionAt(day, time) == MarketSession.BLOCKED) return false;
        // marketOpen~자정~marketClose 래핑 구간만 정규장 진행 중 (그 외 DIRECT 구간은 프리마켓)
        return !time.isBefore(marketOpenTime(isDst)) || time.isBefore(marketCloseTime(isDst));
    }

    // 가장 최근에 실제로 개장한 정규장의 개장 시각 — marketOpen 필드는 "오늘 날짜" 고정이라
    // 자정~개장 전(00:00~marketOpen) 사이에 호출하면 실제로 진행 중인 세션(전날 저녁 개장)이 아닌
    // 미래 시각(오늘 저녁 개장)을 가리키는 문제가 있음. nextTradeDate()의 날짜 롤백과 동일한 패턴.
    public Instant lastSessionOpenInstant() {
        return lastSessionOpenInstantAt(ZonedDateTime.now(KST));
    }

    // 시각 주입식 판단 — 테스트 및 lastSessionOpenInstant 공용
    Instant lastSessionOpenInstantAt(ZonedDateTime nowKst) {
        LocalDate date = nowKst.toLocalTime().isBefore(marketOpenTime(isDst))
                ? nowKst.toLocalDate().minusDays(1)
                : nowKst.toLocalDate();
        return atKst(date, marketOpenTime(isDst));
    }

    public Duration waitUntilOrderTime() {
        return Duration.between(Instant.now(), orderAt);
    }

    public Duration waitUntilPostClose() {
        return Duration.between(Instant.now(), postClose);
    }

    public Duration waitUntilMarketOpen() {
        return Duration.between(Instant.now(), marketOpen);
    }

    public static DstInfo calculate() {
        return calculate(ZonedDateTime.now(KST));
    }

    static DstInfo calculate(ZonedDateTime nowKst) {
        boolean isDst = resolveDst(nowKst.toInstant());
        LocalDate date = nowKst.toLocalDate();
        return new DstInfo(isDst,
                atKst(date, orderTime(isDst)),
                atKst(date, postCloseTime(isDst)),
                atKst(date, marketOpenTime(isDst)));
    }

    // 매매일 경계 기준 시각 — 마감 배치 cron 발화(TradingCloseScheduler 04:30 KST)와 동일 임계값.
    // 04:30 이후면 당일 배치는 이미 계획 완료 → 다음 세션(익일)이 preview/수동 실행 대상
    private static final LocalTime SCHEDULER_RUN_TIME = LocalTime.of(4, 30);

    // preview/수동 실행에서 "오늘 매매 기준 날짜" 산출 — 스케쥴러와 동일 로직 SSOT
    public static LocalDate nextTradeDate() {
        return nextTradeDateAt(LocalDate.now(KST), LocalTime.now(KST));
    }

    // 시각 주입식 판단 — 테스트 및 nextTradeDate 공용
    static LocalDate nextTradeDateAt(LocalDate today, LocalTime now) {
        return now.isBefore(SCHEDULER_RUN_TIME) ? today : today.plusDays(1);
    }

    // 개장 스케쥴러 수동 트리거용: marketOpen을 과거로 설정해 waitUntilMarketOpen() 스킵
    public static DstInfo immediateOpen() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean isDst = resolveDst(now.toInstant());
        LocalDate date = now.toLocalDate();
        return new DstInfo(isDst,
                atKst(date, orderTime(isDst)),
                atKst(date, postCloseTime(isDst)),
                Instant.now().minusMillis(1));
    }

    // 마감 스케쥴러 수동 트리거용: orderAt·postClose를 과거로 설정해 모든 대기 스킵
    public static DstInfo immediateClose() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean isDst = resolveDst(now.toInstant());
        Instant past = Instant.now().minusMillis(1);
        return new DstInfo(isDst, past, past, atKst(now.toLocalDate(), marketOpenTime(isDst)));
    }

    // 재주문 시점 가용성 (현재 시각 기준) — 주문시점 셀렉터 disable 판단 SSOT
    public record ReorderTimingAvailability(
            boolean atOpen,     // AT_OPEN 접수 가능 — 개장 전에만
            boolean atClose,    // AT_CLOSE 접수 가능 — 마감 전에만
            boolean immediate   // 즉시 접수 가능 — 정규장 중에만 (개장 후 && 마감 전)
    ) {}

    public ReorderTimingAvailability reorderTimingAvailability() {
        return reorderTimingAvailabilityAt(Instant.now());
    }

    // 시각 직접 지정 (테스트 주입용)
    // BLOCKED 시간대(장마감~프리마켓 전, 주말): 전부 불가
    // DIRECT 시간대: beforeOpen이면 AT_OPEN/AT_CLOSE 가능, afterOpen이면 AT_CLOSE/IMMEDIATE 가능
    public ReorderTimingAvailability reorderTimingAvailabilityAt(Instant now) {
        ZonedDateTime nowKst = now.atZone(KST);
        if (sessionAt(nowKst.getDayOfWeek(), nowKst.toLocalTime()) == MarketSession.BLOCKED) {
            return new ReorderTimingAvailability(false, false, false);
        }
        boolean beforeOpen = now.isBefore(marketOpen);
        return new ReorderTimingAvailability(beforeOpen, true, !beforeOpen);
    }

    // 수동 실행용: orderAt을 과거로 설정해 waitUntilOrderTime() 스킵, postClose/marketOpen은 정상 계산
    public static DstInfo immediate() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean isDst = resolveDst(now.toInstant());
        // postTime이 이미 지났으면 내일 날짜 사용 (같은 날 05:10 KST 이후 호출 시 과거 Instant 방지)
        LocalDate targetDate = now.toLocalTime().isBefore(postCloseTime(isDst))
                ? now.toLocalDate()
                : now.toLocalDate().plusDays(1);
        return new DstInfo(isDst, Instant.now().minusMillis(1),
                atKst(targetDate, postCloseTime(isDst)),
                atKst(targetDate, marketOpenTime(isDst)));
    }
}
