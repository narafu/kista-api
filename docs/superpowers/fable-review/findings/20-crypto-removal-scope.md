# 20. 크립토 삭제 범위 측정 (바이낸스·코인 매매·대시보드 코인 지표)

측정일 2026-11-14, 읽기 전용(코드·DB 변경 없음, 운영 DB는 SELECT만). 줄 수는 `wc -l`. 경로 약어: `TC` = `trading-core/src/main/java/com/kista/tradingcore`, `TCT` = `trading-core/src/test/java/com/kista/tradingcore`.
업비트·KIS 삭제는 `guides/04-broker.md` 8절 단계 A 몫이다. 여기서는 업비트 파일을 "04A 공유"로만 표시하고 줄 수는 참고용으로 따로 적는다.

## 0. 먼저 알아야 할 사실 (실행 세션용)

1. **바이낸스는 운영에서 실사용 중이다.** 계좌 1개(활성, 사용자 1명), ETH DCA 전략 1개 ACTIVE, 최근 주문 2026-11-13. 삭제는 실거래 중단이다. 삭제 전 사용자 확인·포지션 정리(보유 ETH 처리)가 코드 작업보다 선행한다(4절).
2. **DCA 전략 타입은 주식도 쓴다.** UPRO·QLD 주식 DCA가 있고 운영에 MOCK ACTIVE QLD DCA 1건이 살아 있다. DCA 전략·테이블·`DcaCycleOrderStrategy`는 유지하고 코인 분기만 걷는다.
3. **DCA 조건 `FEAR_GREED`는 코인·주식 두 경로가 갈린다.** 주식 배치 경로(`DcaPlanInputCollector`)는 CNN 지수(`FearGreedIndexPort`, `TC/trading/application/service/DcaPlanInputCollector.java:58`), 코인 경로(`CryptoDcaRoundExecutor`)는 alternative.me 코인 지수(`CryptoFearGreedIndexPort`, `TC/trading/application/service/CryptoDcaRoundExecutor.java:67`)다. 운영 FEAR_GREED 조건은 전부 바이낸스 전략에만 있어 **코인 공포탐욕**이다(현재 버전 3건, 삭제 전략 버전 포함 4건). 삭제 후 이 조건 enum 상수는 주식도 쓰는 CNN 경로가 남으므로 `DcaIndicator.FEAR_GREED` 상수는 유지다.
4. **`/api/meta`·`Broker`/`Market`/`Currency`/`StrategyTicker` enum 상수 삭제는 DB `@Enumerated(STRING)` 값 때문에 데이터 정리가 먼저다.** 운영 `trading.trading_runtime_settings`(키 `trading-policy`) JSON이 `brokers.BINANCE`·`brokers.UPBIT` 키와 DCA `allowedValues`의 BTC·ETH를 담고 있다. enum에서 상수를 지우면 EnumMap 역직렬화가 실패할 수 있다(추정 — 어댑터 `TradingPolicySettingsPersistenceAdapter`의 unknown 키 처리 미확인). 코드 삭제 배포 전에 이 JSON 행을 정리하는 단계가 필요하다.

## 1. 바이낸스 코드 전수

### 1-1. main

| 디렉토리·파일 | 파일 | 줄 | 메모 |
|---|---|---|---|
| `TC/broker/adapter/out/binance/` | 7(CLAUDE.md 포함) | 541 | `BinanceExchangeAdapter` 239, `BinanceHttpClient` 174, `BinanceAuthApi` 50, `BinanceSymbolRegistry` 34, `BinanceClientOrderIds` 22, `BinanceConfig` 18, `CLAUDE.md` 5 |
| `TC/broker/domain/model/binance/BinanceApiException.java` | 1 | 24 | 바이낸스 전용 예외 |
| `TC/stats/adapter/out/binance/` | 2 | 166 | `BinanceMarketDataAdapter` 150(현물·선물 공개 시세), `BinanceConfig` 16 |
| `TC/stats/application/port/output/BinanceMarketDataPort.java` | 1 | 34 | 지표 서비스 전용 포트 |
| **main 합계** | **11** | **766** | 전부 통째 삭제 |

yml·환경변수: 바이낸스 전용 키는 없다(`application.yml`에 `binance:` 블록 없음 — base URL은 Config 클래스 상수, 자격증명은 사용자별 계좌 키). 관련 공용 키는 `CMC_API_KEY`(코인마켓캡 — 5절 대시보드 지표 몫, `trading-core/src/main/resources/application.yml:118`, `.env.example`, `deploy/server/README.md:91`).
compose·`.github`·`deploy`: 바이낸스·업비트 언급은 `deploy/server/caddy/kista-api.caddy:18`의 정규식 `crypto-market-indicators` 한 곳뿐이다(`CaddyRoutingTest`가 컨트롤러 경로와 대조하므로 엔드포인트 삭제 시 같이 고친다).
관리자 정책: `shared/src/main/java/com/kista/shared/kernel/TradingPolicySettings.java:30-31`(`UPBIT`·`BINANCE` 기본 BrokerSettings, `SERVER_EGRESS_IP` 상수 22행 — 코인 거래소 허용 IP 안내값 전용).

### 1-2. test

