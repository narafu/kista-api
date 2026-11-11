package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.MarketSession;
import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.time.KstTradeDates;

import java.time.*;
import java.util.List;
import java.util.stream.Stream;

public record DstInfo(
        boolean isDst,      // 미국 뉴욕 기준 일광절약시간(DST) 적용 여부
        Instant orderAt,    // 주문 시각 (KST): DST=04:30, 비DST=05:30
        Instant postClose,  // 시장 마감 후 체결 확인 시각 (KST): DST=05:10, 비DST=06:10
        Instant marketOpen  // 미 정규장 개장 시각 (KST): DST=22:30, 비DST=23:30
) {

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
        return TimeZones.NY.getRules().isDaylightSavings(instant);
    }

    private static Instant atKst(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(KST).toInstant();
    }

    // 주어진 KST 시각 기준 주문 가능 시간대 판단
    public MarketSession currentSessionAt(ZonedDateTime nowKst) {
        return sessionAt(nowKst.getDayOfWeek(), nowKst.toLocalTime());
    }

    // 시각 주입식 판단 — currentSessionAt 공용
    MarketSession sessionAt(DayOfWeek day, LocalTime time) {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return MarketSession.BLOCKED;
        // blocked: [marketClose, premarketStart)
        if (!time.isBefore(marketCloseTime(isDst)) && time.isBefore(premarketStartTime(isDst))) {
            return MarketSession.BLOCKED;
        }
        return MarketSession.DIRECT;
    }

    // 주어진 KST 시각 기준 정규장 진행 중 여부
    public boolean isRegularSessionActiveAt(ZonedDateTime nowKst) {
        return isRegularSessionActiveAt(nowKst.getDayOfWeek(), nowKst.toLocalTime());
    }

    // 시각 주입식 판단 — isRegularSessionActiveAt 공용
    boolean isRegularSessionActiveAt(DayOfWeek day, LocalTime time) {
        if (sessionAt(day, time) == MarketSession.BLOCKED) return false;
        // marketOpen~자정~marketClose 래핑 구간만 정규장 진행 중 (그 외 DIRECT 구간은 프리마켓)
        return !time.isBefore(marketOpenTime(isDst)) || time.isBefore(marketCloseTime(isDst));
    }

    // 가장 최근에 실제로 개장한 정규장의 개장 시각 — marketOpen 필드는 "오늘 날짜" 고정이라
    // 자정~개장 전 사이에 호출하면 미래 시각(오늘 저녁 개장)을 가리키므로 전날로 롤백한다 (nextTradeDate와 동일 패턴)
    public Instant lastSessionOpenInstantAt(ZonedDateTime nowKst) {
        LocalDate date = nowKst.toLocalTime().isBefore(marketOpenTime(isDst))
                ? nowKst.toLocalDate().minusDays(1)
                : nowKst.toLocalDate();
        return atKst(date, marketOpenTime(isDst));
    }

    public Duration waitUntilOrderTime(Instant now) {
        return Duration.between(now, orderAt);
    }

    public Duration waitUntilPostClose(Instant now) {
        return Duration.between(now, postClose);
    }

    public Duration waitUntilMarketOpen(Instant now) {
        return Duration.between(now, marketOpen);
    }

    // 주어진 KST 시각 기준 당일 DstInfo 산출
    public static DstInfo calculate(ZonedDateTime nowKst) {
        boolean isDst = resolveDst(nowKst.toInstant());
        LocalDate date = nowKst.toLocalDate();
        return new DstInfo(isDst,
                atKst(date, orderTime(isDst)),
                atKst(date, postCloseTime(isDst)),
                atKst(date, marketOpenTime(isDst)));
    }

    // 실제 거래 세션의 마감 30분 전 주문과 마감 10분 후 리포트 시각
    public static DstInfo forCloseSession(Instant marketOpen, Instant marketClose) {
        return new DstInfo(resolveDst(marketOpen), marketClose.minus(Duration.ofMinutes(30)),
                marketClose.plus(Duration.ofMinutes(10)), marketOpen);
    }

    // 수동 마감은 주문 대기만 생략하고 실제 세션의 리포트 시각을 유지한다
    public DstInfo withImmediateOrder(Instant now) {
        return new DstInfo(isDst, now.minusMillis(1), postClose, marketOpen);
    }

    // 주어진 KST 시각 기준 거래일 — 재기동 재개 등 시각을 이미 들고 있는 호출부용 (계산은 KstTradeDates SSOT)
    public static LocalDate nextTradeDate(ZonedDateTime nowKst) {
        return KstTradeDates.nextTradeDate(nowKst);
    }

    // 오늘(KST)과 다음 거래일 후보 — 같은 날이면 1건. 04:00~마감 리포트 사이 접수된 오늘 주문과 개장 배치가 미리 저장한 다음 거래일 주문을 함께 볼 때 쓴다
    public static List<LocalDate> tradeDateCandidates(ZonedDateTime nowKst) {
        return Stream.of(nowKst.toLocalDate(), nextTradeDate(nowKst)).distinct().toList();
    }

    // 시각 주입식 판단 — nextTradeDate 공용
    static LocalDate nextTradeDateAt(LocalDate today, LocalTime now) {
        return KstTradeDates.nextTradeDateAt(today, now);
    }

    // 개장 배치 발화 시각 — TradingOpenScheduler cron(22:00 KST 월~금)과 동일. 이 시각에 대상 전략을 확정한다(DST 무관)
    private static final LocalTime OPEN_BATCH_FIRE_TIME = LocalTime.of(22, 0);

    // 시각 주입식 판단 — 오늘 시작 전략을 그날 개장 배치(22:00 KST) 이후에 등록했는지 — 그날 밤 AT_OPEN 주문(INFINITE 지정가 매도·VR 사다리)이 자동 생성되지 않는다
    public static boolean openBatchMissedAt(LocalDate scheduledStart, ZonedDateTime nowKst) {
        DayOfWeek day = nowKst.getDayOfWeek();
        return scheduledStart.equals(nowKst.toLocalDate())
                && day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && !nowKst.toLocalTime().isBefore(OPEN_BATCH_FIRE_TIME);
    }

    // 개장 배치용: 거래일 T의 개장 시각(T-1일 저녁) — 자정 이후 재개돼도 이미 지난 개장을 기다리지 않도록 거래일 기준 산출
    public static DstInfo forOpenBatch(LocalDate tradeDate, ZonedDateTime nowKst) {
        DstInfo now = calculate(nowKst);
        return new DstInfo(now.isDst(), now.orderAt(), now.postClose(),
                atKst(tradeDate.minusDays(1), marketOpenTime(now.isDst())));
    }

    // orderAt과 같은 KST 일자의 장마감 시각 — 재개 시 마감 접수 마감 판정용
    public Instant marketCloseAt() {
        return atKst(orderAt.atZone(KST).toLocalDate(), marketCloseTime(isDst));
    }

    // 개장 스케쥴러 수동 트리거용: marketOpen을 과거로 설정해 waitUntilMarketOpen 스킵
    public static DstInfo immediateOpen(ZonedDateTime now) {
        boolean isDst = resolveDst(now.toInstant());
        LocalDate date = now.toLocalDate();
        return new DstInfo(isDst,
                atKst(date, orderTime(isDst)),
                atKst(date, postCloseTime(isDst)),
                now.toInstant().minusMillis(1));
    }

    // 마감 스케쥴러 수동 트리거용: 주문 시각 대기만 스킵하고 체결 확인은 실제 마감 후 시각까지 기다린다
    // (postClose를 과거로 두면 접수 직후 리포트가 돌아 살아 있는 LOC가 CANCELLED로 기록되고 체결 없는 포지션이 저장된다)
    public static DstInfo immediateClose(ZonedDateTime now) {
        boolean isDst = resolveDst(now.toInstant());
        LocalDate date = now.toLocalDate();
        return new DstInfo(isDst, now.toInstant().minusMillis(1),
                atKst(date, postCloseTime(isDst)), atKst(date, marketOpenTime(isDst)));
    }
}
