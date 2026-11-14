# 23. 지침서 01~07 전제 점검 (측정, 2026-11-14)

규칙: 확인 결과 열의 "일치 / 어긋남 / 대상 없음 / 미확인". 기본값·대체 경로 열은 지침서 안의 절 번호 또는 "없음". 코드 변경·커밋 없음.

## 요약

### 지침서 x 유형 건수(표 행 수, 한 행이 여러 유형이면 대표 유형 하나)

| 지침서 | U1 | U2 | U3 | U4 | U5 | U6 | U7 |
|---|---|---|---|---|---|---|---|
| 01 | 1 | 8 | 1 | 1 | 1 | 2 | 7 |
| 02 | 2 | 9 | 1 | 1 | 1 | 3 | 6 |
| 03 | 0 | 14 | 2 | 1 | 1 | 3 | 6 |
| 04 | 0 | 13 | 1 | 1 | 2 | 4 | 7 |
| 05 | 1 | 24 | 2 | 2 | 3 | 5 | 8 |
| 06 | 0 | 16 | 2 | 1 | 2 | 3 | 6 |
| 07 | 0 | 16 | 2 | 1 | 1 | 3 | 4 |

절 유무(기본값 / 중단 조건 / 하지 말 것): 01 없음/없음/7절, 02 없음/없음/7절, 03 없음/없음/7절, 04 없음/없음/9절, 05 9절/10절/없음, 06 없음/9절/8절, 07 없음/없음/7절.

### 어긋난 U2 전제(중요도 순)

1. **04 단계 A-3·2-1** "KIS 토큰 테이블 drop·`KisToken*` 삭제": `KisTokenEntity`가 `trading.broker_tokens`에 매핑되고 `KisTokenPersistenceAdapter`가 `BrokerTokenCachePort`를 구현하며 메리츠(`MeritzAuthApi:44` -> `DbTokenCoordinator`)가 같은 테이블을 씀. 통째 삭제하면 메리츠 토큰 캐시가 죽음. A-1 전수표 분류와 A-3 마이그레이션이 영향.
2. **05 P3** "nativeQuery R 11곳·T 약 45곳": 지금 R 16곳·T 39곳. 가드 정규식 대상 범위가 달라짐.
3. **05 M9** "`PlainDecimalSerializer` 중복 1벌": 그 이름의 클래스 없음(대상 없음).
4. **05 X6** 호출처 목록: notifier는 6종(`CryptoTradeNotifier` 포함)이고 코인 2개(`CryptoTradeNotifier`·`CryptoDcaRunner`)는 15가 삭제. `TradingUserProfiles`·`UserNotifyProfilePersistenceAdapter`가 목록에 없음.
5. **02 T2** "marketcalendar 493줄": 지금 681줄(공백·주석 제외 544줄). T2 전후 측정 기준선이 다름.
6. **06 1-1** "22:00이 2곳": 3곳(`TradingOpenScheduler:32,46`, `DstInfo:121`). 06 T8 "문서 각 30줄 이하": `scheduler-time-table.md` 71줄·`workflow-batch.md` 34줄.
7. **04 1절 facade 359줄**: 지금 `*BrokerAdapter` 4개 574줄(KIS·Mock 제외 241줄). 04 E "`getPriceSnapshot(s)` 호출처 0": 외부 소비자 0은 맞으나 어댑터 4개·`BrokerRouter` 위임과 테스트 `verify(never())` 5곳 존재.
8. 줄 번호만 이동(영향 작음): 01 규칙 `:466`->465, 03 T8/T9 대상 줄(`StrategyUpdateWriter:58,61`->60,63, `CapitalLedgerService:78,183`->79,184, `VrReconfigureService:68`->69, `ManualTradingService:62`->57), 03 `CycleOrderComputer:44-56`->`:75-94`, 03 `TradingCycleResponse:125`(분기 위치 다름), 05 7절 `RefreshTokenCookieHelper`(경로 `user/adapter/in/web/security/`), 07 수치(480파일 77,704줄·`@Test` 3,756·TradingServiceTest 2,421줄).
9. 02 T6 선행: `finance` 외 `trading` Store 통합 대상 포트 3개(`StrategyInfinite/Vr/DcaDetailPort`)는 일치.
10. 미확인(타 레포·외부): 05 `build-bundle.sh:17`·`lb_try_duration` 120초(kista-infra), 05 M5 Boot 4.1.1 Flyway 자동구성 조건, 01·02의 CGLIB·Hibernate `@SQLRestriction` 프로젝션 동작.

### 걸리는 U7(상위)

1. **06 T3** cron 22:00 제거: `ArchitectureMapTest:31-36`이 `TradingOpenScheduler#run`의 process=`kista-trading`·times=`["22:00"]` 단언. 같은 테스트 수정 필요(T3·T8에 언급 없음).
2. **06 T3·T4·T5 / flows.yml** `code:` 참조: `TradingOpenScheduler#run`·`TradingCloseScheduler#schedule/#pingHeartbeatSlot/#runNow`·`TradingBatchResumer#recoverClose/#resumeOpen`·`DstInfo#wait*/#openBatchMissedAt`·`TradingService#waitFor`. 삭제·개명 시 `ArchitectureMapExporter.codeError`로 `ArchitectureMapTest` 실패. 06 T8이 문서·flows.yml을 별도 커밋으로 둬 중간 커밋이 깨짐.
3. **03 T4·T5·T9** 서비스 이동: `HexagonalArchitectureTest:296-311` `accountAllowed` Set에 `...trading.application.service.*` FQCN 박혀 있고 `:316 containsAll`이 이동·개명 시 실패(`VrReconfigureService`·`DcaConfigService`·`StrategyService` 등). T9에 갱신 문장 없음.
4. **04 단계 A** KIS 삭제: `HexagonalArchitectureTest:122-123` `UsTradeDates` 허용 목록에 `KisTradingApi`·`KisPriceApi` FQCN, `:442` "KIS 파서는 application 의존 금지" 규칙(삭제로 대상 비면 `failOnEmptyShould=true`로 실패 가능).
5. **05 M8·R·M9** 모듈 개명·합병: `ArchitectureMapTest:40-44`(두 모델·`api.notify`/`tradingcore.notify` 모듈명 단언), `GradleModuleBoundaryTest:55,74,120,130`(`..adapter.in.schedule..`·루트 패키지 전제, `AdminSchedulerController` FQCN 예외), `HexagonalArchitectureTest:179-180` ClockConfig FQCN 2개, `:136`·`:99` 시간·platform 규칙, `InternalApiContractTest`(X8까지 유지).
6. 01 B: usecase 패키지 9개 `@NamedInterface("usecase")`와 모듈 `package-info` 주석 6개, `HexagonalArchitectureTest:346·356·465` 3규칙(단계 A가 선행 삭제), `:231` notify->`application.usecase` 의존 금지.
7. 05 P4: `GradleModuleBoundaryTest:74`·`SchedulerDisabledContextTest`가 `scheduler.enabled` 전제.

### 순서·충돌 메모(사실만)
- 07 7절 "`@Disabled` 금지"와 01 단계 A "`interfaces_must_earn_their_keep`에 `@Disabled`와 사유"가 충돌. 지금 `@Disabled` 14건.
- 06 8절 "미국장 정규 시간(KST 22:30~06:00) 배포 금지"와 06 T4 "접수 후~리포트 전 의도적 재기동"이 같은 시간대.
- 04 선행 W1(KIS 계좌 삭제)이 사용자 작업 대기라 04 A·B와 02·06 T3의 선행이 막혀 있음(roadmap 2절).

---

## 지침서 01 헥사고날 다이어트 (S6)

실행 여부: 단계 A~D 모두 미실행(`git log --since=2026-11-11`에 usecase·인터페이스 제거 커밋 없음, usecase 패키지 9개 그대로 존재). 삭제·대체된 태스크: 없음(KIS 삭제는 04 단계 A 소관, 01 D-3은 "브로커 facade는 건드리지 않는다"라 영향 없음).

| 태스크 | 유형 | 지침서 문장 | 확인 결과 | 기본값·대체 경로 |
|---|---|---|---|---|
| 전체 | U2 | 1절 "UseCase 52개 + port/output 97개" | 일치. `find ... application/usecase -name '*.java' ! -name package-info.java`=52, `port/output`=97 | - |
| 전체 | U2 | 1절 "UseCase 52개는 전부 구현 1개" | 일치. 각 인터페이스명으로 `implements[^{]*\bX\b` grep → 52개 전부 main 구현 파일 1개 | - |
| B | U2 | 1절 "모듈 밖 소비 UseCase는 셋뿐(UserUseCase admin 2곳, AccountUseCase trading 1곳, PrivacyTradeValidationUseCase trading 1곳)" | 일치. 모듈 다른 곳 import perl 검사: `admin.TelegramBotService`·`admin.AdminService`→UserUseCase, `trading.UserTradingDataEraseService`→AccountUseCase, `trading.PrivacyBaseGuard`→PrivacyTradeValidationUseCase. 이 4건 외 없음 | - |
| D | U2 | 1절 "TradingExecutionFacade(80줄)" | 일치(79줄, `wc -l`). KisBrokerAdapter 파일 존재(`broker/adapter/out/kis/`) — 단 04 단계 A가 지우면 대상 없음 | D-3이 "건드리지 않는다" |
| A | U2 | 1절 규칙 `:346`·`:356`·`:466` | 어긋남(사소). 메서드 선언은 346·356·465행. 규칙 이름 3개는 일치 | - |
| A | U2 | 2절 "trading-core 쪽 아키텍처 테스트에 같은 규칙이 복제돼 있으면"(A-2) | 대상 없음. `trading-core/src/test/.../architecture/`에는 Modulith·OpenApi·StreamSubscriber·VendorModel 4개뿐, Hexagonal 없음. 루트 `HexagonalArchitectureTest`가 `importPackages("com.kista")`(`:54`)로 trading-core까지 봄 | 조건문이라 영향 없음 |
| C | U2 | 1절 "구현 다형성 포트 11개·의존 역전 4개" | 미확인(포트 97개 전수 구현 수 집계는 하지 않음) | C-1이 분류표를 먼저 만들게 함 |
| B | U2 | 4절 위험 1 "서비스에 final이 붙어 있으면" | 일치(없음). `grep 'final class' *Service.java` 0건 | - |
| B | U1 | 2절 "Spring 프록시는 CGLIB 클래스 프록시(Boot 기본 proxyTargetClass=true)" | 레포에 `proxyTargetClass`·`@EnableAspectJ` 설정 없음(기본값 사용). 외부 확인 필요: Spring Boot Reference "AOP - proxyTargetClass" | 위험 1에 컨텍스트 로드 테스트 |
| B | U7 | B-5 "서비스 클래스에 @NamedInterface("api")" | 걸림(주의). `HexagonalArchitectureTest.web_must_stay_pure_inbound_sink`(:248-260)는 `com.kista.api.web..`·`tradingcore.web..`의 `application.service` 의존 금지. 현재 web 모듈의 cross-module usecase import는 0건이라 지금은 안 걸리나, 컨트롤러가 UseCase 대신 서비스를 직접 쓰게 되는 모듈 내부 컨트롤러는 영향 없음. 단 web 앱셸이 서비스를 쓰는 변경이 생기면 걸림 | 없음 |
| A | U7 | A-1 "삭제: `inbound_adapters_must_not_depend_on_application_layer`(:346)" | 걸림(예상된 삭제). 삭제 전까지 B 단계에서 컨트롤러가 service를 직접 import하면 이 규칙(:346, `adapter.in..`→`application.service..`)과 `rest_controllers_must_not_depend_on_application_implementations`(:465)이 실패. 순서상 A가 먼저라 해소 | 단계 A가 B 앞(6절) |
| B | U7 | B-1~4 usecase 패키지 삭제 | 걸림. `inbound_port_interfaces_must_have_UseCase_or_Query_suffix`(:388)는 삭제 대상이라 OK. `inbound_adapters_must_not_perform_outbound_http`(:231)는 notify가 `..application.usecase..`에 의존 금지 규칙 — 패키지가 없어지면 의미가 비지만 `that()`가 notify 클래스라 `failOnEmptyShould`에 안 걸림(should 쪽은 noClasses) | 없음 |
| B | U7 | B-5 "NamedInterface("usecase")" 삭제 | 걸림(같이 고쳐야 함). usecase 패키지 `package-info.java` 9개에 `@NamedInterface("usecase")` 선언(`api/{benchmark,admin,user,finance,market}`, `tradingcore/{account,trading,stats,privacy}`). 모듈 `package-info.java`의 주석(`benchmark`·`admin`·`user`·`finance`·`privacy`·`account`)이 "usecase 공개"를 서술. 다른 모듈의 `allowedDependencies = "x::usecase"` 류가 있는지는 미확인(grep으로 `::usecase` 확인하지 않음) | B-7 문서 갱신 |
| B | U7 | 4절 위험 3 / B-7 `flows.yml` | 안 걸림. `flows.yml`에 `UseCase` 문자열 0건 | - |
| C | U7 | 단계 C `interfaces_must_earn_their_keep` | 걸림 없음(신설). 단 A-1에서 `@Disabled` 사유를 달라고 함 | A-1에 명시 |
| 전체 | U7 | 7절 "브로커 포트는 건드리지 않는다" | `TradingCoreTestVendorModelTest`·`.github/tests/*.bats`에 usecase 참조 0건(grep) → 안 걸림 | - |
| B | U3 | B-2 "한 서비스가 인터페이스 여러 개를 구현하면 그대로 둔다" | 구현 1개라 해당 없음(52/52). 여러 인터페이스를 구현하는 서비스가 있는지는 미확인 | 있음(B-2) |
| C | U4 | (없음) | 동작 변경 없음. 해당 문장 없음 | 7절 |
| 전체 | U5 | (없음) | 배포 문장 없음. 동작 불변이라 승인 문장 불필요 | 없음 |
| C | U6 | C-4 "인터페이스를 유지한다"(모듈 밖에 인프라 구현 노출 시) | 대체 경로 있음(유지 후 보고) | C-4 |
| 전체 | U6 | 4절 위험 1·2 "컨텍스트 로드 테스트가 잡는다" | 잡힌 뒤 대응(final 제거)은 있음, 순환 빈 참조 대응은 없음 | 위험 1만 |

