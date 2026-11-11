# 레포 간 변경 모음

레포를 넘는 변경 후보를 한곳에 모은다. 관장은 fable-api. 각 항목의 상세 근거는 출처 문서에 있다.

## A. 배포 계약 (출처: fable-infra, 2026-11-11)

상세: `kista-infra/docs/superpowers/fable-review/findings/cross-repo-deploy.md`, 지침서 `kista-infra/docs/superpowers/fable-review/guides/01-deploy-contract.md`. 세 레포 배포 자산은 약 4,500줄(api 1,514 / ui 522 / infra 2,500).

| # | 변경 | api 영향 | ui 영향 | 상태 |
|---|---|---|---|---|
| A1 | 배포 대기 구조 제거: 앱 CI는 dispatch만 하고, infra `reconcile.yml`이 결과를 앱 커밋의 commit status로 되돌려 쓴다 | `wait-reconcile.sh`·bats·`stub/gh`·대기·토큰 재발급 step 삭제 | 같은 사본 삭제(두 레포 합 약 320줄) | GitHub App `kista-infra-dispatch`를 앱 레포에 설치하고 statuses:write 부여 필요 — **사용자 승인 필요** |
| A2 | `required-env` 계약 파일 폐지, compose `${VAR:?}` 필수 보간으로 대체 | `detect-deploy-scope.sh:42`의 required-env 패턴 삭제 | `deploy/server/docker-compose.yml`에 `${API_BASE_URL:?}`·`${TRADING_API_BASE_URL:?}`, required-env와 contract.bats 단언 삭제 | 후보 |
| A3 | `readiness` 계약 파일 폐지: compose healthcheck를 `/actuator/health/readiness`로 바꿔 Docker healthy = 준비 완료 | compose healthcheck 변경, `Dockerfile:46-47` HEALTHCHECK 삭제 | 없음 | **api 판단 대기**: readiness 그룹에 DB·Redis가 있어 일시 장애 때 unhealthy가 돼도 부작용이 없는지, Caddy `health_uri`는 liveness로 둘지 |
| A4 | Caddy 라우팅 소유 대칭화: ui도 자기 스니펫을 소유, 공용 upstream 블록은 infra Caddyfile의 named snippet 1회 정의 | api 스니펫 3회 반복 블록을 import로, `CaddyRoutingTest`가 import 문을 견디는지 확인 | `deploy/server/caddy/kista-ui.caddy` 신설 | 후보 |
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