| 파일 | 줄 |
|---|---|
| `TCT/broker/adapter/out/binance/BinanceExchangeAdapterTest` | 441 |
| `…/BinanceHttpClientTest` | 229 |
| `…/BinanceTestnetIT` | 132 |
| `…/BinanceAuthApiTest` | 108 |
| `…/BinanceSymbolRegistryTest` | 27 |
| `…/BinanceClientOrderIdsTest` | 18 |
| `TCT/stats/adapter/out/binance/BinanceMarketDataAdapterTest` | 203 |
| **test 합계** | **7 파일 / 1,158줄** |

### 1-3. 문서·시드

| 파일 | 줄 | 처리 |
|---|---|---|
| `docs/agents/binance-api.md` | 94 | 삭제 (CLAUDE.md 참조 목록 `CLAUDE.md` 63줄 내 2곳 수정) |
| `TC/broker/adapter/out/binance/CLAUDE.md` | 5 | 디렉토리와 함께 삭제 |
| `docs/agents/modules/tradingcore/{dca,broker,stats,trading,account,matching,notify,web,trading-testing}.md`, `modules/shared/{platform,kernel}.md`, `modules/api/{market,admin}.md` | 합 약 70줄 언급 | 문장 수정(언급 수는 6절 표) |
| `docs/agents/code-quality-backlog.md` | 60개 언급/970줄 | 코인 관련 항목 정리 |
| `scripts/dev-seed/dev-seed.sql` | 82개 언급/432줄 | BINANCE·UPBIT 시드 블록(224-414행 일대) 삭제 |

## 2. 크립토 매매 코드

### 2-1. DCA 실행기 둘의 정체

| 실행기 | 대상 | 진입점 | 판정 |
|---|---|---|---|
| 주식 DCA 배치 | 주식 계좌(UPRO·QLD) | 매매 배치 → `DcaPlanInputCollector`(입력 수집, 194줄) → `DcaCycleOrderStrategy.plan()` (`matching/.../DcaCycleOrderStrategy.java:27-28` 주석) | 유지 |
| 코인 DCA 틱 | 코인 계좌(업비트·바이낸스) | `CryptoDcaScheduler`(`TC/trading/adapter/in/schedule/CryptoDcaScheduler.java:25` 10분 cron, 락 `crypto-dca`) → `CryptoDcaRunner` → `CryptoDcaRoundExecutor`(242줄)가 규칙마다 `decideRule()` 직접 호출 + `CryptoOrderSynchronizer`(체결 동기화) | **통째 삭제** |

→ 지침서 06이 말한 "둘"은 이 둘이다. 크립토 삭제로 코인 DCA 틱이 사라지고 주식 배치 경로 하나만 남는다. 공통 커널 `DcaCycleOrderStrategy.decideRule()`(규칙 판정)은 주식 경로가 계속 쓰므로 유지하되 코인 분기를 걷는다. `BatchContextFactory.java:51`은 이미 "코인 계좌 전략은 배치에서 제외"(`continue`)한다.

### 2-2. 항목별 판정

