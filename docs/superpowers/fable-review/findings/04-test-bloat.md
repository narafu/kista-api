# 04. 테스트 비대 측정 (읽기 전용)

측정: 2026-11-11, 정적 grep/awk/python 집계 + 표본 읽기. 테스트·빌드 미실행. `추정`은 표본 외삽이다.
범위: `src/test`(api), `trading-core/src/test`, `shared/src/test`, 각 testFixtures. 합계 **480 파일 / 76,693줄 / @Test 3,728개**(평균 20.6줄/테스트, fixture 242줄 포함).
분류는 휴리스틱(어노테이션·파일명)이라 경계 파일은 오분류 가능.

## A. 종류·모듈별 분포

| 모듈 | 종류 | 파일 | 줄 | @Test |
|---|---|---:|---:|---:|
| api | 단위(*Test) | 101 | 11,983 | 572 |
| api | WebMvc 슬라이스 | 34 | 4,685 | 225 |
| api | DataJpa 슬라이스 | 19 | 2,024 | 98 |
| api | SpringBootTest(컨텍스트·보안) | 8 | 796 | 13 |
| api | *IT | 2 | 172 | 3 |
| api | 아키텍처(ArchUnit/Modulith) | 7 | 1,056 | 42 |
| api | 문서·계약(ApiDocs/ArchMap) | 1 | 386 | 0 |
| trading-core | 단위 | 217 | 44,792 | 2,277 |
| trading-core | DataJpa | 25 | 4,161 | 166 |
| trading-core | WebMvc | 24 | 3,369 | 166 |
| trading-core | *IT | 6 | 730 | 17 |
| trading-core | SpringBootTest | 3 | 265 | 6 |
| trading-core | 아키텍처 | 3 | 89 | 3 |
| shared | 단위 / 계약 | 24 / 1 | 1,852 / 91 | 135 / 5 |
| fixtures | api·shared·tc | 5 | 242 | - |

- trading-core 단위 테스트가 전체 줄의 58%(44.8k). 슬라이스(WebMvc+DataJpa) 합 14.2k(18.5%), 통합·아키텍처·계약은 합 약 3.4k(4.5%)뿐이다. 즉 비대의 본체는 "mock 기반 서비스 단위 테스트"다.
- main 대비 3배 초과 상위 20(테스트줄/main줄, 클래스명 매칭 가능한 것만. main 줄은 파일 전체 줄 기준):

| # | 테스트 | 테스트줄 | main줄 | 배수 |
|---|---|---:|---:|---:|
| 1 | DcaRuleDecisionTest | 207 | 16 | 12.9 |
| 2 | StrategyServiceTest | 3,098 | 270 | 11.4 |
| 3 | RedisBlacklistAdapterTest | 92 | 14 | 6.5 |
| 4 | AdminSettingsControllerTest | 303 | 54 | 5.6 |
| 5 | TradingServiceTest | 2,418 | 478 | 5.0 |
| 6 | CryptoMarketIndicatorControllerTest | 160 | 35 | 4.5 |
| 7 | UserDeletedStreamConsumerTest | 110 | 28 | 3.9 |
| 8 | InvestmentPointsQueryServiceTest | 327 | 89 | 3.6 |
| 9 | TradingOrderExecutorTest | 634 | 181 | 3.5 |
| 10 | ManualTradingServiceTest | 622 | 176 | 3.5 |
| 11 | StrategyVrDetailPersistenceAdapterTest | 277 | 79 | 3.5 |
| 12 | StrategyStatusInternalControllerTest | 169 | 48 | 3.5 |
| 13 | RootSecurityPolicyTest | 154 | 44 | 3.5 |
| 14 | KisTokenPersistenceAdapterTest | 158 | 46 | 3.4 |
| 15 | AccountServiceTest | 823 | 247 | 3.3 |
| 16 | BenchmarkServiceTest | 808 | 243 | 3.3 |
| 17 | FinanceBudgetPersistenceAdapterTest | 223 | 66 | 3.3 |
| 18 | TradingStatsServiceTest | 1,415 | 435 | 3.2 |
| 19 | CapitalLedgerServiceTest | 727 | 221 | 3.2 |
| 20 | AssetSnapshotServiceTest | 390 | 121 | 3.2 |

