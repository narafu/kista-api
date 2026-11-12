# 17. 미배정 모듈 측정 (finance · admin · stats · notify)

조사 기준: 2026-11-13, HEAD `1db0d8db1`. 읽기 전용 측정(코드 변경 없음). 경로 약어: `API/` = `src/main/java/com/kista/api/`, `TC/` = `trading-core/src/main/java/com/kista/tradingcore/`, `FIN/` = `API/finance/`. 줄 수는 `wc -l` 기준(주석·import·빈 줄 포함).
이미 처방된 것(지침서 01 UseCase·단일 포트 제거, 02 Response·Request 사본·Store 통합·조회 View 직행, 10 T4·T8 가계부 집계·keyset)은 "일반 처방" 칸으로 묶고, 그것을 적용해도 남는 문제를 "고유 문제"로 따로 적는다.

## 1. `api.finance` (가계부)

### 1-1. 규모

| 레이어 | 파일 | 줄 | 비고 |
|---|---|---|---|
| domain/model | 19 | 452 | record·enum 다수, 인터페이스 1(`GroupShareable`) |
| application/usecase | 11 | 171 | 인터페이스 10(전부 구현 1개) + package-info |
| application/port/output | 9 | 249 | 인터페이스 8(전부 구현 1개) + package-info |
| application/service | 14 | 1,206 | 서비스 9·지원 클래스 4(`FinanceAccessSupport` 32, `GroupShareSupport` 49, `GroupMembershipSupport` 41, `MonthlyClosingGuard` 31)·Notifier 1 |
| application/event | 2 | 11 | |
| adapter/in/web (컨트롤러) | 9 | 929 | 이 중 Swagger 애너테이션 약 250줄(27%) |
| adapter/in/web/dto | 19 | 476 | `@Schema` 83줄 |
| adapter/in/event·schedule | 2 | 56 | 탈퇴 cascade 리스너 26, 월말 알림 스케줄러 30 |
| adapter/out/persistence | 25 | 1,439 | Entity 9(약 590)·JpaRepository 9·PersistenceAdapter 7 |
| adapter/out/user | 1 | 32 | `UserModuleFinanceMemberAdapter` |
| 합계 | 112 | 5,024 | record 선언 38개(35파일), 인터페이스 28(UseCase 10·Port 8·JpaRepository 9·GroupShareable 1), import 806줄(16%), `//` 주석 354줄 |
| test | 27 | 5,441 | main 대비 1.08배. 가장 큰 것: `FinanceCategoryServiceTest` 492, `FinanceBudgetServiceTest` 456, `AssetSnapshotServiceTest` 390 |

구현 1개짜리 인터페이스: UseCase 10/10, Port 8/8(`FinanceMemberPort`는 user 모듈 어댑터가 구현하는 의존 역전이라 지침서 01 조건 2에 해당). 같은 모듈 안 호출처가 컨트롤러 1개뿐인 UseCase가 대부분이고, 모듈 밖 소비는 `FinanceRegistrationReminderUseCase` 1개(`API/notify/adapter/out/gateway/FinanceReminderChannelNotifier.java`)뿐이다.

### 1-2. 기능 대비 분량

엔드포인트 43개: 계좌 6, 카테고리 7, 예산 6, 거래내역 6, 자산 기록 6, 월 점검 2, 그룹 5, 일괄 등록 1, 관리자 시스템 카테고리 4. 테이블 9개(`finance_groups`, `_group_members`, `_group_invitations`, `_accounts`, `_categories`, `_budgets`, `_transactions`, `_asset_snapshots`, `_monthly_closings`).
엔드포인트 1개당 main 평균 117줄(5,024/43), test 126줄.

수직 추적(컨트롤러에서 DB까지, 파일 = 타입 하나씩):

| 기능 | 거치는 타입 |
|---|---|
| `POST /api/finance/transactions` | `FinanceTransactionController` → `FinanceTransactionRequest` → `FinanceTransactionCommand` → `FinanceTransactionUseCase` → `FinanceTransactionService`(+`MonthlyClosingGuard`·`GroupShareSupport`·`FinanceGroupPort`·`FinanceCategoryPort` 조회) → `FinanceTransaction`(도메인) → `FinanceTransactionPort` → `FinanceTransactionPersistenceAdapter` → `FinanceTransactionEntity` → `FinanceTransactionJpaRepository` → `FinanceTransactionResponse`. **핵심 11타입 + 보조 3**, 합 532줄 |
| `POST /api/finance/asset-snapshots` | 위와 같은 11타입(합 624줄) + 컨트롤러가 `FinanceCategoryPort`·`FinanceAccountPort`를 직접 주입받아 응답을 보강(`FIN/adapter/in/web/AssetSnapshotController.java:36-39,123-145`) |

### 1-3. 평행 구조

같은 모양의 5묶음(`GroupShareable` 애그리게이트): 거래내역·자산 기록·예산·계좌·카테고리. 묶음 하나가 11파일이다.

| 애그리게이트 | ctrl | usecase | service | port | adapter | entity | jpa | domain | cmd | req | resp | 합 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Transaction | 114 | 22 | 120 | 25 | 50 | 67 | 39 | 31 | 12 | 26 | 26 | 532 |
| AssetSnapshot | 145 | 22 | 121 | 26 | 55 | 88 | 39 | 35 | 16 | 35 | 42 | 624 |
| Budget | 116 | 22 | 174 | 32 | 66 | 64 | 42 | 38 | 12 | 25 | 26 | 617 |
| Account | 108 | 16 | 104 | 35 | 70 | 85 | 34 | 63 | 11 | 37 | 30 | 593 |
| Category | 132 | 27 | 188 | 41 | 71 | 69 | 63 | 65 | 11 | 29 | 31 | 727 |

5묶음 합계 3,093줄 = 모듈의 62%. 서로 다른 부분은 `create`/`update`의 검증 몇 줄과 JPA 쿼리뿐이고 나머지는 같은 뼈대다.

같은 뼈대로 반복되는 블록:

| 블록 | 위치 | 줄 | 구성원 사이 다른 점 |
|---|---|---|---|
| 서비스 `shareToGroup`+`unshare` (`GroupShareSupport` 호출 + `.map(save+log).orElse(existing)`) | `FIN/application/service/FinanceTransactionService.java:90-116`, `AssetSnapshotService.java:91-121`, `FinanceAccountService.java:79-103`, `FinanceBudgetService.java:149-173`, `FinanceCategoryService.java:75-106` | 각 약 14~30(합 약 130) | 마감월 가드(거래·자산만), 시스템 카테고리 가드와 하위 일괄 전환(카테고리만), 에러 문구·로그 문구 |
| 컨트롤러 `share`/`unshare`/`delete` 메서드(Swagger 애너테이션 포함) | `FinanceAccountController.java:89,102`, `FinanceBudgetController.java:82,96`, `AssetSnapshotController.java:89,102`, `FinanceTransactionController.java:81,94`, `FinanceCategoryController.java:99,112` | 각 12~14 × 10 = 약 130 | 경로 이름·UseCase 필드·설명 문구 |
| 포트 `findByIdOrThrow` default | `FIN/application/port/output/AssetSnapshotPort.java:17`, `FinanceAccountPort.java:19`, `FinanceBudgetPort.java:22`, `FinanceCategoryPort.java:20`, `FinanceGroupPort.java:26`, `FinanceTransactionPort.java:17` | 각 4 | 예외 문구의 명사 |
| 소프트 삭제 JPQL `softDeleteById`·`softDeleteByUserId` | `FinanceAccountJpaRepository.java:28,32`, `FinanceAssetSnapshotJpaRepository.java:33,37`, `FinanceTransactionJpaRepository.java:33,37`, `FinanceCategoryJpaRepository.java:43`(+ 재귀 CTE `:38`), `FinanceGroupJpaRepository.java:30` | 각 3~4 | 엔티티 이름 |
| Entity `fromModel`/`toDomain` | 9개 Entity 전부 | 각 15~25 | 필드 목록 |

### 1-4. 타입 복제

| 개념 | 선언 횟수 | 필드가 100% 같은 쌍 |
|---|---|---|
| 거래내역 | Request·Command·Domain·Entity·Response 5 | Request=Command(4/4), 일괄 등록 `TransactionItem`(`BulkFinanceRegisterRequest.java:38`)도 같은 4필드 → **같은 4필드가 Request·Command·Item·Entity에 4번**. Response는 도메인에서 `userId`·`createdAt`만 뺀 6/8 |
| 자산 기록 | 5 + `AssetItem` | Request=Command=`AssetItem`=8필드(`AssetSnapshotRequest.java:14`, `AssetSnapshotCommand.java:7`, `BulkFinanceRegisterRequest.java:23`). Response는 15필드(이름 조인 보강) |
| 계좌 | 5 | Request=Command(6/6). Response 8/10 |
| 예산 | 5 | Request=Command(4/4). Response 6/8 |
| 카테고리 | 5 | Request=Command(4/4). Response는 `system`·`children` 추가 |
| 일괄 등록 결과 | Response·Result 2 | `BulkFinanceRegisterResponse`=`BulkFinanceRegisterResult`(3/3) |

