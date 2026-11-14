# 결정 기록 — 유지·기각·트리거 보류

구조 변경·리팩토링을 제안하기 전에 대상 클래스·주제어로 이 파일을 grep한다. 여기 있는 항목은 다시 제안하지 않는다. 재검토 조건이 적힌 항목은 그 조건이 성립했을 때만 다시 본다.

- 자동 로드하지 않는다. 규칙(지금 지켜야 하는 것)은 `constraints.md`와 모듈 문서가 갖고, 여기는 "하지 않기로 한 것과 그 이유"만 둔다.
- 2026-11-14 작성. 출처: 옛 `code-quality-backlog.md` 유지 목록, `own-type-ledger.md`, memory `project_*` 5개, Fable 전면 검토(`docs/superpowers/fable-review/`)의 재심 표와 사용자 결정. 재심에서 "뒤집는다"·"소멸"로 판정된 결정은 넣지 않았다. 그 결정은 해당 지침서가 실행한다.
- 지침서(`docs/superpowers/fable-review/guides/NN`)가 실행되어 항목의 대상이 사라지면 그 커밋에서 항목도 지운다.
- 형식: `### 결정 한 줄` 다음 줄에 `이유 … 재검토 조건 … (날짜, 근거)`.

## 1. 구조·모듈

### 엔진(매매 실행)은 단일 프로세스로 둔다
이유: 배치 재개가 잔여 락을 인수하고, `AccountBudgetLock`은 JVM 락이다. 겹침 기동은 이중 실행이다. 재검토 조건: 없음. 05 통합 뒤에도 worker 단일 인스턴스로 승계한다. (2026-11, fable 05 11절)

### DB 스키마 5분리 유지, 물리 DB 분리는 하지 않는다
이유: 소유 서비스 경계는 스키마로 충분하다. 물리 분리(옛 4b-2 보류)는 사용자 결정 D2로 폐기했다. 재검토 조건: 없음. (2026-11-12, fable 05 D2)

### trading 스키마도 expand/contract를 지킨다
이유: 통합 뒤 롤링 배포 중 옛 코드가 새 스키마를 읽는다. `check-migrations.sh`는 root만 강제하므로 스스로 지킨다. 재검토 조건: 없음. (2026-11-12, fable 05 D3)

### Spring Modulith `explicitly-annotated`와 고정 모듈 ID 유지
이유: 모듈 ID가 문서·아키텍처 맵·가드에 박혀 있다. 재검토 조건: 없음. 05 통합 뒤 모델은 1개다. (2026-11, fable 05 11절)

### 브로커 기능은 broker 밖에 `*Port`로만 노출하고 `adapter/out`은 비공개
이유: 브로커 포트에는 실제 다형성이 있다(메리츠·토스·Mock). 재검토 조건: 없음. (2026-11, fable 01·04)

### 브로커 capability 포트는 유지한다
이유: 증권사마다 지원 기능이 다르다. 포트를 묶는 인터페이스와 순수 위임 facade만 04 단계 E가 record 묶음으로 바꾼다. 재검토 조건: 없음. (2026-07 유지 확정, 2026-11 fable 04 5절)

### `CycleOrderStrategy` 이중 계층(순수 계산기 + 어댑터) 유지
이유: 계산기는 공식 SSOT(`trading-formulas.md`)다. 어댑터는 입력 변환·cap·예산 축소를 맡는다. 합치면 공식 파일에 운영 관심사가 섞인다. DCA만 계산기가 어댑터 안에 있는 것도 그대로 둔다. 재검토 조건: 없음. (2026-07 유지 확정, 2026-11 fable 03 재심 유지)

### 매매 helper(계획기·실행기·캡퍼·리포터) 세분화 유지
이유: 배치·수동·미리보기·DCA 수집 4갈래가 공유한다. 재검토 조건: 06 단계 모델 적용 뒤 호출처가 1곳이 된 헬퍼는 인라인한다(06 T7). (2026-07 유지 확정, 2026-11 fable 06 재심 유지)

### admin 서비스 분리 구조 유지
이유: 2026-07 검토에서 변경 불필요로 확정했다. 재검토 조건: 05 단계 R(admin·web 셸 해체, 사용자 결정 D4)에서 다시 판정한다. (2026-07)

### Redis Pub/Sub 구독자의 `@PostConstruct` 등록 유지
이유: Pub/Sub은 백로그가 없어 기동 중 메시지는 어떤 방식이든 유실된다. 컨테이너 시작 전 리스너 등록은 무해하다. 대상: `RedisTradeEventSubscriber`, `TradeEventBenchmarkCacheInvalidator`. 재검토 조건: 없음. (2026-10-31, 2026-11 fable 05 재심 유지)

### `UserFcmCleanupListener`는 user 모듈에 둔다
이유: notify는 순수 outbound여야 한다. 재검토 조건: 없음. (2026-10-31)

### feargreed 어댑터 중복(root ↔ trading-core) 유지
이유: trading-core는 root를 호출할 수 없다. DCA FEAR_GREED 조건은 최신 점수 1건을 fail-open으로 즉시 읽어야 한다. 재검토 조건: 05 통합(단계 M·X)으로 프로세스 경계가 없어질 때. (2026-10-31)

### 예외 핸들러 오류 보고, `FcmAdapter.addAllTokens` 통합 보류
이유: 2026-10-31 보류로 확정했다. 예외 advice 자체는 S5 P1에서 platform `ProblemDetailExceptionHandler` 하나로 합쳤고, 오류 보고는 프로세스별 `ExceptionReporter`다. 재검토 조건: 05 통합 뒤 프로세스가 하나가 될 때. (2026-10-31)

