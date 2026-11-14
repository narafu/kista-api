## 핵심 제약 사항

`(가드: X)`는 빌드가 강제하는 테스트 이름이다(배경은 그 테스트 주석). 하지 않기로 한 것은 `decisions.md`.

### Git 규칙
- `git push`는 사용자가 명시적으로 요청할 때만, 요청하면 즉시
- 커밋 전 author 확인: `narafu <narafu@kakao.com>`
- 커밋 메시지는 한글, Conventional Commit 접두사(`feat(scope):`·`fix:`·`docs:`·`debug:` 등) + 명령형 제목
- 배포 불변식: kista-trading은 단일 인스턴스·stop-first·`stop_grace_period` ≥ 200s(재개가 잔여 락을 인수해 겹침 기동은 이중 실행). EPR 재발행 플래그는 kista-trading·kista-scheduler만 true(가드: `compose-invariants.bats`). 파이프라인 → `docker-infra.md`

### 코드 작성 기본
- deprecated API 금지. Spring 7/Jackson 3 대체: `HttpStatus.UNPROCESSABLE_CONTENT`, `JsonNode.asString()`, `org.jspecify.annotations.Nullable`(중첩은 `Outer.@Nullable Inner`)
- 클래스 식별 문자열은 `X.class.getSimpleName()`(리터럴·`getClass()` 금지)
- Virtual Thread: `@Async`·`CompletableFuture` 금지, 대기는 `Thread.sleep()`
- 시간: 거래일은 KST 단일 기준, 현재 시각은 `Clock` 주입, `ZoneId.of`는 `TimeZones`만, `UsTradeDates`는 허용 어댑터만(가드: `main_code_must_not_read_system_clock_directly` 외 2개) → `modules/shared/platform.md` "시간 기준 정책"
- 파라미터 8개 이상이거나 같은 타입이 인접해 자리 바뀜이 통과할 수 있으면 record로 묶는다(기존 record 재사용)
- 계좌번호 마스킹은 `AccountNumberMasker.mask()`만. 상태 종속 민감 필드는 응답 DTO `from()`에서 조건부 마스킹 → `modules/api/user.md`
- 수량 이름: 보유 `holdings`, 주문/체결 `quantity`, 복합 `orderedQuantity`/`filledQuantity`
- enum 메타는 `GET /api/meta` 단일 번들. UI에 enum 리터럴 하드코딩 금지

### 기본값·금액 자릿수
- 매퍼(`toEntity`/`toDomain`)와 web DTO `toCommand()`는 기본값을 정하지 않는다. 생략은 null로 넘기고 도메인 resolver·팩토리·서비스 한 곳이 정한다(`0` sentinel 금지). 예외: DB 컬럼 자체가 nullable인 레거시 값
- 금액 자릿수 리터럴 `2`·모듈별 `MONEY_SCALE` 금지. USD 가격·원장은 `Currency.USD.amountScale()`, 코인 DCA 계좌도 지나는 원장 경로는 `AccountBalance.LEDGER_SCALE`, 시장 통화가 정하는 값(주문 금액·DCA 표시)은 `Market`/`Currency`의 `amountScale()`
- 원장 금액이 응답으로 나갈 때는 조립 서비스에서 `Currency.round()`. 비율은 반올림 전 값으로 계산. 가격·수량·지표값은 대상 아님

### 신규 파일 배치
애그리게이트 모듈(`com.kista.<api|tradingcore|shared>.<module>`) 안에 `domain/model` + `application/{usecase,port/output,service}` + `adapter/{in,out}`. 공개 범위는 모듈 문서.
- 인바운드 포트 `application/usecase/`, 아웃바운드 포트 `application/port/output/`의 `*Port`(`*Repository` 금지), 파라미터·반환 타입과 `*Command`(`*Request` 금지)는 `domain/model/`(가드: `outbound_port_interfaces_must_have_Port_suffix` 등)
- broker 밖이 쓰는 기능은 `tradingcore.broker.application.port.output`의 `*Port`로(가드: `vendor_models_must_not_leak_outside_broker`)
- 크로스모듈 컨트롤러는 `api.web`, trading-core 전역 web은 `tradingcore.web`, 그 외 컨트롤러는 모듈 `adapter/in/web`
- 공용 인프라는 `shared.platform`, 주문생성 커널은 `tradingcore.matching`(가드: `platform_must_not_depend_on_other_modules`·`matching_must_not_depend_on_other_modules`)
- 같은 하위 패키지 이름을 모듈마다 다른 뜻으로 재사용하지 않는다
- `@Configuration`=`config`, 저장 트리거 리스너=`adapter/in/event`, `@Scheduled`=`adapter/in/schedule`(`application/service` 금지). 예외: `TradingStatsService` 캐시 무효화 `@EventListener`(EPR 행을 남기지 않으려고), 스트림 컨슈머 복구 `@Scheduled`는 `adapter/in/redis`
- `@Aspect` 금지(가드: `no_aspects_in_codebase`). `*JpaRepository`는 package-private(가드: `persistence_jpa_repositories_must_be_package_private`). UUID PK는 v7(가드: `entity_ids_must_use_uuid_v7_generator`). `@ManyToOne`엔 `@JoinColumn(name=..., nullable=false)`
- 모듈 간 참조가 얽히면 own-type 또는 이벤트(EPR). 프로세스 경계 wire 타입은 `shared.contract`(가드: `InternalApiContractTest`). 값 타입 복제는 (a) 순환 불가피 (b) 외부 계약 분리일 때만, (b)는 file:line 증거 필수 → 허용 원장 `decisions.md` 5절