Request=Command가 100% 같은 쌍 5개 + 결과 쌍 1개. 이 쌍이 가지는 변환은 `toCommand()` 5개와 `BulkFinanceRegisterResponse.from()`.

### 1-5. 내부 중복(3곳 이상)

| 로직 | 위치 | 비고 |
|---|---|---|
| 서비스 `create`의 "현재 그룹 조회 → 카테고리 검증 → 마감월 검증 → `requireGroupIfSharing` → ownerGroupId 계산 → `new X(null, ownerGroupId, ...)` → save → log" | `FinanceTransactionService.java:41-56`, `AssetSnapshotService.java:41-55`, `FinanceBudgetService.java:45-64`, `FinanceAccountService.java:36-50`, `FinanceCategoryService.java:38-55` | 5곳, 순서·단계가 같고 검증 단계만 다르다 |
| 서비스 `update`의 "findByIdOrThrow/findActive → `FinanceAccessSupport` → 검증 → `new X(existing.id(), ...)`로 전 필드 재구성 → save" | 위 5개 서비스 `update` | `withGroupId` 같은 `with*`가 없어 필드 8~12개를 매번 나열 |
| 카테고리 트리 조립(`list`+재귀 `toResponse`) | `FIN/adapter/in/web/FinanceCategoryController.java:35-58`, `AdminFinanceCategoryController.java:40-58` | 2곳. 주석이 "같은 패턴 — 건드리지 않는다"라고 스스로 인정(`:41`) |
| `resolveParent` 계열 부모 검증 | `FinanceCategoryService.java:173` | 1곳(복제 아님) |

Java와 SQL 양쪽 중복 계산은 미발견(집계가 없고 필터 조건만 SQL에 있다. 소프트 삭제 조건은 아래 1-8).

### 1-6. 간접층

- 호출처 1곳뿐인 UseCase: `FinanceUserDataEraseUseCase`(호출처 `FIN/adapter/in/event/FinanceUserCascadeListener.java` 1곳, 위임 한 줄), `FinanceRegistrationReminderUseCase`(스케줄러 `FinanceRegistrationReminderScheduler.java` + notify 모듈 어댑터 1곳). 나머지 8개는 컨트롤러 1곳 + 일괄 등록 서비스(`BulkFinanceRegisterService.java:25-26`이 `AssetSnapshotUseCase`·`FinanceTransactionUseCase`를 호출).
- 위임만 하는 클래스: `FinanceUserCascadeListener`(진입점이라 유지 대상, 지침서 01의 유지 규칙).
- `UserModuleFinanceMemberAdapter`(32줄)는 user 모듈 포트 2개를 finance 포트 하나로 변환하는 순수 위임이지만 모듈 경계 어댑터다.

### 1-7. 큰 파일(200줄 초과)

없음. 최대 `FinanceCategoryService` 188줄(주제 묶음 4: 사용자 CRUD, 공유 전환, 시스템 카테고리 관리, 부모 검증), `FinanceBudgetService` 174줄(기간 겹침 정리가 약 45줄로 고유 로직).

### 1-8. 비일관성

| 항목 | 이 모듈 | 비교 대상 / 모듈 내 불일치 |
|---|---|---|
| 소프트 삭제 방식 4종 | `@SQLRestriction` 적용 5개(Tx·Snapshot·Group·Member·Invitation: `FinanceTransactionEntity.java:19` 등), `deleted_at`은 있으나 **의도적으로 제한 없음** 2개(`FinanceAccountEntity.java:16-19`, `FinanceCategoryEntity.java:15-18`), 하드 삭제(예산, `FinanceBudgetEntity.java:15`), 삭제 개념 없음(월 점검 `FinanceMonthlyClosingEntity.java:15`) | `constraints.md` 소프트 삭제 절은 `@SQLRestriction` 선언이 기본. 같은 모듈 안에서 4가지로 다르다 |
| 조회 메서드 쌍 | 계좌·카테고리 포트만 `findByIdOrThrow`(삭제 포함)와 `findActiveByIdOrThrow`(삭제 제외) 둘을 둔다(`FinanceAccountPort.java:16-25`, `FinanceCategoryPort.java:17-26`). 서비스가 어느 것을 쓸지 매번 골라야 한다 — 코드 리뷰에서 부활 버그가 났다고 주석에 기록(`FinanceCategoryService.java:59-61`) | 나머지 5개 포트는 `findByIdOrThrow` 하나 |
| 로그 접두사 | 공유 전환 로그 5개 전부 `[userId={}]` 없음(`FinanceTransactionService.java:99`, `AssetSnapshotService.java:100`, `FinanceAccountService.java:85`, `FinanceBudgetService.java:155`, `FinanceCategoryService.java:84`), 공유 해제 로그는 4개가 있고 계좌만 없음(`FinanceAccountService.java:99`) | `constraints.md` "주석 규칙": 엔티티 id가 범위에 있으면 `[userId={}]` 접두사 |
| 응답 보강 위치 | 자산 기록 응답 조립(카테고리명·계좌명·루트 카테고리)을 컨트롤러가 out-port로 직접 한다(`AssetSnapshotController.java:123-145`, N+1을 주석으로 인정). 그룹 멤버 닉네임도 컨트롤러가 `FinanceMemberPort`로 한 건씩 조회(`FinanceGroupController.java:34,56`) | 같은 모듈의 다른 7개 컨트롤러는 UseCase만 호출. 타 모듈은 응답 조립을 서비스(`TradingStatsService`·`StrategyDetailAssembler`)가 한다 |
| 목록 | 거래내역·자산 스냅샷이 LIMIT 없이 전량 반환 | `findings/07-list-endpoints.md`가 이미 다룸(재계수 안 함) |

### 1-9. 손댈 필요 없음

- `GroupShareSupport`(49)·`FinanceAccessSupport`(32)·`GroupMembershipSupport`(41)·`MonthlyClosingGuard`(31)는 이미 5개 서비스 공통 로직을 모은 상태다. 위 3곳 이상 중복 중 `create`·`update` 뼈대만 남았다.
- 크로스 모듈 의존은 user(`UserModuleFinanceMemberAdapter` 1곳)와 `UserDeletedEvent`(리스너 1곳)뿐이다. 외부에서 finance를 import하는 main 파일은 `notify`(1)·`web/GlobalExceptionHandler`(1)·`web/MetaController`(1) 3개.
- 파일 200줄 초과 0개, 서비스 최대 188줄.

### 1-10. 숫자 요약 / 처방 추정 / 고유 문제

- 5,024줄·112파일·엔드포인트 43개(117줄/개)·테이블 9개, test 5,441줄(1.08배). 5개 애그리게이트 묶음이 3,093줄(62%).
- 01·02 일반 처방으로 줄 추정량: UseCase 10파일 171줄 + 단일 구현 포트 7개 약 225줄(`FinanceMemberPort` 24줄 유지) + Request 5개 약 152줄 중 검증 애너테이션 이전분을 뺀 약 110줄 + Response 사본 일부(필드 6~8개짜리 4개 약 100줄) = **약 600~700줄(추정, 12~14%)**. 계좌·카테고리 Response는 `system`·`children`·이름 조인이 있어 사본이 아니다.
- 고유 문제(일반 처방으로 안 줄어드는 것):
  1. 5개 애그리게이트의 `create`/`update`/`share`/`unshare` 서비스 뼈대와 컨트롤러 10개 메서드가 같은 모양으로 복제(`FinanceTransactionService.java:41-116` 외 4곳, 컨트롤러 share/unshare 10곳). 합 약 400줄이 "엔티티 이름만 다른" 코드다.
  2. 소프트 삭제·조회 방식 4종 혼재와 `findActive*` 쌍(`FinanceAccountEntity.java:16-19`, `FinanceCategoryEntity.java:15-18`, 두 포트의 `findActive*`).
  3. 컨트롤러가 out-port로 N+1 조립(`AssetSnapshotController.java:123-145`, `FinanceGroupController.java:56`).
  4. 카테고리 트리 조립 2곳 중복(`FinanceCategoryController.java:35-58`, `AdminFinanceCategoryController.java:40-58`).
  5. 도메인 record에 `with*`가 없어 `update`마다 전 필드를 나열(`FinanceTransactionService.java:66-68` 등 5곳).

## 2. `api.admin` (관리자 조회·정정·런타임 설정)

### 2-1. 규모

