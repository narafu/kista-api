# 지침서 15. 크립토 삭제 — 바이낸스, 코인 매매, 대시보드 코인 지표

> **실행 세션은 먼저 맨 끝의 10절 "실행 전 보정"과 `00-roadmap.md` 9절(공통 규칙)을 읽는다.** 본문과 다르면 그쪽이 우선한다. (2026-11-14)

작성 2026-11-14 (Fable). 근거: `findings/20-crypto-removal-scope.md`(코드·DB·계약 전수, 운영 DB 읽기 조회). 직접 확인한 코드: `TradingPolicySettingsPersistenceAdapter`(정책 JSON 역직렬화), `OrderType.MARKET`·`DcaRoundSkippedEvent`·`pingCrypto`의 호출처.

파일 단위 목록은 `findings/20`의 1~3절과 6절이 원본이다. 이 지침서는 판정과 순서만 적는다.

## 0. 사용자 결정

확정(2026-11-13 밤):

- 바이낸스 관련 기능을 전부 없앤다.
- 크립토 매매를 전부 없앤다.
- 대시보드의 크립토 지표를 전부 없앤다. (지침서 12의 D2 "코인 시장 지표 유지"를 뒤집는다.)

이 결정으로 달라지는 기존 판정:

- 업비트: 04는 "매매 어댑터만 삭제, 김치 프리미엄용 공개 시세는 남긴다"였다. 지표가 사라지므로 `stats/adapter/out/upbit`도 지운다. **업비트 삭제 전체를 이 지침서로 옮긴다**(04 단계 A에는 KIS만 남는다).
- 04의 2-6절(코인 거래소 포트 정리)·단계 E의 코인 부분, 06의 2-6절·T6·D2의 코인 틱 핑, 12의 B3·D4·F7·F8, 13의 코인 지표 View 직행은 대상이 사라진다.

**확정(2026-11-14, 사용자)**: C1은 사용자가 ETH 전략을 내리는 시점에 맞춘다(시점은 사용자가 정하고 직접 내린다). C2~C5는 권고대로. 추가 지침: **DCA 종목에 BITO·EETH를 넣는다**(2-4절, T0). 아래 표는 처음 낸 권고다.

| # | 내용 | 권고 |
|---|---|---|
| C1 | 운영 바이낸스 계좌의 ETH DCA 전략이 살아 있다(ACTIVE, 마지막 주문 2026-11-13). 삭제는 실거래 중단이다. 보유 ETH는 KISTA가 팔지 않는다 | 사용자가 화면에서 전략을 삭제하고 바이낸스·업비트 계좌를 삭제한다(단계 P). 보유 ETH는 거래소에서 직접 처리한다. 종료 시점은 사용자가 정한다 |
| C2 | 바이낸스 계좌의 과거 기록(주문 60, 포지션 61, DCA 실행 74, 규칙 20)을 DB에서 **완전히 지운다**. 지침서 11의 D1("루트에만 삭제 표시, 자식 보존")의 예외다 | 완전 삭제. enum 상수(`BINANCE`·`ETH`·`MARKET`)를 지우면 그 값을 가진 행은 읽는 순간 예외가 난다. 지우기 전에 해당 행을 덤프로 보관한다. 통계에서 이 계좌의 성과가 사라진다 |
| C3 | 가계부의 자산 분류 `AssetClass.CRYPTO` | 유지. 매매·지표와 무관한 사용자 입력 분류다. 지우려면 가계부 데이터 이전이 필요하다 |
| C4 | `/api/meta`의 `brokers[].market`·`minOrderAmount`·`amountScale`과 `Market` enum. 코인이 빠지면 시장이 미국 주식 하나다 | `Market` enum과 메타 필드를 지운다(단계 B). kista-ui가 그 필드를 먼저 걷어야 한다 |
| C5 | `ErrorCode.BROKER_IP_NOT_ALLOWED` 삭제. `constraints.md`의 "ErrorCode 상수 삭제 금지"의 예외다 | 삭제. 코인 거래소 허용 IP 전용이고 kista-ui의 사용처도 함께 사라진다 |

C1의 사전 작업이 끝나기 전에는 단계 A 이후를 시작하지 않는다. 단계 P·U와 전수표 작성, T0(종목 추가)은 지금 해도 된다. T0을 먼저 배포해야 사용자가 ETH 전략을 내리기 전에 EETH DCA로 옮길 수 있다.

## 1. 현재 구조와 범위

| 묶음 | main 파일/줄 | test 파일/줄 |
|---|---|---|
| 바이낸스(주문 어댑터, 공개 시세 어댑터, 예외) | 11 / 766 | 7 / 1,158 |
| 업비트(04에서 이관) | 10 / 662 | 5 / 730 |
| 코인 매매(거래소 포트·값 타입, `CryptoTradingRules`, 10분 틱 일체, 체결 알림, 코인 공포탐욕 포트, 코인 포트폴리오) | 23 / 1,211 | 14 / 1,909 |
| 대시보드 코인 지표(trading-core stats) | 26 / 1,290 | 7 / 1,299 |
| root 코인 공포탐욕 | 3 / 86 | 1 / 98 |
| **통째 삭제 합계** | **73 / 4,015** | **34 / 5,194** |