주의: 배수가 큰 것은 "비대"가 아니라 "규칙이 서비스에 몰린 곳"일 수 있다(StrategyService는 270줄이지만 등록 정책 분기를 전부 품는다). 1·3위급 소형 파일은 절대 줄 수가 작아 영향이 없다. 영향이 큰 건 2·5·15·16·18.

## B. 계층 중복 검증 (4개 기능 대조)

| 기능 | 계층별 현황 | 중복 판정 |
|---|---|---|
| 전략 등록 | 서비스 `StrategyServiceTest` 에 `register_*` 약 80개(VR 램프·시드·부트스트랩·DCA·티커 규칙) / 컨트롤러 `TradingCycleControllerTest` register 계열 ~15 / 영속 `StrategyPersistenceAdapterTest`·`StrategyCreationPersisterDbTest` / 도메인 `domain/creation` 리졸버 테스트는 `VrRegistrationTest`·`VrRampInputTest` 12개뿐 | **계층 중복은 낮다(약 10%: divisionCount·티커 불일치·중복 티커가 서비스+컨트롤러에 2회)**. 대신 **규칙이 리졸버가 아닌 서비스 mock 레벨에서 검증**되어 단언 1개에 stub 20줄이 붙는다(층 위치 문제) |
| INFINITE 주문 생성 | 커널 `InfiniteStrategyTypeTest`(567)·`InfinitePositionTest`·`InfiniteCycleOrderStrategyTest` / `CycleOrderComputerTest`(395) / `BuyOrderPriceCapperTest`(493) / `TradingServiceTest`·`TradingOrderExecutorTest`·`ManualTradingServiceTest`에서 reverse·skip 분기 재검증 / `BacktestEngineTest`(1,162)가 같은 공식을 시뮬레이션 | 공식 수치는 커널·골든에 1벌. 서비스 계층은 "분기 선택·skip" 오케스트레이션을 별도 검증하므로 의미 중복은 약 15~20%(reverse/leg 존재 조합 중복 추정) |
| 계좌 등록 | `AccountServiceTest`(823, 52개) / `AccountControllerTest`(291, 19) / `AccountPersistenceAdapter*`(141+66+67) / 마스킹은 `AccountNumberMaskerTest` 9 + DTO 2곳 | 소유권 403·마스킹은 3계층에서 각 1~3회 반복. 중복 약 15%(추정) |
| 통계 조회 | `TradingStatsServiceTest`(1,415, 65) + `AccountStatisticsServiceTest` + 컨트롤러 2개(144, 129) + `ReturnMetricsTest`·`InvestmentIndexCalculatorTest` | 수익률 수식은 `ReturnMetricsTest`와 서비스 테스트 양쪽에서 값 단언(6+8회). 중복 약 15~20%(추정) |

종합 추정: **의미 중복(같은 규칙을 2계층 이상에서 같은 입력으로 단언) 약 10~20%**. 크기 문제의 주 원인은 중복이 아니라 (1) 서비스 단위 테스트의 stub/setup 비중 (2) 같은 4xx 매트릭스·소유권 검증의 모듈별 반복(Finance 5개 서비스 `SecurityException` 계열 단언 74회)이다.

## C. mock 배선 테스트