| 레이어 | 파일 | 줄 | 비고 |
|---|---|---|---|
| domain/model | 13 | 173 | 예외 4(`Admin*Exception`·`TradingPolicyUnavailableException`, 37줄)·설정 record 4·`AuditLog`·`AppErrorLog`·`AdminStats`·`AdminAnomalies` |
| application/usecase | 8 | 137 | 인터페이스 7(전부 구현 1개, 최대 `AdminQueryUseCase` 47줄·메서드 12개) |
| application/port/output | 11 | 159 | 인터페이스 10: **내부 HTTP 포트 7개(107줄)** + 영속 포트 3(`AuditLogPort`·`AppErrorLogPort`·`RuntimeSettingsPort`) |
| application/service | 8 | 608 | `AdminQueryService` 155, `AdminService` 134, `AdminPrivacyTradeService` 79, `AdminSettingsService` 68 외 |
| adapter/in/web (컨트롤러) | 12 | 683 | |
| adapter/in/web/dto | 18 | 771 | `AdminSettingsRequest` 165, `RuntimeSettingsResponse` 102 |
| adapter/in/telegram·event·redis | 8 | 350 | `TelegramBotService` 183 |
| adapter/out/internal (HTTP 어댑터) | 7 | 412 | trading-core 내부 API 호출, 포트 7개와 1:1 |
| adapter/out/persistence | 9 | 389 | Entity 3·JpaRepository 3·어댑터 3 |
| 합계 | 93 | 3,697 | record 선언 49개(31파일), 인터페이스 20 |
| test | 40 | 4,233 | main 대비 1.14배. 큰 것: `AdminSettingsControllerTest`·`TelegramBotServiceTest`·`PrivacyQueryHttpAdapterTest`·`AdminPrivacyTradeControllerTest`·`AdminTradeControllerTest` |

구현 1개짜리 인터페이스: UseCase 7/7, 포트 10/10(`RuntimeSettingsService`는 `ApprovalPolicyPort` 의존 역전 구현이라 별도).

### 2-2. 기능 대비 분량

엔드포인트 29개: 사용자 4, 계좌·전략 3, 거래 3(조회 2·수동 체결 보정 1), 로그·이상 징후 4, PRIVACY 기준표 6, 설정 2, 공개 런타임 설정 1, 스케줄러 수동 실행 2, 대시보드 1, 핑 1, 클라이언트 오류 수집 1, 텔레그램 웹훅 1. 테이블 3개(`audit_logs`, `app_error_logs`, `admin_runtime_settings`). 나머지 엔드포인트는 trading-core 데이터를 내부 HTTP로 불러와 보여주는 것이다.
엔드포인트 1개당 main 평균 127줄(3,697/29), test 146줄.

덩어리별 분량(파일명 패턴으로 묶음, 겹침 없음):

| 덩어리 | 파일 | 줄 |
|---|---|---|
| 런타임 설정(`*Settings*`·`RuntimeConfig*`·`Benchmark*`) | 17 | 738 |
| 로그·이상 징후(감사·앱 오류·클라이언트 오류·anomalies) | 20 | 683 |
| 내부 HTTP 어댑터 7 | 7 | 412 |
| PRIVACY 기준표 | 7 | 419 |
| 텔레그램 봇 | 3 | 237 |
| 수동 체결 보정 | 4 | 170 |

수직 추적:

| 기능 | 거치는 타입 |
|---|---|
| `GET /api/admin/trades` | `AdminTradeController` → `AdminQueryUseCase` → `AdminQueryService` → `TradingQueryPort` → `TradingQueryHttpAdapter` →(HTTP)→ trading-core 내부 컨트롤러. 응답 조립에 `AccountQueryPort`·`AccountQueryHttpAdapter`, `AdminUserUseCase`·`AdminService`·`UserSummaryPort`, `UserSummaries`, contract 타입 3(`OrderResponse`·`AccountSummaryResponse`·`StrategySummaryResponse`), `AdminTradeResponse`. **admin 쪽만 15타입**, 요청 1건에 내부 HTTP 호출 3회(주문·계좌 전체·전략 요약)와 사용자 전량 조회 1회 |
| `PATCH /api/admin/accounts/{a}/strategies/{s}/status` | `AdminAccountController` → `StrategyStatusRequest` → `AdminStrategyUseCase` → `AdminStrategyService` → `TradingCommandPort` → `TradingCommandHttpAdapter` →(HTTP)→ trading-core. 감사 기록 `AuditLogPort` → `AuditLogPersistenceAdapter` → `AuditLogEntity` → `AuditLogJpaRepository`(+`AuditLog`). **11타입**, 서비스 본문은 호출 1 + 감사 1 |

### 2-3. 평행 구조

| 묶음 | 구성원 | 줄 | 다른 부분 |
|---|---|---|---|
| 내부 HTTP 포트·어댑터 쌍 7 | `AccountQuery`·`PortfolioQuery`·`PrivacyQuery`·`TradingCommand`·`TradingPolicy`·`TradingQuery`·`TradingSchedulerCommand` | 포트 107 + 어댑터 412 = 519 | URL과 반환 타입뿐. 어댑터 본문은 `internalApiRestClient.get/post().uri(..).retrieve().body(new ParameterizedTypeReference<..>(){})` 한 패턴(`TradingQueryHttpAdapter.java:20-69`) |
| 서비스의 "호출 → 감사 로그" 쌍 | `AdminStrategyService`(35줄), `AdminTradeCorrectionService`(52), `AdminPrivacyTradeService`(79), `AdminSettingsService`(68), `AdminService`(134) | 감사 호출 15곳 + 리스너 1곳 | 액션 문자열·대상 타입·`Map.of(...)` 키 |

감사 기록 호출 위치(16곳): `AdminService.java:66,74,81,90,122,131`, `AdminSettingsService.java:42`, `AdminPrivacyTradeService.java:33,43,53,63,75`, `AdminStrategyService.java:25,32`, `AdminTradeCorrectionService.java:29`, `UserSeedPromotedAuditListener.java:27`. 액션 문자열 리터럴이 서비스마다 직접 박혀 있고(`AUDIT_ACTION` 상수는 1곳만), 목록·상수 클래스가 없다.

### 2-4. 타입 복제

필드가 100% 같은 쌍:

| 쌍 | 위치 |
|---|---|
| `AuditLog`=`AuditLogResponse`(7/7) | `domain/model/AuditLog.java:7`, `dto/AuditLogResponse.java:11` |
| `AppErrorLog`=`ErrorLogResponse`(6/6) | `AppErrorLog.java:7`, `ErrorLogResponse.java:11` |
| `AdminStats`=`AdminDashboardResponse`(5/5) | `AdminStats.java:3`, `AdminDashboardResponse.java:6` |
| `AdminAnomalies`=`AnomaliesResponse`(2/2) | `AdminAnomalies.java:8`, `AnomaliesResponse.java:8` |
| `PrivacyQueryPort.CreateBaseResult`=`AdminPrivacyTradeUseCase.CreateResult`(2/2) | `PrivacyQueryPort.java:19`, `AdminPrivacyTradeUseCase.java:14` |

런타임 설정은 같은 트리(`auth`·`brokers`·`strategies{enabled, fields{ticker, divisionCount, recurringMode, bandWidth, intervalWeeks → customizable/allowedValues/defaultValue}}`·`benchmarks{etf → allowedValues/defaultValue}`)를 5곳에서 따로 선언한다:

| 위치 | 줄 | 내용 |
|---|---|---|
| `adapter/in/web/dto/AdminSettingsRequest.java:26-165` | 165 | Request 쪽 중첩 record 약 9개 |
| `adapter/in/web/dto/RuntimeSettingsResponse.java:20-102` | 102 | Response 쪽 같은 모양 약 9개(`StrategyRequest`=`StrategyResponse`, `FieldRequests`=`FieldResponses`, `BenchmarkFieldRequest`=`BenchmarkFieldResponse` 필드 100% 일치) |
| `domain/model/{RuntimeSettings,RuntimeSettingsBundle,BenchmarkSettings,BenchmarkFieldSettings}.java` | 81 | root 소유 부분(`BenchmarkFieldRequest`=`BenchmarkFieldResponse`=`BenchmarkFieldSettings`, 3중) |
| `shared/.../kernel/TradingPolicySettings.java:12`, `StrategyFieldSettings.java`, `StrategyCreationSettings.java` | 149 | trading 정책 쪽(`:shared`) |
| `adapter/out/persistence/settings/RuntimeSettings*` | 124 | 영속(JSON 컬럼으로 추정, 미확인: 직렬화 방식 미열람) |

합 약 620줄이 한 트리의 변형이다.

### 2-5. 내부 중복

| 로직 | 위치 | 비고 |
|---|---|---|
| "사용자 전량 조회 → id Map" 조인 | `AdminTradeController.java:82`, `AdminObservabilityController.java:89`, `AdminAccountController.java:49`(`UserSummaries.mapById`) | 3곳, 요청마다 전체 사용자 로드 |
| "계좌 전량 조회 → id Map" | `AdminTradeController.java:buildAccountMap`, `AdminQueryService.java:getAnomalies`(계좌 전량) | 2곳 |
| 감사 로그 + 호출 뼈대 | 2-3 참조 | 16곳 |
| `AdminService`의 승인·거절 4개 메서드(웹/텔레그램 × 승인/거절) | `AdminService.java:60-92` | 같은 순서(위임 → 로그 → 감사)를 채널만 바꿔 4번 |

