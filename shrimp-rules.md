# KISTA Development Guidelines

## Project Overview

- **목적**: 한국투자증권(KIS) REST API를 통한 SOXL 분할매매 자동화 (trade-kis-n8n → Spring Boot 마이그레이션)
- **패키지 루트**: `com.kista`
- **기반**: `claude-starter-kit` (Hexagonal Architecture, Java 21, Spring Boot 3)
- **빌드 도구**: Gradle (Kotlin DSL) — `build.gradle.kts`
- **데이터베이스**: PostgreSQL + Flyway
- **배포**: OCI Always Free + docker-compose

---

## Project Architecture

### 디렉토리 구조

```
src/main/java/com/kista/
├── domain/
│   ├── model/          # 순수 Java record/class (Spring·JPA 어노테이션 없음)
│   ├── strategy/       # 매매 전략 인터페이스 및 구현
│   └── port/
│       ├── in/         # UseCase 인터페이스 (인바운드 포트)
│       └── out/        # 아웃바운드 포트 인터페이스
├── application/
│   └── service/        # UseCase 구현 (@Service)
└── adapter/
    ├── in/
    │   ├── schedule/   # TradingScheduler
    │   ├── web/        # REST Controller + DTO
    │   └── telegram/   # Telegram Webhook Controller + Bot Service
    └── out/
        ├── kis/        # KIS API Adapter
        ├── persistence/ # JPA Entity + Repository + Adapter
        └── notify/     # TelegramAdapter (알림 전송)

src/main/resources/db/migration/
├── V1__create_trade_histories.sql
├── V2__create_portfolio_snapshots.sql
└── V3__create_strategy_configs.sql
```

### 레이어 의존성 (ArchUnit으로 빌드 시 강제)

```
adapter.in → domain.port.in (UseCase 인터페이스)
application.service → domain (model + port)
adapter.out → domain.port.out (Port 인터페이스 구현)
domain → 외부 레이어 의존 금지
```

---

## Code Standards

### 네이밍 규칙

| 종류 | 형식 | 예시 |
|------|------|------|
| UseCase 인터페이스 | `{동사}{명사}UseCase` | `ExecuteTradingUseCase` |
| Port 인터페이스 | `Kis{기능}Port`, `{기능}Port` | `KisOrderPort`, `NotifyPort` |
| Service 클래스 | `{도메인}Service` | `TradingService` |
| Adapter 클래스 | `Kis{기능}Adapter`, `{기능}Adapter` | `KisOrderAdapter`, `TelegramAdapter` |
| Entity 클래스 | `{도메인}Entity` | `TradeHistoryEntity` |
| JPA Repository | `{도메인}JpaRepository` | `TradeHistoryJpaRepository` |
| Controller | `{기능}Controller` | `FidaOrderController` |

### 도메인 모델 규칙

- **`record`** 사용 (불변 값 객체)
- `@Nullable` 명시: `holdings == 0`이면 `avgPrice`는 반드시 `null`
- Spring (`@Component`, `@Service` 등) 및 JPA (`@Entity`, `@Column` 등) 어노테이션 **절대 금지**
- `shouldSkip()` 같은 비즈니스 판단 메서드는 도메인 모델에 위치

### 매매 변수 공식 (변경 금지 — 단위 테스트로 검증)

```
A = avgPrice (qty==0이면 currentPrice 사용)
Q = soxlQty
M = A × Q
D = effectiveAmt (해외주문가능금액)
B = D + M
K = B ÷ 20  (scale=2, HALF_UP)
T = Q==0 ? 0 : floor(M ÷ K)
S = (20 - T×2) ÷ 100  (scale=4, HALF_UP)
P = A × 1.2  (scale=2, HALF_UP)
```

---

## Functionality Implementation Standards

### 새 도메인 기능 추가 순서

1. `domain/model/` — 도메인 모델 추가/수정
2. `domain/port/in/` — UseCase 인터페이스 정의
3. `domain/port/out/` — 필요한 아웃바운드 포트 정의
4. `application/service/` — UseCase 구현
5. `adapter/out/` — Port 구현 (KIS, Persistence, Notify)
6. `adapter/in/` — 진입점 구현 (Scheduler, Controller, Telegram)