U1 요약: 외부 문서 단정은 CGLIB 프록시 1건. 
절 점검: "판단이 갈릴 때의 기본값" 절 = 없음(개별 문장에 흩어져 있음: B-2, C-4). "중단 조건" 절 = 없음. "하지 말 것" 절 = 있음(7절).

---

## 지침서 02 영속·DTO 축소 (N7)

실행 여부: T1~T9 전부 미실행(스펙 스냅샷 파일·테스트 없음 — `OpenApiNullMarkedRecordContractTest`·`OpenApiSchemaNameCollisionTest`만 있음, Store 클래스 0개). 삭제·대체: T8의 "Alpaca 설정 3벌"은 14(공급자 전환)·05 X·R로 이동했을 수 있음(05 Q-07 답) — 02 본문엔 반영 없음. 04 단계 A로 KIS·업비트가 지워지면 T6·T8의 대상 파일이 줄어든다(5절 선행조건 3).

| 태스크 | 유형 | 지침서 문장 | 확인 결과 | 기본값·대체 경로 |
|---|---|---|---|---|
| 전체 | U2 | 1절 "@Query 101(native 55)·@Modifying 52·@SQLRestriction 29·@Convert 8" | 일치. `grep -rn` 집계: Query 101 / nativeQuery=true 55 / Modifying 52 / SQLRestriction 29 / Convert 8 | - |
| 전체 | U2 | 1절 "@Lock 2곳"(7절) · "연관 매핑 각 1" · "@Embedded·@Version 0" | 일치(Lock 2, ManyToOne 1, OneToMany 1, 기타 0) | - |
| T6 | U2 | 1절 "Entity 44 + JpaRepository 41" | 일치(`^@Entity` 44, `*JpaRepository.java` 41) | - |
| T6 | U2 | 2-3절 "StrategyInfiniteDetailPort·StrategyVrDetailPort·StrategyDcaDetailPort와 어댑터" | 일치. 포트 3개 `trading/application/port/output/`, 어댑터 3개 `trading/adapter/out/persistence/`에 있음 | - |
| T2 | U2 | 6절 "시범 모듈 marketcalendar(493줄)" | 어긋남(지금은 trading-core marketcalendar main 681줄, 공백·`//` 줄 제외 544줄. 지침서 이후 `d8ce1e96f`·`b047e292e`가 코드를 줄임에도 더 큼 — 측정 기준이 다른 것으로 보이나 어느 기준인지 미확인). T2의 "전후 줄 수 측정" 기준선이 달라진다 | 없음 |
| T5 | U2 | 6절 "StrategyDetailAssembler(190줄)" | 일치(189줄 `wc -l`). 위치는 `trading/application/service/StrategyDetailAssembler.java` | - |
| T8 | U2 | 6절 "동명 사본 `CycleHistory*`, `InvestmentPoint`, `MarketSessionResponse`" | 일치(존재). `CycleHistoryResponse`/`PageResponse`가 `trading/adapter/in/web/dto`와 `stats/adapter/in/web/dto` 두 곳, `InvestmentPoint`가 root `benchmark/domain/model`과 trading-core `stats/domain/model`, `MarketSessionResponse`가 root `market/.../dto`와 `shared/contract/marketcalendar`. 두 프로세스·두 모듈에 걸친 사본 | - |
| T6 | U2 | 3절 "C-27 `OrderPort.findByIdOrThrow` default 부재" | 일치(`OrderPort.java`에 `findByIdOrThrow` 없음) | - |
| T1 | U2 | 5절 "10 T1·T2 선행" | 완료됨(roadmap S3 `1915ba682`). 스냅샷 자체(02 T1)는 없음 | - |
| 전체 | U1 | 2-1절 "쿼리의 생성자 프로젝션(`select new`) 또는 인터페이스 프로젝션" | 외부 확인 필요: Spring Data JPA Reference "Projections"(native 쿼리의 생성자 프로젝션 미지원 여부). native 55개 중 View가 될 것은 4절 위험 3이 "JPQL 기본"으로만 처리 | 4절 위험 3(native면 deleted_at 명시) |
| 전체 | U1 | 2-4절 "JPQL 프로젝션은 `@SQLRestriction`이 적용된다"(4절 위험 3) | 외부 확인 필요: Hibernate 7 User Guide "@SQLRestriction"(HQL select-new에 적용되는지). 레포 근거는 `constraints.md` "nativeQuery는 미적용"뿐 | 위험 3 "테스트로 확인" |
| T6 | U7 | 2-3절 "Entity·JpaRepository는 package-private으로 Store 뒤에" | 안 걸림. `persistence_jpa_repositories_must_be_package_private`(`HexagonalArchitectureTest:412`)와 같은 패키지(`trading/adapter/out/persistence` 단일, `policy` 하위만 별도)라 Store가 같은 패키지에 있으면 OK. 서브패키지에 Store를 두면 걸림 | 없음 |
| T6 | U7 | 2-3절 Store 개명(`PersistenceAdapter`→`Store`) | 안 걸림 확인 필요: `*Store` 접미사 규칙은 가드에 없음. `outbound_port_interfaces_must_have_Port_suffix`(:376)는 interface만 대상 | 없음 |
| T6 | U7 | T6 "테이블 단위 포트 제거" + 01 단계 C | 걸림(01과 같은 파일). `HexagonalArchitectureTest:285-311` denylist는 `application.service` FQCN 목록(`StrategyService` 등)이며 Store·포트 이름은 없음 → T6 단독으론 안 걸림. 단 `service/support/OwnedStrategy` 등이 목록에 FQCN으로 박혀 있어 03·05가 서비스를 옮기면 같이 고침 | 없음 |
| T4~T8 | U7 | 2-1절 "View record는 모듈의 `api` 패키지" | 걸림 가능. 현재 모듈 NamedInterface는 "domain"/"usecase"/"port"/"event"/"schedule"(architecture.md)이고 "api"는 없음 → 05의 모듈 재구성(`api` 인터페이스)과 순서 맞춰야 함. `ModulithArchitectureTest`(root·trading-core)가 NamedInterface 외 참조를 막음. 지침서 02 안엔 임시 위치 없음 | 없음("지침서 05와 조율"만) |
| T9 | U7 | T9 "필드 집합이 같은 record 금지 가드" | 걸림 없음(신설). 단 `failOnEmptyShould=true`(testing.md)라 허용목록이 비면 안 됨. 현재 같은 필드 집합 쌍(`Order`↔`OrderResponse` 등)이 다수라 허용 목록 크기가 큼 | 허용 목록 포함(T9) |
| T1 | U7 | 5절 스냅샷 테스트 | 걸림 없음. 단 `OpenApiSchemaNameCollisionTest`(root·trading-core)·`OpenApiNullMarkedRecordContractTest`가 이미 스펙을 읽음 — 스키마 이름 변경은 3군데 영향. 지침서는 `@Schema(name=...)`로 이름 유지(8절) | 8절 |
| T6 | U3 | 6절 T6 "쓰기 경로의 SQL과 트랜잭션 경계는 바꾸지 않는다" | 스펙 공백 아님. 단 Store 5개 경계(`Strategy`+버전·상세 등)에서 현 어댑터 5개가 합쳐지며 `@Transactional` 위치가 바뀌는지에 대한 기본값은 없음 | 7절(경계 불변)만 |
| T6 | U4 | 6절 T6·T5 "매매 원장을 건드리므로 리뷰어를 Opus로" | 진행 중 사이클·주문에 대한 2차 효과 문장 없음(스키마·컬럼 불변이라 데이터 영향 없음은 7절 "Entity 컬럼 매핑 불변"로 간접) | 7절 |
| 전체 | U5 | (배포 문장 없음) | T5·T6은 원장 경로인데 배포 시점·승인 주체·확인 SQL 없음 | 없음 |
| T4 | U6 | "관리자 View는 데이터 소유 모듈로 — 지침서 05와 조율" | 멈춘 뒤 대체 경로 없음 | 없음 |
| T9 | U6 | 6절 말미 "리뷰어를 Opus로" | 해당 아님 | - |
| 선행 | U6 | 5절 "지침서 04(브로커 삭제)를 먼저 끝낸다" | 04 단계 A(W1)는 사용자 작업 대기 중. 04가 안 끝났을 때의 대체(삭제 예정 파일 건드리지 않기)는 있음("삭제될 코드를 옮기지 않는다") | 5절 3 |

