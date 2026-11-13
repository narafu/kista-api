# 레포 간 변경 모음

레포를 넘는 변경 후보를 한곳에 모은다. 관장은 fable-api. 각 항목의 상세 근거는 출처 문서에 있다.

## A. 배포 계약 (출처: fable-infra, 2026-11-11)

상세: `kista-infra/docs/superpowers/fable-review/findings/cross-repo-deploy.md`, 지침서 `kista-infra/docs/superpowers/fable-review/guides/01-deploy-contract.md`. 세 레포 배포 자산은 약 4,500줄(api 1,514 / ui 522 / infra 2,500).

| # | 변경 | api 영향 | ui 영향 | 상태 |
|---|---|---|---|---|
| A1 | 배포 대기 구조 제거: 앱 CI는 dispatch만 하고, infra `reconcile.yml`이 결과를 앱 커밋의 commit status로 되돌려 쓴다 | `wait-reconcile.sh`·bats·`stub/gh`·대기·토큰 재발급 step 삭제 | 같은 사본 삭제(두 레포 합 약 320줄) | infra 완료(ab5dd7a, payload `sha`가 있으면 status 기록). api 완료(2026-11-13: `event_type=deploy` + `client_payload[sha]`, 대기 구조 삭제). ui 완료(42fc30cd). 실배포로 두 앱 커밋 모두 `deploy/production` success 확인(2026-11-13). infra 정리(옛 dispatch 타입 2개·`run-name` request_id 삭제)는 ab47f81 |
| A2 | `required-env` 계약 파일 폐지, compose `${VAR:?}` 필수 보간으로 대체 | `detect-deploy-scope.sh:42`의 required-env 패턴 삭제 | `deploy/server/docker-compose.yml`에 `${API_BASE_URL:?}`·`${TRADING_API_BASE_URL:?}`, required-env와 contract.bats 단언 삭제 | 완료: 1단계 infra 번들 수용(5209cb8)·2단계 api(bd1e56f80)·ui, 3단계 infra `reconcile.sh` 검사 삭제(d541047, 배포 완료) |
| A3 | `readiness` 계약 파일 폐지: compose healthcheck를 `/actuator/health/readiness`로 바꿔 Docker healthy = 준비 완료 | compose healthcheck 변경, `Dockerfile:46-47` HEALTHCHECK 삭제 | 없음 | **api 판단 대기**: readiness 그룹에 DB·Redis가 있어 일시 장애 때 unhealthy가 돼도 부작용이 없는지, Caddy `health_uri`는 liveness로 둘지 |
| A4 | Caddy 라우팅 소유 대칭화: ui도 자기 스니펫을 소유, 공용 upstream 블록은 infra Caddyfile의 named snippet 1회 정의 | api 스니펫 3회 반복 블록을 import로, `CaddyRoutingTest`가 import 문을 견디는지 확인 | `deploy/server/caddy/kista-ui.caddy` 신설 | 완료(2026-11-13, infra 01 T7). infra `09a70b7`(Caddyfile `(upstream)` snippet, caddy 마운트를 `/opt/kista-infra/routes`로), api `8af24c94b`(스니펫 3곳 `import upstream /actuator/health/liveness`, `compose-invariants.bats` 갱신. `CaddyRoutingTest`는 수정 불필요), ui `5804575f`, infra `c3894cd`(Caddyfile ui 블록 삭제, `routes/*.caddy` import). 동작 차이는 kista-scheduler·kista-trading upstream의 `keepalive off`뿐 |
| A5 | 문서 SSOT 정리: 서버 초기 설정·백업 복구는 infra, 운영 `.env` 키 목록은 각 앱 레포 `.env.example` | `deploy/server/README.md:19-51,68-103,166-179`, `docs/agents/docker-infra.md:157-178` 삭제·링크화. `CMC_API_KEY`가 `.env.example`에 없다(코드는 trading-core `application.yml:118`에서 읽음) | 해당 구간 정리 | 후보. api 문서 정리 지침서와 묶는다 |
| A6 | 낡은 서술 수정 | `docker-infra.md:18` "data_net은 kista-api만"(실제 3 role), `:12` config 판정 파일 목록 누락, README·docker-infra.md의 `SERVER_HOST` secret 언급 | 없음 | 문서 정리 지침서에서 처리 |
| A7 | ui CI: 변경 경로 판정 없음(문서만 바꿔도 배포), typecheck 이중 실행, PR 단계 shellcheck·bats 없음 | 없음 | `server-deploy.yml`·`ci.yml` | fable-ui에 전달됨 |

