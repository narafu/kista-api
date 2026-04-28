# 설계 문서: 웹 대시보드 + REST API & Telegram 봇 (FSM)

**날짜:** 2026-04-28  
**대상 태스크:** 웹 대시보드 + REST API 구현 / Telegram 봇 구현 (FSM)

---

## 1. 웹 대시보드 + REST API

### 1.1 Application 계층 (신규 Service 3개)

| 클래스 | 패키지 | 구현 인터페이스 | 핵심 동작 |
|--------|--------|----------------|-----------|
| `TradeHistoryService` | `application/service` | `GetTradeHistoryUseCase` | `TradeHistoryPort.findBy(from, to, symbol)` 위임 |
| `PortfolioService` | `application/service` | `GetPortfolioUseCase` | `getCurrent()` → `findRecent(7)` 첫 번째 요소(최신, 주말 허용); `getSnapshots(days)` 위임 |
| `FidaOrderService` | `application/service` | `ExecuteFidaOrderUseCase` | 토큰 취득 → `KisOrderPort.place(LIMIT 주문)` |

- 모든 Service는 `@Service` 어노테이션 부착 (ArchUnit 규칙 준수)
- `FidaOrderService`는 `KisTokenPort` + `KisOrderPort` 주입

### 1.2 REST API (DashboardController)

**위치:** `adapter/in/web/DashboardController`

| 메서드 | 경로 | 파라미터 | 동작 |
|--------|------|---------|------|
| GET | `/api/trades` | `from` (기본: -30일), `to` (기본: 오늘), `symbol` (기본: SOXL) | 거래 내역 목록 반환 |
| GET | `/api/portfolio/current` | — | 최신 포트폴리오 스냅샷 반환 |
| GET | `/api/portfolio/snapshots` | `days` (기본: 30) | N일간 스냅샷 목록 반환 |
| POST | `/api/orders/fida` | body: `FidaOrderRequestDto` | 수동 지정가 주문 실행 |

**DTO (adapter/in/web/dto/):**
- `TradeHistoryResponse` — TradeHistory 필드를 JSON 직렬화
- `PortfolioSnapshotResponse` — PortfolioSnapshot 필드를 JSON 직렬화
- `FidaOrderRequestDto` — symbol, direction, qty, price (`@NotNull`, `@Positive` 검증)

### 1.3 프론트엔드

**위치:** `src/main/resources/static/index.html`

- Chart.js CDN (vanilla JS, 빌드 도구 없음)
- 포트폴리오 자산 추이 꺾은선 차트 (`/api/portfolio/snapshots`)
- 거래 내역 테이블 (`/api/trades`)
- fetch API로 REST 호출

### 1.4 테스트 전략

| 대상 | 방식 |
|------|------|
| `TradeHistoryService` | `@ExtendWith(MockitoExtension.class)` — Port mock |
| `PortfolioService` | `@ExtendWith(MockitoExtension.class)` — Port mock |
| `FidaOrderService` | `@ExtendWith(MockitoExtension.class)` — KisTokenPort + KisOrderPort mock |
| `DashboardController` | `@WebMvcTest` — UseCase mock, HTTP 상태코드 + JSON 검증 |

---

## 2. Telegram 봇 (FSM)

### 2.1 패키지 구조

```
adapter/in/telegram/
  TelegramWebhookController   — POST /telegram/webhook 수신
  TelegramBotService          — FSM 상태 관리 + 명령 처리
  TelegramApiClient           — RestTemplate sendMessage 호출
  TelegramUpdate (record)     — 웹훅 페이로드 역직렬화
```

### 2.2 FSM 상태 및 명령어

**상태 저장:** `ConcurrentHashMap<Long, BotState>` (인메모리, chatId 키)

```
enum BotState { IDLE, AWAITING_RUN_CONFIRM }
```

| 현재 상태 | 입력 | 동작 | 다음 상태 |
|-----------|------|------|-----------|
| IDLE | `/help`, `/start` | 명령어 목록 전송 | IDLE |
| IDLE | `/status` | 최신 포트폴리오 스냅샷 텍스트 전송 | IDLE |
| IDLE | `/history [N=7]` | 최근 N일 거래 내역 전송 | IDLE |
| IDLE | `/run` | "정말 수동 실행할까요? (yes/no)" | AWAITING_RUN_CONFIRM |
| AWAITING_RUN_CONFIRM | `yes` | Virtual Thread로 `ExecuteTradingUseCase.execute()` 실행 후 "실행 시작" 메시지 | IDLE |
| AWAITING_RUN_CONFIRM | `no` / `/cancel` | "취소되었습니다." | IDLE |
| any | 알 수 없는 입력 | "알 수 없는 명령어입니다." | 유지 |

### 2.3 보안

- 수신 `message.chat.id` ≠ `telegram.chat-id` 설정값이면 무시 (응답 없음)
- 설정값 누락 시 경고 로그만 출력하고 처리 생략

### 2.4 TelegramApiClient

- 기존 `TelegramProperties` (botToken, chatId) 공유
- `sendMessage(String chatId, String text)` — `parse_mode: HTML` 지원
- 실패 시 로그만 기록 (예외 전파 안 함)

### 2.5 TelegramUpdate (역직렬화 record)

```
TelegramUpdate(Long updateId, Message message)
Message(Long messageId, Chat chat, String text)
Chat(Long id)
```
Jackson `@JsonProperty`로 snake_case 매핑.

### 2.6 수동 실행 (Virtual Thread)

```java
Thread.ofVirtual().start(() -> {
    try { tradingUseCase.execute(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    catch (Exception e) { log.error("수동 실행 실패", e); }
});
```
`@Async`, `CompletableFuture` 사용 금지 — CLAUDE.md 규칙 준수.

### 2.7 테스트 전략

| 대상 | 방식 |
|------|------|
| `TelegramBotService` | `@ExtendWith(MockitoExtension.class)` — FSM 상태 전이 단위 테스트 |
| `TelegramWebhookController` | `@WebMvcTest` — 웹훅 페이로드 파싱 + 200 응답 검증 |
| `TelegramApiClient` | `@ExtendWith(MockitoExtension.class)` — RestTemplate mock |

---

## 3. 구현 순서

1. `TradeHistoryService` + 단위 테스트 → 커밋
2. `PortfolioService` + 단위 테스트 → 커밋
3. `FidaOrderService` + 단위 테스트 → 커밋
4. `DashboardController` + DTO + `@WebMvcTest` → 커밋
5. `index.html` 프론트엔드 → 커밋
6. `TelegramApiClient` + 단위 테스트 → 커밋
7. `TelegramBotService` (FSM) + 단위 테스트 → 커밋
8. `TelegramWebhookController` + `@WebMvcTest` → 커밋