### 예외 응답
- Controller catch/rethrow 금지. advice는 `ProblemDetailExceptionHandler` 하나, 매핑은 소유 모듈 `*ProblemDetails`가 기여(중복 기여는 기동 실패) → `modules/api/web.md`
- `detail`은 내부 정보 없는 한국어 문장, IAE/ISE 리터럴은 "~니다."로 끝(가드: `ExceptionMessageFormatTest`). `ErrorCode` 상수 이름 변경·삭제 금지(kista-ui 계약)

### 트랜잭션과 외부 호출
- `@Transactional` 안에서 외부 HTTP 금지 → `publishEvent` + `@TransactionalEventListener(AFTER_COMMIT)`(이벤트 `application/event/`, 리스너 `adapter/out/`). 저장 트리거 이벤트는 리스너를 `adapter/in/event/`에. 발행 지점이 트랜잭션 밖이면 동기 `@EventListener`
- 브로커·시세 실패는 `BrokerCallGuard`: 중단은 `wrap`, 대체값 진행은 `tryGet`. 서비스의 raw `catch (RuntimeException)`은 이벤트 발행·분기가 있을 때만. 벤더 429·한도 코드는 어댑터에서 `BrokerRateLimitException`

### 영속성
- 스키마 소유 5분리, 남의 스키마 참조(FK 포함) 금지 → `architecture.md`
- 적용된 마이그레이션은 주석 포함 수정 금지(checksum 크래시). root expand/contract, enum은 `VARCHAR(20)` + `@Enumerated(STRING)` → `flyway.md`
- AES 컬럼은 `@Convert(AesStringConverter)`·`length≥512`·조건은 `*_hash`, raw INSERT는 암호문 → `modules/shared/platform.md`
- 소프트 삭제 테이블(`users`·`accounts`·`strategy`·`strategy_cycle`·`cycle_position`·`app_error_logs`)은 `@SQLRestriction("deleted_at IS NULL")`. `nativeQuery`엔 적용 안 돼 수동으로 쓴다. Cascade는 서비스가 사이클 → 계좌 → 사용자 순
- `application-local.yml` datasource는 `${DB_URL:...}` 형식 유지

### 공유 enum (`shared.kernel`)
- User·Strategy·Broker·NotificationChannel enum은 kernel 독립 타입(nested 재도입 금지). `@Enumerated(STRING)` 상수명 변경 금지. 기본 알림 채널은 `User.DEFAULT_CHANNEL`
- Ticker 추가 시 증권사 거래소 코드 레지스트리도 갱신. PRIVACY·VR 등록 ticker는 `StrategyCreationSettings.ticker()` 고정값(PRIVACY=SOXL, VR=TQQQ)

### 보안·필터
`@Component` Filter + `addFilterBefore()`는 `FilterRegistrationBean.setEnabled(false)`. `/api/internal/**`는 `X-Internal-Token`. CORS에 PATCH. 라우트는 첫 매치 우선 → `modules/shared/platform.md`

### 주석·로그
- 신규 코드는 주석을 함께 쓴다: 필드 `// 역할 한 줄`, 로직 블록 직전 단계 설명 한 줄, 코드값 `"840" // 국가코드: 미국`. Javadoc·블록 주석 금지
- 로그는 `[strategyId={}]`·`[accountId={}]`·`[orderId={}]`·`[userId={}]` 접두사로 시작(중간 `key=` 금지). id가 범위에 없으면 기존 키·범주 태그(`[DCA]`)를 쓰고 새 형식을 만들지 않는다
- `log.error`는 예외를 마지막 인자로, `log.warn`은 `e.getMessage()`만

### 모듈 문서가 SSOT인 규칙
Account ↔ Strategy 분리·원장 상한·런타임 매매 정책 → `modules/tradingcore/trading.md`, 매매·VR 공식 → `trading-formulas.md`(바꾸면 문서와 테스트를 함께), 런타임 설정 API → `modules/api/admin.md`.

### 아키텍처 맵 flows.yml 동기화
`src/test/resources/architecture/flows.yml`은 수기 원본이고 `ArchitectureMapTest`는 `code:` 존재와 enum 집합만 본다. 흐름 동작(`desc`·`cond`·`state`·`fail`), 통신 경로(`lane`·`to`), 상태 전이(`lifecycles`), 기능 추가·삭제(`flows`·`landscape`)를 바꾸면 같은 작업에서 갱신한다. 클래스 삭제·개명은 그 커밋에서 `code:`를 고친다. "아키텍처 맵 최신화" 점검은 마지막 수정 이후 `git log`로 대조한다.
