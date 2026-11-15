## com.kista.api.web (`:api`)

Spring Modulith 앱셸 모듈, `Type.CLOSED`, NamedInterface 0개. 아무 모듈도 web을 참조하지 않는 sink다 (가드: `HexagonalArchitectureTest.web_must_stay_pure_inbound_sink` — `web → ..application.service../..adapter.out..` 금지, trading-core `tradingcore.web`과 대칭). **신규 파일이 단일 모듈만 소비하거나 모듈 의존이 없으면 web에 두지 말고 소유 모듈로 보낸다** — 여기엔 진짜 다중 모듈 fan-out과 순환 회피용 구현체만 둔다.

- `RootSecurityPolicy`(`SecurityRoutePolicy`): root 고유 라우트 규칙. `SecurityConfig`가 공통 규칙(internal/admin) 뒤에 적용하므로 이 정책이 admin/internal을 permitAll로 열 수 없다. 공개 읽기 4경로(`POST /api/client-errors`·`GET /api/market/**`·`GET /api/meta`·`GET /api/runtime-config`)는 같은 매처 상수를 permitAll 등록과 `anonymousFallbackMatchers()`(무효 토큰 익명 통과)가 공유한다. `/api/auth`는 로그인·갱신·로그아웃 POST만 공개로 적고 공유에서 뺀다.
- `UserEventResubmitScheduler`: FAILED `UserDeletedEvent`·`UserNotifyProfileChangedEvent` publication을 10분마다 재발행한다. user에 두면 notify→user 의존과 순환이라 앱셸에 둔다. worker role이라 public EPR 재발행 소유자 kista-scheduler에서만 돈다. trading-core `UserEventResubmitScheduler`와 골격이 같아도 `shared.platform`으로 추출하지 않는다(2곳뿐, 필터 이벤트 타입이 `shared.kernel`이라 platform이 참조 불가, 알림 포트·문구가 달라 공통부가 상수 2개뿐).
- `ApiExceptionReporter`는 trading-core `TradingExceptionReporter`와 동형이지만 합치지 않는다 → `decisions.md`.
- `MetaController`(`GET /api/meta`): enum 메타 단일 번들. finance+sharedkernel fan-out이라 단일 모듈로 옮길 수 없다. 내부 API·RestClient 호출이 없어 trading-core 장애와 무관하게 응답한다 (가드: `HexagonalArchitectureTest.inbound_adapters_must_not_perform_outbound_http`).
- `AdminSchedulerController`(`/kbland-*`)는 worker role 전용이라 kista-api에선 빈이 없다(404).

### 예외 응답 자동 처리 (공용 advice)
- Controller에서 catch/rethrow 하지 않는다. advice는 프로세스당 `platform.web.ProblemDetailExceptionHandler` 하나이고(`ProblemDetailAutoConfiguration`이 등록, `@WebMvcTest`에도 `AutoConfigureWebMvc.imports`로 올라온다) 다른 `@RestControllerAdvice`를 만들지 않는다 — catch-all을 가진 advice가 둘이면 먼저 평가된 쪽이 모든 예외를 잡는다.
- **예외 매핑 SSOT**: 예외를 소유한 모듈의 `adapter/in/web/<Module>ProblemDetails`(`ProblemDetailMappingContributor`)가 `mappings()` 표(`Mapping(status, title, code, fixedDetail)`)로 기여한다. 표로 못 나타내는 응답(헤더·동적 title·오류 보고: `User.CooldownException` 429+`Retry-After`, `BrokerApiException` 503)만 `responders()`로 기여한다. 같은 예외를 두 곳이 기여하면 기동 실패. 현재 응답은 `ExceptionResponseTableTest`(root·trading-core)가 고정한다.
- 미매핑 500은 프로세스별 `ExceptionReporter` 빈이 `AppErrorRaisedEvent`로 보고한다. 슬라이스 테스트에는 이 빈이 없어 보고를 건너뛴다 — 보고를 단언하려면 직접 `@Import`.
- 범용 예외 선택: 부재=`NoSuchElementException`(404, 고정 문구라 메시지에 진단용 id 허용), 상태=`IllegalStateException`, 입력=`IllegalArgumentException`(둘 다 400, 메시지 원문이 detail). IAE/ISE 리터럴은 "~니다."로 끝나야 한다 (가드: `ExceptionMessageFormatTest` — 기존 위반은 `exception-message-allowlist.txt`에 동결, 고치면 지운다).
- **ProblemDetail 계약**: `detail`은 사용자에게 그대로 보일 한국어 문구다. 내부 정보(URL·응답 바디·계좌번호·enum 원문·영어 프레임워크 메시지)는 싣지 말고 로그로 — 영어 프레임워크 예외는 `Mapping.fixedDetail`로 고정 문구를 쓴다. 예외: 검증 실패(`MethodArgumentNotValidException`)는 "[field: message]" 형식 유지.
- **기계 판독 `code`**: UI가 분기해야 하는 예외만 `ErrorCode`(`com.kista.shared.platform.web`)를 `Mapping`에 지정한다. 상수 이름 변경·삭제 금지(kista-ui 계약). 추가 절차: ① `ErrorCode` 상수 ② 소유 모듈 `*ProblemDetails`의 `Mapping` ③ 같은 예외 클래스가 여러 의미면 서브클래스 분리(`resolve()`가 계층 하위부터 탐색 — 예: `AlreadyOrderedTodayException`) ④ openapi는 `ErrorCodeOpenApiCustomizer`가 자동 반영 → kista-ui `gen:types` + 문구 매핑.
- **내부 API 경유 한계**: root 내부 API 어댑터는 trading-core 응답의 status만 보고 `Admin*` 예외를 되살린다. 같은 status에 다른 코드가 실려 오는 경로가 생기면 `InternalApiErrorDetails`가 `code`도 읽어 전달하도록 확장한다.
