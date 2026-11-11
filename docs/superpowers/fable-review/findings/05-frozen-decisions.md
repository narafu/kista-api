# 05. 동결 결정(유지 확정·재론 금지·기각·트리거 보류) 목록

작성: 2026-11-11, 읽기 전용 요약. 이 문서는 판단을 하지 않는다. 원문 결정과 그 이유를 옮기고, 이유가 원문에 없으면 "이유 미기재"로 적었다. 재심 여부는 상위 모델이 정한다.

**읽은 범위**
- 메모리: `/Users/phs/.claude/projects/-Users-phs-workspace-kista-kista-api/memory/` 의 `project_*.md`·`feedback_*.md` 전체(이하 "메모리" + 파일명)
- `docs/agents/code-quality-backlog.md` 전체(972줄). 유지 목록은 L578-L655
- `docs/agents/own-type-ledger.md`, `docs/agents/constraints.md`, `docs/agents/architecture.md`, `docs/agents/scheduler-time-table.md`, `docs/agents/docker-infra.md`, `docs/agents/workflow-*.md`, `docs/agents/modules/**/*.md`에서 "유지·예외·기각·변경 금지·의도" 표현이 붙은 곳
- `docs/history/modulith-migration-history.md`의 결정 요약

**경로 표기**: `constraints.md`·`architecture.md` 등은 `docs/agents/` 기준이다. 백로그 항목은 `CQ-nn`으로 적었다.

---

## 1. 구조 수준 결정