Java와 SQL 양쪽에서 같은 계산: 없음(admin은 집계 SQL이 없다). 기간 필터는 구현이 3가지다(2-7).

### 2-6. 간접층

- `AdminQueryService` 12개 메서드 중 **7개가 다른 빈 호출 한 줄**: `listAccounts`(`:62-65`), `getStrategySummariesByCycleIds`, `listStrategies`, `listStrategiesByAccountIds`, `listStrategyTradeDates`, `listErrorLogs`, `deleteErrorLog`. `listTrades`·`listPrivacyBases`는 기본값 날짜 계산만 더한다.
- 호출처 1곳뿐인 포트: `PortfolioQueryPort`(호출처 `TelegramBotService.java:33,130,156` 1곳, 어댑터 38줄), `TradingSchedulerCommandPort`(컨트롤러 1곳, 7줄 포트·34줄 어댑터).
- 위임 클래스: `RuntimeConfigService`(36줄)·`AdminSettingsService`는 서로 다른 정책(기본값 강등 vs 엄격 조회)을 가져 위임만은 아니다.

### 2-7. 큰 파일(200줄 초과)

없음. 최대 `TelegramBotService` 183줄(주제 묶음 3: 웹훅 분기, 인라인 승인·거절, `/status`·`/history` 조회·포맷), `AdminSettingsRequest` 165줄(선언만).

### 2-8. 비일관성

| 항목 | 이 모듈 | 비교 대상 |
|---|---|---|
| 기간 필터 구현 3종 | `AdminService.filterByDate`(메모리, KST 변환, `AdminService.java:49-58`), 감사 로그(SQL 범위, `AuditLogPersistenceAdapter.java:42`), 계좌(내부 API가 필터, `AdminQueryService.java:62-65`) | 한 모듈 안에서 세 방식 |
| 목록 상한 | 감사 로그 `findAll()` 상한 없음(`AuditLogPort.java:16`), 앱 오류 `findRecent(limit, ...)`(`AppErrorLogPort.java:15`), 거래 기본 최근 30일(`AdminQueryService.java:listTrades`) | 같은 컨트롤러(`AdminObservabilityController`) 안에서 다름. 목록 상한 자체는 `findings/07`이 다룸 |
| 컨트롤러 안의 조립 | 사용자·계좌·전략을 컨트롤러가 3개 UseCase로 모아 조립(`AdminAccountController.java:45-55`, `AdminTradeController.java:78-92`) | `FinanceCategory`는 서비스 조립, trading은 `*Assembler`. 모듈 안에서도 서비스 레이어 조립은 `AdminAnomalies`뿐 |
| 어댑터 위치 | `TelegramBotService`가 `adapter/in/telegram`에서 `PortfolioQueryPort`(아웃바운드 포트)를 직접 사용(`:33`) | `constraints.md` "인바운드 어댑터는 `application.service` 직접 금지"는 지키지만 out-port를 직접 쓴다 |
| 예외 종류 | `Admin*Exception` 4개를 내부 HTTP 오류 변환용으로 자체 정의(`domain/model/`) | 타 모듈은 `ProblemDetailMappings`의 공통 예외로 처리(`constraints.md`). 미확인: 4개가 `GlobalExceptionHandler`에서 실제로 매핑되는지는 열람 안 함 |

### 2-9. 손댈 필요 없음

- 컨트롤러 12개가 평균 57줄이고 200줄 이상 파일이 없다. 서비스 8개가 608줄(평균 76줄)로 작다.
- 감사 로그·앱 오류·런타임 설정 영속은 테이블 3개에 어댑터 3개, 각 52~96줄로 이미 직선적이다.
- 런타임 설정 갱신의 순서·실패 의미(trading 정책 먼저 → root 저장, PUT 멱등)는 `AdminSettingsService` 주석에 명시돼 있고 구조가 단순하다.

### 2-10. 숫자 요약 / 처방 추정 / 고유 문제

- 3,697줄·93파일·엔드포인트 29(127줄/개)·테이블 3, test 4,233줄(1.14배). 내부 HTTP 포트·어댑터 7쌍이 519줄(14%).
- 이미 처방된 양: 05 X1(내부 HTTP 어댑터 7쌍 + contract 대응 삭제) 약 520줄, 01(UseCase 7 + 영속 포트 3) 약 185줄, 02(Response 사본 4 + 설정 Response 일부) 약 150~250줄 → **합 약 850~950줄(추정, 23~26%)**.
- 고유 문제(일반 처방 뒤에도 남는 것):
  1. 런타임 설정 트리 5중 선언 약 620줄(`AdminSettingsRequest.java:26-165`, `RuntimeSettingsResponse.java:20-102`, `shared/.../TradingPolicySettings.java:12`, `StrategyFieldSettings.java`, `domain/model/BenchmarkFieldSettings.java`). 02의 "Request=Command 직접 바인딩"은 이 트리 모양 자체를 줄이지 않는다.
  2. 감사 로그 호출이 서비스마다 수작업 16곳, 액션 문자열 리터럴 분산(`AdminService.java:66-131`, `AdminPrivacyTradeService.java:33-75`).
  3. 사용자·계좌 전량 로드 후 컨트롤러 조인 3곳(`AdminTradeController.java:82`, `AdminObservabilityController.java:89`, `AdminAccountController.java:49`).
  4. 텔레그램 봇이 adapter/in 안에서 사용자 UseCase·관리 UseCase·포트폴리오 포트를 모두 호출하는 183줄 흐름(`TelegramBotService.java:33-183`). 05의 "notify" 이동 처방과 겹치는지 미확인(해당 절 미열람).
  5. 웹/텔레그램 채널별 승인·거절 4개 메서드 중복(`AdminService.java:60-92`).

## 3. `tradingcore.stats` (통계·백테스트·포트폴리오)

백테스트 실행 루프(`BacktestEngine`·`FillSimulator`)는 지침서 03 T7이 다루므로 아래 "백테스트 제외" 수치와 분리한다. 경로 약어: `ST/` = `TC/stats/`.

### 3-1. 규모

| 레이어 | 파일 | 줄 | 비고 |
|---|---|---|---|
| domain/backtest | 7 | 730 | `BacktestEngine` 582(03이 다룸) |
| domain/model | 25 | 618 | 암호화폐 지표 record·순수 계산(`CryptoPremiums` 93·`CryptoHashRibbons` 91·`CryptoValuations` 66·`CryptoMarketIndicators` 115) 포함 |
| application/usecase | 9 | 128 | 인터페이스 8(전부 구현 1개) |
| application/port/output | 9 | 145 | 인터페이스 8, 구현 7개 어댑터(`MempoolBlockHeightAdapter`만 포트 2개 구현) |
| application/service | 9 | 1,399 | 서비스 8 + `CapitalLedgerTimeline` 103 |
| application (루트) | 1 | 335 | `InvestmentIndexCalculator`(public final, 호출처 `InvestmentPointsQueryService`·`BacktestService` 2곳) |
| adapter/in/web (컨트롤러·매퍼·openapi) | 10 | 575 | 컨트롤러 9(522)·`StatsContractMapper` 46·`CryptoMarketIndicatorsOpenApiCustomizer` 53 |
| adapter/in/web/dto | 13 | 644 | `CryptoMarketIndicatorsResponse` 212 |
| adapter/out (시세·지표 HTTP) | 16 | 779 | 어댑터 7(Binance 150·CoinMarketCap 122·Upbit 121·Alpaca 78·Coinbase 77·Mempool 56·Frankfurter 41) + Config/Properties 9 |
| 합계 | 100 | 5,357 | record 선언 112개(47파일), 인터페이스 16. **백테스트 제외 약 4,270줄** |
| test | 32 | 7,841 | main 대비 1.46배. `TradingStatsServiceTest` 1,415(서비스 435의 3.25배)·`BacktestEngineTest` 1,162·`BacktestServiceTest` 580·`InvestmentIndexCalculatorTest` 562·`CryptoMarketIndicatorServiceTest` 527 |

소유 테이블 0개(stats는 Entity·JpaRepository가 없고 다른 모듈의 포트로만 읽는다).

### 3-2. 기능 대비 분량

엔드포인트 16개: 사용자 통계 3(`/api/stats/summary`·`equity-curve`·`cycles`), 계좌 통계 3(`portfolio`·`daily-trades`·`prices`), 대시보드 2(`cycle-history`·`/api/daily-trades`), 암호화폐 포트폴리오 2, 암호화폐 지표 1, 백테스트 1, 내부 4(`investment-points`·`exchange-rate`·`portfolio/current`·`portfolio/history`).
엔드포인트 1개당 main 평균 335줄(5,357/16), 백테스트 제외 약 285줄. test는 평균 490줄.

