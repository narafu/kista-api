package com.kista.adapter.in.telegram;

import com.kista.common.TimeZones;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.PortfolioUseCase;
import com.kista.domain.port.in.UserUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
class TelegramBotService {

    @Value("${telegram.chat-id:}")
    private final String adminChatId;  // 명령을 허용하는 관리자 텔레그램 채팅 ID
    private final TelegramApiClient apiClient;
    private final PortfolioUseCase portfolioUseCase;
    private final UserUseCase userUseCase;
    void handle(TelegramUpdate update) {
        // 인라인 버튼 클릭(callback_query) 처리 — message가 null이므로 별도 분기 필수
        if (update.callbackQuery() != null) {
            handleCallbackQuery(update.callbackQuery());
            return;
        }
        if (update.message() == null || update.message().text() == null) return;
        long chatId = update.message().chat().id();
        String text = update.message().text().trim();

        if (!String.valueOf(chatId).equals(adminChatId)) {
            log.warn("Unauthorized webhook from chatId={}", chatId);
            return;
        }

        String reply = handleIdle(text);
        if (reply != null) {
            apiClient.sendMessage(String.valueOf(chatId), reply);
        }
    }

    private void handleCallbackQuery(TelegramUpdate.CallbackQuery callbackQuery) {
        // 버튼 로딩 스피너 즉시 해제
        apiClient.answerCallbackQuery(callbackQuery.id());

        long chatId = callbackQuery.message().chat().id();
        if (!String.valueOf(chatId).equals(adminChatId)) {
            log.warn("Unauthorized callback_query from chatId={}", chatId);
            return;
        }

        String data = callbackQuery.data();
        String[] parts = data != null ? data.split(":") : new String[0];
        if (parts.length != 2) {
            log.warn("알 수 없는 callback_data: {}", data);
            return;
        }

        String action = parts[0];
        UUID targetUserId;
        try {
            targetUserId = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            log.warn("callback_data UUID 파싱 실패: {}", data);
            return;
        }

        String reply = switch (action) {
            case "approve" -> {
                userUseCase.approve(targetUserId);
                log.info("텔레그램 관리자 승인: targetUserId={}", targetUserId);
                yield "✅ 승인 완료: " + targetUserId;
            }
            case "reject" -> {
                userUseCase.reject(targetUserId);
                log.info("텔레그램 관리자 거절: targetUserId={}", targetUserId);
                yield "❌ 거절 완료: " + targetUserId;
            }
            default -> {
                log.warn("알 수 없는 callback action: {}", action);
                yield null;
            }
        };

        if (reply != null) {
            apiClient.sendMessage(String.valueOf(chatId), reply);
        }
    }

    private String handleIdle(String text) {
        String cmd = text.split("\\s+")[0].toLowerCase();
        return switch (cmd) {
            case "/start", "/help" -> """
                    사용 가능한 명령어:
                    /status — 최신 포트폴리오 현황
                    /history [days] — 거래 내역 (기본 7일)""";
            case "/status" -> buildStatusMessage();
            case "/history" -> buildHistoryMessage(parseHistoryDays(text));
            // V2: 수동 실행은 스케줄러(화~토)가 자동 처리
            case "/run" -> "V2에서는 스케줄러(화~토)가 자동 실행합니다. 계좌 전략은 앱에서 설정하세요.";
            default -> "알 수 없는 명령어입니다. /help 를 입력하세요.";
        };
    }

    private String buildStatusMessage() {
        // adminChatId로 사용자 UUID 조회 — 미설정이면 데이터 없음 메시지
        return userUseCase.findUserIdByTelegramChatId(adminChatId)
                .map(userId -> {
                    try {
                        CyclePositionHistoryEntry s = portfolioUseCase.getCurrent(userId);
                        // closingPrice가 null이면 평가액 0으로 처리
                        double marketValue = s.closingPrice() != null
                                ? s.closingPrice().doubleValue() * s.holdings() : 0.0;
                        double totalAsset = marketValue + (s.usdDeposit() != null ? s.usdDeposit().doubleValue() : 0.0);
                        double avgPrice = s.avgPrice() != null ? s.avgPrice().doubleValue() : 0.0;
                        return String.format(
                                "<b>포트폴리오 현황</b>%n보유: %d주 @ $%.4f%n평가액: $%.2f%n예수금: $%.2f%n총자산: $%.2f",
                                s.holdings(), avgPrice, marketValue,
                                s.usdDeposit() != null ? s.usdDeposit().doubleValue() : 0.0, totalAsset);
                    } catch (NoSuchElementException e) {
                        return "포트폴리오 데이터가 없습니다.";
                    }
                })
                .orElse("텔레그램 Chat ID가 계정과 연결되지 않았습니다.");
    }

    private String buildHistoryMessage(int days) {
        LocalDate to = LocalDate.now(TimeZones.KST);
        LocalDate from = to.minusDays(days);
        // adminChatId로 사용자 UUID 조회 — 미설정이면 데이터 없음 메시지
        return userUseCase.findUserIdByTelegramChatId(adminChatId)
                .map(userId -> {
                    List<Order> list = portfolioUseCase.getHistory(userId, from, to, Ticker.SOXL);
                    if (list.isEmpty()) return "최근 " + days + "일 거래 내역이 없습니다.";
                    StringBuilder sb = new StringBuilder("<b>최근 " + days + "일 거래 내역</b>\n");
                    list.forEach(h -> sb.append(String.format("%s %s %s %d주 $%.4f%n",
                            h.tradeDate(), h.direction(), h.orderType(), h.quantity(), h.price())));
                    return sb.toString().trim();
                })
                .orElse("텔레그램 Chat ID가 계정과 연결되지 않았습니다.");
    }

    private int parseHistoryDays(String text) {
        String[] parts = text.split("\\s+");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return 7;
    }
}