절 점검: "판단이 갈릴 때의 기본값" 절 = 없음. "중단 조건" 절 = 없음. "하지 말 것" 절 = 있음(7절).

---

## 지침서 03 전략 축 재설계 (N8)

실행 여부: T1~T10 전부 미실행(`PlanInput`·`StrategyKind`·`BacktestKind`·`requireType` 클래스 0개, git log에 해당 커밋 없음). 삭제됨(15 크립토 삭제): 1절 `CryptoDcaRunner:58`, 2-4절 `CryptoDcaRunner:58` 필터, 7절 "코인 DCA 파이프라인 합치지 않는다". 본문에 갱신 주석 있음. 선행: S2(08) 구현 완료(roadmap S2)라 선행조건 충족.

| 태스크 | 유형 | 지침서 문장 | 확인 결과 | 기본값·대체 경로 |
|---|---|---|---|---|
| T3 | U2 | 1절 "PlanContext(balance, ticker, tradeDate, accountId, infinite, privacy, vr, dca)" | 일치(`CycleOrderStrategy.java:102`, 중첩 record). 지침서는 `CycleOrderStrategy.java`로만 적음 — 실제 `matching/domain/strategy/` | - |
| T3 | U2 | 1절 "OrderPlan(position, vrPosition, orders, skips, referencePrices, reverseMode)" | 일치(`:170`) | - |
| T3 | U2 | 1절 "CycleOrderComputer.compute()가 INFINITE·PRIVACY 묶음을 항상 만들고(:44-56), VR만 조건부" | 어긋남(사소). 줄 번호가 이동: 메서드 시작 `:56`~, INFINITE 조립 `:75`, PRIVACY `:84`, VR 조건부 `:94`. 내용(INFINITE·PRIVACY 무조건, VR만 `strategy.isVr()` 조건)은 일치 | - |
| T4 | U2 | 2-2절 "compute()의 9인자(같은 타입 인접, nullable 3개)" | 일치. 오버로드 2개(8인자, 9인자 `dcaInputs` 포함), `BigDecimal prevClosePrice`·`currentPrice` 인접 | - |
| T2 | U2 | 1절 고정값 플래그 7개 `requiresPrevClose`·`endsCycleOnLiquidation`·`tracksReverseMode`·`requiresRolloverCheck`·`capsIndividualOrders`·`fixesPricesAtPlan`·`recordsRounds` | 일치(`CycleOrderStrategy.java:33,52,55,58,80,87,91` default 7개, `:29` 주석 "SSOT는 StrategyType.capability()"). `StrategyCapability`는 `shared.kernel`(`shared/.../kernel/StrategyCapability.java`)에 있고 현재 필드는 `tickerFixed`·`requiresPrivacyBase`·`supportsReverseMode`·`supportsCycleSeed` 등 — 7개 플래그는 없음. 호출부 확인: `StrategyOrderPlanBuilder:68,83`, `TradingCandidatePlanner:154,197`, `TradingOrderExecutor:179`, `CyclePositionPersistor:54` 등이 `cycleOrderStrategies.of(type).플래그()`를 읽음 | T2 문장대로(없음) |
| T2 | U7 | T2 "7개를 `StrategyCapability`로" | 걸림 주의. `StrategyCapability`는 `:shared`라 `kernel_must_not_depend_on_other_modules`(`HexagonalArchitectureTest:70`)·`GradleModuleBoundaryTest`가 `:shared→trading-core` 의존을 막음 → 플래그 값은 순수 boolean이어야 함(문제없음). 단 `StrategyType.capability()`에 값을 넣는 곳(`StrategyType` enum)도 같이 고쳐야 함 | 없음 |
| T3 | U7 | 2-1절 `PlanInput`·`PlanState`를 matching에 | 안 걸림. `matching_must_not_depend_on_other_modules`(`:198`)는 matching→`shared.kernel` 외 의존 금지. `PlanInput`의 `DcaInput`이 trading 타입(`DcaRuleDecision`·`DcaCycleOrderStrategy.DcaRuleInputs`)을 쓰면 걸리는지는 현재 `DcaCycleOrderStrategy`가 어디 있는지에 달림(미확인) | 없음 |
| T4~T5 | U7 | 2-3절 `trading/application/service/kind/**` 신설 | 걸림(예외 목록 FQCN). `HexagonalArchitectureTest:296-311` `accountAllowed` Set에 `trading.application.service.support.SelectionChain`·`StrategyService`·`StrategyUpdateWriter`·`VrReconfigureService` 등이 FQCN으로 박혀 있고 `:316` `assertThat(existing).containsAll(accountAllowed)`가 개명·이동 시 실패시킴. T9의 `VrReconfigureService`→`kind/vr/` 이동이 직접 걸림. `PrivacyBaseGuard`·`DcaConfigService`도 이동 대상(`DcaConfigService`는 `:308` 목록에 있음) | T9에 "예외 목록 갱신" 문장 없음 |
| T9 | U7 | 2-3절 이동 + 7절 | 걸림. `flows.yml` `code:`는 단순 클래스명(`ArchitectureMapExporter.codeError`: 단순 이름(유일) 또는 FQCN)이라 패키지 이동은 안 걸리고, 개명·삭제만 걸림. `PrivacyBaseGuard`(flows.yml 4곳), `StrategyCreationPersister`(1곳), `CycleOrderStrategy`·`OrderPlan`(3곳 — 정확히 매칭된 문자열은 `DcaCycleOrderStrategy`) | T10에 flows.yml만 언급 |
| T9 | U7 | 2-4절 가드 `strategy_type_branching_only_in_kind_packages` | 신설. 현재 `StrategyType` 개별 상수·`isX()` 분기 합계 main 38곳(`grep -rnE '\.is(Infinite|Vr|Privacy|Dca)\(\)|case (INFINITE|VR|PRIVACY|DCA)'`), 지침서 "약 63곳"(상수 참조 포함 추정)과 정의가 달라 직접 비교 불가 | 허용 목록 약 15곳(4절) |
| T5 | U2 | 1절 "`StrategyCreationPersister`(switch 2개 7케이스)" | 일치(7건 매칭 `grep -c`) | - |
| T6 | U2 | 1절 "StrategyDetailAssembler(12, 단건·배치 두 벌)" | 어긋남(지금 10건 매칭, 파일 189줄). 두 벌 존재 여부 자체는 미확인 | - |
| T7 | U2 | 1절 "BacktestEngine(582줄, 13곳)·BacktestService(7)" | 일치(582줄, 13건, 7건). `BacktestEngine:63-69` switch에 `case VR/INFINITE/PRIVACY, DCA`, DCA는 예외(2-5절 "DCA 미지원 `:67`"은 `:68-69`로 이동) | - |
| T7 | U2 | 5절 "BacktestEngineTest(1,162줄)와 골든 파일" | 일치(1163줄). 골든은 `trading-core/src/test/resources/backtest-golden/{infinite-reverse,infinite,privacy,...}.txt` | - |
| T3 | U2 | 5절 "CycleOrderComputerTest(395줄)" | 일치 | - |
| T8 | U2 | 6절 "`StrategyUpdateWriter:58,61`, `CapitalLedgerService:78,183`, `StrategyService:177,237`" | 어긋남(사소). 현재 `StrategyUpdateWriter:60,63`, `CapitalLedgerService:79,184`, `StrategyService:177,237`. 내용(isVr/isDca 분기)은 일치 | 분기별 판정표(T8) |
| T9 | U2 | 2-4절 "`VrReconfigureService:68`, `DcaConfigService:40`, `ManualTradingService:62`" | 어긋남(사소). 현재 `VrReconfigureService:69`, `DcaConfigService:40`, `ManualTradingService:57`(DCA 가드, 메시지는 예외 클래스) | "메시지 문구 유지" |
| T6 | U2 | 2-2절 "`TradingCycleResponse.java:125`의 분기" | 어긋남(`TradingCycleResponse:127`의 `isPrivacy()`/`isDca()`는 등록 응답 안내 플래그 계산이지 `KindDetail` 분기가 아님. 상세 조립 분기 위치는 미확인) | - |
| T7 | U2 | 2-5절 "FillSimulator ↔ MockBrokerAdapter:127,157" | 어긋남(사소: 현재 `MockBrokerAdapter:125`대 `getExecutions` 주석, 재구현 `fills()`는 `:157`). 일치 | 2-5절 허용 안 되면 두 벌 유지 |
| T7 | U7 | 2-5절 "broker가 matching에 의존할 수 있는지는 05 단계 R `allowedDependencies`에서 확인" | 걸림. `MockBrokerAdapter` 주석이 "broker는 trading 타입을 참조할 수 없어 재구현"(`:~155`)이라 명시. `broker` 모듈 `package-info`에는 `allowedDependencies` 없음(grep 결과 `@ApplicationModule(id="broker")`만) → Modulith 기본(허용 선언 없으면 모든 의존 허용)이나 `ModulithArchitectureTest`의 순환 검사가 `matching`이 `broker`를 안 보는 한 통과. `FillSimulator`는 `stats/domain/backtest` 소속이라 kernel(matching)로 내리면 `matching_must_not_depend_on_other_modules`(`:198`) 안 걸림, broker→matching 의존은 새 의존이라 `ModulithArchitectureTest`(trading-core) 확인 필요(미확인) | 2-5절 대체 경로 있음(두 벌 유지 + 표 기반 테스트) |
| T3·T4 | U3 | 2-1절 "`needsCapCheck`를 `state instanceof PlanState.None`이면 건너뜀. 구현체가 추가 조건을 갖고 있으면 `capBuyOrders` 안으로" | 기본값 있음(조건부 문장) | 2-1절 |
| T3 | U3 | 4절 위험 2 "OrderPlan nullable 의미가 호출부마다 다를 수 있다" | 탐색 grep 명령은 있으나 판정 기준 없음 | 4절 명령만 |
| T3·T4·T8 | U4 | 4절 "동작 변경은 없다" + 위험 3 "afterPositionSaved 트랜잭션 순서" | 2차 효과는 "호출 위치와 트랜잭션 그대로" 한 문장. 진행 중 사이클·다음 날 효과 문장 없음(동작 불변 선언) | 4절 위험 3 |
| 전체 | U5 | (배포 문장 없음) | T3·T4는 주문 계산 경로. 배포 시점·승인·확인 SQL 없음 | 없음 |
| T3 | U6 | 4절 위험 1 "스냅샷 테스트로 막는다" | 스냅샷 불일치 시 대체 경로 없음 | 없음 |
| T8 | U6 | 7절 "T8에서 실제 분기가 요구할 때만 늘린다" | 판정표 보고만, 멈춘 뒤 경로 없음 | T8 |
| T7 | U6 | 5절 2 "골든은 바이트 단위로 같아야 한다" | 불일치 시 대체 경로 없음 | 없음 |