이 밖에 코인 분기만 걷는 기존 파일이 main 약 30개, 테스트 약 40개다(`findings/20` 6-2절·2-3절).

문제의식: 코인은 주식과 실행 모양이 달라(24시간 시장, 시장가 주문, 폴링 동기화, 소수 수량, 거래소별 최소 금액·수수료) 공용 코드 곳곳에 `isCrypto()`·`isCryptoExchange()` 삼항 분기를 남겼다. 운영 사용자는 1명, 전략은 1개다.

## 2. 목표 상태

### 2-1. 통째로 지우는 것

`findings/20`의 1절(바이낸스), 2-2절의 "통째 삭제" 행, 3-1절(코인 시장 지표와 외부 어댑터 — 코인베이스·코인마켓캡·멤풀·업비트·바이낸스 시세), 3-2절의 코인 공포탐욕 3파일. 엔드포인트 3개가 사라진다:

- `GET /api/crypto-market-indicators`
- `GET /api/accounts/{accountId}/crypto-portfolio`
- `GET /api/accounts/{accountId}/crypto-prices`

`stats/adapter/out/frankfurter`의 `FrankfurterConfig`는 지우지 않는다. 지침서 14의 T4가 최신 환율 조회에 쓴다. 과거 환율 범위 조회(`HistoricalFxRatePort`와 그 어댑터 메서드)는 소비처가 코인 지표뿐이라 지운다. 14의 T4가 아직이면 `FrankfurterConfig`만 남긴다.

### 2-2. 분기를 걷어 한 갈래로 만드는 것

| 대상 | 남는 모양 |
|---|---|
| `Broker` | `UPBIT`·`BINANCE` 상수, `isCryptoExchange()`, 만료 출처 `BROKER_API` 삭제. `keyOnlyCredentials`는 메리츠가 쓰므로 유지 |
| `StrategyTicker`, `StrategyType.DCA` | `BTC`·`ETH` 삭제. DCA 허용 주문 유형에서 `MARKET` 삭제 |
| `OrderType.MARKET` | 삭제. 호출처가 코인 규칙(`DcaRule`·`DcaRuleRequest`)과 주식 어댑터 3곳의 거부 분기뿐이다 |
| `Currency` | `USDT` 삭제. `KRW`는 토스 잔고가 쓰므로 유지 |
| `Market` enum(shared.kernel) | 단계 B에서 삭제(C4). `Broker.market`, `StrategyTicker.markets`, `tradableOn`, `DcaRule.requireCompatible`, `StrategyCreationService`의 시장 검증이 함께 사라진다. finance 모듈의 `Market`은 별개 enum이라 건드리지 않는다 |
| DCA 커널(`DcaCycleOrderStrategy`·`DcaRule`·`BootstrapPosition`) | `crypto ? … : …` 삼항과 `CryptoTradingRules` 입력, `executionHour`, 코인 최소 금액 검증 삭제. 주식 정수 수량 경로 하나 |
| 거래일 계산(`OrderCancelService`·`TradingPreviewService`) | 코인용 `LocalDate.now(clock)` 갈래 삭제. 주식 세션 기준 하나 |
| `AccountLedgerTotalsService`·`UnassignedCashCalculator`·`ManualTradeCorrectionService`·`StrategyService`·`StrategyCreationService`·`OwnedStrategy`·`StrategyDetailAssembler`·`DcaSummaryAssembler`·`BatchContextFactory` | 코인 분기와 `cryptoExchanges` 주입 삭제. 투자금 추가·인출·수동 보정의 "코인 계좌는 리포트 창 검사를 건너뛴다" 예외도 사라진다 |
| 허용 IP | `BrokerSettings.allowedIp`, `SERVER_EGRESS_IP`, `BrokerIpNotAllowedException`, `ErrorCode.BROKER_IP_NOT_ALLOWED`, 관리자 설정 요청·응답의 `allowedIp` 삭제(C5) |
| 알림·하트비트 | `notifyCryptoTrade`, `HeartbeatPort.pingCrypto`, 설정 `heartbeat.crypto-url`(`HEARTBEAT_CRYPTO_URL`) 삭제 |
| root 공포탐욕 | `FearGreedQueryService`에서 코인 캐시·TTL·분기 삭제(CNN 조회만), `FearGreedResponse`의 `crypto` 필드와 컨트롤러의 `SOURCE_CRYPTO` 삭제 |
| 설정·배포 | `CMC_API_KEY`, 업비트 설정 키, Caddy 경로 정규식의 `crypto-market-indicators`(`CaddyRoutingTest`가 대조) |