| 항목 | 위치 | 판정 | 근거·메모 |
|---|---|---|---|
| 코인 거래소 포트·라우터 | `CryptoExchangePort`(45줄)·`CryptoExchanges`(31줄) | 통째 삭제 (04A 공유) | 구현체가 업비트·바이낸스뿐 |
| 코인 값 타입 | `broker/domain/model/Crypto{Balance 28,Candle 14,OrderResult 22}` | 통째 삭제 | 코인 포트 전용 |
| 코인 거래 규칙 | `shared/.../kernel/CryptoTradingRules.java`(47줄) | 통째 삭제 | `DcaRuleInputs` 입력(`DcaCycleOrderStrategy.java:36-41`)과 어댑터가 쓰는 코인 전용 타입 |
| 코인 DCA 틱 일체 | `CryptoDcaScheduler 34`·`CryptoDcaRunner 125`·`CryptoDcaRoundExecutor 242`·`CryptoOrderSynchronizer 135`·`CryptoLedgerRecorder 63`·`CryptoFailure 23`·`CryptoDcaExecutionUseCase 8` | 통째 삭제 | 24/7 10분 스케줄러 포함 |
| 24시간 스케줄러 heartbeat | `HeartbeatPort.pingCrypto()`(`trading/application/port/output/HeartbeatPort.java:7`), `HeartbeatAdapter.java:28` | 분기만 제거 | 코인 틱 핑 전용 메서드·설정(dead-man 코인 URL, 추정 — yml 키 미확인) |
| 코인 공포탐욕 포트 | `CryptoFearGreedIndexPort 11`·`CryptoFearGreedIndexAdapter 43`(`https://api.alternative.me/fng/?limit=1`) | 통째 삭제 | 코인 틱만 소비(`CryptoDcaRoundExecutor.java:67`) |
| 코인 체결·알림 | `CryptoOrderFilledEvent 22`·`CryptoTradeNotifier 41`, `TradingUserNotificationPort.notifyCryptoTrade`(`notify/.../TradingUserNotificationPort.java:41`)·`TradingUserNotificationAdapter.java:141`, `DcaRoundSkippedEvent`(주식 DCA도 발행 가능성 — `DcaExecutionRecorder.java:87`) | 이벤트·notifier 삭제, `notifyCryptoTrade`만 제거, `DcaRoundSkippedEvent`는 주식이 쓰는지 확인 후 유지(추정) | |
| 코인 포트폴리오 조회 | `CryptoPortfolioService 107`·`CryptoPortfolioController 63`·DTO 2·유스케이스·`CryptoPortfolio` | 통째 삭제 | 엔드포인트 5절 |
| 코인 계좌 현재가·잔고 분기 | `AccountLedgerTotalsService.java:33,54`(`cryptoExchanges` 주입), `UnassignedCashCalculator.java:29,63` | 분기만 제거 | 주식 USD 경로 유지 |
| 브로커 `isCryptoExchange()` 분기 | `OrderCancelService.java:59,82,98,168`, `TradingPreviewService.java:78,101,106`, `StrategyCreationService.java:76`, `BatchContextFactory.java:51`, `CryptoPortfolioService.java:76` | 분기만 제거 | 거래일 계산(`LocalDate.now(clock)` vs `DstInfo`)이 코인용 24/7과 주식용으로 갈리는 곳 — 코인 삭제 시 주식 쪽 하나로 단순화 |
| `Market.isCrypto()` 분기 | `DcaRule.java:44,59-70`(`executionHour`은 코인 MARKET 주문만, 최소금액 `minOrderAmount`), `DcaCycleOrderStrategy.java:40,96-137,162`, `DcaSummaryAssembler.java:39,61-65`, `BootstrapPosition.java:13,26-30`, `StrategyService.java:143`, `ManualTradeCorrectionService.java:60,130,159,179-181`, `OwnedStrategy.java:27`, `StrategyDetailAssembler.java:180`, `StrategyDetail.java:23` | 분기만 제거 | 각 분기가 `crypto ? (코인) : (주식 정수 수량)`꼴. 주식 측만 남김 |
| `Market` enum | `shared/.../kernel/Market.java:11-12`(`CRYPTO_KRW`·`CRYPTO_USDT`), `isCrypto`·`minOrderAmount`·`amountScale` | `US_STOCK` 단일 상수로 축소 | `Broker.market`·`StrategyTicker.markets`·`BrokerMeta`·`/api/meta`가 소비. 단일 상수면 `Market` 자체를 없앨지는 별도 판단(추정: `tradableOn`·`DcaRule.requireCompatible`·`StrategyCreationService.java:54` 검증이 함께 단순화됨) |
| `Currency` enum | `Currency.java` `USDT(2)` | USDT 상수 삭제 | `KRW`는 유지: `TossHoldingsApi.java:75`가 `Currency.KRW` 사용, `Currency.format`의 KRW 분기 존재. `JPY`는 기존부터 미사용(범위 밖, 언급만) |
| `amountScale` 분기 | `Currency.java:11-14`·`Market.amountScale()` | 유지 | USD(2)·KRW(0)가 남으므로 헬퍼 구조는 그대로. USDT만 사라짐 |
| 수량 소수 처리 | `Quantities.normalize`(`shared/.../kernel/Quantities.java:5-15`) | **주식도 쓰므로 유지** | 주석이 "정수 주식 수량과 같게 맞춘다"고 명시, DB `numeric(20,8)`(V8, V11)은 주식 정수 왕복에도 필요. 코인 상한 상수만 제거: `BootstrapPosition.CRYPTO_SCALE`, `ManualTradeCorrectionService.CRYPTO_SCALE` |
| `StrategyTicker` | `BTC`·`ETH`(`StrategyTicker.java:21-22`, `Market.CRYPTO_*`) | 상수 2개 삭제 | `StrategyType.DCA.capability()`(`StrategyType.java:31`)·`TradingPolicySettings.java:40`에서도 제거 |
| `Broker` | `UPBIT`·`BINANCE`(`Broker.java:14,16`) | 상수 삭제 (04A와 같은 편집) | `keyOnlyCredentials`는 `MERITZ`도 true(`Broker.java:15`)라 필드·`Account.java:40`·`AccountService.java:67,141`은 유지. `apiKeyExpirySource.BROKER_API`는 업비트 전용이라 상수·`AccountService.java:98,153` 분기 제거(추정). `Account.java:25` 정렬 리스트에서 2개 제거 |
| 허용 IP | `BrokerSettings.allowedIp`(`shared/.../BrokerSettings.java:7` "업비트만 값"), `SERVER_EGRESS_IP`, `BrokerIpNotAllowedException`·`ErrorCode.BROKER_IP_NOT_ALLOWED`(`ErrorCode.java:8`)·`BrokerProblemDetails.java:35` | 필드·상수·예외 삭제 후보 | `ErrorCode` 상수 삭제는 constraints.md "ErrorCode 변경·삭제 금지(kista-ui 계약)"와 충돌 — UI 세션 합의 필요. 코인 전용이라 UI도 같이 지우므로 합의되면 삭제 |
| 전략 타입 DCA | `StrategyType.DCA` | **유지** | 주식 UPRO·QLD 사용, 운영 MOCK QLD DCA ACTIVE 1건(4절) |
| 코인 전용 DCA 필드 | `DcaRule.executionHour`, `strategy_dca_rule.execution_hour`(V9·V12), `DcaRuleRequest.java:28,41`·`DcaRuleResponse.java:24`, `OrderType.MARKET` 허용(`StrategyType.java:32`) | 필드·허용값 제거 (컬럼은 expand/contract 2배포) | `OrderType.MARKET`이 주식 경로에서 쓰이는지 별도 확인(추정: 코인 전용) |
| 거래소 수수료 | `CryptoTradingRules.FeeSide` | 삭제 | |
| `DcaIndicator.FEAR_GREED` | `DcaIndicator.java:16` | 유지 | 주식 DCA도 CNN 경로로 지원(주식 DCA에 FEAR_GREED 조건 실사용은 없음 — 운영 조건 6건 중 FEAR_GREED는 전부 바이낸스) |