절 점검: "판단이 갈릴 때의 기본값" 절 = 없음(7절 "default 빈 구현"·2-5절 대체 경로가 흩어져 있음). "중단 조건" 절 = 없음. "하지 말 것" 절 = 있음(7절).

---

## 지침서 04 브로커 축 (S1 완료분 + W1·N3)

실행됨: 단계 C의 B1(`0c1edf76d`)·B2(`3a08223a1`)·B6(`89e047265`)·B5(`b9a4763f1`)·B4(`5016551a0`) 완료. B3 철회, B7은 D-2로 이동. 삭제됨(14·15): 업비트 삭제(단계 A의 업비트 항목)·바이낸스 관련 B4·B8·단계 D 3의 바이낸스·2-6 코인 포트·단계 E의 2-6·4절 전체·단계 F·2-2의 시세 포트 분리(14 T1·T3으로 이동). 남은 태스크: 단계 A(KIS만), B, C의 B8·B9(S1 범위 밖), D(1은 14로 이동 → 2·3·4 남음), E(2-5 facade + 3절 "줄일 수 있는 것").

| 태스크 | 유형 | 지침서 문장 | 확인 결과 | 기본값·대체 경로 |
|---|---|---|---|---|
| A-3 | U2 | A-3 "Flyway: KIS 토큰 테이블 drop"·2-1 "`adapter/out/persistence/KisToken*`" 삭제 | **어긋남(중요)**. `KisTokenEntity`는 `@Table(name="broker_tokens", schema="trading")`(`KisTokenEntity.java:16`)이고 `KisTokenPersistenceAdapter implements BrokerTokenCachePort`. 이 포트를 쓰는 `DbTokenCoordinator`(`broker/adapter/out/internal/`)를 `MeritzAuthApi:44`가 주입(`grep DbTokenCoordinator`). 즉 `broker_tokens` 테이블과 어댑터는 메리츠가 공유 → 통째 drop·삭제하면 메리츠 토큰 캐시가 죽음. 영향 단계: A-1 전수표 분류, A-3 마이그레이션. A-2에 "`DbTokenCoordinator`는 메리츠도 쓰므로 남긴다"만 있고 테이블·`KisToken*` 개명은 언급 없음. 토스는 `TossAuthApiNoJpaTest:43,83`에서 `broker_tokens`에 행이 안 남는 것을 검증 | 없음 |
| A | U2 | 2-1 "`KisAdminPriceSource`·`KisAdminTokenCoordinator`, `domain/model/kis`, `KisRateLimitException`, `Broker.KIS`" | 일치(kis 디렉토리에 `KisAdminPriceSource`·`KisAdminTokenCoordinator`·`KisAuthApi`·`KisBrokerAdapter`·`KisConfig`·`KisExchangeRegistry`·`KisHttpClient`·`KisOrderApi`·`KisPriceApi`·`KisResponseParser`·`KisTradingApi`). `Broker.KIS`는 `shared/.../kernel/Broker.java:12`. KIS 언급 파일 main 95·test 99(`grep -rlE 'Kis|KIS'`) | A-1 전수표 |
| B | U2 | 2-1·단계 B "`Broker.supportsOpenAuctionOrders()`(KIS만 true)와 그 분기" | 일치. 정의 `Broker.java:40`(`return this == KIS`), main 호출처 1곳 `TradingService.java:380`(DCA 필터), 테스트 `shared/.../MarketVocabularyTest:21`. 지침서의 "호출처를 전부 읽고"는 호출처가 1곳이라 범위가 작음 | 단계 B 본문 |
| A | U2 | 단계 A-2 "`TradingOrderExecutor`의 KIS 언급, `shared`의 `InternalApiClientConfig`·`UsTradeDates`의 KIS 참조, `MeritzAuthApi`의 `KisAuthApi` 언급이 주석인지 의존인지" | 확인: `TradingOrderExecutor:132,162`는 주석(`KisHttpClient`), `UsTradeDates.java:5,8`·`HttpClientTimeouts:24`는 주석, `MeritzAuthApi:43`은 주석("KisAuthApi와 동일"). `InternalApiClientConfig`는 grep 결과에 안 나옴(KIS 언급 없음 — 대상 없음). 모두 주석이라 코드 의존 아님 | A-2가 판정 위임 |
| A | U2 | 2-2 "`BenchmarkService.java:240` 환율 라벨 `TOSS_INVEST`" | 일치(`BenchmarkService:240`, 추가로 `BenchmarkComparisonResponse:84`의 `allowableValues = "TOSS_INVEST"`·`CurrentExchangeRatePort:5` 주석). 단 14 T4가 환율 공급자를 바꿈 | 14로 이동 |
| E | U2 | 3절 "호출처 0인 `BrokerPricePort.getPriceSnapshot(s)`·`PriceSnapshot`" | 어긋남(부분). `getPriceSnapshot(s)`를 부르는 곳은 어댑터 4개의 위임(`MeritzBrokerAdapter:76,81`·`TossBrokerAdapter:105,110`·`MockBrokerAdapter:69,74`·`KisBrokerAdapter:91,96`)과 `BrokerRouter:94,99`뿐이고 외부 소비자 main 0건은 맞음. 단 `PriceSnapshot`은 `MeritzPriceApi`·`MockBrokerAdapter`의 반환 타입으로 쓰임(`PriceSnapshot` 자체 삭제 시 영향). 테스트에 `verify(pricePort, never()).getPriceSnapshot…` 5곳(`TradingServiceTest:1599` 등) | 없음 |
| E | U2 | 3절 "`TradingReporter.java:58`, `CancelledOrderFillRecorder.java:38`의 `getExecutions` 종목마다 반복" | 일치(두 곳에 `executionPort.getExecutions(..., ticker, ...)` 호출 존재: `TradingReporter:58`, `CancelledOrderFillRecorder:38`) | - |
| E | U2 | 1절 facade "359줄" | 어긋남(사소). 현재 `*BrokerAdapter` 4개 합 574줄(Kis 118·Meritz 103·Mock 215·Toss 138); KIS·Mock 제외하면 241줄. 지침서 수치는 KIS·업비트 포함 당시 기준으로 보임 | - |
| D-2 | U2 | 2-4 "토스 Redis 분산 구조 약 690줄" | 미확인(합산 안 함). `TossRedisTokenStore` 229줄, `TossDistributedTokenCoordinator`·`TossTokenStore` 존재는 확인 | - |
| D-2 | U2 | 2-4 "메리츠 `expires_date` 의존, `expires_in` 폴백 없음" | 일치(`MeritzAuthApi:118` `text(json,"expires_date")`, `:55` 주석 "만료는 expires_date(최초 발급 기준 절대시각)"). `expires_in` 폴백 grep 0건 | 2-4 본문 |
| D-2 | U2 | 2-4 / B7 "토스 `verifyAccount`가 운영 토큰을 무효화(주석은 '건드리지 않는다')" | 어긋남. `TossAuthApi.verifyCredentials`(`:87-91`) 주석은 "토큰 캐시 저장 없음"이고 `verifyAccount`는 `:81`에서 시작. "건드리지 않는다" 문자열은 grep으로 안 나옴(주석 문구가 바뀌었거나 다른 곳) | D-2가 "틀린 주석을 고친다" |
| C | U2 | 3절 B1·B2·B5·B6 `file:line` | 대상 없음(이미 수정·커밋). B6 `TossOrderApi:85-87`에 PARTIAL_FILLED 주석 존재 | - |
| D-3 | U2 | 2-3 "메리츠 110ms, 토스 0" 호출 간격 | 미확인(값 grep 안 함) | - |
| A | U7 | 2-1 `KisTradingApi`·`KisPriceApi` 삭제 | 걸림(예외 목록 FQCN). `HexagonalArchitectureTest:122-123` `UsTradeDates` 허용 목록에 `...broker.adapter.out.kis.KisTradingApi`·`KisPriceApi` FQCN 박혀 있음(`:112` DisplayName은 "7개 KIS/Toss/Meritz/캘린더 어댑터"). 삭제 시 허용 목록과 DisplayName 같이 수정. 이 규칙이 존재 확인(`containsAll`) 방식이면 삭제만으로 실패(`:316`은 다른 규칙). 이 규칙에 존재 확인이 있는지는 미확인 | A에 없음 |
| A | U7 | 2-1 KIS 파서 | 걸림. `HexagonalArchitectureTest:442` "KIS 파서는 application 레이어를 의존하지 않는다" 규칙 — 대상(`KisResponseParser`)이 삭제되면 `failOnEmptyShould=true`(testing.md)로 규칙이 비어 실패 가능 → 규칙 삭제 필요 | A에 없음 |
| A | U7 | 2-1 `vendor_models_must_not_leak_outside_broker` | 걸림(주석·목록). `:236-243`은 `domain.model.kis/toss/upbit/meritz/binance` 패키지 목록 — `kis` 패키지 삭제로 `resideInAnyPackage` 항목이 빈 패키지가 되나 규칙 전체가 비는 건 아님 | - |
| A·E | U7 | 2-5 facade 제거 / A | 안 걸림 확인: `TradingCoreTestVendorModelTest`는 broker 밖 테스트의 벤더 모델 import 금지라 KIS 삭제 시 오히려 대상 감소. `.github/tests/*.bats`는 `KIS` 문자열이 이미지 변수명(`KISTA_...`)뿐이라 무관 | - |
| A | U7 | 2-1 `Broker.KIS` 삭제 | 걸림 가능. `Broker` enum은 `shared.kernel`, `/api/meta`(MetaController)로 노출, `ApiKeyExpirySource` 주석(`Broker.java:22`)·`AccountNumberMasker.java:3` 주석에 KIS 언급, `MarketVocabularyTest`(shared)에 enum 상수별 단언. 가드는 `MetaController` 스펙 테스트 | 6절 위험 3 |
| D-2 | U7 | 2-4 토큰 캐시를 client_id 키 메모리 캐시로 | 걸림 없음. 단 `TossAuthApiNoJpaTest`·`AccountTokenEvictionAdapter`가 계좌 UUID 키를 전제 → 같이 고침 | - |
| E | U7 | 2-5 "벤더 `Config`가 `BrokerCapabilities` record 묶음 등록" | 걸림 가능. `HexagonalArchitectureTest:236`·Modulith "port" NamedInterface가 포트 구현을 `adapter/out`에 두는 것을 전제, `BrokerRouter`가 `BrokerCapabilitiesPort` 목록을 주입받는 구조(`BrokerRouter:94`) → `BrokerRouter` 시그니처 불변이라 지침서는 "공개 메서드 그대로" | 2-5 |
| A | U3 | A-3 "소프트 삭제된 KIS·UPBIT 계좌 행의 처리(하드 삭제 또는 보존)는 Opus가 정한다" | 기본값 없음(판정을 위임) | 없음 |
| A | U4 | 6절 위험 1 "`accounts.broker` KIS 행이 남은 채 상수를 지우면 읽는 순간 예외, native 쿼리·관리자 조회는 소프트 삭제 필터 안 탐" | 있음: A-3 가드("live 계좌 있으면 실패")와 7절 4번 확인 SQL(`select broker, count(*) filter (where deleted_at is null) live ...`). 소프트 삭제 행 처리는 U3 | 7절 4, A-3 |
| A·D | U5 | 7절 선행조건 1·2 | 승인 주체: 사용자(KIS 전략 종료·계좌 삭제는 "사용자 작업", roadmap W1). 배포 시점·직전 확인은 7절 4번 SQL뿐, 배포 시각(매매 시간대 밖) 문장은 04에 없음(roadmap S1 "매매 시간대 밖 배포"만) | 7절 4 |
| D-2 | U5 | 2-4·D-2 토큰 단순화 | 운영 영향(재기동 시 토스 재발급 1회, 메리츠 `broker_tokens` 테이블 삭제) 배포 승인·시점 문장 없음. 메리츠 토큰 테이블 삭제는 expand/contract 위반 가능(constraints "드롭은 두 배포로 분리"는 root만, trading-core 제외라 해당 없음) | 없음 |
| B | U6 | 단계 B "없으면 추가" | 대체 경로 있음(테스트 추가) | 단계 B |
| D-3 | U6 | 단계 D 3 "골격이 벤더 합보다 작아지지 않으면 원복" | 원복이 대체 경로 | 2-3·D-3 |
| D-2 | U6 | 2-4 "지침서 05의 방향이 바뀌면 이 태스크를 재검토" | 재검토 후 경로 없음 | D-2 |
| D-2 | U6 | 2-4 "비파괴(실측)" 메리츠 재발급이 같은 토큰을 돌려준다 | 전제가 틀리면(재발급이 이전 토큰 무효화) 대체 경로 없음 | 없음 |