수직 추적:

| 기능 | 거치는 타입 |
|---|---|
| `GET /api/stats/summary` | `TradingStatsController` → `TradingStatsUseCase` → `TradingStatsService.getSummary`(캐시) → `computeSummary` → `loadCycles` → `AccountPort`·`StrategyPort`·`StrategyCyclePort`·`CyclePositionPort`·`CycleCapitalEventPort`(5개 포트, 전부 다른 모듈 소유) → `toTypeStats`·`settledPnl` → `CapitalLedgerTimeline.openBasisRealized` → `StatsSummary`·`StrategyTypeStats` → `StatsSummaryResponse`. **10타입**(포트 5개와 그 어댑터는 별도) |
| `GET /api/crypto-market-indicators` | `CryptoMarketIndicatorController` → `CryptoMarketIndicatorUseCase` → `CryptoMarketIndicatorService.assemble`(87줄) → 포트 6(`BinanceMarketDataPort`·`CoinbaseMarketDataPort`·`UpbitMarketDataPort`·`CryptoMarketMetricsPort`·`BlockHeightPort`·`HashrateHistoryPort`) → 어댑터 6(+Config 6·Properties 2) → 순수 계산 3(`CryptoPremiums`·`CryptoHashRibbons`·`CryptoValuations`) → `CryptoMarketIndicators`(record 12) → `CryptoMarketIndicatorsResponse`(record 19) + `CryptoMarketIndicatorsOpenApiCustomizer`. 한 응답에 **record 31개** |

### 3-3. `TradingStatsService`(435줄) 내부 구조

메서드와 줄 수(`ST/application/service/TradingStatsService.java`):

| 묶음 | 메서드(줄 수) |
|---|---|
| 캐시·무효화 | `onTradingReportReady`(3, :107)·`onStrategyCapitalChanged`(13, :115)·`invalidateUserCache`(4, :129)·키 record 2 |
| summary | `getSummary`(4)·`computeSummary`(29, :140)·`unrealizedByCycle`(14, :253)·`toTypeStats`(43, :268)·`settledPnl`(5, :314) |
| equity-curve | `getEquityCurve`(5)·`computeEquityCurve`(20, :177)·`buildPoints`(65, :326)·`chainAdjustment`(12, :395) |
| cycles | `getCyclePerformances`(25, :199)·`toPerformance`(22, :408) |
| 공용 | `loadCycles`(22, :229)·`CycleView` 내부 record(19, :82)·`sum`(3) |

한 서비스가 summary·곡선·성과 목록 세 엔드포인트와 캐시 무효화 리스너를 함께 가진다(책임 묶음 5). 같은 계산을 반복하는 블록의 쌍:

| 반복 계산 | 위치 쌍 | 비고 |
|---|---|---|
| "미종료 사이클의 최신 포지션 일괄 조회(비어 있으면 `Map.of()`)" | `TradingStatsService.java:217-221`(`getCyclePerformances`), `:254-258`(`unrealizedByCycle`) | 같은 3줄, 같은 서비스 안. 같은 가드가 trading에도 있다: `TradingPreviewService.java:122`, `StrategyDetailAssembler.java:102`, `CryptoDcaRoundExecutor.java:87` |
| "계좌 → 전략 → 사이클 → 자본 이벤트 적재 체인" | `TradingStatsService.java:229-251`(`loadCycles`), `InvestmentPointsQueryService.java:55-83` | 두 서비스가 같은 4단 조회를 따로 짠다(필터만 다름: MOCK 제외·통화) |
| "사이클 종료 여부로 갈라 진행 중/종료 분할" | `TradingStatsService.java:165,217,254,271-272` | 5곳(`v.closed()`/`!v.closed()`) |
| "MOCK 제외 + 통화 필터" | `TradingStatsService.java:141,184`, `InvestmentPointsQueryService.java:50` | 3곳 |

### 3-4. `TradingStatsService`와 `InvestmentIndexCalculator`(335줄)의 중복

`InvestmentIndexCalculator` 메서드(줄 수): `calculate`(20)·`buildDailyValuations`(61, :51)·`buildExternalFlows`(33, :113)·`addCapitalEventFlows`(27)·`addEntryFlow`(7)·`addExitFlow`(10)·`buildCompleteValuesByCycle`(28, :196)·`compoundDailyReturns`(34, :226)·`activeCycleOn`(25, :267)·`isContiguousTransition`(3)·`entryValue`·`entryDate`·`lastCompleteValue`·`compareCycles`(9, :320)·`laterPosition`(3)·`sumFlows`(5). 책임 묶음 3: 일별 평가액 시계열, 외부 현금흐름(진입·교체·종료·입출금), 시간가중 복리.

두 클래스는 "사이클 스냅샷을 KST 일별로 묶어 carry-forward하고, 같은 전략의 사이클 교체를 처리해 일별 합계를 만든다"는 같은 개념을 서로 다르게 구현한다(누적자산 곡선 vs 투자지수):

| 개념 | `TradingStatsService` | `InvestmentIndexCalculator` | 차이 |
|---|---|---|---|
| 스냅샷 KST 일자 묶기(날짜별 사이클당 마지막 값) | `:339-343`(`put`, 입력이 `createdAt`·id 정렬이라 마지막이 이김, `:155` 정렬 의존) | `:56-65`(`merge(laterPosition)`), `:205-213`(`merge(laterPosition)` 또 한 번) | 3블록, 마지막을 고르는 규칙이 2종(정렬 의존 vs `createdAt` 비교, 동시각 `id` 비교 없음) |
| 사이클 신구 비교 | `:72-73` `NEWER_CYCLE`(`createdAt`, `id`) | `:320-328` `compareCycles`(`nullsFirst(createdAt)`, `id`) | 같은 의도, null 처리만 다름. `CapitalLedgerTimeline.java:48`은 포지션을 `createdAt`만으로 정렬 |
| 교체된 앞 사이클의 처리("어느 사이클이 그날 유효한가") | `:328-333` `hasSuccessor`, `:355-359` 체인 `TreeSet`, `:372-394` 종료일·후속 여부 분기 | `:267-292` `activeCycleOn`, `:293` `isContiguousTransition` | 같은 질문에 두 규칙. 곡선은 "후속이 생길 때까지 마지막 값 유지 + 체인 보정", 지수는 "종료일 당일만 유지" |
| 총자산 평가 | `CyclePosition.totalAssetWithAvgPriceFallback()`(`:262,379,412`) | `CyclePosition.totalAssetAtCloseOrNull()`(`:93,215`) | 종가 없을 때 평단가로 대체 vs 불완전 처리(`trading/domain/model/CyclePosition.java:56,65`). 의도된 차이(화면 표시 vs 수익률)로 주석에 명시 |

같은 사이클·스냅샷 입력으로 두 계산이 다른 날 다른 값을 낼 수 있다. 통합 가능성 판단은 설계 몫(미확인: 두 결과를 대조하는 테스트가 있는지는 열람 안 함).

### 3-5. 평행 구조·타입 복제

| 묶음 | 구성원 | 줄 | 다른 부분 |
|---|---|---|---|
| 외부 시세 어댑터 + Config | Binance·Coinbase·Upbit·CoinMarketCap·Mempool·Frankfurter·Alpaca | 어댑터 645 + Config·Properties 134 | Config 7개 중 5개(`BinanceConfig`·`CoinbaseConfig`·`MempoolConfig`·`UpbitMarketDataConfig`·`FrankfurterConfig`)가 16줄 본문 `RestClients.withTimeouts(3_000, 7_000)` 한 줄(`ST/adapter/out/coinbase/CoinbaseConfig.java:14` 등). Alpaca 설정은 3벌이 이미 02 T8 대상 |
| 암호화폐 지표 domain↔Response 미러 | domain 12 record + Response 19 record | 115 + 212 | 필드 100% 일치 쌍 14(`CryptoMarketIndicators`=`CryptoMarketIndicatorsResponse` 7/7, `TickerIndicators`=`TickerResponse` 7/7, `DatedHashRibbon`=`DatedHashRibbonResponse` 6/6, `Halving`, `FundingRate`, `LongShortRatio`, `DatedPremiumPair` 등) |
| 통계 결과 domain↔Response 쌍 | `StatsSummary`=`StatsSummaryResponse`(5/5), `CyclePerformance`=`Item`(12/12), `CyclePerformancePage`=`CyclePerformancePageResponse`(4/4), `EquityPoint`=`Point`(3/3), `BacktestResult`=`BacktestResponse`(4/4) | 합 약 250 | 02의 View 직행 처방 대상 |
| 내부 컨트롤러용 contract 변환 | `StatsContractMapper`(46줄) | 46 | 05가 다룸 |

