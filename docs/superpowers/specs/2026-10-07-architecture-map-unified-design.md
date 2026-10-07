# 아키텍처 맵 통합 설계 (modules.html + process.html → 단일 페이지)

## 목적·전제

- **용도**: 본인 설계·리뷰. "이 흐름이 어느 모듈을 거치나", "이 모듈을 바꾸면 어떤 흐름이 영향받나"를 빠르게 오간다.
- **성공 기준**: 어느 뷰에서든 한 번의 클릭으로 관련 뷰로 이동한다. URL 해시만으로 특정 상태를 다시 연다(리뷰 메모 링크). 흐름을 모듈 구조 위에서 재생한다.
- **원칙 — 수기 콘텐츠 0**: 새로 손으로 쓰는 콘텐츠(투어·용어집·해설 문구)는 없다. 기존 `flows.yml`은 그대로 쓰고, 추가 기능은 전부 classpath 또는 기존 데이터에서 자동 도출한다.
- **제약**: 테스트가 생성하는 정적 파일이고 `file://`로 연다 — `fetch` 불가, 상대경로 `<script src>`/`<link href>`는 가능. 외부 라이브러리는 cdnjs(cytoscape)만 쓴다. 번들러는 쓰지 않는다.
- **우선순위**: 정보 밀도·이동 속도 > 친절한 설명. 모바일은 최소 대응(현행 900px 브레이크포인트 수준).

## 1. 범위·데이터 모델

### 산출물 위치
- 출력: `build/architecture-map/` (`index.html` + 정적 파일 + 생성 `data.js`). `build/spring-modulith-docs/`는 Modulith Documenter(puml) 전용으로 남긴다.
- `modules.html`·`process.html`은 폐지한다.
- build 하위 유지 근거: 생성물이라 항상 최신이고 git 노이즈가 없다. docs/ 커밋은 대용량 JSON diff와 드리프트 때문에 기각했다. CI 배포는 필요해지면 추가한다.

### 원본 배치 (src/test 유지)
- 생성 Java는 src/test에만 둘 수 있다 — 세 서브프로젝트 클래스를 동시에 보는 곳은 루트 테스트 classpath뿐이고(`testImplementation(project(":trading-core"))`), src/main에 두면 `app.jar`에 실린다. 생성이 곧 flows.yml 검증 단언이라 테스트 위치가 의미상으로도 맞다.
- 정적 파일은 `src/test/resources`에 둔다 — `test` 태스크 입력으로 자동 추적돼 CSS·JS만 고쳐도 재실행된다(docs/로 옮기면 `UP-TO-DATE`로 건너뛰어져 낡은 페이지를 보게 된다).

```
src/test/resources/architecture/
├── flows.yml                  # 기존 그대로
└── map/                       # 정적 원본 — 그대로 복사
    ├── index.html             # 셸: 헤더·탭·패널 골격, <script> 순서 나열
    ├── style.css              # 공통 토큰 (현재 두 템플릿에 복제된 것 통합)
    ├── core.js                # 라우터·공유 상태·교차 색인·통합 검색
    └── views/
        ├── structure.js       # 모듈 그래프 + 흐름 오버레이
        ├── landscape.js
        ├── flow.js
        ├── timeline.js
        └── lifecycle.js
```

`data.js` 형식: `window.DATA = { modules: [...], map: {...}, jobs: [...] };` — 기존 `/*__DATA__*/` 치환과 `</script>` 이스케이프는 사라진다.

### 자동 도출 데이터
classpath가 필요한 것만 Java에서, 나머지는 JS(`core.js`)에서 계산한다.

| 도출 항목 | 위치 | 방법 |
|---|---|---|
| 단계 → 모듈 | Java | 단계 `code:`의 클래스(이미 `resolve()`로 검증) 패키지에서 모듈명 추출 → `step.modules[]` |
| 모듈 → 프로세스 | Java | 기존 `ModuleGraphExporter.project()` 재사용 |
| 모듈 → 흐름·단계 | JS | `step.modules` 역색인 |
| 잡 → 흐름 단계 | JS | `job.name`(`Class#method`)이 단계 `code` 항목과 같으면 연결 |
| 단계 → 생명주기 전이 | JS | `transition.flow`(`flowId/stepId`) 역색인 |
| 흐름 → 터치 레인·모듈 | JS | 단계 `lane`·`to`·`modules` 합집합 |

모듈명 추출 규칙은 기존 `jobs()`와 같다: `com.kista.<module>.…`의 첫 세그먼트.