U1: 메리츠 스펙(`/Users/phs/Downloads/meritz_openapi_spec_20261112.json`)·토스 스펙은 단계 C(완료)에서 이미 대조됨. 남은 D-2의 "메리츠 재발급 같은 토큰(실측)"은 스펙에 없는 실측 주장 — 미확인(스펙 JSON 미조회). 토스 "재발급 시 이전 token 무효화"는 Q-02 근거에 스펙 인용 있음(일치).
절 점검: "판단이 갈릴 때의 기본값" 절 = 없음(Q-03이 이 부재로 발생). "중단 조건" 절 = 없음(각 항목에 흩어짐: 7절 4번 "live 0", D-3 "원복"). "하지 말 것" 절 = 있음(9절).

---

## 지침서 05 모듈·프로세스 통합 (N1·N2, W2)

실행됨(S5): P1 `228c0749a`·`0d23b822b`, P2(블랙리스트·fearGreed 개명) `f5e707675`, P7 `1260139b8`. P5는 M9로 이동(Q-06), P2의 ClockConfig는 M9로·Alpaca 3벌은 X·R로 이동(Q-07). 삭제됨(15): X6 목록의 `CryptoDcaRunner`. 남은 태스크: P3, P4, P6, M1~M14·4-3, X1~X9, R(①~⑦), G, 선택 단계 B·C. (실행 전제: roadmap N1은 W2(P3·P4)와 kista-infra 일정 합의가 선행.)

