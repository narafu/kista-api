# 아키텍처 맵 통합 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `modules.html`(모듈 그래프)과 `process.html`(업무 흐름 맵)을 `build/architecture-map/` 단일 페이지로 합치고, 교차 색인·딥링크·흐름 오버레이·시각 개선을 수기 콘텐츠 없이 자동 도출 데이터로 붙인다.

**Architecture:** 생성은 루트 테스트(`ArchitectureMapTest`)가 맡는다 — Modulith 모듈 그래프 + flows.yml + `@Scheduled` 잡을 `data.js`(`window.DATA = {...}`)로 쓰고, `src/test/resources/architecture/map/` 정적 파일(셸·CSS·core.js·뷰 5개)을 그대로 복사한다. 브라우저 쪽은 classic `<script>` 여러 개 — `core.js`만 전역 헬퍼·라우터를 갖고, 뷰 파일은 IIFE로 감싸 `VIEWS[이름]`에 등록한다. URL 해시 하나가 전체 상태다.

**Tech Stack:** Java 21, ArchUnit, Spring Modulith 2.1 (`ApplicationModules`), SnakeYAML, Jackson 3(`tools.jackson`), 바닐라 JS(ES2023, 번들러 없음), Cytoscape.js 3.30.2(cdnjs), SVG.

**Spec:** `docs/superpowers/specs/2026-10-07-architecture-map-unified-design.md`

## Global Constraints

- 수기 콘텐츠 0 — 투어·용어집·해설 문구 추가 금지. `flows.yml` 스키마·내용은 바꾸지 않는다(머리 주석의 파일명 언급만 갱신).
- `step.modules`는 flows.yml 키가 아니다 — Java가 출력 전용(`DATA.stepModules`)으로 내고 `core.js`가 붙인다(`Step` record에 컴포넌트를 추가하면 yaml에 손으로 써도 통과돼 원칙이 뚫린다).
- `file://`로 연다 — `fetch` 금지, 상대경로 `<script src>`/`<link href>`만. 외부 라이브러리는 cdnjs cytoscape만. 번들러 없음.
- 출력: `build/architecture-map/`(`index.html` + 정적 파일 + 생성 `data.js`). 매번 비우고 새로 쓴다. `build/spring-modulith-docs/`는 Documenter(puml) 전용.
- 생성 Java·정적 원본은 `src/test`에 둔다(`src/main`이면 `app.jar`에 실린다).
- 정적 파일 복사는 classpath 디렉토리 순회 — 파일 목록 하드코딩 금지.
- 구조 그래프는 결정적 배치(영역별 concentric) 유지, force 레이아웃 금지.
- 모바일은 현행 900px 브레이크포인트 수준만.
- 주석 규칙: `//` 인라인만, Javadoc·블록 주석 금지(JS 섹션 구분용 `/* ---------- */`는 기존 관례라 유지).
- 테스트는 좁혀서: `bash gradlew :test --tests 'com.kista.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"`. 전체 스위트는 마지막 1회. 브라우저 확인도 마지막 1회.
- 커밋 메시지 한글 + Conventional Commit, author `narafu <narafu@kakao.com>`, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 커밋 전 리뷰어 검수(전역 규칙).

## Review Focus

1. **숨긴 컨테이너에서 Cytoscape 초기화** — `#flow/...`로 딥링크해 들어온 뒤 구조 탭으로 전환해도 그래프가 0×0으로 찌그러지지 않고 전체가 보여야 한다. → Task 1: cy를 첫 `show()`에서 생성, 이후 `show()`마다 `cy.resize()`.
2. **오버레이 대상이 숨김 모듈** — `close-batch/lock`의 모듈은 `[platform, trading]`이고 platform은 기본 숨김이다. 숨김·필터 밖 모듈은 그래프에서 건너뛰고(스트립·패널 칩에는 남김) 나머지로 경로를 그린다. → Task 3 `stepNodes()`.
3. **존재하지 않는 대상으로 딥링크** — 없는 모듈·흐름·단계·잡·전이 번호 해시는 해당 탭의 기본 화면으로 떨어져야 하고 예외로 페이지가 죽으면 안 된다. → Task 2 각 `show()`의 fallback 분기.
4. **잡 이름의 `#` 왕복** — `#timeline/TradingCloseScheduler%23run`이 잡 패널을 열고, 잡 클릭이 같은 해시를 만든다. → Task 2 `hash()`/`parseHash()`(segment별 `encodeURIComponent`/`decodeURIComponent`, `?` 뒤는 `URLSearchParams`).
5. **테마 수동 토글 후 그래프 색** — cytoscape 스타일·엣지 색은 생성 시점 `css()` 값으로 박힌다. 토글 시 `cy.style(styles())` + 재렌더 없으면 다크 페이지에 라이트 그래프가 남는다. → Task 4 `themechange` 핸들러.

JS 자동 테스트 인프라가 없으므로 위 항목은 각 태스크의 `node --check` 게이트 + Task 4 마지막 브라우저 체크리스트로 확인한다.

---

## File Structure

```
src/test/java/com/kista/architecture/
├── ModuleGraphExporter.java        수정 — write() 제거, graph(modules) 데이터 조립 전용
├── ArchitectureMapExporter.java    개명(← ProcessMapExporter) — flows 로드·검증·잡·stepModules·data.js·정적 복사
├── ArchitectureMapTest.java        개명(← ProcessMapTest) — 검증 단언 + 생성
└── ModulithArchitectureTest.java   수정 — verify + puml만
src/test/resources/architecture/
├── flows.yml                       머리 주석만 수정 (Task 4)
├── module-graph.html               삭제 (Task 1)
├── process-map.html                삭제 (Task 1)
└── map/
    ├── index.html                  셸: 헤더·탭·섹션 골격·패널·script 순서
    ├── style.css                   두 템플릿 CSS 통합(충돌 셀렉터 리네임)
    ├── core.js                     헬퍼·라우터(①) → 교차 색인·공유 상태·검색·단계 패널(②) → 테마(④)
    └── views/
        ├── structure.js            모듈 그래프(①) → 해시 연동·연결(②) → 오버레이·점진적 공개(③) → 테마·hover dash(④)
        ├── landscape.js            랜드스케이프(①) → 노선도·스파크라인(④)
        ├── flow.js                 스윔레인(①) → 공유 상태·연결(②) → 화살표·흐르는 점(④)
        ├── timeline.js             선형 타임라인(①) → 잡 딥링크·연결(②) → 원형 시계(④)
        └── lifecycle.js            생명주기(①) → 연결(②)
docs/architecture-map.md, CLAUDE.md  문서 (Task 4)
```

### JS 공용 인터페이스 (모든 태스크가 따른다)

- 뷰 등록: `VIEWS.<name> = { show(rest, params), leave?(), home?(), enabled? }`
  - `rest: string[]` — 해시 `#view/a/b`의 `['a','b']`(디코드됨), `params: URLSearchParams` — `?` 뒤
  - `leave()` — 라우팅 때마다 **모든** 뷰에 호출(재생 정지 등). 현행 `route()`가 매번 `stop()`을 부르던 동작 보존
  - `home()` — 탭 클릭 시 이동할 해시(`'#...'`). 없으면 `'#' + name`
  - `enabled: false`면 탭·섹션 제거
- `core.js` 전역(①): `$`, `esc`, `css`, `MAP`, `VIEWS`, `setPanel(html)`, `parseHash()`, `route()`
- `core.js` 전역(②): `hash(view, ...parts)`, `IDX`, `SHARED`, `stepLabel(ref)`, `chipLink(href, label)`, `connections(sections)`, `stepPanel(fid, i, hrefOf)`
- `core.js` 전역(④): `REDUCED`, `applyTheme(t)`, window 이벤트 `themechange`

뷰 파일끼리는 서로 참조하지 않는다(전부 `core.js` 경유). 뷰 파일 top-level에 `const`/`function`을 두지 말고 반드시 IIFE 안에 둔다 — classic script는 전역 스코프를 공유해 이름 하나 겹치면 뒤 파일 전체가 SyntaxError로 죽는다.

### JS 게이트 (각 태스크 공통 — 브라우저 대신 매번 실행)

```bash
cd /c/Users/USER/workspace/kista/kista-api
M=src/test/resources/architecture/map
for f in $M/core.js $M/views/*.js; do node --check "$f" || exit 1; done
cat build/architecture-map/data.js $M/core.js $M/views/structure.js $M/views/landscape.js $M/views/flow.js $M/views/timeline.js $M/views/lifecycle.js > "$TMPDIR/all.js" && node --check "$TMPDIR/all.js" && echo JS-OK
```
(`$TMPDIR`가 없으면 scratchpad 경로 사용.) 이어붙인 파일 검사가 파일 간 중복 top-level 선언을 잡는다.

---

### Task 1: 이식 — 익스포터·테스트 재배치 + 정적 파일 분할 (동작 변화 없음)

**Files:**
- Modify: `src/test/java/com/kista/architecture/ModuleGraphExporter.java`
- Rename+Modify: `ProcessMapExporter.java` → `ArchitectureMapExporter.java`
- Rename+Modify: `ProcessMapTest.java` → `ArchitectureMapTest.java`
- Modify: `src/test/java/com/kista/architecture/ModulithArchitectureTest.java`
- Create: `src/test/resources/architecture/map/{index.html,style.css,core.js}`, `map/views/{structure,landscape,flow,timeline,lifecycle}.js`
- Delete: `src/test/resources/architecture/module-graph.html`, `process-map.html`

**Interfaces:**
- Produces (Java): `ModuleGraphExporter.graph(ApplicationModules) → List<ModuleGraphExporter.Module>`, `ArchitectureMapExporter.OUTPUT: Path`(`build/architecture-map`), `ArchitectureMapExporter.write(List<ModuleGraphExporter.Module>, FlowMap, List<Job>)`, `record Data(List<Module> modules, FlowMap map, List<Job> jobs)`
- Produces (JS): 위 "JS 공용 인터페이스"의 ① 항목

- [ ] **Step 1: 테스트 개명 + 새 출력 단언 (실패 테스트)**

```bash
git mv src/test/java/com/kista/architecture/ProcessMapTest.java src/test/java/com/kista/architecture/ArchitectureMapTest.java
git mv src/test/java/com/kista/architecture/ProcessMapExporter.java src/test/java/com/kista/architecture/ArchitectureMapExporter.java
```

`ArchitectureMapTest.java` 전체:

