# 지침서 02. 영속·DTO 삼중 구조 축소 — 타입은 한 번만 선언한다

작성 2026-11-12 (Fable). 근거 측정: `findings/02-type-duplication.md` A·D절, `findings/01-vertical-slices.md` 패턴 1·2, JPA 기능 사용 집계(2026-11-12).

## 1. 현재 구조와 문제

한 개념이 계층마다 다시 선언된다.

```
Request record → Command record → domain record → Entity class → (다시) domain record → Response record
```

| 측정 | 값 |
|---|---|
| record 선언 | 618개 약 8,051줄(main의 14.5%) |
| 변환 메서드(`from`/`toDomain`/`toEntity`/`toCommand`/`toResponse` 등) | 174개 1,253줄(람다·인라인 생성 제외, 실제는 20~30% 더) |
| 영속 계층(Entity 44 + JpaRepository 41 + PersistenceAdapter) | 120파일 7,243줄 |
| 필드가 도메인과 100% 같은 Response | `Order`↔`OrderResponse`, `Strategy`↔`StrategyResponse`, `FinanceAccount`↔`FinanceAccountResponse` 등 |
| 도메인↔Entity 필드 겹침 | 72~93% |
| 테이블 1개당 파일 | 포트·어댑터·Entity·JpaRepository·도메인 record 5개. 필드 1개짜리 `StrategyInfiniteDetail`도 5파일 145줄 |
| 한 필드의 선언 횟수 | `divisionCount`가 main 37개 파일, `bandWidth`가 29개 파일에 나온다 |
| 한 애그리게이트의 저장소 수 | Strategy 하나에 포트 6, 어댑터 5, JpaRepository 6 |

줄 수보다 **타입과 파일의 개수**가 문제다. 필드 하나를 추가하면 5~6개 타입과 변환 4곳을 고친다.

JPA를 어떻게 쓰는지도 확인했다.

| JPA 기능 | 사용 횟수 |
|---|---|
| 연관 매핑(`@ManyToOne`/`@OneToMany`) | 각 1 |
| `@Embedded`/`@Embeddable`, `@Version`, `@ElementCollection` | 0 |
| `@Query` | 101 (그중 native 55) |
| `@Modifying` | 52 |
| `@SQLRestriction`(소프트 삭제) | 29 |
| `@Convert`(AES 암호화) | 8 |

객체 그래프, 지연 로딩, 변경 감지를 거의 쓰지 않는다. JPA를 "행 매퍼 + 명시적 save"로 쓰고 있다. 도메인을 영속 기술에서 분리하려고 Entity와 record를 따로 두었지만, 분리로 보호받는 ORM 의존이 실제로는 거의 없다.

## 2. 목표 구조

**원칙: 와이어 타입, 도메인 타입, 저장 타입은 각각 필요한 곳에만 한 번 선언한다. 필드가 같은 사본을 만들지 않는다.**

### 2-1. 조회 경로: View record 하나

조회(GET)는 도메인 애그리게이트를 거치지 않는다. 쿼리가 **View record를 직접 만들고 그 record가 곧 응답**이다.

```
지금: JpaRepository → Entity → toDomain → domain record → Response.from → Response record
목표: JpaRepository(@Query, DTO 프로젝션) → OrderView record ──→ JSON
```

- 가공이 없는 조회는 쿼리의 생성자 프로젝션(`select new ...OrderView(...)` 또는 인터페이스 프로젝션)이 View를 만든다.
- 가공이 있는 조회(금액 반올림, 여러 소스 조립)는 서비스가 결과 record를 만들고 그것이 응답이다. `XxxResult`와 `XxxResponse`를 따로 두지 않는다.
- View·결과 record는 모듈의 `api` 패키지에 둔다(지침서 05). OpenAPI 설명(`@Schema`)과 `@Nullable`은 이 record에 붙인다.
- 관리자 화면이 같은 데이터를 다른 필드로 보면 View를 하나 더 만든다(`AdminTradeView`). 이건 사본이 아니라 다른 모양이다.

### 2-2. 변경 경로: Command를 직접 바인딩

- 컨트롤러는 `@RequestBody`로 **Command record를 직접** 받는다. 경로 변수와 `userId`는 별도 인자로 서비스에 넘긴다. Request record와 `toCommand()`를 두는 것은 와이어 모양이 Command와 실제로 다를 때뿐이다.
- 입력 형식 검증 애너테이션(`jakarta.validation`)은 Command에 붙인다. 업무 규칙은 도메인이 검증한다.
- 변경 응답은 2-1의 View를 다시 조회해 돌려주거나 식별자만 돌려준다.

### 2-3. 저장: 애그리게이트당 Store 하나