### 2-3. 남기는 것

- **DCA 전략 타입과 주식 DCA 경로**(`DcaPlanInputCollector` → `DcaCycleOrderStrategy.plan()`). 운영에 Mock QLD DCA가 살아 있다.
- **`DcaIndicator.FEAR_GREED`**. 주식 경로는 CNN 지수를 쓴다. 운영 사용처는 전부 바이낸스 전략이지만 조건 자체는 주식에도 유효하다.
- **`DcaRoundSkippedEvent`**. 주식 DCA(`DcaExecutionRecorder.java:87`)가 발행한다.
- **`Quantities.normalize`와 `numeric(20,8)` 수량 컬럼.** 주식 정수 수량의 왕복에도 쓰인다. 코인 전용 자릿수 상수(`CRYPTO_SCALE` 2곳)만 지운다.
- `accounts.account_no` NULL 허용, `api_key_expires_at`(메리츠·토스가 쓴다).
- `AssetClass.CRYPTO`(C3), `platform`의 `crypto` 패키지(AES 암호화, 이름만 같다).

### 2-4. 추가: DCA 종목 BITO·EETH (2026-11-14 사용자 지침)

둘 다 미국 상장 ETF(NYSE Arca)라 주식 DCA 경로를 그대로 탄다. 코인 코드와 무관하고 삭제보다 먼저 배포한다.

고칠 곳(전수, `QLD` grep 기준):

| 파일 | 내용 |
|---|---|
| `shared/.../kernel/StrategyTicker.java` | 상수 2개. 모양은 `QLD(BigDecimal.ZERO, "…", Market.US_STOCK)`와 같다. 설명 문자열은 발행사의 현재 공식 명칭을 확인해 적는다 |
| `shared/.../kernel/StrategyType.java:31` | DCA 허용 종목 `EnumSet`과 `:18` 주석. 다른 전략 종류에는 넣지 않는다 |
| `shared/.../kernel/TradingPolicySettings.java:45` | DCA 허용 종목 기본값 |
| `trading-core/.../meritz/MeritzExchangeRegistry.java` | 거래소 매핑. QLD(`"0066","AX"`)와 같을 가능성이 높지만 **메리츠 계좌로 두 종목의 현재가를 1회씩 조회해 확인한 뒤** 넣는다(`meritz-api.md:42`의 실측 목록도 갱신) |
| `trading-core/.../kis/KisExchangeRegistry.java` | KIS가 아직 남아 있으면 AMEX 매핑(`"AMEX","AMS"`) 추가. 04 단계 A가 먼저 끝났으면 없다 |
| 테스트 | `StrategyTypeCapabilityTest:69`, `TradingPolicySettingsTest:55,112`(둘 다 `containsExactly`), `MeritzExchangeRegistryTest:20` |
| 문서 | `README.md:13`, `meritz-api.md:42` |

자동으로 따라오는 것: `/api/meta`(`StrategyTicker.values()`), 토스(심볼이 enum 이름), Alpaca(심볼이 enum 이름. 두 종목 모두 `tradable`, 2026-11-14 실호출). 벤치마크의 ETF 목록(`EtfBenchmarkSymbol`)은 별개라 건드리지 않는다.

**운영 정책은 자동으로 바뀌지 않는다.** 저장된 정책 JSON의 DCA 허용 종목 배열은 backfill 대상이 아니다(`TradingPolicySettingsPersistenceAdapter:60-85`는 빠진 enum 키만 채운다). 배포 뒤 사용자가 관리자 설정 화면에서 두 종목을 허용 목록에 넣는다. 화면에서 안 되면 `db/migration-trading`의 jsonb 수정 1건으로 한다.

알아 둘 것: EETH는 거래가 적다(2026-11-13 통합 거래량 56,160주, `iex` 100주). 주문 가격의 기준이 되는 현재가는 계좌 증권사에서 읽으므로(지침서 14의 3-5절) 문제가 없고, 화면의 현재가는 15분 지연 통합 시세가 된다. 지침서 14의 전환(T3) 전에는 지금 경로(관리자 토스 시세)가 두 종목을 그대로 준다.

## 3. 순서

DB에 남은 enum 문자열이 순서를 정한다. `@Enumerated(STRING)` 컬럼과 정책 JSON의 키는 상수가 사라지면 읽는 순간 예외가 난다.

- 정책 JSON(`trading.trading_runtime_settings`의 `trading-policy`)에는 `brokers.BINANCE`·`brokers.UPBIT` 키와 DCA 허용 종목 `BTC`·`ETH`가 들어 있다. `TradingPolicySettingsPersistenceAdapter.deserialize`는 모르는 키를 만나면 `IllegalStateException`("거래 정책 역직렬화에 실패했습니다.")을 던진다. 반대로 키가 **없는** 것은 `backfillMissingEnumKeys`가 기본값으로 채우므로, JSON에서 키를 먼저 지우는 것은 옛 이미지에도 안전하다.
- `accounts.broker`, `strategy.ticker`, `orders.ticker`·`order_type`의 값은 삭제 표시된 행에도 남는다. 삭제 표시 행을 읽는 조회(native query, 통계, 관리자)가 하나라도 있으면 터진다. 그래서 C2는 완전 삭제다.