### Prometheus·Grafana 스택 유지
이유: 2026-07 검토에서 변경 불필요로 확정했다. 재검토 조건: 없음. (2026-07)

### 테스트 DB는 docker-compose 로컬 PostgreSQL, `*IT`는 `kistadb_it`
이유: Testcontainers보다 빠르고 CI와 같다. 바꿀 이유가 측정에서 나오지 않았다. 재검토 조건: 없음. (2026-11, fable 07 재심 유지)

### `@WebMvcTest`·DB 상속 테스트의 `SAME_THREAD` 제거는 기각
이유: 병렬 실행 시 mock 오염이 실제로 일어났다. 재검토 조건: 없음. (2026-07 기각, 2026-11 fable 07 재심 유지)

### 테스트 FQCN을 import로 일괄 전환하지 않는다
이유: 기능상 이득이 없다. 파일을 건드릴 때만 바꾼다. 재검토 조건: 없음. (2026-11-08 CQ-56, fable 07 재심 유지)

### 미사용 import 빌드 가드는 두지 않는다
이유: 플러그인을 새로 들여야 한다. 1,100여 파일 중 21개였고 타입 이동 잔재라 재발 근거가 없다. 재검토 조건: 이관과 무관한 재발이 감사에서 확인될 때. (2026-11-08 CQ-42)

## 2. 코드 수준 — 중복·추출·형식

### MapStruct 도입 거부, 매핑은 수동
이유: 어댑터 매핑에 해시 인자(`AccountEntity`)와 부모-자식 조립(DCA)이 있다. 엔티티 메서드와 `toDomain/toEntity`가 병존하는 것도 같은 이유로 유지한다. 재검토 조건: 없음. (2026-10-31)

### `hasher.hash()`를 도메인으로 옮기지 않는다
이유: HMAC 비밀키는 인프라 소관이다. AES는 `AttributeConverter`, `*_hash` 계산은 어댑터에 둔다. 재검토 조건: 없음. (2026-10-31)

### Null 체크 전사 적용 기각
이유: 스캔 아티팩트로 판명됐다. record 필드는 nullable + 주석, 포트·전략의 skip은 `Optional`로 표현한다. 재검토 조건: 없음. (2026-07)

### `with*()` 패턴 변경 기각
이유: 2026-07 검토에서 기각했다. 재검토 조건: 없음. (2026-07)

### 긴 파라미터는 같은 타입 인접 2쌍 이상인 곳만 record로 묶는다
이유: 기준 적용 결과 7곳이었다. 이 기준보다 앞서 생긴 8~10인자 메서드(`BuyOrderPriceCapper.capIfNeeded`, `DcaExecutionRecorder.recordSkipped`·`buildPreview` 등)도 재제안하지 않는다. 재검토 조건: 없음. `CycleOrderComputer.compute()`는 예외로 03에서 묶는다. (2026-10-31, 2026-11-08·09 감사)

### 2곳 중복은 추출하지 않는다(목록)
이유: 추출 기준(3곳 이상 + 같은 이유로 변경)에 미달한다. 대상은 아래와 같다.
- 가격 문자열 포맷: `MeritzOrderApi`·`KisResponseParser`, 증권사 wire 포맷
- 라이브·백테스트 별지점 평균: `CycleOrderComputer`·`BacktestEngine`. 공식이므로 바꿀 때 양쪽을 함께 본다
- 페이지 슬라이싱: `StrategyHistoryQueryService`·`AccountStatisticsService`
- 평단 필수 규칙: `BootstrapPosition`·`BacktestEngine`
- "사용자를 찾을 수 없습니다": `BatchContextFactory`·`CryptoDcaRunner`
- `UserEventRepublisher`의 같은 본문 메서드 2개
- `validateWithdrawalSufficiency` 호출 2곳(인자·시점이 다르다)
- 평단 가중평균 3곳(`AccountBalance`·`CapitalInjectionService`·`VrReconfigureService`): 체결 대 투입, VR 0주 null로 의미가 다르고 공식에 닿는다
- MAINTAIN 시드 규칙 2곳
- `Instant.parse(cursor)`, `EquityPoint`/`BacktestPoint`(stats). `BacktestPoint`는 프론트 차트 재사용

재검토 조건: 같은 이유로 바뀌는 세 번째 사례가 생길 때. (2026-11-01~10 감사)

### `DcaCycleOrderStrategy.withLimits` 9필드 복사 유지
이유: 파일 안 헬퍼 한 곳이고 호출처도 이 파일뿐이다. 재검토 조건: 03 전략 축 재편 때. (2026-11)

### `CycleCompletedEvent`·`CycleEndedEvent`를 합치지 않는다
이유: 발행 흐름과 리스너 phase가 다르고 동시에 발행되지 않는다. 재검토 조건: 없음. (2026-11)

### 총자산 계산 5곳 유지
이유: 의미가 다르다(종가/평단 폴백, scale 유무, 평가액 기준, 종료금액 계약). `VrCycleRolloverService`의 계산은 공식이다. 재검토 조건: 없음. (2026-11)

### 수량 내림 FLOOR 대 DOWN, `InfinitePosition`의 scale 없는 divide 유지
이유: 양수 입력에서 결과가 같다. 공식 코드라 표기 통일의 이득이 비용보다 작다. 재검토 조건: 공식 변경 때 함께 본다. (2026-11)