```java
package com.kista.architecture;

import com.kista.KistaApplication;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("아키텍처 맵(build/architecture-map)")
class ArchitectureMapTest {

    @Test
    @DisplayName("flows.yml이 실제 코드·레인·흐름만 참조하고, 아키텍처 맵을 생성한다")
    void flowsReferenceExistingCodeAndExport() {
        // 운영 코드만 — 테스트·testFixtures 클래스가 단순 이름 유일성 판정을 흐리지 않도록 제외
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("testFixtures") && !location.contains("test-fixtures"))
                .importPackages("com.kista");
        var map = ArchitectureMapExporter.load();

        assertThat(ArchitectureMapExporter.violations(map, classes)).isEmpty();

        // 프로세스 판별·KST 시각 계산 회귀 가드 — 마감 배치는 kista-trading에서 04:30
        var jobs = ArchitectureMapExporter.jobs(classes);
        assertThat(jobs).filteredOn(j -> j.name().equals("TradingCloseScheduler#run"))
                .singleElement()
                .satisfies(j -> {
                    assertThat(j.process()).isEqualTo("kista-trading");
                    assertThat(j.times()).containsExactly("04:30");
                });

        var modules = ModuleGraphExporter.graph(ApplicationModules.of(KistaApplication.class));
        ArchitectureMapExporter.write(modules, map, jobs);

        // 셸·생성 데이터·복사된 뷰가 모두 출력에 있어야 한다
        assertThat(ArchitectureMapExporter.OUTPUT.resolve("index.html")).exists();
        assertThat(ArchitectureMapExporter.OUTPUT.resolve("data.js")).content().startsWith("window.DATA = ");
        assertThat(ArchitectureMapExporter.OUTPUT.resolve("views/structure.js")).exists();
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `bash gradlew :compileTestJava 2>&1 | grep -E "error|BUILD"`
Expected: `ProcessMapExporter`/`graph`/`OUTPUT` 관련 `cannot find symbol` — BUILD FAILED

- [ ] **Step 3: `ModuleGraphExporter` — `write()` 제거, `graph()` 추가**

- 클래스 주석: `// Modulith 모듈 그래프를 아키텍처 맵 데이터(DATA.modules)로 조립한다`
- 삭제: `OUTPUT`, `TEMPLATE`, `PLACEHOLDER` 상수, `record Graph`, `write()` 전체, 쓰지 않게 된 import(`JsonMapper`, `IOException`, `InputStream`, `UncheckedIOException`, `StandardCharsets`, `Files`, `Path`)
- 추가:

```java
    static List<Module> graph(ApplicationModules modules) {
        return modules.stream()
                .filter(m -> !EXCLUDED.contains(name(m)))
                .sorted(Comparator.comparing(ModuleGraphExporter::name))
                .map(m -> toModule(m, modules))
                .toList();
    }
```

- [ ] **Step 4: `ArchitectureMapExporter` — 클래스명·출력 교체**

- `ProcessMapExporter` → `ArchitectureMapExporter` 전체 치환(생성자·메서드 참조 포함).
- 클래스 주석: `// 모듈 그래프 + 업무 흐름 맵(flows.yml) + @Scheduled 시간표를 단일 페이지(build/architecture-map/)로 내보낸다`
- 상수 교체: `OUTPUT`·`TEMPLATE`·`PLACEHOLDER` 삭제 후

```java
    static final Path OUTPUT = Path.of("build/architecture-map"); // 생성물 — 매번 비우고 새로 쓴다
    private static final String STATIC = "/architecture/map"; // 정적 원본(셸·CSS·JS) — 그대로 복사
```

- `record Data(FlowMap map, List<Job> jobs)` → `record Data(List<ModuleGraphExporter.Module> modules, FlowMap map, List<Job> jobs)`
- `write(FlowMap, List<Job>)`를 아래로 교체하고 `deleteRecursively` 추가:

```java
    static void write(List<ModuleGraphExporter.Module> modules, FlowMap map, List<Job> jobs) {
        try {
            deleteRecursively(OUTPUT); // 삭제된 뷰 파일이 남지 않도록
            // test 리소스는 jar가 아니라 디렉토리 — 순회 복사라 뷰 파일을 추가해도 Java 수정 불필요
            var source = Path.of(ArchitectureMapExporter.class.getResource(STATIC).toURI());
            try (var paths = Files.walk(source)) {
                for (var path : paths.toList()) {
                    var target = OUTPUT.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(path, target);
                    }
                }
            }
            Files.writeString(OUTPUT.resolve("data.js"),
                    "window.DATA = " + JSON.writeValueAsString(new Data(modules, map, jobs)) + ";\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
```

- import: `java.net.URISyntaxException` 추가, `StandardCharsets` 제거(미사용 시).

- [ ] **Step 5: `ModulithArchitectureTest` — verify + puml만**

```java
    @Test
    @DisplayName("모듈 간 의존이 허용된 방향으로만 존재하고 순환이 없다")
    void verifyModularStructure() {
        var modules = ApplicationModules.of(KistaApplication.class).verify();

        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
```

- [ ] **Step 6: 정적 셸 `map/index.html` 작성**

```html
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>KISTA 아키텍처 맵</title>
<link rel="stylesheet" href="style.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/cytoscape/3.30.2/cytoscape.min.js"
        integrity="sha384-IWROdLKRsN1UuJywMlWl7/blXQ8GEooN2n7dzTxfEPd7ybYIKCUJ2Ol/1Gpf3YV4" crossorigin="anonymous"></script>
</head>
<body>
<header>
  <h1>KISTA 아키텍처 맵</h1>
  <nav class="tabs" role="tablist">
    <button class="tab" role="tab" data-view="structure">구조</button>
    <button class="tab" role="tab" data-view="landscape">랜드스케이프</button>
    <button class="tab" role="tab" data-view="flow">흐름</button>
    <button class="tab" role="tab" data-view="timeline">타임라인</button>
    <button class="tab" role="tab" data-view="lifecycle">생명주기</button>
  </nav>
  <span class="spacer"></span>
</header>
<main>
  <section class="view" id="view-structure" hidden>
    <div class="view-head">
      <h2>모듈 구조<small id="summary"></small></h2>
      <input type="search" class="search" id="mod-search" placeholder="모듈 검색…" aria-label="모듈 검색">
      <div class="filter-group" id="types"><span class="group-label">의존 종류</span></div>
      <label class="chip check"><input type="checkbox" id="hideInfra" checked>platform·sharedkernel 숨김</label>
      <span class="spacer"></span>
      <button class="btn" id="fit">전체 보기</button>
    </div>
    <div id="stage">
      <div id="cy"></div>
      <div class="graph-legend">
        <span><i class="sq" style="border-color:var(--api);background:var(--api-tint)"></i>:api</span>
        <span><i class="sq" style="border-color:var(--tc);background:var(--tc-tint)"></i>:trading-core</span>
        <span><i class="sq" style="border-color:var(--shared);background:var(--shared-tint)"></i>:shared</span>
        <span>엣지 굵기 = 클래스 의존 수</span>
      </div>
    </div>
  </section>
  <!-- 아래 4개 섹션: process-map.html <main> 안의 section 4개를 그대로 옮긴다 (id·마크업 불변) -->
</main>
<aside id="panel"></aside>
<script src="data.js"></script>
<script src="core.js"></script>
<script src="views/structure.js"></script>
<script src="views/landscape.js"></script>
<script src="views/flow.js"></script>
<script src="views/timeline.js"></script>
<script src="views/lifecycle.js"></script>
</body>
</html>
```

주석 자리에 `process-map.html`의 `<section class="view" id="view-landscape">` ~ `id="view-lifecycle"` 섹션 4개를 그대로 붙이고 주석은 지운다. 섹션 제목 문구("프로세스 랜드스케이프" 등)는 그대로 둔다.

- [ ] **Step 7: `map/style.css` 작성 — 통합 + 충돌 셀렉터 리네임**

1. `process-map.html`의 `<style>` 내용(`:root`부터 `@media (max-width: 900px) {...}`까지) 전체를 그대로 옮긴다 — 토큰·body grid(`minmax(0, 1fr) 440px`)·`.chip`(aria-pressed)·`.badge`(11px)·`.legend`(flex 행)·`.p-head h2`(19px)는 이쪽을 기준으로 삼는다.
2. `main { ... }` 규칙에 `position: relative;`를 추가한다.
3. `.p-head h2` 규칙에 `display: flex; align-items: center; gap: 8px; flex-wrap: wrap;`을 추가한다(모듈 패널의 프로젝트 배지 정렬).
4. 파일 끝(미디어쿼리 앞)에 구조 탭 전용 규칙을 추가한다 — module-graph.html에서 온 규칙이며 충돌하던 셀렉터는 리네임했다(`.group`→`.filter-group`, `.legend`→`.graph-legend`, checkbox chip→`.chip.check`):

```css
  /* 구조 탭 — 모듈 그래프 */
  #view-structure:not([hidden]) { position: absolute; inset: 0; display: flex; flex-direction: column; }
  #view-structure .view-head { padding: 10px 16px; margin: 0; background: var(--surface); border-bottom: 1px solid var(--line); }
  #view-structure .view-head h2 small { font-weight: 500; color: var(--muted); margin-left: 6px; font-size: 13px; }
  .search { padding: 6px 10px; width: 180px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-2); color: var(--fg); font: inherit; }
  .search:focus { outline: 2px solid var(--api); outline-offset: -1px; }
  .filter-group { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
  .group-label { font-size: 12px; color: var(--muted); margin-right: 2px; }
  .chip.check { position: relative; user-select: none; }
  .chip.check input { position: absolute; opacity: 0; pointer-events: none; }
  .chip.check:has(input:checked) { color: var(--fg); background: var(--surface-2); border-color: color-mix(in srgb, var(--fg) 25%, transparent); }
  .chip.check:has(input:focus-visible) { outline: 2px solid var(--api); }
  .dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; flex: none; }
  .chip.check:not(:has(input:checked)) .dot { opacity: .3; }
  #stage { position: relative; flex: 1; min-height: 0; }
  #cy { position: absolute; inset: 0; }
  .graph-legend { position: absolute; left: 16px; bottom: 16px; display: flex; gap: 14px; padding: 8px 12px; font-size: 12px; color: var(--muted);
                  background: var(--surface); border: 1px solid var(--line); border-radius: 10px; box-shadow: var(--shadow); }
  .graph-legend span { display: inline-flex; align-items: center; gap: 6px; }
  .graph-legend .sq { display: inline-block; width: 12px; height: 12px; border-radius: 3px; border: 2px solid; }
  .stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 12px; }
  .stat { padding: 8px 10px; background: var(--surface-2); border-radius: 8px; }
  .stat b { display: block; font-size: 18px; font-variant-numeric: tabular-nums; }
  .stat span { font-size: 12px; color: var(--muted); }
  .empty { color: var(--muted); font-size: 13px; margin: 0; }
  details.dep { border: 1px solid var(--line); border-radius: 8px; margin-bottom: 6px; background: var(--surface); }
  details.dep[open] { background: var(--surface-2); }
  details.dep summary { list-style: none; display: flex; align-items: center; gap: 8px; padding: 7px 10px; cursor: pointer; }
  details.dep summary::-webkit-details-marker { display: none; }
  details.dep summary::before { content: "▸"; color: var(--muted); font-size: 11px; transition: transform .15s; }
  details.dep[open] summary::before { transform: rotate(90deg); }
  .mod-link { font-weight: 600; color: var(--fg); text-decoration: none; border-bottom: 1px dashed transparent; }
  .mod-link:hover { border-bottom-color: currentColor; }
  .bar { flex: 1; display: flex; height: 6px; border-radius: 3px; overflow: hidden; background: var(--line); min-width: 40px; }
  .count { font-variant-numeric: tabular-nums; color: var(--muted); font-size: 12px; min-width: 28px; text-align: right; }
  .dep-body { padding: 0 10px 8px 28px; }
  .type-row { margin-top: 6px; font-size: 12px; font-weight: 600; display: flex; align-items: center; gap: 6px; }
  .pairs { margin: 4px 0 0; padding: 0; list-style: none; }
  .pairs li { padding: 2px 0; word-break: break-all; }
  .arrow { color: var(--muted); margin: 0 4px; }
  table.rank { width: 100%; border-collapse: collapse; font-size: 13px; }
  table.rank th { text-align: left; font-weight: 600; color: var(--muted); font-size: 12px; padding: 4px 6px; border-bottom: 1px solid var(--line); }
  table.rank td { padding: 6px; border-bottom: 1px solid var(--line); }
  table.rank .num { text-align: right; font-variant-numeric: tabular-nums; }
  table.rank tbody tr { cursor: pointer; }
  table.rank tbody tr:hover { background: var(--surface-2); }
```