- 전체 `when(` 3,723 / `verify(` 2,127 / `assertThat(` 4,929. verify가 단언보다 많거나 같은 파일 80개(15,343줄, 696 @Test = 줄 20%). 그러나 상위는 `TradingServiceTest`(v222/a6), `TradingOrderExecutorTest`, `CryptoDcaRoundExecutorTest`, `CycleRotationServiceTest`, `OrderCancelServiceTest` 등 **오케스트레이션 순서·호출 여부가 곧 안전 속성**이라 순수 배선이 아니다(G 군).
- 진짜 로직 없는 위임: main 비주석 30줄 이하 클래스의 테스트 77파일 7,493줄이 후보지만 enum·record·전략 룰 포함이라 과대. 표본(StrategyStatusInternalControllerTest, UserChannelNotifierTest, FidaOrderControllerTest)으로 보면 **순수 배선 추정 약 25~30파일, 2,500~3,500줄**(추정, 전체의 3~4.5%).
- `@WebMvcTest` 58클래스 391 `@Test` 4,673줄: `jsonPath` 없이 상태코드·mock 호출만 보는 테스트가 **241개(62%), 2,349줄**. 그중 147개(약 1,400줄)는 401/403/400/404 등 오류 상태 단언. 응답 필드까지 확인하는 테스트는 38%.
- DTO `*Response/*Request/*Mapper` 전용 테스트 9파일 497줄(대부분 필드 복사 확인, 추정 저가치 300줄).

## D. setup 보일러플레이트

| 지표 | 값 |
|---|---|
| `@BeforeEach` 본문 합계 | 1,163줄 (475 파일 중 평균 2.4줄) |
| private/static 헬퍼 본문 합계 | 3,357줄 (파일당 7줄, 전체 4.4%) |
| `@Mock/@MockitoBean` 필드 | 744개 |
| 8인자 이상 단일행 `new X(` | 51건(멀티라인 생성은 미집계라 실제는 훨씬 많음) |
| `TradingFixtures` 사용 파일 | trading-core 42개 / `DomainFixtures` 14개 (api) |
| fixture 우회 직접 생성 | `new Account(` 63회(21파일) / `new Strategy(` 207회 |
| 최대 파일 setup | `StrategyServiceTest` 첫 `@Test`가 338행(11%), mock 포트 20여 개, `when(` 425·`new Strategy/Account` 37; `TradingServiceTest` 240행, `when(` 407 |

- 복붙 헬퍼(이름 중복 6회 이상): `strategy`(11), `account`(10), `order`(9), `position`(7), `buy`(7), `bd`(7), `adapter`(7), `rule`(6), `plan`(6), `command`(6), `record`(6). 이름이 같은 헬퍼는 대부분 `new Strategy(...)`·`new Account(...)` 래퍼로 `TradingFixtures`와 기능이 겹친다(본문 동일 여부는 표본 확인, 전수 diff 아님).
- 감량 레버: 직접 생성 270건을 fixture 빌더로 치환하면 건당 평균 2~4줄 절감 → **약 600~900줄(추정)**.

## E. 파라미터화 여지

- 현재 `@ParameterizedTest` 26개, `@Nested` 15개. 클래스 내 "토큰 구조(식별자·상수·숫자 정규화) 동일 메서드 5개 이상" 기준으로 잡힌 건 8개 클래스뿐, **합 349줄**:

| 클래스 | 동형 메서드 | 줄 |
|---|---:|---:|
| AdminSettingsControllerTest | 6 | 112 |
| MockBrokerAdapterTest | 5 | 66 |
| StrategyVrDetailTest | 6 | 42 |
| HexagonalArchitectureTest | 5 | 40 |
| TradingExceptionHandlerTest | 5 | 30 |
| BinanceAuthApiTest | 5 | 21 |
| DcaCycleOrderStrategyTest | 5 | 20 |
| KisResponseParserTest | 6 | 18 |

- 결론: 엄격 기준의 파라미터화 이득은 작다. `StrategyServiceTest`의 `register_*_rejects*` 류는 입력과 stub이 달라 토큰 구조가 다르므로 위 표에 안 잡혔지만, 완화 기준(같은 메서드 호출 + 예외 단언)으로는 클래스당 10~20개 묶음 가능(추정 300~500줄).

## F. 500줄 초과 테스트 클래스 전수 (27개)