### 비율 scale 1/4/6 혼재 유지(`TradingStatsService`)
이유: 용도가 다르다. 중간 계산은 6, 결과는 4, 기간 평균은 1이다. 재검토 조건: 없음. (2026-11)

### 브로커 응답 숫자 파서 3벌 유지
이유: 빈 값의 의미가 증권사 wire마다 다르다(ZERO, null, 예외). 재검토 조건: 없음. (2026-10-31)

### 증권사별 인증 catch·백오프·인터럽트 처리 공통화 기각
이유: 인터럽트 시 동작이 각 클라이언트의 재시도 계약(주문 중복 위험)에 묶여 있다. 재검토 조건: 없음. KIS 삭제(04 단계 A) 뒤 남은 벌수로 다시 세지 않는다. (2026-07 기각)

### RestClient 설정 빈 여러 개 유지
이유: `HttpClientTimeouts`로 이미 공용화했다. 남은 차이는 주석에 근거가 있다. 재검토 조건: 없음. (2026-11)

### 거래소 코드 레지스트리 키 집합 동일(KIS·메리츠) 유지
이유: 코드값이 증권사 wire다. 종목을 추가할 때 양쪽을 갱신한다. 재검토 조건: KIS 삭제 때 소멸. (2026-11)

### web 응답 Mapper 클래스와 DTO `from()` 병존 유지
이유: `Order`→DTO 3종은 필드가 다르다. contract 매핑과 사용자 응답 DTO는 계약이 다르다. 재검토 조건: 02 영속·DTO 축소에서 다시 판정한다. (2026-11)

### `String accountNo` 원시값 유지
이유: 포트·어댑터 경계의 wire 값이다. 애그리게이트 내부는 `BrokerAccountRef`가 묶는다. 재검토 조건: 없음. (2026-11)

### `TradingErrorEvent(null, ...)` 반복 유지
이유: 생성자 한 줄이라 헬퍼를 둘 이득이 없다. `e.getMessage()`만 보내는 알림(`VrCycleRolloverService`·`VrReconfigureService`)도 그대로 둔다. 재검토 조건: 없음. (2026-11)

### 잠금·종목·정책 단위 로그의 비표준 접두사 유지
이유: 범위 안에 쓸 수 있는 strategyId·accountId·orderId·userId가 없다. 잠금 하나가 여러 전략을 덮는다. 재검토 조건: 없음. (2026-11-08 CQ-07)

### 알림·예외 문구 속 `orderId=`, `FinanceAccountService`의 `accountId`는 접두사로 바꾸지 않는다
이유: 금융 accountId와 trading accountId를 혼동하지 않게 하기 위해서다. 재검토 조건: 없음. (cc6db1e64)

### warn 로그 4곳(`AccountTokenEvictionAdapter`·`KisPriceApi`·`MeritzPriceApi`·`CryptoOrderSynchronizer`)은 그대로 둔다
이유: 스택이 필요할 가능성이 있어 판정을 보류했다. 재검토 조건: 없음. (cc6db1e64)

### 토스 `getCommon` 429 재시도는 호출 람다(`retryOnTooManyRequests`) 안에 둔다
이유: rate limit 변환을 이 위치에서 한다. 재검토 조건: 없음. (2dc6cd156)

### `BrokerCallGuard` 밖 raw catch 유지(이벤트·분기·재시도가 있는 곳)
이유: `constraints.md`가 허용하는 예외(이벤트 발행·분기 처리)다. `TradingPreviewService` 등의 재시도·부분 실패 맵·다음 틱 재시도 분기도 여기에 해당한다. 재검토 조건: 없음. (2026-11)

### `CancelledOrderFillRecorder` 바깥 try/catch 유지
이유: 도달 불가가 아니다(`ifPresent` 람다 안의 `OrderFill` 생성 예외). 지우면 한 그룹의 실패가 다른 그룹까지 멈춘다. 재검토 조건: 없음. (2026-11-08 CQ-56)

### `findPlannedOrPlaced*` 포트 이름 유지(SUBMITTING 포함)
이유: 전반 개명의 이득이 비용보다 작다. 주석으로 의미를 정정했다. 재검토 조건: 02에서 Store가 구체 클래스가 될 때. (2026-11-08 CQ-56)

### not-found ISE를 `NoSuchElementException`으로 일괄 교체하지 않는다
이유: ISE는 400, NSEE는 404라 상태코드가 바뀐다. 타입은 두고 메시지만 한국어 고정 문구로 둔다. 재검토 조건: 10 T7 오류 문구 정리 때 응답 표와 함께 본다. (2026-11-08)

### `StrategyStatusInternalController`의 ARCHIVED 이중 검사 유지
이유: 선검사를 지우면 ARCHIVED→ARCHIVED 요청이 400에서 200 no-op으로 바뀐다. `case ARCHIVED`는 default 없는 switch의 컴파일 가드다. 재검토 조건: 없음. (2026-11-08)

### 수정 요청에 `@Valid`를 붙이지 않는 곳 유지
대상: `TradingCycleController` update, `AccountController` 이름 변경. 이유: `@NotNull type`, `@Pattern` 계좌번호는 등록 전용 제약이다. 별명은 서비스가 검증한다. 재검토 조건: 없음. (2026-11)

### DTO compact constructor의 IAE 유지(`DcaConfigRequest`·`DcaRuleRequest`)
이유: `[field: message]` 대신 사용자 문구로 400을 낸다. 재검토 조건: 없음. (2026-11)

