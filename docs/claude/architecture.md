## 아키텍처

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`).

```
domain/          ← 순수 Java record/class. Spring·JPA 어노테이션 금지
  model/         ← 불변 값 객체 (record 사용)
  strategy/      ← TradingStrategy 인터페이스 및 구현 (SoxlDivisionStrategy) — @Component 허용 예외 (ArchUnit)
  port/in/       ← UseCase 인터페이스 (인바운드 포트)
  port/out/      ← 아웃바운드 포트 인터페이스

application/
  service/       ← UseCase 구현체 (@Service), Port를 통해서만 외부 호출

adapter/in/
  schedule/      ← TradingScheduler (월~금 04:00 KST, 멀티계좌)
  web/           ← REST Controller + DTO (DashboardController)
  telegram/      ← TelegramWebhookController + TelegramBotService

adapter/out/
  kis/           ← KIS API Adapter (KisHttpClient 공통 헤더 처리)
  persistence/   ← JPA Entity + JpaRepository + PersistenceAdapter
  notify/        ← TelegramAdapter (NotifyPort 구현)
```

### 레이어 의존 방향

```
adapter.in  →  domain.port.in (UseCase)
application →  domain (model + port)
adapter.out →  domain.port.out (Port 구현)
domain      →  외부 의존 없음
```

---

## 동시 수정 필요 파일 쌍

| 파일 A | 파일 B |
|--------|--------|
| 새 환경변수 추가 | `application.yml` + `.env.example` + `docker-compose.yml` |
| 새 Flyway 마이그레이션 | 해당 Entity + JpaRepository |
| Port 인터페이스 수정 | 구현 Adapter + 테스트 Mock |
| `KisOrderPort` 시그니처 변경 | `TradingService` + `FidaOrderService` + 관련 테스트 |
| `AccountService` UseCase 추가 | `AccountController` 필드 + 엔드포인트 동시 추가 |
| 매매 공식 변경 | `SoxlDivisionStrategyTest` |
| `TradingVariables` 필드 추가 | `TelegramAdapterTest.java` (하드코딩 생성자) + `SoxlDivisionStrategyTest` |
| 새 KIS Adapter 추가 | 같은 패키지에 `*AdapterTest` 단위 테스트 |
| `UserPersistenceAdapter` telegramBotToken 변경 | `UserEntity` + `AesCryptoService` 암호화/복호화 패턴 확인 |

### 텔레그램 알림 우선순위 (notifyTradingReport)
- 계좌별 `telegramBotToken/chatId` 있으면 계좌봇 발송 → 없으면 `User.telegramBotToken/chatId` 사용자봇 → 없으면 생략 (`log.warn`)
- `UserPersistenceAdapter`: telegramBotToken AES-256 암호화/복호화 적용 (`AccountPersistenceAdapter`와 동일 패턴)