| 줄 | @Test | 클래스 | 검증 내용 |
|---:|---:|---|---|
| 3,098 | 150 | StrategyServiceTest | 전략 등록·일시정지/재개/보관/삭제·시드 수정 정책(mock 기반) |
| 2,418 | 70 | TradingServiceTest | 개장/마감 배치 오케스트레이션·주문 접수·skip·알림 |
| 1,415 | 65 | TradingStatsServiceTest | 통계·수익률·캐시 무효화 |
| 1,162 | 42 | BacktestEngineTest | 백테스트 시뮬레이션(INFINITE/VR) |
| 836 | 53 | TradingCycleControllerTest | 사이클·전략 REST 엔드포인트 슬라이스 |
| 823 | 52 | AccountServiceTest | 계좌 등록·키 회전·소유권 |
| 819 | 40 | TradingPreviewServiceTest | 주문 미리보기 |
| 808 | 39 | BenchmarkServiceTest | 주택/ETF 벤치마크 비교 |
| 727 | 50 | CapitalLedgerServiceTest | 원금 투입·인출 원장 |
| 659 | 22 | TossHttpClientTest | 토스 HTTP 클라이언트 요청·오류 매핑 |
| 654 | 27 | CyclePositionPersistenceAdapterTest | cycle_position 영속(DB) |
| 634 | 32 | TradingOrderExecutorTest | 주문 실행·접수 |
| 631 | 32 | UserServiceTest | 가입·승인·프로필 |
| 622 | 21 | ManualTradingServiceTest | 수동 매매 |
| 603 | 31 | OrderCancelServiceTest | 주문 취소 |
| 580 | 33 | BacktestServiceTest | 백테스트 서비스 |
| 567 | 28 | InfiniteStrategyTypeTest | INFINITE 주문 공식 |
| 565 | 24 | VrReconfigureServiceTest | VR 재설정 |
| 562 | 25 | InvestmentIndexCalculatorTest | 투자 지표 계산 |
| 540 | 39 | DcaPlanInputCollectorTest | DCA 계획 입력 수집 |
| 536 | 34 | CryptoDcaRoundExecutorTest | 코인 DCA 라운드 실행 |
| 527 | 31 | CryptoMarketIndicatorServiceTest | 코인 시장 지표 |
| 522 | 35 | PrivacyStrategyTest | PRIVACY 전략 공식 |
| 518 | 30 | OrderPersistenceAdapterDbTest | orders 영속(DB) |
| 515 | 25 | VrStrategyTypeTest | VR 공식 |
| 511 | 18 | VrCycleRolloverServiceTest | VR 사이클 롤오버 |
| 506 | 30 | HexagonalArchitectureTest | ArchUnit 레이어 규칙 |

27개 합 약 20.8k줄(전체 27%). 상위 2개(5.5k)가 7%.

## G. 보호 가치 높은 테스트 (감량 대상 아님)

그룹 간 일부 겹침(예: StrategyServiceTest는 상태전이에 포함, TradingServiceTest는 주문 안전)이라 합산 금지.

| 군 | 파일 | 줄 | @Test | 비고 |
|---|---:|---:|---:|---|
| 매매·VR·PRIVACY 공식 커널(`matching/`) | 21 | 4,122 | 285 | 변경 금지 SSOT(trading-formulas) |
| 백테스트 엔진·골든 | 3 | 1,486 | 52 | 공식 회귀 골든 |
| 원장(CapitalLedger·CyclePosition 등) | 16 | 3,332 | 171 | 금액·평단·보유수량 |
| 주문 안전(실행·취소·캡퍼·보정·정합) | 13 | 7,029 | 292 | 중복 접수·상한·재시도 |
| 상태 전이·배치·스케줄러(StrategyServiceTest 포함) | 26 | 5,938 | 279 | 롤오버·재개·archive |
| 보안·JWT·암호화 | 21 | 2,299 | 98 | |
| 아키텍처·계약·컨텍스트로드 | 19 | 2,173 | 61 | 구조 가드, 줄 대비 가치 최고 |
| Flyway/스키마 | 4 | 288 | 5 | 마이그레이션 전용 테스트는 얇음(운영 DB 보호는 DataJpa·IT 의존) |