| 단계 | 내용 | 누가 |
|---|---|---|
| **P** 사전 | (T0 배포 뒤, 사용자가 정한 시점에) ① 사용자가 화면에서 ETH DCA 전략 삭제(미체결 취소 포함), 바이낸스·업비트 계좌 삭제 ② 관리자 설정에서 `BINANCE` 비활성, DCA 허용 종목에서 `BTC`·`ETH` 제거 ③ 운영 조회로 확인: 살아 있는 코인 계좌·전략 0, 코인 미체결 주문 0, 미완료 `event_publication` 중 코인 이벤트 0 ④ `git tag archive/crypto` | 사용자, 실행 세션(확인) |
| **U** UI | kista-ui가 코인 화면·위젯·메타 필드 참조를 걷고 배포한다(`cross-repo.md` G절). API는 아직 그대로라 순서가 안전하다 | fable-ui 실행 세션 |
| **A** 삭제 | 통째 삭제(2-1) + 분기 제거(2-2, `Market` enum 제외) + Flyway 데이터 정리(4절)를 한 배포로 | 실행 세션 |
| **B** 정리 | `Market` enum과 메타 필드 삭제(C4), `strategy_dca_rule.execution_hour` 컬럼 삭제 | 실행 세션 |
| **D** 문서·운영 | 문서 정리, 운영 secret(`CMC_API_KEY`·`HEARTBEAT_CRYPTO_URL`·업비트 키) 제거 요청, healthchecks.io의 코인 체크 삭제(사용자) | 실행 세션, 사용자 |

단계 A를 한 배포로 묶는 이유: 통째 삭제와 분기 제거는 서로 컴파일 의존이라 나눌 수 없고, 데이터 정리는 상수가 사라지는 바로 그 이미지가 기동하기 전에 끝나야 한다(Flyway가 앱보다 먼저 돈다). kista-trading은 stop-first 단일 인스턴스라 옛 이미지와 겹치지 않는다. root(kista-api, blue/green)의 변경은 응답 필드 삭제뿐이고 단계 U가 먼저라 겹침 구간에도 안전하다.

## 4. DB

trading-core 소유 디렉토리(`db/migration-trading`)의 다음 번호로 만든다. `flyway-migration` 스킬을 쓴다. 적용된 마이그레이션(V9·V12·V18의 코인 주석 포함)은 고치지 않는다.

1. **가드**: 삭제 표시되지 않은 `UPBIT`·`BINANCE` 계좌나 `BTC`·`ETH` 전략이 있으면 예외로 중단한다(단계 P 누락 방지).
2. **완전 삭제**(C2): 코인 계좌에 딸린 행을 자식부터 지운다. 대상 테이블과 순서는 실행 세션이 FK를 읽고 정하되 최소한 `dca_execution`, `orders`, `cycle_position`(과 종류별 상세), `cycle_capital_event`, `strategy_cycle`(과 상세), `strategy_dca_*`·`strategy_version`(과 상세), `strategy`, `broker_tokens`, `accounts`를 포함한다.
3. **정책 JSON**: `trading-policy` 값에서 `brokers.UPBIT`·`brokers.BINANCE` 키, 각 브로커의 `allowedIp`, DCA 종목 허용값의 `BTC`·`ETH`를 지운다(jsonb 연산자).
4. (단계 B, 별도 배포) `ALTER TABLE trading.strategy_dca_rule DROP COLUMN execution_hour`.

마이그레이션을 쓰기 전에 실행 세션이 2번의 대상 행을 `pg_dump --data-only`(테이블별 `--where` 또는 `COPY (SELECT …)`)로 떠서 서버에 보관하고, 삭제 SQL과 예상 행 수(주문 60, 포지션 61, DCA 실행 74, 규칙 20, 조건 약 14, 전략 2, 사이클 2 이상, 계좌 2)를 사용자에게 보여 준 뒤 진행한다(지침서 11의 D4와 같은 절차).

root 스키마는 바꿀 것이 없다. `kista_ref.fear_greed_snapshots`에는 CNN 행만 있다(V5가 코인 행을 이미 지웠다).

## 5. 예상 감량과 위험

- main 약 −4,400줄(통째 4,015 + 분기 제거 추정 400), 파일 73개 삭제. test 약 −5,500줄, 파일 34개 삭제. 외부 호출처가 7곳 줄고(바이낸스·업비트·코인베이스·코인마켓캡·멤풀·alternative.me·Frankfurter 과거 환율), 스케줄러 1개(10분 틱)와 하트비트 체크 1개가 사라진다.