| 번호 | 결정 내용 | 당시 이유 | 출처 | 결정 시기 | 관련 코드 위치 |
|---|---|---|---|---|---|
| S-01 | 2-프로세스 분리: root `:api`(kista-api·kista-scheduler)와 `:trading-core`(kista-trading)는 컴파일 의존 없음. 통신은 내부 HTTP·Redis만 | 프로세스 경계. trading-core는 root를 호출할 수 없다(`GradleModuleBoundaryTest.tradingCoreMustNotCallRootInternalApi`). 단일 인스턴스 매매 프로세스 분리(이유 전문은 history 4a~4b 절 참고, 미확인) | `architecture.md:7`, `modules/tradingcore/dca.md:23`, `history:132` | 2026-08~09 (trading-core 분리 4a~4b) | `GradleModuleBoundaryTest`, `TradingApplication`, `ApiApplication` |
| S-02 | Gradle 서브프로젝트 3개 `:api`·`:trading-core`·`:shared`. `:shared`는 단방향(shared ← trading-core, shared ← api) | 공용 어휘·wire 계약·인프라 leaf 분리. 역방향 의존 금지 | `architecture.md:3-7` | 미기재 | `GradleModuleBoundaryTest` |
| S-03 | DB 스키마 5분리: root `public`·`finance`·`kista_ref`, trading `trading`·`trading_ref`. 다른 서비스 소유 테이블 참조(FK 포함) 금지 | 소유 경계를 스키마·마이그레이션에도 맞춤. 같은 DB를 공유하던 상태에서 분리 | `architecture.md:19-20`, `history:148-154` | 2026-09-21 | `flyway.md`(DB 스키마 5분리 절) |
| S-04 | 물리 DB 분리(4b-2)는 보류 | 스키마 분리 이후 물리 분리는 선행 작업만 끝난 상태. 근거는 spec 아카이브 링크 | `history:150` | 2026-09-21 이후 | — |
| S-05 | Spring Modulith 모듈 구조. 모듈 ID는 `package-info`의 `@ApplicationModule(id)`로 고정, 감지는 `explicitly-annotated`. 두 프로세스 모델 `ApplicationModules.of` 2개 | 모듈 경계를 `verify()`로 강제 | `architecture.md:54` | 2026-08 (Modulith 점진 도입) | `ModulithArchitectureTest` (api·trading-core) |
| S-06 | 헥사고날 구조 + ArchUnit 빌드 강제 | 레이어 방향 강제 | `architecture.md:14` | 미기재 | `HexagonalArchitectureTest` |
| S-07 | 모듈 간 참조는 상대 모듈의 NamedInterface만 거친다. 프로세스 경계를 넘는 wire 타입은 `com.kista.shared.contract`에 선언(own-type 복제 금지) | 양쪽 컴파일 타임 공유, `InternalApiContractTest`가 강제 | `architecture.md:54-56`, `constraints.md:32-38` | 미기재 | `InternalApiContractTest` |
| S-08 | own-type 게이트: 모듈 간 값 타입 복제는 (a) 순환 불가피 또는 (b) 외부 계약 분리일 때만 정당. (b)는 파일:라인 증거 필수 | 복제 남용 방지 | `constraints.md:32-38`, `own-type-ledger.md:1-55` | 미기재 | 원장 항목 전체 |
| S-09 | own-type 허용 원장 항목 유지: `CycleHistoryPageResponse`/`CycleHistoryResponse` 이중 복제 | 각기 다른 HTTP 엔드포인트의 JSON 계약. 통합하면 한쪽 필드 추가가 다른 쪽 스키마로 전이 | `own-type-ledger.md:9-10` | 미기재 | `trading/adapter/in/web/dto`, `stats/adapter/in/web/dto` |
| S-10 | own-type 허용 원장 항목: narrowing projection들(`BrokerAccountRef`, `TradingAccount`, `NotificationRecipient`, `FidaPlannedOrder`, `StrategyRef` 등) | 애그리게이트 축소 노출(타입 표면 축소). 자격증명 은닉 목적 아님 | `own-type-ledger.md:14-30` | 여러 시기 | 각 항목 |
| S-11 | 2026-07-11 유지 확정: 매매 helper 세분화, CycleOrderStrategy 이중 계층, UseCase 구현체 1개짜리 22개, admin 서비스 6종, 브로커 capability 포트 14개, UI FSD·프록시 구조, Prometheus/Grafana 스택 | "변경 불필요로 확정된 구조를 다시 논의하지 않기 위해" 기록. 개별 이유 미기재 | 메모리 `project_2026_07_11_full_review.md` | 2026-07-11 | 해당 모듈 전반 |
| S-12 | 2026-07 기각 확정: `@WebMvcTest` SAME_THREAD 제거, widgets cross-import 코드 수정(문서 예외만 명문화), `with*()` 패턴 변경, 소유권 검증 중복 제거, KisAuthApi/TossAuthApi catch 공통화, CycleOrderComputer.compute() 파라미터 객체화 | 메모리 본문에는 이유가 "이미 중앙화", "PlanContext가 이미 캡슐화" 등 일부만 있음. `VrSettingsSection` 파생상태 useMemo화는 "모드 선택 동작이 바뀜" | 메모리 `project_2026_07_11_full_review.md`, `testing.md`(SAME_THREAD 필수) | 2026-07-11 | `@WebMvcTest` 테스트 전반, `TossAuthApi`, `KisAuthApi` |
| S-13 | 잔고 급변 가드와 브로커 서킷브레이커는 진행하지 않음 | 사용자 확정. 이유 미기재 | 메모리 `project_2026_07_11_full_review.md` | 2026-07-17 | — |
| S-14 | Null 체크 전사 적용은 완전 기각. record 필드는 nullable+주석, 포트·전략 skip은 Optional | 원문: "스캔 아티팩트로 판명" | 메모리 `project_2026_07_11_full_review.md` | 2026-07 | — |
| S-15 | SaaS 상용화 갭(결제·구독·온보딩 없음)은 별도 이니셔티브로만 연다 | 2026-08-31 재검증에서 그대로 유효 | 메모리 `project_2026_07_11_full_review.md` | 2026-08-31 | — |
| S-16 | UI와 API 계약은 건강. 프록시 때문에 단순 grep 대조는 오탐이 나므로 `app/api/` 라우트를 경유해 확인 | 오탐 방지 | 메모리 `project_2026_07_11_full_review.md` | 2026-07 | kista-ui `app/api/` |
| S-17 | MapStruct 도입 거부 | 원문: "확정". 어댑터 매핑은 수동 유지 | 메모리 `project_review_refactor_2026_10_31.md`, `backlog:624` | 2026-10-31 | `AccountEntity`, `StrategyDcaRuleEntity` |
| S-18 | `hasher.hash()`를 도메인으로 옮기지 않는다. HMAC 비밀키는 인프라. AES는 `AttributeConverter`, `*_hash`는 어댑터에 남김 | 원문: 확정 판단. HMAC 키가 인프라 소관이라는 점이 근거 | 메모리 `project_review_refactor_2026_10_31.md` | 2026-10-31 | 어댑터 |
| S-19 | 등록 응답은 `StrategyService.assemble` 조회 경로로 통일 | kista-ui 확인 완료, UI 수정 불필요 | 메모리 `project_review_refactor_2026_10_31.md` | 2026-10-31 | `StrategyService.assemble` |
| S-20 | Pub/Sub 구독자의 `@PostConstruct` 등록 유지. Stream 컨슈머의 `ApplicationReadyEvent` 배선과 모양만 다르다 | Pub/Sub은 백로그가 없어 기동 중 메시지는 어느 방식이든 유실. 컨테이너 시작 전 리스너 등록은 무해 | 메모리 `project_review_refactor_2026_10_31.md` | 2026-11-08 종결 | `RedisTradeEventSubscriber`, `TradeEventBenchmarkCacheInvalidator` |
| S-21 | `UserFcmCleanupListener` 유지 | notify가 순수 outbound여야 한다는 규칙 | 메모리 `project_review_refactor_2026_10_31.md` | 2026-10-31 | notify 모듈 |
| S-22 | feargreed 어댑터 중복(trading-core ↔ root)은 의도된 것. 이동하지 않음 | trading-core는 root를 호출할 수 없고, DCA FEAR_GREED 조건은 최신 점수 1건을 fail-open으로 즉시 필요 | `modules/tradingcore/dca.md:23`, `modules/api/market.md:11` | 미기재 | `adapter/out/feargreed` |
| S-23 | `TradingExceptionHandler.reportErrorLog`와 root `GlobalExceptionHandler.reportErrorLog`를 합치지 않음 | `shared.platform`→`shared.kernel` 참조 금지, kernel outbound-zero라 공용 헬퍼를 둘 모듈이 없음 | `modules/tradingcore/web.md:7`, `modules/api/web.md:10` | 미기재 | 두 핸들러 |
| S-24 | `StrategyUseCase`는 command/query 미분리 유지 | 유일 소비자는 `TradingCycleController`. 원문 이유는 일부만 확인됨(`trading.md:8` 줄 잘림) | `modules/tradingcore/trading.md:8` | 미기재 | `StrategyUseCase` |
| S-25 | 거래일 경계 04:30 고정 (cron 경계를 장 마감이나 DST별 시각으로 옮기지 않음) | 기각 1(장 마감 이동): 배치 주문 구간과 수동 실행이 겹쳐 별도 방어가 필요. 기각 2(DST별 분기): 미리보기·수동 실행·재개·창·cron·테스트를 전부 바꿔야 하고 얻는 건 표시 정확도와 대기 제거뿐 | `scheduler-time-table.md:63-69` | 미기재 | `DstInfo.nextTradeDateAt` |
| S-26 | 라이브 경로에 `LocalDate.now(KST)` 기준 등 일부 현재 시각 사용 유지 | 체크포인트 키·다음 실행 후보 계산이라 거래일 값이 아님(주석에 명시) | 백로그 keep list `backlog:586-588` | 2026-11-08 | `TradingService:79,158`, `CycleSnapshotCreator:97` |
| S-27 | 시간 기준은 KST 단일. `ZoneId.of`는 `TimeZones`만, 현재 시각은 `Clock` 주입, `UsTradeDates`는 허용 어댑터에서만. 외부 원본 참조 데이터는 원본 기준 유지 | 거래일은 KST 단일 기준(이유 상세는 `modules/shared/platform.md`) | `constraints.md:132-137`, `modules/shared/platform.md:38` | 미기재 | `UsTradeDates`, `TimeZones` |
| S-28 | Flyway: 이미 적용된 마이그레이션 파일은 수정·삭제·이름 변경 금지. root는 expand/contract 준수 | 운영 DB checksum 불일치로 기동 크래시(V1 포함) | `constraints.md:93-99`, `flyway.md:15` | 미기재 | `db/migration` |
| S-29 | enum 컬럼은 `VARCHAR(20)` + `@Enumerated(STRING)`. 네이티브 ENUM 금지 | 이유 미기재 | `constraints.md:95-96` | 미기재 | 마이그레이션 전반 |
| S-30 | trading-core는 단일 인스턴스, stop-first 교체, `stop_grace_period` ≥ 200s 유지 | 재개가 잔여 락을 인수하므로 겹침 기동은 이중 실행 | `constraints.md:7` | 미기재 | `.github/tests/compose-invariants.bats` |
| S-31 | EPR 재발행 소유: `REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART`는 kista-trading·kista-scheduler만 true, kista-api는 false | 둘 이상이 같은 테이블에 true면 이중 claim | `constraints.md:7`, `modules/tradingcore/notify.md:9`, `docker-infra.md` "서버 배포 방식" | 미기재 | `docker-compose.yml` |
| S-32 | Redis 스트림 전환: Pub/Sub(유실 허용) → Stream(`MAXLEN 10,000`, "유실 없음"이 아니라 "상한 이내 보존") | 유실 방지 | `history:228`, `history:231` | 2026-09-30 (F10, 7b) | `RedisStreams.add(...)` |
| S-33 | 리스너 패키지·이름 변경 시 EPR 고아 row를 피하려 `listener_id` UPDATE(이벤트·시그니처 불변 + 리스너 멱등 조건) | DELETE는 재시도를 끊음 | `docker-infra.md:134` | 미기재 | EPR 런북 |
| S-34 | `@Aspect` 저장소 전체 금지 (가드 `no_aspects_in_codebase`) | 모듈 간 런타임 결합 발생. 횡단 관심사는 이벤트로 | `constraints.md:24` | 미기재 | `HexagonalArchitectureTest` |
| S-35 | `@Configuration`·`@TransactionalEventListener`·`@Scheduled`는 `application/service`에 두지 않음. 예외: `TradingStatsService` 캐시 무효화 `@EventListener` | 예외 이유는 EPR `event_publication` 행이 메모리 캐시 무효화 때문에 남지 않게 하려는 것 | `constraints.md:22` | 미기재 | `TradingStatsService` |
| S-36 | Redis Stream 컨슈머 복구 `@Scheduled`는 `adapter/in/redis`에 둠 | platform `RedisStreamSubscriber` 베이스가 골격을 가짐 | `constraints.md:23` | 미기재 | `UserEventStreamConsumer` 등 |
| S-37 | Virtual Thread 활성. `@Async`·`CompletableFuture` 금지 | 이유 미기재 | `constraints.md:81-82` | 미기재 | — |
| S-38 | `ErrorCode` 상수 이름 변경·삭제 금지 | kista-ui 계약 | `constraints.md:45` | 미기재 | `ErrorCode` |
| S-39 | ProblemDetail `detail`은 한국어 문장이고 내부 정보를 싣지 않음. IAE/ISE 리터럴은 "~니다."로 끝남 | 사용자 노출 정보 통제 (`ExceptionMessageFormatTest` 가드) | `constraints.md:42-47`, `modules/api/web.md:16` | 2026-11 (CQ-05) | `ExceptionMessageFormatTest` |
| S-40 | 내부 API 경로는 소유권 재검증을 하지 않음 (설계상 의도) | `/api/internal/**`는 X-Internal-Token 경로라 설계상 의도 | 백로그 keep list `backlog:608` | 2026-11-08 | `PrivacyBaseInternalController` 외 |
| S-41 | 보안: `SecurityConfig` `authenticationEntryPoint` 필요, 라우트는 첫 매치 우선, CORS에 PATCH 필수 | 401 대신 403 방지 | `constraints.md:111-115` | 미기재 | `SecurityConfig` |
| S-42 | 보류 확정(2026-10-31): 재발행 스케줄러 shared 추출, 예외 핸들러 오류 보고 통합, FcmAdapter `addAllTokens` | 원문에 이유 미기재 | 메모리 `project_review_refactor_2026_10_31.md` | 2026-10-31 | — |
| S-43 | `validateWithdrawalSufficiency` 호출 2곳은 통합하지 않음 | 인자·시점이 다름 | 메모리 `project_review_refactor_2026_10_31.md` | 2026-10-31 | — |
| S-44 | 긴 파라미터 묶음은 동일 타입 인접 2쌍 이상인 7곳만 record로 묶음 | 기준 적용 결과 | 메모리 `project_review_refactor_2026_10_31.md`, `constraints.md:118` | 2026-10-31 | — |
| S-45 | `validateInfiniteMinSeed`는 fail-open이 의도. `BrokerCallGuard.wrap`(fail-closed) 적용 안 함 | 2026-10-31 확정. 이유 미기재(백로그 keep list는 "의도된 동작") | 메모리 `project_review_refactor_2026_10_31.md`, `backlog:616` | 2026-10-31 | `StrategyCreationService.java:154` |
| S-46 | 바이낸스 DCA: 출금 권한 주기 점검 스케줄러, 가격 컬럼 정밀도, -1021 시각 오차 대응은 트리거 조건부(보류). 입금 주소 표시·입금 알림, 격리 마진 레버리지 DCA는 기각 | 사용자 1명·계좌 1개라 판단(2026-11-08). 기각 이유는 바이낸스 앱으로 충분, 별도 기획 | 메모리 `project_binance_dca.md` | 2026-11-08 | `BinanceAuthApi`(`enableWithdrawals`) |
| S-47 | 브로커 로드맵: 미국주식 메리츠 1차, 크립토 바이낸스(USDT spot) 1차, 토스는 대시보드·통계 중심, 업비트·빗썸·KIS는 선택(제거 가능) | 사용자 진술(전략적 투자 방향) | 메모리 `project_broker_strategy.md` | 2026-10-23 | 어댑터 패키지 |
| S-48 | broker 의존 방향: 모듈 경계 밖 기능은 `broker.application.port.output`의 신규 `*Port`로 노출. `adapter/out/*`은 비공개 | 어댑터 교체 용이성 | `constraints.md:18-21` | 미기재 | `broker/application/port/output` |
| S-49 | 브로커 실패 정책: 실패 시 중단은 `BrokerCallGuard.wrap`(fail-closed), 대체값으로 진행은 `tryGet`(fail-open). 벤더 429는 `BrokerRateLimitException` 변환 | 정책 통일 | `constraints.md:118` | 2026-11 (CQ-06, CQ-14) | `BrokerCallGuard` |
| S-50 | 테스트 DB는 docker-compose 로컬 PostgreSQL. Testcontainers 아님. `*IT`는 `kistadb_it` 별도 DB | 중단된 IT 잔재가 롤백 기반 테스트 건수를 깨지 않게 함 | `testing.md` "테스트 DB" 절 | 미기재 | `DataJpaTestBase` |
| S-51 | `@WebMvcTest` 클래스와 DB 상속 테스트는 `@Execution(SAME_THREAD)` 필수 | 병렬 시 mock 오염 | `testing.md` "Mockito 병렬 테스트 주의" 절 | 미기재 | — |
| S-52 | `@WithMockUser` 금지. principal은 항상 `UUID` | `@AuthenticationPrincipal UUID`와 충돌 | `testing.md` | 미기재 | — |
| S-53 | 로그 문맥 접두사 형식 `[strategyId={}]` 등. 잠금·종목·정책 단위 로그는 예외 | 범위에 쓸 id가 없음 | `constraints.md:120-124`, backlog keep list `backlog:634` | 2026-11-08 (CQ-07) | — |
| S-54 | `@Transactional` 안에서 외부 HTTP 호출 금지. 이벤트 + `AFTER_COMMIT` 리스너 패턴 | 롤백 시 취소 불가 | `constraints.md:126-130` | 미기재 | — |
| S-55 | 브로커 응답 숫자 파서 3벌 유지 | 빈값 의미가 증권사 wire별로 다름(ZERO, null, 예외) | backlog keep list `backlog:620` | 2026-11-08 | `KisResponseParser`, `TossResponseParser`, `MeritzResponse` |
| S-56 | 매매 공식·VR 공식은 변경 금지(단위 테스트 검증 SSOT) | 원문: "변경 금지 SSOT" | `constraints.md:51`, `modules/tradingcore/trading-formulas.md:1-3` | 미기재 | `matching/` `trading/` |

