# 지침서 10. UI 계약 — 스펙 정확화, 목록 규약, 메타, 상태 플래그, 집계

작성 2026-11-11 (Fable). 기준: `ui-api-principles.md`. 근거 측정: `findings/07-list-endpoints.md`, `cross-repo.md` B절. kista-ui 지침서 01·05가 이 지침서의 T1~T2, T5~T7에 의존한다.

## 1. 현재 구조와 문제

| 영역 | 현황 |
|---|---|
| OpenAPI required | Response record 59개 중 `requiredMode`를 쓰는 것은 2개. `@NullMarked` 0개. 그 결과 UI 생성 타입의 프로퍼티 83%가 optional이고 UI가 타입 74쌍을 수기로 다시 선언한다 |
| 숫자 형식 | Jackson 전역 설정 없음. BigDecimal 필드를 가진 Response 30개. `PlainDecimalSerializer` 2벌만 지수 표기를 막는다 |
| 목록 | 컬렉션 엔드포인트 42개(사용자 20, 관리자 9, 내부 13). 페이지 형식 3갈래(커서 `items,nextCursor,hasMore` 2개 / `content,page,size,totalElements` 1개 / `items,page,totalElements,totalPages` 1개), `size` 기본값·상한도 제각각. 봉투는 배열·`{items}`·`{orders}`·`{content}` 네 갈래 |
| 늘어나는 데이터의 전량 반환 | 12개. 가계부 거래내역·자산 스냅샷(LIMIT 없음), 전략 주문, 일별 체결, 관리자 trades/users/accounts |
| 반쪽 페이징 | `/api/stats/cycles`는 전량을 읽고 메모리에서 자른다. 로그 조회(audit 100건, errors 500건)는 다음 페이지를 받을 방법이 없다 |
| 집계 | finance 가계부는 집계 엔드포인트 0개. 월별·카테고리별 합계, 순자산, 예산 소진을 UI가 계산(약 875줄) |
| 메타 | `MetaBundle` 18필드. `EnumMeta.description`은 항상 null. 응답·요청에 쓰이는데 메타에 없는 enum 약 15개(`UserRole`, `UserStatus`, `Currency`, `SkipReason`, `CycleStatusFilter` 등). 전략 기본값은 `/api/runtime-config`로 따로 나가고 VR 램프 기본값표는 어디에도 없어 UI가 복제 |
| 상태 플래그 | `can*` 0개. 전략 허용 액션, API 키 만료, 미리보기 경고를 UI가 여러 필드로 추론(약 120~250줄) |
| 오류 | `ErrorCode` 13개. Bean Validation 오류는 `[필드명: 기본메시지]`가 그대로 나가 한국어 보장이 안 된다 |

## 2. 목표 규약

### 2-1. 스펙이 와이어와 일치한다

- 응답 DTO 패키지(`..adapter.in.web.dto`)와 `shared.contract`에 패키지 단위 `@NullMarked`를 건다. null일 수 있는 컴포넌트만 `@Nullable`을 붙인다.
- springdoc가 이 표기를 `required`로 내보내게 한다. 구현 방법은 T1에서 확인한다: springdoc 버전이 jspecify를 직접 읽으면 설정만, 아니면 `:shared` platform에 커스터마이저 1개(non-`@Nullable` record 컴포넌트를 required로 표기)를 둔다. `@Schema(requiredMode=...)`를 필드마다 붙이는 방식은 쓰지 않는다(2개 DTO의 기존 표기는 지운다).
- BigDecimal은 전역으로 plain number다. `PlatformAutoConfiguration`에서 Jackson 3 매퍼에 `WRITE_BIGDECIMAL_AS_PLAIN`을 켜고 `PlainDecimalSerializer` 2벌을 지운다.
- 금액을 문자열로 보내지 않는다. UI가 정밀도 손실 사례를 제시하면 그때 필드 단위로 다시 정한다.

### 2-2. 목록 규약 (하나뿐이다)

모든 컬렉션 응답은 객체 봉투다. 배열을 그대로 반환하지 않는다.

```jsonc
// 전량형 — 상한이 구조적으로 작다
{ "items": [ ... ] }

// 페이지형 — 시간이 지나면 늘어난다
// 요청: ?cursor=<불투명 문자열>&size=<기본 50, 최대 200>
{ "items": [ ... ], "nextCursor": "..." , "hasMore": true }
```