5. 기존 `@media (max-width: 900px)` 블록 안에 추가:

```css
    #view-structure:not([hidden]) { position: relative; height: 70vh; }
    .graph-legend { display: none; }
```

(`#view-structure:not([hidden])`로 쓰는 이유: `#view-structure { display:flex }`는 ID 특이도가 `.view[hidden] { display:none }`을 이겨 숨김이 풀린다.)

- [ ] **Step 8: `map/core.js` 작성**

```js
'use strict';
// 공용 헬퍼·라우터 — 뷰 파일은 IIFE 안에서 VIEWS[이름] = { show(rest, params), leave?, home?, enabled? }를 등록한다
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const css = v => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
const MAP = DATA.map;
const VIEWS = {};
const VIEW_ORDER = ['structure', 'landscape', 'flow', 'timeline', 'lifecycle']; // 첫 항목이 기본 탭

const setPanel = html => { $('panel').innerHTML = html; $('panel').scrollTop = 0; };

// #view/a/b?k=v → { view, rest: ['a','b'], params } — segment별 디코드(잡 이름의 #는 %23으로 온다)
function parseHash() {
  const [path, query = ''] = location.hash.slice(1).split('?');
  const [view, ...rest] = path.split('/').map(decodeURIComponent);
  return { view, rest, params: new URLSearchParams(query) };
}

function route() {
  const { view, rest, params } = parseHash();
  const names = VIEW_ORDER.filter(v => VIEWS[v] && VIEWS[v].enabled !== false);
  const v = names.includes(view) ? view : names[0];
  document.querySelectorAll('.tab').forEach(t => t.setAttribute('aria-selected', t.dataset.view === v));
  document.querySelectorAll('.view').forEach(s => s.hidden = s.id !== 'view-' + v);
  Object.values(VIEWS).forEach(x => x.leave?.());
  VIEWS[v].show(v === view ? rest : [], params);
}

window.addEventListener('DOMContentLoaded', () => {
  // 비활성 뷰(예: 생명주기 정의 없음)는 탭·섹션 제거
  for (const v of VIEW_ORDER) {
    if (VIEWS[v]?.enabled === false) {
      document.querySelector(`.tab[data-view="${v}"]`)?.remove();
      $('view-' + v)?.remove();
    }
  }
  document.querySelectorAll('.tab').forEach(t => t.onclick = () => {
    location.hash = VIEWS[t.dataset.view].home?.() ?? '#' + t.dataset.view;
  });
  window.addEventListener('hashchange', route);
  route();
});
```

(뷰 스크립트는 body 끝에서 DOMContentLoaded 전에 실행되므로 모든 뷰가 등록된 뒤 첫 `route()`가 돈다.)

- [ ] **Step 9: `views/structure.js` 작성**

module-graph.html `<script>`의 `// 의존 종류별 표시 이름·색 변수`부터 끝(마지막 `render();` 제외)까지를 `(() => { ... })();` 안으로 옮기고 아래를 적용한다.

1. 삭제(core.js가 공급): `const css = ...`, `const esc = ...`, `const $ = ...`.
2. `const cy = cytoscape({...});` → `let cy;` 선언만 위에 두고, cytoscape 생성 + `cy.on(...)` 5개를 `init()` 함수 안으로 옮긴다:

```js
  let cy; // 첫 show()에서 생성 — 숨긴 컨테이너에서 만들면 0×0으로 잡혀 fit이 깨진다
  function init() {
    cy = cytoscape({ /* 기존 옵션·style 배열 그대로 */ });
    // 기존 cy.on('tap', ...)·mouseover·mouseout 핸들러 그대로
    render();
  }
```

3. 검색 입력 id: `$('search')` → `$('mod-search')`.
4. 패널 `[data-mod]` 클릭 핸들러·필터 체크박스·`hideInfra`·`fit`·검색 핸들러는 IIFE top-level에 그대로 둔다. 단 `render`/`clearFocus`/`selectModule`이 `cy`를 쓰므로 `fit`·검색·필터 핸들러 첫 줄에 `if (!cy) return;`을 넣는다.
5. 의존 종류 칩 마크업의 `<label class="chip">` → `<label class="chip check">`.
6. 개요 패널 힌트 문구: `테스트(<code>ModulithArchitectureTest</code>)` → `테스트(<code>ArchitectureMapTest</code>)`.
7. IIFE 끝에 등록:

```js
  VIEWS.structure = {
    show() {
      if (!cy) return init();
      cy.resize(); // 숨김 중 창 크기 변경 반영
      const sel = cy.$('node.selected');
      sel.length ? showModule(sel.id()) : showOverview();
    },
  };
```

- [ ] **Step 10: 나머지 뷰 4개 작성 — process-map.html 스크립트 분할**

공통: 각 파일은 `(() => { ... })();`. `const DATA`, `$`, `esc`, `MAP`은 core.js 것을 쓰므로 옮기지 않는다. 구역은 process-map.html의 `/* ---------- N. ... ---------- */` 표식 기준이다.

**`views/landscape.js`** — `/* ---------- 1. 랜드스케이프 ---------- */`부터 `/* ---------- 2. 스윔레인` 직전까지. `panelLandscape()`의 원본 안내 문구 `<code>ProcessMapTest</code>` → `<code>ArchitectureMapTest</code>`. 끝에:

```js
  VIEWS.landscape = { show: () => panelLandscape() };
```

**`views/flow.js`** — 맨 위에 `const laneIndex`·`const laneLabel` 두 줄과 `let currentFlow = null, selected = -1, timer = null;`, 이어서 `/* ---------- 2. 스윔레인 ---------- */`부터 `/* ---------- 3. 하루 타임라인` 직전까지. 끝에 원본 `route()`의 flow 분기를 옮긴다:

```js
  VIEWS.flow = {
    show([flowId, stepId]) {
      const id = Object.hasOwn(MAP.flows, flowId ?? '') ? flowId : Object.keys(MAP.flows)[0];
      if (id !== currentFlow) renderFlow(id); else requestAnimationFrame(drawLinks); // 숨김 중 리사이즈 반영
      select(MAP.flows[id].steps.findIndex(s => s.id === stepId));
    },
    leave: stop,
    home: () => '#flow/' + (currentFlow ?? Object.keys(MAP.flows)[0]),
  };
```

**`views/timeline.js`** — `const PROC_COLOR`·`const procColor` 두 줄, 이어서 `/* ---------- 3. 하루 타임라인 ---------- */`부터 `/* ---------- 4. 상태 생명주기` 직전까지. 끝에:

```js
  renderTimeline();
  VIEWS.timeline = { show: () => panelTimeline() };
```

**`views/lifecycle.js`** — `let currentLc = null, selectedT = -1;`, 이어서 `/* ---------- 4. 상태 생명주기 ... */`부터 스크립트 끝 `renderTimeline(); route();` 직전까지. 단 첫 줄 `if (!Object.keys(MAP.lifecycles).length) document.querySelector(...).remove();`는 삭제(core가 `enabled`로 처리). 끝에:

```js
  VIEWS.lifecycle = {
    enabled: Object.keys(MAP.lifecycles).length > 0, // 정의가 없으면 탭 숨김
    show([lcId, t]) {
      const id = Object.hasOwn(MAP.lifecycles, lcId ?? '') ? lcId : Object.keys(MAP.lifecycles)[0];
      if (id !== currentLc) renderLifecycle(id);
      selectTransition(/^\d+$/.test(t ?? '') ? +t : -1);
    },
    home: () => '#lifecycle/' + (currentLc ?? Object.keys(MAP.lifecycles)[0]),
  };
```

`enabled`가 false면 이 뷰의 칩 렌더(`$('lc-chips').innerHTML = ...`)는 섹션이 아직 있으니 무해하다.

- [ ] **Step 11: 옛 템플릿 삭제**

```bash
git rm src/test/resources/architecture/module-graph.html src/test/resources/architecture/process-map.html
```

- [ ] **Step 12: 테스트 통과 확인**

Run: `bash gradlew :test --tests 'com.kista.architecture.ArchitectureMapTest' --tests 'com.kista.architecture.ModulithArchitectureTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 13: JS 게이트** — 위 "JS 게이트" 실행. Expected: `JS-OK`

- [ ] **Step 14: 이식 체크리스트 대조(코드 리딩)** — 아래 각 항목이 새 파일에 존재하는지 grep으로 확인한다.
  - 구조: 의존 종류 필터 칩(`data-type`), infra 숨김(`hideInfra`), 클래스 쌍 패널(`depList`·`showEdge`), 의존 순위 개요 표(`showOverview`·행 클릭), 모듈 검색(`mod-search`), 전체 보기(`fit`), 패널 `mod-link` 이동, hover
  - 흐름: 랜드스케이프 카드 → 흐름(`.card.linked`), 흐름 칩, ◀/▶|, ▶ 재생(`play`·`stop`), touch 점·호출선(`drawLinks`), resize 재그리기
  - 타임라인: 현재 시각선(`.now`), 주기 실행 점선(`.periodic`), 잡 패널(`panelJob`)
  - 생명주기: 칩, 전이 표, terminal/pseudo 노드, 생명주기 호(`drawArcs`) + resize, 정의 없으면 탭 제거(`enabled`)
  - 흔적: `grep -rn "modules.html\|process.html\|ProcessMapTest\|ProcessMapExporter" src/test` 결과 0건

- [ ] **Step 15: 리뷰 후 커밋**

```bash
git add -A src/test/java/com/kista/architecture src/test/resources/architecture
git commit -m "$(cat <<'EOF'
refactor(architecture): 모듈 그래프·업무 흐름 맵을 단일 아키텍처 맵 페이지로 이식