### `UserNotifyProfilePersistenceAdapter`의 `occurredAt` 폴백, persistence SUM null→0 유지
이유: `occurredAt`은 구버전 발행분 때문에 남긴 레거시 nullable 예외다. SUM은 매퍼가 아니라 집계 쿼리 결과 처리다. 재검토 조건: 없음. (2026-11)

### 조회 경로 `.orElse(DEFAULT_DIVISION_COUNT)` 폴백, 시드 미리보기 `divisionCount` `defaultValue="20"` 유지
이유: 폴백은 null 기본값이 아니라 버전 상세 행 누락에 대한 방어다. 예외로 바꾸면 매매가 멈춘다. 미리보기 기본값은 등록 resolver로 흐르지 않는 미리보기 전용 쿼리 파라미터다. 재검토 조건: 없음. (2026-11-07 CQ-12)

### 엔티티 저장 패턴 두 갈래(`findOrCreate` 대 `new` + `setId`) 유지
이유: 지금 호출처가 모두 id=null 신규 저장이라 merge 덮어쓰기가 없다. 재검토 조건: `StrategyCycle` 등에 update 경로가 생길 때. (2026-11-08)

### `getStrategyOrders` 기간 기본값·size 상한(100/200) 불통일 유지
이유: UI '전체' 프리셋이 서버 기본 30일에 의존한다. 200은 컨트롤러 3곳의 관례다. 통일하면 API 계약이 바뀐다. 재검토 조건: 10 T3·T4 목록 규약 전환 때. (2026-11-09)

### 주식 전용 `*Usd` 필드명 유지
대상: 미리보기, `MarginPort.getUsdBuyableAmount`, `TradeLegSummary.amountUsd`, `TradingReport.totalBoughtUsd/totalSoldUsd` 등. 이유: wire 키라 개명하면 UI 파일 다수가 바뀐다. 이벤트 필드는 EPR 직렬화 대상이라 개명하려면 `@JsonAlias`가 필요하다. 재검토 조건: 없음. (2026-11-10, fable 10 재심 유지. 코인 계좌 합류 트리거는 15 크립토 삭제로 사라졌다)

### 소유권 검증 중복 제거 기각, 응답 코드 400/404/403 구분 유지
이유: 소유권 검증은 이미 중앙화돼 있다. 내부 토큰 경로는 userId가 바디로 들어오므로 입력 오류 400이 맞다. 재검토 조건: 없음. (2026-07 기각, 2026-11 fable 10 재심 유지)

### 내부 API(`/api/internal/**`)는 소유권을 다시 검증하지 않는다
이유: X-Internal-Token 경로라 설계상 의도다. `TradingStatsService`·`PortfolioService`는 userId 범위 조회라 남의 accountId는 빈 결과가 된다(검증 누락이 아니다). 재검토 조건: 05 통합으로 내부 API가 사라질 때 소멸. (2026-11)

### `TradingPreviewService`의 `TradingAccount.from(account).brokerRef().broker()` 우회 유지
이유: trading application.service는 `Account` 애그리게이트를 직접 쓸 수 없다(가드 `trading_batch_path_must_not_carry_account_aggregate`). 투영을 경유하는 것이 정답이다. 재검토 조건: 없음. (2026-11-09 CQ-64)

### 등록 응답은 `StrategyService.assemble` 조회 경로로 통일
이유: kista-ui를 확인했고 UI 수정이 필요 없다. 재검토 조건: 없음. (2026-10-31)

### `ErrorCode` 상수 이름 변경·삭제 금지
이유: kista-ui 계약이다. 예외: 15 C5의 `BROKER_IP_NOT_ALLOWED` 삭제. 재검토 조건: 없음. (2026-11 fable 10 재심 유지)

### stats 서비스의 `to` null→today 인라인 유지
이유: CQ-04에서 호출부에 남기기로 했다. CQ-17의 Clock 주입과 충돌하지 않게 하기 위해서다. 재검토 조건: 없음. (2026-11)

### stats 외부 어댑터의 빈 응답은 빈 결과로 처리
이유: 소비처가 모두 `tryGet`이고 다음 단계가 빈 값을 실패로 바꾼다. 재검토 조건: 없음. (2026-11-08)

### 날짜 계산 예외(KST 기준 변환이 아닌 것) 유지
대상과 이유:
- `TradingBatchResumer`의 04:30 경계 주석: 요일 조합 판정이라 `nextTradeDate`와 계산이 다르다
- `CycleSnapshotCreator`의 `LocalDate.now(KST)`: 주석이 `r.today()`(다음 거래일)와 다름을 밝힌다
- `DcaSummaryAssembler.plusDays(1)`: KST 안의 다음 실행 후보일 계산이다
- 어댑터·컨트롤러의 조회 창 `minusDays`/`plusDays`: 단순 조회 창이다

재검토 조건: 06 단계 모델·시각 모델(T3~T5)이 이 코드를 다시 쓸 때. (2026-11)

## 3. 매매·도메인 정책

### INFINITE 1회 매수금 산정은 바꾸지 않는다(자산비 동적화 재제안 금지)
이유: `unitAmount = (예수금 + 평단 × 보유) / divisionCount`를 매 계획마다 최신 `cycle_position`에서 다시 계산하므로 입출금·실현손익이 이미 반영된다. 시장가 평가 기준은 기각했다. T, priceOffsetRate, 최종회차, 역모드 진입이 가격에 흔들리고, 고점 근처의 미실현 이익이 매수를 부풀린다. 재검토 조건: 없음. (2026-11-11, fable 08 F2 재심 유지)