- 페이지형은 **커서(keyset)** 하나다. page·size 형식 2갈래는 커서로 바꾼다. 이미 커서를 쓰는 2개 엔드포인트의 형식을 그대로 표준으로 삼는다.
- `size` 기본 50, 상한 200. 공용 record `PageRequest`(cursor, size 정규화)와 `CursorPage<T>`를 `:shared` platform에 하나 두고 전 모듈이 쓴다.
- 전체 건수·전체 페이지 수는 목록 응답에 싣지 않는다. 화면에 총계가 필요하면 그것은 집계이므로 집계 엔드포인트가 준다.
- 정렬은 서버 고정이고 엔드포인트 문서에 적는다. 화면이 사용자 선택 정렬을 요구할 때만 `sort=<필드>,<asc|desc>` 한 형식으로 추가한다. 전량형은 UI가 받은 전량 안에서 정렬해도 된다.
- 각 컨트롤러 메서드의 `@Operation` 설명 첫머리에 `[전량형: <상한 근거>]` 또는 `[페이지형]`을 적는다.

분류(T4에서 전수 확정):

| 페이지형으로 전환 | 전량형 유지 |
|---|---|
| 가계부 거래내역, 자산 스냅샷, 전략 주문(`/trading-cycles/{id}/orders`), 일별 체결, 사이클 이력(`/stats/cycles`), DCA 실행 이력, 관리자 trades·users·accounts, audit·error 로그 | 사용자의 계좌 목록, 전략 목록, 가계부 계좌·카테고리·예산, 종목·휴장일·메타성 목록 |

차트용 시계열(자산 스냅샷 추이 등)은 "기간 파라미터로 범위를 정하는 전량형"으로 둔다. 기간 상한을 서버가 강제한다.

### 2-3. 메타 한 곳

- `/api/meta`가 UI가 쓰는 모든 코드값의 라벨·설명과 **등록 기본값·허용 범위**의 단일 출처다. `/api/runtime-config`의 전략 기본값·허용값과 VR 램프 기본값표(`VrRampInput.withDefaults`)를 메타로 합친다. 런타임에 바뀌는 값이면 메타 응답이 그 시점 값을 싣는다(캐시 정책은 T5에서 정한다).
- 응답·요청에 나가는 enum은 전부 메타에 있어야 한다. 가드 테스트: web DTO가 참조하는 enum 집합 ⊆ 메타에 실린 enum 집합.
- `EnumMeta.description`은 값을 채우거나 필드를 없앤다. 항상 null인 필드를 두지 않는다. `StrategyTypeMeta`의 label과 description이 같은 값인 것도 같은 기준으로 정리한다.
- `brokers[]`에 `tradable`(지침서 04), 시장·통화·`amountScale`을 싣는다.

### 2-4. 서버가 판정해서 내려준다

- 전략 상세: `allowedActions`(예: `PAUSE`, `RESUME`, `INJECT_CAPITAL`, `WITHDRAW_CAPITAL`, `EDIT_SEED`, `RESET_VR`, `DELETE`)와, 막힌 액션은 `blockedActions[{action, reasonCode}]`. 사유 코드의 문구는 메타가 준다.
- 계좌: `apiKeyExpiry{status: OK|EXPIRING|EXPIRED|NONE, expiresOn, daysLeft}`.
- 주문 미리보기: 경고를 `warnings[{code, detail}]` 배열로. UI의 `computeOrderReadiness`가 하던 판정을 서버로 옮긴다.
- 예약 시작·투자금 조정 가능 여부도 같은 방식.

### 2-5. 오류 문구

- `detail`은 항상 사용자에게 보여줄 한국어 문장이다. Bean Validation 메시지도 포함한다: 제약 애너테이션에 한국어 `message`를 주고, 필드명을 노출하는 `[필드명: ...]` 조립을 없애고 필드는 확장 속성 `fieldErrors[{field, detail}]`로 싣는다.
- `ErrorCode`는 동작 분기용이다. UI가 코드→문구 맵을 갖지 않는다. 상수 이름은 바꾸지 않는다(기존 계약).

### 2-6. 가계부 집계

월별·카테고리별 합계, 순자산 추이, 예산 소진율을 서버가 계산한다. 엔드포인트 명세의 재료는 `kista-ui/docs/superpowers/fable-review/findings/j-ui-api-principle-violations.md` 2-1절이다(자산 스냅샷 7가지 결과 / 거래·예산 요약·추이·예산 진행 / 일괄 등록 미리보기). UI 계산 약 925줄이 대상이다. 금액 반올림은 `Currency.round`를 쓴다.

## 3. 재심한 과거 결정