| 태스크 | 유형 | 지침서 문장 | 확인 결과 | 기본값·대체 경로 |
|---|---|---|---|---|
| P3 | U2 | 3절 P3 "nativeQuery(R 11곳, T 약 45곳)" | 어긋남. 지금 R 16곳, T 39곳(`grep -rn 'nativeQuery *= *true'` main). JdbcTemplate main 사용 파일 4개 | P3 본문 "모든 nativeQuery" |
| P3 | U2 | P3 "`scheduler_locks`와 `event_publication`이 public·trading 두 스키마에 같은 이름" | 일치(root `V1__init.sql:300,345`가 `public.event_publication`·`public.scheduler_locks`, trading은 4-3절 `trading.*`) | - |
| P4 | U2 | P4 "`@ConditionalOnProperty(prefix="scheduler")`를 전부 바꾼다" | 일치. root 스케줄러 8개(`KbLandHousing…`·`KbLandPriceIndex…`·`MarketIndexPriceSync…`·`UserEventResubmit…`·`RefreshTokenCleanup…`·`FinanceRegistrationReminder…`·`FearGreed…`·`AdminSchedulerController`) + trading-core(`MarketCalendarRefresh…`·`ApiKeyExpiry…` 등) 사용 확인. `@Scheduled` 파일 18개 | - |
| P4 | U2 | P4 "T의 `@Scheduled` 중 게이트 없는 것(Redis 스트림 복구 등)" | 일치. `@ConditionalOnProperty` 없는 `@Scheduled` 파일: `AppErrorStreamConsumer`·`PushNotificationStreamConsumer`·`RedisStreamSubscriber`(shared)·`UserEventStreamConsumer`·`TimeZones`(grep 오탐 가능성 — `@Scheduled` 문자열이 주석에 있을 수 있음, 미확인). `AppErrorStreamConsumer`·`PushNotificationStreamConsumer`는 root 소유라 "T의"는 아님 | P4 |
| P4 | U2 | P4 "`kista.role` 프로퍼티" | 대상 없음(아직 미도입 — `grep 'kista.role\|KISTA_ROLE'` main·yml 0건). 단 `deploy/server/roles`가 존재(디렉토리, 내용 비어 보임). `scheduler.enabled`/`SCHEDULER_ENABLED` 언급 파일: `README.md`·`deploy/server/docker-compose.yml`·`docs/agents/{local-dev,commands,docker-infra,scheduler-time-table,modules/tradingcore/{dca,account,trading}}.md`·`docs/architecture-map.md` — P4의 "commands.md만 고친다"보다 많음 | P4 "compose·.env.example·bats 갱신" |
| P4 | U7 | P4 "ArchUnit 가드 추가: `@Scheduled` 클래스는 `@ConditionalOnWorkerRole`" | 걸림(기존 가드와 겹침). `GradleModuleBoundaryTest:74 schedulerRoleOnlyPackageMustNotBeReferencedFromOutside`가 `..adapter.in.schedule..` 밖에서 이 패키지 참조 금지(`AdminSchedulerController` FQCN 예외), `SchedulerDisabledContextTest`가 컨텍스트 기준 게이트 누락 검출. 두 가드가 `scheduler.enabled` 프로퍼티를 전제 → 개명 시 같이 수정. 가드 `AdminSchedulerController` FQCN은 `com.kista.api.web`(R3 web 해체 시 같이) | P4에 없음 |
| P3 | U7 | P3 "native SQL 문자열 정규식 가드" | 걸림 없음(신설). `RepositoryLimitingQueryTest`와 같은 방식 | - |
| P6 | U2 | P6 "T `AppErrorStreamPublisher` + R `AppErrorStreamConsumer`" | 일치(경로: `trading/adapter/out/redis/AppErrorStreamPublisher`, `api/admin/adapter/in/redis/AppErrorStreamConsumer`) | - |
| M2 | U2 | M2 "`ApiApplication`에 `@SpringBootApplication(scanBasePackages…)`, `@EnableScheduling` 확인, `TradingApplication` 삭제" | 일치(현재 `@SpringBootApplication`·`@Modulithic(additionalPackages="com.kista.shared")`·`@EnableScheduling`; `TradingApplication`은 `@ConfigurationPropertiesScan`도 가짐) — M2는 `@ConfigurationPropertiesScan`을 명시하라고 함(일치). `@EntityScan`·`@EnableJpaRepositories` 현재 둘 다 없음(기본 스캔) | M2 |
| M3 | U2 | M3 "T의 `application{,-local,-prod}.yml`" 3개 + `spring.application.name=kista-api` 유지 | 일치(양쪽 3개씩, name `kista-api`·`kista-trading`). `management.metrics.tags.application: ${spring.application.name}`(`application.yml:71`)가 있어 Grafana 쿼리 의존 확인 | M3 |
| M5 | U2 | M5 "`SPRING_FLYWAY_BASELINE_ON_MIGRATE` env 계약, RUNBOOK 갱신" | 일치(`deploy/server/schema-reorg/RUNBOOK.md:45,89`). 단 09가 `schema-reorg` 삭제를 승인 대상(A~G)으로 둠 → RUNBOOK이 삭제되면 대상 없음 | M5 |
| M5 | U1 | M5 "Boot 4.1.1 소스 확인 — Flyway 빈을 직접 정의하면 자동구성이 꺼진다" | 외부 확인 필요: Spring Boot 4.x `FlywayAutoConfiguration`(`@ConditionalOnMissingBean(Flyway.class)`). 지침서는 이미 소스를 봤다고 적음. 레포 `build.gradle.kts`에 Boot 버전은 grep으로 못 찾음(plugin 블록 미확인) | M5 "빈 DB로 한 번 검증" |
| M8 | U2 | M8 "`ModulithArchitectureTest` 2개를 1개로(모듈 19개)" | 일치(root·trading-core 각 1개). 19 = api 7 + tc 9 + shared 3(architecture.md). `ArchitectureMapTest:40`가 `ApplicationModules.of(ApiApplication)`·`of(TradingApplication)` 둘 다 사용하고 `:44`가 `"api.notify","tradingcore.notify","shared.kernel"` 모듈명을 단언 → M8 개명(`trading-notify`·`trading-web`)·합병이 직접 걸림 | M8 "단계 R에서 최종 이름" |
| M8 | U7 | M8 모듈 ID 충돌 `notify`·`web` 개명 | 걸림(같이 고쳐야 함). `ArchitectureMapTest:40-44`(`ModuleGraphExporter.graph(두 모델)`·모듈명 단언), `ModuleGraphExporter`·`ArchitectureMapExporter`(flows.yml 모듈 소속), `docs/agents/modules/*` 파일명(`api/notify.md`·`tradingcore/notify.md`)·`architecture.md` 표. `HexagonalArchitectureTest`는 ID가 아니라 패키지 기준이라 안 걸림 | M8에 문서·ArchitectureMapTest 언급 없음 |
| M9 | U2 | M9 "T `ClockConfig`" 삭제 + 가드 예외 FQCN | 일치(`com.kista.api.ClockConfig`·`com.kista.tradingcore.ClockConfig`, `HexagonalArchitectureTest:179-180`) | M9에 명시됨 |
| M9 | U2 | M9 "`PlainDecimalSerializer` 중복 1벌" | 대상 없음(그 이름의 클래스 없음 — `find -name PlainDecimalSerializer.java` 0건. S3 `1915ba682`가 BigDecimal plain을 다른 방식으로 처리). 중복 여부 미확인 | 없음 |
| M9 | U2 | M9 "사용자 이벤트 스트림 경로 4종 + `UserEventResubmitScheduler` 한 벌" | 일치. R `UserEventStreamPublisher`, T `UserEventStreamConsumer`·`UserEventRepublisher`·`UserEventStreamBridge` 존재, `UserEventResubmitScheduler` 2벌(`api/web/`, `tradingcore/trading/adapter/in/schedule/`). 리스너 `TradingUserCascadeListener`·`UserNotifyProfileSyncListener`는 `trading/adapter/in/event/` | M9·P5 |
| M9 | U7 | M9 `UserEventResubmitScheduler` 합침 | 걸림 가능. `GradleModuleBoundaryTest:74`가 `AdminSchedulerController` FQCN만 예외로 두고 `UserEventResubmitScheduler`(api/web 패키지, `adapter.in.schedule` 아님)는 해당 규칙 대상 밖. `HexagonalArchitectureTest.web_must_stay_pure_inbound_sink`는 `api.web..`의 `application.service` 의존 금지 — 합친 스케줄러가 api/web에 남으면 서비스 의존 시 걸림 | 없음 |
| M12 | U2 | M12 "`TradingApplicationContextLoadTest`·`SchedulerLockService*Test`의 `TradingApplication` 참조, `RootDataJpaTestBase` 삭제" | 일치(`TradingApplicationContextLoadTest` 존재, `TradingApplication`을 참조하는 테스트 파일 6개, `RootDataJpaTestBase` 참조 테스트 20개). 20개 참조가 `DataJpaTestBase`로 바뀜 — M12가 "삭제 후 `DataJpaTestBase`로"라 적은 범위가 파일 20개 | M12 |
| M13 | U2 | M13 "스키마 단순명 충돌(`Market` 2개, `MarketSessionResponse` 2개, `InvestmentPoint` 2개)" | 일치. `Market`: `shared/kernel`·`api/finance/domain/model`, `MarketSessionResponse`: `shared/contract/marketcalendar`·`api/market/.../dto`, `InvestmentPoint`: `tradingcore/stats/domain/model`·`api/benchmark/domain/model`. 단 `OpenApiSchemaNameCollisionTest`가 이미 root·trading-core에 존재(각 프로세스 내부 충돌만 검출) | M13 |
| M10·G | U2 | M10/7절 "`@scheduler`·`@trading` matcher", `lb_try_duration` 지금 120초 | 일치(matcher `@scheduler`·`@trading` 존재 `kista-api.caddy:10,19`). `lb_try_duration`은 이 레포가 아니라 kista-infra `(upstream)` snippet이 소유(`kista-api.caddy:2-3` 주석) → 120초는 미확인(kista-infra 레포) | 7절 "5초" |
| M10 | U7 | M10 `RoleRoutes`/`CaddyRoutingTest` 확장 | 걸림. `CaddyRoutingTest`가 `@trading`·`@scheduler` regex를 로드(`:37-38`)하고 `SCHEDULER_PREFIX="/api/admin/scheduler/"`, `trading-core/build/classes/java/main` 경로를 스캔(`:42`) — M1에서 trading-core가 일반 jar로 바뀌어도 `build/classes` 경로는 유지, 단 `TradingApplication` 삭제 시 컨트롤러 스캔 대상이 변함. 7절이 `vars`·`handle_errors` 줄·주소 2개 사이트 줄을 파싱하는지 확인하라고 적음 | 7절 |
| X1 | U2 | X1 "내부 HTTP 어댑터 market(2)→benchmark(2)→admin(7)" | 일치(market 2: `CandleQuery…`·`MarketCalendarQuery…`, benchmark 2: `CurrentExchangeRate…`·`InvestmentPoints…`, admin 7: `AccountQuery…`·`PortfolioQuery…`·`PrivacyQuery…`·`TradingCommand…`·`TradingPolicy…`·`TradingQuery…`·`TradingSchedulerCommand…`). "내부 HTTP 31개 핸들러"(1-4절)는 미집계: `api/internal` 컨트롤러 파일 13개(`grep -rln 'api/internal' *Controller.java`), 핸들러 메서드 수는 미확인 | X1 |
| X1·X8 | U7 | X8 "`InternalApiContractTest` 삭제" | 걸림(예정). `InternalApiContractTest`는 contract 타입만 쓰는지(`:58`)·contract 의존(`:84`)을 검증 — X8에서 삭제. X1~X7 중간 단계에서 contract 타입을 지우면 이 규칙이 비어 `failOnEmptyShould`로 실패할 수 있음(M12는 "X까지 유지") | M12 |
| X5 | U2 | X5 "`FidaOrderRequest.orders` `@NotNull`" | 일치(이미 완료 — `820203d13`, `FidaOrderRequest.java`에 `@NotNull`) | - |
| X6 | U2 | X6 "`TradingUserProfilePort` 호출처(notifier 7종, `BatchContextFactory`, `MarketEventNotifier`, `StrategyCreationService`, `CryptoDcaRunner`, `UserTradingDataEraseService`)" | 어긋남(구성 다름). 현재 참조 파일: `CycleEndedNotifier`·`ApiKeyExpiryNotifier`·`CycleLifecycleNotifier`·`CryptoTradeNotifier`·`TradingReportNotifier`·`TradingAlertNotifier`(notifier 6종 + `TradingUserProfiles`), `BatchContextFactory`, `MarketEventNotifier`, `StrategyCreationService`, `UserTradingDataEraseService`, `CryptoDcaRunner`, 그리고 `UserNotifyProfilePersistenceAdapter`·`UserNotifyProfileSyncListener`·`TradingUserProfilePort` 자체. 코인 2개(`CryptoTradeNotifier`·`CryptoDcaRunner`)는 15로 삭제 | X6 |
| X6 | U2 | X6 "native SQL `StrategyJpaRepository.java:44-58`의 `JOIN user_notify_profile`" | 일치(`:44` 주석, `:48`·`:58` JOIN) | X6 기본값 있음("user API로 id 목록") |
| X7 | U2 | X7 "알림 채널 판정 2곳(`TradingUserNotificationAdapter.java:175`, `UserPushNotificationAdapter.java:32`)" | 일치(파일 존재: `tradingcore/notify/adapter/out/gateway/`, `api/notify/adapter/out/gateway/`). 줄 번호 내용은 `sed -n 175p` 빈 출력 → 줄 번호 어긋남 가능(파일 길이 미확인) | X7 |
| G | U2 | 7절 "`RefreshTokenCookieHelper.java:23-38`" | 어긋남(위치). 파일은 `api/user/adapter/in/web/security/RefreshTokenCookieHelper.java`(지침서는 경로 미기재). 줄 번호 내용은 미확인 | 7절 불변 조건 |
| G·4-3 | U2 | 4-3 "`build-bundle.sh:17`", kista-infra 지침서 03 5-1절 | 대상 없음(이 레포에 `build-bundle.sh` 없음 — kista-infra 레포). 미확인(타 레포) | 4-3 |
| R | U2 | 6절 "모듈별 `allowedDependencies`를 package-info에 선언" | 현재 선언 0건(`grep allowedDependencies --include=package-info.java` main 0). 신규 도입 | 6절 규칙 1 |
| R | U7 | R② kernel 해체 `Strategy*`→trading, `Broker·Market`→broker | 걸림. `HexagonalArchitectureTest:70 kernel_must_not_depend_on_other_modules`·`:99 platform_must_not_depend_on_other_modules`·`:136 zoneId_of_must_only_be_called_in_TimeZones`(`TimeZones` 위치 의존)·`main_code_must_not_read_system_clock_directly`의 `ClockConfig` FQCN 예외(`:179-180`)·`:296-311` Account 허용 목록의 FQCN(`...trading.application.service.*` 서비스 이동 시)·`ModulithArchitectureTest`·`GradleModuleBoundaryTest:55 sharedMustNotDependOnTradingCoreOrApiModules`(R⑦ 루트 패키지 `com.kista.<module>` rename 시 `subprojectClassesStayUnderOwnRootPackage:120`, `tradingCoreTestClassesStayUnderOwnRootPackage:130` 전부 무효). `TradingCoreTestVendorModelTest`는 broker 패키지 이동 시 같이 | 6절 순서 ①~⑦ 각각 별도 커밋(가드 수정 언급 없음) |
| R⑤ | U7 | 6절 "이벤트·리스너 이동(EPR 절차)" | 걸림. `EventPublicationRegistryTest`(root, `:38`)는 리스너 실패·재제출을 검증, FQCN 이동 영향 없음. 운영 `listener_id` 고아는 `deploy/hooks/pre-apply.sh`·`epr-orphans.sh`(docker-infra.md)가 잡음 — 이 레포에 `deploy/hooks` 존재(확인: `ls deploy` → hooks) | 6절 EPR 절차 |
| M1~M14 | U3 | M3 "값이 다른 키의 결정은 `findings/10` B-1 표 '합병 판단' 열" | 기본값은 외부 문서(findings/10)에 위임 | M3 |
| X6 | U3 | X6 "활성 사용자 수가 수천을 넘으면 재검토" | 기본값 있음("user API로 id 목록") | X6 |
| P4 | U4 | P4 함정 "env를 먼저 배포하고 코드를 배포" | 있음(순서 명시). 단 `scheduler.enabled` env 제거와 매매 스케줄러 중단 가능성 문장은 없음(`matchIfMissing=true`라 env 없으면 켜짐 → role 도입 후 role 누락은 기동 실패) | P4 |
| M1~M14 | U4 | M6 "`trading.scheduler_locks` 사용, 잡 이름이 겹치지 않는지 확인" | 있음. 진행 중 잔여 락 인수(`stop_grace_period`)는 4-3절 사전 확인에 있음 | M6·4-3 |
| 4-3 | U5 | 4-3 "kista-infra와 함께, 매매 시간대 밖(KST 07:00~16:00)", "자동 배포 금지" | 승인 주체: 문서에 "사람이 확인하며" — 구체적 사용자 승인 문장은 roadmap N1("kista-infra와 전환 일정 합의")뿐. 배포 직전 확인 SQL 있음(4-3 사전: `event_publication` 미완료·`scheduler_locks`) | 4-3 |
| P3·P4 | U5 | 단계 P "3 role 배포 후 2거래일 관찰" | 승인 주체 없음. 시점(매매 시간대 밖)은 단계 M만 명시 | 2절 |
| R⑤ | U5 | 6절 "배포 직전 운영에서 미완료 행이 0인지 확인" | 확인 방법 있음(`pre-apply.sh`·`epr-orphans.sh`·런북), 승인 주체 없음 | 6절 |
| M4~M8 | U6 | 10절 "단계 M에서 R↔T 순환 빈 참조가 구조적으로 풀리지 않는다 → 멈춘다" | 대체 경로 없음(1절 "이 절을 어기는 구현은 중단한다"도 대체 없음) | 10절 |
| 4-3 | U6 | 10절 "전환 중 옛 컨테이너 잔존·worker 2개 → 멈춘다" | 롤백 절차 있음(4-3 "롤백:" 단락) | 4-3 |
| M9 | U6 | 10절 "탈퇴 cascade가 EPR 행 없이 실행된다 → 멈춘다" | 대체 경로 없음. 단 P5가 M9로 이동해 이 조건이 M9 완료 판정과 직접 연결 | 10절 |
| 전체 | U6 | 10절 "전환 후 주문 수·금액이 전환 전 미리보기와 다르다" | 대체 경로 없음(롤백만) | 4-3 |
| 단계 M | U6 | 9절 "단계 M만은 쪼갤 수 없다" | 없음 | 9절 |

절 점검: "판단이 갈릴 때의 기본값" 절 = 있음(9절). "중단 조건" 절 = 있음(10절). "하지 말 것" 절 = 없음(1-3절 "빈을 role로 쪼개지 않는다", 4-2 "`@Primary`·`@Lazy`로 덮지 않는다", 9절 표에 흩어짐).