### INFINITE cap 배수 1.05 유지
이유: 2026-10-25 재평가에서 36일 중 cap 가격 미체결은 1일이었다. 1.10은 체결액이 약 4%p 줄고, 1.02~1.03은 표본 대표성이 부족하다. 1.05는 정량 근거가 없는 운영 판단값이고 문헌에 없는 KISTA 고유 요소다. 재검토 조건: 4절 cap 재평가 트리거. (2026-10-25)

### 리버스모드 쿼터매수 BUY에도 cap을 적용한다
이유: cap의 목적은 증권사 LOC 괴리(15~20%)로 인한 거부를 막는 것이다. 리버스 쿼터는 급락 구간이라 괴리가 더 크다(사용자 확인). 가격만 cap으로 낮추고, 수량은 쿼터 예산 ÷ cap이다. 재검토 조건: 없음. (2026-11)

### INFINITE 보정 주문 3건은 일반모드 BUY마다 상시 부착
이유: 보정 합계가 원장 예수금을 넘으면 뒤쪽(03→02→01)부터 제거하고 base는 바꾸지 않는다. 재검토 조건: 4절 계좌 단위 접수 트리거. (2026-11)

### 리버스모드 별지점은 최근 5거래일 종가 평균
이유: 문헌 규칙이다(`trading-formulas.md`). 재검토 조건: 없음. (2026-11 fable 08 F10 재심 유지)

### PRIVACY는 cap을 넘어도 수량을 다시 계산하지 않는다
이유: cap을 넘은 BUY는 주문가만 `currentPrice × 1.05`로 바꾸고 수량은 FIDA 원본을 따른다. AT_CLOSE 주문의 open 경로 cap은 대칭성 때문에 두는 실질 no-op이다. 재검토 조건: 없음. (2026-11)

### PRIVACY 기준표 조회는 거래일 정확 일치(`findTodayTrade`), 미리보기만 `>=`
이유: 운영 PRIVACY 78개 거래일 모두 D-1 기준표가 있어 결과가 같다. 미래 기준표를 끌어오지 않기 위해서다. 보유 초과·부족분 처리는 의도된 동작이다. 재검토 조건: 없음. (2026-11)

### VR 사이클 `capsIndividualOrders()` 기본값 false
이유: 사다리 전체를 취소하고 다시 저장하는 방식을 유지한다. 재검토 조건: 없음. (2026-11)

### 보유가 0이어도 사이클을 유지한다(`endsCycleOnLiquidation()=false`)
이유: 청산만으로 사이클을 닫지 않는다. 재검토 조건: 없음. (2026-11)

### VR 롤오버: V′ ≤ 0이면 보류(사이클 유지, 알림), V=0·보유 0이면 `nextValue()` 그대로
이유: 자연 성장을 따른다. "V 강제 0 유지" 가드는 폐기했다. 재검토 조건: 없음. (2026-11)

### VR `gStepWeeks=0`·`pStepWeeks=0` 허용(램프 비활성)
이유: 램프 자체를 끄는 값이다. 나머지 파라미터 검증은 생략한다. 재검토 조건: 없음. (2026-11)

### VR 시작 전 재설정은 첫 사이클을 제자리에서 갱신한다
이유: 거부하지 않고 허용하되 새 사이클을 만들지 않는다. startDate가 역전된 사이클이 생기지 않는다. 시작 뒤 재설정은 옛 사이클과 새 사이클 모두 오늘(KST)이다. 재검토 조건: 없음. (2026-11-08 CQ-44)

### VR 실력공식·기본값은 KISTA 현행 유지
이유: 실력공식은 소유자 글(`velog.io/@narafu/VR`, 원저작 라오어 카페 인용)과 코드(`VrPosition`)가 일치한다. 기본값은 소유자 글과 8건 다르지만 사용자가 코드 값이 맞다고 확인했다. 기본값은 운영 판단 값이라 "변경 금지"로 다루지 않는다. 재검토 조건: 기본값은 운영 판단으로 언제든. (2026-11-12, 사용자 결정 F7·F8)

### 원장↔증권사 보유 수량 자동 대사는 만들지 않는다
이유: 사용자가 같은 계좌에서 직접 매매할 수 있어 차이가 정상이다. 감지는 미리보기 `accountHoldingsCheck` 경고로 충분하다. 시스템 밖 거래·입금으로 생긴 괴리는 관리자 수동 보정으로만 반영한다(`TradingReporter` 주석). 재검토 조건: 없음. (2026-11-07 사용자 결정, CQ-39)

### 잔고 급변 가드·브로커 서킷브레이커는 만들지 않는다
이유: 사용자 확정. 재검토 조건: 없음. (2026-07-17)

### INFINITE 최소 시드 검사는 fail-open
이유: 시세 조회에 실패하면 통과시킨다(의도). `BrokerCallGuard.wrap`(fail-closed)을 적용하지 않는다. 등록·시드 수정·인출이 `InfiniteMinSeedGuard` 한 곳을 공유한다. 재검토 조건: 없음. (2026-10-31, CQ-58·63)

### 수동 마감 트리거가 창 밖이면 202를 주고 관리자 알림으로 거부
이유: 응답 코드를 바꾸지 않아 UI 영향이 없다. 비대칭 이유는 주석에 있다. 재검토 조건: 06 판정표(`{accepted, reasonCode}`) 도입 때. (2026-11-08 사용자 결정, CQ-60)

### `markCancelled`는 증권사 상태 우선(last-write-wins), 행 잠금은 넣지 않는다
이유: 증권사 상태를 진실로 본다. 이미 FILLED·PARTIALLY_FILLED인 행만 덮지 못하게 막았고, 경합 창은 남는다. 재검토 조건: 없음. (2026-11-08 사용자 결정, CQ-59)