### 2-3. 코인 전용 테스트(전략·브로커 쪽)와 분기 테스트

완전 삭제 14파일 1,909줄은 6절. 분기 제거로 고쳐야 하는 기존 테스트(코인 케이스 삭제·수정, 추정 줄 수 아님 — 코인 언급 파일 기준): `StrategyServiceTest.java`(3,098줄), `TradingStatsServiceTest.java`(1,415), `AccountServiceTest.java`(823), `TradingPreviewServiceTest.java`(819), `CapitalLedgerServiceTest.java`(761), `CyclePositionPersistenceAdapterTest.java`(654), `OrderCancelServiceTest.java`(603), `ManualTradeCorrectionServiceTest.java`(501), `DcaCycleOrderStrategyTest.java`(343), `DcaPlanInputCollectorTest.java`(540), `AccountControllerTest.java`(291), `MetaControllerTest.java`(139), `TradingPolicySettingsTest.java`(135), `MarketVocabularyTest.java`(89), `HexagonalArchitectureTest.java`(506), `TradingFixtures.java`(105, testFixtures), `ApiKeyExpirySchedulerTest.java`(99) 등 약 40개 파일에 코인 문자열이 있다.

## 3. 대시보드 크립토 지표

### 3-1. 코인 시장 지표 (trading-core `stats` 모듈)

| 구성 | 위치 | 줄 | 출처 |
|---|---|---|---|
| 엔드포인트 | `GET /api/crypto-market-indicators` (`CryptoMarketIndicatorController.java:30`, 35줄) | | |
| 서비스 | `CryptoMarketIndicatorService` | 216 | 김프·코인베이스 프리미엄·펀딩비·OI·밸류에이션·롱숏·반감기·도미넌스·알트 시즌·Hash Ribbons(`flows.yml:806`) |
| 응답 DTO | `CryptoMarketIndicatorsResponse` | 210 | |
| 도메인 | `CryptoMarketIndicators 115`·`CryptoHashRibbons 91`·`CryptoPremiums 93`·`CryptoValuations 66`·`Dated{HashRibbon,LongShortRatio,PremiumPair,Valuation,Value}`·`FundingRate{Quote,Record}` | 약 430 | |
| 유스케이스 | `CryptoMarketIndicatorUseCase` | 10 | |
| 외부 API 어댑터 | Upbit 시세(`UpbitMarketDataAdapter 121`, 04A), Binance 시세(`BinanceMarketDataAdapter 150`, 1절), `CoinbaseMarketDataAdapter 77`+Config, `CoinMarketCapAdapter 122`+Config 18+Properties 7(`CMC_API_KEY`), `MempoolBlockHeightAdapter 56`+Config 16(블록 높이·해시레이트) | 포트 4개 (`BlockHeightPort`·`HashrateHistoryPort`·`CryptoMarketMetricsPort`·`CoinbaseMarketDataPort`) | |
| 스케줄러·테이블 | 없음 — 요청 시 계산, 인메모리 캐시(추정) | | 전용 테이블 없음 |
| Caddy | `deploy/server/caddy/kista-api.caddy:18` 정규식 | | |

main 합계(바이낸스·업비트 시세 어댑터 제외): **26 파일 / 1,290줄** (`TC/stats/**` 위 목록). test: `CryptoMarketIndicatorServiceTest 527`·`CryptoMarketIndicatorControllerTest 160`·`Crypto{HashRibbons 144,Premiums 128,Valuations 109}Test`·`CoinbaseMarketDataAdapterTest 94`·`CoinMarketCapAdapterTest 137` = **7 파일 / 1,299줄**. 주식 지표와 섞여 있지 않다: 컨트롤러·서비스가 별도이고 `stats`의 주식 기능(`TradingStatsService`·`HistoricalCandlePort`(Alpaca)·`Frankfurter`)과 어댑터가 다르다. 갈림 지점은 `TC/stats/adapter/out/` 디렉토리(alpaca·frankfurter 유지 / binance·coinbase·coinmarketcap·mempool·upbit 삭제).

### 3-2. 공포탐욕지수 (root `:api` `market` 모듈) — 코인·CNN이 한 서비스에 섞임

| 구성 | 위치 | 줄 | 판정 |
|---|---|---|---|
| 코인 어댑터 | `CryptoFearGreedAdapter`(alternative.me 이력 API) | 65 | 삭제 |
| 코인 포트·이력 | `CryptoFearGreedPort 9`·`CryptoFearGreedHistory 12` | 21 | 삭제 |
| 섞인 서비스 | `FearGreedQueryService`(93줄) — `CRYPTO_*` TTL·캐시 상수 6개(27-35행), `cryptoHistory()`(70행), `cryptoTtl()`(85행), 소스 분기(54행) | 93 | 분기만 제거: CNN 조회만 남기면 약 25줄(추정) |
| 컨트롤러·DTO | `FearGreedController.java:26,39`(`SOURCE_CRYPTO`), `FearGreedResponse.java:14,32-33`(`SourceView crypto` 필드) | 42+46 | **응답 계약 변경**: `crypto` 필드 제거 → kista-ui `widgets/fear-greed-card` 영향 |
| 엔티티·마이그레이션 | `FearGreedSnapshotEntity.java:32`(`source` "CRYPTO"|"CNN"), `V5__fear_greed_crypto_snapshots_delete.sql`(이미 CRYPTO 행 삭제) | | 운영 `kista_ref.fear_greed_snapshots`는 CNN 1,642행뿐(4절) — 데이터 정리 불필요. `source` 컬럼 자체는 CNN 단일이 돼 유지할지 별도 판단 |
| 스케줄러 | `FearGreedScheduler`(35줄)는 CNN 수집 | | 유지 |