---

## 2. 코드 수준 결정

백로그 유지 목록(`code-quality-backlog.md` L582-L654, 73행)을 같은 이유끼리 묶었다. 행별 원문 위치는 "출처" 칸의 줄 번호다. 괄호 안은 행 번호다.

| 번호 | 결정 내용 | 당시 이유 | 출처 | 결정 시기 | 관련 코드 위치 |
|---|---|---|---|---|---|
| C-01 | 날짜·시간 관련 유지 (`LocalDate.now(KST)` 2곳, `plusDays(1)` 다음 실행 후보, 단순 조회 창 `minusDays`/`plusDays`, stats 크립토 `ZoneOffset.UTC`) | 체크포인트 키·다음 실행 후보·단순 조회 창·외부 원본 UTC라 기준 변환이 아님. Toss 캔들 커서 UTC가 API 요구인지는 미확인 | backlog L586-L590 (586, 587, 588, 589, 590), `TradingStatsService` 등 | 2026-11-08 | `TradingService:79,158`, `CycleSnapshotCreator:97`, `DcaSummaryAssembler:58-62` |
| C-02 | 세션 계산 복제 유지 (`TossPriceApi` DstInfo 복제, `MarketSessionSnapshot`, `TradingBatchResumer` 04:30 주석) | broker와 marketcalendar는 trading을 참조할 수 없음. 경계 문서화 주석 | backlog L583, L584, L585 | 2026-11-01~ | `TossPriceApi:161-199`, `MarketSessionSnapshot:11,16` |
| C-03 | 총자산 계산 5곳 유지 | 의미가 다름(종가/평단 폴백, scale 유무, 평가액 기준, 종료금액 계약) | backlog L591 | 2026-11-08 | `InfinitePosition:43`, `CyclePosition:66-70` 등 |
| C-04 | 수량 내림 FLOOR vs DOWN, 비율 scale 1/4/6, `InfinitePosition:119` scale 없는 divide 유지 | 양수 입력에서 결과 동일(FLOOR/DOWN). 용도별 scale 다름. 공식 코드라 변경 안 함 | backlog L592, L593, L594 | 2026-11-01 | `VrCycleOrderStrategy:85`, `VrStrategy:99`, `TradingStatsService:233-301` |
| C-05 | 금액 scale 관련 유지: `CRYPTO_SCALE = 8` 2벌, 라이브·백테스트 별지점 평균 2곳 | 2곳(추출 기준 3곳 미달). 근거 주석이 다름. 공식이라 한쪽만 고치면 불일치 | backlog L595, L597 | 2026-11-01 | `BootstrapPosition:13`, `ManualTradeCorrectionService:49`, `CycleOrderComputer:122`, `BacktestEngine:570` |
| C-06 | 가격 문자열 포맷 2곳, prefix 평균 2곳, 페이지 슬라이싱 2곳, stats 2곳 중복(기록만) 유지 | 2곳이라 추출 기준 미달. wire 포맷 | backlog L596, L598, L599, L646 | 2026-11-08 | `MeritzOrderApi:208`, `KisResponseParser:43` 등 |
| C-07 | 타입 가드·필터·표시 `isPrivacy` 호출 유지 | 동작 분기가 아니라 가드·필터·표시. CQ-03 규칙의 허용 범위 | backlog L600 | 2026-11-01 | `DcaExecutionQueryService:39` 등 |
| C-08 | 단건/일괄 조회 분기 두 벌 유지 (`StrategyDetailAssembler`). `DcaCycleOrderStrategy.withLimits` 복사 유지 | 단건과 일괄(N+1 회피) 조회 형태가 다름. 파일 안 헬퍼로 이미 모임 | backlog L601, L602 | 2026-11-01 | `StrategyDetailAssembler:40-65,117-149` |
| C-09 | 주문 상태 판정 서비스 산재 유지 | 같은 조합이 3곳이 되면 `Order` 판정 메서드로 올린다는 조건부 유지 | backlog L603 | 2026-11-08 | `OrderCancelService` 등 |
| C-10 | `TradingErrorEvent(null, ...)` 29곳 유지. `e.getMessage()`만 보내는 알림 유지 | 생성자 한 줄이라 헬퍼 이득이 없음. 문맥 보강은 CQ-07·CQ-19 기준 후에 봄 | backlog L604, L605 | 2026-11-01 | 13파일 |
| C-11 | 소유권 응답 코드 400/404/403 유지. `TradingStatsService`·`PortfolioService` 필터 방식 유지. 내부 컨트롤러 소유권 검증 없음 유지 | 내부 토큰 경로는 입력 오류(400)가 맞음. userId 범위 조회로 남의 accountId는 빈 결과. 내부 API 설계상 의도 | backlog L606, L607, L608 | 2026-11-08 | `SelectionChain:27-41`, `TradingInternalQueryController:90-95` |
| C-12 | `@Valid` 미적용 유지 (`TradingCycleController` update, `AccountController` 이름 변경) | 등록 전용 제약(`@NotNull type`, `@Pattern` 계좌번호)이라 수정 요청에 안 맞음. 주석에 명시 | backlog L609, L610 | 2026-11-01 | `TradingCycleController:92`, `AccountController:68` |
| C-13 | DTO compact constructor의 IAE 유지, `UserNotifyProfilePersistenceAdapter` `occurredAt` 폴백 유지, persistence SUM null→0 유지 | 사용자 문구로 400을 내려는 의도(주석 `:16`). 이벤트 필드 주석이 구버전 발행분을 명시. 집계 쿼리 결과 처리 | backlog L611, L612, L613 | 2026-11-01 | `DcaConfigRequest:17-18`, `OrderPersistenceAdapter:216,251` |
| C-14 | `BrokerCallGuard` 밖 raw catch 유지(이벤트·분기 있음), 재시도·fallback이 있는 raw catch 유지 | `constraints.md`가 허용하는 예외(이벤트 발행·분기 처리) | backlog L614, L615 | 2026-11-01 | `ManualTradingService:80,117` 등 |
| C-15 | `validateInfiniteMinSeed` fail-open 유지 | 2026-10-31 확정, 의도된 동작 | backlog L616 | 2026-10-31 | `StrategyCreationService.java:154` |
| C-16 | `KisAuthApi`/`TossAuthApi` catch 공통화 기각, 증권사별 백오프·인터럽트 처리 4벌 유지 | 기각 확정(2026-07). 인터럽트 시 동작이 각 클라이언트의 재시도 계약(주문 중복 위험)에 묶임 | backlog L617, L618; 메모리 `project_2026_07_11_full_review.md` | 2026-07 / 2026-11-01 | `KisAuthApi:107-167`, `TossHttpClient:72,115` |
| C-17 | 어댑터 파서·설정 중복 유지 (Upbit·Binance `execute/translate`, RestClient 설정 빈 5개, 거래소 레지스트리 키 집합, 역방향 티커 조회) | 오류 코드 wire 포맷 차이. RestClient는 이미 `HttpClientTimeouts`로 공용화. 레지스트리 키는 증권사 wire(티커 추가 시 양쪽 갱신) | backlog L619, L621, L622, L623 | 2026-11-01 | `UpbitHttpClient:82-110`, `KisExchangeRegistry` 등 |
| C-18 | 엔티티·매퍼 중복 유지: `toDomain/toEntity` vs 엔티티 메서드, web 응답 Mapper vs DTO `from()` 병존 | MapStruct 거부 확정. `AccountEntity`는 해시 인자, DCA는 부모-자식 조립 때문. Mapper와 `from()`은 계약이 다름 | backlog L624, L625; 메모리 `project_review_refactor_2026_10_31.md` | 2026-10-31 / 2026-11-08 | `AccountEntity:66`, `TradingContractMapper` |
| C-19 | `isCryptoExchange()` 서비스 분기 유지 | 전략 타입이 아니라 증권사 capability 술어이고 이미 capability 형태 | backlog L626 | 2026-11-01 | `BatchContextFactory:51` 등 |
| C-20 | 평단 필수 규칙 2곳, "사용자를 찾을 수 없습니다" 복제 2곳, `String accountNo` 원시값 유지 | 2곳(추출 기준 미달). 포트·어댑터 경계 wire 값. 애그리게이트 내부는 `BrokerAccountRef`가 묶음 | backlog L627, L628, L629 | 2026-11-01 | `BootstrapPosition:35`, `BacktestEngine:420` |
| C-21 | 조회 경로 `.orElse(DEFAULT_DIVISION_COUNT)` 폴백 유지. 시드 미리보기 `divisionCount` `@RequestParam(defaultValue="20")` 유지 | null 기본값이 아니라 버전 상세 행 누락 방어. 예외로 바꾸면 매매가 멈춤(CQ-12). 미리보기 전용 쿼리 파라미터 | backlog L631, L632 | 2026-11-07 | `CyclePositionPersistor` 등 |
| C-22 | `CryptoLedgerRecorder` 증분 `divide(volume, 8, HALF_UP)` 유지 | 증분을 `LEDGER_SCALE(2)`로 먼저 반올림하면 최종 예수금이 0.01 어긋남. DB `numeric(20,2)`의 최종 1회 반올림이 현행 동작 보존 | backlog L633 | 2026-11-08 | `CryptoLedgerRecorder` |
| C-23 | 잠금·종목·정책 단위 로그의 비표준 접두사 유지 | 쓸 수 있는 strategyId·accountId·orderId·userId가 범위에 없음. 잠금 하나가 여러 전략을 덮음 | backlog L634 | 2026-11-08 | `TradingService:461` 등 |
| C-24 | 미사용 import 빌드 가드 도입 안 함 | 약 1,100개 파일 중 21개, 재발 근거 없음. 신규 플러그인 필요. 재검토 트리거: 이관과 무관한 재발이 감사에서 확인될 때 | backlog L635 | 2026-11-08 | 빌드 설정 |
| C-25 | `StrategyStatusInternalController` ARCHIVED 이중 검사 유지 | 선검사를 지우면 no-op 200이 되어 400이 바뀜. `case ARCHIVED`는 default 없는 switch 컴파일 가드 | backlog L636 | 2026-11-08 | `StrategyStatusInternalController:37,48` |
| C-26 | not-found ISE→NSEE 일괄 교체 안 함(타입 유지, 메시지만 고정 문구) | ISE는 400, NSEE는 404라 상태코드가 바뀜 | backlog L637 | 2026-11-08 | `StrategyCyclePersistenceAdapter` 등 |
| C-27 | `OrderPort` default `findByIdOrThrow` 부재 유지 | 효과가 서비스 3곳 문구 통일뿐. default 메서드는 mock override 때문에 테스트 stub이 NPE가 됨 | backlog L638 | 2026-11-08 | `OrderPort` |
| C-28 | 응답 DTO enum 표현 혼재(String vs enum) 유지 | JSON 결과 같고, 통일하면 OpenAPI 스키마만 흔들림 | backlog L639 | 2026-11-08 | `TradingCycleResponse` 등 |
| C-29 | `UserEventRepublisher` 동일 본문 메서드 2개 유지, `CycleCompletedEvent`/`CycleEndedEvent` 필드 동일 유지 | 2곳(추출 기준 미달). 발행 흐름과 리스너 phase가 달라 병합 안 함. 동시 발행 없음 | backlog L640, L645 | 2026-11-08 | `CycleRotationService:54`, `ManualTradeCorrectionService:111` |
| C-30 | 코인 부분체결 후 취소 주문의 FILLED 확정 유지 | 업비트 시장가 매수는 자투리 때문에 cancel로 끝나는 일이 흔해 의도. 주식은 CANCELLED 유지 | backlog L641; `CQ-61` 상태 | 2026-11-08 | `CryptoOrderSynchronizer.settle`, `CryptoLedgerRecorder:60` |
| C-31 | `CancelledOrderFillRecorder` 바깥 try/catch 유지. `findPlannedOrPlaced*` 이름 유지. 테스트 FQCN 전수 import 전환 안 함. `getStrategyOrders` 기간 기본값·size 상한 100/200 불통일 유지 | 바깥 try/catch는 도달 불가가 아님(람다 안 `OrderFill` 생성이 던질 수 있음). 이름은 주석 정정으로 충분. UI '전체' 프리셋이 서버 기본 30일에 의존 | backlog L642, L643, L644, L652 | 2026-11-09 | `CancelledOrderFillRecorder:36-41`, `TradingCycleController:264-269` |
| C-32 | 엔티티 저장 패턴 두 갈래 유지(`findOrCreate` vs `new`+`setId`) | 현재 호출처가 모두 id=null 신규 저장이라 merge 덮어쓰기 없음. update 경로가 생기면 재검토 | backlog L651 | 2026-11-08 | `StrategyCyclePersistenceAdapter:120` 등 |
| C-33 | 주식 전용 `*Usd` 이름 유지(미리보기·KIS 포트폴리오·`MarginPort`), `TradeLegSummary.amountUsd`·`TradingReport.totalBoughtUsd/totalSoldUsd` 유지 | 코인 경로를 지나지 않음. wire 키라 개명 시 UI 다수 파일 변경. 마감 리포트는 `TradingReportReadyEvent` EPR 직렬화 대상. 재검토 트리거: 코인 계좌가 마감 리포트·`TradingReportReadyEvent`에 합류할 때 | backlog L653, L654 | 2026-11-10 | `NextOrdersResponse`, `PortfolioSummaryResponse`, `MarginPort` |
| C-34 | `TradingPreviewService` `TradingAccount.from(account).brokerRef().broker()` 우회 유지 | trading application.service는 `Account` 애그리게이트를 직접 쓸 수 없음(`trading_batch_path_must_not_carry_account_aggregate`). 투영 경유가 정답 | backlog L650 | 2026-11-09 | `TradingPreviewService:79` |
| C-36 | 원장↔증권사 보유 수량 자동 대사 없음 | 사용자가 같은 계좌에서 직접 매매할 수 있어 차이가 정상. 감지는 미리보기 `accountHoldingsCheck` 경고로 충분 | backlog L630; 사용자 결정 2026-11-07 (CQ-39) | 2026-11-07 | `TradingReporter` 마감 리포트 |
| C-37 | `TradingBatchResumer` 등 로그 `e.getMessage()` 기반 알림 유지 | 문맥 보강은 CQ-07·CQ-19 기준이 정해진 뒤 함께 본다 | backlog L605 | 2026-11-01 | — |
| C-38 | 내부 컨트롤러 소유권 검증 없음 유지 | `/api/internal/**` X-Internal-Token 설계상 의도 | backlog L608 | 2026-11-08 | `PrivacyBaseInternalController`, `FidaOrderController`, `AccountInternalController` |
| C-39 | stats 평가액 계산 2종 유지 | 곡선·요약은 연속성(평단 대체), 투자지수(TWR)는 실평가 필요(종가 없으면 제외). 용도가 다름 | backlog L647; `modules/tradingcore/stats.md:10` | 2026-11-08 | `TradingStatsService`, `InvestmentIndexCalculator` |
| C-40 | 빈 응답 → 빈 결과 처리 유지 | 소비처가 모두 `tryGet`이고 다음 단계가 빈 값을 실패로 바꿈. 미결제약정만 가드가 없어 CQ-43에서 수정 | backlog L649 | 2026-11-08 | `BinanceMarketDataAdapter` 등 |
| C-43 | 2026-07 메모리 기각: `VrSettingsSection` 파생상태 useMemo화 기각 | 원문: 모드 선택 동작이 바뀜 | 메모리 `project_2026_07_11_full_review.md` | 2026-07 | kista-ui `VrSettingsSection` |
| C-44 | 2026-07 메모리 기각: `CycleOrderComputer.compute()` 파라미터 객체화 기각 | 원문: `PlanContext`가 이미 캡슐화 | 메모리 `project_2026_07_11_full_review.md` | 2026-07 | `CycleOrderComputer.compute()` |
| C-45 | 2026-07 메모리 기각: 소유권 검증 중복 제거 | 원문: 이미 중앙화 (단, 백로그 CQ-41·CQ-09는 2026-11에 전략 소유권 2줄을 `OwnedStrategy`로 통일함. 범위가 다름, 아래 6절) | 메모리 `project_2026_07_11_full_review.md`, `backlog:683-687` | 2026-07 | `OwnedStrategy` |
| C-46 | 2026-07 메모리 기각: `with*()` 패턴 변경, widgets cross-import 코드 수정 | 원문: 기각. widgets cross-import는 문서 예외만 명문화 | 메모리 `project_2026_07_11_full_review.md` | 2026-07 | `with*()` 전반, kista-ui widgets |
| C-47 | 2026-07 메모리 기각: `@WebMvcTest` SAME_THREAD 제거 | 원문: 기각. `testing.md`도 필수로 명시 | 메모리, `testing.md` | 2026-07 | `@WebMvcTest` 클래스 |
| C-48 | trading-core 클래스 추가 시 root `HexagonalArchitectureTest`도 실행 (재발 방지 규칙) | 2026-11-08 `AccountLedgerTotalsService`가 `:trading-core:test`는 통과했지만 root 규칙에 걸려 CI 배포가 실패함 | 메모리 `feedback_root_archunit_covers_tradingcore.md` | 2026-11-08 | `HexagonalArchitectureTest` |

