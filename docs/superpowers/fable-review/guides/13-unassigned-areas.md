# 13. 지정하지 않은 영역: 손댈 곳과 손대지 않을 곳

작성 2026-11-13 (Fable). 근거는 `findings/17-unassigned-modules.md`(finance·admin·stats·notify)와 `findings/18-crosscutting.md`(보안·설정·이벤트·오류 처리·미사용 코드), 그리고 코드 직접 확인(`JwtAuthFilter`, `TradingStatsService.buildPoints`, `InvestmentIndexCalculator`). 기준 커밋 `1db0d8db1`.

> **2026-11-14 갱신**: 코인 지표·바이낸스·업비트는 삭제된다(`15-crypto-removal.md`). stats 절의 코인 지표 View 직행과 `CryptoMarketIndicatorsOpenApiCustomizer`, 설정 정리의 `binance.base-url`·`upbit`·`CMC_API_KEY`, 알림의 "코인 체결" 표기 갈래는 하지 않는다. `TossPriceApi`의 세션 판정 복제는 14의 T5에서 사라진다.

지침서 01~10이 직접 다루지 않은 9개 영역을 본 결과다. 영역마다 "01·02·05의 일반 처방으로 줄어드는 것"은 다시 처방하지 않는다. 그 처방을 적용한 뒤에도 남는 **그 영역만의 문제**와, **손댈 필요가 없다는 판정**을 근거와 함께 적는다.

## 0. 한눈에

| 영역 | 규모(main) | 일반 처방으로 줄 양 | 판정 | 고유 처방으로 더 줄 양(추정) |
|---|---|---|---|---|
| `api.finance` | 5,024 | 600~700(01·02) | 손댄다(작게). 뼈대 반복 3종 | 약 250 |
| `api.admin` | 3,697 | 850~950(01·02·05 X1) | 손댄다. 런타임 설정 5중 선언 | 약 450 |
| `tradingcore.stats` | 5,357 | 450~550(01·02) + 백테스트(03) | 손댄다. 같은 개념의 두 구현 — 정합성 문제 | 약 150 |
| `notify` 양쪽과 알림 문구 | 1,798 | 250~350(01·05) | 손댄다. 문구·포맷·발송 골격 | 약 300 |
| 보안·JWT·인증 | 381(shared) + user 모듈 | — | 구조는 손대지 않는다. 결함 1건과 작은 수정 5건 | — |
| 설정 파일 | yml 5개 373줄 | 05에서 한 벌로 | 손대지 않는다. 작은 수정 4건 | — |
| 이벤트·EPR | 이벤트 32, 리스너 41 | 스트림 4개는 05 | 구조는 손대지 않는다. 선언 방식 통일 | — |
| 오류 처리·`BrokerCallGuard` | 예외 36, `ErrorCode` 14 | 처리기 통합은 05 P1 | 구조는 손대지 않는다. 불일치 4건 | — |
| 메서드·필드 미사용 코드 | 완전 미사용 5건, test 전용 7건 | — | 정리 한 번 | 약 60 |

네 모듈 공통으로 200줄 넘는 파일이 거의 없고(stats 6개뿐), 진입점 없는 서비스는 0개, 리스너·발행처가 없는 이벤트도 0개, 바인딩 없는 설정 키도 0개다. 죽은 코드가 쌓인 코드베이스가 아니다. 남은 문제는 "같은 것을 여러 번 적었다"에 몰려 있다.

## 1. `api.finance` (가계부)

### 문제

5개 애그리게이트(거래·자산 기록·예산·계좌·카테고리)가 같은 뼈대 11파일씩 3,093줄로 모듈의 62%다. 01(UseCase 제거)·02(Request=Command 5쌍 직접 바인딩, 조회 View 직행)가 이 중 600~700줄을 줄인다. 그 뒤에 남는 것:

1. `shareToGroup`·`unshare`가 서비스 5곳(`FinanceTransactionService.java:90-116`, `AssetSnapshotService.java:91-121`, `FinanceAccountService.java:79-103`, `FinanceBudgetService.java:149-173`, `FinanceCategoryService.java:75-106`)에 같은 순서로 있다. 다른 것은 가드 한두 개와 문구뿐이다.
2. 도메인 record에 `with*`가 없어 `update`와 공유 전환이 필드 8~12개를 매번 다시 나열한다(5곳).
3. 삭제된 행을 다루는 방식이 4가지다(`@SQLRestriction` 5개, 일부러 뺀 2개, 하드 삭제 1개, 삭제 없음 1개). 계좌·카테고리 포트만 `findByIdOrThrow`와 `findActiveByIdOrThrow` 둘을 두어 서비스가 매번 골라야 하고, 잘못 골라 삭제된 카테고리가 살아난 적이 있다(`FinanceCategoryService.java:59-61` 주석).
4. 컨트롤러가 out-port를 직접 불러 응답을 한 건씩 보강한다(`AssetSnapshotController.java:123-145`, `FinanceGroupController.java:56`).
5. 카테고리 트리 조립이 두 컨트롤러에 있다(`FinanceCategoryController.java:35-58`, `AdminFinanceCategoryController.java:40-58`).