| 위험 | 대응 |
|---|---|
| 실거래 중인 전략을 코드가 먼저 끊는다 | 단계 P가 선행 조건. 마이그레이션 가드가 한 번 더 막는다 |
| enum 상수 삭제 뒤 남은 문자열 값 | C2 완전 삭제 + 정책 JSON 정리. 단계 A 배포 직전에 `SELECT DISTINCT`로 `accounts.broker`·`strategy.ticker`·`orders.ticker`·`orders.order_type`·`dca_execution`의 값 집합을 확인한다 |
| 분기 제거가 주식 DCA 동작을 바꾼다 | 6절 |
| 미완료 `event_publication`에 지워진 이벤트 클래스(`CryptoOrderFilledEvent`)가 남는다 | 단계 P ③에서 확인. 남아 있으면 `docker-infra.md`의 "EPR 고아 정리" 절차 |
| 배포 중 UI가 지워진 엔드포인트를 부른다 | 단계 U가 먼저 |
| 과거 성과 통계에서 코인 계좌가 사라진다 | C2에 명시. 덤프 보관 |

## 6. 동작 보존용 선행 테스트

1. 삭제 전에 주식 DCA 경로를 고정하는 테스트가 충분한지 확인한다: `DcaCycleOrderStrategyTest`·`DcaPlanInputCollectorTest`의 주식 케이스(MOO·MOC, 수량 내림, 예산 부족 정책, 조건 4종). 주식 케이스가 코인 케이스와 한 테스트 메서드에 섞여 있으면 먼저 나눈다(별도 커밋).
2. 분기 제거 커밋에서는 **주식 케이스의 기대값을 한 줄도 바꾸지 않는다.** 바뀌어야 통과한다면 분기 제거가 동작을 바꾼 것이다.
3. `MetaControllerTest`, `TradingPolicySettingsTest`, `MarketVocabularyTest`의 기대값은 코인 값만 뺀다.
4. 정책 JSON: 운영에서 읽은 지금의 JSON(코인 키 포함)에 4절 3번의 SQL을 적용한 결과가 새 코드에서 역직렬화되는지 테스트 1건(`TradingPolicySettingsPersistenceAdapter`).
5. 구조 가드: `ModulithArchitectureTest`·`HexagonalArchitectureTest`(양쪽), `ArchitectureMapTest`(`flows.yml`의 `code:` 참조), `CaddyRoutingTest`, `InternalApiContractTest`.

## 7. 태스크

공통 완료 판정: `./gradlew compileJava compileTestJava 2>&1 | grep -E "error|BUILD"`, 단계 끝에 `./gradlew test 2>&1 | grep -E "FAILED|BUILD"` 1회. 실계좌 `*IT`(`BinanceTestnetIT` 포함)는 돌리지 않는다.

| # | 내용 | 모델 | 선행 |
|---|---|---|---|
| T0 | DCA 종목 BITO·EETH 추가(2-4절). 메리츠 거래소 코드 실측 → 코드·테스트·문서 → 배포 → 사용자가 관리자 설정에서 허용. 관찰: Mock 계좌로 두 종목 DCA 전략을 만들어 개장 배치 1회 | Sonnet | 없음 |
| T1 | 전수표: `findings/20`의 목록을 지금 코드와 대조해 파일별 "삭제 / 분기 제거 / 유지"로 확정한다. 추가로 `Upbit|UPBIT|Binance|BINANCE|Crypto|crypto|USDT|BTC|ETH|코인|업비트|바이낸스`를 main·test·resources·docs·`flows.yml`·`.github`·`deploy`·`scripts`에서 grep해 빠진 것을 찾는다. `findings/20`의 "미확인" 3건 중 남은 것(사용자 삭제 cascade가 코인 계좌에서도 같은 경로인지)을 닫는다 | Sonnet | 없음 |
| T2 | 6절 1번: 주식 DCA 테스트 분리·보강 | Opus | 없음 |
| T3 | 단계 P ③의 운영 확인 조회(읽기 전용)와 결과 기록. 덤프와 삭제 SQL 초안을 사용자에게 제시(4절) | Opus | 사용자의 단계 P ①② |
| T4 | 단계 A: 통째 삭제 → 분기 제거 → Flyway(가드·완전 삭제·정책 JSON) → 테스트 기대값. 커밋은 "통째 삭제", "분기 제거", "마이그레이션"으로 나누되 배포는 하나 | Sonnet(통째 삭제·기계적 분기), Opus(DCA 커널·거래일·원장 서비스의 분기, 마이그레이션 검토) | T1~T3, 단계 U 배포 |
| T5 | `flows.yml`(흐름 `crypto-market-indicators`·`crypto-dca` 삭제와 문구 약 20곳), `README.md`, `docs/architecture-map.md`, `CLAUDE.md`, `docs/agents/binance-api.md`·`upbit-api.md` 삭제, 모듈 문서(`dca.md`·`broker.md`·`stats.md`·`trading.md`·`account.md`·`notify.md`·`modules/api/market.md`·`admin.md`), `scheduler-time-table.md`, `code-quality-backlog.md`의 코인 항목, `scripts/dev-seed/dev-seed.sql`의 코인 시드, `.env.example`·`deploy/server/README.md` | Sonnet | T4와 같은 배포 |
| T6 | 단계 B: `Market` enum과 메타 필드 삭제, `execution_hour` 컬럼 삭제 마이그레이션 | Opus(범위 확인), Sonnet(적용) | T4 배포 후 1거래일, kista-ui의 메타 필드 제거 확인 |
| T7 | 단계 D: `cross-repo.md`에 운영 secret 제거 요청 기록, memory `project_broker_strategy`·`project_binance_dca` 갱신 확인 | Sonnet | T4 |

