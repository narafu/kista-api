package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.UserPushNotificationRequestedEvent;
import com.kista.shared.platform.telegram.TelegramHttpClient;
import com.kista.tradingcore.account.application.event.ApiKeyExpiringEvent;
import com.kista.tradingcore.notify.application.port.output.TradingNotifyPort;
import com.kista.tradingcore.trading.application.event.DcaRoundSkippedEvent;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.domain.model.TradingReport;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 특성화(characterization) 테스트 — 문구 조립기 리팩토링 전 "지금 코드가 내는 최종 문자열"을 고정한다.
// 문구가 어색해 보여도 고치지 않는다. 리팩토링 뒤 의도한 차이(금액 표기 통일·라벨 한글화)만 기대값에서 바뀌어야 한다.
class NotificationMessageSnapshotTest {

    static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String NICK = "내 <계좌> & 1"; // HTML 이스케이프가 드러나는 닉네임
    static final TradingUserProfile PROFILE = new TradingUserProfile(USER_ID, Map.of(), "tok", "chat");

    final TelegramHttpClient telegram = mock(TelegramHttpClient.class);
    final RedisPushNotificationPublisher push = mock(RedisPushNotificationPublisher.class);
    final TradingUserNotificationAdapter adapter = new TradingUserNotificationAdapter(telegram, push);