root 쪽 삭제: main 3파일/86줄 + `FearGreedQueryService` 분기, test `CryptoFearGreedAdapterTest 98` + `FearGreedQueryServiceTest 229`(크립토 케이스 삭제).

### 3-3. 대시보드의 DCA 조건 메타

`/api/meta`의 `dcaIndicators`(`DcaIndicatorMeta.java`)는 enum 전체를 노출 — `DcaIndicator`가 유지되므로 변화 없음.

## 4. DB

### 4-1. 스키마 (Flyway)

크립토 **전용 테이블은 없다.** 코인과 주식이 같은 테이블을 쓴다(`strategy_dca_*`, `dca_execution`, `orders`, `cycle_position`). 코인 전용 컬럼·enum 문자열은 아래뿐이다. enum 컬럼은 `VARCHAR(20)` + `@Enumerated(STRING)`이고 DB CHECK 제약이 없어(V1 확인) 상수 삭제 자체로 스키마가 깨지지 않는다(행 값만 문제).

| 대상 | 위치 | 비고 |
|---|---|---|
| `trading.strategy_dca_rule.execution_hour` | V9(`strategy_dca_version`에 추가)·V12(`strategy_dca_rule`로 이전), 체크 `execution_hour BETWEEN 0 AND 23` | 코인 MARKET 전용(주식 NULL). 컬럼 삭제는 코드 삭제 배포 후 후속. trading-core는 expand/contract 예외(constraints.md Flyway)라 한 배포로도 가능하나 구 이미지 겹침 시 validate가 걸리므로 두 단계 권장(추정) |
| `trading.accounts.account_no` NULL 허용 | V10 | 메리츠도 쓰므로 유지 |
| `trading.accounts.api_key_expires_at` | V10·V19 | 업비트(API 조회)·KIS·토스·메리츠 사용 → 유지 |
| `broker` 값 `UPBIT`·`BINANCE`, `ticker` 값 `BTC`·`ETH` | `accounts.broker`, `strategy.ticker`, `orders.ticker`, `dca_execution` | 행 삭제 필요 |
| 코멘트 | V9·V12 `COMMENT ON COLUMN … 코인 DCA`, V18 | 기존 마이그레이션 수정 금지(checksum) — 그대로 둔다 |
| `kista_ref.fear_greed_snapshots` CRYPTO 행 | V5가 이미 삭제 | 운영 CNN 1,642행뿐 → 추가 작업 없음 |

필요한 마이그레이션(신규 번호, trading-core `migration-trading` V21~):
1. **데이터 정리 DML** — BINANCE·UPBIT 계좌 사슬 삭제/소프트 삭제(규칙: 사이클 → 계좌 순, constraints.md "소프트 삭제"). 서비스 계층 cascade(`UserDeletedEvent` 경로 재사용 가능 여부는 미확인)와 직접 SQL 중 택일.
2. **`trading_runtime_settings` JSON 갱신** — `trading-policy` 값에서 `brokers.BINANCE`·`brokers.UPBIT` 키와 `strategies.DCA.ticker.allowedValues`의 BTC·ETH 제거, 기본값 ticker 확인.
3. (후속) `ALTER TABLE trading.strategy_dca_rule DROP COLUMN execution_hour`.
root 스키마(`public`·`finance`·`kista_ref`)는 변경 없음. `finance.AssetClass.CRYPTO("크립토")`(`src/main/java/com/kista/api/finance/domain/model/AssetClass.java:14`)는 가계부 자산 분류로 거래와 무관하다 — **삭제 대상 아님, 사용자 확인 필요**("크립토 지표 전부"에 포함시킬지).

### 4-2. 운영 읽기 전용 조회 결과 (2026-11-14 조회, `ssh kista-server` → `docker exec kista-postgres psql`, SELECT만)