### 3-6. 간접층·큰 파일·비일관성

- 호출처 1곳뿐: `BrokerStatisticsUseCase`·`BrokerStatisticsService`(25줄, 메서드 1개 `currentExchangeRate`(6)). `PortfolioService`(56)는 내부 컨트롤러 1곳(`PortfolioQueryInternalController`)만 호출한다.
- 200줄 초과 파일: `BacktestEngine` 582(03), **`TradingStatsService` 435(책임 5묶음)**, **`InvestmentIndexCalculator` 335(3묶음)**, `BacktestService` 240(검증 3·조립 2·실행 1), `CryptoMarketIndicatorService` 216(`assemble` 87줄 한 메서드), `CryptoMarketIndicatorsResponse` 212.
- 페이지네이션 3종: `/api/stats/cycles`는 **전량 적재 후 메모리 `subList`**(`TradingStatsService.java:199-227`, 오프셋·`totalElements`), `/api/accounts/{id}/cycle-history`는 커서(`AccountStatisticsService.java:67-75`, `size+1` 조회), 나머지 14개는 페이지 없음. 07·10 T4가 `/stats/cycles` 쿼리 수준 페이징을 이미 처방.
- 소유권 검증: `AccountStatisticsService`·`CryptoPortfolioService`는 `accountPort.requireOwnedAccount`(`AccountStatisticsService.java:46,54,94,103`), `InvestmentPointsQueryService.java:55`는 `strategyPort.findByIdOrThrow` 후 `requireOwnedAccount`. `architecture.md`가 "stats는 모듈 경계상 `OwnedStrategy`를 못 쓴다"고 명시한 의도된 예외다.
- 금액 반올림: `Currency.round`로 통일(`TradingStatsService.java:432`, `AccountStatisticsService.java:82`, `CryptoPortfolioService.java:85`). 비율의 자릿수는 4·6·1·10 등 리터럴이 흩어져 있으나 `constraints.md`가 비율은 별개라고 규정.
- `/api/daily-trades`(`DashboardController`)와 `/api/accounts/{id}/daily-trades`(`StatisticsController`) 같은 결과 타입을 두 컨트롤러가 나눠 가진다(`AccountStatisticsService.getDailyTransactions`·`getDailyTransactionsForUser` 두 메서드 + 공용 `toDailyTransactionResult`).

### 3-7. 손댈 필요 없음

- `AccountStatisticsService`(128줄, 메서드 9개 평균 9줄)·`PortfolioService`(56)·`CryptoPortfolioService`(107)·`InvestmentPointsQueryService`(89)·`CapitalLedgerTimeline`(103)는 책임이 하나씩이고 길이가 짧다.
- 포트는 8개 모두 외부 시세·지표(HTTP) 포트로 어댑터가 실제 외부 구현이다(`MempoolBlockHeightAdapter`는 포트 2개). 영속 포트가 없어 01·02의 Store 처방 대상이 아니다.
- 소유권·`BrokerCallGuard.wrap`·`Currency.round` 사용은 `constraints.md` 규칙과 일치한다.

### 3-8. 숫자 요약 / 처방 추정 / 고유 문제

- 5,357줄·100파일·엔드포인트 16(335줄/개)·테이블 0. 백테스트 제외 약 4,270줄. test 7,841줄(1.46배).
- 01·02 일반 처방으로 줄 추정량: UseCase 8파일 약 125줄 + 포트 중 영속 아닌 8개는 유지 + 통계·암호화폐 Response 사본(필드 100% 일치 약 19쌍) 약 300~400줄 + `StatsContractMapper` 46(05) → **약 450~550줄(추정, 약 10%)**. 백테스트 3벌 약 200줄은 03이 이미 처방.
- 고유 문제:
  1. 같은 "일별 사이클 스냅샷 carry-forward·교체 처리" 두 구현과 마지막 스냅샷 고르는 규칙 3블록(`TradingStatsService.java:326-393`, `InvestmentIndexCalculator.java:51-111,196-224,267-292`). 사이클 신구 비교 4형태(`TradingStatsService.java:72`, `InvestmentIndexCalculator.java:320`, `CapitalLedgerTimeline.java:48`, `TradingStatsService.java:155`).
  2. `TradingStatsService`가 한 파일에 책임 5묶음 435줄(테스트 1,415줄)이고, 사이클 적재 체인이 `InvestmentPointsQueryService.java:55-83`와 중복(`TradingStatsService.java:229-251`).
  3. `/stats/cycles`가 전량 적재 후 메모리 페이징(`TradingStatsService.java:199-227`).
  4. 암호화폐 지표 한 응답이 domain 12 + Response 19 record 미러(`CryptoMarketIndicators.java`, `CryptoMarketIndicatorsResponse.java:22-212`)와 springdoc 이름 충돌 보정용 `CryptoMarketIndicatorsOpenApiCustomizer`(53줄)를 유발.
  5. 시세 어댑터 Config 5개가 같은 한 줄 빈(`ST/adapter/out/*/…Config.java`), `CryptoMarketIndicatorService.assemble` 87줄 단일 메서드(`ST/application/service/CryptoMarketIndicatorService.java`).

## 4. `api.notify` + `tradingcore.notify` (알림 게이트웨이·리스너)와 알림 문구

경로 약어: `AN/` = `API/notify/`, `TN/` = `TC/notify/`. `notify` 두 모듈은 합쳐 50파일 1,798줄로 작다. 문제는 크기가 아니라 **문구가 모듈 밖에서 만들어진다는 점과 같은 발송 골격의 복제**다.

### 4-1. 규모

| 구분 | 파일 | 줄 | 비고 |
|---|---|---|---|
| `api.notify` 합계 | 33 | 1,065 | domain 2(24)·port 5(40)·web 4(117, dto 11)·adapter/out 20(823: gateway 14, sse 3·164, persistence 3·126)·adapter/in/redis 1(58) |
| `tradingcore.notify` 합계 | 17 | 733 | port 3(72)·gateway 13(657). domain·web·persistence 없음 |
| test | 34 | 2,507 | api 20파일 1,319 + trading-core 14파일 1,188. main 대비 1.39배 |

record 선언 2개(api)·0개(tc), 인터페이스 api 5(`UserNotificationPort`·`NotifyPort`·`PushNotificationPort`·`FcmDeviceTokenPort`·`FcmDeviceTokenJpaRepository`)·tc 3. 구현 2개 이상: `UserNotificationPort`(Telegram·Fcm·Composite 3개, 유일한 다형성). 나머지 포트는 구현 1개.
엔드포인트 4개(`POST/DELETE /api/fcm/tokens`, `GET /api/trades/stream`(SSE), `GET /api/auth/status-stream`(SSE)), 테이블 1개(`fcm_device_tokens`). 엔드포인트 1개당 api 평균 266줄이지만 대부분이 게이트웨이(진입점이 이벤트 리스너 31개)다.

### 4-2. 문구를 만드는 곳 전수

**(a) 사용자·관리자에게 가는 고정 문구 클래스**

| 클래스 file | 메서드 수 | 문구 종류 | 비고 |
|---|---|---|---|
| `TN/adapter/out/gateway/TradingUserNotificationAdapter.java`(180줄) | 13 | 16(매매 결산·사이클 종료·새 사이클·전략 자동 일시정지·로테이션 실패·예수금 부족 3변형(매도/증권사 거절/원장 상한)·매매 오류·배치 중단·장 개시·장 마감·DCA 미주문·코인 체결·API 키 만료 2변형) | 텔레그램 HTML 문구. FCM은 `toPlainText`(`:154-156`)로 태그를 지워 재사용 |
| `AN/adapter/out/gateway/TelegramUserNotificationAdapter.java`(57) | 4 | 신규 가입(버튼)·자동 승인·승인·거절 | `callback_data`("approve:"+uuid)를 여기서 만들고 admin이 파싱한다(`TelegramBotService.java:69-95`) |
| `AN/adapter/out/gateway/FcmAdapter.java`(73) | 2 | 승인·거절을 **다시 한 번 리터럴로** 가짐(`:30-37`) | 텔레그램 "가입 신청이 거절되었습니다." vs FCM "가입이 거절되었습니다." — 이미 문구가 갈라졌다 |
| `AN/adapter/out/gateway/TelegramAdapter.java`(42) | 1 | 관리자 오류 템플릿 `"<b>⚠️ 관리자 알림</b>%n%s"` | |
| `TN/adapter/out/gateway/TradingNotifyAdapter.java`(62) | 3 | 같은 관리자 오류 템플릿(중복)·잔고 부족·휴장 | |
| `AN/adapter/out/gateway/SchedulerNotifier.java`(27) | 3 | `"작업명 시작/완료"`·`"[작업명] 오류"` 인라인 | |
| `AN/adapter/out/gateway/UserDeletedNotifier.java`(21) | 1 | `"[userId=…] 사용자 cascade 삭제 완료"` 인라인 | |
| `TN/adapter/out/gateway/OrderCancelFailureNotifier.java`(25) | 1 | 취소 실패 문장 인라인 | |
| `API/admin/adapter/in/telegram/TelegramBotService.java`(183) | 약 8 | `/help`·`/status` 블록·`/history`·미연결·데이터 없음·`/run`·알 수 없는 명령 | notify 모듈이 아니라 admin에 있다 |
| `API/finance/application/service/FinanceRegistrationReminderNotifier.java`(83) | 1 | 제목 상수 + `"N월 가계부(…) 등록이 아직 없어요…"` 본문을 **finance가 만들어** 이벤트에 싣는다 | notify는 전달만(`FinanceReminderChannelNotifier.java:20`) |