    // 텔레그램 HTML 본문(발송 직전)과 FCM 제목·본문을 함께 단언한다
    private void assertSent(String telegramHtml, String fcmTitle, String fcmBody) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendMessage(eq("chat"), text.capture(), eq("tok"));
        assertThat(text.getValue()).isEqualTo(telegramHtml);
        ArgumentCaptor<UserPushNotificationRequestedEvent> event = ArgumentCaptor.forClass(UserPushNotificationRequestedEvent.class);
        verify(push).publish(event.capture());
        assertThat(event.getValue()).isEqualTo(new UserPushNotificationRequestedEvent(USER_ID, fcmTitle, fcmBody));
    }

    private void send(Consumer<TradingUserNotificationAdapter> call) {
        call.accept(adapter);
    }

    @Test
    @DisplayName("notifyTradingReport — 매매 결산")
    void notifyTradingReport() {
        send(a -> a.notifyTradingReport(PROFILE, NICK, new TradingReport(LocalDate.of(2026, 11, 14),
                StrategyType.INFINITE, StrategyTicker.SOXL, new BigDecimal("1234.5"), new BigDecimal("0.1234"))));
        assertSent("""
                <b>매매 결산[2026-11-14]</b> — 내 &lt;계좌&gt; &amp; 1
                [INFINITE] SOXL 매수: $1234.50 | 매도: $0.12""", "매매 결산", """
                매매 결산[2026-11-14] — 내 <계좌> & 1
                [INFINITE] SOXL 매수: $1234.50 | 매도: $0.12""");
    }

    @Test
    @DisplayName("notifyCycleCompleted — 사이클 종료 (CycleCompletedEvent·CycleEndedEvent 공용)")
    void notifyCycleCompleted() {
        send(a -> a.notifyCycleCompleted(PROFILE, NICK, StrategyType.VR, StrategyTicker.TQQQ, StrategyCycleSeedType.MAINTAIN));
        assertSent("""
                🔄 <b>사이클 종료</b> — 내 &lt;계좌&gt; &amp; 1
                [VR] TQQQ 사이클이 완료되었습니다.
                다음 사이클 정책: MAINTAIN""", "사이클 종료", """
                🔄 사이클 종료 — 내 <계좌> & 1
                [VR] TQQQ 사이클이 완료되었습니다.
                다음 사이클 정책: MAINTAIN""");
    }

    @Test
    @DisplayName("notifyNewCycleStarted — 새 사이클 시작 (NewCycleStartedEvent)")
    void notifyNewCycleStarted() {
        send(a -> a.notifyNewCycleStarted(PROFILE, NICK, StrategyType.INFINITE, StrategyTicker.SOXL, new BigDecimal("1234.5")));
        assertSent("""
                🚀 <b>새 사이클 시작</b> — 내 &lt;계좌&gt; &amp; 1
                [INFINITE] SOXL 사이클이 시작되었습니다.
                투자금: $1234.50""", "새 사이클 시작", """
                🚀 새 사이클 시작 — 내 <계좌> & 1
                [INFINITE] SOXL 사이클이 시작되었습니다.
                투자금: $1234.50""");
    }

    @Test
    @DisplayName("notifyStrategyAutoPaused — 소액 시드 자동 일시정지 (StrategyAutoPausedEvent)")
    void notifyStrategyAutoPaused() {
        send(a -> a.notifyStrategyAutoPaused(PROFILE, NICK, StrategyType.INFINITE, StrategyTicker.SOXL,
                new BigDecimal("12.5"), new BigDecimal("30.126")));
        assertSent("""
                ⏸️ <b>전략 자동 일시정지</b> — 내 &lt;계좌&gt; &amp; 1
                [INFINITE] SOXL 다음 사이클 투자금 $12.50가 1주 가격 $30.13보다 작아 새 사이클을 열지 않았습니다.
                재개해도 매수할 수 없으니 전략을 삭제하고 충분한 투자금으로 다시 등록해 주세요.""", "전략 자동 일시정지", """
                ⏸️ 전략 자동 일시정지 — 내 <계좌> & 1
                [INFINITE] SOXL 다음 사이클 투자금 $12.50가 1주 가격 $30.13보다 작아 새 사이클을 열지 않았습니다.
                재개해도 매수할 수 없으니 전략을 삭제하고 충분한 투자금으로 다시 등록해 주세요.""");
    }

    @Test
    @DisplayName("notifyCycleRotationFailed — 사이클 전환 오류 자동 일시정지 (CycleRotationFailedEvent)")
    void notifyCycleRotationFailed() {
        send(a -> a.notifyCycleRotationFailed(PROFILE, NICK, StrategyType.INFINITE, StrategyTicker.SOXL));
        // 소스 텍스트 블록에 줄 연결 백슬래시가 없어 둘째·셋째 줄 앞에 공백 16칸이 그대로 나간다(현재 동작 그대로 고정)
        String indent = " ".repeat(16);
        assertSent("⏸️ <b>전략 자동 일시정지</b> — 내 &lt;계좌&gt; &amp; 1\n" + indent
                + "[INFINITE] SOXL 사이클은 정상 종료됐지만 다음 사이클을 여는 중 오류가 나 전략을 일시정지했습니다.\n" + indent
                + "재개하면 새 사이클 시작을 다시 시도합니다(남은 원장이 1주 가격보다 작으면 재개되지 않습니다).",
                "전략 자동 일시정지",
                "⏸️ 전략 자동 일시정지 — 내 <계좌> & 1\n" + indent
                + "[INFINITE] SOXL 사이클은 정상 종료됐지만 다음 사이클을 여는 중 오류가 나 전략을 일시정지했습니다.\n" + indent
                + "재개하면 새 사이클 시작을 다시 시도합니다(남은 원장이 1주 가격보다 작으면 재개되지 않습니다).");
    }

    @Test
    @DisplayName("notifyInsufficientBalance — SELL 거절(판매 가능 수량 부족), 일부 접수")
    void notifyInsufficientBalanceSell() {
        send(a -> a.notifyInsufficientBalance(PROFILE, NICK, StrategyType.INFINITE, StrategyTicker.SOXL, 2, true, OrderDirection.SELL));
        assertSent("⚠️ <b>증권사 판매 가능 수량 부족</b> — 내 &lt;계좌&gt; &amp; 1 SOXL INFINITE 매도 2건 실패 (일부 접수). 보유 수량 확인 바랍니다.",
                "보유 수량 부족",
                "⚠️ 증권사 판매 가능 수량 부족 — 내 <계좌> & 1 SOXL INFINITE 매도 2건 실패 (일부 접수). 보유 수량 확인 바랍니다.");
    }

    @Test
    @DisplayName("notifyInsufficientBalance — BUY 증권사 거절(주문 가능 금액 부족), 전부 실패")
    void notifyInsufficientBalanceBuyBroker() {
        send(a -> a.notifyInsufficientBalance(PROFILE, NICK, StrategyType.INFINITE, StrategyTicker.SOXL, 3, false, OrderDirection.BUY));
        assertSent("⚠️ <b>증권사 주문 가능 금액 부족</b> — 내 &lt;계좌&gt; &amp; 1 SOXL INFINITE 매수 3건 실패. 예수금 확인 바랍니다.",
                "예수금 부족",
                "⚠️ 증권사 주문 가능 금액 부족 — 내 <계좌> & 1 SOXL INFINITE 매수 3건 실패. 예수금 확인 바랍니다.");
    }

    @Test
    @DisplayName("notifyInsufficientBalance — BUY 증권사 거절, 일부 접수")
    void notifyInsufficientBalanceBuyBrokerPartial() {
        send(a -> a.notifyInsufficientBalance(PROFILE, NICK, StrategyType.INFINITE, StrategyTicker.SOXL, 1, true, OrderDirection.BUY));
        assertSent("⚠️ <b>증권사 주문 가능 금액 부족</b> — 내 &lt;계좌&gt; &amp; 1 SOXL INFINITE 매수 1건 실패 (일부 접수). 예수금 확인 바랍니다.",
                "예수금 부족",
                "⚠️ 증권사 주문 가능 금액 부족 — 내 <계좌> & 1 SOXL INFINITE 매수 1건 실패 (일부 접수). 예수금 확인 바랍니다.");
    }

    @Test
    @DisplayName("notifyInsufficientBalance — 전략 원장 상한 거절(failedCount=0)")
    void notifyInsufficientBalanceLedgerCap() {
        send(a -> a.notifyInsufficientBalance(PROFILE, NICK, StrategyType.INFINITE, StrategyTicker.SOXL, 0, false, OrderDirection.BUY));
        assertSent("⚠️ <b>전략 원장 예수금 부족</b> — 내 &lt;계좌&gt; &amp; 1 SOXL INFINITE 오늘 매수 주문을 내지 않았습니다.",
                "예수금 부족",
                "⚠️ 전략 원장 예수금 부족 — 내 <계좌> & 1 SOXL INFINITE 오늘 매수 주문을 내지 않았습니다.");
    }

    @Test
    @DisplayName("TradingErrorEvent(userId 있음) → TradingAlertNotifier → notifyError — 매매 오류 발생 사용자 템플릿")
    void tradingErrorEventToUserNotifyError() {
        TradingUserProfilePort profilePort = mock(TradingUserProfilePort.class);
        when(profilePort.findByUserId(USER_ID)).thenReturn(Optional.of(PROFILE));
        TradingAlertNotifier notifier = new TradingAlertNotifier(mock(TradingNotifyPort.class), adapter, profilePort);

        notifier.onTradingError(new TradingErrorEvent(USER_ID, "주문 실패 <코드 & 1>"));

        assertSent("⚠️ <b>매매 오류 발생</b>\n주문 실패 &lt;코드 &amp; 1&gt;", "매매 오류 발생",
                "⚠️ 매매 오류 발생\n주문 실패 <코드 & 1>");
    }

    @Test
    @DisplayName("notifyBatchInterrupted — 매매 일시 중단 (BatchInterruptedEvent)")
    void notifyBatchInterrupted() {
        send(a -> a.notifyBatchInterrupted(PROFILE, NICK));
        assertSent("""
                ⏸️ <b>매매 일시 중단</b> — 내 &lt;계좌&gt; &amp; 1
                시스템 재배포로 오늘 매매가 일시 중단됐습니다. 잠시 후 자동 재시도되거나, 필요 시 관리자에게 문의해주세요.""", "매매 일시 중단", """
                ⏸️ 매매 일시 중단 — 내 <계좌> & 1
                시스템 재배포로 오늘 매매가 일시 중단됐습니다. 잠시 후 자동 재시도되거나, 필요 시 관리자에게 문의해주세요.""");
    }

    @Test
    @DisplayName("notifyMarketOpen — 장 개시 (MarketOpenEvent)")
    void notifyMarketOpen() {
        send(a -> a.notifyMarketOpen(PROFILE));
        assertSent("🟢 미국 장이 열렸습니다.", "장 개시", "🟢 미국 장이 열렸습니다.");
    }

    @Test
    @DisplayName("notifyMarketClose — 장 마감 (MarketCloseEvent)")
    void notifyMarketClose() {
        send(a -> a.notifyMarketClose(PROFILE));
        assertSent("🔴 미국 장이 마감되었습니다.", "장 마감", "🔴 미국 장이 마감되었습니다.");
    }

    @Test
    @DisplayName("notifyDcaRoundSkipped — DCA 미주문, 방향·규칙·상세 모두 있음")
    void notifyDcaRoundSkippedFull() {
        send(a -> a.notifyDcaRoundSkipped(PROFILE, new DcaRoundSkippedEvent(USER_ID, NICK, StrategyTicker.UPRO, 2,
                OrderDirection.BUY, LocalDate.of(2026, 11, 14), DcaSkipReason.INSUFFICIENT_BALANCE, "주문가능금액 < 1주 캡가 & 기타")));
        assertSent("""
                ⏭️ <b>DCA 미주문</b> — 내 &lt;계좌&gt; &amp; 1
                UPRO 매수 DCA 규칙 2 (2026-11-14)
                사유: 예수금 부족 — 주문가능금액 &lt; 1주 캡가 &amp; 기타""", "DCA 미주문", """
                ⏭️ DCA 미주문 — 내 <계좌> & 1
                UPRO 매수 DCA 규칙 2 (2026-11-14)
                사유: 예수금 부족 — 주문가능금액 < 1주 캡가 & 기타""");
    }

    @Test
    @DisplayName("notifyDcaRoundSkipped — DCA 미주문, 방향·규칙·상세 없음(삭제된 규칙)")
    void notifyDcaRoundSkippedMinimal() {
        send(a -> a.notifyDcaRoundSkipped(PROFILE, new DcaRoundSkippedEvent(USER_ID, "계좌", StrategyTicker.UPRO, null,
                null, LocalDate.of(2026, 11, 14), DcaSkipReason.NO_HOLDINGS, null)));
        assertSent("""
                ⏭️ <b>DCA 미주문</b> — 계좌
                UPRO DCA (2026-11-14)
                사유: 보유 수량 없음""", "DCA 미주문", """
                ⏭️ DCA 미주문 — 계좌
                UPRO DCA (2026-11-14)
                사유: 보유 수량 없음""");
    }

    @Test
    @DisplayName("notifyApiKeyExpiring — N일 후 만료, +1년 만료일 브로커(Toss)는 수정 안내 추가")
    void notifyApiKeyExpiringDaysLeftEstimated() {
        send(a -> a.notifyApiKeyExpiring(PROFILE, expiring(Broker.TOSS, 7)));
        assertSent("[내 &lt;계좌&gt; &amp; 1] 토스증권 API 키가 7일 후(2026-11-30) 만료됩니다. 실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다.",
                "API 키 만료",
                "[내 <계좌> & 1] 토스증권 API 키가 7일 후(2026-11-30) 만료됩니다. 실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다.");
    }

    @Test
    @DisplayName("notifyApiKeyExpiring — 오늘 만료 + +1년 만료일 브로커(TOSS)")
    void notifyApiKeyExpiringTodayEstimated() {
        send(a -> a.notifyApiKeyExpiring(PROFILE, expiring(Broker.TOSS, 0)));
        assertSent("[내 &lt;계좌&gt; &amp; 1] 토스증권 API 키가 오늘 만료됩니다. 키를 새로 발급해 교체해 주세요. 실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다.",
                "API 키 만료",
                "[내 <계좌> & 1] 토스증권 API 키가 오늘 만료됩니다. 키를 새로 발급해 교체해 주세요. 실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다.");
    }

    private static ApiKeyExpiringEvent expiring(Broker broker, int daysLeft) {
        return new ApiKeyExpiringEvent(USER_ID, UUID.randomUUID(), NICK, broker, LocalDate.of(2026, 11, 30), daysLeft);
    }
}