build/architecture-map/에 셸·CSS·core.js·뷰 5개 + 생성 data.js로 출력한다.
기능 변화 없음 — 두 화면을 탭으로 합쳤다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 교차 색인·라우팅·공유 상태·통합 검색

**Files:**
- Modify: `ArchitectureMapExporter.java`, `ArchitectureMapTest.java`
- Modify: `map/index.html`, `map/style.css`, `map/core.js`, `map/views/{structure,flow,timeline}.js`

**Interfaces:**
- Consumes: Task 1 전부
- Produces (Java): `ArchitectureMapExporter.stepModules(FlowMap, JavaClasses) → Map<String, Map<String, List<String>>>`(flowId → stepId → 정렬된 모듈명), `moduleViolations(FlowMap, JavaClasses, Set<String>) → List<String>`, `moduleOf(JavaClass) → String`, `write(List<Module>, FlowMap, Map<String, Map<String, List<String>>>, List<Job>)`, `Data(modules, map, stepModules, jobs)`
- Produces (JS): `hash`, `IDX`, `SHARED`, `stepLabel`, `chipLink`, `connections`, `stepPanel` (아래 정의). `step.modules: string[]`가 모든 단계에 붙는다.

- [ ] **Step 1: 실패 단언 추가 (`ArchitectureMapTest`)**

`var modules = ...` 다음, `write` 호출을 아래로 교체:

```java
        // 단계 code: 클래스는 전부 모듈 소속이어야 하고, 오버레이 회귀 가드 — 마감 배치 락 단계는 trading을 거친다
        var moduleNames = modules.stream().map(ModuleGraphExporter.Module::name).collect(Collectors.toSet());
        assertThat(ArchitectureMapExporter.moduleViolations(map, classes, moduleNames)).isEmpty();
        var stepModules = ArchitectureMapExporter.stepModules(map, classes);
        assertThat(stepModules.get("close-batch").get("lock")).contains("trading");

        ArchitectureMapExporter.write(modules, map, stepModules, jobs);
```

import `java.util.stream.Collectors`.

- [ ] **Step 2: 컴파일 실패 확인** — `bash gradlew :compileTestJava 2>&1 | grep -E "error|BUILD"` → `cannot find symbol moduleViolations`

- [ ] **Step 3: Java 구현**

(a) 클래스 매칭 중복 제거 — `resolve`와 `codeError`가 같은 필터를 갖고 있다:

```java
    // 단순 이름(유일해야 함) 또는 FQCN으로 찾은 후보 전부
    private static List<JavaClass> matches(String name, JavaClasses classes) {
        return classes.stream()
                .filter(c -> name.contains(".") ? c.getName().equals(name) : c.getSimpleName().equals(name))
                .toList();
    }

    // 단순 이름(유일) 또는 FQCN → 클래스
    private static Optional<JavaClass> resolve(String name, JavaClasses classes) {
        if (name == null) {
            return Optional.empty();
        }
        var found = matches(name, classes);
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }
```

`codeError`의 `var matches = classes.stream()...toList();` → `var matches = matches(name, classes);`

(b) 모듈명 규칙 하나로 — `toJob`의 `var module = owner.getPackageName().replaceFirst(...)...` → `var module = moduleOf(owner);` 그리고:

```java
    // com.kista.<module>.… 의 첫 세그먼트 — 잡·단계 모두 이 규칙
    static String moduleOf(JavaClass owner) {
        return owner.getPackageName().replaceFirst("^com\\.kista\\.", "").split("\\.")[0];
    }
```

(c) 단계 → 모듈 도출과 검증:

```java
    // 단계 code: 클래스의 소속 모듈 (flowId → stepId → 모듈명) — flows.yml 키가 아니라 출력 전용
    static Map<String, Map<String, List<String>>> stepModules(FlowMap map, JavaClasses classes) {
        var result = new TreeMap<String, Map<String, List<String>>>();
        map.flows().forEach((flowId, flow) -> {
            var steps = new LinkedHashMap<String, List<String>>();
            flow.steps().forEach(s -> steps.put(s.id(), s.code().stream()
                    .flatMap(ref -> resolve(ref.split("#", 2)[0], classes).stream())
                    .map(ArchitectureMapExporter::moduleOf)
                    .distinct().sorted().toList()));
            result.put(flowId, steps);
        });
        return result;
    }

    // 모듈 밖 클래스(예: com.kista 루트)를 가리키는 단계 code: — 오버레이가 조용히 빠지지 않도록
    static List<String> moduleViolations(FlowMap map, JavaClasses classes, Set<String> moduleNames) {
        var errors = new ArrayList<String>();
        map.flows().forEach((flowId, flow) -> flow.steps().forEach(s -> s.code().forEach(ref ->
                resolve(ref.split("#", 2)[0], classes)
                        .filter(c -> !moduleNames.contains(moduleOf(c)))
                        .ifPresent(c -> errors.add(flowId + "." + s.id() + ": " + c.getName() + "은 어느 모듈에도 속하지 않음")))));
        return errors;
    }
```

(d) `record Data(List<ModuleGraphExporter.Module> modules, FlowMap map, Map<String, Map<String, List<String>>> stepModules, List<Job> jobs)` — `write` 시그니처에 `stepModules` 추가하고 `new Data(modules, map, stepModules, jobs)`. import `LinkedHashMap`, `Set`, `TreeMap`.

- [ ] **Step 4: 테스트 통과** — `bash gradlew :test --tests 'com.kista.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"` → `BUILD SUCCESSFUL`

- [ ] **Step 5: `core.js` — 교차 색인·해시·공유 상태·연결·단계 패널**

`const setPanel` 다음에 추가:

```js
// 해시 생성 — segment별 인코드(잡 이름의 #·흐름 id 등)
const hash = (view, ...parts) => '#' + [view, ...parts.filter(p => p != null).map(p => encodeURIComponent(p))].join('/');

// 단계 → 모듈(Java 도출)을 단계에 붙인다
for (const [fid, f] of Object.entries(MAP.flows)) {
  for (const s of f.steps) s.modules = DATA.stepModules[fid]?.[s.id] ?? [];
}

// 교차 색인 — 전부 DATA에서 자동 도출
const IDX = {
  modSteps: {},        // 모듈 → [{flow, step}]
  stepJobs: {},        // "flow/step" → [job index]  (잡 이름 = 단계 code 항목)
  jobSteps: {},        // 잡 이름 → [{flow, step}]
  stepTransitions: {}, // "flow/step" → [{lc, i}]
};
for (const [fid, f] of Object.entries(MAP.flows)) {
  for (const s of f.steps) {
    for (const m of s.modules) (IDX.modSteps[m] ??= []).push({ flow: fid, step: s.id });
    DATA.jobs.forEach((j, i) => {
      if (!s.code.includes(j.name)) return;
      (IDX.stepJobs[`${fid}/${s.id}`] ??= []).push(i);
      (IDX.jobSteps[j.name] ??= []).push({ flow: fid, step: s.id });
    });
  }
}
for (const [lid, lc] of Object.entries(MAP.lifecycles)) {
  lc.transitions.forEach((t, i) => { if (t.flow?.includes('/')) (IDX.stepTransitions[t.flow] ??= []).push({ lc: lid, i }); });
}

// 탭 사이 공유 선택 — 구조 탭에서 고른 모듈, 흐름·오버레이에서 고른 단계
const SHARED = { module: null, step: null }; // step: {flow, step}

const findStep = ({ flow, step }) => MAP.flows[flow]?.steps.find(s => s.id === step);
const stepLabel = ref => `${MAP.flows[ref.flow].title} · ${findStep(ref).title}`;
const chipLink = (href, label) => `<a class="tag link" href="${esc(href)}">${esc(label)}</a>`;

// 패널 하단 "연결" 섹션 — [[소제목, [html...]], ...], 빈 항목은 생략
function connections(sections) {
  const body = sections.filter(([, items]) => items.length)
    .map(([title, items]) => `<h4>${esc(title)}</h4><div class="tags">${items.join('')}</div>`).join('');
  return body ? `<h3>연결</h3>${body}` : '';
}

// 흐름 단계 상세 패널 — 흐름 탭·구조 오버레이 공용, hrefOf(k) = k번째 단계로 가는 해시
function stepPanel(fid, i, hrefOf) {
  const flow = MAP.flows[fid], s = flow.steps[i], key = `${fid}/${s.id}`;
  const laneLabel = id => MAP.lanes.find(l => l.id === id)?.label ?? id;
  setPanel(`<div class="p-head"><span class="hint">${i + 1} / ${flow.steps.length} · <span class="lane-pill">${esc(laneLabel(s.lane))}</span></span>
      <h2>${esc(s.title)}</h2></div>
    <div class="p-body">
      ${s.cond ? `<h3>실행 조건</h3><div class="box">${esc(s.cond)}</div>` : ''}
      <p class="desc">${esc(s.desc)}</p>
      ${s.state ? `<h3>상태 변화</h3><div class="box state">${esc(s.state)}</div>` : ''}
      ${s.fail ? `<h3>실패 경로</h3><div class="box fail">${esc(s.fail)}</div>` : ''}
      ${s.to.length ? `<h3>호출·기록 대상</h3><div class="tags">${s.to.map(t => `<span class="tag">${esc(laneLabel(t))}</span>`).join('')}</div>` : ''}
      ${s.code.length ? `<h3>담당 코드</h3><div class="tags">${s.code.map(c => `<code class="tag">${esc(c)}</code>`).join('')}</div>` : ''}
      <div class="navs">${i > 0 ? `<a class="btn" href="${esc(hrefOf(i - 1))}">◀ ${esc(flow.steps[i - 1].title)}</a>` : ''}
        ${i < flow.steps.length - 1 ? `<a class="btn" href="${esc(hrefOf(i + 1))}">${esc(flow.steps[i + 1].title)} ▶</a>` : ''}</div>
      ${connections([
        ['모듈', s.modules.map(m => chipLink(hash('structure', m), m))],
        ['생명주기 전이', (IDX.stepTransitions[key] ?? []).map(({ lc, i: t }) => {
          const tr = MAP.lifecycles[lc].transitions[t];
          return chipLink(hash('lifecycle', lc, t), `${MAP.lifecycles[lc].title}: ${tr.from ?? '생성'} → ${tr.to ?? '삭제'}`);
        })],
        ['시작 잡', (IDX.stepJobs[key] ?? []).map(j => chipLink(hash('timeline', DATA.jobs[j].name), DATA.jobs[j].name))],
      ])}
    </div>`);
}
```

- [ ] **Step 6: `core.js` — 통합 검색**

`index.html` 헤더 `<span class="spacer"></span>` 앞에:

```html
  <div class="qbox"><input type="search" class="search" id="q" placeholder="검색 ( / )" aria-label="통합 검색" autocomplete="off">
    <div class="results" id="q-results" hidden></div></div>
```