### 제외
- 투어·용어집·단계별 해설(수기 콘텐츠).
- 프로세스 간 HTTP·Redis 통신 엣지 자동 추출 — 가능 여부 불확실, 필요 시 별도 스파이크.

## 2. 화면 구성·라우팅·뷰 간 이동

### 레이아웃
- 헤더: 제목 · 탭 5개(**구조** · **랜드스케이프** · **흐름** · **타임라인** · **생명주기**) · 통합 검색(`/` 단축키) · 라이트/다크 토글.
- 본문 + 오른쪽 상세 패널(440px, 접기 가능) 2분할 유지.

### 라우팅 — 해시 하나가 전체 상태
```
#structure
#structure/trading                              모듈 포커스
#structure/trading?flow=close-batch&step=lock   오버레이 재생 위치
#landscape
#flow/close-batch/lock
#timeline/TradingCloseScheduler#run
#lifecycle/order/3
```
(잡 이름의 `#`는 해시 안에서 `encodeURIComponent` 처리)

생명주기 탭은 정의가 없으면 숨기는 현행 동작을 유지한다.

### 공유 선택 상태
- 흐름 단계 선택 중 **구조** 탭으로 가면 그 단계 모듈이 포커스되고 오버레이가 그 단계에 멈춘 상태로 열린다.
- 모듈 선택 중 **흐름** 탭으로 가면 그 모듈을 거치는 단계만 강조하고 나머지는 흐리게 한다.

### 교차 링크 — 패널 하단 "연결" 섹션 자동 생성

| 보고 있는 것 | 자동 연결 |
|---|---|
| 모듈 | 거치는 흐름·단계 목록, 이 모듈 소속 잡 |
| 흐름 단계 | 소속 모듈 칩, 일으키는 생명주기 전이, 시작 잡 |
| 잡 | 시작하는 흐름 단계 |
| 생명주기 전이 | 흐름 단계(현행 유지) |
| 랜드스케이프 카드 | 터치 레인·모듈 스파크라인 |

### 통합 검색
모듈·흐름·단계 제목·`code` 클래스명·잡·상태 enum을 한 목록에서 찾는다. 결과를 선택하면 해당 해시로 이동한다.

## 3. 오버레이·모션·시각 개선

### 3.1 흐름 오버레이 (구조 탭)
- 구조 탭 헤더의 "흐름 오버레이" 선택 → 재생 바(◀ ▶ ■ + 단계 슬라이더).
- 각 단계의 `step.modules`를 노드 하이라이트. 연속 단계의 모듈 사이 **경로 엣지**: 기존 의존 엣지가 있으면 강조, 없으면 점선 가상 엣지(프로세스 경계 호출이 대개 여기서 드러난다).
- 재생: 경로를 따라 점 하나가 이동(Cytoscape `animate` 위치 보간), 지나간 경로는 옅은 잔상으로 남긴다.
- 하단 미니 스트립에 단계 제목 나열, 클릭 시 점프 — 흐름 탭과 선택 상태 공유.
- 모듈 없는 단계(외부 레인만 — 사용자·fida 등)는 스트립에만 표시하고 그래프에서는 건너뛴다.

### 3.2 구조 탭 그래프
- **점진적 공개**: 처음엔 프로세스 박스(`:api`·`:trading-core`·`:shared`) 접힘 + 박스 간 의존 합계 엣지. 박스 클릭 시 모듈로 펼침. "모두 펼치기" 버튼. 오버레이 재생·모듈 딥링크 진입 시에는 자동으로 펼친다.
- **결정적 배치 유지** — 리뷰 시 위치 기억을 깨지 않도록 force 레이아웃은 쓰지 않는다. 배치는 `:api` | `:shared` | `:trading-core` 세 열이고, 열마다 의존 많은 순 카드 그리드다(랜드스케이프 카드 톤). 카드는 이름과 나감·들어옴 수 2줄을 담는다. 접힌 박스엔 소속 모듈 목록을 보인다.
- 엣지는 기본 아주 옅게 그리고, hover·선택·오버레이 때만 진하게 한다. 엣지는 카드 밑으로 지나가며, 직선 경로에 다른 카드가 걸리면 호로 휜다. 모듈을 선택하면 그 모듈과 이웃으로 확대한다(최대 1.15배).
- hover 시 연결 엣지에 흐르는 dash(방향 표시), 노드 크기에 의존 수 약하게 반영.
- 현행 기능 유지: 의존 종류 필터, platform·sharedkernel 숨김, 클래스 쌍 상세 패널, 의존 순위 개요.