### 미리보기 경고 합산은 ACTIVE 전략만
이유: 정지 전략은 주문을 내지 않는다. 재검토 조건: 없음. (2026-11-08 CQ-62)

### 정지 전략은 접수 주문이 있을 때만 마감 리포트 대상
이유: 투자금 추가·수동 보정과 같은 기준이다(`CloseReportScope.awaitsCloseReport`). 재검토 조건: 없음. (2026-11)

### 취소된 주문의 체결분은 "기록된 filledQuantity"로 통일한다
이유: 주식 DCA SELL 상한과 재주문 원장 예산의 취소분 차감에 같은 정의를 쓴다. 재검토 조건: 없음. (2026-11 CQ-23·24)

### 투자금 추가·인출·시드 수정 이력은 `cycle_capital_event`에 남기고 과거분은 백필하지 않는다
이유: 원금선이 소급해서 바뀌지 않게 하기 위해서다. 이력은 배포 이후부터 남는다. 시드 수정은 종료 사이클 400, 사이클 잠금, 마감 리포트 창 거절을 함께 적용한다. 재검토 조건: 없음. (2026-11-09 사용자 결정)

### stats 정책: 원금은 개장 시가 평가, 실현 기준은 사이클 개장 시가
- 진행 중 사이클의 매도 확정분은 실현에 포함한다.
- 끝난 전략의 손익은 곡선에 남긴다.
- 평균 수익률은 정의를 유지하고 문서에 고지한다.
- 투자 포인트는 이력을 restore한 뒤 계산한다(흐름일 수익률 0).
- `strategy_cycle.open_amount`를 저장하고, 개장 시가는 따로 두지 않는다.
- `startAmount`(매매 계약)는 바꾸지 않는다.

이유: 사용자 정책 결정. 재검토 조건: 없음. (2026-11-09 CQ-67~70)

### VR "실현손익"은 종료 사이클 기준으로 정의한다
이유: 마지막 보유가 0이면 end−start, 보유가 남으면 장부가 기준 실현분이다. 별도 realized_pnl은 기록하지 않는다. 라벨은 "종료 사이클 손익"이다. 재검토 조건: 없음. (2026-11 CQ-67·68)

### stats 평가액 계산 2종 유지
이유: 곡선·요약용 `totalAssetWithAvgPriceFallback()`은 연속성을 위해 평단으로 대체한다. 투자지수(TWR)용 `totalAssetAtCloseOrNull()`은 실평가가 필요해 종가가 없으면 건너뛴다. 재검토 조건: 없음. (2026-11-08)

### MAINTAIN 로테이션의 이익은 미사용 현금으로 남긴다
이유: 의도된 동작이다. 백테스트도 idleCash로 같게 처리한다. 재검토 조건: 없음. (2026-11-11)

### 장중 추가·인출 시 INFINITE 지정가 매도 목표가가 하루 stale일 수 있다
이유: 알려진 한계로 기록하고 코드는 고치지 않는다. INFINITE LOC 매도는 AT_CLOSE, 지정가 매도(목표가)는 AT_OPEN이다. 재검토 조건: 없음. (2026-11, fa18083cd)

### PRIVACY·DCA 상세에서 `currentRound`는 null
이유: 회차는 INFINITE 전용 개념이다. `divisionCount`도 INFINITE에만 채운다. 재검토 조건: 없음. (2026-11-01 사용자 확정)

### 토스 체결 조회 날짜는 "US 세션일 + 1"
이유: broker는 trading `DstInfo`를 쓸 수 없어 KIS·메리츠와 같은 규칙을 택했다. 재검토 조건: 05·06에서 calendar 모듈로 세션 판정을 모을 때. (2026-11)

## 4. 트리거 조건부 보류

### INFINITE 계좌 단위 "base 먼저, 보정 나중" 접수
대상: 약 100줄, `TradingService.placeAll` 2회 순차 호출. 트리거: 계좌당 INFINITE가 둘 이상이고, base 거절 `InsufficientBalanceEvent`가 실제로 반복될 때. (2026-11, fable 08 재심 유지)

### INFINITE cap 1.05 재평가 방법 재설계
트리거: 표본이 몇 주 쌓이면 착수한다. 식별법 후보는 두 가지다. base 매수 지정가가 평단·기준가보다 낮은 날을 쓰는 방법, 또는 접수 시점 현재가 기록을 추가하는 방법이다. 1.02~1.10 범위를 다시 평가한다. (2026-11, fable 08 재심 유지)

### 전략별 원장 상한에서 MOCK 장중 접수 주문의 시가 혜택
트리거: 접수 시각과 1분봉 데이터를 확보할 때. (2026-10, 0e8a6847)

### KIS APBK0952 취소 분류 처리
트리거: 없음(KIS 삭제 04 단계 A로 소멸 예정). (2026-11)

### "활성 최신 버전" 정렬 기준 3종(version_no 대 created_at) 통일
트리거: 활성 버전이 1개라는 불변식이 깨진 사례가 발견될 때. (2026-11-08)

### 주문 상태 판정을 `Order` 메서드로 올리기
트리거: 같은 상태 조합이 3곳이 될 때. (2026-11-04)

### SaaS 상용화(결제·구독·온보딩)
트리거: 별도 이니셔티브로만 연다. 두 번째 매매 사용자가 생기기 전에는 다중 사용자 골격에 기능을 얹지 않는다(5절 12 D0). (2026-08-31 재검증)

