package com.kista.tradingcore.notify.adapter.out.gateway;

import com.kista.shared.platform.telegram.TelegramHttpClient;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.domain.model.TradingReport;
import com.kista.shared.kernel.UserPushNotificationRequestedEvent;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import com.kista.tradingcore.notify.application.port.output.TradingUserNotificationPort;
import com.kista.tradingcore.account.application.event.ApiKeyExpiringEvent;
import com.kista.tradingcore.trading.application.event.CryptoOrderFilledEvent;
import com.kista.tradingcore.trading.application.event.DcaRoundSkippedEvent;
import com.kista.shared.kernel.OrderDirection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Telegram은 직접 발송, FCM은 root에 Redis Pub/Sub으로 위임(fcm_device_tokens가 users FK라
// trading-core가 직접 조회할 수 없음). 메시지 문구는 root의 구 TelegramUserNotificationAdapter(삭제 전
// 커밋 26a1afa1) 원문을 byte-identical하게 유지 — User→TradingUserProfile 타입/접근자만 치환했다.
@Component
@RequiredArgsConstructor
class TradingUserNotificationAdapter implements TradingUserNotificationPort {

    private final TelegramHttpClient telegramHttpClient;
    private final RedisPushNotificationPublisher pushNotificationPublisher; // FCM 발송을 root에 위임하는 Redis 발행

    @Override
    public void notifyTradingReport(TradingUserProfile profile, String accountNickname, TradingReport report) {
        String text = """
                <b>매매 결산[%s]</b> — %s%n\
                [%s] %s 매수: $%.2f | 매도: $%.2f""".formatted(
                report.date(), TelegramHttpClient.escapeHtml(accountNickname),
                report.strategyType().name(), report.ticker().name(),
                report.totalBoughtUsd(), report.totalSoldUsd());
        notify(profile, "매매 결산", text);
    }

    @Override
    public void notifyCycleCompleted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                                      StrategyTicker ticker, StrategyCycleSeedType cycleSeedType) {
        String text = """
                🔄 <b>사이클 종료</b> — %s%n\
                [%s] %s 사이클이 완료되었습니다.%n\
                다음 사이클 정책: %s""".formatted(
                TelegramHttpClient.escapeHtml(accountNickname), strategyType.name(), ticker.name(), cycleSeedType.name());
        notify(profile, "사이클 종료", text);
    }

    @Override
    public void notifyNewCycleStarted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                                       StrategyTicker ticker, BigDecimal initialUsdDeposit) {
        String text = """
                🚀 <b>새 사이클 시작</b> — %s%n\
                [%s] %s 사이클이 시작되었습니다.%n\
                시드: $%.2f""".formatted(
                TelegramHttpClient.escapeHtml(accountNickname), strategyType.name(), ticker.name(), initialUsdDeposit);
        notify(profile, "새 사이클 시작", text);
    }

    @Override
    public void notifyInsufficientBalance(TradingUserProfile profile, String accountNickname, StrategyType strategyType, StrategyTicker ticker) {
        String text = """
                ⚠️ <b>예수금 부족</b> — %s%n\
                [%s] %s 장 마감 전 예수금 확인 바랍니다.""".formatted(
                TelegramHttpClient.escapeHtml(accountNickname), strategyType.name(), ticker.name());
        notify(profile, "예수금 부족", text);
    }

    @Override
    public void notifyError(TradingUserProfile profile, Exception e) {
        notify(profile, "매매 오류 발생", "⚠️ <b>매매 오류 발생</b>%n%s".formatted(TelegramHttpClient.escapeHtml(e.getMessage())));
    }

    @Override
    public void notifyBatchInterrupted(TradingUserProfile profile, String accountNickname) {
        String text = """
                ⏸️ <b>매매 일시 중단</b> — %s%n\
                시스템 재배포로 오늘 매매가 일시 중단됐습니다. 잠시 후 자동 재시도되거나, 필요 시 관리자에게 문의해주세요.""".formatted(
                TelegramHttpClient.escapeHtml(accountNickname));
        notify(profile, "매매 일시 중단", text);
    }

    @Override
    public void notifyMarketOpen(TradingUserProfile profile) {
        notify(profile, "장 개시", "🟢 미국 장이 열렸습니다.");
    }

    @Override
    public void notifyMarketClose(TradingUserProfile profile) {
        notify(profile, "장 마감", "🔴 미국 장이 마감되었습니다.");
    }

    @Override
    public void notifyDcaRoundSkipped(TradingUserProfile profile, DcaRoundSkippedEvent e) {
        String direction = e.direction() == null ? "" : e.direction().getLabel() + " ";
        String text = """
                ⏭️ <b>DCA 미주문</b> — %s%n\
                %s %sDCA (%s)%n\
                사유: %s%s""".formatted(
                TelegramHttpClient.escapeHtml(e.accountNickname()), e.ticker().name(), direction, e.tradeDate(), e.reason().getLabel(),
                e.detail() == null ? "" : " — " + TelegramHttpClient.escapeHtml(e.detail()));
        notify(profile, "DCA 미주문", text);
    }

    @Override
    public void notifyCryptoTrade(TradingUserProfile profile, CryptoOrderFilledEvent e) {
        String side = e.direction() == OrderDirection.SELL ? "매도" : "매수";
        String text = "[%s] %s %s 체결 — 수량 %s, 평균가 %s원, 금액 %s원".formatted(
                TelegramHttpClient.escapeHtml(e.accountNickname()), e.ticker().name(), side,
                e.filledQuantity().toPlainString(), krw(e.avgPrice()), krw(e.amountKrw()));
        notify(profile, "코인 체결", text);
    }

    @Override
    public void notifyApiKeyExpiring(TradingUserProfile profile, ApiKeyExpiringEvent e) {
        String nickname = TelegramHttpClient.escapeHtml(e.accountNickname());
        String text = e.daysLeft() == 0
                ? "[%s] 업비트 API 키가 오늘 만료됩니다. 키를 새로 발급해 교체해 주세요.".formatted(nickname)
                : "[%s] 업비트 API 키가 %d일 후(%s) 만료됩니다.".formatted(nickname, e.daysLeft(), e.expiresOn());
        notify(profile, "API 키 만료", text);
    }

    // 원화 표기 — 정수 반올림 + 천 단위 콤마
    private static String krw(BigDecimal v) {
        return "%,d".formatted(v.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    // 계좌 닉네임(사용자 입력)·DCA 사유 상세("주문가능금액 < 1주 캡가" 등)는 parse_mode=HTML이라 이스케이프 — 미이스케이프 '<'·'&'는 텔레그램 400으로 알림 유실
    // 텔레그램 직접 발송 + FCM은 root PushNotificationStreamConsumer(Redis Stream 구독)에 위임
    private void notify(TradingUserProfile profile, String title, String text) {
        sendIfLinked(profile, text);
        pushNotificationPublisher.publish(new UserPushNotificationRequestedEvent(profile.userId(), title, toPlainText(text)));
    }

    // FCM은 HTML을 렌더링하지 않는다 — 텔레그램용 태그를 지우고 엔티티를 되돌린다(&amp;는 마지막에 — 이중 해제 방지)
    static String toPlainText(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private void sendIfLinked(TradingUserProfile profile, String text) {
        // 봇 연결 + 알림 채널에 텔레그램 포함일 때만 — 채널 NONE·FCM 사용자는 앱 푸시만 받는다
        if (!profile.telegramEnabled()) return;
        telegramHttpClient.sendMessage(profile.chatId(), text, profile.telegramBotToken());
    }
}