---

## 3. 매매·도메인 정책 결정

| 번호 | 결정 내용 | 당시 이유 | 출처 | 결정 시기 | 관련 코드 위치 |
|---|---|---|---|---|---|
| P-01 | INFINITE 1회 매수금 산정은 변경 없음. `unitAmount = (원장 예수금 + avgPrice×holdings) / divisionCount`를 매 계획마다 최신 `cycle_position`에서 재계산. 시가평가 기준 전환 기각. PRIVACY식 자산비 동적화 재제안 금지 | 2026-11-11 검토. 시가평가로 바꾸면 T 계산·`priceOffsetRate`·`isFinalRound`·리버스 진입이 가격에 흔들리고, 미실현 이익이 고점 매수액을 부풀림. 원장 기반이라 입출금·실현손익이 이미 반영됨 | 메모리 `project_infinite_sizing_reviewed.md` | 2026-11-11 | `InfinitePosition.unitAmount`, `CapitalLedgerService` |
| P-02 | INFINITE LOC 매도(기준가+0.01)는 `AT_CLOSE`. 22:00~04:30 사이 입출금이 있어도 매도·매수 기준가가 맞물림. 지정가 매도(목표가)는 `AT_OPEN` 유지, 잔존 한계는 하루 동안 목표가가 stale일 수 있는 것 | 입출금 시점에 따른 LOC 가격 불일치 수정. 리버스모드와 동일 방식 | 메모리 `project_infinite_sizing_reviewed.md`; `modules/tradingcore/trading.md:49`; 커밋 `fa18083cd` | 2026-11-11 | `InfiniteStrategy`, `workflow-batch.md:10` |
| P-03 | cap 배수 1.05 유지. 1.10과 1.02~1.03은 측정상 이득이 작거나 표본 대표성이 부족. 표본이 쌓이면 같은 조회로 재평가 | 2026-10-25 재평가: 36일 중 cap 가격 미체결 1일. 1.10은 체결액 약 4%p 감소. 1.05는 "정량 근거 없는 운영 판단값"이라고 원문에 명시 | `modules/tradingcore/trading-formulas.md:32-35` | 2026-10-25 | `PriceCapPolicy` |
| P-04 | cap 재평가 방법 재설계(백로그). 식별법을 새로 정한 뒤 1.02~1.10 재평가 | 원문: 기존 SQL("보정 leg가 있는 날은 cap 발동일")은 보정이 매일 붙어 무효. 표본이 몇 주 쌓이면 재설계. 사용자 백로그 보류 | 메모리 `project_infinite_correction_followups.md`(항목 1) | 2026-11-08 | `trading-formulas.md:34` |
| P-05 | 계좌 단위 "base 먼저, 보정 나중" 접수는 보류 | 트리거 미충족: 계좌당 INFINITE 둘 이상에서 base 거절 `InsufficientBalanceEvent`가 실제로 반복될 때 착수. 2026-11-08 운영 조회상 계좌 2개에 1개씩 | 메모리 `project_infinite_correction_followups.md`(항목 2) | 2026-11-08 | `TradingService.placeAll`, `parallelRunner.runAll` |
| P-06 | 전략 단위 "base 먼저, 보정 나중" 접수는 완료(커밋 `ff594837c`). 계좌 단위 구현과 혼동하지 않음 | 계좌 단위 구현은 브랜치·stash·dangling 커밋에 없음 | 메모리 `project_infinite_correction_followups.md` | 2026-11 | — |
| P-07 | 리버스모드 쿼터매수 BUY에도 cap 적용. 가격만 cap으로 낮추고 수량은 쿼터 예산 ÷ cap | cap의 목적은 증권사 LOC 괴리(15~20%) 거부 방지. 리버스 쿼터는 급락 구간에서 괴리가 더 커서 cap이 필요하다는 사용자 확인 | `backlog` CQ-30 상태(L496); `workflow-capper.md:15`; `trading-formulas.md:42` | 2026-11-06 | `ReverseInfiniteStrategy.LOC_BUY_LEG`, `ReverseModePosition.quotaBuyQuantity` |
| P-08 | INFINITE 보정 주문 3건은 일반모드 BUY마다 상시 부착. 보정 합계가 원장 예수금을 넘으면 뒤쪽(03→02→01)부터 제거. base는 불변 | 이유 미기재(원문은 동작 설명만) | `workflow-capper.md:17,21`; `trading-formulas.md:17,30` | 미기재 | `InfiniteStrategy.buildOrders`, `trimCorrectionsToBudget` |
| P-09 | 리버스 별지점 = 최근 5거래일 종가 평균(scale 2, HALF_UP). LOC 매도가 = 별지점, 쿼터 매수 LOC = 별지점 − 0.01. 공식은 변경 금지 | 원문: 변경 금지 SSOT. 수동 체결 보정 행은 체결가가 종가 자리에 들어가므로 제외 | `modules/tradingcore/trading-formulas.md:37-40` | 미기재 | `CycleOrderComputer:122`, `BacktestEngine:570` |
| P-10 | PRIVACY cap 초과 시 수량 재산정 없음. cap 초과 BUY 주문가만 `currentPrice × 1.05`로 교체, 수량은 FIDA 원본 유지 | 원문: 수량은 FIDA 원본 유지 | `workflow-capper.md:32` | 미기재 | `PrivacyCycleOrderStrategy.capBuyOrders()` |
| P-11 | PRIVACY·INFINITE BUY·LOC 매도 등 AT_CLOSE 주문의 open 경로 cap은 실질 no-op(대칭성 유지 목적) | 전략별 분기 대칭성 | `workflow-capper.md:27,35` | 미기재 | `capIfNeeded(atOpen=true)` |
| P-12 | VR 사이클 `capsIndividualOrders()` 기본값 false 유지 | 사다리 전체 취소·재저장 방식 유지 | `workflow-capper.md:42` | 미기재 | `VrCycleOrderStrategy`, `BuyOrderPriceCapper.applyBatchCap` |
| P-13 | PRIVACY 기준표 조회 `findTodayTrade`는 발행일 == `releaseDateFor(거래일)` 정확 일치만. `findSeedPreviewBase`만 `>=` 유지 | 운영 PRIVACY 78개 거래일 모두 D-1 기준표가 있어 결과 동일. 미래 기준표를 끌어오지 않기 위함 | `modules/tradingcore/privacy.md:18`; backlog CQ-32 (L510) | 2026-11-06 | `findTodayTrade`, `BacktestService` |
| P-14 | PRIVACY 보유 초과·부족분 처리는 "의도된 동작" | 원문 절 제목만 있음, 이유 미기재 | `modules/tradingcore/privacy.md:22` | 미기재 | PRIVACY 체결 처리 |
| P-15 | 보유 0이어도 사이클 유지: `endsCycleOnLiquidation()=false`. `CyclePositionPersistor`가 종료를 발동하지 않음 | 원문 이유 미기재 | `modules/tradingcore/trading.md:44` | 미기재 | `Strategy.endsCycleOnLiquidation` |
| P-16 | VR N주 롤오버: V′ ≤ 0이면 롤오버 보류(사이클 유지, 알림). V=0·holdings=0인 롤오버는 `nextValue()` 결과를 그대로 씀. "V 강제 0 유지" 가드는 폐기 | 원문 이유 미기재. 자연 성장 | `modules/tradingcore/trading-formulas.md:85` | 미기재 | `VrCycleRolloverService.rollIfDue` |
| P-17 | VR gStepWeeks=0 / pStepWeeks=0 허용(램프 비활성화). 나머지 파라미터 검증 생략 | 램프 자체를 끄는 값 | `trading-formulas.md:64,66` | 미기재 | VR 등록·재설정 검증 |
| P-18 | VR 시작 전 재설정은 새 사이클을 만들지 않고 첫 사이클을 제자리에서 갱신. startDate 유지, 새 사이클 알림 없음 | 역전된 startDate 사이클 생성 방지(CQ-44 정책 판단: 거부 대신 허용) | `trading-formulas.md:87`; backlog CQ-44 (L703) | 86c04774e | `CycleSnapshotCreator.reconfigureVrCycleBeforeStart` |
| P-19 | 투자금 추가·인출 이력은 `cycle_capital_event`에 남기고, 시드 수정·VR 시작 전 재설정도 `RESET` Kind로 차액을 기록. 과거분 백필은 하지 않음 | 원금선 소급 변경 방지. 이력은 배포 이후부터. 사용자 결정(2026-11-09): 시드 수정에 종료 사이클 400, 사이클 잠금, 마감 리포트 창 거절 추가 | backlog CQ-35 (L550), CQ-69 (L947-L948) | 2026-11-06 / 2026-11-09 | `CapitalLedgerService`, `StrategyUpdateWriter:101-102`, `CycleSnapshotCreator:108-113` |
| P-20 | 투자 포인트는 이력으로 restore한 뒤 계산. 흐름 금액은 평가액 변화 기준이라 흐름일 수익률 0 | 원문: BINANCE 운영 사례 재현 고정 | backlog CQ-69 (L947-L952) | 2026-11-09 | `InvestmentPointsQueryService:70-75` |
| P-21 | 투자지수(TWR)용 `totalAssetAtCloseOrNull()`(종가 없으면 건너뜀)과 summary·곡선용 `totalAssetWithAvgPriceFallback()`은 의도적으로 2개 | 목적이 다름. 원가 섞임 방지 vs 항상 값 | `modules/tradingcore/stats.md:10` | 미기재 | `CyclePosition` 2 메서드 |
| P-22 | 평균 수익률 정의는 "유지·고지"(CQ-70). 승률·평균 수익률·성과 목록은 불변 | 원문 절 제목. 이유 미기재 | `modules/tradingcore/stats.md:29`; backlog CQ-36 (L557) | 2026-11-06 / 2026-11-09 | `TradingStatsService` |
| P-23 | VR "실현손익"은 종료 사이클 기준으로 재정의(마지막 holdings 0이면 end−start, holdings>0이면 장부가 기준 실현분). 별도 realized_pnl 미기록 | 원문: CQ-67·68과 대체. 라벨 "종료 사이클 손익" | backlog CQ-36 (L557) | 2026-11-06 | `TradingStatsService` |
| P-24 | 전략별 원장 상한: 각 전략은 자기 원장(`cycle_position.deposit`) 안에서만 BUY. 보류: MOCK 장중 접수 주문의 시가 혜택 | 원문: 전략 간 공유 금지 취지. 보류 사유는 접수 시각+1분봉 필요 | 메모리 `project_done_do_not_redo.md`; `workflow-ledger-price.md:6` | 2026-10-24 | `StrategyLedgerBudget` |
| P-25 | 원장↔증권사 보유 수량 자동 대사 없음 (C-36과 동일 결정) | 같은 계좌의 직접 매매는 정상 | backlog L630 | 2026-11-07 | `TradingReporter` |
| P-26 | 코인 부분체결 후 취소 → FILLED 확정 유지 (C-30과 동일) | 업비트 자투리 | backlog L641 | 2026-11-08 | `CryptoOrderSynchronizer.settle` |
| P-27 | 수동 마감 트리거: 창 밖이면 202 후 관리자 알림으로 거부. 현행 유지, 비대칭 이유를 주석으로 남김 | 원문 정책 판단 결과 유지 | `workflow-batch.md:15`; backlog CQ-60 (L845-L849) | 2026-11-09 | `TradingCloseScheduler.runNow`, `TradingSchedulerInternalController` |
| P-28 | 시드 수정·인출 뒤 INFINITE 최소 시드 재확인 (CQ-63) | 정책 판단 후 구현(완료). 원문 이유는 "다시 확인하지 않는다" 문제 제기 | backlog CQ-63 (L862-L866) | 2026-11 | `StrategyUpdateWriter` 등 |
| P-29 | INFINITE 최소 시드 fail-open (C-15와 동일) | 2026-10-31 확정 | backlog L616 | 2026-10-31 | `StrategyCreationService:154` |
| P-30 | 정지 전략은 접수 주문 보유 시에만 마감 리포트 대상 (`CloseReportScope.awaitsCloseReport`) | 원문: 투자금 추가·수동 보정과 같은 기준 | backlog CQ-27 (L474) | 2026-11 | `CloseReportScope` |
| P-31 | 등록 예수금 부호·자릿수 검증은 DCA 외 전략에도 적용 | 원문 CQ-58 완료 | backlog CQ-58 (L829-L835) | 2026-11 | 등록 검증 |
| P-32 | 바이낸스 clientOrderId 재전송 금지. 접수는 재전송하지 않고 결과 불명이면 SUBMITTING 유지 | 재전송이 곧 이중 매수 (clientOrderId 재사용 허용) | `modules/tradingcore/dca.md:20` | 미기재 | `BinanceOrderApi` |
| P-33 | 바이낸스 -1021(timestamp 범위 초과)은 재시도·시각 동기화 없이 ERROR 로그만 남김 | 원문 표 기재. 시각 오차 보정은 트리거 조건부 보류(P-41 참조) | `binance-api.md:41` | 미기재 | `BinanceApiException(rejected=true)` |
| P-34 | 정책 제외: 주식 DCA SELL 상한·재주문 원장 예산 등 CQ-23·CQ-24 취소분 차감은 "기록된 filledQuantity" 정의로 통일 | 원문: 취소된 주문의 체결분 처리 | backlog CQ-23 (L446), CQ-24 (L453) | 2026-11 | `StrategyLedgerBudget.pendingCancelledFill` |
| P-35 | 라이브 `CycleSnapshotCreator`와 `CycleOrderComputer`의 별지점 계산 두 곳은 공식이라 함께 본다 | 한쪽만 고치면 불일치 | backlog L597 | 2026-11-01 | `CycleOrderComputer:122`, `BacktestEngine:570` |
| P-36 | 백테스트 시드 정책·원장 예산·PRIVACY 중간 시작 운영 정합 (CQ-71) | 2026-11-10 백테스트 감사 결과 완료. TWR 지표 후속까지 완료 | backlog CQ-71 (L965-L972) | 2026-11-10 | `BacktestService` |
| P-37 | 코인 계좌 USD 접미사 재검토 트리거: 코인 계좌가 마감 리포트·`TradingReportReadyEvent`에 합류할 때 | 원문 트리거 | backlog L653 | 2026-11-10 | `TradingReport` |
| P-38 | 바이낸스 출금 권한 점검 스케줄러: 다른 사용자에게 공개할 때 착수 (트리거 조건부) | 원문: 연결 테스트가 이미 출금 권한이 켜진 키를 거절 | 메모리 `project_binance_dca.md` | 2026-11-08 | `BinanceAuthApi.enableWithdrawals` |
| P-39 | 1달러 미만 코인 추가 직전에 가격 컬럼 정밀도 확장 (트리거 조건부) | 원문 | 메모리 `project_binance_dca.md` | 2026-11-08 | 가격 컬럼 |
| P-40 | 입금 주소 표시·입금 도착 알림 기각. 격리 마진 레버리지 DCA는 별도 기획(기각) | 바이낸스 앱으로 충분 / 후속 작업이 아님 | 메모리 `project_binance_dca.md` | 2026-11-08 | — |
| P-41 | -1021 시각 오차 대응(`/api/v3/time` 오프셋 보정)은 운영 로그에 실제 발생하면 착수 (트리거 조건부) | 원문 | 메모리 `project_binance_dca.md`; `binance-api.md:41` | 2026-11-08 | `BinanceHttpClient` |
| P-42 | 2026-07 잔고 급변 가드·서킷브레이커 미진행 (S-13과 동일) | 사용자 확정 | 메모리 `project_2026_07_11_full_review.md` | 2026-07-17 | — |
| P-43 | 원장 기반 INFINITE 재계산은 "변경 불필요" — 동적 사이징 재제안 금지 (P-01과 동일) | 검토 결과 | 메모리 `project_infinite_sizing_reviewed.md` | 2026-11-11 | — |
| P-44 | MAINTAIN 로테이션 시 수익을 미사용 현금으로 남기는 것은 의도 | 백테스트도 idleCash로 같은 방식 | 메모리 `project_infinite_sizing_reviewed.md` | 2026-11-11 | `MAINTAIN` 로테이션 |
| P-45 | 장중 추가·인출 시 INFINITE 지정가 매도 목표가 stale 한계는 `trading.md`에 알려진 한계로 기록(코드 미수정) | 원문: 한계 기록 | `modules/tradingcore/trading.md:49`; 커밋 `15ece40e7` | 2026-11-11 | `InfiniteStrategy` |
| P-46 | 사이클 시작 시가 평가 저장: `strategy_cycle.open_amount` 저장. 개장 시가는 따로 두지 않음 | 실현 승계 프리미엄 D = 개장 평가 − 장부가가 첫 행에서 나옴. 같은 날 매수·매도 동시 체결은 순증감 근사 | backlog CQ-67 (L928) | 2026-11 | `CapitalLedgerTimeline.openBasisRealized` |
| P-47 | `startAmount`(매매 계약)은 변경하지 않음 (CQ-67) | 원문: 매매 계약인 startAmount는 그대로 둔다 | backlog CQ-67 (L926) | 2026-11 | `StrategyCycle` |
| P-49 | Toss 체결 조회 날짜는 "US 세션일 + 1" 규칙 (04:30 경계 대신) | broker는 trading `DstInfo`를 쓸 수 없어 KIS·메리츠와 같은 규칙 채택 | backlog CQ-26 (L467) | 2026-11 | `TossOrderApi`, `UsTradeDates` |