### 목표

- **공유 전환을 한 곳에**: 이미 있는 `GroupShareSupport`(49줄)가 "조회 → 권한 → 가드 → `withGroupId` → 저장 → 로그"를 통째로 하고, 서비스는 자기 가드만 넘긴다. 5개 도메인 record가 `GroupShareable`에 `withGroupId(UUID)`를 구현한다.
- **`with*` 추가**: 02 뒤에는 Command가 곧 입력이므로 `existing.with(command)` 한 줄이 된다.
- **삭제된 행 읽기는 조회 View의 일**: 계좌·카테고리에서 `@SQLRestriction`을 뺀 이유는 "삭제된 계좌·카테고리의 이름을 남은 거래가 보여줘야 한다" 하나다. 02의 View 직행 뒤에는 거래·자산 목록 View가 SQL 조인으로 이름을 직접 읽는다. 그러면 Entity는 다른 가계부 Entity와 같이 `@SQLRestriction`을 달고 `findActive*` 쌍이 사라진다. 4번(컨트롤러 N+1)도 같은 View가 없앤다. 예산의 하드 삭제와 월 점검의 무삭제는 맞다(이력이 필요 없는 설정, 지울 수 없는 기록).
- 카테고리 트리 조립은 서비스의 함수 하나로.

컨트롤러의 share/unshare 메서드 10개(약 130줄)는 그대로 둔다. 엔드포인트가 UI 계약이고, 하나로 합치려면 경로를 바꿔야 한다. 얻는 것이 Swagger 애너테이션 몇십 줄이다.

기각: **제네릭 CRUD 기반 클래스(서비스·컨트롤러를 타입 파라미터로)**. 5개의 `create`·`update`는 검증이 서로 달라 훅 메서드가 늘어나고, 읽는 사람이 기반 클래스와 구현을 오가게 된다. 반복되는 것 중 "같은 이유로 함께 바뀌는" 것은 공유 전환뿐이다.

### 손대지 않는 것

`FinanceAccessSupport`·`GroupMembershipSupport`·`MonthlyClosingGuard`(공통 로직이 이미 모여 있다), 예산 기간 겹침 정리(`FinanceBudgetService`의 고유 로직 45줄, DB의 `EXCLUDE` 제약과 짝), 그룹·초대·멤버 구조, 모듈 경계(외부 의존이 user 1곳과 이벤트 1개).

## 2. `api.admin` (관리자)

12의 D0이 (나)면 이 절의 대부분은 삭제로 바뀐다. 아래는 (가)일 때다.

### 문제

1. **런타임 설정 트리를 5곳에 선언한다**(약 620줄): 요청 `AdminSettingsRequest.java:26-165`, 응답 `RuntimeSettingsResponse.java:20-102`, root 도메인 4개 record, `:shared`의 `TradingPolicySettings`·`StrategyFieldSettings`·`StrategyCreationSettings`, 영속 2벌. 저장소도 둘이다(`public.admin_runtime_settings`, `trading.trading_runtime_settings`). root가 trading에 내부 HTTP로 위임한다.
2. 감사 로그 호출 16곳의 액션 이름이 문자열 리터럴이다(`AdminService.java:66-131` 등).
3. 컨트롤러 3곳이 요청마다 사용자 전체를 불러 메모리에서 조인한다(`AdminTradeController.java:82`, `AdminObservabilityController.java:89`, `AdminAccountController.java:49`).
4. 승인·거절이 채널(웹/텔레그램)별로 4개 메서드다(`AdminService.java:60-92`). 텔레그램 승인 버튼의 `callback_data`를 notify가 만들고(`TelegramUserNotificationAdapter.java:26-27`) admin이 해석한다(`TelegramBotService.java:69-95`).
5. `AdminQueryService` 12개 메서드 중 7개가 위임 한 줄이다. `Admin*Exception` 2개는 던지는 곳이 없다.

### 목표