`core.js`에 추가(`window.addEventListener('DOMContentLoaded'` 앞):

```js
// 통합 검색 색인 — 모듈·흐름·단계(제목·code)·잡·상태 enum
const SEARCH = [
  ...DATA.modules.map(m => ({ kind: '모듈', label: m.name, sub: m.project, href: hash('structure', m.name) })),
  ...Object.entries(MAP.flows).flatMap(([fid, f]) => [
    { kind: '흐름', label: f.title, sub: f.trigger, href: hash('flow', fid) },
    ...f.steps.map(s => ({ kind: '단계', label: s.title, sub: `${f.title} · ${s.code.join(' ')}`, href: hash('flow', fid, s.id) })),
  ]),
  ...DATA.jobs.map(j => ({ kind: '잡', label: j.name, sub: `${j.process} · ${j.days}`, href: hash('timeline', j.name) })),
  ...Object.entries(MAP.lifecycles).flatMap(([lid, lc]) => lc.states.map(st =>
    ({ kind: '상태', label: `${st.id} ${st.label}`, sub: `${lc.title} · ${lc.enum}`, href: hash('lifecycle', lid) }))),
].map(e => ({ ...e, text: `${e.label} ${e.sub ?? ''}`.toLowerCase() }));

function search(q) {
  const box = $('q-results');
  const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const hits = words.length ? SEARCH.filter(e => words.every(w => e.text.includes(w))).slice(0, 30) : [];
  box.hidden = !hits.length;
  box.innerHTML = hits.map(e => `<a href="${esc(e.href)}"><span class="kind">${esc(e.kind)}</span><b>${esc(e.label)}</b><small>${esc(e.sub ?? '')}</small></a>`).join('');
}
```

DOMContentLoaded 핸들러 안 `route();` 앞에:

```js
  const q = $('q');
  q.addEventListener('input', () => search(q.value));
  q.addEventListener('keydown', e => {
    if (e.key === 'Enter') $('q-results').querySelector('a')?.click();
    if (e.key === 'Escape') { q.value = ''; search(''); q.blur(); }
  });
  $('q-results').addEventListener('click', () => { q.value = ''; search(''); });
  document.addEventListener('keydown', e => {
    if (e.key === '/' && !/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName)) { e.preventDefault(); q.focus(); }
  });
```

`style.css`에 추가:

```css
  .qbox { position: relative; }
  .qbox .search { width: 240px; }
  .results { position: absolute; top: calc(100% + 4px); left: 0; z-index: 10; width: 420px; max-height: 60vh; overflow: auto;
             background: var(--surface); border: 1px solid var(--line); border-radius: 10px; box-shadow: var(--shadow); }
  .results a { display: grid; grid-template-columns: 44px 1fr; gap: 0 8px; padding: 6px 10px; color: var(--fg); text-decoration: none; border-top: 1px solid var(--line); }
  .results a:first-child { border-top: 0; }
  .results a:hover, .results a:focus { background: var(--surface-2); }
  .results .kind { grid-row: span 2; font-size: 11px; color: var(--muted); padding-top: 2px; }
  .results small { color: var(--muted); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .p-body h4 { font-size: 12px; font-weight: 600; margin: 10px 0 6px; }
  a.tag.link { color: var(--fg); text-decoration: none; }
  a.tag.link:hover { border-color: color-mix(in srgb, var(--fg) 35%, transparent); }
  .lanes.modfilter .step:not(.mod-hit) { opacity: .3; }
```

모바일 `@media` 블록에 `.qbox .search { width: 160px; } .results { width: calc(100vw - 32px); }` 추가.

- [ ] **Step 7: `structure.js` — 해시 연동·연결·모듈 검색 제거**

통합 검색이 모듈 검색을 대신하므로 구조 헤더의 `#mod-search`(index.html)와 그 `input` 핸들러를 삭제한다(부분 일치 다중 하이라이트는 통합 검색 결과 목록으로 대체).

- `modLink`: `<a href="#" class="mod-link" data-mod=...>` → `` `<a class="mod-link" href="${esc(hash('structure', name))}">${esc(name)}</a>` ``
- 패널 `[data-mod]` 위임 핸들러 본문: `selectModule(el.dataset.mod)` → `location.hash = hash('structure', el.dataset.mod)` (개요 표 행 `tr[data-mod]`용으로 남는다; `<a>`는 href로 이동하므로 `e.preventDefault()` 전에 `if (el.tagName === 'A') return;`)
- 노드 탭: `focus(e.target); showModule(e.target.id());` → `location.hash = hash('structure', e.target.id());`
- 빈 곳 탭: `clearFocus()` → `location.hash = '#structure'`
- `selectModule(name)` 첫 줄 다음에 `SHARED.module = name; SHARED.step = null;`
- `clearFocus()` 안에 `SHARED.module = null;`
- 필터 변경(의존 종류 칩·`hideInfra`)은 `render()`가 포커스를 풀므로 두 핸들러에서 `render()` 다음에 `if (parseHash().rest.length) history.replaceState(null, '', '#structure');` (`render()` 안에 두면 딥링크 진입 시 숨김 해제 `render()`가 모듈 해시를 지운다)
- `showModule(name)`의 `p-body` 끝에:

```js
      ${connections([
        ...Object.entries(Object.groupBy(IDX.modSteps[name] ?? [], r => r.flow)).map(([fid, refs]) =>
          [MAP.flows[fid].title, refs.map(r => chipLink(hash('flow', fid, r.step), findStep(r).title))]),
        ['소속 잡', DATA.jobs.filter(j => j.module === name).map(j => chipLink(hash('timeline', j.name), j.name))],
      ])}
```

- 등록 교체:

```js
  // 흐름에서 고른 단계가 있으면 그 단계의 (보이는) 첫 모듈, 아니면 마지막으로 고른 모듈
  const homeModule = () => SHARED.step ? findStep(SHARED.step)?.modules.find(m => !hidden(m)) : SHARED.module;
  VIEWS.structure = {
    show([mod]) {
      if (!cy) init(); else cy.resize(); // 숨김 중 창 크기 변경 반영
      if (mod && byName[mod]) {
        if (hidden(mod)) { $('hideInfra').checked = false; render(); } // 숨긴 infra 모듈 딥링크
        selectModule(mod);
      } else {
        clearFocus();
      }
    },
    home: () => hash('structure', homeModule() ?? undefined),
  };
```

(`hash()`는 null/undefined segment를 버리므로 모듈이 없으면 `#structure`.)

- [ ] **Step 8: `flow.js` — 단계 패널 공용화·공유 상태·모듈 필터**

- `panelStep` 함수 삭제, `select()`의 `panelStep(flow, flow.steps[i], i);` → `stepPanel(currentFlow, i, k => hash('flow', currentFlow, flow.steps[k].id));`
- `select()`에서 `i >= 0`일 때 `SHARED.step = { flow: currentFlow, step: flow.steps[i].id };`
- 모듈 필터 — `renderFlow()`의 `$('swim').innerHTML = html;` 다음에:

```js
  // 구조 탭에서 고른 모듈을 거치는 단계만 강조
  const m = SHARED.module;
  $('lanes').classList.toggle('modfilter', !!m);
  flow.steps.forEach((s, i) => $('step-' + i).classList.toggle('mod-hit', !!m && s.modules.includes(m)));
  $('mod-filter').hidden = !m;
  $('mod-filter').textContent = m ? `모듈: ${m} ✕` : '';
```

- index.html `#view-flow` view-head의 `<span class="spacer"></span>` 앞에 `<button class="chip" id="mod-filter" hidden aria-pressed="true"></button>` 추가, flow.js에 `$('mod-filter').onclick = () => { SHARED.module = null; renderFlow(currentFlow); select(selected); };`
- `show()`는 모듈 필터가 바뀌었을 수 있으므로 항상 다시 그린다 — `if (id !== currentFlow) renderFlow(id); else requestAnimationFrame(drawLinks);` → `renderFlow(id);`
- `home` 교체 — 공유 단계 → 그 단계, 고른 모듈 → 그 모듈을 처음 거치는 흐름, 아니면 현재 흐름:

```js
    home: () => SHARED.step ? hash('flow', SHARED.step.flow, SHARED.step.step)
      : hash('flow', (SHARED.module && IDX.modSteps[SHARED.module]?.[0]?.flow) ?? currentFlow ?? Object.keys(MAP.flows)[0]),
```

- `panelLandscape`(landscape.js)의 흐름 목록 링크 `href="#flow/${esc(id)}"`는 그대로 둔다(id에 인코드할 문자 없음).

- [ ] **Step 9: `timeline.js` — 잡 딥링크·연결**

- 행 클릭: `r.onclick = () => panelJob(+r.dataset.i)` → `r.onclick = () => location.hash = hash('timeline', DATA.jobs[+r.dataset.i].name)`
- `panelJob(i)`의 `p-body` 끝에 `${connections([['시작하는 흐름 단계', (IDX.jobSteps[j.name] ?? []).map(r => chipLink(hash('flow', r.flow, r.step), stepLabel(r)))]])}`
- 등록 교체:

```js
  VIEWS.timeline = {
    show([name]) {
      const i = DATA.jobs.findIndex(j => j.name === name);
      i >= 0 ? panelJob(i) : (document.querySelectorAll('.tl-row.sel').forEach(r => r.classList.remove('sel')), panelTimeline());
    },
  };
```

- [ ] **Step 10: 테스트 + JS 게이트** — Step 4 명령 재실행(생성물 갱신) 후 "JS 게이트". Expected: `BUILD SUCCESSFUL`, `JS-OK`

- [ ] **Step 11: 리뷰 후 커밋** — 메시지 `feat(architecture): 아키텍처 맵 교차 색인·딥링크·공유 상태·통합 검색 추가` (본문: 단계→모듈 자동 도출, 모듈 소속 단언·close-batch/lock 회귀 가드)

---

### Task 3: 흐름 오버레이·점진적 공개 (구조 탭)

**Files:**
- Modify: `map/index.html`, `map/style.css`, `map/views/structure.js`

**Interfaces:**
- Consumes: `stepPanel`, `hash`, `SHARED`, `findStep`, `step.modules`(Task 2)
- Produces: 해시 `#structure[/<module>]?flow=<fid>&step=<sid>`. 흐름 탭 `home()`은 Task 2의 `SHARED.step`으로 오버레이 위치를 이어받는다.

- [ ] **Step 1: index.html — 오버레이 컨트롤·재생 바·접기 버튼**

`#view-structure` view-head의 `<button class="btn" id="fit">` 앞에:

```html
      <select class="search" id="ov-flow" aria-label="흐름 오버레이"><option value="">흐름 오버레이 없음</option></select>
      <button class="btn" id="expand">모두 펼치기</button>
```

`#stage` 닫는 태그 앞에:

```html
      <div class="ov-bar" id="ov-bar" hidden>
        <button class="btn" id="ov-prev" aria-label="이전 단계">◀</button>
        <button class="btn" id="ov-play">▶ 재생</button>
        <button class="btn" id="ov-next" aria-label="다음 단계">▶|</button>
        <input type="range" id="ov-slider" min="0" value="0" aria-label="단계">
        <div class="ov-strip" id="ov-strip"></div>
      </div>
```

style.css 추가:

```css
  .ov-bar { position: absolute; left: 16px; right: 16px; bottom: 16px; z-index: 2; display: flex; flex-wrap: wrap; gap: 6px 8px; align-items: center;
            padding: 8px 10px; background: var(--surface); border: 1px solid var(--line); border-radius: 10px; box-shadow: var(--shadow); }
  .ov-bar input[type=range] { flex: 1; min-width: 120px; }
  .ov-strip { flex-basis: 100%; display: flex; gap: 4px; overflow-x: auto; }
  .ov-strip button { flex: none; padding: 2px 8px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); color: var(--muted);
                     font: inherit; font-size: 12px; cursor: pointer; }
  .ov-strip button.done { color: var(--fg); }
  .ov-strip button.on { border-color: var(--tc); color: var(--fg); font-weight: 600; background: var(--tc-tint); }
  .ov-strip button.nomod { border-style: dashed; }
  #stage:has(.ov-bar:not([hidden])) .graph-legend { display: none; }
```

- [ ] **Step 2: 점진적 공개 — `structure.js`**

상태 추가: `const expanded = new Set(); // 펼친 서브프로젝트 — 처음엔 모두 접힘`

`elements()` 교체 — 접힌 프로젝트는 모듈 대신 박스 노드 하나, 엣지는 표시 노드 기준으로 합산:

```js
  const shown = name => expanded.has(byName[name].project) ? name : byName[name].project; // 모듈이 그려지는 노드 id
  function elements() {
    const mods = DATA.modules.filter(m => !hidden(m.name));
    const projects = Object.entries(PROJECTS).map(([p, c]) => {
      const n = mods.filter(m => m.project === p).length;
      return { data: { id: p, label: expanded.has(p) ? p : `${p} · ${n}`, accent: css(c.accent), tint: css(c.tint) },
               classes: expanded.has(p) ? '' : 'proj' };
    });
    const nodes = mods.filter(m => expanded.has(m.project)).map(m => ({
      data: { id: m.name, label: m.name, parent: m.project, accent: css(PROJECTS[m.project].accent),
              weight: outgoing(m.name).length + incoming(m.name).length },
    }));
    return [...projects, ...nodes, ...edges()];
  }
```

`edges()`의 집계 키를 표시 노드로 바꾼다 — 루프를 아래로 교체:

```js
  function edges() {
    const groups = {};
    for (const m of DATA.modules) {
      if (hidden(m.name)) continue;
      for (const d of outgoing(m.name)) {
        const s = shown(m.name), t = shown(d.target);
        if (s !== t) (groups[`${s}->${t}`] ??= { source: s, target: t, deps: [] }).deps.push(d);
      }
    }
    return Object.entries(groups).map(([id, { source, target, deps }]) => {
      const type = TYPE_PRIORITY.find(t => deps.some(d => d.type === t));
      return { data: { id, source, target, count: deps.length, color: css(TYPES[type][1]),
                       width: Math.min(1 + Math.log2(deps.length + 1) * 1.2, 8) } };
    });
  }
```

(모두 펼치면 같은 프로젝트 안 의존이 `s !== t`로 걸러지지 않는다 — 모듈 id끼리이므로 현행과 동일.)

cytoscape style 배열에 접힌 박스 스타일 추가:

```js
      { selector: 'node.proj', style: {
          'background-color': 'data(tint)', 'border-color': 'data(accent)', 'border-style': 'dashed', color: 'data(accent)',
          'font-size': 15, 'font-weight': 700, width: 160, height: 64 } },
```

`layout()` 루프 첫 줄에 접힌 프로젝트 배치:

```js
      if (!expanded.has(p)) { cy.getElementById(p).position({ x: (box.x1 + box.x2) / 2, y: (box.y1 + box.y2) / 2 }); continue; }
```

탭 핸들러 수정:
- 노드 탭: `if (e.target.hasClass('proj')) { expanded.add(e.target.id()); return render(); }`를 맨 앞에.
- 엣지 탭: 끝점 중 하나라도 `proj`면 `[source, target]` 프로젝트를 펼치고 `render()` 후 return — 합계 엣지는 클래스 쌍 패널 대상이 아니다:

```js
  cy.on('tap', 'edge', e => {
    const ends = e.target.connectedNodes();
    if (ends.some(n => n.hasClass('proj'))) { ends.forEach(n => n.hasClass('proj') && expanded.add(n.id())); return render(); }
    focus(e.target); showEdge(e.target.data('source'), e.target.data('target'));
  });
```

`expandAll()`과 버튼:

```js
  function expandAll(on = true) {
    const before = expanded.size;
    Object.keys(PROJECTS).forEach(p => on ? expanded.add(p) : expanded.delete(p));
    $('expand').textContent = on ? '모두 접기' : '모두 펼치기';
    if (expanded.size !== before) render();
  }
  $('expand').onclick = () => cy && expandAll(expanded.size < Object.keys(PROJECTS).length);
```

`render()` 끝의 요약·개요가 박스 노드를 모듈로 세지 않도록:
- `$('summary').textContent = ...cy.nodes().not(':parent').length...` → `DATA.modules.filter(m => !hidden(m.name)).length`
- `showOverview()`의 `cy.nodes().not(':parent').map(n => { const name = n.id(); ...` → `DATA.modules.filter(m => !hidden(m.name)).map(({ name }) => { ...`
- `selectModule(name)` 첫 줄에 `if (!expanded.has(byName[name].project)) { expanded.add(byName[name].project); render(); }` (딥링크 진입 시 자동 펼침)

- [ ] **Step 3: 흐름 오버레이 — `structure.js`**

```js
  /* ---------- 흐름 오버레이 ---------- */
  const REDUCE_MOTION = matchMedia('(prefers-reduced-motion: reduce)').matches; // 흐르는 점 대신 정적 하이라이트
  let ov = null, ovTimer = null; // ov: { fid, i }
  $('ov-flow').insertAdjacentHTML('beforeend', Object.entries(MAP.flows).map(([id, f]) =>
    `<option value="${esc(id)}">${esc(f.title)}</option>`).join(''));
  const ovHash = (fid, i) => fid ? `${hash('structure')}?flow=${encodeURIComponent(fid)}&step=${encodeURIComponent(MAP.flows[fid].steps[i].id)}` : '#structure';
  // 단계 i가 그래프에 올리는 모듈 — 숨김·필터 밖은 건너뛴다(스트립·패널에는 남는다)
  const stepNodes = (fid, i) => MAP.flows[fid].steps[i].modules.filter(m => byName[m] && !hidden(m));

  function ovStop() {
    clearInterval(ovTimer); ovTimer = null;
    $('ov-play').textContent = '▶ 재생';
  }

  function clearOverlay() {
    ov = null;
    $('ov-bar').hidden = true;
    $('ov-flow').value = '';
    cy.remove('.ov-virtual, .ov-dot');
    cy.elements().removeClass('ov-on ov-trail');
  }

  function showOverlay(fid, i) {
    const prevOv = ov;
    ov = { fid, i };
    SHARED.step = { flow: fid, step: MAP.flows[fid].steps[i].id };
    expandAll();
    const steps = MAP.flows[fid].steps;
    $('ov-flow').value = fid;
    $('ov-bar').hidden = false;
    $('ov-slider').max = steps.length - 1;
    $('ov-slider').value = i;
    $('ov-prev').disabled = i <= 0;
    $('ov-next').disabled = i >= steps.length - 1;
    $('ov-strip').innerHTML = steps.map((s, k) =>
      `<button data-k="${k}" class="${k === i ? 'on' : k < i ? 'done' : ''}${s.modules.length ? '' : ' nomod'}" title="${esc(s.modules.join(', ') || '모듈 없음')}">${k + 1}. ${esc(s.title)}</button>`).join('');
    $('ov-strip').querySelector('.on')?.scrollIntoView({ block: 'nearest', inline: 'center' });

    // 0..i 단계를 훑으며 연속(모듈 있는) 단계 사이 경로를 만든다 — 의존 엣지가 있으면 그것, 없으면 점선 가상 엣지
    cy.remove('.ov-virtual, .ov-dot');
    cy.elements().removeClass('focus faded selected ov-on ov-trail');
    let prev = [], last = [], from = null;
    const trail = cy.collection();
    for (let k = 0; k <= i; k++) {
      const cur = stepNodes(fid, k);
      if (!cur.length) continue;
      for (const a of prev) for (const b of cur) {
        if (a === b) continue;
        let e = cy.getElementById(`${a}->${b}`).union(cy.getElementById(`${b}->${a}`));
        if (!e.length) e = cy.add({ data: { id: `ov:${k}:${a}->${b}`, source: a, target: b }, classes: 'ov-virtual' });
        trail.merge(e);
        if (k === i) e.addClass('ov-on');
      }
      cur.forEach(m => trail.merge(cy.getElementById(m)));
      if (k === i) { from = prev[0] ?? null; last = cur; }
      prev = cur;
    }
    cy.elements().not(trail).not(':parent').addClass('faded');
    trail.addClass('ov-trail');
    last.forEach(m => cy.getElementById(m).addClass('ov-on'));

    // 이전 단계 모듈 → 현재 단계 모듈로 점 하나 이동 (바로 다음 단계로 넘어갈 때만)
    const to = last[0];
    if (!REDUCE_MOTION && from && to && prevOv?.fid === fid && prevOv.i === i - 1) {
      const dot = cy.add({ data: { id: 'ov-dot' }, classes: 'ov-dot', position: { ...cy.getElementById(from).position() } });
      dot.animate({ position: cy.getElementById(to).position() }, { duration: 600, easing: 'ease-in-out-cubic',
        complete: () => dot.remove() });
    }
    stepPanel(fid, i, k => ovHash(fid, k));
  }

  $('ov-flow').onchange = e => location.hash = e.target.value ? ovHash(e.target.value, 0) : '#structure';
  $('ov-slider').oninput = e => ov && (location.hash = ovHash(ov.fid, +e.target.value));
  $('ov-prev').onclick = () => ov && ov.i > 0 && (location.hash = ovHash(ov.fid, ov.i - 1));
  $('ov-next').onclick = () => ov && (location.hash = ovHash(ov.fid, Math.min(ov.i + 1, MAP.flows[ov.fid].steps.length - 1)));
  $('ov-strip').onclick = e => { const b = e.target.closest('[data-k]'); if (b) location.hash = ovHash(ov.fid, +b.dataset.k); };
  // ▶ 재생 — 흐름 탭과 같은 1.4초 간격, 해시는 replaceState(라우팅이 재생을 멈추지 않도록)
  $('ov-play').onclick = () => {
    if (ovTimer) return ovStop();
    const n = MAP.flows[ov.fid].steps.length;
    let i = ov.i < n - 1 ? ov.i : -1;
    const tick = () => {
      i++;
      if (i >= n) return ovStop();
      showOverlay(ov.fid, i);
      history.replaceState(null, '', ovHash(ov.fid, i));
    };
    tick();
    ovTimer = setInterval(tick, 1400);
    $('ov-play').textContent = '■ 정지';
  };
```