### 재발행(EPR) 설정 변경
트리거: 이벤트·시그니처가 바뀔 때 `listener_id` 정책을 다시 확인한다. 리스너의 패키지·이름을 바꾸면 EPR 고아 row가 생긴다. 이벤트·시그니처가 그대로이고 리스너가 멱등이면 DELETE 대신 `listener_id` UPDATE(`docker-infra.md` 런북). (2026-10-31)

## 5. own-type 허용 원장

게이트 규칙은 `constraints.md` "모듈 경계 own-type". 요약하면 값 타입 복제는 (a) 순환 불가피 또는 (b) 외부 계약 분리일 때만 허용하고, (b)는 wire 변환 코드 소비를 grep한 file:line 증거가 필요하다. 그 밖에는 sharedkernel 승격이나 소유권 이동을 한다. 프로세스 경계를 넘는 wire 타입은 복제하지 않고 `com.kista.shared.contract`에 선언한다(가드 `InternalApiContractTest`). 05 단계 X·R이 실행되면 이 절을 다시 판정한다(contract는 FIDA를 빼고 소멸, 공개 범위는 `api`·`events`로).

### 허용: 성격 표지 예외 `AdminBrokerCredentialException`·`AdminBrokerRateLimitException`·`AdminPrivacyTradeConflictException` (a)
이유: 내부 API의 422/429/409를 root 공용 advice가 매핑할 수 있게 되돌리는 표지다. 값 타입이 아니라 contract 대상도 아니다.

### 허용: narrowing projection
이유는 공통이다. 애그리게이트의 타입 표면을 줄이는 것이 목적이고, 자격증명 은닉이 목적은 아니다(`BrokerAccountRef.toString()`만 마스킹). 대상은 아래와 같다.
- `BrokerAccountRef`(broker): `Account.toBrokerRef()` 1곳에서 변환한다. `SellableQuantity`·`BrokerCredentialException`·`BrokerRateLimitException`은 broker 단독 소유다.
- `StrategyRefLite`(broker): `MockSimulationDataPort` 확장용 초경량 뷰.
- `StrategyCreationRequest`(trading.domain.creation, 원시값 5개): 리졸버 4종이 19필드 `RegisterStrategyCommand` 대신 필요한 필드만 받는다.
- `FidaPlannedOrder`(privacy, 4필드): `Order`(15필드) 대신 FIDA 송수신 필드만 둔다.
- `StrategyRef`(api.benchmark, id/type/ticker): `InvestmentPointsPort.Result.selectedStrategy`와 `BenchmarkComparison.strategy`가 공유한다.
- `NotificationRecipient`(api.notify, User 6필드): notify가 User를 모르게 한다. 변환은 `NotificationRecipients.from(User)`.
- `TradingAccount`(trading, Account 4필드): 변환 `from(Account)`. 가드 `trading_batch_path_must_not_carry_account_aggregate`.

### 게이트 대상 아님: 단일 소유 포트 시그니처 타입
`OrderInstruction`·`OrderResult`·`CancelInstruction`·`PriceSnapshot`(broker)은 복제본이 없고 trading이 직접 소비한다. 순환은 `BrokerAccountRef`로 끊는다.

### 게이트 대상 아님: 포트 역전(DIP)
필요한 쪽이 정의하고 가진 쪽이 구현하는 정상 설계다. 사례는 다음과 같다. `ApprovalPolicyPort`(user 정의, admin 구현), `BrokerEnabledPort`(account 정의, trading `TradingPolicyService` 구현), `AccountOpenOrderCancelPort`(account 정의, trading `AccountOpenOrderCanceller` 구현), `MockSimulationDataPort`(broker 정의, trading 구현).

### 복제본·제3자 구현을 새 설계 선례로 삼지 않는다
이유: 제3자(정의자도 소유자도 아닌 모듈)의 구현은 선례가 될 수 없다. 모듈 간 데이터 의존은 소유자 이벤트 + 소비자 소유 복제본, 또는 내부 HTTP로 한다. 신규 복제 테이블은 마이그레이션 백필이 필수이고, 원본의 모든 쓰기 지점을 전수 확인해 발행해야 한다. 복제본이 비면 소비처마다 실패 양상이 다르다. `BatchContextFactory`는 시끄럽게 중단하고, 등록은 거부되고, `MarketEventNotifier`는 조용히 미발송한다(`user_notify_profile` 사례). 재검토 조건: 05 통합으로 프로세스 경계가 사라질 때. (2026-11)

## 6. 사용자 결정 (2026-11 Fable 검토)

실행 계획 자체는 각 지침서에 있다. 여기는 뒤집지 말아야 할 방향만 둔다.

### 브로커 구성: 주문은 메리츠·토스·Mock, KIS·업비트·바이낸스는 삭제
이유: 메리츠를 우선하는 이유는 환율·매매 수수료다. 토스는 대중적인 증권사라 주문 연결을 남긴다(2026-11-12. 2026-11-11의 "토스 축소" 결정을 대체). KIS 삭제는 KIS 활성 전략 이전이 선행 조건이다(04 W1). 바이낸스·크립토 매매·대시보드 크립토 지표는 전부 삭제한다(15, 2026-11-13). 재검토 조건: 없음. (fable 04 0절, 15 0절)