- **설정 트리는 record 한 벌**: 05 통합 뒤에는 두 저장소와 내부 HTTP가 필요 없다. `RuntimeSettings` 한 트리(가입 정책, 브로커, 전략 종류별 필드 정책, 벤치마크)를 도메인 record로 두고, 그 record가 그대로 PUT 요청 본문·GET 응답·jsonb 저장 형태다(02의 직접 바인딩). 검증 애너테이션은 record에 붙인다. 테이블은 `admin_runtime_settings` 하나로 합치고 `trading_runtime_settings`는 다음 배포에서 drop한다. 5벌 → 1벌, 약 620 → 약 170줄.
- 감사 액션은 enum `AuditAction`(지금 7종). 호출 16곳은 그대로 명시 호출로 둔다. 어디서 감사가 남는지 코드에서 보이는 편이 낫고 `@Aspect`는 금지다.
- 사용자·계좌 조인은 02의 View(SQL 조인)로. 05 통합 뒤 같은 DB 연결에서 읽는다.
- 승인·거절은 메서드 2개 + 채널 인자. `callback_data`의 조립과 해석을 한 클래스(`ApprovalCallback`의 `encode`·`parse`)에 둔다.
- 5번은 01과 05 X2가 없앤다.

### 손대지 않는 것

컨트롤러 12개(평균 57줄)와 서비스 8개(평균 76줄)의 크기, 감사 로그·오류 로그 영속(각 52~96줄), 설정 갱신 순서의 의미(`AdminSettingsService` 주석대로. 통합 뒤에는 한 트랜잭션이 된다).

## 3. `tradingcore.stats` (통계)

### 문제 — 구조보다 정합성

`TradingStatsService.buildPoints`(누적자산 곡선, `:326-393`)와 `InvestmentIndexCalculator.buildDailyValuations`(투자지수, `:51-111`)는 같은 일을 한다: 사이클 스냅샷을 KST 날짜별로 묶어 이월하고, 한 전략의 사이클이 바뀌는 날 "어느 사이클이 그날의 값인가"를 정해 합친다. 그런데 규칙이 다르다.

| | 곡선 (`TradingStatsService`) | 지수 (`InvestmentIndexCalculator`) |
|---|---|---|
| 그날 유효한 사이클 | **스냅샷이 있는** 가장 나중 사이클(`:355-359` 체인의 마지막) | **`startDate`가 지난** 가장 나중 사이클(`activeCycleOn` `:267-292`) |
| 새 사이클은 시작됐는데 스냅샷이 아직 없는 날 | 앞 사이클의 마지막 값을 이어 쓴다 | 새 사이클을 고르고 값이 없으므로 그날을 건너뛴다 |
| 같은 날 스냅샷 둘 중 마지막 | 입력 정렬에 의존(`put`) | `createdAt` 비교(`merge(laterPosition)`) |
| 사이클 순서 | `NEWER_CYCLE`(`createdAt`, `id`) | `compareCycles`(`nullsFirst(createdAt)`, `id`), `CapitalLedgerTimeline:48`은 `createdAt`만 |
| 평가액 | 종가 없으면 평단으로 대체 | 종가 없으면 미완성(의도된 차이, 주석 있음) |

같은 계좌의 곡선과 지수가 사이클 교체일 주변에서 서로 다른 사이클을 기준으로 삼을 수 있다. 둘을 대조하는 테스트는 확인되지 않았다.

그 밖의 구조 문제:

- `TradingStatsService` 435줄이 요약·곡선·사이클 성과 세 엔드포인트와 캐시 무효화를 함께 가진다. 테스트가 1,415줄이다.
- "계좌 → 전략 → 사이클 → 자본 이벤트" 적재를 `TradingStatsService.loadCycles`(`:229-251`)와 `InvestmentPointsQueryService.java:55-83`가 따로 짠다. MOCK 제외·통화 필터가 3곳에 있다.
- 코인 지표 응답 하나에 도메인 record 12개와 응답 record 19개가 거울처럼 있고, 이름 충돌 때문에 `CryptoMarketIndicatorsOpenApiCustomizer`(53줄)가 생겼다.
- 외부 시세 Config 5개가 같은 한 줄(`RestClients.withTimeouts(3_000, 7_000)`)이다.

### 목표

- **일별 사이클 장부를 하나로**: 순수 도메인 클래스 `DailyCycleBook`이 (사이클 목록, 스냅샷 목록)을 받아 날짜마다 "전략별 유효 사이클과 그 최신 스냅샷"을 낸다. 사이클 순서 비교, 같은 날 마지막 스냅샷 고르기, 교체일 규칙이 이 안에 한 번만 있다. 곡선은 여기에 원금·체인 보정을, 지수는 외부 현금흐름·복리를 얹는다. 평가액 함수(평단 대체 여부)만 호출자가 넘긴다.
- **규칙은 하나로 정한다**: 기본값은 지수의 `createdAt` 비교(입력 순서에 기대지 않는다)와 `NEWER_CYCLE` 순서. 유효 사이클 규칙은 T1의 대조 테스트로 두 구현이 갈리는 날을 먼저 찾고 정한다. 화면 숫자가 바뀌면 사용자 확인을 받는다(중단 조건).
- `TradingStatsService`를 셋으로 나눈다: 요약, 곡선, 사이클 성과. 적재는 `CycleLedgerLoader` 하나를 `InvestmentPointsQueryService`와 함께 쓴다. 캐시와 무효화 리스너는 지금 자리(서비스)에 둔다(`constraints.md`에 이유가 적힌 예외).
- 코인 지표는 02의 View 직행 대상이다(도메인 record가 곧 응답). 응답 record 19개와 Customizer가 사라진다. 12의 D2가 삭제면 하지 않는다.
- 시세 Config 5개는 공용 `RestClient` 빈 하나로.