배포 후 관찰(T4): 1거래일. 주식 DCA(Mock QLD)의 주문·이력, 개장·마감 배치의 대상 수, 계좌 등록 화면, 관리자 설정 화면, `/api/meta`, `/api/market/fear-greed`.

## 8. 판단이 갈릴 때의 기본값, 중단 조건, 하지 말 것

기본값:

- 코인만 쓰던 것인지 애매한 코드는 호출처를 grep해 주식 경로의 호출이 0이면 지운다. 1곳이라도 있으면 남기고 T1 전수표에 적는다.
- 주석·로그 문구에만 남은 "코인" 언급은 같은 커밋에서 고친다.
- 테스트 fixture(`TradingFixtures`)의 코인 헬퍼는 사용처가 0이 되면 지운다.
- T0에서 메리츠 실측 코드가 QLD와 다르면 실측값을 쓴다. 조회가 실패하면(메리츠가 그 종목을 지원하지 않음) 그 종목을 넣지 않고 `questions.md`에 적는다.

중단 조건(`questions.md`에 적고 멈춘다):

- 마이그레이션 가드에 걸리는 살아 있는 코인 계좌·전략이 있다.
- 주식 DCA 테스트의 기대값을 바꿔야 통과한다.
- 삭제 표시된 코인 행을 읽어야 하는 기능이 발견된다(예: 탈퇴 사용자 데이터 정리가 코인 계좌 행을 다시 읽는다).
- `OrderType.MARKET`이나 `executionHour`를 주식 경로가 쓰는 곳이 발견된다.

하지 말 것:

- 단계 P 전에 enum 상수를 지우지 않는다.
- DCA 전략 타입, `DcaIndicator.FEAR_GREED`, `Currency.KRW`, `Quantities.normalize`, 수량 컬럼의 자릿수, `keyOnlyCredentials`를 지우지 않는다.
- 적용된 마이그레이션 파일을 고치지 않는다.
- 가계부의 `AssetClass.CRYPTO`와 finance의 `Market` enum을 건드리지 않는다.
- 코인 코드를 지우면서 주식 쪽을 "겸사겸사" 리팩토링하지 않는다. 분기 제거는 주식 쪽 갈래를 그대로 남기는 것까지다(종류별 재구성은 03, 배치 재설계는 06).
- kista-ui를 이 세션에서 고치지 않는다.

## 9. 다른 지침서와의 관계

- **04**: 단계 A에서 업비트가 빠지고 KIS만 남는다. 2-6절과 단계 E의 코인 포트 정리는 하지 않는다. 2-3절의 HTTP 공통 골격 대상에서 바이낸스가 빠진다(메리츠·토스 둘). S1에서 이미 고친 바이낸스 결함(B3)은 코드와 함께 사라진다.
- **03**: 코인 DCA 파이프라인에 대한 언급(77·82·156행)은 대상이 없다. DCA 종류 핸들러는 주식 경로만 다룬다.
- **06**: 2-6절(실행기 둘), T6, D2의 "코인 틱 핑은 그대로"가 사라진다. 스케줄러는 주식 배치 하나다. 이 지침서가 06보다 먼저 실행되면 06의 범위가 그만큼 준다. 순서는 어느 쪽이 먼저여도 되지만 같은 배포에 싣지 않는다.
- **05**: X6의 호출처 목록에서 `CryptoDcaRunner`가 빠진다.
- **11**: C2는 D1의 예외다. `execution_hour` 컬럼 삭제는 11의 T7(1:1 상세 테이블 접기)과 같은 테이블을 건드리므로 T6을 11 T7보다 먼저 하거나 같은 마이그레이션에 넣는다.
- **12**: D2가 "삭제"로 바뀐다. F7은 이 지침서의 T4다. B3의 코인 미리보기, D4, F8, F4의 바이낸스 부분은 대상이 없다.
- **13**: stats 절의 코인 지표 View 직행, `CryptoMarketIndicatorsOpenApiCustomizer`, 설정 정리의 `binance.base-url`·`upbit`·`CMC_API_KEY` 항목은 대상이 없다. 알림 금액 표기의 "코인 체결" 갈래도 사라진다.
- **14**: `FrankfurterConfig`를 남긴다(2-1절).
- **cross-repo**: G절(kista-ui가 걷을 화면·필드, 운영 secret).

