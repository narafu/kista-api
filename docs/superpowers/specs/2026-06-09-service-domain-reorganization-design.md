# application/service/ 도메인별 재조직 설계

## 목적

현재 `application/service/` 패키지에 30개 클래스가 평탄하게 나열되어 있어, 어떤 서비스가 어떤 도메인에 속하는지 파악하기 어렵다.
`domain/model/` 및 `adapter/out/persistence/`가 이미 도메인별 서브패키지로 구성되어 있으므로, `application/service/`도 동일 원칙을 적용한다.

---

## 변경 후 패키지 구조

```
application/service/
├── user/           (4개)
│   ├── UserService
│   ├── UserCascadeDeleter   ← public으로 변경 (AdminService 주입)
│   ├── FcmTokenService
│   └── KakaoLoginService
│
├── account/        (3개)
│   ├── AccountService
│   ├── AccountStatisticsService
│   └── KisConnectionTestService
│
├── tradingcycle/   (1개)
│   └── TradingCycleService
│
├── trading/        (12개)
│   ├── TradingService          ← ExecuteTradingUseCase (배치 실행)
│   ├── ManualTradingService    ← ManualExecuteTradingUseCase
│   ├── TradingPreviewService   ← GetNextOrdersUseCase
│   ├── TradingOrderExecutor    ← BUY 가격 보정 + KIS 접수 helper
│   ├── TradingOrderPlanner     ← PLANNED 주문 저장 helper
│   ├── TradingBalanceLoader    ← 잔고 로드 helper
│   ├── TradingPriceFetcher     ← 현재가 조회 helper
│   ├── TradingReporter         ← 체결 조회 + 이력 저장 + 알림 helper
│   ├── CycleOrderComputer      ← 전략 계산 helper
│   ├── CycleRotationService    ← 사이클 자동 재등록 (TradingReporter가 호출)
│   ├── BuyOrderPriceCapper     ← 매수 가격 상한 처리 helper
│   └── OrderCancelService      ← CancelOrderUseCase
│
├── privacy/        (2개)
│   ├── PrivacyTradeService     ← GetPrivacyCurrentBaseUseCase
│   └── FidaOrderService        ← ExecuteFidaOrderUseCase
│
├── portfolio/      (2개)
│   ├── PortfolioService        ← GetPortfolioUseCase
│   └── TradeHistoryService     ← GetTradeHistoryUseCase
│
├── market/         (1개)
│   └── MarketHolidayService    ← GetMarketHolidaysUseCase
│
└── admin/          (5개)
    ├── AdminService            ← AdminListUsersUseCase, AdminUserActionUseCase, AdminDashboardUseCase
    ├── AdminAccountService     ← AdminListAccountsUseCase
    ├── AdminAnomaliesService   ← AdminAnomaliesUseCase
    ├── AdminAuditService       ← AdminListAuditLogsUseCase
    └── AdminTradeService       ← AdminListTradesUseCase
```

---

## 코드 변경 범위

### 각 파일 (30개)
- `package com.kista.application.service;` → `package com.kista.application.service.<domain>;`
- import 추가 없음 (다른 서비스를 타 도메인 패키지에서 직접 참조하는 경우만 해당)

### access modifier 변경 (1개)
| 파일 | 변경 전 | 변경 후 | 이유 |
|---|---|---|---|
| `UserCascadeDeleter` | package-private | `public` | `AdminService`(admin 패키지)가 직접 필드 주입 |

### 크로스 도메인 서비스 의존
| 호출자 | 피호출자 | 해결 방법 |
|---|---|---|
| `AdminService` (admin/) | `UserCascadeDeleter` (user/) | `UserCascadeDeleter`를 `public`으로 선언 |

나머지 서비스들은 UseCase 인터페이스(`domain/port/in/`) 또는 같은 패키지 내 helper만 주입한다.

---

## 영향 없는 항목

- **ArchUnit 규칙**: `application.service.*` 내부 서브패키지 간 의존은 현재 검증하지 않음. 레이어 간 의존 방향(`adapter → domain.port.in`, `application → domain`) 변화 없음.
- **Spring 빈 주입**: 컨트롤러는 UseCase 인터페이스로만 주입하므로 구현 클래스 패키지 변경에 무관.
- **테스트**: 서비스 단위 테스트는 패키지 선언 + import만 조정.

---

## 도메인 기준 정리

| 서브패키지 | 대응 domain/model | 역할 |
|---|---|---|
| user/ | domain/model/user/ | 사용자 관리, FCM 토큰, 카카오 인증 |
| account/ | domain/model/account/ | 증권 계좌 CRUD, KIS 연결 테스트 |
| tradingcycle/ | domain/model/tradingcycle/ | 사이클 CRUD / pause / resume |
| trading/ | domain/model/strategy/ + order/ | 매매 실행, 주문 계획·접수·취소, 사이클 재등록 |
| privacy/ | domain/model/privacy/ | PRIVACY 전략 기준가·FIDA 주문 |
| portfolio/ | — | 포트폴리오·거래 이력 조회 |
| market/ | — | 미국 시장 휴장일 조회 |
| admin/ | domain/model/admin/ | 관리자 대시보드·감사 로그·이상 감지 |