### 손대지 않는 것

`AccountStatisticsService`(128줄, 메서드 평균 9줄)·`PortfolioService`·`CryptoPortfolioService`·`CapitalLedgerTimeline`, 외부 시세 포트 8개(전부 실제 외부 구현이 있는 포트라 01의 유지 조건에 맞는다), 소유권 검증 방식(stats가 `OwnedStrategy`를 못 쓰는 것은 05 단계 R에서 모듈이 바뀔 때 다시 본다), `/stats/cycles`의 메모리 페이징(10 T4가 다룬다).

## 4. 알림(`notify` 양쪽)과 문구

모듈은 작다(50파일 1,798줄). 문제는 문구가 모듈 밖에서 만들어지고, 같은 골격이 프로세스마다 있다는 점이다. 05 X6·X7(사용자 복제본·푸시 스트림 제거)이 끝난 뒤를 기준으로 처방한다.

### 문제

1. `TradingErrorEvent(userId 또는 null, String message)`를 서비스 16개 파일 38곳이 자유 서식 문자열로 만든다. `userId`가 있으면 그 문자열이 **사용자에게** 간다(`TradingAlertNotifier.java:37`). 개발자용 문장과 사용자용 문장이 같은 통로를 쓴다. `TradingErrorReporter`를 거치는 곳이 11곳, 직접 `new` 하는 곳이 37곳이다.
2. 금액 표기가 두 가지다. 주식 알림은 `$%.2f`(6곳, 로케일 미지정), 봇 `/status`와 코인 체결은 `Currency.format`("1,234.50 USD"). 날짜·수량·전략 표기는 헬퍼가 없어 문구마다 순서와 언어(영문 enum 이름/한글 라벨)가 다르다.
3. HTML 이스케이프가 호출자 책임이다(17곳). 빠뜨리면 텔레그램이 400을 주고 알림이 사라진다(`TradingUserNotificationAdapter.java:149` 주석).
4. 같은 문구가 채널마다 따로 있고 이미 갈라졌다: 거절 안내가 텔레그램 "가입 신청이 거절되었습니다." 대 FCM "가입이 거절되었습니다."(`TelegramUserNotificationAdapter.java:44-52`, `FcmAdapter.java:30-37`). 관리자 오류 템플릿은 두 프로세스에 같은 것이 있다.
5. "연결된 경우에만 보낸다"와 채널 판정이 3곳에 조건이 다르게 있다(`TelegramUserNotificationAdapter.java:50`, `TradingUserNotificationAdapter.java:160`, `UserChannelNotifier.java:48`). 알림 유형 설정(`NotificationType`) 확인은 호출처마다 한다/안 한다가 다르다.
6. 알림 리스너 선언이 세 가지다: `@TransactionalEventListener(fallbackExecution = true)` 18, `(phase = AFTER_COMMIT)` 9, `@EventListener` 1 외. 클래스마다 주석으로 고른 이유를 다시 설명한다.
7. 이름이 비슷한 이벤트 쌍: `MarketCloseEvent`(장 마감)·`MarketClosedEvent`(휴장), `CycleCompletedEvent`·`CycleEndedEvent`(둘 다 같은 `notifyCycleCompleted`를 부른다).

### 목표

