# kista-api 코어

KIS(한국투자증권) REST API를 통한 SOXL/TQQQ 분할매매 자동화 서비스.
Java 21 + Spring Boot 3 + Hexagonal Architecture.

## 레이어 구조

```
domain/
  model/       — 불변 record. Spring·JPA 어노테이션 금지
    user/       user, account/, tradingcycle/, strategy/, order/, kis/, admin/, privacy/
  strategy/    — InfiniteStrategy/PrivacyStrategy 인터페이스+구현 (@Component 허용 예외)
  port/in/     — UseCase 인터페이스 (인바운드 포트)
  port/out/    — 아웃바운드 포트 인터페이스 (*Port 접미사 필수)

application/
  service/     — UseCase 구현체 (@Service)
  config/      — Spring Config
  event/       — 도메인 이벤트

adapter/in/
  schedule/    — TradingScheduler (화~토 KST 04:00)
  web/         — REST Controller + DTO + security/
  telegram/    — TelegramWebhookController + TelegramBotService

adapter/out/
  kis/         — KIS API Adapter
  persistence/ — JPA: user/, account/, tradingcycle/, trade/, kistoken/, audit/, privacy/, calendar/
  notify/      — TelegramAdapter (NotifyPort 구현)
  alpaca/      — AlpacaCalendarAdapter
  kakao/       — 카카오 OAuth
  sse/         — SSE 어댑터
  crypto/      — AesCryptoService
```

## 레이어 의존 방향 (ArchUnit 빌드 시 강제 검증)

```
adapter.in  → domain.port.in
application → domain (model + port)
adapter.out → domain.port.out
domain      → 외부 의존 없음
```

## 핵심 불변 규칙

- `*Port` 접미사: 아웃바운드 포트만. `*JpaRepository`는 adapter 레이어 package-private
- 도메인 record는 Spring·JPA 어노테이션 금지
- 새 외부 서비스: `*Properties` + `*Config` + `*Adapter` 3파일 패턴
- `@Async` / `CompletableFuture` 사용 금지 — Virtual Thread 사용

## 주요 참조 메모리

- 아키텍처 상세(컨트롤러 목록, 동시수정 필요 파일쌍): `mem:architecture`
- JPA·Flyway 제약: `mem:constraints`
- 매매 공식·전략 패턴: `mem:trading_strategy`
- KIS API 연동: `mem:kis_api`
- 테스트 패턴: `mem:testing`
- 빌드/실행 명령어: `mem:suggested_commands`