cytoscape style 배열 추가:

```js
      { selector: 'node.ov-trail', style: { 'border-width': 3 } },
      { selector: 'node.ov-on', style: { 'background-color': 'data(accent)', color: '#fff', 'border-color': css('--focus') } },
      { selector: 'edge.ov-trail', style: { opacity: 0.35, 'line-color': css('--tc'), 'target-arrow-color': css('--tc') } },
      { selector: 'edge.ov-on', style: { opacity: 1, width: 4, 'line-color': css('--tc'), 'target-arrow-color': css('--tc') } },
      { selector: 'edge.ov-virtual', style: { 'line-style': 'dashed', width: 2 } },
      { selector: 'node.ov-dot', style: { width: 14, height: 14, padding: 0, label: '', shape: 'ellipse',
          'background-color': css('--tc'), 'border-width': 0, events: 'no' } },
```

`render()`이 요소를 갈아엎으므로 끝에 오버레이 재적용: `clearFocus();` 다음에 `if (ov) showOverlay(ov.fid, ov.i);` — 단 `clearFocus()` 안에서 `showOverview()`가 패널을 덮으니 순서는 `clearFocus(); if (ov) showOverlay(...)`.

- [ ] **Step 4: 등록·탭 상태 연동**

`VIEWS.structure` 교체:

```js
  VIEWS.structure = {
    show([mod], params) {
      if (!cy) init(); else cy.resize();
      const fid = params.get('flow');
      if (fid && Object.hasOwn(MAP.flows, fid)) {
        const i = Math.max(0, MAP.flows[fid].steps.findIndex(s => s.id === params.get('step')));
        return showOverlay(fid, i);
      }
      if (ov) clearOverlay();
      if (mod && byName[mod]) {
        if (hidden(mod)) { $('hideInfra').checked = false; render(); }
        selectModule(mod);
      } else {
        clearFocus();
      }
    },
    leave: ovStop,
    // 흐름에서 고른 단계가 있으면 오버레이를 그 단계에 멈춘 채로, 아니면 마지막 모듈
    home: () => SHARED.step
      ? `${hash('structure', findStep(SHARED.step)?.modules.find(m => !hidden(m)))}?flow=${encodeURIComponent(SHARED.step.flow)}&step=${encodeURIComponent(SHARED.step.step)}`
      : hash('structure', SHARED.module ?? undefined),
  };
```

(Task 2의 `homeModule` 상수는 삭제.) `selectModule()`은 이미 `SHARED.step = null`을 하므로 모듈을 고르면 오버레이 이어받기가 끊긴다 — 의도대로.

- [ ] **Step 5: 테스트 + JS 게이트** — `bash gradlew :test --tests 'com.kista.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"` 후 "JS 게이트". Expected: `BUILD SUCCESSFUL`, `JS-OK`

- [ ] **Step 6: 리뷰 후 커밋** — `feat(architecture): 구조 탭에 흐름 오버레이 재생과 서브프로젝트 점진적 공개 추가`

---

### Task 4: 시각 개선(원형 타임라인·노선도·모션·테마) + 문서

**Files:**
- Modify: `map/index.html`, `map/style.css`, `map/core.js`, `map/views/{structure,landscape,flow,timeline}.js`
- Modify: `src/test/resources/architecture/flows.yml`(머리 주석 1~2행), `docs/architecture-map.md`(L2·업무 흐름 맵 절), `CLAUDE.md`(참고 문서 절)

**Interfaces:**
- Consumes: Task 1~3 전부
- Produces: `REDUCED`, `applyTheme`, `themechange` 이벤트(core.js)

- [ ] **Step 1: 테마 토큰 — `style.css`**

`:root` 다크 블록 교체 — 시스템 설정 기본 + `data-theme` 수동 덮어쓰기:

```css
  @media (prefers-color-scheme: dark) {
    :root:not([data-theme="light"]) { /* 기존 다크 토큰 그대로 */ }
  }
  :root[data-theme="dark"] { /* 같은 다크 토큰 복제 */ }
```

추가:

```css
  .view:not([hidden]) { animation: fade .15s ease-out; }
  @keyframes fade { from { opacity: 0; } }
  @media (prefers-reduced-motion: reduce) {
    *, *::before, *::after { animation: none !important; transition: none !important; }
  }
```

- [ ] **Step 2: 테마 토글 — `core.js`·index.html**

헤더 끝(`<span class="spacer">` 뒤)에 `<button class="btn" id="theme" aria-label="테마 전환">◐</button>`.

core.js:

```js
const REDUCED = matchMedia('(prefers-reduced-motion: reduce)').matches; // 흐르는 점·dash 끔
const THEME_KEY = 'kista-architecture-map-theme';
const isDark = () => document.documentElement.dataset.theme === 'dark'
  || (!document.documentElement.dataset.theme && matchMedia('(prefers-color-scheme: dark)').matches);
function applyTheme(t) {
  if (t) document.documentElement.dataset.theme = t; else delete document.documentElement.dataset.theme;
  window.dispatchEvent(new Event('themechange')); // cytoscape처럼 색을 값으로 박는 뷰가 다시 그린다
}
try { applyTheme(localStorage.getItem(THEME_KEY)); } catch { /* 저장소 차단 — 시스템 설정 */ }
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => applyTheme(document.documentElement.dataset.theme));
```

DOMContentLoaded 안에:

```js
  $('theme').onclick = () => {
    const t = isDark() ? 'light' : 'dark';
    applyTheme(t);
    try { localStorage.setItem(THEME_KEY, t); } catch { /* 기억만 못 함 */ }
  };
```

structure.js의 `structure.js` Task 3 `REDUCE_MOTION` 상수는 삭제하고 `REDUCED`를 쓴다.

- [ ] **Step 3: structure.js — 테마 재적용·hover dash·노드 크기**

cytoscape `style: [...]` 배열을 `function styles() { return [...]; }`로 빼고 `cytoscape({ ..., style: styles() })`. 추가:

```js
  // 테마 전환 — 스타일·엣지 색이 생성 시점 값으로 박혀 있어 다시 계산
  window.addEventListener('themechange', () => {
    if (!cy) return;
    cy.style(styles());
    render();
    if (!$('view-structure').hidden) route(); // 선택·오버레이 상태를 해시에서 복원
  });
```

styles 안:
- `node` 스타일에 `'font-size': 'mapData(weight, 0, 80, 12, 16)'` (기존 `'font-size': 13` 대체 — 의존 수 약하게 반영. `node.proj`·`:parent`는 자기 font-size가 덮는다)
- 추가: `{ selector: 'edge.flowing', style: { opacity: 0.9, 'line-style': 'dashed', 'line-dash-pattern': [6, 4] } }`

hover 핸들러 교체 — 노드 hover 시 연결 엣지에 흐르는 dash:

```js
  let dashOffset = 0, dashRaf = 0;
  const dashLoop = () => {
    dashOffset = (dashOffset + 0.6) % 1000;
    cy.edges('.flowing').style('line-dash-offset', -dashOffset);
    dashRaf = requestAnimationFrame(dashLoop);
  };
  cy.on('mouseover', 'node, edge', e => {
    if (e.target.isNode() && e.target.isParent()) return;
    e.target.addClass('hover');
    $('cy').style.cursor = 'pointer';
    if (e.target.isNode()) {
      e.target.connectedEdges().addClass('flowing');
      if (!REDUCED && !dashRaf) dashRaf = requestAnimationFrame(dashLoop);
    }
  });
  cy.on('mouseout', 'node, edge', e => {
    e.target.removeClass('hover');
    $('cy').style.cursor = '';
    cy.edges('.flowing').removeClass('flowing');
    cancelAnimationFrame(dashRaf); dashRaf = 0;
  });
```

(`REDUCED`면 dash는 정적 패턴만 남는다.)

- [ ] **Step 4: flow.js — 호출선 화살표·재생 중 흐르는 점**

`drawLinks()`:
- seq 경로에 `data-seq="${i}"`, call 경로에 `data-from="${t.dataset.from}"`, call에 `marker-end="url(#call-ah)"`
- 현재 재생 단계의 들어오는 순서선·나가는 호출선에 `live` 클래스: `const live = timer && selected >= 0;` 후 seq는 `live && i === selected - 1`, call은 `live && +t.dataset.from === selected`이면 `class="seq live"`/`class="call live"`
- `svg.innerHTML = d;` → `` svg.innerHTML = `<defs><marker id="call-ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0,0 L10,5 L0,10 z" style="fill:var(--uses)"/></marker></defs>` + d; ``
- `select()` 끝(`panel` 호출 전)에 `requestAnimationFrame(drawLinks);` — 재생 tick마다 live 경로 갱신

style.css:

```css
  svg.links .live { stroke: var(--tc); stroke-width: 4; stroke-dasharray: 0 10; stroke-linecap: round; opacity: 1; animation: flowdot .6s linear infinite; }
  @keyframes flowdot { to { stroke-dashoffset: -10; } }
```

- [ ] **Step 5: landscape.js — 노선도·스파크라인·모듈 배지**

카드 렌더에 흐름 요약 추가:

```js
  // 흐름이 건드리는 레인(단계 lane·to)과 모듈 수 — 카드·역 공통
  function footprint(fid) {
    const steps = MAP.flows[fid]?.steps ?? [];
    const lanes = new Set(steps.flatMap(s => [s.lane, ...s.to]));
    const mods = new Set(steps.flatMap(s => s.modules));
    return `<span class="spark" aria-label="터치 레인">${MAP.lanes.map(l =>
      `<i class="${lanes.has(l.id) ? 'on' : ''}" title="${esc(l.label)}"></i>`).join('')}</span><span class="badge mods">모듈 ${mods.size}</span>`;
  }
```

`.card.linked` 마크업의 `<small><span class="badge go">흐름 보기 →</span></small>` → `<small>${footprint(s.flow)}</small>`.

style.css — 첫 그룹(`.row.journey`, 사용자 여정)을 노선도로, 기존 `.row.journey` 2줄(→ 화살표·gap) 삭제 후:

```css
  .row.journey { position: relative; flex-wrap: nowrap; gap: 0; overflow-x: auto; padding-bottom: 4px; }
  .row.journey::before { content: ""; position: absolute; left: 60px; right: 60px; top: 15px; height: 4px; border-radius: 2px; background: var(--tc); }
  .row.journey .card { flex: 1 0 128px; padding-top: 34px; text-align: center; background: none; border: 0; box-shadow: none; }
  .row.journey .card::before { content: ""; position: absolute; top: 7px; left: 50%; translate: -50% 0; width: 20px; height: 20px; border-radius: 50%;
                               background: var(--surface); border: 4px solid var(--tc); box-sizing: border-box; }
  .row.journey .card.pending::before { border-color: var(--line); }
  .row.journey .card.linked:hover { background: var(--tc-tint); border-radius: 10px; }
  .spark { display: inline-flex; gap: 2px; vertical-align: middle; margin-right: 6px; }
  .spark i { width: 6px; height: 12px; border-radius: 1px; background: var(--line); }
  .spark i.on { background: var(--uses); }
  .badge.mods { background: var(--surface-2); color: var(--muted); border: 1px solid var(--line); }
```

- [ ] **Step 6: timeline.js — 원형 24h 시계(기본) + 선형 토글**

index.html `#view-timeline` view-head 끝에 `<button class="btn" id="tl-mode">선형 보기</button>`, `#tl` 앞에 `<div id="tl-dial" class="dial"></div>`.

timeline.js 추가:

```js
  // 매매 구간 띠 — 개장·마감 잡의 실제 cron 시각에서 도출(없으면 띠 없음)
  const BAND = ['TradingOpenScheduler#run', 'TradingCloseScheduler#run'];
  const RING = { 'kista-trading': 120, 'kista-scheduler': 170 }; // 그 외 프로세스 = 220
  const R = 250, C = 260; // 바깥 반지름·중심(viewBox 520)
  const ang = min => min / 1440 * 2 * Math.PI - Math.PI / 2; // 0시 = 12시 방향, 시계방향
  const pt = (r, min) => [C + r * Math.cos(ang(min)), C + r * Math.sin(ang(min))];
  function arc(r, a, b) { // a→b 시계방향(자정 넘김 허용)
    const sweep = (b - a + 1440) % 1440, [x0, y0] = pt(r, a), [x1, y1] = pt(r, b);
    return `M${x0},${y0} A${r},${r} 0 ${sweep > 720 ? 1 : 0} 1 ${x1},${y1}`;
  }

  function renderDial() {
    const ringOf = p => RING[p] ?? 220;
    const procs = [...new Set(DATA.jobs.map(j => j.process))];
    const [open, close] = BAND.map(n => DATA.jobs.find(j => j.name === n));
    let svg = '';
    if (open?.times.length && close?.times.length) {
      const a = Math.min(...open.times.map(toMin)), b = Math.max(...close.times.map(toMin));
      svg += `<path class="band" d="${arc(RING['kista-trading'], a, b)}"><title>매매 구간 ${open.times[0]} → ${close.times.at(-1)}</title></path>`;
    }
    for (const p of procs) {
      const periodic = DATA.jobs.some(j => j.process === p && !j.times.length);
      svg += `<circle class="ring${periodic ? ' periodic' : ''}" cx="${C}" cy="${C}" r="${ringOf(p)}"><title>${esc(p)}${periodic ? ' · 주기 실행 잡 있음' : ''}</title></circle>`;
    }
    for (let h = 0; h < 24; h += 3) {
      const [x, y] = pt(R, h * 60);
      svg += `<text class="hour" x="${x}" y="${y}">${String(h).padStart(2, '0')}</text>`;
    }
    DATA.jobs.forEach(j => j.times.forEach(t => {
      const [x, y] = pt(ringOf(j.process), toMin(t));
      svg += `<a href="${esc(hash('timeline', j.name))}"><circle class="dot-job" cx="${x}" cy="${y}" r="7" style="fill:${procColor(j.process)}"><title>${esc(j.name)} ${t}</title></circle></a>`;
    }));
    const kst = new Date(Date.now() + 9 * 3600e3), [hx, hy] = pt(R - 24, kst.getUTCHours() * 60 + kst.getUTCMinutes());
    svg += `<line class="hand" x1="${C}" y1="${C}" x2="${hx}" y2="${hy}"><title>현재 KST</title></line>`;
    $('tl-dial').innerHTML = `<svg viewBox="0 0 520 520" role="img" aria-label="24시간 잡 시계">${svg}</svg>
      <div class="legend">${procs.map(p => `<span><i class="job" style="position:static;transform:none;background:${procColor(p)}"></i>${esc(p)} · 반지름 ${ringOf(p)}</span>`).join('')}
      <span>점선 링 = 주기 실행 잡 · 띠 = 매매 구간</span></div>`;
  }

  let linear = false;
  $('tl-mode').onclick = () => {
    linear = !linear;
    $('tl').hidden = !linear; $('tl-dial').hidden = linear;
    $('tl-mode').textContent = linear ? '원형 보기' : '선형 보기';
  };
  renderDial();
  $('tl').hidden = true;
```

`view-head` 설명 문구 "빨간 세로선은 현재 시각입니다." → "빨간 바늘·세로선은 현재 시각입니다."

style.css:

```css
  .dial { display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-start; }
  .dial svg { width: min(520px, 100%); height: auto; }
  .dial .ring { fill: none; stroke: var(--line); stroke-width: 1.5; }
  .dial .ring.periodic { stroke: var(--default); stroke-dasharray: 4 4; }
  .dial .band { fill: none; stroke: var(--tc-tint); stroke-width: 30; stroke-linecap: round; }
  .dial .hour { font-size: 12px; fill: var(--muted); text-anchor: middle; dominant-baseline: middle; }
  .dial .dot-job { stroke: var(--surface); stroke-width: 2; cursor: pointer; }
  .dial .hand { stroke: var(--event); stroke-width: 2; opacity: .8; }
  .dial .legend { flex-direction: column; }
```

- [ ] **Step 7: 문서**

`flows.yml` 1~2행:

```yaml
# 업무 흐름 맵 원본 — 사람이 작성한다. 아키텍처 맵(build/architecture-map/index.html)의 랜드스케이프·흐름·생명주기 탭이 된다.
# ArchitectureMapTest가 code: 의 `클래스#메서드` 참조가 실제로 존재하는지 검증한다(이름이 바뀌면 빌드 실패).
```

`docs/architecture-map.md` "L2 모듈 의존 그래프" 절(258·261행 근처): 출력 주석을 `# 출력: build/spring-modulith-docs/components.puml (전체), module-<name>.puml (모듈별)`로, `modules.html` 설명 줄을 "클릭 탐색 그래프는 아키텍처 맵 구조 탭(아래 절)으로 옮겼다"로 교체.

"업무 흐름 맵" 절(265~277행): 제목을 `## 아키텍처 맵 (build/architecture-map/, 반자동 생성)`으로, 실행 명령을 `./gradlew :test --tests 'com.kista.architecture.ArchitectureMapTest'`, 출력 주석을 `# 출력: build/architecture-map/index.html (file://로 연다, cytoscape만 CDN)`으로. 본문에 추가할 사실(문장 그대로):
- 탭 5개(구조·랜드스케이프·흐름·타임라인·생명주기) 단일 페이지. URL 해시가 전체 상태(`#structure/trading?flow=close-batch&step=lock`, `#flow/close-batch/lock`, `#timeline/TradingCloseScheduler%23run`, `#lifecycle/order/3`) — 리뷰 메모에 링크로 남길 수 있다.
- 단계 → 모듈은 `code:` 클래스 패키지에서 자동 도출(`ArchitectureMapExporter.stepModules`) — flows.yml에 쓰지 않는다. `code:` 클래스가 모듈 밖이면 테스트가 깨진다.
- 생성기 `ModuleGraphExporter`(모듈 그래프 데이터)·`ArchitectureMapExporter`(검증·잡·data.js·정적 복사), 정적 원본 `src/test/resources/architecture/map/`(뷰 파일 추가 시 Java 수정 불필요).
- 기존 `ProcessMapTest`/`ProcessMapExporter`/`process-map.html` 언급은 모두 새 이름으로 바꾼다.

`CLAUDE.md` 56행: `모듈 의존 그래프(`modules.html`)는 `ModulithArchitectureTest`가, 업무 흐름 맵(`process.html` — 랜드스케이프·스윔레인·하루 타임라인, 원본 `src/test/resources/architecture/flows.yml`)은 `ProcessMapTest`가 `build/spring-modulith-docs/`에 생성` → `아키텍처 맵(`build/architecture-map/index.html` — 모듈 구조·흐름 오버레이·랜드스케이프·스윔레인·타임라인·생명주기 단일 페이지, 원본 `src/test/resources/architecture/flows.yml`)은 `ArchitectureMapTest`가 생성`

확인: `grep -rn "modules.html\|process.html\|ProcessMapTest\|ProcessMapExporter\|module-graph.html\|process-map.html" docs CLAUDE.md AGENTS.md README.md src --include=* | grep -v docs/superpowers` → 0건.

- [ ] **Step 8: 테스트 + JS 게이트** — `ArchitectureMapTest` 좁힌 실행 + "JS 게이트".

- [ ] **Step 9: 전체 스위트 1회** — `bash gradlew test 2>&1 | grep -E "FAILED|BUILD"`. 실패 시 `grep -l 'failures="[1-9]' build/test-results/test/*.xml trading-core/build/test-results/test/*.xml`로 먼저 진짜/환경성 실패를 가른다(로컬 postgres 미기동 등 환경성 실패는 이 작업과 무관함을 보고).

- [ ] **Step 10: 브라우저 확인 1회** (`file:///C:/Users/USER/workspace/kista/kista-api/build/architecture-map/index.html`)
  1. 탭 5개 각각 렌더(콘솔 에러 0)
  2. `#flow/close-batch/lock`으로 열고 구조 탭 클릭 → 그래프 정상 크기, 오버레이가 lock 단계에 멈춤, trading 강조(platform은 숨김이라 건너뜀)
  3. 오버레이 ▶ 재생 — 점 이동·잔상·스트립 진행, 모듈 없는 단계는 스트립 점선
  4. 딥링크 왕복: 모듈 패널 → 흐름 단계 칩 → 단계 패널 모듈 칩 → 구조; 잡 `#timeline/TradingCloseScheduler%23run` → 단계 → 잡
  5. 없는 대상 해시(`#structure/nope`, `#flow/nope/x`, `#timeline/nope`, `#lifecycle/nope/99`) → 각 탭 기본 화면, 에러 없음
  6. 통합 검색 `/` → "close" → Enter
  7. 테마 토글 ◐ → 그래프·패널 모두 다크, 새로고침 후 유지
  8. 접힌 박스 클릭 → 펼침, "모두 펼치기/접기", 의존 종류 필터·infra 숨김 토글 후 오버레이 유지
  9. 원형 시계 22:30→04:30 띠가 자정을 넘어 한 호, 선형 보기 토글

- [ ] **Step 11: 리뷰 후 커밋** — `feat(architecture): 아키텍처 맵 시각 개선(원형 타임라인·노선도·모션·테마)과 문서 갱신`