고정 템플릿은 사용자 대상 약 23개(tc 16 + api 7) + 관리자 대상 약 14개(오류 템플릿 중복 포함)다.

**(b) 호출처가 문구를 직접 만들어 이벤트·포트에 싣는 것(notify는 `e.getMessage()`만 전달)**

| 이벤트 | 생성 지점 수 | 위치 |
|---|---|---|
| `TradingErrorEvent(null\|userId, String message)` | **38곳, 16개 파일** | `VrCycleRolloverService` 9, `OrderSubmitter` 6, `TradingService` 3, `TradingOrderExecutor` 3, `TradingBatchGuard` 2, `TradingReporter` 2, `TradingPriceFetcher` 2, `PrivacyBaseGuard` 2, `DcaExecutionRecorder` 2, 그 외 7파일 각 1(`TC/trading/application/service/…`) |
| `PrivacyAlertRaisedEvent` | 2 | `TC/privacy/application/service/PrivacyService.java:31,36` |
| `BenchmarkAlertRaisedEvent` | 2 | `API/benchmark/application/service/HousingBenchmarkService.java:39,52` |
| `FearGreedFetchFailedEvent` | 1 | `API/market/application/service/FearGreedService.java:37` |
| `OrderCancelFailedEvent` | 1 | `TC/trading/application/service/OrderCancelService.java:155` |
| `notifyPort.notifyError(...)` 직접 호출 | 1 | `API/web/UserEventResubmitScheduler.java:54` |
| 사용자 노출 예외 문구에 금액 포맷 | 1 | `TC/trading/application/service/StrategyService.java:192`(`$%.2f`) |

관리자 알림 문구가 **서비스 코드 38+7곳에 자유 서식**으로 박혀 있다. `[PRIVACY]`·`[접수 결과 불명]`·`[DCA 개장 전 접수]`처럼 접두사 규칙이 호출처마다 다르다. 메시지 종류 카탈로그나 코드값 enum이 없다.

### 4-3. 같은 포맷을 따로 구현한 곳

| 포맷 | 구현들 | 비고 |
|---|---|---|
| 금액(USD) | `$%.2f` 리터럴 6곳: `TN/…/TradingUserNotificationAdapter.java:35,59,69`, `TradingNotifyAdapter.java:49`, `TC/trading/application/service/StrategyService.java:192`, `TC/stats/domain/backtest/BacktestEngine.java:167`(`Locale.US "$%,.2f"`) | 로케일 미지정(알림 5곳)과 `Locale.US`+천 단위 구분(백테스트)이 섞였다 |
| 금액(통화별) | `Currency.format`(`shared/.../kernel/Currency.java:31`, `Locale.ROOT`·천 단위, KRW "15,000원"·그 외 "10.50 USDT") — 사용처는 코인 체결(`TradingUserNotificationAdapter.java` `notifyCryptoTrade`)과 `TelegramBotService.java:136-139,163`뿐 | 같은 사용자의 주식 알림은 `$1234.50`, 봇 `/status`는 `1,234.50 USD` — 같은 금액이 두 표기 |
| 수량 | `Quantities.normalize(...).toPlainString()`(봇), `e.filledQuantity().toPlainString()`(코인 체결), `%d`(예수금 부족 건수) | 정규화 경로가 알림마다 다름 |
| 날짜 | `report.date()`·`e.tradeDate()`·`e.expiresOn()`을 그대로 문자열화, `month.getMonthValue() + "월"` | 날짜 포맷 헬퍼 없음, 전부 ISO 또는 임의 조합 |
| 계좌·전략 표기 | 계좌는 별명만 `escapeHtml(accountNickname)`(`TradingUserNotificationAdapter.java` 12곳)이고 `AccountNumberMasker`는 알림에서 안 씀. 전략 표기 순서가 문구마다 다름: `[전략유형] 종목`(사이클 종료·새 사이클) vs `계좌 종목 전략유형`(예수금 부족) vs `종목 규칙/방향`(DCA). 전략 유형·종목은 `.name()` 영문, DCA 방향·증권사·사유는 `.getLabel()` 한글 | 같은 알림 모듈 안에서 라벨 규칙이 이원화 |
| HTML 이스케이프 | `TelegramHttpClient.escapeHtml`을 문구 클래스가 사용처마다 호출(tc 13곳·api 4곳). 빠뜨리면 텔레그램 400으로 알림이 유실된다고 `TradingUserNotificationAdapter.java:149` 주석이 경고 | 이스케이프가 호출자 책임 |

### 4-4. 두 프로세스 사이 중복

| 중복 | 위치 | 줄 |
|---|---|---|
| 관리자 오류 알림 발송(오류 보고 이벤트 발행 try/catch → `"<b>⚠️ 관리자 알림</b>%n%s"` 전송 + `send` 헬퍼) | `AN/adapter/out/gateway/TelegramAdapter.java:21-41` ≈ `TN/adapter/out/gateway/TradingNotifyAdapter.java:30-61` | 각 약 20, 문구까지 같음 |
| 포트 재정의: root `NotifyPort`(`notifyError`·`notifyInfo`)와 `TradingNotifyPort`(+2 메서드), root `UserNotificationPort`의 매매 부분과 `TradingUserNotificationPort`(13 메서드) | `AN/application/port/output/NotifyPort.java`, `TN/application/port/output/TradingNotifyPort.java:17`, `TradingUserNotificationPort.java:44` | 주석이 "root 판을 trading-core가 좁혀 재정의"라고 인정 |
| 사용자 알림 수신자 투영 | `AN/domain/model/NotificationRecipient.java`(`hasTelegramBot()`) ≈ `TC/trading/domain/model/TradingUserProfile`(`telegramEnabled()`) | 05 X6이 복제본 제거 대상으로 이미 지정 |
| 텔레그램 발송 골격 `TelegramHttpClient.sendMessage(chatId, text, botToken)` + "연결된 경우에만" 게이트 | `TelegramUserNotificationAdapter.java:50-56`(`sendIfLinked`), `TradingUserNotificationAdapter.java:160-164`(`sendIfLinked`), `UserChannelNotifier.java:48-54` | 3곳, 게이트 조건이 `hasTelegramBot()` vs `telegramEnabled()` vs `includesTelegram() && hasTelegramBot()`로 다름 |

### 4-5. 평행 구조

| 묶음 | 구성원 | 줄 | 다른 부분 |
|---|---|---|---|
| tc "이벤트 → 프로필 재조회 → 포트 호출" 리스너 메서드 | `TradingAlertNotifier` 7(`:35-94`), `CycleLifecycleNotifier` 2, `CycleEndedNotifier` 1, `ApiKeyExpiryNotifier` 1, `TradingReportNotifier` 1, `CryptoTradeNotifier` 1 | 리스너 클래스 9개 약 330줄(`TradingAlertNotifier` 94·`TradingReportNotifier` 57·`CryptoTradeNotifier` 41·`CycleLifecycleNotifier` 36·`CycleEndedNotifier` 29·`PrivacyAlertNotifier` 27·`OrderCancelFailureNotifier` 25·`ApiKeyExpiryNotifier` 22 + `TradingUserProfiles` 22) | 메서드마다 `TradingUserProfiles.requireProfile(...)` + 포트 호출 한 줄. 이벤트 필드 → 포트 인자 매핑만 다름. 같은 `notifyCycleCompleted`를 서로 다른 이벤트 2개(`CycleCompletedEvent`·`CycleEndedEvent`)가 부른다(`CycleLifecycleNotifier.java:26`, `CycleEndedNotifier.java:25`) |
| api "이벤트 → 관리자 봇" 리스너 | `AlertNotifier` 2, `SchedulerNotifier` 1, `UserDeletedNotifier` 1 | 77 | 메시지 문자열 한 줄 |
| `TradeEventMessage.sell/buy` 삼항(9개 인자) | `TradingReportNotifier.java:48-53`, `CryptoTradeNotifier.java:32-35` | 각 6 | 2곳 |
| SSE 레지스트리 2개 | `AN/adapter/out/sse/SseEmitterRegistry.java`(52, 사용자당 emitter 1개·timeout 0) ≈ `TradeSseEmitterRegistry.java`(70, 사용자당 리스트·timeout 30분·ping) | 122 | `connect`·정리 콜백 골격이 같고 동시 연결·타임아웃 정책이 다름(09가 프로세스 로컬 상태로 다룸) |
| 리스너 애너테이션 형태 | `@TransactionalEventListener(fallbackExecution = true)` 18, `(phase = AFTER_COMMIT)` 9, `(phase = AFTER_COMMIT, fallbackExecution = true)` 1, `@EventListener` 1 | 리스너 31개 | 같은 "알림 발송" 역할에 4형태. 클래스 주석이 형태를 고른 이유를 클래스마다 다시 설명(`AlertNotifier`·`SchedulerNotifier`·`TradingAlertNotifier`) |

