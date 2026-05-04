package com.kista.adapter.in.telegram;

import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
class TelegramBotService {

    @Value("${telegram.chat-id:}")
    private final String adminChatId;  // 명령을 허용하는 관리자 텔레그램 채팅 ID
    private final TelegramApiClient apiClient;
    private final GetTradeHistoryUseCase getTradeHistoryUseCase;
    private final GetPortfolioUseCase getPortfolioUseCase;
    private final ExecuteTradingUseCase executeTradingUseCase;
    // 채팅 ID별 FSM 상태 (ConcurrentHashMap: 웹훅 동시 수신 대비)
    private final ConcurrentHashMap<Long, BotState> stateMap = new ConcurrentHashMap<>();

    void handle(TelegramUpdate update) {
        if (update.message() == null || update.message().text() == null) return;
        long chatId = update.message().chat().id();
        String text = update.message().text().trim();

        if (!String.valueOf(chatId).equals(adminChatId)) {
            log.warn("Unauthorized webhook from chatId={}", chatId);
            return;
        }

        BotState state = stateMap.getOrDefault(chatId, BotState.IDLE);
        String reply = switch (state) {
            case IDLE -> handleIdle(chatId, text);
            case AWAITING_RUN_CONFIRM -> handleRunConfirm(chatId, text);
        };
        if (reply != null) {
            apiClient.sendMessage(String.valueOf(chatId), reply);
        }
    }

    private String handleIdle(long chatId, String text) {
        String cmd = text.split("\\s+")[0].toLowerCase();
        return switch (cmd) {
            case "/start", "/help" -> """
                    사용 가능한 명령어:
                    /status — 최신 포트폴리오 현황
                    /history [days] — 거래 내역 (기본 7일)
                    /run — 수동 매매 실행
                    /cancel — 진행 중인 대화 취소""";
            case "/status" -> buildStatusMessage();
            case "/history" -> buildHistoryMessage(parseHistoryDays(text));
            case "/run" -> {
                stateMap.put(chatId, BotState.AWAITING_RUN_CONFIRM);
                yield "정말 수동 매매를 실행할까요? (yes/no)";
            }
            default -> "알 수 없는 명령어입니다. /help 를 입력하세요.";
        };
    }

    private String handleRunConfirm(long chatId, String text) {
        return switch (text.toLowerCase()) {
            case "yes" -> {
                stateMap.put(chatId, BotState.IDLE);
                Thread.ofVirtual().start(() -> {
                    try {
                        executeTradingUseCase.execute();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("수동 매매 스레드 인터럽트");
                    } catch (Exception e) {
                        log.error("수동 매매 실패", e);
                    }
                });
                yield "매매 실행을 시작합니다. 결과는 완료 후 알림으로 전송됩니다.";
            }
            case "no", "/cancel" -> {
                stateMap.put(chatId, BotState.IDLE);
                yield "취소되었습니다.";
            }
            default -> "yes 또는 no 로 답해주세요.";
        };
    }

    private String buildStatusMessage() {
        try {
            PortfolioSnapshot s = getPortfolioUseCase.getCurrent();
            return String.format(
                    "<b>포트폴리오 현황 [%s]</b>%n보유: %d주 @ $%.4f%n현재가: $%.4f%n평가액: $%.2f%n예수금: $%.2f%n총자산: $%.2f",
                    s.snapshotDate(), s.qty(), s.avgPrice(), s.currentPrice(),
                    s.marketValueUsd(), s.usdDeposit(), s.totalAssetUsd());
        } catch (NoSuchElementException e) {
            return "포트폴리오 데이터가 없습니다.";
        }
    }

    private String buildHistoryMessage(int days) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(days);
        List<TradeHistory> list = getTradeHistoryUseCase.getHistory(from, to, "SOXL");
        if (list.isEmpty()) return "최근 " + days + "일 거래 내역이 없습니다.";
        StringBuilder sb = new StringBuilder("<b>최근 " + days + "일 거래 내역</b>\n");
        list.forEach(h -> sb.append(String.format("%s %s %s %d주 $%.4f%n",
                h.tradeDate(), h.direction(), h.orderType(), h.qty(), h.price())));
        return sb.toString().trim();
    }

    private int parseHistoryDays(String text) {
        String[] parts = text.split("\\s+");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return 7;
    }
}