infra 재심 결과 유지: role별 SHA 선언, 앱 단위 전부 아니면 전무, compose 스케일 blue/green, kista-trading stop-first·`stop_grace_period` 200s, 앱 레포가 SSH 키를 갖지 않는 구조. Swarm·Kamal 전환은 기각.

### 사용자 결정 (2026-11-11, fable-infra 세션에서)

1. A1 배포 대기 구조 삭제 확정. api·ui의 wait-reconcile 일체가 삭제 대상(infra 지침서 01 T6).
2. 배포 구성도 생성기 삭제. 그림 원본은 infra README의 수기 mermaid 하나. api `docs/architecture-map.md:62-156` 배포 구간은 그 링크로 바꾼다.
3. 시크릿 sops + age 전환. infra `.env.example`이 사라지므로 운영 키 목록 원본은 api·ui `.env.example`(api는 `CMC_API_KEY`·`KIS_ADMIN_APP_KEY`·`KIS_ADMIN_APP_SECRET` 추가 필요. KIS 삭제 결정에 따라 KIS 키는 추가하지 않는다).
4. A4 Caddy 라우팅 소유 통일 진행. api 스니펫이 infra 공용 upstream snippet을 import, `CaddyRoutingTest` 수정 필요(infra 지침서 01 T7).
5. A3 readiness: api 판단 "가능". 조건은 Caddy `health_uri`는 liveness 유지, unhealthy 기반 재기동 미도입, `service_healthy`·헬스 알림 의미 확인.
6. `API_DOMAIN`: Caddy 컨테이너가 infra.env에서 받아 `kista-api.caddy:5` 사이트 주소로 쓴다. kista-api.env의 값은 아무도 읽지 않아 제거. api `docs/agents/docker-infra.md:24` 문장을 이에 맞게 고친다.

kista-infra 검토는 지침서 기준으로 끝났다(실행은 별도 세션).

## B. api ↔ ui 계약

책임 분담 기준은 `ui-api-principles.md`(사용자 지시로 작성). api 쪽 실행은 지침서 10.

- 측정에서 확인: kista-ui는 전략·계좌·통계를 root를 거치지 않고 trading-core로 직접 프록시한다(`kista-ui/app/api/trading-cycles/[[...path]]/route.ts:3-5`). 프로세스 통합·분리 재검토(주제 05) 결론에 따라 ui 프록시 라우트 구조가 바뀐다.

### api가 바뀌면 줄어드는 ui 코드 (출처: fable-ui 1차, 2026-11-11)

상세: `kista-ui/docs/superpowers/fable-review/findings/b-type-duplication.md` 6절.