| 결정 | 재심 결과 |
|---|---|
| C-33 주식 전용 `*Usd` 필드명 유지(wire 키라 개명 시 UI 다수 변경) | 유지. 이 지침서는 필드 이름을 바꾸지 않는다 |
| C-11 소유권 응답 코드 400/404/403 구분 유지 | 유지 |
| `PlainDecimalSerializer`를 "전역 설정을 건드리지 않고 필드에만 적용" | **뒤집는다.** 전역 한 곳이 일관되고 사본 2벌이 사라진다 |
| `ErrorCode` 상수 이름 변경·삭제 금지 | 유지 |

## 4. 위험

- 봉투 변경(배열 → `{items}`)과 페이지 형식 변경은 UI와 동시에 나가야 한다. 엔드포인트 단위로 api 배포 → ui 배포 사이에 깨진다. **전환 방법**: 새 형식을 같은 경로에 바로 적용하고 ui를 같은 날 배포한다. 버전 경로를 만들지 않는다(사용자 수가 적고 UI가 유일한 클라이언트다). 배포 순서는 `cross-repo.md`에 엔드포인트 묶음별로 적는다.
- `@NullMarked` 도입으로 실제로는 null이 나가는 필드를 required로 잘못 표기할 수 있다. T1에서 DTO마다 `from()` 팩토리와 도메인 필드의 null 가능성을 읽어 `@Nullable`을 붙인다. 기계적으로 하지 않는다.
- 전역 plain number는 EPR 이벤트 직렬화와 Redis 계약에도 적용된다. number 토큰이라는 점은 같아 역직렬화는 그대로지만, 저장된 EPR 행과의 호환을 T2에서 확인한다.
- 내부 API(`/api/internal/**`) 13개도 같은 규약을 따른다. root와 trading-core가 함께 배포되지 않으면 깨지므로 내부 API 변경은 한 배포에 묶는다(배포 가드 문서 참고).

## 5. 태스크

완료 판정 공통: `./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"`, 해당 모듈 web 테스트, 그리고 T1 이후로는 스펙 가드 테스트.

| # | 태스크 | 모델 | 완료 판정 |
|---|---|---|---|
| T1 | **응답 required 정확화.** springdoc의 jspecify 지원 여부를 gradle 캐시의 sources jar로 확인 → 방식 결정 → DTO 패키지에 `@NullMarked`, null 가능 컴포넌트에 `@Nullable`. 스펙 가드 테스트 추가: 생성 스펙에서 Response 스키마의 optional 프로퍼티가 `@Nullable` 컴포넌트 집합과 일치 | Opus(방식·null 판정), Sonnet(적용) | 가드 테스트 통과. fable-ui에 "생성 타입 재생성 가능" 통지 |
| T2 | **BigDecimal 전역 plain number.** 전역 설정 1곳, `PlainDecimalSerializer` 2벌 삭제, 직렬화 테스트 1개(`1E+2`가 `100`으로 나감), EPR·Redis 호환 확인 | Sonnet | 테스트 통과 |
| T3 | 공용 `PageRequest`·`CursorPage<T>`·`Items<T>`를 `:shared` platform에 추가. 기존 커서 2개 엔드포인트를 이 타입으로 옮긴다(와이어 불변) | Sonnet | 기존 web 테스트 통과 |
| T4 | 42개 엔드포인트 분류표 확정(`findings/07` A절 기반)과 전환. 묶음: ① 가계부 ② 전략 주문·체결·사이클 이력 ③ 관리자 ④ 내부 API. 묶음마다 keyset 인덱스가 필요한지 확인하고 필요하면 Flyway 추가. `/stats/cycles`는 쿼리 수준 페이징으로 바꾼다 | Opus(분류·keyset 키 결정), Sonnet(적용) | 묶음별 web·persistence 테스트, ui 동시 배포 |
| T5 | 메타 통합: 누락 enum 추가, `description` 정리, `/api/runtime-config` 전략 기본값·VR 램프 기본값표 이관, 가드 테스트 | Sonnet, 캐시 정책은 Opus | 가드 테스트 통과 |
| T6 | 상태 플래그: 전략 `allowedActions`·`blockedActions`, 계좌 `apiKeyExpiry`, 미리보기 `warnings`. 판정 로직은 도메인에 두고 단위 테스트로 고정 | Opus | 도메인 테스트 통과 |
| T7 | 오류 문구: Bean Validation 한국어화, `fieldErrors` 확장, `ExceptionMessageFormatTest` 범위 확대 | Sonnet | 테스트 통과 |
| T8 | 가계부 집계 엔드포인트(fable-ui 명세 수신 후) | Opus(명세), Sonnet(구현) | 서비스·web 테스트 |
| T9 | `ui-api-principles.md`를 `docs/agents/ui-api-contract.md`로 옮겨 상시 규칙화, `constraints.md`에서 가리킨다. 가드: 컬렉션을 반환하는 컨트롤러 메서드는 `Items`/`CursorPage`만 반환(ArchUnit) | Sonnet | 가드 통과 |