### 3.3 흐름 탭(스윔레인)
- 호출선 화살표 마커. 재생 중 현재 단계 순서선·호출선 위로 점이 흐름(SVG `stroke-dashoffset`).
- 공유 상태에 따른 모듈 필터 강조(2장).

### 3.4 타임라인 탭 — 원형 24h 시계
- 원형 다이얼 기본, 선형 보기 토글. 22:30 개장 → 04:30 마감이 자정을 넘어 한 호로 이어지고 매매 구간을 호 띠로 칠한다.
- 프로세스별 동심원 링(kista-trading 안쪽, kista-scheduler 바깥), 현재 KST 바늘.
- 주기 실행 잡(cron 없음)은 링 전체 점선.

### 3.5 랜드스케이프 탭
- "사용자 여정" 그룹은 노선도형(가로선 위 원형 역), 나머지 그룹은 카드 그리드 유지.
- 카드·역마다 레인 10칸 스파크라인(터치 레인 채색) + 모듈 수 배지.
- 환승역 표현은 제외(레이아웃 알고리즘 필요).

### 3.6 공통
- `style.css` 단일 토큰. 라이트/다크는 시스템 설정 기본, `data-theme` 수동 토글로 덮어쓰고 localStorage에 기억(try/catch).
- `prefers-reduced-motion`이면 흐르는 점·dash를 끄고 정적 하이라이트로 대체.
- 뷰 전환 150ms 페이드 수준.

### 제외
3D, 물리 시뮬레이션 레이아웃, 노선도 환승 계산.

## 4. 생성 테스트 이전·검증·문서

### 테스트
- `ModulithArchitectureTest`: `verify()` + puml 생성만. `ModuleGraphExporter.write` 호출과 `withoutClean()` 제거(폴더를 공유하지 않으므로).
- `ProcessMapTest` → `ArchitectureMapTest` 개명. 기존 flows 검증·잡 회귀 가드 유지 + `ApplicationModules.of(KistaApplication.class)` 추가. 모듈 분석이 두 테스트에서 중복되는 몇 초는 공유 캐시보다 단순해서 감수한다.

### 익스포터
- `ModuleGraphExporter`: `write()` 제거, `graph(modules)` 데이터 조립 전용.
- `ProcessMapExporter` → `ArchitectureMapExporter` 개명: flows 로드·검증·잡 수집 + `step.modules` 도출 + `data.js` 쓰기 + `map/` 정적 파일 복사.
- 정적 파일은 classpath 디렉토리를 순회해 복사한다(파일 목록 하드코딩 없음 — 뷰 파일을 추가해도 Java 수정 불필요).
- 출력 폴더는 매번 비우고 새로 쓴다(삭제된 뷰 파일 잔존 방지).
- 옛 템플릿 `module-graph.html`·`process-map.html` 삭제.

### 새 검증 단언 (ArchitectureMapTest)
1. `code:`가 있는 단계의 클래스는 전부 모듈에 속한다 — 모듈 밖이면 위반.
2. 오버레이 회귀 가드: `close-batch` 흐름 `lock` 단계의 모듈에 `trading` 포함.
3. 출력에 `index.html`·`data.js` 존재.

JS 로직은 자동 테스트를 두지 않는다(테스트 인프라 없음). 구현 완료 후 브라우저 1회 확인: 탭 5개, 딥링크 왕복, 오버레이 재생, 다크모드.

### 문서 갱신
- `docs/architecture-map.md` "L2 모듈 의존 그래프"·"업무 흐름 맵" 절: 생성 테스트명, 출력 경로(`build/architecture-map/index.html`), 단일 페이지 설명, 생성기·원본 경로.
- `CLAUDE.md` "참고 문서"의 `modules.html`/`process.html` 언급 → `build/architecture-map/`.
- `flows.yml` 머리 주석의 `process.html`·`ProcessMapTest` 언급.
- `.github/tests/architecture-map.bats`는 `docs/architecture-map.md`의 "L0-1" 절만 대조하므로 영향 없음(확인함).

### 커밋 단위 (각각 빌드 통과)
1. 익스포터·테스트 재배치 + 정적 파일 분할 — 기존 두 화면을 기능 그대로 한 페이지에 이식(동작 변화 없음).
2. 교차 색인·라우팅·공유 상태·통합 검색.
3. 흐름 오버레이·점진적 공개.
4. 시각 개선(원형 타임라인·노선도·모션·테마) + 문서 갱신.