## 10. 실행 전 보정 (2026-11-14 — 본문과 다르면 이 절이 우선)

**T4 착수 조건(단계 U)의 확인 방법.** ui의 코인 화면 제거는 2026-11-14 기준 미실행이다(ui 지침서 `12-crypto-removal.md`, ui 실행 순서 S1). 끝나면 `cross-repo.md` G1 행 아래에 "ui 제거 완료(커밋 해시, 배포일)"가 적힌다. 그 기록이 없으면 T4를 시작하지 않는다. T6(메타 필드 삭제)의 "kista-ui의 메타 필드 제거 확인"도 같은 기록으로 본다(ui 12의 T4). T0~T3은 이 조건과 무관하다.

공통 규칙은 `00-roadmap.md` 9절. 측정 원본은 `findings/24`. 사용자 작업 상태: ETH DCA 전략은 종료됐다(2026-11-14 통보). 바이낸스·업비트 계좌 삭제와 관리자 설정 변경은 통보받지 않았다. T3의 확인 SQL로 본다.

전제 정정:

- **`OrderType.MARKET`의 주식 어댑터 거부 분기 3곳**은 `MeritzOrderApi.java:82`, `TossOrderApi.java:183`, `KisOrderApi.java:159`의 `case MARKET -> throw`다(`switch` 안이라 `OrderType.MARKET`으로 검색하면 나오지 않는다). 그 밖에 `MARKET`을 말하는 곳은 `DcaRule.java:40-73`, `DcaRuleRequest.java:41`, `Order.java:88`, `StrategyType.java:33`이고 전부 코인 분기다. T4에서 함께 걷는다.
- 그래서 8절 중단 조건 "`OrderType.MARKET`이나 `executionHour`를 주식 경로가 쓰는 곳이 발견된다"의 뜻은 "**주식 계좌에서 `MARKET` 주문이나 `executionHour` 값이 실제로 만들어지는 경로**가 있다"다. 위 목록처럼 주식·코인이 같이 지나는 클래스 안의 코인 분기는 해당하지 않는다. 확인 방법: 주식 DCA 테스트(T2)에서 `MARKET` 주문이 한 번도 만들어지지 않는다.
- 2-2절의 코인 지표 경로는 Caddy 말고도 `TradingSecurityPolicy.java:20`의 공개 경로 목록과 `tradingcore/web/package-info.java:5`의 주석에 있다. 같이 지운다.
- 6절 테스트 목록에 `ExceptionResponseTableTest.java:73`(trading-core, `BrokerIpNotAllowedException` 행)이 빠져 있다. C5로 `ErrorCode.BROKER_IP_NOT_ALLOWED`를 지울 때 이 행과 `BrokerProblemDetails.java:35`를 같이 지운다.
- 09 T3·13 U9가 `.env.example`에 `CMC_API_KEY`를 추가하라고 한 것은 철회했다. 이 지침서가 그 키를 쓰는 코드를 지운다.

`flows.yml`(T4의 삭제 커밋에서 같이 고친다. `ArchitectureMapTest`가 `code:`의 클래스 존재를 검증한다):

- `code:`에 삭제 대상 클래스가 있는 줄은 2026-11-14 기준 `:711,741,811,834,1629,1637,1646,1655,1662`다. 착수할 때 `git grep -n -iE "crypto|upbit|binance" -- src/test/resources/architecture/flows.yml`로 다시 찾는다.
- `:711`·`:741`(계좌 연결 테스트)과 `:834`(잔고 조회)는 흐름이 남는다. `code:` 목록에서 `UpbitAuthApi`·`BinanceAuthApi`와 코인 포트폴리오 항목만 뺀다.
- `:811`(코인 시장 지표)과 `crypto-dca` 흐름은 흐름째 지운다.
- **API 키 만료 알림은 남는 기능이다**(토스·메리츠가 `ApiKeyExpirySource.ESTIMATED`). 그런데 그 단계(`:1669-1670`, `ApiKeyExpiryScheduler#publishDue`)가 `crypto-dca` 흐름 안에 있고 `landscape`의 `api-key-expiry`(`:58`)가 `flow: crypto-dca`를 가리킨다. 이 단계를 `api-key-expiry`라는 독립 흐름으로 떼어 남기고 `:58`의 `flow:`를 그것으로 바꾼다. `:57`의 `crypto-dca` 항목은 지운다. 단계의 `desc`에서 "업비트·KIS"를 뺀다.