- 저장소는 **테이블이 아니라 애그리게이트 단위**다. `StrategyStore` 하나가 `strategy`·`strategy_version`·전략별 상세 테이블을 함께 읽고 쓴다. `StrategyInfiniteDetailPort`·`StrategyVrDetailPort`·`StrategyDcaDetailPort`와 그 어댑터는 사라진다.
- Store는 지침서 01의 형태다(인터페이스 없는 구체 클래스, 공개 시그니처는 도메인 타입과 View만). Entity와 JpaRepository는 package-private으로 Store 뒤에 숨는다.
- 도메인 record ↔ Entity 변환은 쓰기 경로의 애그리게이트에만 남는다. 읽기 경로에서는 2-1 때문에 사라진다.
- 목표 애그리게이트(trading 기준): `Strategy`(+버전·전략별 상세), `StrategyCycle`(+VR 상세·자본 이벤트), `CyclePosition`(+INFINITE 상세), `Order`, `DcaExecution`. Store 5개.

### 2-4. JPA는 유지한다

Spring Data JDBC로 바꾸면 record를 그대로 저장해 Entity 44개와 변환을 없앨 수 있다. 1절의 사용 패턴(연관 매핑 없음, native 쿼리 다수)만 보면 잘 맞는다. 그래도 지금은 기각한다.

- `@SQLRestriction`에 기대는 소프트 삭제가 29곳이다. JDBC에는 대응 기능이 없어 모든 쿼리와 `findById`에 `deleted_at IS NULL`을 손으로 넣어야 한다. 계좌·사용자·원장에서 하나를 빠뜨리면 삭제된 행이 살아난다.
- JPQL 46개를 SQL로 다시 써야 하고 원장 영속 전부를 건드린다.
- 2-1~2-3만으로 삼중 선언의 대부분이 사라진다. Entity는 Store 뒤의 필드 목록으로만 남아 유지 비용이 작다.

재검토 조건: 소프트 삭제를 DB 뷰나 보관 테이블 방식으로 바꾸게 되면 그때 다시 본다.

### 2-5. 규칙 문서 개정

루트 `CLAUDE.md`의 "응답: 도메인 객체 직접 반환 금지 → 전용 Response DTO (`from()` 팩토리)"를 다음으로 바꾼다.

> 응답 타입은 조회 쿼리나 서비스가 만든 View·결과 record다. 행위를 가진 도메인 애그리게이트(`Strategy`, `Order`, `Account` 등)는 직접 반환하지 않는다. 필드가 같은 Response 사본과 `from()`은 만들지 않는다.

와이어 계약의 보호는 사본이 아니라 지침서 10 T1의 스펙 가드 테스트가 맡는다.

### 기각한 대안

- **Entity를 도메인 모델로 쓴다**(record를 없앰): 매매 계산 커널(`matching`)의 불변 record와 순수성을 잃는다.
- **CRUD 모듈(가계부)만 Entity를 직접 쓰고 매매 모듈은 지금대로**: 모듈마다 방식이 달라진다. 2-1~2-3은 전 모듈에 같은 규칙으로 적용된다.
- **MapStruct 등 매퍼 생성기**: 사본을 그대로 두고 변환 코드만 숨긴다. 타입 개수가 줄지 않는다.
- **도메인 record를 그대로 응답으로**: 응답에 필요 없는 필드와 내부 식별자가 새고, `@Schema` 설명이 도메인에 붙는다.

## 3. 재심한 과거 결정

| 결정 | 재심 결과 |
|---|---|
| RESTful 원칙 "전용 Response DTO + `from()`" | **개정**(2-5) |
| C-28 응답 DTO의 enum 표현 혼재(String 대 enum) 유지 | View로 옮기면서 enum으로 통일한다. JSON은 같다 |
| S-09 `CycleHistoryPageResponse`/`CycleHistoryResponse` 이중 복제 유지 | View 1개로 합친다. 두 엔드포인트가 같은 View를 반환한다(지침서 05에서 프로세스를 합치면 사유도 소멸) |
| C-27 `OrderPort.findByIdOrThrow` default 부재 유지 | Store가 구체 클래스가 되므로 `findByIdOrThrow`를 전 Store에 일반 메서드로 통일한다 |
| `constraints.md` "persistence 매퍼는 필드 복사만", "web DTO의 `toCommand()`도 기본값을 정하지 않는다" | 유지. Command 직접 바인딩에도 같은 규칙(생략 필드는 null) |
| `constraints.md` "포트의 파라미터·반환 타입은 `domain/model` 하위" | View는 `api` 패키지로. 문구 개정 |

## 4. 예상 감량과 위험

- Response 사본과 `from()`: 약 1,000~1,500줄
- Request 사본과 `toCommand()`: 약 300~500줄
- 테이블 단위 포트·어댑터 → 애그리게이트 Store: 약 800~1,200줄, 파일 약 40개
- 읽기 경로의 `toDomain`: 약 150~250줄
- 합 **약 2,300~3,400줄(추정), 파일 100개 이상.** 지침서 01의 포트 인터페이스 감량과 일부 겹친다.

위험:

1. **와이어 변경 사고**: View 필드 이름·순서·null 처리를 사본과 다르게 만들면 응답이 바뀐다. 선행 조건으로 스펙 스냅샷을 고정한다(5절).
2. **조회에서 도메인 불변식 우회**: View 쿼리는 도메인 생성자의 검증을 타지 않는다. 조회는 저장된 값을 그대로 보여주는 것이므로 문제가 아니지만, 계산 필드(`currentRound` 등)는 쿼리가 아니라 서비스가 도메인 메서드로 만든다.
3. **소프트 삭제**: JPQL 프로젝션은 `@SQLRestriction`이 적용되지만 native 쿼리는 적용되지 않는다(`constraints.md` 기존 규칙). View 쿼리는 JPQL을 기본으로 하고, native가 필요하면 `deleted_at IS NULL`을 명시하고 테스트로 확인한다.
4. **금액 반올림 누락**: 지금은 `XxxResponse` 조립 서비스가 `Currency.round`를 건다. View 직행으로 바꿀 때 반올림이 필요한 금액 필드는 서비스 조립 경로에 남긴다.

## 5. 선행 조건

1. 지침서 10 T1·T2(스펙 required, BigDecimal 형식)를 먼저 끝낸다.
2. **OpenAPI 스펙 스냅샷 테스트**를 추가한다: 생성된 스펙 JSON을 파일로 고정하고 차이가 나면 실패. 이 지침서의 모든 태스크는 "스냅샷 불변"이 완료 조건이다(의도한 변경은 스냅샷을 갱신하고 `cross-repo.md`에 적는다).
3. 지침서 04(브로커 삭제)를 먼저 끝낸다. 삭제될 코드를 옮기지 않는다.

## 6. 태스크

공통 완료 판정: 컴파일, 해당 모듈 web·persistence 테스트, 스펙 스냅샷 불변, 아키텍처 테스트.

| # | 태스크 | 모델 |
|---|---|---|
| T1 | 스펙 스냅샷 테스트 추가(root, trading-core 각각). 기준 스냅샷 커밋 | Sonnet |
| T2 | **시범 모듈 `marketcalendar`**(493줄)에 2-1~2-3을 적용하고 전후 파일·줄 수를 잰다. 결과로 규칙을 `constraints.md`에 확정 | Opus |
| T3 | 가계부(`finance`): Response 10개·Request 9개·영속 스택 9벌. 필드 100% 일치 사본부터 | Sonnet, 묶음별 |
| T4 | `account`, `user`, `benchmark`, `market`, `admin`(관리자 View는 데이터 소유 모듈로 — 지침서 05와 조율) | Sonnet |
| T5 | `trading`의 조회 경로: 주문·사이클 이력·전략 목록을 View로. `StrategyDetailAssembler`(190줄, 단건·배치 이중 구현)는 지침서 03에서 다룬다 | Opus(View 설계), Sonnet(적용) |
| T6 | `trading`의 저장: 테이블 단위 포트·어댑터를 애그리게이트 Store 5개로 합친다. **쓰기 경로의 SQL과 트랜잭션 경계는 바꾸지 않는다.** persistence 테스트(`*DbTest`)가 그대로 통과해야 한다 | Opus |
| T7 | `stats`, `privacy` | Sonnet |
| T8 | 동명 사본 정리(`findings/02` A-3): `CycleHistory*`, `InvestmentPoint`, `MarketSessionResponse`, Alpaca 설정 3벌 등 | Sonnet |
| T9 | 가드 추가: ① `*Response` record에 `static from(` 금지가 아니라, **필드 집합이 다른 record와 같은 record 금지**(리플렉션으로 같은 모듈 안 record의 컴포넌트 이름·타입 집합을 비교, 허용 목록 포함) ② 컨트롤러가 Entity·도메인 애그리게이트를 반환하지 않음 | Opus |

모듈 하나가 끝날 때마다 커밋한다. T5·T6은 매매 원장을 건드리므로 리뷰어를 Opus로 둔다.

## 7. 하지 말 것

- 쓰기 경로의 쿼리·잠금(`@Lock` 2곳)·트랜잭션 전파를 바꾸지 않는다.
- JSON 필드 이름을 바꾸지 않는다.
- View에 계산 로직을 넣지 않는다(record의 compact constructor 검증·파생 메서드 금지).
- 한 태스크에서 조회 경로와 저장 경로를 함께 바꾸지 않는다.
- Entity의 컬럼 매핑을 바꾸지 않는다(`ddl-auto: validate`).

## 8. 다른 지침서와의 관계

- 01: Store의 형태(인터페이스 없는 구체 클래스)는 01이 정한다. 01의 단계 C(단일 구현 포트 제거)와 이 지침서 T6을 **같은 작업으로 묶는다**(같은 파일).
- 03: 전략별 상세 테이블의 저장 통합(2-3)은 03의 전략 축 설계와 맞물린다. 03이 전략별 상세의 도메인 모양을 정하고 이 지침서가 저장 형태를 정한다.
- 05: View·결과 record의 위치(`api` 패키지), 관리자 View의 소유 모듈.
- 10: T1·T2 선행, 목록 응답은 `Items<View>`·`CursorPage<View>`.
- kista-ui: 스냅샷이 불변이면 영향 없음. 의도한 스키마 이름 변경(`XxxResponse` → `XxxView`)은 UI 생성 타입 이름을 바꾼다. **스키마 이름은 `@Schema(name=...)`으로 기존 이름을 유지한다**(UI 변경 0).
