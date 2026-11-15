## 아키텍처

### Gradle 구조
서브프로젝트 셋: `:trading-core`(매매 실행 — trading/matching/broker/account/privacy/marketcalendar 등), `:shared`(kernel/contract/platform — outbound-zero 공용 어휘·wire 계약·인프라 leaf), 루트 `:api`(그 외, bootJar). 루트와 `:trading-core`는 `:shared`만 단방향 `implementation` 의존한다.
- 루트 main ↔ `:trading-core` main 사이 컴파일 의존은 양방향 모두 없다. 통신은 내부 HTTP·Redis이고 wire 타입은 `com.kista.shared.contract`가 선언한다. 루트 **테스트**만 `testImplementation(project(":trading-core"))`
- 서브프로젝트마다 자기 루트 패키지(`com.kista.api`·`com.kista.tradingcore`·`com.kista.shared`) 아래에만 클래스를 둔다. 앱 클래스(`ApiApplication`, `TradingApplication`)가 루트에 있어 기본 스캔이 자기 루트만 덮는다(가드: `GradleModuleBoundaryTest` — 역방향 의존 금지 포함. 컨텍스트 로드는 `ApplicationContextLoadTest`·`TradingApplicationContextLoadTest`)

Hexagonal Architecture(Port & Adapter) + Spring Modulith 애그리게이트 모듈. 레이어 방향은 `HexagonalArchitectureTest`, 모듈 경계는 각 프로세스의 `ModulithArchitectureTest`(`ApplicationModules.verify()`)가 빌드에서 강제한다. 클래스·필드 상세는 코드가 SSOT다.

### DB 스키마 5분리
소유 서비스별 5개 — root: `public`·`finance`·`kista_ref`, trading-core: `trading`·`trading_ref`. 신규 테이블은 소유 서비스 기준으로 Entity에 `@Table(schema=...)`를 명시한다(public도 — search_path 첫 스키마가 root는 `finance`, trading은 `trading`이라 생략하면 validate 실패). 다른 서비스 소유 스키마 테이블은 FK 포함 참조 금지. 소유 표·search_path → `flyway.md`.

### 모듈 한눈에 보기
모듈 상세는 각 모듈 디렉토리의 `CLAUDE.md`가 자동 로드한다. 여러 모듈을 함께 다룰 때는 `docs/agents/modules/<루트>/<ID>.md`를 직접 Read.

```
# api 프로세스 (kista-api·kista-scheduler) — api 7 + shared 3     NamedInterface
api.finance     가계부                                  domain/usecase/port
api.notify      Telegram/FCM 아웃바운드 게이트웨이       port/domain
api.market      공포탐욕지수                             domain/port/event
api.benchmark   주택/ETF 벤치마크 비교                   domain/usecase/port/event/schedule
api.admin       관리자 조회·정정·런타임 설정             domain/usecase/port
api.user        가입·승인·프로필·JWT 인증                domain/usecase/port/event
api.web         크로스모듈 fan-out 앱셸                  (없음)
shared.kernel   공유 enum·이벤트·순수 포트(OPEN)
shared.contract 프로세스 경계 wire record(OPEN)
shared.platform 공용 인프라 leaf(OPEN)

# trading-core 프로세스 (kista-trading) — trading-core 9 + shared 3
matching        주문생성 커널(순수 계산)                 kernel
broker          증권사 연동(메리츠·토스·KIS·Mock 등)   domain/port/application
trading         주문·사이클 실행·전략 설정               domain/usecase/port/schedule/event
stats           통계·백테스트·포트폴리오 조회            domain/usecase/port
notify          매매 알림 리스너·게이트웨이              (없음)
marketcalendar  미국 시장 휴장일                         domain/port
privacy         FIDA 기준 매매표 SSOT                    domain/port/api/event
account         계좌 자격증명·브로커 연결                domain/api/port/event
web             전역 web 앱셸                            (없음)
```
shared 외 모듈은 전부 CLOSED. 모듈 문서 경로는 `modules/api/<ID>.md`·`modules/shared/<ID>.md`·`modules/tradingcore/<ID>.md`.

### Spring Modulith 모듈 구성
모듈 모델은 프로세스별 둘이다(`ApplicationModules.of(ApiApplication.class)`·`of(TradingApplication.class)`). 두 앱 모두 `@Modulithic(additionalPackages = "com.kista.shared")`. 감지 전략은 `explicitly-annotated`라 **새 모듈은 `package-info`의 `@ApplicationModule(id = ...)`를 반드시 지정**한다. 두 프로세스에 같은 ID(`notify`, `web`)가 있어 문서에서는 `api.notify`/`tradingcore.notify`처럼 쓴다.

모듈 간 참조는 상대 모듈이 공개한 NamedInterface(도메인 타입 또는 own-type projection)만 거친다. 다른 프로세스 사이는 EPR이 전달되지 않으므로 내부 HTTP API 또는 Redis(Pub/Sub·Stream)를 쓴다.

### 웹 계층 공통
- 컨트롤러는 `@AuthenticationPrincipal UUID userId`로 주입한다. `SecurityContextHolder` 수동 호출 금지
- 소유권 검증 패턴 → `modules/tradingcore/trading.md` "소유권 검증", JPA Auditing → `modules/shared/platform.md` "JPA Auditing"
- 텔레그램은 사용자 봇만 쓰고 계좌별 설정이 없다. 발송 규칙 → `modules/tradingcore/notify.md`