| T10 | **검증 전용 엔드포인트.** `POST /api/accounts/{accountId}/trading-cycle-validations`(등록과 같은 body), 수정·VR 재설정도 같은 형태. 응답 `{fieldErrors[], warnings[], derived{...}}`. `derived`는 서버가 채울 기본값·파생값(램프 실효값, 필요 인출 자산, 시드 미리보기 등). 등록 서비스와 **같은 resolver 경로**를 저장 없이 태운다(검증 로직을 한 벌 더 만들지 않는다). 기존 `GET .../strategy-seed-preview`는 `derived`로 흡수 | Opus | resolver 단위 테스트 + web 테스트. 등록이 거절하는 입력은 검증도 같은 `fieldErrors`를 낸다는 대칭 테스트 |
| T11 | 단건 조회 `GET /api/accounts/{id}`, `GET /api/trading-cycles/{id}`. 지금은 UI가 목록을 받아 찾는다 | Sonnet | web 테스트(소유권 포함) |
| T12 | 차트 지표를 시계열 응답에 싣는다(이동평균·RSI). DCA 조건 판정이 쓰는 계산과 같은 코드를 쓴다. 사용자 상태 응답에 `reapplyAvailableAt` 추가 | Sonnet | 지표 값이 판정용 계산과 같다는 테스트 |

UI가 요청한 변경과의 대응(`kista-ui/.../findings/j-ui-api-principle-violations.md` 11-3절): A1·A4·A6·A8·A9 → T4, A2·A3·A5 → T8(명세는 같은 문서 2-1절 A·B·C 묶음의 입출력을 그대로 쓴다), A7 → T4(정렬 키 보정), A10·A17·A18·A19 → T8에 "기존 응답의 집계 필드 추가" 묶음으로, A11·A12·A13·A14·A15 → T6, A16 → T5, A20 → T7·T10.

관리자 PRIVACY 목록(`AdminPrivacyTradeController.java:44`, 파라미터 `range`): UI는 파라미터 없이 전량을 받아 로컬에서 거른다(fable-ui 정정 2026-11-12 — `days`를 보내는 호출부는 없었다. 앞서 "기간이 무시된다"고 적었던 것은 틀렸다). 전량 수신은 T4 관리자 묶음에서 페이지형 전환으로 해소한다.

사용자 결정(2026-11-12, UI 세션): ① 메타 `userStatuses`의 REJECTED 라벨은 "반려"(T5) ② 관리자 목록·전략 주문 내역은 번호 페이지네이션 대신 "더 보기" — 총건수·총페이지 미제공 규약에 예외 없음.

T1·T2는 다른 태스크와 독립이고 UI 01의 차단 조건이라 **가장 먼저** 실행한다. T3 → T4, T5·T6·T7은 서로 독립이다.

## 6. 하지 말 것

- JSON 필드 이름을 바꾸지 않는다(봉투와 페이지 필드 제외).
- `@Schema(requiredMode=...)`를 필드마다 붙여 해결하지 않는다.
- 새 page·size 형식이나 엔드포인트별 봉투 키(`orders`, `content`)를 만들지 않는다.
- 목록 응답에 합계·건수를 얹지 않는다.
- UI만을 위해 같은 데이터를 다른 모양으로 한 번 더 내려주는 엔드포인트를 만들지 않는다. 한 화면이 항상 같이 쓰는 묶음만 합친다(원칙 문서 마지막 행).

## 7. 다른 지침서와의 관계

- 02(영속·DTO): Response record를 줄이는 방식에 따라 T1의 `@NullMarked` 적용 대상이 달라진다. T1은 02보다 먼저 해도 된다(표기는 record가 합쳐져도 따라간다).
- 04(브로커): `brokers[].tradable`, `CandleResponse` 스키마 이름.
- 05(프로세스): 내부 API 13개의 존속 여부. 프로세스를 합치면 T4의 ④ 묶음이 사라진다. **④는 05의 결론 뒤에 한다.**
- kista-ui 지침서 01(타입 SSOT) ← T1·T2, 05(서버 규칙 사본 제거) ← T5·T6·T7, 가계부 ← T8.