| # | ui 현상 | api에 필요한 변경 | ui 감량(추정) |
|---|---|---|---|
| B1 | 생성 타입 `shared/lib/api-types.ts`(9,564줄)의 프로퍼티 832개 중 692개(83%)가 optional이라 ui가 생성 타입을 4개 파일에서만 쓰고 74쌍을 수기 재선언, 정규화 함수 16개 217줄 | 응답 DTO의 required를 정확히 내보낸다(record `@NullMarked` 기본 + `@Nullable`만 표기, springdoc 반영). BigDecimal 직렬화를 전역 한 곳에서 plain number로 통일 | 정규화·Raw 타입·방어 코드 약 352줄 + 수기 타입 대부분. ui 최대 항목 |
| B2 | VR 램프 기본값표·사전 검증 계산 약 70줄(`vrRamp.ts:33-43`, `vrDerived.ts`) | `/api/meta` 또는 전략 메타로 기본값·범위 제공 | 약 70줄 |
| B3 | 서버가 아는 상태를 ui가 재판정(`computeOrderReadiness`, `capitalAdjustment`, `scheduled-start`, `apiKeyExpiry`) | 응답에 상태 플래그·사유 코드 | 약 120줄 |
| B4 | 증권사·시장·통화 정적 맵(`api-schema.ts:52-114`) | 메타 `brokers[]`에 빠진 속성 보충 | 약 63줄 |
| B5 | 라벨·문구 맵(사용자 상태·역할, VR 모드, skip 사유, 에러 코드 문구), 코드값 집합 19종이 약 110곳에 복제 | 메타에 라벨 추가, ErrorCode 응답에 사용자 문구 | 약 100줄 + 복제 지점 |
| B6 | 가계부 집계 약 875줄을 ui가 계산 | 서버 집계 엔드포인트 신설(신규 API, 우선순위는 api 판단) | 약 875줄 |

와이어 형식 확인(2026-11-11, 코드 기준): Jackson 전역 설정이 없어 BigDecimal은 JSON number로 나간다. `PlainDecimalSerializer` 2벌(stats web dto, shared.contract.notify)만 지수 표기를 막는다. 그 밖의 필드는 scale에 따라 지수 표기 number가 나갈 수 있다. ui 주석의 "문자열로 온다"는 현재 코드와 맞지 않는다.

B1은 api 지침서 02(영속·DTO 구조)에 넣는다. B2~B5는 메타·응답 보강이라 별도 소규모 지침서로 묶는다.

### api 태스크 ↔ ui 태스크 짝 (2026-11-12, fable-ui 지침서 9개 완료 기준)

ui 지침서: `kista-ui/docs/superpowers/fable-review/guides/`. api 태스크 번호는 `guides/10-ui-contract.md`.

| api 선행 | ui 후행 | 배포 |
|---|---|---|
| T1 required 표기, T2 BigDecimal plain number | ui 01 타입 SSOT(생성 타입 직접 사용) | api 먼저, ui는 타입 재생성 후 |
| (없음) | ui 02 데이터 층(openapi-fetch, `resolveUpstream` 한 함수) ← ui 01 | ui 단독 |
| T11 단건 조회 | ui 02의 "목록을 받아 find" 쿼리 삭제 | api 먼저 |
| T5 메타 통합 | ui 05 메타 소비·runtime-config 통합 | api 먼저(추가만이라 안전) |
| T6 상태 플래그 | ui 05 상태 재판정 삭제 | api 먼저 |
| T7 오류 문구 | ui 05 오류 문구 맵 삭제 | api 먼저 |
| T3·T4 목록 규약(가계부 → 전략 주문·체결·이력 → 관리자) | ui 05 목록 페이지형, ui 06 DataList 페이지형 | **묶음별 같은 날 배포**(봉투가 바뀐다) |
| T8 가계부 집계 | ui 05 가계부 집계 삭제 | api 먼저 |
| T10 검증 전용 엔드포인트 | ui 05 규칙 사본 삭제, ui 04 태스크 6(전략 폼 통합, T5·T7도 필요) | api 먼저 |
| T12 차트 지표, `reapplyAvailableAt` | ui 05 indicators.ts·localStorage 쿨다운 삭제 | api 먼저 |
| 지침서 04 단계 A(KIS·업비트 삭제) | ui 05 KIS·업비트 전용 코드 삭제 | 같은 배포 묶음 |
| T9 원칙 문서를 `docs/agents/ui-api-contract.md`로 이동 | ui 08 태스크 10(루트 CLAUDE.md가 그 경로를 가리킴) | 문서 |

**테스트 짝 확인**(ui 테스트를 지우기 전에 서버 테스트가 같은 케이스를 갖는지 대조한다. ui 07):