| 항목 | 값 |
|---|---|
| 계좌 수(삭제 제외/포함) | BINANCE 1/1, UPBIT 1/1, KIS 1/1, MERITZ 3/3, TOSS 1/1, MOCK 1/6(삭제 5). 코인 계좌 보유 사용자 수 1명 |
| UPBIT 계좌 하위 | 전략·사이클·주문 0건 (계좌만 존재) |
| BINANCE 전략 | DCA·ETH ACTIVE 1(삭제 안 됨) + DCA·ETH PAUSED 1(소프트 삭제) |
| BINANCE 사이클 | 진행 중(end_date NULL) 2: 활성 전략 1, 삭제 전략 1 |
| BINANCE `cycle_position` | 61행, 최근 2026-11-13 |
| BINANCE `orders` | 60건: FILLED 59, FAILED 1, 기간 2026-10-31~2026-11-13 (**마지막 주문 어제**) |
| BINANCE `dca_execution` | 74건: ORDERED 59, SKIPPED 15, 마지막 거래일 2026-11-13 |
| BINANCE DCA 규칙 | 20행(BUY·MARKET·DAILY, 시각 지정). 활성 전략 규칙 14행 |
| DCA 조건 종류별(코인) | AVG_PRICE 4, FEAR_GREED 4, RSI 4, SMA 2 (활성 전략 현재: AVG_PRICE 3, FEAR_GREED 3, RSI 3, SMA 2) |
| DCA 조건(주식) | 전부 MOCK의 `AVG_PRICE` 5건(GTE 3·LTE 2). 주식 DCA의 FEAR_GREED·RSI·SMA 사용 0건 |
| 코인 종목 ticker 전략 | ETH 2(활성 1) — BTC 0 |
| 주식 DCA | MOCK QLD ACTIVE 1(+ 삭제된 MOCK UPRO·QLD, TOSS QLD, MERITZ QLD 각 1) → DCA 전략 타입 유지 근거 |
| 크립토 지표 테이블 | 없음(요청 시 계산). `kista_ref.fear_greed_snapshots`는 CNN 1,642행(2021-01-21~2026-11-13), CRYPTO 0 |
| 정책 JSON | `trading_runtime_settings`의 `trading-policy`에 `UPBIT enabled=false allowedIp=168.107.63.152`, `BINANCE enabled=true allowedIp=168.107.63.152` 키 존재 |

## 5. 계약·주변 영향

### 5-1. `/api/meta` 번들에서 빠지는 값

| 필드 | 빠지는 값 | 위치 |
|---|---|---|
| `brokers` (`BrokerMeta`) | `UPBIT`, `BINANCE` 항목. `market`(`CRYPTO_KRW`·`CRYPTO_USDT`), `minOrderAmount`, `amountScale`, `apiKeyExpirySource`(`BROKER_API`), `keyOnlyCredentials` 값 | `src/main/java/com/kista/api/web/dto/BrokerMeta.java:8-27` |
| `tickers` (`TickerMeta`) | `BTC`, `ETH` | `StrategyTicker.java:21-22` |
| `orderTypes` | `MARKET` (코인 전용 확인: `KisOrderApi.java:159`·`TossOrderApi.java:183`·`MeritzOrderApi.java:82`가 MARKET을 거부) | `OrderType` |
| `strategyTypes[DCA]` | `availableTickers`에서 BTC·ETH, 주문 유형 `MARKET` 제거 | `StrategyType.java:30-34` |
| `markets` | 변화 없음 — `MetaController.java:57`의 `Market`은 finance 모듈의 별개 enum(`com.kista.api.finance.domain.model.Market`) | |
| `dcaIndicators` 등 DCA 상수 | 변화 없음 | |
`MetaControllerTest.java`(139줄)가 enum 번들을 검증하므로 기대값 수정이 필요하다. `ErrorCode.BROKER_IP_NOT_ALLOWED`(`ErrorCode.java:8`)는 kista-ui 계약 상수라 UI 합의 후 삭제.

### 5-2. 크립토 전용·관련 엔드포인트

| 메서드·경로 | 컨트롤러 | 판정 |
|---|---|---|
| `GET /api/crypto-market-indicators` | `CryptoMarketIndicatorController.java:30` | 삭제 (Caddy 정규식도 수정) |
| `GET /api/accounts/{accountId}/crypto-portfolio` | `CryptoPortfolioController.java:38` | 삭제 |
| `GET /api/accounts/{accountId}/crypto-prices` | `CryptoPortfolioController.java:54` | 삭제 |
| `GET /api/market/fear-greed` | `FearGreedController.java:33` | 유지, 응답 `crypto` 필드만 제거(`FearGreedResponse.java:14`) |
| `POST /api/accounts`·`PUT …/credentials`·`POST …/test` | `AccountController`(trading-core) | 유지, `broker` 허용 값·문서 문구(`AccountRequest.java:19-23`, `CredentialsRequest.java:12-14`, `AccountResponse.java:17-25`)에서 업비트·바이낸스 제거 |
| `PUT /api/trading-cycles/{id}/dca-config`·`GET …/dca-executions` | `TradingCycleController.java:140,272` | 유지 (주식 DCA) |
| 관리자 설정 `PUT /api/admin/settings` 계열 | `AdminSettingsController`, `AdminSettingsRequest.java:37-54`·`RuntimeSettingsResponse.java:35`의 `allowedIp` | 유지, `allowedIp` 필드 제거 검토 |

### 5-3. wire 계약·알림·문서