---

## 지침서 06 배치 오케스트레이션 (S10·N9)

실행 여부: T1~T8 전부 미실행(`BatchStep`·`Decision`·`TradingBatchScheduler` 없음, git log에 해당 커밋 없음; 조기 마감 커밋 `9e542baf7`이 선행으로 반영된 상태). 삭제됨(15): T6 코인, 2-6절, D2의 코인 틱 핑, 1-4절 코인 파이프라인, 3절 D2의 "코인 체크". 14 영향: `CLOSE_REPORT` 창 시작 `closeAt+20분`(14 D3 승인 시), `reloadPlacementPrices`는 14 T7이 삭제.

| 태스크 | 유형 | 지침서 문장 | 확인 결과 | 기본값·대체 경로 |
|---|---|---|---|---|
| T2·T4 | U2 | 머리말 "TradingCloseScheduler(239), TradingOpenScheduler(111), TradingBatchResumer(144), TradingService(485), DstInfo(161)" | 일치(`wc -l`: 239·111·144·485·161) | - |
| T1 | U2 | 1-1 "04:30이 5곳(DstInfo, KstTradeDates, TradingBatchResumer, TradingOpenScheduler 2회)" | 일치(대략). `LocalTime.of(4, 30)` 계열: `TradingBatchResumer:35`, `TradingOpenScheduler:67,101`, `DstInfo:23`(orderTime 줄 부근), `KstTradeDates:12`(`SCHEDULER_RUN_TIME`) 5건. 단 `DstInfo:23`은 "4:30"이 아니라 `orderTime(isDst)` 선언이라 같은 줄에 04:30이 있는지는 미확인 | - |
| T3 | U2 | 1-1 "22:00이 2곳" | 어긋남(3곳). `TradingOpenScheduler:32`(`CRON_TIME`)·`:46`(`cron = "0 0 22 …"`)·`DstInfo:121`(`OPEN_BATCH_FIRE_TIME` 22:00). 같은 파일 2줄 + DstInfo 1줄 | - |
| T1 | U2 | 1-1 "마감 30분 전이 3곳(`ORDER_MARGIN`, `DstInfo.forCloseSession`, `MarketSessionTimes.TRADE_DATE_SWITCH_MARGIN`)" | 일치(`TradingCloseScheduler:56 ORDER_MARGIN`, `MarketSessionTimes:14 TRADE_DATE_SWITCH_MARGIN`; `DstInfo.forCloseSession`은 존재 미grep) | - |
| T2 | U2 | 1-2 "`TradingService.executeBatch(contexts)`는 스케줄러가 더 이상 부르지 않는다. 남은 호출처는 `TradingExecutionFacade.executeBatch`뿐 — 실행 세션이 grep으로 확정" | 일치. `.executeBatch(` main 호출은 `TradingExecutionFacade:32,47`뿐이고 `TradingExecutionUseCase:15`는 선언만. facade·usecase를 통해 부르는 곳은 main에 0건 → 죽은 경로 맞음. 단 `TradingService:63,75`가 오버로드 2개 | T2 "죽은 경로 삭제" |
| T2 | U2 | 1-2 "`placedStates`·`reportAll`·`rebuildPlacedStates`·`reportRebuilt`" | 일치(`reportAll`/`reportRebuilt`/`rebuildPlacedStates`는 `TradingService`에만 존재 — `grep -l`: `TradingService.java`). 동등성 IT 위치: 기존 `*IT` 중 해당 없음(`CryptoLedgerRecorderIT`만), `*DbTest`는 `CyclePositionPersistorDbTest` 등 | 6절 2번 |
| T3 | U2 | 7절/위험 4 "KIS DCA 개장 전 접수(`placePreOpenDcaOrders`)" | 일치(`TradingService:350,378,380`, `Broker.supportsOpenAuctionOrders()`). 04 단계 B가 선행 | 위험 4 |
| T7 | U2 | 2-1 "체크포인트 `STARTED→PLANNED→PLACING→PLACED→DONE`, 컬럼 `VARCHAR`" | 일치(`TradingBatchPhase` enum 5값 주석 동일). `VARCHAR` 여부는 미확인(Flyway 미조회) | 2-1 |
| T4 | U2 | 2-3 "`TradingBatchResumer`의 `@Profile("!local")`·`@Order(LOWEST_PRECEDENCE)`" | 일치(`:31` `@Profile("!local")`). `@Order`는 미grep | 2-3 |
| T4 | U2 | 2-3 "락 TTL 2~3시간 → 15분" | 어긋남 가능. `TradingOpenScheduler:30`·`TradingCloseScheduler:55`에 `LOCK_TTL` 존재, 값은 미확인(잘림) | 2-3 "15분에서 시작" |
| T7 | U2 | 2-5 "핑이 네 곳: 단계 완료, 휴장일, 04:30 대체 핑, 리포트 재개" | 일치(`TradingCloseScheduler:77,85` `noBatch`·`pingHeartbeatSlot`, `TradingOpenScheduler` ping, `HeartbeatAdapter.pingOpen/pingClose`). `pingCrypto`는 `CryptoDcaScheduler`(삭제 대상) | D2 |
| T5 | U2 | 2-4 "`KstTradeDates`·`DstInfo.nextTradeDate` 삭제" | 일치(존재, 사용처 main: `MarketSessionTimes`·`DstInfo`·`KstTradeDates`). `KstTradeDatesTest`(28줄)·`DstInfoTest`(290줄)·`DstInfoForOpenBatchTest`(34줄) 같이 삭제·이전 | - |
| T5 | U2 | 2-4 "`TossPriceApi:161-199`의 세션 판정 복제" | 어긋남(사소: 지금 `TossPriceApi:154-171`에 `resolveMarketSessionAt`·DST 시각표 `:170-171`) | 04·05가 처리 |
| T8 | U2 | 7절 T8 "`workflow-batch.md`·`scheduler-time-table.md` 각 30줄 이하" | 어긋남. 현재 `workflow-batch.md` 34줄, `scheduler-time-table.md` 71줄 | - |
| T4 | U2 | 1-3 "스케줄러 세 클래스 합 494줄, 의존 12·11·6" | 일치(239+111+144=494). 의존 개수는 미확인 | - |
| T2 | U2 | 5절 "TradingServiceTest(2,418줄)" | 어긋남(사소). 지금 2,421줄 | - |
| T1 | U7 | T1 판정표 신설 | 안 걸림. 단 `DstInfo`가 `trading/domain/model`에 있어 `domain_must_not_depend_on_outer_layers`(`HexagonalArchitectureTest:331`) 대상이고 판정표를 `marketcalendar`에 두면 `marketcalendar` 모듈 NamedInterface("domain"/"port") 공개 필요 | 10절 "R 전이면 marketcalendar에" |
| T3 | U7 | T3 cron 22:00 제거 | 걸림(가드 값 박혀 있음). `ArchitectureMapTest:31-36`이 `TradingOpenScheduler#run`의 `j.process()=="kista-trading"`·`j.times()==["22:00"]`을 단언 → cron을 없애면 `jobs()`에서 사라져 `singleElement()` 실패. `ArchitectureMapExporter.toJob`이 `@Scheduled`로 job을 뽑음(`:248-251`). 테스트 같이 수정 필요. `scheduler-time-table.md`는 마크다운이라 가드 없음 | T3·T8에 ArchitectureMapTest 언급 없음 |
| T3·T4 | U7 | flows.yml `code:` 참조 | 걸림(삭제·개명 시). `flows.yml`의 `code:`에 `TradingOpenScheduler#run`(`:81,280`)·`TradingCloseScheduler#schedule`·`#pingHeartbeatSlot`(`:265`)·`#runNow`(`:1476`)·`TradingBatchResumer#recoverClose`·`#resumeOpen`(`:1777,1779`)·`DstInfo#waitUntilOrderTime/#waitUntilPostClose/#waitUntilMarketOpen/#openBatchMissedAt`(`:161,181,345,553`)·`TradingService#waitFor` 등 존재. `ArchitectureMapExporter.codeError`가 클래스#메서드 존재를 검증해 삭제·개명 시 `ArchitectureMapTest` 실패. T8이 "flows.yml 개장·마감 흐름과 lifecycles"를 같은 작업에서 고친다고 적음(커밋이 T2~T5와 분리되면 중간 커밋이 깨짐) | T8(별도 커밋이라 순서 위반) |
| T4 | U7 | T4 스케줄러 3→1 | 걸림. `GradleModuleBoundaryTest:74 schedulerRoleOnlyPackageMustNotBeReferencedFromOutside`(`..adapter.in.schedule..` 밖 참조 금지)와 `SchedulerDisabledContextTest`(게이트 누락)가 스케줄러 이름·패키지 불변 전제. `TradingSchedulerInternalController`(`flows.yml:1476`)가 `TradingOpenScheduler#runNow`·`TradingCloseScheduler#runNow`를 부름 → 통합 시 컨트롤러·`adapter.in.schedule` 참조 규칙 영향 | T4 "관리자 수동 트리거 runNow" |
| T5 | U7 | T5 `DstInfo`·`KstTradeDates` 삭제 | 걸림 없음(가드 grep: `HexagonalArchitectureTest`·architecture 디렉토리에 `DstInfo`·`KstTradeDates` 0건). `MarketVocabularyTest`·`KstTradeDatesTest`는 삭제 대상 | - |
| T5 | U7 | T5 "`TradingCycleResponse`의 `openBatchMissedAt` 기준 변경" | 걸림 없음(UI 계약 영향은 U4) | D1 |
| T2 | U3 | 위험 1 "재조립 경로는 screen 미적용, 로테이션 최소금액 알림이 다르다(`TradingService` 주석)" | 기본값은 "정상 경로에만 있던 동작은 재조립 경로로 옮긴 뒤 교체"로 있음. 실제 주석 위치 미확인 | T2·위험 1 |
| T3 | U3 | 3절 D1 "개장 대상 확정 시각 `openAt`으로" | 기본값 있음("openAt으로, 결정 전엔 22:00 스냅샷 없이 구현하되 배포 전 사용자 확인") | 3절 |
| T2~T5 | U4 | 2-1 "`STARTED`·`PLANNED`는 한 배포 동안 옛 값을 읽을 수 있게 둔다", 위험 2·3 | 있음(체크포인트 값 호환, 단계 경계 메모리 상태). 배포 중 진행 중인 배치(접수 직후 리포트 대기 중)는 `CLOSE_REPORT`가 DB에서 재조립하므로 영향 없다는 점은 2-1·위험 1에서 간접 서술. 다음 날 효과: D1(22:00 이후 등록 전략이 `openAt`에 포함)·D3(수동 실행 차단 구간 변경)가 사용자 동작 변경 — 지침서는 "사용자에게 유리/지금이 틀림"으로만 서술 | 3절 D1·D3 |
| T2~T5 | U5 | 머리말·7절 "단계마다 운영 배포 후 2거래일 관찰", "매매 시간대 밖(KST 07:00~16:00)", T4 "의도적 재기동 실험 2회" | 시점은 명시. 승인 주체 없음(T3 "배포 전에 사용자 확인"만 D1에). 배포 직전 확인 SQL 없음(관찰 항목은 있음: "리포트 시각·내용·알림을 전날과 대조") | 7절 표 |
| T4 | U5 | T4 "의도적 재기동 1회" | 실행 주체·승인 문장 없음. 미국장 정규 시간 배포 금지(8절)와 접수 후~리포트 전 재기동(미국장 중)이 충돌 가능 — 접수 후 마감 전은 정규장 시간대라 8절 "22:30~06:00 배포 금지"와 직접 충돌 | 없음 |
| T2·T4 | U6 | 9절 "리포트 동등성 IT에서 원장 금액이 다르고 원인을 설명할 수 없으면 멈춘다", "관찰에서 패턴이 다르면", "재개 경고 알림이 실험 밖에서 나면" | 멈춘 뒤 대체 경로 없음(롤백 절차·이전 배포로 되돌리기 문장 없음) | 9절 |
| T4 | U6 | 2-3 "TTL은 운영 로그를 보고 정한다" | 로그가 없을 때의 기본값은 "15분에서 시작" | 2-3 |
| T7 | U6 | D2 "healthchecks.io 일정 변경(사용자 작업)", "바꾸기 전에는 지금의 핑 위치를 유지" | 대체 경로 있음 | 2-5 |