---

## 4. code-quality-backlog 미해결 항목

**결론: 미해결(open) 항목은 없다.** 72개 CQ 항목 전부 상태가 done/closed/해소 중 하나다. 이 중 70개는 `- 상태: done…`/`closed` 표기이고, CQ-28은 "해소(2026-11-04)", CQ-36은 "(a) done / (b) 불필요 / (c) done"이다. 아래는 상태는 닫혔지만 잔여 사항이 남은 항목이다.

| ID | 제목 | 대상 파일 | 상태 | 한 줄 요약 |
|---|---|---|---|---|
| CQ-21 | 백테스트 러너 선택의 타입 switch | `BacktestService`, `BacktestEngine` | done(부분) | VR·INFINITE 전용 검증 분기는 타입 고유 파라미터 검증이라 유지 |
| CQ-28 | 마감 접수 후~장마감 사이 재주문이 리포트에서 빠짐 | 재주문 경로 | 해소(2026-11-04) | 기술한 경로는 재현 안 됨. 대신 자정~장마감 IMMEDIATE 차단 판정 오류와 원본 거래일 미검사를 찾았음 |
| CQ-36 | VR "실현손익"에 평가익 포함 | 통계·UI 라벨 | done(a·c), (b) 불필요 | 남은 후속 없음(2026-11-09 확인) |
| CQ-46 | 같은 계좌·종목 중복 등록 경합 | 등록 경로 | done | 배포 전 운영 중복 0건 재확인 필요(2026-11-08 기준 0건) |
| CQ-56 | trading 잔손질 묶음 | `CancelledOrderFillRecorder` 외 | done | 미처리 3건(바깥 try/catch, 포트 이름, 기간 기본값 통일)은 유지 목록으로 이동 |
| CQ-59 | `markCancelled`·`markFilled` 잠금·상태 검사 없음 | 주문 상태 기록 | done | 행 잠금은 넣지 않음(결정 범위 밖). 조회→검사→저장 사이 경합 창 잔존. 실제 경합 경로는 미확인 |
| CQ-00 | PRIVACY `currentRound` 항상 0.0 | `StrategyService` | done(24b885bc5) | 본문에 "kista-ui가 null을 받아도 깨지지 않는지 미확인"이라는 메모가 있음(L35) |
| CQ-42 | 미사용 import·파라미터 잔재 | 전역 | done | 가드는 두지 않음(유지 목록의 "미사용 import 빌드 가드") |
| CQ-44 | 시작예정일 전 VR 재설정 역전 사이클 | VR 재설정 | done(86c04774e) | 거부 대신 허용을 택함(P-18) |
| CQ-63 | INFINITE 최소 시드 재확인 | 시드 수정·인출 | done(8ece9ebdf) | 전일종가 조회는 `NOT_SUPPORTED` 진입점에서만 |