| ui 테스트 | 서버 쪽 대응 |
|---|---|
| `aggregate.test.ts`(346줄) 등 가계부 집계 케이스 | T8 서비스 테스트 |
| `computeOrderReadiness`·`canWithdrawCapital`·`apiKeyExpiryState` 케이스 | T6 도메인 테스트 |
| `strategyFormGuards`·`vrDerived` 케이스 | T10 대칭 테스트 |

ui에만 있던 규칙(서버가 검증하지 않아 지금은 우회 가능한 규칙)이 대조 중 나오면 서버에 먼저 추가한다. T6·T8·T10의 구현자는 위 ui 테스트 파일을 케이스 목록의 입력으로 읽는다.

### 결정·정정 (2026-11-12)

- D1=A 확정(사용자): api와 trading-core를 한 코드베이스로 합치고 web/worker role로 나눈다. **ui의 API 진입점은 하나가 된다** — `TRADING_API_BASE_URL`과 trading 직결 프록시 라우트는 api 지침서 05의 배포 전환 단계에서 삭제한다(fable-ui가 2026-11-12 루트 compose에 추가한 `TRADING_API_BASE_URL`도 그때 지운다). infra의 role 정의는 kista-web·kista-worker 2개가 된다.
- 토스 주문 유지(사용자). `brokers[].tradable` 메타는 만들지 않는다(전 브로커가 매매 가능).
- 메타 `userStatuses` REJECTED 라벨은 "반려".
- 관리자 목록·전략 주문 내역은 "더 보기" 방식. 총건수 미제공 규약에 예외 없음.
- PRIVACY `days`/`range`: ui가 틀린 파라미터를 보낸 적은 없다. 처음부터 파라미터 없이 전량을 받았다.
- 진입점 단일화 짝: api 지침서 05의 배포 전환 단계 ↔ ui 지침서 02 태스크 8(`resolveUpstream`이 한 호스트만 반환, `getTradingApiBaseUrl`·`TRADING_API_BASE_URL`·`NEXT_PUBLIC_TRADING_API_BASE_URL` 폴백·루트/deploy compose 값 삭제). **같은 날 배포.** 그 전까지 ui는 두 호스트 매핑을 유지한다.
- 매매 공식 변경(api 지침서 08 F1)의 ui 영향(fable-ui grep 확인): `currentRound`는 화면 3곳이 `toFixed(1)`로 표시해 자릿수가 늘어도 깨지지 않는다. `priceOffsetRate`는 표시·비교·계산 코드가 없다. 소수 2자리를 가정한 비교는 없다. 회차 표시는 소수 1자리를 유지한다(ui 변경 없음).

### fable-ui 요청 (2026-11-13) — `fieldErrors` 응답 규약, api 지침서 10의 2-5절·T7 반영 대기

ui 지침서 04 "서버 오류의 분담"의 전제다. ui는 실패 토스트를 전역 한 곳에서 항상 `detail`로 띄우고, 폼은 `fieldErrors`를 필드 옆에 더한다.

- `fieldErrors`가 하나라도 있는 응답의 `detail`은 요약 문장 하나로 고정한다("입력값을 확인해 주세요."). 필드별 문장은 `fieldErrors[].detail`에만 싣는다. Bean Validation 실패와, 필드를 지목하는 도메인 검증 실패 둘 다 같다. 이 규약이 없으면 필드 하나짜리 오류에서 같은 문장이 토스트와 필드에 두 번 나온다(동작은 깨지지 않는다).
- `fieldErrors[].field`는 요청 바디의 필드 경로다. Spring 기본 표기(`orders[0].price`) 그대로 보내면 된다. ui가 `orders.0.price`로 바꾼다.
- ui는 폼 필드 이름을 요청 바디 필드 이름과 같게 짓는다. 이름이 맞지 않는 `fieldErrors`는 폼 버튼 위 한 줄로 보인다. api가 필드 이름을 ui에 맞출 필요는 없다.

## C. 인스턴스 구성 (fable-infra, 사용자 결정 2026-11-12)

상세: `kista-infra/docs/superpowers/fable-review/guides/03-instance-topology.md`.