### 4-6. 채널별 경로 길이(이벤트 발행 → HTTP 호출까지 거치는 클래스)

수치는 대표 경로 1건 기준이며 이벤트 record와 프레임워크 클래스는 세지 않았다.

| 채널·경로 | 거치는 클래스 | 수 |
|---|---|---|
| 사용자 텔레그램(매매, tc) | 리스너(예 `CycleLifecycleNotifier`) → `TradingUserProfiles` → `TradingUserProfilePort` → `UserNotifyProfilePersistenceAdapter` → `TradingUserNotificationPort` → `TradingUserNotificationAdapter` → `TelegramHttpClient` → HTTP | 7 |
| 사용자 FCM(매매, 프로세스 2개) | 위 5개 → `RedisPushNotificationPublisher` →(Redis Stream)→ `PushNotificationStreamConsumer`(+`RedisStreamSubscriber`) → `PushNotificationPort` → `UserPushNotificationAdapter` → `UserPort` → `FcmAdapter` → `FcmDeviceTokenPort`·어댑터 → Firebase HTTP | 약 13 |
| 사용자 SSE(체결, 프로세스 2개) | `TradingReportNotifier` → 프로필 3종 → `TradingRealtimeNotificationPort` → `RedisTradeEventPublisher` →(Redis Pub/Sub)→ `RedisTradeEventSubscriber` → `TradeSseEmitterRegistry` → `TradeStreamController`(연결 보유) | 약 9 |
| 관리자 텔레그램(tc) | `TradingAlertNotifier` → `TradingNotifyPort` → `TradingNotifyAdapter` → `TelegramHttpClient` | 4(+오류 보고: `AppErrorRaisedEvent` → `AppErrorStreamPublisher` → Redis → `AppErrorStreamConsumer` → `AppErrorRaisedListener` 4개 더) |
| 관리자 텔레그램(api: 시세·벤치마크·스케줄러) | `AlertNotifier`/`SchedulerNotifier` → `NotifyPort` → `TelegramAdapter` → `TelegramHttpClient` | 4 |
| 가입 승인·거절·신규 가입 | `CompositeUserNotificationAdapter`(리스너+라우터) → `TelegramUserNotificationAdapter` → `TelegramHttpClient` (+`FcmAdapter` → `FcmDeviceTokenPort`·어댑터) | 3 (FCM 포함 6) |
| 가계부 리마인더 | `FinanceRegistrationReminderScheduler` → `FinanceRegistrationReminderNotifier`(문구 조립) →(이벤트)→ `FinanceReminderChannelNotifier` → `UserChannelNotifier` → `TelegramHttpClient`/`PushNotificationPort` → `UserPushNotificationAdapter` → `FcmAdapter` | 약 8 |

### 4-7. 간접층·큰 파일·비일관성

- 호출처 1곳뿐/위임: `FinanceReminderChannelNotifier`(23줄, 본문 1줄이 `UserChannelNotifier.notify` 위임), `NotificationRecipients`(16)·`TradingUserProfiles`(22) 변환 헬퍼, `CompositeUserNotificationAdapter.notifyNewUser/notifyAutoApprovedUser`(텔레그램 직행 위임 2개), `FcmAdapter.notifyNewUser/notifyAutoApprovedUser`(본문 없는 빈 구현 2개, `:19-26`).
- 200줄 초과 파일: 없음. 최대 `TradingUserNotificationAdapter` 180줄(문구 클래스 한 개, 메서드 13개).
- 사용자 알림 채널 판정 2곳: `UserChannelNotifier.java:41-52`(텔레그램 `includesTelegram()`, FCM은 `UserPushNotificationAdapter.java:30`이 `includesFcm()`)와 `TradingUserNotificationAdapter.java:160`(`profile.telegramEnabled()`) — 05 X7이 이미 지정.
- 알림 유형별 on/off 게이트: `UserChannelNotifier.java:40`은 `isNotificationEnabled(type)`을 서비스가, tc는 호출처가 제각각 건다(`CryptoTradeNotifier.java:25` `TRADING_ALERT` 확인, `TradingReportNotifier.java:36` `event.reportEnabled()`, `ApiKeyExpiryNotifier.java`는 "설정 무관 발송"을 주석으로 명시, `TradingAlertNotifier`는 게이트 없음).
- 주석 드리프트: `TradingRealtimeNotificationPort.java:5`("지금은 인터페이스만 존재")·`TradingUserNotificationPort.java:4`("구현체는 후속 태스크가 채운다")·`TradingNotifyPort.java:9`("브리핑 문서는 BigDecimal로…")는 구현이 끝난 뒤에도 남은 이관 작업 메모다.

### 4-8. 손댈 필요 없음

- `UserChannelNotifier`(55줄)는 "수신자 조회 → 유형별 게이트 → 텔레그램·FCM 각 leg 격리"를 한 곳에 모은 모범 구조이고, 가계부 리마인더가 이 위로 얇게 얹혀 있다.
- `UserPushNotificationAdapter`(36줄)는 FCM 채널 판정의 단일 지점이다(`PushNotificationPort` 호출처 2곳이 공유).
- `UserNotificationPort`는 구현 3개로 실제 다형성이 있다. `fcm_device_tokens` 영속(3파일 126줄)·SSE 컨트롤러 2개(63줄)는 단순하다.
- 파일 200줄 초과 0개, api 평균 32줄/파일.

### 4-9. 숫자 요약 / 처방 추정 / 고유 문제

- 두 모듈 합 50파일 1,798줄(test 2,507줄, 1.39배), 엔드포인트 4·테이블 1·이벤트 리스너 31. 사용자 대상 고정 문구 약 23종, 관리자 대상 고정 문구 약 14종 + 호출처 인라인 문구 45곳. 채널 경로 길이 3~13클래스.
- 01·05 처방으로 줄 추정량: 01(1구현 포트 `NotifyPort`·`PushNotificationPort`·`FcmDeviceTokenPort`·`TradingNotifyPort`·`TradingUserNotificationPort`·`TradingRealtimeNotificationPort` 약 99줄) + 05 X7(`RedisPushNotificationPublisher` 35·`PushNotificationStreamConsumer` 58·`UserPushNotificationRequestedEvent` 8 + 프로필 복제본 소비) 약 100줄 + 05의 포트 이중화 해소 → **약 250~350줄(추정, 14~19%)**. 문구·포맷 문제는 위 처방이 전혀 건드리지 않는다.
- 고유 문제(일반 처방 뒤에도 남는 것):
  1. 관리자 알림 문구가 서비스 38+7곳에 자유 서식(`VrCycleRolloverService` 9, `OrderSubmitter` 6 등 `TC/trading/application/service/*`)이고 카탈로그·코드값이 없다. 사용자 고정 문구는 `TradingUserNotificationAdapter.java:30-150` 한 클래스에 13개 메서드로 몰려 있다.
  2. 포맷 헬퍼 부재: 금액 `$%.2f` 6곳과 `Currency.format` 사용처가 공존(`TradingUserNotificationAdapter.java:35,59,69` vs `TelegramBotService.java:136-139`), 날짜·수량·전략 표기·이스케이프가 호출처 책임.
  3. 같은 문구가 채널마다 따로 존재하고 이미 갈라졌다: 승인·거절(`TelegramUserNotificationAdapter.java:44-52` vs `FcmAdapter.java:30-37`), 관리자 오류 템플릿(`TelegramAdapter.java:35` vs `TradingNotifyAdapter.java:44`).
  4. 발송 골격 복제: 텔레그램 `sendIfLinked`·채널 게이트가 3곳(`TelegramUserNotificationAdapter.java:50`, `TradingUserNotificationAdapter.java:160`, `UserChannelNotifier.java:48`), 알림 유형 설정 게이트가 호출처마다 다름(위 4-7).
  5. 알림 문구가 모듈 경계 밖에 있다: 가계부 리마인더 문구는 finance(`FinanceRegistrationReminderNotifier.java:42`), 관리자 봇 응답은 admin(`TelegramBotService.java:111-165`), `callback_data` 프로토콜은 notify가 만들고 admin이 해석(`TelegramUserNotificationAdapter.java:26-27` ↔ `TelegramBotService.java:69-95`).