단계 P ③의 확인 SQL(읽기 전용, `ssh kista-server`). 전부 `live`·건수가 0이어야 단계 A를 배포한다.

```sql
-- 살아 있는 코인 계좌
SELECT broker, count(*) FILTER (WHERE deleted_at IS NULL) AS live, count(*) AS total
FROM trading.accounts WHERE broker IN ('BINANCE', 'UPBIT') GROUP BY 1;

-- 살아 있는 코인 전략
SELECT ticker, count(*) FILTER (WHERE deleted_at IS NULL) AS live, count(*) AS total
FROM trading.strategy WHERE ticker IN ('BTC', 'ETH') GROUP BY 1;

-- 코인 미체결 주문
SELECT ticker, status, count(*) FROM trading.orders
WHERE ticker IN ('BTC', 'ETH') AND status IN ('PLANNED', 'SUBMITTING', 'PLACED', 'PARTIALLY_FILLED')
GROUP BY 1, 2;

-- 미완료 코인 이벤트
SELECT event_type, count(*) FROM trading.event_publication
WHERE completion_date IS NULL AND event_type ILIKE '%crypto%' GROUP BY 1;
```

정책 JSON은 `trading.trading_runtime_settings`의 `trading-policy` 행을 읽어 `brokers`에 `BINANCE`가 비활성인지, DCA 허용 종목에 `BTC`·`ETH`가 없는지 눈으로 본다. `total`(삭제 표시 포함)의 행 수는 4절의 삭제 SQL이 지울 예상 행 수로 쓴다.

기본값:

- **T0의 메리츠 거래소 코드**: 실행 세션은 실계좌를 부르지 않는다. BITO·EETH는 QLD와 같은 NYSE Arca 상장이므로(`PROGRESS.md` 2차 결정 기록: Alpaca에서 둘 다 `ARCA`) `MeritzExchangeRegistry`에 QLD와 같은 매핑을 넣는다. 확인은 배포 뒤 사용자가 한다: 관리자 설정에서 두 종목을 허용하기 **전에** 메리츠 계좌의 전략 등록 화면에서 두 종목의 현재가가 조회되는지 본다. 조회되지 않으면 그 종목은 허용하지 않고 매핑을 고친다. 이 순서를 T0의 완료 보고에 적는다.
- T0의 상수 설명 문자열(발행사 명칭)은 발행사 사이트에서 확인한다. 확인할 수 없으면 `ProShares Bitcoin ETF`(BITO), `ProShares Ether ETF`(EETH)로 넣고 "명칭 미확인"을 보고에 적는다.
- T0에서 허용 종목 목록의 테스트(`StrategyTypeCapabilityTest:69`, `TradingPolicySettingsTest:55,112`)는 `containsExactly`라 순서를 본다. 새 종목은 목록 끝에 더한다. `KisExchangeRegistry`는 04 단계 A가 아직이면 같이 더하고, 끝났으면 대상이 없다.
- 4절의 삭제 순서를 FK에서 정할 수 없는 테이블이 있으면(FK가 없는 참조) 자식으로 보고 먼저 지운다.

중단 조건의 대체 경로(8절에 더한다):

- 살아 있는 코인 계좌·전략이 있다(위 SQL의 `live`가 0이 아님): 사용자의 단계 P가 끝나지 않은 것이다. 삭제 커밋은 로컬에 두고 배포를 요청하지 않는다. 기다리는 동안 T1·T2(코드 삭제 없음)와 T0은 진행한다.
- 주식 DCA 테스트의 기대값을 바꿔야 통과한다: 걷어 낸 분기 중 주식 쪽 갈래를 잘못 고른 것이다. 그 파일의 변경을 되돌리고 코인 분기만 다시 걷는다. 기대값은 고치지 않는다.
- 삭제 표시된 코인 행을 읽어야 하는 기능이 나온다: 탈퇴 사용자 정리(`TradingUserCascadeListener`에서 시작)가 후보다. C2(완전 삭제)로 행이 없어지므로 읽을 것이 없다. 완전 삭제 뒤에도 그 기능이 0행으로 정상 종료하는지 테스트로 확인하고 진행한다.

배포(단계 A): **되돌릴 수 없다.** 삭제 마이그레이션이 나가면 이전 이미지로 되돌려도 지운 행은 돌아오지 않는다. 복구 수단은 덤프뿐이다. 코드 쪽 문제는 롤백하지 않고 앞으로 고친다. 그래서 배포 전 확인표(로드맵 9-4)에 넣는다: 위 SQL 결과, 4절의 삭제 SQL과 예상 행 수, 덤프 파일 경로, kista-ui의 코인 화면 제거가 배포됐다는 확인(`cross-repo.md` G1). 매매 시간대 밖. 단계 B(T6)는 그 뒤 1거래일이 지나고 kista-ui의 메타 필드 참조가 빠졌다는 기록이 `cross-repo.md`에 있을 때 한다.