| 대상 | 위치 | 영향 |
|---|---|---|
| wire 타입 | `shared/.../contract/stats/PortfolioCurrentResponse.java:14`·`PortfolioOrderResponse.java:19`의 `Currency currency` 주석 "USD·USDT·KRW" | 타입은 그대로, `Currency.USDT` 삭제 시 주석만. 코인 전용 wire 타입은 없음(`contract` 패키지에 코인 record 없음) |
| 알림 문구 | `TradingUserNotificationAdapter.java:141-146` `notifyCryptoTrade`("코인 체결"), `DcaRoundSkippedEvent` 문구, 코인 DCA 오류 `DcaRule.java:44-70` 메시지 4개 | 코인 체결 알림 삭제. `CryptoTradeNotifierTest 77`·`TradingUserNotificationAdapterWordingTest 74` 수정 |
| `flows.yml` | `src/test/resources/architecture/flows.yml` 47개 언급/1,779줄: 지표 흐름 `crypto-market-indicators`(51·803-811행), 흐름 `crypto-dca`(57-58·`api-key-expiry` 흐름이 `crypto-dca`를 가리킴), 계좌 등록·검증 단계 desc(690-770), 포트폴리오(832-834), 배치 컨텍스트 제외 문구(88·288), 전략 생성 규칙(512-516) | 흐름 2개 삭제 + 문구 약 20곳 수정. `ArchitectureMapTest`가 `code:` 참조를 검증하므로 삭제한 클래스 참조는 반드시 제거 |
| `README.md` | 4개 언급: 8·13·14·15행(코인 매매 소개, 업비트·바이낸스, 코인 DCA 10분 주기) | 4곳 수정 |
| `docs/architecture-map.md` | 3개 언급: 97행(`CNN · Crypto`), 106행(코인 틱 heartbeat), 165행(`platform` crypto = 암호화 패키지, 무관) | 2곳 수정 |
| `CLAUDE.md`(프로젝트) | 63줄 중 2곳(바이낸스·업비트 문서 포인터) | 수정 |

### 5-4. kista-ui (읽기 전용 집계, 상세는 UI 세션 몫)

패턴 `binance|upbit|crypto|coin|바이낸스|업비트|코인|usdt`(대소문자 무시)로 `ts·tsx·md·json` 160개 파일(이 중 docs 계열 약 29개 제외하면 코드·테스트 약 131개), 총 859회 언급(`node_modules`·`.next` 제외).

| 대표 경로(파일 수) | |
|---|---|
| `features/strategy/create-strategy/sections`(11)·`model`(8) | 전략 생성 폼의 코인 분기 |
| `features/account/create-account/steps`(8) | 계좌 등록 거래소 선택 |
| `widgets/account-detail`(8)·`widgets/strategy-detail`(4)·`widgets/strategy-card`(2) | 코인 포트폴리오·상세 |
| `widgets/crypto-market-indicators`(6) | 코인 지표 위젯 (통째 삭제) |
| `widgets/fear-greed-card`(2)·`widgets/dashboard`(2)·`widgets/stats-overview`(3)·`widgets/benchmark-comparison`(4+2)·`widgets/admin-trade-list`(3)·`widgets/market-holiday-calendar`(2) | 분기·통화 표기 |
| `entities/account`(4)·`entities/trade`(3)·`entities/strategy`(3)·`entities/meta`(3)·`entities/finance/lib`(3) | 모델·메타 타입 |
| `shared/lib`(3)·`shared/lib/format`(2) | USDT·KRW 포맷 |

## 6. 합계

### 6-1. 통째 삭제 (줄 수 `wc -l`)

| 묶음 | main 파일/줄 | test 파일/줄 | 비고 |
|---|---|---|---|
| 바이낸스 (1절) | 11 / 766 | 7 / 1,158 | 시세 어댑터 포함 |
| 코인 매매 (2절: 포트·값 타입·`CryptoTradingRules`·틱 일체·체결 알림·코인 공포탐욕 포트·코인 포트폴리오) | 23 / 1,211 | 14 / 1,909 | `CryptoExchangePort` 등 일부는 04A와 같은 파일 |
| 대시보드 코인 지표 (3-1, 바이낸스·업비트 시세 어댑터 제외) | 26 / 1,290 | 7 / 1,299 | |
| root 코인 공포탐욕 (3-2, 파일 단위) | 3 / 86 | 1 / 98 | `FearGreedQueryService` 분기는 6-2 |
| **이 지침서 범위 소계** | **63 / 3,353** | **29 / 4,464** | |
| 참고: 업비트 (04A 공유) | 10 / 662 | 5 / 730 | 여기서 중복 집계하지 않음 |
| 참고: 합산(바이낸스+업비트+코인 전부) | 73 / 4,015 | 34 / 5,194 | |
| 문서 | `docs/agents/binance-api.md` 94줄(삭제), `upbit-api.md` 66줄(04A), 디렉토리 CLAUDE.md 2(5줄씩) | | 수정 대상 문서는 5-3·1-3절 |

### 6-2. 분기만 제거되는 기존 파일 (main) — 수정이 따르는 공용 코드