### 공용 시세는 Alpaca, 주문 경로의 현재가는 계좌 증권사
이유: 관리자 증권사 키로 받는 공용 시장 데이터를 없앤다(토스 키 1년 갱신 부담). 주문 경로(BUY 상한, DCA·VR 기준가)의 현재가는 계좌 증권사 시세 API이고, Alpaca는 화면과 확정 값에 쓴다. 값마다 출처는 하나다. 폴백 체인은 삭제한다. 확정 종가는 마감+20분이다. 환율은 Frankfurter 일 1회 참고환율이다. 재검토 조건: 없음. (2026-11-14, fable 14 D1~D5)

### `GET /api/market/candles`는 공개 유지
이유: 비회원 차트가 나와야 한다. 인증으로 바꾸면 비회원 차트 401과 전역 401 처리가 무한 반복될 위험이 있다. 재검토 조건: 비회원 차트를 없애기로 하면 ui를 먼저 배포한다. (2026-11-14, fable 14 D2)

### 통합 방식: 한 코드베이스 + 실행 role 분리, admin·web 셸 해체
이유: 사용자 결정 D1~D4, 권고대로. 엔진 HTTP는 worker에 둔다. 재검토 조건: 없음. (2026-11-12·13, fable 05 0절)

### fida는 별도 레포·별도 컨테이너
이유: 접점이 `POST /api/internal/fida-orders` 하나다. 운영 호스트만 kista-server로 옮기고, fida는 응답에서 `id`만 읽는다. 재검토 조건: 없음. (2026-11-12)

### 다른 사용자를 받는다. 다만 두 번째 매매 사용자가 생기기 전에는 다중 사용자 골격에 기능을 얹지 않는다
이유: 토스 주문 유지가 다른 사용자를 전제로 한다. 지난 90일 런타임 설정 18커밋·수동 보정 29커밋이 쓰이지 않는 골격에 소모됐다. 재검토 조건: 두 번째 매매 사용자가 생길 때. (2026-11-13, fable 12 D0)

### 기능 존폐
- 주택 벤치마크(KB Land)는 유지한다.
- 수동 체결 보정은 동결하고, 06 뒤 마감 리포트 재실행으로 놓친 체결이 반영되면 삭제한다.
- 코인 DCA 미리보기·DCA 수동 바로 주문·DCA 백테스트는 만들지 않는다.
- CSV 가져오기·반복 거래(가계부)는 만들지 않는다.
- `rejectReason`은 양쪽에서 삭제한다.

(2026-11-13, fable 12 D1~D5, ui D7·D15·D16)

### 삭제된 전략·계좌 데이터는 루트에만 삭제 표시, 자식 데이터 보존
이유: 권고안. 예외는 15 C2(바이낸스 계좌 과거 기록 완전 삭제)다. 1:1 상세 테이블 4개는 부모로 접고, 계좌를 삭제하면 자격증명도 삭제한다. 재검토 조건: 없음. (2026-11-13, fable 11 D1~D3)

### 매매 공식 결함 수정 F1·F3·F4·F6 진행, `trading-formulas.md`는 "변경 금지"가 아니라 "바꾸면 문서와 테스트를 함께 고친다"
이유: "변경 금지" 문구 때문에 F1 같은 결함이 문서화된 동작으로 굳었다. 재검토 조건: 없음. (2026-11-12, fable 08)

### `AssetClass.CRYPTO`(가계부)는 유지
이유: 사용자 입력 분류다. 지우려면 가계부 데이터를 이전해야 한다. 재검토 조건: 없음. (2026-11-14, fable 15 C3)

### 운영 키 목록의 원본은 `.env.example`, `API_DOMAIN`은 Caddy가 infra.env에서 받는다
이유: 시크릿은 sops + age로 kista-infra가 관리한다. KIS 키·`CMC_API_KEY`는 추가하지 않는다(삭제 예정 코드). 재검토 조건: 없음. (2026-11-11, cross-repo 결정 3·6)

## 7. 코인 경로 — 지침서 15 실행 시 이 절을 통째로 지운다

15(바이낸스·업비트·크립토 매매 삭제)가 끝나기 전까지 코드가 남아 있어서 둔다.

- 코인 부분체결 뒤 취소된 주문은 FILLED로 확정한다. 업비트 시장가 매수는 자투리 때문에 cancel로 끝나는 일이 흔하다. 주식은 CANCELLED를 유지한다. (CQ-61)
- `CryptoLedgerRecorder` 증분은 `divide(volume, 8, HALF_UP)`로 한다. 증분을 `LEDGER_SCALE(2)`로 먼저 반올림하면 최종 예수금이 0.01 어긋난다. (2026-11-08)
- `CRYPTO_SCALE = 8`의 2벌은 근거 주석이 다르다(DB numeric 대 업비트 상한).
- Upbit·Binance의 `execute/translate`, 역방향 티커 조회, `CryptoValuations`·`CryptoHashRibbons` prefix 평균은 각 2곳이라 유지한다.
- `isCryptoExchange()`는 전략 타입이 아니라 증권사 capability 술어다.
- stats 크립토 `ZoneOffset.UTC`는 외부 원본(UTC 일봉)을 그대로 쓴다.
- 바이낸스 clientOrderId는 재전송하지 않는다. 결과가 불명이면 SUBMITTING을 유지한다. -1021은 ERROR 로그만 남긴다.
- 출금 권한 주기 점검·가격 컬럼 정밀도 확장·-1021 시각 보정은 트리거 보류다. 입금 주소 표시·입금 알림·격리 마진 레버리지 DCA는 기각했다.

## 8. 되돌릴 수 없는 운영 사실

### Supabase 프로젝트는 2026-08-14에 영구 삭제됐다
롤백 경로가 없다. 백업은 kista-infra Object Storage뿐이다.