- 결정: **운영 1대 + 최소 대기 1대.** 운영 스택은 1번 한 대에 모으고(fida 포함) 3 OCPU·18GB로 키운다. 2번은 비운 뒤 대기 호스트(주 1회 백업 복구 리허설, 공개 포트 22뿐, 운영 자격증명 없음). web·상태 계층 분리, 스테이징, 자체 러너는 기각.
- 실측(2026-11-12 11:32 KST, 매매 시간대 밖): 1번 2 OCPU·12GB 중 2.5GB 사용(api 531MiB, scheduler 452MiB, trading 551MiB, ui 80MiB, postgres 64MiB, DB 33MB). 2번은 실제로 1 OCPU·6GB. 별도 50GB 볼륨은 어디에도 붙어 있지 않다.
- 백업을 하루 1회(KST 02:00, 매매 세션 한가운데)에서 3회(06:40, 14:00, 22:00)로.
- fida는 KISTA에 합치든 아니든 운영 호스트로 옮긴다. 브라우저 컨테이너는 data_net에 넣지 않는다.
- 미확인: OCI 계정이 Pay As You Go인지(Always Free 전용이면 유휴 인스턴스 회수 대상일 수 있음).
- **2 role 전환 첫 배포의 이중 실행 위험**: api 지침서 05의 4-3절에 순서를 반영했다. `roles` 순서는 worker → web, EPR 재발행 소유는 worker.
- JVM 힙 참고: 실사용이 각 500MiB 안팎이라 지침서 05 M14의 시작값(web 1280m, worker 2048m)은 여유가 있다.
- Caddy 내부 리스너 실증 완료(fable-infra): api 스니펫 사이트 주소에 `http://:8080` 추가, UI는 `API_BASE_URL=http://caddy:8080` 하나. api 지침서 05의 7절, ui 지침서 02 태스크 8, infra 지침서 03 5-4절이 짝이다.
- infra mem_limit 시작값: web 2048m, worker 3072m, postgres 2048m. OCI 계정은 Pay As You Go 확인.
- infra `reconcile.yml`은 새 dispatch 타입 `deploy`와 옛 타입을 함께 받는다. 앱 레포의 전환은 infra 01 T6(commit status 전환)과 같이.

## D. fida (fable-fida, 사용자 결정 2026-11-12)

상세: `/Users/phs/workspace/fida/docs/superpowers/fable-review/guides/01-merge-or-separate.md`.

- 결정: **fida는 별도 레포·별도 컨테이너 유지**(kista-api에 합치지 않는다). 운영 호스트만 kista-server로 옮긴다(C절).
- 계약 원본은 kista 쪽이다(지침서 05 X5 이후 privacy 모듈 `api` 패키지). fida는 응답에서 `id`만 읽도록 줄인다(fida guides/02 태스크 3). 접점은 `POST /api/internal/fida-orders` 1개, 요청 7필드 + 응답 `id`.
- kista-api 쪽 할 일 (지침서 05 X5에 함께 넣는다):
  1. `ContractWireFormatTest`에 응답 `id` 필드명을 고정하는 단언 추가(fida가 읽는 유일한 응답 필드).
  2. ✅(2026-11-13, S1 세션, `820203d13`) `FidaOrderRequest.orders`에 `@NotNull` 추가. 지금은 null이면 `PrivacyTradeValidationService.inspect`·`saveBaseWithOrders`의 `orders().stream()`에서 NPE → 500 가능(코드 추론, 재현 안 함). 400이 되게 하고 테스트로 고정.
  3. fida가 보내는 `tradeDate`(매매표 제목의 M/D)를 kista는 `releaseDate`(발행일, 거래일 D-1)로 저장한다. 두 날짜의 의미를 kista 쪽에서 확정해 계약 타입 주석에 남긴다.
  4. fida의 `requireSubmittable()`은 kista `PrivacyTradeValidationService`와 일부 겹치지만 400 사전 차단이라 fida에 유지. 기준 규칙은 kista.