| 파일 | 제거되는 것 |
|---|---|
| `shared/.../kernel/Broker.java` | UPBIT·BINANCE 상수, `isCryptoExchange()`, `BROKER_API` 만료 출처 |
| `shared/.../kernel/Market.java` | `CRYPTO_KRW`·`CRYPTO_USDT`·`isCrypto`·`minOrderAmount`(주식은 ZERO). 상수 1개만 남으면 `Market` 폐지 검토 |
| `shared/.../kernel/Currency.java` | `USDT` |
| `shared/.../kernel/StrategyTicker.java`·`StrategyType.java` | BTC·ETH, DCA 주문 유형 `MARKET` |
| `shared/.../kernel/OrderType.java` | `MARKET` (추정 — 코인 전용 확인됨, 다른 소비처 grep 필요) |
| `shared/.../kernel/TradingPolicySettings.java`·`BrokerSettings.java` | 업비트·바이낸스 기본값, `SERVER_EGRESS_IP`, `allowedIp` |
| `matching/.../DcaCycleOrderStrategy.java`·`DcaRule.java`·`BootstrapPosition.java` | `crypto` 삼항 분기, `tradingRules` 입력, `executionHour`, 코인 최소금액 검증 |
| `trading/.../DcaSummaryAssembler`·`DcaRuleRequest/Response`·`StrategyService`·`StrategyCreationService`·`StrategyDetailAssembler`·`StrategyDetail`·`OwnedStrategy`·`ManualTradeCorrectionService`·`OrderCancelService`·`TradingPreviewService`·`BatchContextFactory`·`AccountLedgerTotalsService`·`UnassignedCashCalculator` | `isCrypto`·`isCryptoExchange`·`cryptoExchanges` 분기와 주입 |
| `account/.../AccountService`·`Account`·`ApiKeyExpiryScheduler`·`AccountRequest/Response/CredentialsRequest` | 거래소 키 만료 분기, 업비트·바이낸스 문구, `Account.java:25` 리스트 |
| `broker/.../Kis·Toss·MeritzOrderApi`(MARKET 거부 case)·`BrokerProblemDetails`·`BrokerIpNotAllowedException`·`DailyTransaction` 주석 | `MARKET` case, IP 예외 |
| `notify/...TradingUserNotificationPort·Adapter` | `notifyCryptoTrade` |
| `trading/.../HeartbeatPort·HeartbeatAdapter` | `pingCrypto` |
| root `FearGreedQueryService`·`FearGreedController`·`FearGreedResponse` | `CRYPTO_*` 상수·`cryptoHistory`·`crypto` 응답 필드 (서비스 93줄 → 약 25줄, 추정) |
| root `BrokerMeta`·`MetaController` | 값 변화만(코드는 enum 순회라 변경 최소) |
| `AdminSettingsRequest`·`RuntimeSettingsResponse` | `allowedIp` |
| 설정 | `trading-core application.yml:99-100(upbit)·118(cmc)`, `.env.example`·`deploy/server/README.md`(`CMC_API_KEY`), `kista-api.caddy:18` |

테스트 분기 수정은 약 40개 파일(2-3절). `TradingFixtures`(testFixtures)·`MetaControllerTest`·`HexagonalArchitectureTest`·`ModulithArchitectureTest` 계열은 삭제한 패키지 참조가 걸리는지 확인해야 한다. 삭제 후 `NamedInterface`·모듈 문서(`modules/tradingcore/{broker,stats,trading,dca}.md`)의 패키지 트리도 갱신.

### 6-3. 단순화 효과(분기 제거로 사라지는 구조)

- DCA 판정 커널이 `crypto ?` 삼항 없이 주식 정수 수량 경로 하나가 된다(`DcaCycleOrderStrategy.java:96-137`, `DcaRule.java:59-70`).
- 거래일 계산이 코인 24/7(`LocalDate.now(clock)`)과 주식 `DstInfo` 두 갈래에서 하나로(`OrderCancelService.java:82`, `TradingPreviewService.java:101`).
- 브로커 속성(`keyOnlyCredentials`·`apiKeyExpirySource`·`allowedIp`)이 메리츠·KIS·토스 중심으로 단순화(`BROKER_API` 출처와 IP 허용 안내 사라짐).
- 매매 스케줄러가 10분 틱 하나 줄어 `scheduler-time-table.md`·healthchecks 코인 핑이 삭제된다.
- UI 메타(`/api/meta`)에서 거래소별 시장·최소금액·통화 필드 사용처가 사라질 수 있다(UI 세션 판단).

### 6-4. 실행 순서에 영향을 주는 위험·확인 항목

1. **운영 바이낸스 활성 전략(ETH DCA)·보유 ETH 처리와 사용자 확인** — 코드 삭제 배포 전에 전략 정지·정리 결정 필요. 삭제 후에도 거래소에 ETH가 남는다.
2. **`trading_runtime_settings` JSON 역직렬화** — enum 상수 삭제 배포 전에 JSON에서 BINANCE·UPBIT·BTC·ETH 제거(4-1절). 새 이미지 기동 실패를 막는다(추정, 미검증).
3. **`ErrorCode` 상수(`BROKER_IP_NOT_ALLOWED`)·`/api/meta`·`/api/market/fear-greed` 응답 변경은 UI 계약** — UI 세션이 먼저 코인 화면·필드 참조를 걷고 배포해야 한다(`/api/crypto-market-indicators` 삭제는 UI 위젯 삭제 후).
4. **배포 창 404** — 메모리 `project_deploy_lag_404`: 엔드포인트 삭제 배포 중 구·신 컨테이너 겹침 구간에서 UI가 404를 볼 수 있다.
5. **`AssetClass.CRYPTO`(가계부)는 대상 아님으로 가정** — 사용자 확인 필요(4-1절).
6. **미확인**: `HeartbeatPort.pingCrypto`의 코인 전용 healthchecks URL 설정 키, `OrderType.MARKET`·`DcaRoundSkippedEvent`의 주식 측 사용 여부 grep, 사용자 삭제 cascade(`UserDeletedEvent`) 경로 재사용 가능성, EPR(`event_publication`)에 남은 코인 이벤트 행 정리(docker-infra.md "EPR 고아 정리" 절차 참고 — 이벤트 클래스를 지우면 고아 행 발생).