완료 항목 수: 72개 전부 닫힘(done 70, 해소 1, (a)(b)(c) 혼합 1). 유지 목록(백로그 L578-L654)에는 73행이 있다.

---

## 5. 트리거 조건부로 보류된 작업

| 작업 | 무엇이 충족되면 재개하는지 | 출처 |
|---|---|---|
| INFINITE: 계좌 단위 "base 먼저, 보정 나중" 접수 (약 100줄, `TradingService.placeAll` 2회 순차 호출) | 계좌당 INFINITE가 둘 이상이고, base 거절 `InsufficientBalanceEvent`가 실제로 반복될 때 | 메모리 `project_infinite_correction_followups.md`(항목 2) |
| INFINITE cap 1.05 재평가 방법 재설계(식별법 새로 정하고 1.02~1.10 재평가) | 표본이 몇 주 쌓이면 착수. 식별법 후보: base 매수 지정가가 평단·기준가보다 낮은 날, 또는 접수 시점 현재가 기록 추가 | 메모리 `project_infinite_correction_followups.md`(항목 1); `trading-formulas.md:34-35` |
| 바이낸스 출금 권한 주기 점검 스케줄러 | 다른 사용자에게 공개할 때 | 메모리 `project_binance_dca.md` |
| 바이낸스 가격 컬럼 정밀도 확장 | 1달러 미만 코인을 추가하기 직전 | 메모리 `project_binance_dca.md` |
| 바이낸스 -1021 시각 오차 대응 | 운영 로그에 실제로 발생할 때 | 메모리 `project_binance_dca.md`; `binance-api.md:41` |
| 물리 DB 분리(4b-2) | 원문 "보류 중". 재개 조건 미기재. 선행 작업은 스키마 재편으로 완료 | `history:150` |
| 전략별 원장 상한: MOCK 장중 접수 주문의 시가 혜택 | 접수 시각+1분봉 데이터가 필요(원문 보류 사유) | 메모리 `project_done_do_not_redo.md` |
| KIS APBK0952 취소 분류 처리 | 원문 "보류 중". 재개 조건 미기재 | 메모리 `project_done_do_not_redo.md` |
| 2026-10-31 보류: 재발행 스케줄러 shared 추출, 예외 핸들러 오류 보고 통합, FcmAdapter `addAllTokens` | 재개 조건 미기재 | 메모리 `project_review_refactor_2026_10_31.md` |
| 미사용 import 빌드 가드 | 이관과 무관한 재발이 감사에서 확인될 때. 신규 플러그인 필요 | `backlog:635` |
| "활성 최신 버전" 정렬 기준 3종(`version_no` vs `created_at`) | 활성 버전이 1개라는 불변식 위반이 발견될 때 | `backlog:713` |
| USD 접미사 개명(`TradeLegSummary.amountUsd` 등) | 코인 계좌가 마감 리포트·`TradingReportReadyEvent`에 합류할 때 (`currency` 필드 추가 + 개명 + `@JsonAlias`) | `backlog:653` |
| CQ-46 배포 전 운영 중복 확인 | 배포 전 재확인(2026-11-08 기준 0건) | `backlog:729` |
| CQ-59 행 잠금 추가 | 원문: 결정 범위 밖. 재개 조건 미기재 | `backlog:843` |
| 브로커 축소: 업비트·빗썸·KIS 제거 | "may be removed in the future" (사용자 진술). 재개 조건 없음 | 메모리 `project_broker_strategy.md` |
| SaaS 상용화(결제·구독·온보딩) | 별도 이니셔티브로만 연다 | 메모리 `project_2026_07_11_full_review.md` |
| `StrategyCycle` update 경로 생김(엔티티 저장 패턴) | update 경로가 생기면 재검토 | `backlog:651` |
| 재발행 설정 변경 | 이벤트·시그니처가 바뀔 때 `listener_id` 정책 재확인 | `docker-infra.md:134` |

