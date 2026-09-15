package com.kista.trading.notify.adapter.out.gateway;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TradingReport;
import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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
        String text = String.format(
                "<b>매매 결산[%s]</b> — %s%n"
                + "[%s] %s 매수: $%.2f | 매도: $%.2f",
                report.date(), accountNickname,
                report.strategyType().name(), report.ticker().name(),
                report.totalBoughtUsd(), report.totalSoldUsd());
        notify(profile, "매매 결산", text);
    }

    @Override
    public void notifyCycleCompleted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                                      StrategyTicker ticker, StrategyCycleSeedType cycleSeedType) {
        String text = String.format(
                "🔄 <b>사이클 종료</b> — %s%n"
                + "[%s] %s 사이클이 완료되었습니다.%n"
                + "다음 사이클 정책: %s",
                accountNickname,
                strategyType.name(), ticker.name(),
                cycleSeedType.name());
        notify(profile, "사이클 종료", text);
    }

    @Override
    public void notifyNewCycleStarted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                                       StrategyTicker ticker, BigDecimal initialUsdDeposit) {
        String text = String.format(
                "🚀 <b>새 사이클 시작</b> — %s%n"
                + "[%s] %s 사이클이 시작되었습니다.%n"
                + "시드: $%.2f",
                accountNickname,
                strategyType.name(), ticker.name(),
                initialUsdDeposit);
        notify(profile, "새 사이클 시작", text);
    }

    @Override
    public void notifyInsufficientBalance(TradingUserProfile profile, String accountNickname, StrategyType strategyType, StrategyTicker ticker) {
        String text = String.format(
                "⚠️ <b>예수금 부족</b> — %s%n"
                + "[%s] %s 장 마감 전 예수금 확인 바랍니다.",
                accountNickname, strategyType.name(), ticker.name());
        notify(profile, "예수금 부족", text);
    }

    @Override
    public void notifyError(TradingUserProfile profile, Exception e) {
        notify(profile, "매매 오류 발생", String.format("⚠️ <b>매매 오류 발생</b>%n%s", e.getMessage()));
    }

    @Override
    public void notifyBatchInterrupted(TradingUserProfile profile, String accountNickname) {
        String text = String.format(
                "⏸️ <b>매매 일시 중단</b> — %s%n"
                + "시스템 재배포로 오늘 매매가 일시 중단됐습니다. 잠시 후 자동 재시도되거나, 필요 시 관리자에게 문의해주세요.",
                accountNickname);
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

    // 텔레그램 직접 발송 + FCM은 root PushNotificationRelayListener(Redis 구독)에 위임
    private void notify(TradingUserProfile profile, String title, String text) {
        sendIfLinked(profile, text);
        pushNotificationPublisher.publish(new UserPushNotificationRequestedEvent(profile.userId(), title, text));
    }

    private void sendIfLinked(TradingUserProfile profile, String text) {
        if (profile.telegramBotToken() == null || profile.chatId() == null) return;
        telegramHttpClient.sendMessage(profile.chatId(), text, profile.telegramBotToken());
    }
}