- **사용자에게 가는 문장은 notify 모듈에만 있다.** `TradingErrorEvent`는 관리자 전용으로 좁히고(userId는 "누구 일인지" 문맥으로만), 사용자에게 알릴 일은 종류가 있는 이벤트로 낸다(대부분 이미 있다: `InsufficientBalanceEvent`, `CycleRotationFailedEvent` 등). 38곳을 "관리자만/사용자도"로 분류하는 것이 첫 작업이다. 관리자 알림은 전부 `TradingErrorReporter` 한 통로로 낸다. 관리자용 문장의 카탈로그는 만들지 않는다. 운영자가 읽는 로그성 문장이고 종류를 고정할 이유가 없다.
- **문구 조립기 하나**: `Message.of("<b>%s</b> %s", args...)`가 인자를 항상 이스케이프한다. 금액은 `Currency.format`, 수량은 `Quantities.normalize`, 전략은 `전략 라벨 + 종목` 한 순서로 내는 포맷 함수를 같은 곳에 둔다. `$%.2f` 6곳을 없앤다. 사용자에게 보이는 enum은 한글 라벨을 쓴다(`/api/meta`의 라벨과 같은 값).
- **메시지 정의는 채널과 무관하게 한 번**: `(제목, HTML 본문)` 하나를 만들고 텔레그램은 그대로, FCM은 태그를 지워 보낸다(trading 쪽 `toPlainText`가 이미 하는 방식을 가입 승인·거절에도 쓴다). `FcmAdapter`의 문구 리터럴과 빈 구현 2개가 사라진다.
- **발송 통로 하나**: 지금의 `UserChannelNotifier`(수신자 조회 → 유형 설정 확인 → 텔레그램·FCM을 서로 격리해 발송)를 모든 사용자 알림이 쓴다. 호출은 `notify(userId, NotificationType, message)` 하나다. 설정과 무관하게 보내야 하는 것(API 키 만료)은 유형에 "항상 발송" 표시를 둔다. trading 쪽 리스너 9개 클래스(약 330줄)는 "이벤트 → 메시지 → notify" 한 줄짜리 메서드가 되므로 한두 클래스로 모은다.
- **리스너 선언은 한 가지**: 알림 리스너는 전부 `@TransactionalEventListener(fallbackExecution = true)`. 트랜잭션 안에서 발행되면 커밋 뒤에, 밖에서 발행되면 즉시 실행돼 발행처 사정을 리스너가 몰라도 된다. `constraints.md`의 "@Transactional 내부 외부 시스템 호출 금지" 절을 이 한 줄로 고친다.
- 재발행 시 알림이 중복될 수 있다는 점은 받아들인다(Modulith는 `fallbackExecution` 리스너도 EPR에 기록하고, 재기동 때 미완료분을 재발행한다. `findings/09`의 "EPR 기록 없음" 서술은 틀렸다). 알림은 "최소 한 번"이면 된다. 주문·원장을 바꾸는 리스너는 지금처럼 멱등이어야 한다.
- 이벤트 이름: `MarketClosedEvent` → `MarketHolidayEvent`. `CycleCompletedEvent`와 `CycleEndedEvent`는 발행 경로를 확인해 뜻이 같으면 하나로 합친다. EPR에 미완료 행이 없을 때 배포한다(이벤트 클래스 이름이 EPR에 저장된다. `docker-infra.md`의 리스너 이동 절차).

### 손대지 않는 것

`UserNotificationPort`의 구현 3개(실제 다형성), `fcm_device_tokens` 영속, SSE 컨트롤러 2개, 가계부 리마인더가 문구를 finance에서 만드는 것(그 모듈의 말이고 notify는 전달만 한다. 조립기만 공용으로 쓴다), 알림 발송 이력을 남기지 않는 것(12 A3).

## 5. 보안·JWT·인증

### 판정: 구조는 손대지 않는다

필터와 `SecurityConfig`는 `:shared` 한 벌(381줄)이고 프로세스 간 중복이 없다. 라우트 매처 14개에 컨트롤러 없는 매처가 없고, 계좌·전략 id를 받는 사용자용 핸들러 약 30개 중 소유권 검증을 빠뜨린 것이 없다. 권한 검사가 URL 규칙과 메서드에 이중으로 있는 곳도 없다.

### 결함 1건

**승인 전(PENDING) 사용자가 인증 경로를 전부 쓸 수 있다.** 로그인은 사용자 상태와 무관하게 액세스 토큰을 내주고(`AuthController.java:55-57`), `JwtAuthFilter.java:45-84`는 블랙리스트와 역할 변경 시각만 본다. 거절(REJECTED)은 블랙리스트로 막히지만 PENDING은 막는 것이 UI뿐이다. 승인 전 사용자가 API를 직접 부르면 계좌를 등록하고 수동 주문까지 낼 수 있다. 야간 배치는 `user_notify_profile.is_active`로 걸러져 돌지 않는다.

지금은 `approvalRequired=false`라 PENDING 사용자가 생기지 않는다. 승인을 켜는 순간 뚫린다.

처방: 토큰의 `role` 클레임을 ACTIVE 사용자에게만 넣는다. 정책의 마지막 규칙을 `authenticated()`에서 `hasAnyRole("USER","ADMIN")`으로 바꾸고, 승인 대기 화면이 쓰는 경로(`/api/auth/**`의 내 정보·상태 스트림·재신청·로그아웃)만 `authenticated()`로 남긴다. 승인되면 기존 "역할 변경 시각" 장치가 옛 토큰을 막고 UI가 새 토큰을 받는다. 필터에 DB 조회를 넣지 않는다.