---

## 6. 서로 모순되거나 같은 결정이 다르게 적힌 곳

1. **cap 1.05 재평가 방법 (높음)**: `modules/tradingcore/trading-formulas.md:34-35`는 "표본이 쌓이면 같은 조회로 재평가한다"고 적는다. 반면 메모리 `project_infinite_correction_followups.md`(항목 1)는 같은 조회("보정 leg가 있는 날 = cap 발동일")가 보정이 매일 붙어 무효이며 식별법을 재설계해야 한다고 적는다. 두 문서가 서로 다른 재평가 방법을 말한다.
2. **소유권 검증 중복 제거**: 메모리 `project_2026_07_11_full_review.md`는 2026-07에 "소유권 검증 중복 제거(이미 중앙화)"를 기각 확정했다. 백로그 `CQ-41`(L683-L687, 2026-11-08 이후)은 전략 소유권 2줄 반복을 `OwnedStrategy`로 통일해 완료했고, `CQ-09`(L184)도 `verifyOwnedBy` 직접 호출을 정리했다. 범위(전략 소유권 반복 vs 계좌·전략 소유권 검증 중복 제거)가 다르지만 "재론 금지" 목록과 실제 처리가 충돌한다. 기각이 무효화된 것은 아니다.
3. **가드 상태 표기**: `constraints.md`는 `ErrorCode`를 "상수 이름 변경·삭제 금지"로 적고(L45), 백로그는 "미사용 import 빌드 가드를 두지 않는다"(L635)고 적는다. 충돌은 아니고 계약 차이이지만 두 곳 모두 "가드 없음" 결정이다.
4. **EPR 재발행 소유**: `constraints.md:7`은 `REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART`를 "kista-trading·kista-scheduler만 true"라고 적는다. `modules/tradingcore/notify.md:9`는 이 프로세스(kista-trading)가 재발행 소유자라고 적는다. 메모리 `project_review_refactor_2026_10_31.md`는 "재발행 스케줄러 shared 추출"을 보류 확정했다. 셋 다 같은 설정을 말하지만 kista-scheduler의 역할은 `docker-infra.md:13`에만 있다.
5. **거래일 경계와 DST**: `scheduler-time-table.md:63-69`는 경계를 04:30으로 고정하고 DST별 분기를 기각했다. `workflow-batch.md:12`는 orderAt을 DST=04:30, 비DST=05:30으로 적는다. 경계(04:30 고정)와 주문 시각(DST별)은 다른 개념이라 모순은 아니지만, 독자가 혼동할 수 있다.
6. **INFINITE 매도 주문 시점**: 메모리 `project_infinite_sizing_reviewed.md`는 LOC 매도가 AT_CLOSE로 바뀌었고 지정가 매도는 AT_OPEN으로 남는다고 적는다. `workflow-batch.md:10`은 AT_CLOSE 슬롯에 "INFINITE BUY·LOC 매도"를 포함한다. 일치한다.
7. **cap 배수 1.05 "변경 금지" 대 "값 유지"**: `trading-formulas.md:1-3`은 파일 전체를 "변경 금지"로 선언하지만, 같은 파일 32절은 1.05가 "정량 근거 없는 운영 판단값"이며 재평가 대상이라고 적는다. 공식 SSOT와 운영 튜닝 값의 경계가 문서에서 흐리다.
8. **리버스 cap**: 백로그 CQ-30 본문(L494)은 "`workflow-capper.md`에 리버스 제외 기술이 없다"고 적었고, 현재 `workflow-capper.md:15`와 `trading-formulas.md:42`는 리버스 쿼터 cap을 적는다. 상태(done)와 문서가 일치한다.
9. **CQ-59 상태**: 백로그 상태는 "done(a0877d62e)"이지만 본문은 "행 잠금은 넣지 않았다(결정 범위 밖, 경합 창 잔존)"라고 적는다. 상태 표기만 보면 완전 해소처럼 읽힌다.
10. **CQ-56 잔여 항목 이동**: 백로그 CQ-56 상태(L820)는 done이면서 미처리 3건을 "유지 목록으로 옮겼다"고 적는다. 그 3건 중 `CancelledOrderFillRecorder` 바깥 try/catch(유지 목록 L642)는 "도달 불가 아님, 지우면 동작 변경"으로 유지되었다. 일치한다.
11. **CQ-28 재현 결과**: 백로그 CQ-28은 원래 "마감 접수 후 재주문이 리포트에서 빠짐"이었는데 "재현 안 됨"으로 해소됐다. 그러나 같은 항목에서 다른 판정 오류를 찾았다고 적었다. 해당 판정 오류의 처리 상태는 본문에 명시되지 않는다.
12. **persistence 기본값 규칙과 조회 폴백**: `constraints.md:9-14`의 "persistence 기본값" 절은 "기본값은 도메인 생성 시점에서 정하고 어댑터에 폴백을 두지 않는다"고 한다. 그런데 백로그 keep list L631("조회 경로 `.orElse(DEFAULT_DIVISION_COUNT)` 폴백")은 버전 상세 행 누락 방어로 조회 경로 폴백을 유지한다. 예외 조항은 "DB 컬럼 자체가 nullable인 레거시 값"뿐이라 이 폴백은 규칙 예외가 아니라 별도 판단으로 유지되었다.
13. **재논의 금지 범위**: 백로그 운영 규칙(L5-L14)은 "재논의 금지"에 `project_review_refactor_2026_10_31`, `project_2026_07_11_full_review`, `own-type-ledger.md`, `constraints.md`의 확정 판단을 포함한다. 반면 `docs/agents/constraints.md`에는 이 목록이 "재론 금지" 항목으로 따로 정리돼 있지 않다. 재논의 금지 범위가 문서마다 다르게 적혀 있다.