보호군 합계 약 26k줄(겹침 제외 전 ~22k줄 추정 = 전체 30%). 이 군에서도 **setup·stub 압축은 가능하지만 단언·시나리오 삭제는 금지**.

## 감량 가능 추정 상위 8

| 순 | 대상(근거 file) | 추정 감량 줄 | 감량 시 잃는 보호 |
|---:|---|---:|---|
| 1 | `trading-core/.../trading/application/service/StrategyServiceTest.java`(3,098): register_* 80개를 `domain/creation` 리졸버 단위 테스트로 이전 + 위 stub 헬퍼 통합 + 완화 파라미터화 | 900~1,200 | 거의 없음(규칙 보존 시). 단 이전 작업이 선행되어야 하고 리졸버 현재 테스트 12개뿐 |
| 2 | WebMvc 오류 상태 전용 테스트 147개(약 1,400줄) — 401/403/400 매트릭스를 `RootSecurityPolicyTest` 계열 1곳 + 엔드포인트별 대표 1개로 | 800~1,100 | 엔드포인트별 401/403 개별 회귀(보안 정책 테스트가 라우트 규칙을 이미 고정하면 낮음, 확인 필요) |
| 3 | fixture 우회 직접 생성 `new Strategy(` 207·`new Account(` 63회 → `TradingFixtures`/빌더 | 600~900 | 없음(순수 setup 치환, 가독성 상승) |
| 4 | `TradingServiceTest.java`(2,418): `when(` 407, 첫 테스트 240행 이후 stub 반복 → 공용 `stub*Basics` 확장·시나리오 빌더 | 500~700 | 없음(단언 유지 시). 주문 안전 군이라 verify 삭제 금지 |
| 5 | Finance 5개 서비스(`FinanceCategory/Group/Transaction/Budget/AssetSnapshotServiceTest`) 소유권·SecurityException 반복을 파라미터화/공용 베이스 | 350~500(추정) | 모듈별 개별 문구 단언 일부 |
| 6 | `TradingStatsServiceTest.java`(1,415, 3.2x) + `BenchmarkServiceTest.java`(808): 값 단언 중복·setup | 300~500(추정) | 통계 수식 이중 단언 일부(`ReturnMetricsTest`가 SSOT) |
| 7 | DataJpa 영속 어댑터 테스트 44파일 6,185줄 중 단순 save/find 왕복(`FinanceBudgetPersistenceAdapterTest` 223줄, `KisTokenPersistenceAdapterTest`, `StrategyVrDetailPersistenceAdapterTest` 등) | 400~700(추정) | 컬럼 매핑 회귀(`ddl-auto: validate`가 구조는 이미 보호) |
| 8 | 순수 배선 테스트(Response/Request DTO 9파일 497줄, 단순 위임 internal controller·notifier ~25파일) | 300~600(추정) | 필드 매핑 누락 감지(컨트롤러 슬라이스가 대체) |

8개 합산 약 4,150~6,300줄(전체의 5.4~8.2%, 겹침 제외 시 약 4~5.5k 추정). **줄 수 기준 "테스트 대폭 감량"은 보호군을 건드리지 않고는 ~8%가 한계**로 보인다. 더 줄이려면 테스트 삭제가 아니라 main 코드 축소(테스트는 따라서 감소)가 지렛대다.

## 한계

- 파일 분류·동형 판정·verify/assert 비율은 정규식 휴리스틱. 멀티라인 `new X(` 인자 수, 헬퍼 본문 동일성은 전수 검증하지 않았다.
- 계층 중복 비율(10~20%)은 4개 기능 표본 외삽이며 사례 간 편차가 크다.
- 감량 줄 수는 전부 추정이고, 실제 실행 시간·flaky·커버리지는 측정하지 않았다(테스트 미실행).