fable-ui 요청 반영(2026-11-13, `cross-repo.md` B절 U1·U2): ① 토큰에 `status` 클레임도 넣는다(UI가 화면 분기에 쓴다. 권한 강제는 위 정책이 한다). ② "역할 변경 시각" 기록을 상태 변경(승인·반려·재신청)에도 건다. 거절 코드는 `TOKEN_STALE_ROLE`을 그대로 쓴다. 반려는 지금 userId 블랙리스트로 막히므로, 반려된 사용자가 재신청 경로를 쓸 수 있는지부터 확인한다. ③ 상태 스트림(`/api/auth/status-stream`)은 UI가 `/api/auth/me` 주기 조회로 바꾼 뒤 삭제한다 — 위 허용 경로 목록에서 빠진다. ④ 반려 사유를 지운다(사용자 결정 2026-11-13, ui D7): 응답 필드·관리자 반려 요청의 `reason`·도메인 필드·텔레그램 반려 알림의 사유 줄·`users.reject_reason` 컬럼(코드 삭제 배포 뒤 다음 배포에서 드롭). 목록은 `cross-repo.md` B절 U7.

12의 D0이 (나)면 승인 흐름째 사라지므로 하지 않는다.

### 작은 수정 5건

| 내용 | 위치 |
|---|---|
| 리프레시 토큰 없이 로그아웃하면 액세스 토큰의 jti를 차단하지 않는다 | `AuthController.java:103-105` |
| 내부 토큰 비교를 `MessageDigest.isEqual`로(상수 시간) | `InternalTokenAuthFilter.java:43` |
| `/api/auth/**` permitAll 앞에 개별 authenticated 5개를 둔 순서 의존 규칙을, 공개 경로를 명시하는 목록으로 | `RootSecurityPolicy.java:28-36` |
| 리프레시 토큰 수명 5일이 숫자로 두 곳 | `TokenService.java:31`, `RefreshTokenCookieHelper.java:14` |
| 필터 순서 주석이 실제와 반대 | `SecurityConfig.java:55-58` |

토큰 발급마다 EC 키를 다시 파싱하는 것(`JwtIssuerService.java:55-65`)은 로그인·갱신 때만이라 그대로 둔다.

## 6. 설정 파일

### 판정: 손대지 않는다

추적되는 yml 5개 373줄에 바인딩 없는 커스텀 키가 0개다(findings 06이 조사하지 못한 항목). 환경변수 이름도 배포 설정과 yml 사이에 한쪽에만 있는 것이 없다. root와 trading-core가 같은 값으로 중복 선언한 키 45개는 05 통합에서 yml이 한 벌이 되며 사라진다.

작은 수정(05 P2·X8과 함께): `AlpacaProperties` 3벌을 하나로 하면서 읽지 않는 `dataBaseUrl` 삭제, `binance.base-url`을 yml에 선언, `meritz`·`upbit`·쿠키 설정의 코드·yml 이중 기본값을 yml 한 곳으로, `.env.example`에 `CMC_API_KEY`·`KIS_ADMIN_APP_*` 추가(KIS는 04에서 삭제되면 넣지 않는다). 규칙 한 줄을 `constraints.md`에 넣는다: 기본값은 yml에만 두고 `@Value`의 `:기본값`을 쓰지 않는다.

## 7. 이벤트·EPR

### 판정: 구조는 손대지 않는다

이벤트 32개 중 리스너 없는 것 0, 발행처 없는 것 0, 같은 모듈 안 1:1이라 직접 호출로 바꿀 것 0. 이벤트를 남용한 흔적이 없다. 프로세스를 넘는 스트림 4개는 05가 없앤다.

남는 일은 4절에 넣었다(리스너 선언 통일, 이름 쌍, `TradingErrorReporter` 한 통로). 추가로:

- 오류 알림 38곳이 오류마다 EPR 행을 만들고 완료 행을 아무도 지우지 않는다. 11 T8(완료 행 삭제 설정)이 해결한다.
- `SchedulerLifecycleEvent`를 trading 스케줄러가 발행해도 리스너가 root에만 있어 지금은 버려진다. 05 통합 뒤 갑자기 알림이 오기 시작한다. 05 P7(스케줄러 알림 필터)이 이 경우를 포함하는지 실행 세션이 확인한다.

## 8. 오류 처리·`BrokerCallGuard`

### 판정: 구조는 손대지 않는다

커스텀 예외 36개 중 한 곳에서만 던지는 것이 10개지만 각각 HTTP 상태나 `ErrorCode`를 정하는 역할이 있어 합치지 않는다. `ErrorCode` 14개는 전부 쓰이고, UI가 분기하지 않는 8개도 이름을 바꾸거나 지우지 않는다(계약). `BrokerCallGuard`는 `wrap` 8곳·`tryGet` 28곳으로 규칙대로 쓰이고, 서비스의 광역 `catch` 51곳 중 규칙 위반은 1곳이다. `log.error`에 예외 객체를 안 넘긴 곳도 1곳뿐이다.