- 같은 호스트로 옮긴 뒤 fida의 `KISTA_URL`을 내부 주소로 바꾸면 Caddy 경로 의존(2026-09-17 404 사고 원인)이 사라진다. fida 앱 컨테이너는 kista-worker에 닿아야 하고 playwright 컨테이너는 data_net에 넣지 않는다 — 네트워크 설계는 fable-infra.
- 함께 승인: fida `POST /orders/from-image` 삭제, GH Actions 수동 재실행 삭제(kista 영향 없음).

## E. 추가 검토(지침서 11~13)에서 나온 레포 간 항목 (2026-11-13, fable-api)

kista-infra에 요청:

- **엔드포인트별 호출 수 수집**(지침서 12 A1). 운영에 접근 로그와 메트릭 수집이 없어 조회 전용 기능(통계·백테스트·벤치마크·코인 지표)이 쓰이는지 알 수 없다. kista-api는 Actuator `http.server.requests`를 이미 만든다. 운영에서 그것을 모아 30일 보관하는 가장 가벼운 방법을 infra가 정한다(Prometheus 한 컨테이너, 또는 Caddy 접근 로그 집계). 30일치가 쌓이면 12의 D1·D2를 다시 본다.
- 11의 D4가 승인되면 운영 정리 SQL 2건(죽은 `scheduler_locks` 7행, 옛 `public.flyway_schema_history`)의 실행 절차는 infra 런북을 따른다.
- 11 T4·T7·T5의 contract 마이그레이션(컬럼·테이블 drop)은 `check-migrations.sh`의 expand/contract 검사를 통과해야 한다. trading 스키마에도 검사가 적용되는 시점(05 D3)을 맞춘다.

kista-ui에 요청·확인:

- `GET /api/admin/accounts/{a}/strategies/{s}/trade-dates`는 ui 호출이 0이다. 쓸 계획이 없으면 삭제한다(12 F2).
- 12의 D1(주택 벤치마크 삭제)이 결정되면 `/stats/benchmark`의 주택 부분을 먼저 걷어낸다. api 코드 삭제는 그 뒤, 테이블 drop은 그 다음 배포다.
- 12의 D0이 (나)(본인·가족용 확정)면 `/pending`·`/rejected`·`/admin/pending`·`/admin/settings` 일부·`/admin/{accounts,users,trades}`가 삭제 대상이 된다. 결정 전에는 이 화면들의 리팩토링을 미룬다.
- 13 U8: "이미 그 상태" 충돌 응답이 400에서 409로 바뀐다(`StrategyService`의 일시정지·재개·삭제). ui가 이 경우 400에 기대는 분기가 있는지 확인한다. 같은 날 배포.
- 13 U5: 사용자 알림 문구의 금액 표기가 `Currency.format` 한 가지로 통일된다(`$1234.50` → `1,234.50 USD` 계열). 푸시 알림 본문을 파싱하는 ui 코드가 있으면 알린다.
- 13 U1: 승인 전(PENDING) 사용자의 토큰에는 `role` 클레임이 없어지고, 승인 대기 화면이 쓰는 경로 말고는 403이 된다. 승인 대기 화면이 부르는 api 경로 목록을 ui가 확정해 준다.

fida에 확인:

- 운영자가 PRIVACY 기준표를 90일 동안 11번 손으로 고쳤다(`audit_logs`의 `PRIVACY_BASE_UPDATE`). fida가 보낸 값이 틀렸던 것인지 kista의 수신 검증이 놓친 것인지 원인을 나눈다(12 B5).

정정:

- `findings/09`의 "`fallbackExecution` 리스너는 EPR 기록 없음"은 틀렸다. Spring Modulith 2.1.1은 `fallbackExecution` 리스너도 `event_publication`에 기록한다(`findings/18` C절, `PersistentApplicationEventMulticaster:116-145`). 05의 판단(스트림 경로를 먼저 끊는다, EPR 한 테이블)은 바뀌지 않는다.
- 04 단계 A의 업비트 삭제 범위에서 `tradingcore/stats/adapter/out/upbit`(코인 시장 지표의 김치 프리미엄 시세)는 뺀다. 브로커 어댑터가 아니다(12의 3-4).