### 새 매매 전략 추가

1. `domain/strategy/TradingStrategy` 인터페이스 구현
2. `application/service/TradingService`에서 Strategy 주입 (Strategy 패턴 유지)
3. `strategy_configs` 테이블에 설정 항목 추가

### FIDA 연동 엔드포인트 수정

- 진입점: `adapter/in/web/FidaOrderController` → `POST /api/fida/orders`
- 흐름: `FidaOrderController` → `FidaOrderService` → `KisOrderPort`
- `trade_histories` 저장 시 `phase = 'FIDA'` 필수

---

## KIS API Adapter Standards

### KisHttpClient 공통 규칙

- **모든** KIS API 호출은 `KisHttpClient`를 통해 공통 헤더 처리
- Base URL: `https://openapi.koreainvestment.com:9443`
- 공통 헤더: `authorization`, `appkey`, `appsecret`, `tr_id`, `custtype: P`
- `tr_id`는 각 Adapter 클래스 내부 상수로 정의

### 토큰 관리

- `KisTokenAdapter`만 토큰 발급/관리 담당
- 다른 KIS Adapter는 `token` 파라미터를 받아 사용 (직접 발급 금지)
- `POST /oauth2/tokenP` — `grant_type: client_credentials`

### KIS API별 tr_id

| Adapter | 메서드 | tr_id |
|---------|--------|-------|
| KisHolidayAdapter | 휴장일 조회 | `CTOS5011R` |
| KisAccountAdapter | 잔고 조회 | `TTTS3012R` |
| KisAccountAdapter | 주문가능금액 | `CTRP6504R` |
| KisOrderAdapter | 해외주식 주문 | `TTTS0308U` |
| KisExecutionAdapter | 체결 조회 | 체결 내역 API |

### 오류 처리

- `KisHolidayAdapter` API 오류 시 → **개장으로 폴백** (안전 방향)
- KIS API 응답 오류 시 도메인 예외(`KisApiException` 등) throw

---

## Scheduler Standards

### TradingScheduler 규칙

- 실행 시각: **04:00 KST 월~금** (`@Scheduled`)
- `TradingService.execute()` 내부에서 `Thread.sleep()` 사용 (Virtual Thread 기반)
- **`@Async`, `CompletableFuture` 사용 금지** — Virtual Thread가 carrier를 해방
- `application.properties`에 `spring.threads.virtual.enabled=true` 필수

### 실행 흐름 순서 (TradingService)

1. `DstInfo` 계산 (서머타임 여부)
2. `Thread.sleep(dst.waitUntilLocDeadline())` — LOC 마감까지 대기
3. KIS 토큰 발급
4. 휴장일 확인 → 휴장이면 알림 후 종료
5. 잔고 조회 → `shouldSkip()` true면 알림 후 종료
6. 현재가 조회 → 변수 계산 → 메인 주문 실행
7. `Thread.sleep(dst.waitUntilPostClose())` — 장 마감 후 10분 대기
8. 체결 확인 → 보정 주문 실행
9. 일일 결산 리포트 Telegram 전송

---

## Database / Flyway Standards

### Flyway 규칙

- **기존 `V*.sql` 파일 절대 수정 금지**
- 스키마 변경 시 항상 **새 버전 파일** 추가 (`V4__...sql`, `V5__...sql`)
- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate DDL 자동 생성 금지

### 테이블 목록

| 파일 | 테이블 | 용도 |
|------|--------|------|
| `V1__create_trade_histories.sql` | `trade_histories` | 주문 내역 저장 |
| `V2__create_portfolio_snapshots.sql` | `portfolio_snapshots` | 자산 추이 스냅샷 |
| `V3__create_strategy_configs.sql` | `strategy_configs` | 전략별 파라미터 설정 |

### Entity 위치 규칙

- JPA Entity는 **`adapter.out.persistence`** 패키지에만 위치
- Entity 클래스명: `{도메인}Entity` (예: `TradeHistoryEntity`)
- domain model에 `@Entity`, `@Column` 등 JPA 어노테이션 추가 금지

---

## Telegram Bot Standards

### 패턴 출처