---

## 부록: 메모리 파일별 결정 요약 (재확인용)

- `project_2026_07_11_full_review.md`: S-11~S-16, S-12(기각), C-43~C-47, P-42
- `project_review_refactor_2026_10_31.md`: S-17~S-21, S-42~S-45
- `project_infinite_sizing_reviewed.md`: P-01, P-02, P-43, P-44
- `project_infinite_correction_followups.md`: P-04, P-05, P-06
- `project_binance_dca.md`: S-46, P-33, P-38~P-41
- `project_broker_strategy.md`: S-47
- `project_done_do_not_redo.md`: P-24, 보류 목록(MOCK, KIS APBK0952), 완료 목록(재작업 금지)
- `project_deploy_lag_404.md`, `project_agent_worktree_base.md`, `project_toss_redis_store_outage.md`, `reference_oci_region.md`: 운영 주의 메모(결정 아님, 본 목록에서 제외)
- `feedback_*.md`(git add 오염, 일관성 선호, 제대로 된 구조 개선, 제안 스타일, 리뷰어 보고, 운영→로컬 마이그레이션 제외, UI 작업 UI 세션, root ArchUnit): 행동 지침(동결 결정 아님). 이 중 "일관성 선호"와 "제대로 된 구조 개선"은 C-48과 함께 재심 시 참고가 된다.