불일치 4건:

| 내용 | 위치 | 처방 |
|---|---|---|
| "이미 그 상태"가 전용 예외(409)와 `IllegalStateException`(400)으로 갈린다 | `StrategyService.java:97,117,160` | 409로 통일. 응답 상태가 바뀌므로 `cross-repo.md`에 올리고 UI가 400에 기대는지 확인 뒤 |
| HTTP 매핑이 없는 예외 2개 | `OrderStateConflictException`, `PrivacyValuationException` | 409·422로 매핑(05 P1의 통합 처리기에서) |
| `BrokerCallGuard.wrap`이 증권사 예외가 아닌 실패를 `IllegalStateException`으로 바꿔 400이 된다 | `BrokerCallGuard.java:32-33` | 요청 잘못이 아니라 상대 시스템 실패다. 502로 매핑되는 예외로 바꾼다 |
| raw catch 1곳, 예외 객체 미전달 1곳 | `TradingPriceFetcher.java:177`, `FcmAdapter.java:70` | `tryGet`으로, 예외 객체 전달 |

## 9. 메서드·필드 단위 미사용 코드

### 판정: 한 번 정리한다. 가드는 만들지 않는다

이름 기준 조사라 하한이지만 규모가 작다.

| 삭제 | 위치 |
|---|---|
| `UserSettingsPort.findOrDefaultByUserIds`(포트·구현) | `UserSettingsPort.java:21`, `UserSettingsPersistenceAdapter.java:60` |
| `FinanceAccount.withAccountNo`, `FinanceCategory.SYSTEM_LOAN_ID` | `FinanceAccount.java:35`, `FinanceCategory.java:23` |
| 읽지 않는 `MarginItem` 컴포넌트 2개 | `MarginItem.java:9-10` |
| `BrokerApiException.isRejected`(인스턴스, 테스트만 사용. 정적 `isRejection`과 같은 판정) | `BrokerApiException.java:42`. 테스트 48곳을 정적 쪽으로 |
| 테스트만 쓰는 도메인 메서드 | `AccountBalance.java:61`, `ReverseModePosition.java:42` — 그 테스트가 공개 동작으로 같은 것을 확인할 수 있으면 삭제 |
| 죽은 `Admin*` 예외 2개 | 05 X2에서 |

`DstInfo`의 테스트 전용 세션 계산과 `TossPriceApi.java:163-178`의 복제 구현은 06 T5(시각 모델 하나)가 다룬다. `CanonicalToken.expiresAtEpochMillis`·`Currency.JPY`·`CryptoOrderResult.State.WATCH`는 외부 입력으로 들어올 수 있어 확인 없이 지우지 않는다.

미사용 코드 가드(ArchUnit·빌드 플러그인)를 만들지 않는 이유: 반년 동안 쌓인 양이 10건 남짓이다. 01·02·05 실행 중 포트와 DTO가 대량으로 사라지므로, 각 지침서의 마지막 커밋에서 IntelliJ의 미사용 선언 검사를 한 번 돌리는 것으로 충분하다(09의 마감 점검 T9에 한 줄 추가).

## 10. 선행 테스트

1. **통계 대조 테스트(3절)**: 같은 사이클·스냅샷 입력으로 곡선의 일별 자산과 지수의 일별 평가액을 함께 계산해 "그날 기준이 된 사이클"이 같은지 비교한다. 시나리오: 사이클 교체일에 새 스냅샷이 있는 경우/없는 경우(지연 롤오버), 같은 날 스냅샷 두 개, 시작 예정일 전 재설정, 후속 없이 종료. 지금 코드에서 갈리는 시나리오를 기록으로 남긴다.
2. **알림 문구 스냅샷(4절)**: 사용자 알림 23종의 현재 문구를 고정 입력으로 찍어 둔다. 조립기로 옮긴 뒤 의도한 차이(금액 표기 통일, 라벨 한글화)만 달라졌는지 본다.
3. **보안 정책 표(5절)**: 07의 "보안은 정책 테스트 한 곳"에 PENDING 토큰 행을 더한다(공개 경로 200, 승인 대기 경로 200, 그 밖 403).
4. 가계부 공유 전환은 기존 서비스 테스트 5개가 그대로 통과해야 한다.

## 11. 태스크