절 점검: "판단이 갈릴 때의 기본값" 절 = 없음(3절 D1~D3 권고·2-3 "15분에서 시작"이 분산). "중단 조건" 절 = 있음(9절). "하지 말 것" 절 = 있음(8절).

---

## 지침서 07 테스트 전략 (S9·N10)

실행 여부: T1~T8 전부 미실행(생성자 가드 없음, `asUser` 헬퍼 없음, git log에 fixture 치환 커밋 없음). 부분 선행 달성: 05 P1의 `ExceptionResponseTableTest`(root·trading-core, `228c0749a`)가 T3의 "`MAPPINGS` 표 기반 테스트"를 이미 만들어 둠. 삭제됨(15): 직접 대응하는 태스크 없음(7절 어디에도 코인 언급 없음 — 단 `CryptoDcaRunnerTest`·`CryptoLedgerRecorderIT` 등은 15가 삭제). 14·15 영향: 지침서가 든 테스트 규모 수치가 코인 삭제 후 달라짐.

| 태스크 | 유형 | 지침서 문장 | 확인 결과 | 기본값·대체 경로 |
|---|---|---|---|---|
| 전체 | U2 | 0절 "테스트 480파일 76,693줄, @Test 3,728개" | 어긋남(사소). 지금 480파일 77,704줄, `@Test` 3,756개(`find … -name '*.java' | wc` / `grep -rho '@Test' | wc`, src/test + trading-core + shared/src/test) | T6 측정 명령으로 전후 비교 |
| T1 | U2 | 1절 "`new Strategy(` 207회, `new Account(` 63회" | 일치(207·63). `new Order(` 151회(지침서 미기재) | T1 |
| T1 | U2 | 2-3 "`TradingFixtures`·`DomainFixtures`의 빌더" | 일치(존재: `trading-core/src/testFixtures/.../TradingFixtures.java`, `src/testFixtures/.../DomainFixtures.java`). "빌더"인지(with* 존재)는 미확인 | 2-3 |
| T2 | U2 | 1절 "`StrategyServiceTest`(3,098줄, 150개), `register_*` 약 80개, `when(` 425회" | 거의 일치(3,098줄·`@Test` 149·`void register_` 73·`when(` 425) | - |
| T2 | U2 | 1절 "`domain/creation`의 테스트는 12개뿐" | 일치(해석). 파일 2개(`VrRampInputTest` 7 + `VrRegistrationTest` 5 `@Test`)=12개 테스트 | - |
| T2 | U2 | T2 "`StrategyKind`·resolver·`VrRampValidator`" | 부분. `VrRampValidator`는 존재(`trading/domain/creation/VrRampValidator.java`), `StrategyKind`는 03 T4에서 생성될 클래스로 아직 없음(`grep StrategyKind` 0건) — 선행 03 T5 | T2 선행 명시 |
| T7 | U2 | 1절 7 "`TradingServiceTest`(2,418줄, `when(` 407, `verify` 222 대 `assertThat` 6)" | 어긋남(사소). 지금 2,421줄·`when(` 408·`verify(` 219·`assertThat` 2. 수치 기준(매칭 방식)이 달라 "전후 `grep -c` 같음"(T7)의 기준선은 지금 값으로 다시 재야 함 | T7 |
| T3 | U2 | 1절 3 "WebMvc 슬라이스 62%(241개, 2,349줄)가 본문을 보지 않는다, 147개가 401/403/400/404만" | 미확인(집계 재현 안 함). `@WebMvcTest` 파일 60개, `*ControllerTest` 내 `isUnauthorized|isForbidden` 66건 | - |
| T3 | U2 | 2-4 "`RootSecurityPolicyTest` 계열" | 일치(`src/test/java/com/kista/api/web/RootSecurityPolicyTest.java`, `void` 14개). trading-core 쪽 정책 테스트(`TradingSecurityPolicy` 대응)는 `find`에 나오지 않음 — 미확인 | 2-4 "05 뒤에는 하나" |
| T3 | U2 | 5·T3 "`MAPPINGS` 표 기반 테스트" | 일치·이미 존재(`ExceptionResponseTableTest` root `src/test/java/com/kista/api/web/`, trading-core `tradingcore/web/`) — T3의 해당 서브 항목은 대부분 완료 | T3 선행 P1 완료 |
| T3·T7 | U2 | 2-3 / T3 `asUser(uuid)` 헬퍼를 `WebMvcTestSupport`에 | 대상 없음(신설). 현재 `WebMvcTestSupport`에는 `userToken(UUID)`·`userTokenWithRole(UUID)`(`:19,24`)와 `DEV_USER_UUID` 상수 존재 — 비슷한 헬퍼가 이미 있음 | 없음 |
| T4 | U2 | 1절 4 "Finance 서비스 5개에 `SecurityException` 단언 74회" | 어긋남(사소). `src/test/java/com/kista/api/finance`의 `SecurityException` 매칭 77건 | - |
| T5 | U2 | 1절 5 "DataJpa 44파일 6,185줄" | 어긋남(사소). `extends .*DataJpaTestBase` 46파일(줄 수 미집계) | T5 메서드 단위 판정표 |
| T1 | U2 | 1절 2 "이름 같은 private 헬퍼(`strategy` 11곳, `account` 10곳, `order` 9곳)" | 미확인(grep 안 함) | - |
| T6 | U2 | T6 "위임 클래스는 01 단계 D가 지움" | 01 단계 D 미실행이라 선행 미충족 | T6 선행 명시 |
| T8 | U2 | T8 "`testing.md`의 'interface default 메서드 주의'를 다형성 포트 11개 범위로 줄여 적는다" | 일치(`testing.md:21` 절 존재). 01 단계 C가 `OrderPort` default 불필요화 | - |
| T1 | U7 | 2-3 "fixture 패키지 밖 테스트가 생성자 직접 호출 금지 — `TradingCoreTestVendorModelTest`와 같은 방식" | 걸림 없음·방식 확인. `TradingCoreTestVendorModelTest`는 `ImportOption.Predefined.ONLY_INCLUDE_TESTS`로 `importPackages("com.kista")`(`:22-23`). 신규 가드의 대상 클래스 조건이 Strategy·Account·Order 생성자 호출(`callConstructor`)이라 현재 위반(207+63+151건)이 많아 T1 완료 전까지 가드는 켤 수 없음(순서: 치환 → 가드). testFixtures 소스는 test import에 포함되지 않아 별도 예외가 필요한지는 미확인. `failOnEmptyShould=true`(`src/test/resources/archunit.properties:1`)는 규칙 대상(`..callConstructor`)이 비면 실패하므로 치환 후에도 "대상 클래스" 쪽이 비지 않는지 확인 필요 | 2-3 "대상 클래스의 테스트 자신 예외" |
| T1 | U7 | T1 `new Strategy(` 치환 | 걸림 가능. `HexagonalArchitectureTest.entity_ids…`·`TradingCoreTestVendorModelTest`와 무관. `TradingFixtures`는 `:trading-core`의 testFixtures라 루트 테스트가 쓸 수 없음(testing.md "루트 테스트는 trading-core testFixtures에 의존하지 않는다") — 루트 쪽 `new Strategy(`가 있는지 확인 필요(`Strategy`는 trading-core 타입이라 루트 main에 없음, 루트 테스트에 있을 가능성은 낮음, 미확인) | 없음 |
| T3 | U7 | T3 401/403 슬라이스 삭제 | 걸림 없음(가드 없음). 단 testing.md "hasRole 인가 검증이 필요한 @WebMvcTest는 `@Import({SecurityConfig.class, JwtAuthFilter.class})` 필수" 규칙이 이 삭제 대상과 겹치는지(`AdminPingControllerTest` 참고) 확인 필요 | 없음 |
| T8 | U7 | 문서 `testing.md` 개정 | 걸림 없음(09 예산 가드가 `testing.md`는 항상 로드 문서 크기 상한 대상일 수 있음 — 09 T7의 크기 예산 가드가 실행되면 줄 수 제한) | T8 "09의 문서 예산 안에서" |
| T2 | U3 | 2-2 "옮길 곳이 없으면 main을 먼저 고친다" | 기본값 있음(별도 커밋). 어떤 규칙이 "인라인"인지 판정 기준은 없음 | 2-2 |
| T5 | U3 | 위험 3 "왕복 테스트가 직접 쓴 쿼리를 지나고 있을 수 있다. 메서드 단위로 판정" | 판정 기준 있음(2-5 "직접 쓴 쿼리마다 1건 이상") | 2-5 |
| T7 | U4 | 7절 "`verify`를 지우지 않는다" | 해당 아님(테스트 변경이라 데이터·운영 2차 효과 없음) | - |
| 전체 | U5 | (배포 문장 없음) | 테스트 전용이라 배포 승인 불필요. 단 T2·T3의 main 선행 수정(규칙 추출)은 03·05가 배포 단위 | - |
| T3 | U6 | 4절 위험 2 "정책 테스트에 구멍이 있으면 못 잡는다 — T3에서 정책 테스트를 먼저 보강하고 그다음 지운다" | 순서 규정은 있음. 보강이 불가능한 경우(예: 라우트 규칙이 코드에서 표로 추출되지 않음)의 대체 경로 없음 | 없음 |
| T2 | U6 | 7절 "`@Disabled`로 남기지 않는다. 지우거나 고친다" | 현재 `@Disabled` 14건(`grep -rn Disabled`). 01 단계 A의 신설 규칙 `@Disabled` 사유 허용(01 A-1)과 이 7절이 충돌 — 01은 `@Disabled`와 사유를 달라고 하고 07은 금지 | 없음 |
| T2·T5 | U6 | 2-2 "변이 확인이 실패하지 않으면" | 대체 경로 없음(새 테스트가 규칙을 못 잡으면 서비스 테스트를 지우지 않는다는 암시만) | 2-2 순서 |

절 점검: "판단이 갈릴 때의 기본값" 절 = 없음. "중단 조건" 절 = 없음. "하지 말 것" 절 = 있음(7절).