- `capital-manager/` 프로젝트의 `SessionState`, `BotCommand`, `TelegramApiClient` 패턴 이식

### 웹훅 및 명령어

- 웹훅 엔드포인트: `POST /webhook/telegram`
- 인증: `TELEGRAM_WEBHOOK_SECRET` 헤더 검증
- 지원 명령어: `/status`, `/history`, `/portfolio`, `/help`

### Adapter 역할 분리

| 클래스 | 역할 |
|--------|------|
| `TelegramWebhookController` | 웹훅 수신 (adapter.in.telegram) |
| `TelegramBotService` | 명령어 FSM 처리 (adapter.in.telegram) |
| `TelegramAdapter` | 메시지 전송 — NotifyPort 구현 (adapter.out.notify) |

---

## Environment Variables Standards

### .env 파일 관리

- 민감 정보 코드 하드코딩 **절대 금지**
- 환경변수 추가/삭제 시 `.env.example`도 **반드시 동시 수정**
- application.properties에서 `${ENV_VAR:default}` 형식 사용

### 필수 환경변수 목록

```
KIS_APP_KEY, KIS_APP_SECRET, KIS_ACCT_STOCK, KIS_HTS_ID
TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, TELEGRAM_WEBHOOK_SECRET
POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
```

---

## Key File Interaction Standards

### 동시 수정이 필요한 파일 쌍

| 파일 A 수정 시 | 파일 B도 반드시 수정 |
|---------------|---------------------|
| `.env.example` | `docker-compose.yml` 환경변수 섹션 |
| 새 환경변수 추가 | `application.properties` + `.env.example` |
| 새 Flyway 마이그레이션 추가 | 해당 Entity/JpaRepository 클래스 |
| Port 인터페이스 수정 | 구현 Adapter + 테스트 Mock |
| 매매 공식 변경 | `SoxlDivisionStrategyTest` 단위 테스트 |
| 새 KIS Adapter 추가 | `KisHttpClient` 공통 헤더 확인 |

---

## Testing Standards

### 단위 테스트 필수 항목

- `SoxlDivisionStrategy`: A, Q, M, D, B, K, T, S, P 계산 정확도 검증
- `CorrectionStrategy`: 미체결 보정 계산 검증
- `AccountBalance.shouldSkip()`: 잔고 부족 판단 로직

### 통합 테스트

- KIS API: 실제 토큰 발급 + 잔고 조회 확인 (모의투자 계정)
- Persistence: Testcontainers PostgreSQL 사용

### ArchUnit 규칙 유지

- `HexagonalArchitectureTest` 클래스 반드시 유지
- 패키지 기반 규칙: `com.kista.domain..`, `com.kista.adapter..`, `com.kista.application..`

---

## AI Decision Standards

### 모호한 상황 판단 기준

| 상황 | 결정 |
|------|------|
| 매매 로직 수정 요청 | `SoxlDivisionStrategy`만 수정, `TradingService`에 로직 추가 금지 |
| 새 KIS API 연동 | 새 Adapter 클래스 생성, `KisHttpClient` 재사용 |
| 스케줄링 방식 변경 요청 | Virtual Thread + Thread.sleep 유지, @Async 도입 거부 |
| 스키마 변경 | 기존 V*.sql 수정 금지, 새 버전 파일 추가 |
| 도메인 모델에 Spring 어노테이션 필요 시 | 어댑터 계층으로 이동 후 변환 |

---

## Prohibited Actions

- **domain 패키지**에 `@Component`, `@Service`, `@Repository`, `@Entity`, `@Column` 추가 금지
- **기존 Flyway 마이그레이션 파일** (`V1__.sql` ~ `V3__.sql`) 내용 수정 금지
- `TradingService` 또는 `FidaOrderService`에 KIS API 직접 호출 로직 추가 금지 (반드시 Port 경유)
- KIS API 키, Telegram 토큰 등 민감 정보를 코드에 하드코딩 금지
- `Thread.sleep()` 대신 `@Async` 또는 `CompletableFuture` 사용 금지
- `adapter.in` 패키지에서 `application.service` 구현체 직접 참조 금지
- 실전 계정으로 바로 전환 금지 — 모의투자 계정으로 1주일 검증 후 전환