| # | 내용 | 대상 | 모델 | 선행 | 완료 판정 |
|---|---|---|---|---|---|
| U1 | 보안 결함·작은 수정 5건(5절). PENDING 차단은 D0=(가)일 때 | `:shared` security, `RootSecurityPolicy`, `JwtIssuerService`, `AuthController` | Opus | 없음. **지금 시작 가능** | `./gradlew :test --tests '*Security*' --tests '*Auth*' 2>&1 \| grep -E "FAILED\|BUILD"` |
| U2 | 미사용 코드 정리(9절)와 오류 처리 불일치 중 raw catch·로그 2건 | 9절 표 | Sonnet | 없음. 지금 가능(S9와 동시 금지: 테스트 파일) | `./gradlew test 2>&1 \| grep -E "FAILED\|BUILD"` |
| U3 | 통계 대조 테스트 작성(코드 불변)과 갈리는 시나리오 보고 | stats 테스트 | Opus | 없음. 지금 가능 | 새 테스트 통과 + 차이 목록을 `findings/20-stats-divergence.md`로 |
| U4 | `DailyCycleBook` 추출, 두 계산이 사용. `TradingStatsService` 3분할, `CycleLedgerLoader` | `TC/stats/application/**` | Opus | U3, 03 T7(백테스트가 `InvestmentIndexCalculator`를 쓴다) | `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.*' 2>&1 \| grep -E "FAILED\|BUILD"` |
| U5 | 알림: 문구 스냅샷 → 조립기·포맷 함수 → `TradingErrorEvent` 38곳 분류와 통로 통일 → 메시지 정의 한 번 → 발송 통로 하나 → 리스너 선언 통일·이벤트 이름 | notify 양쪽, `TC/trading/application/service/*`(이벤트 생성 지점) | Opus(분류·통로), Sonnet(치환) | 05 X6·X7. 조립기·포맷 함수와 스냅샷은 지금 가능 | `./gradlew test --tests '*Notif*' 2>&1`은 서브프로젝트별로: `:test`, `:trading-core:test` |
| U6 | 가계부: `with*`·공유 전환 한 곳·트리 조립 한 곳 | `API/finance/**` | Sonnet | S6(01 단계 B의 finance) 뒤. 삭제 방식 통일은 N7(02) 안에서 | `./gradlew :test --tests 'com.kista.api.finance.*' 2>&1 \| grep -E "FAILED\|BUILD"` |
| U7 | 런타임 설정 한 벌(record 하나, 테이블 하나), `AuditAction` enum, 승인·거절 메서드 2개, `ApprovalCallback` | admin·shared kernel 설정 타입, `MIG-R`·`MIG-T` | Opus | 05 단계 X(내부 HTTP 제거) 뒤, D0=(가) | `./gradlew test 2>&1 \| grep -E "FAILED\|BUILD"` + 운영 설정 값이 전환 전후 같은지 SELECT 비교 |
| U8 | 오류 상태 통일(8절의 409·502)과 매핑 2건 | 통합 예외 처리기 | Opus | 05 P1, `cross-repo.md` 확인 | 처리기 정책 테스트(07 T3) |
| U9 | 설정 작은 수정 4건과 `constraints.md` 규칙 2줄(기본값 위치, 리스너 선언) | yml, `.env.example`, docs | Sonnet | 05 P2와 함께 | 양쪽 컨텍스트 로드 테스트 |

중단 조건: U4에서 규칙을 하나로 맞추면 운영 계좌의 곡선·지수 값이 달라진다(→ 사용자 확인). U5에서 사용자에게 가던 `TradingErrorEvent` 문장 중 대응하는 종류 이벤트가 없는 것이 나온다(→ 새 이벤트를 만들지 묻는다). U7에서 두 설정 저장소의 값이 서로 어긋나 있다.

## 12. 하지 말 것

- 가계부에 제네릭 CRUD 기반 클래스를 만들지 않는다.
- 관리자 알림 문장을 enum·카탈로그로 고정하지 않는다.
- 감사 로그를 `@Aspect`나 애너테이션으로 자동화하지 않는다.
- `ErrorCode` 상수 이름을 바꾸거나 지우지 않는다.
- 한 곳에서만 던지는 예외를 줄 수 때문에 합치지 않는다.
- 필터에서 사용자 상태를 DB로 조회하지 않는다.
- 통계의 평가액 함수 두 가지(평단 대체 여부)를 하나로 합치지 않는다. 의도된 차이다.
- 12의 D0~D2가 나기 전에 admin·코인 지표·벤치마크의 리팩토링(U7, 3절의 지표 부분)을 시작하지 않는다.

## 13. 사용자 결정

이 지침서 고유의 결정은 없다. 12의 D0(다중 사용자)·D2(코인 지표)가 U1의 PENDING 차단, U7, 3절의 지표 부분을 좌우한다. 실행 중 사용자 확인이 필요한 지점은 둘이다: U4에서 화면 숫자가 달라질 때, U8에서 응답 상태 코드가 바뀔 때(kista-ui와 같은 날 배포).
