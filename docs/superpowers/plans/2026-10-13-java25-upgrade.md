# Java 25 업그레이드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** kista-api 빌드·CI·배포 이미지를 Java 21에서 Java 25로 올리고, 그 전제인 Gradle 9.x 전환과 JDK 민감 라이브러리 patch/minor 갱신을 함께 끝낸다.

**Architecture:** 실패 원인을 분리하려고 커밋 3개로 나눈다 — ① Gradle 8.14.5 → 9.8.1(Java 21 유지) ② Java 21 → 25(toolchain·Dockerfile·CI·문서) ③ 라이브러리 patch/minor. 코드(`src/**`) 변경은 없다. 전체 테스트 스위트는 마지막에 1회만 돌린다.

**Tech Stack:** Gradle 9.8.1 (Kotlin DSL, version catalog `gradle/libs.versions.toml`), Java toolchain, Spring Boot 4.1.1, Docker `eclipse-temurin` alpine, GitHub Actions `actions/setup-java`.

**Spec:** 별도 spec 문서 없음 — 2026-10-13 세션 합의 사항(사이드 이펙트 조사 결과 포함)을 이 문서에 그대로 옮겼다.

## Global Constraints

- 대상 버전: Gradle `9.8.1`, Java `25` (Temurin)
- Docker 이미지: builder `eclipse-temurin:25-jdk-alpine`, runtime `eclipse-temurin:25-jre-alpine` (arm64 manifest 존재 확인됨 — 배포 대상 OCI가 arm64)
- 라이브러리: ArchUnit `1.5.1`, Lombok `1.18.48`, springdoc `3.1.1` + swagger-annotations `2.2.55`(짝 유지 — 카탈로그 주석 규칙), firebase-admin `9.11.0`, mockwebserver3 `5.5.0`
- 유지: Spring Boot `4.1.1`, Spring Modulith `2.1.1`, dependency-management `1.1.7`, gson `2.14.0` (이미 최신 GA)
- 범위 밖: Testcontainers 2.x(메이저 — artifact·패키지 변경, 별도 작업), MapStruct 도입(도입하지 않기로 결정), Lombok 제거
- 과거 기록 문서(`docs/superpowers/**`, `docs/reviews/**`)의 "Java 21" 서술은 수정하지 않는다
- 커밋: 한글 Conventional Commit, author `narafu <narafu@kakao.com>`, `git push` 금지(사용자 요청 시에만)
- Gradle 실행은 `bash gradlew`, `--tests` 필터는 서브프로젝트 접두사(`:test`/`:trading-core:test`) 필수

## Review Focus

1. **ArchUnit·Modulith 거짓 통과** — class 파일 major 69를 못 읽으면 import된 클래스가 0개라 규칙이 빈 집합에 대해 통과한다. 기대: 아키텍처 테스트 로그에 `Unsupported class file major version`이 없고 테스트 수가 이전과 같다 (Task 2 Step 6).
2. **Docker builder 안에서 Gradle이 JDK 25로 실행** — CI 테스트는 JDK 25 + Gradle을 호스트에서 돌리므로 컨테이너 경로를 검증하지 않는다. 기대: 로컬 `docker build`가 성공하고 두 jar가 생성된다 (Task 4 Step 2).
3. **서버 `.env`의 `JAVA_OPTS`** — 저장소 밖(서버)에만 있어 이 계획으로 검증 불가. JDK 25에서 제거된 `-XX` 옵션이 있으면 컨테이너가 기동하지 못한다. 기대: 배포 전 사용자가 서버 `.env`를 확인 (Task 4 Step 5 체크리스트).
4. **컴파일 산출물이 실제로 Java 25인지** — toolchain 설정만 바꾸고 데몬이 21로 컴파일하면 무증상. 기대: `javap`로 major version 69 확인 (Task 2 Step 5).
5. **Gradle 9 전환 후 테스트 태스크 설정 유지** — `testJvmSlot` 빌드 서비스·`integration` 태스크·`inputs.file`이 9.x에서도 그대로 동작. 기대: `tasks` 목록에 `integration`이 있고 아키텍처 테스트가 통과 (Task 1 Step 5).

---

## 파일 맵

| 파일 | 변경 | Task |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` | wrapper 재생성 | 1 |
| `build.gradle.kts:13`, `trading-core/build.gradle.kts:13`, `shared/build.gradle.kts:12` | toolchain 21 → 25 | 2 |
| `Dockerfile:2,23` | temurin 21 → 25 | 2 |
| `.github/workflows/ci.yml:54,57` | JDK 21 → 25 | 2 |
| `.github/workflows/server-deploy.yml:120` | java-version 21 → 25 | 2 |
| `README.md:4,21`, `CLAUDE.md:4` | 문서 드리프트 | 2 |
| `gradle/libs.versions.toml` | 버전 갱신 | 3 |
| `build.gradle.kts:101` | mockwebserver3 직접 좌표 버전 | 3 |
| `../CLAUDE.md:9`, `../kista-ui/README.md:9` | 레포 밖 문서 드리프트 | 4 |

---

### Task 0: 사전 조건 (사용자)

- [ ] **Step 1: JDK 25 설치** — IntelliJ `File > Project Structure > SDKs > + > Download JDK` → Vendor `Eclipse Temurin`, Version `25` → 위치 `C:\Users\USER\.jdks\temurin-25.x.x`
- [ ] **Step 2: IntelliJ 버전 확인** — Gradle 9.x·JDK 25를 지원하는 버전인지(`Help > About`). 미지원이면 IDE 업데이트 후 진행

---

### Task 1: Gradle 8.14.5 → 9.8.1 (Java 21 유지)

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` (모두 wrapper 태스크가 생성 — 손으로 고치지 않는다)

**Interfaces:**
- Consumes: 없음
- Produces: Gradle 9.8.1 wrapper — Task 2·3의 모든 명령이 이 wrapper로 실행된다

- [ ] **Step 1: 기존 데몬 정리**

```bash
cd /c/Users/USER/workspace/kista/kista-api && bash gradlew --stop
```

- [ ] **Step 2: deprecation 재확인 (2026-10-13 조사 시 0건)**

```bash
bash gradlew help --warning-mode all 2>&1 | grep -iE "deprecat|warning" || echo "NO WARNINGS"
```
Expected: `NO WARNINGS`. 경고가 나오면 멈추고 해당 경고를 먼저 해소한다.

- [ ] **Step 3: wrapper 갱신 (2회 실행 — 두 번째 실행이 새 버전으로 jar·스크립트를 재생성)**

```bash
bash gradlew wrapper --gradle-version 9.8.1
bash gradlew wrapper --gradle-version 9.8.1
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```
Expected: `distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.1-bin.zip`

- [ ] **Step 4: 클린 컴파일**

```bash
bash gradlew clean compileJava compileTestJava :trading-core:compileTestJava :shared:compileJava --warning-mode all 2>&1 | grep -E "BUILD|error:|deprecat|FAILED"
```
Expected: `BUILD SUCCESSFUL`, deprecation·error 없음

- [ ] **Step 5: 아키텍처 테스트 + 태스크 등록 확인**

```bash
bash gradlew :test --tests 'com.kista.architecture.*' 2>&1 | grep -E "BUILD|FAILED"
bash gradlew tasks --all 2>/dev/null | grep -E "^integration "
grep -ohE 'tests="[0-9]+"' build/test-results/test/TEST-com.kista.architecture.*.xml | awk -F'"' '{s+=$2} END {print "architecture tests:", s}'
```
Expected: `BUILD SUCCESSFUL`, `integration - Testcontainers PG 통합 테스트 (*IT.java)` 한 줄, `architecture tests: N` — N을 기준값으로 기록(Task 2 Step 6에서 비교)

- [ ] **Step 6: 커밋**

```bash
git add gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.jar gradlew gradlew.bat
git commit -m "$(cat <<'EOF'
build: Gradle을 9.8.1로 올린다

Java 25 toolchain은 Gradle 9.1 이상에서만 지원되므로 JDK 전환에 앞서 wrapper만 먼저 올린다.
8.14.5 기준 --warning-mode all 경고 0건으로 빌드 스크립트 변경은 없다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01X437pik1CohrM5wx3qsZJR
EOF
)"
```

---

### Task 2: Java 21 → 25

**Files:**
- Modify: `build.gradle.kts:13`, `trading-core/build.gradle.kts:13`, `shared/build.gradle.kts:12`
- Modify: `Dockerfile:2`, `Dockerfile:23`
- Modify: `.github/workflows/ci.yml:54`, `.github/workflows/ci.yml:57`, `.github/workflows/server-deploy.yml:120`
- Modify: `README.md:4`, `README.md:21`, `CLAUDE.md:4`

**Interfaces:**
- Consumes: Task 1의 Gradle 9.8.1 wrapper
- Produces: Java 25 class 파일(major 69) — Task 3 검증과 Task 4 전체 스위트의 전제

- [ ] **Step 1: toolchain 감지 확인 (Task 0 설치 결과)**

```bash
bash gradlew -q javaToolchains 2>&1 | grep -E "Language Version|Location"
```
Expected: `Language Version: 25` 항목이 하나 이상. 없으면 멈추고 Task 0 Step 1을 다시 확인한다(foojay 플러그인은 감지가 실패할 때만 검토).

- [ ] **Step 2: toolchain 3곳 변경**

세 파일 모두 동일하게:
```kotlin
// 변경 전
        languageVersion = JavaLanguageVersion.of(21)
// 변경 후
        languageVersion = JavaLanguageVersion.of(25)
```
대상: `build.gradle.kts:13`, `trading-core/build.gradle.kts:13`, `shared/build.gradle.kts:12`

- [ ] **Step 3: Dockerfile·CI 변경**

`Dockerfile:2`
```dockerfile
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-alpine AS builder
```
`Dockerfile:23`
```dockerfile
FROM eclipse-temurin:25-jre-alpine AS runtime
```
`.github/workflows/ci.yml:54,57`
```yaml
      - name: Set up JDK 25
        uses: actions/setup-java@d7793b545071e98d581d3bf084a51c3213318a07 # v4.9.0
        with:
          java-version: '25'
```
`.github/workflows/server-deploy.yml:120`
```yaml
          java-version: '25'
```

- [ ] **Step 4: 문서 드리프트 수정**

`README.md:4`
```markdown
![Java](https://img.shields.io/badge/Java-25-orange)
```
`README.md:21`
```markdown
Java 25 · Spring Boot 4 · Hexagonal Architecture · Spring Modulith · PostgreSQL 17 · Redis · Flyway · Docker Compose · OCI
```
`CLAUDE.md:4` — 문장 앞부분만 교체:
```markdown
Java 25 + Spring Boot 4 기반 Hexagonal Architecture + Spring Modulith (...이하 동일)
```

- [ ] **Step 5: 클린 컴파일 + 산출물 major version 확인**

```bash
bash gradlew clean compileJava compileTestJava :trading-core:compileTestJava :shared:compileJava 2>&1 | grep -E "BUILD|error:|FAILED"
javap -v build/classes/java/main/com/kista/KistaApplication.class | grep "major version"
javap -v trading-core/build/classes/java/main/com/kista/tradingweb/TradingApplication.class | grep "major version"
```
Expected: `BUILD SUCCESSFUL`, 두 줄 모두 `major version: 69`

- [ ] **Step 6: 아키텍처 테스트 + 거짓 통과 점검 (Review Focus 1)**

```bash
bash gradlew :test --tests 'com.kista.architecture.*' 2>&1 | grep -E "BUILD|FAILED|Unsupported class file"
grep -ohE 'tests="[0-9]+"' build/test-results/test/TEST-com.kista.architecture.*.xml | awk -F'"' '{s+=$2} END {print "architecture tests:", s}'
```
Expected: `BUILD SUCCESSFUL`, `Unsupported class file` 출력 없음, 테스트 수가 Task 1 Step 5 실행 때와 같음(Task 1에서 같은 awk 명령으로 기준값을 기록해 둔다)

- [ ] **Step 7: 커밋**

```bash
git add build.gradle.kts trading-core/build.gradle.kts shared/build.gradle.kts Dockerfile .github/workflows/ci.yml .github/workflows/server-deploy.yml README.md CLAUDE.md
git commit -m "$(cat <<'EOF'
build: Java 25로 올린다

toolchain 3곳·Docker builder/runtime 이미지·CI setup-java를 25로 바꾸고 README·CLAUDE.md 버전 표기를 맞춘다.
temurin 25 alpine은 arm64 manifest가 있어 OCI 배포 이미지에 그대로 쓸 수 있다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01X437pik1CohrM5wx3qsZJR
EOF
)"
```

---

### Task 3: JDK 민감 라이브러리 patch/minor 갱신

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts:101`

**Interfaces:**
- Consumes: Task 2의 Java 25 빌드
- Produces: 없음

- [ ] **Step 1: 버전 카탈로그 변경**

`gradle/libs.versions.toml` `[versions]`:
```toml
archunit                 = "1.5.1"
springdoc                = "3.1.1"
lombok                   = "1.18.48"
firebase-admin           = "9.11.0"
```
`[libraries]` swagger-annotations 줄:
```toml
swagger-annotations            = { module = "io.swagger.core.v3:swagger-annotations-jakarta", version = "2.2.55" } # shared contract 패키지의 @Schema용(springdoc 3.1.1이 끌어오는 버전과 동일)
```

- [ ] **Step 2: mockwebserver3 직접 좌표 변경**

`build.gradle.kts:101`
```kotlin
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
```

- [ ] **Step 3: 실제 해석 버전 확인 (BOM이 덮어쓰지 않는지)**

```bash
for d in archunit lombok swagger-annotations-jakarta springdoc-openapi-starter-webmvc-ui firebase-admin mockwebserver3; do bash gradlew -q :dependencyInsight --configuration testRuntimeClasspath --dependency $d 2>/dev/null | grep -m1 -E "^[a-z].*:[0-9]"; done
bash gradlew -q :shared:dependencyInsight --configuration compileClasspath --dependency swagger-annotations-jakarta 2>/dev/null | grep -m1 -E "^[a-z].*:[0-9]"
```
Expected: 각각 `1.5.1`, `1.18.48`, `2.2.55`, `3.1.1`, `9.11.0`, `5.5.0`. springdoc이 끌어오는 swagger와 `:shared`의 swagger가 둘 다 `2.2.55`여야 한다(어긋나면 classpath에 두 버전이 섞인다).

- [ ] **Step 4: 컴파일 + 영향 테스트**

```bash
bash gradlew compileJava compileTestJava :trading-core:compileTestJava 2>&1 | grep -E "BUILD|error:|FAILED"
bash gradlew :test --tests 'com.kista.architecture.*' 2>&1 | grep -E "BUILD|FAILED"
```
Expected: 둘 다 `BUILD SUCCESSFUL` (mockwebserver3·springdoc·firebase 영향 테스트는 Task 4 전체 스위트가 커버)

- [ ] **Step 5: 커밋**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "$(cat <<'EOF'
build: JDK 민감 라이브러리를 patch·minor 최신으로 올린다

ArchUnit 1.5.1, Lombok 1.18.48, springdoc 3.1.1(+swagger-annotations 2.2.55 짝 유지), firebase-admin 9.11.0, mockwebserver3 5.5.0.
Testcontainers 2.x는 메이저 변경이라 별도 작업으로 남긴다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01X437pik1CohrM5wx3qsZJR
EOF
)"
```

---

### Task 4: 최종 검증 (전체 스위트 1회 + Docker)

**Files:**
- Modify: `../CLAUDE.md:9`, `../kista-ui/README.md:9` (레포 밖 드리프트)

**Interfaces:**
- Consumes: Task 1~3 결과
- Produces: 없음

- [ ] **Step 1: 전체 테스트 1회 (postgres 기동 필요)**

```bash
docker compose up -d postgres redis
bash gradlew test 2>&1 | grep -E "BUILD|FAILED|Unsupported class file"
grep -l 'failures="[1-9]\|errors="[1-9]' build/test-results/test/*.xml trading-core/build/test-results/test/*.xml 2>/dev/null
```
Expected: `BUILD SUCCESSFUL`, 실패 XML 없음. 실패 시 XML로 환경성 실패(DB 연결)와 진짜 실패를 먼저 구분한다 — 같은 전체 스위트를 바로 재실행하지 않는다.

- [ ] **Step 2: 로컬 Docker 빌드 (Review Focus 2)**

```bash
docker build -t kista-api:java25-check . 2>&1 | grep -E "ERROR|BUILD|naming to|FAILED" 
docker run --rm --entrypoint sh kista-api:java25-check -c "java -version 2>&1 | head -1; ls -1 /app"
```
Expected: 빌드 성공, `openjdk version "25...`, `app.jar`·`trading-core.jar` 둘 다 존재

- [ ] **Step 3: 레포 밖 문서 드리프트 수정 (커밋 대상 아님 / kista-ui는 별도 레포 커밋)**

`../CLAUDE.md:9`
```
├── kista-api/   # Java 25 + Spring Boot 4 (독립 git)
```
`../kista-ui/README.md:9` — `(Java 21 + Spring Boot 4)` → `(Java 25 + Spring Boot 4)`, kista-ui 레포에서 별도 커밋:
```bash
git -C ../kista-ui add README.md
git -C ../kista-ui commit -m "$(cat <<'EOF'
docs: 백엔드 Java 버전 표기를 25로 맞춘다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01X437pik1CohrM5wx3qsZJR
EOF
)"
```

- [ ] **Step 4: 커밋 전 검수** — 3개 커밋 diff(`git diff HEAD~3`)를 직접 재검토하거나 Sonnet 리뷰어 1개로 확인. 코드 변경이 없는 빌드 설정 diff라 멀티에이전트 리뷰는 불필요.

- [ ] **Step 5: 배포 전 사용자 체크리스트 (Review Focus 3)**
  - 서버 `.env`의 `JAVA_OPTS`에 JDK 25에서 제거된 `-XX` 옵션이 없는지 확인
  - 첫 배포 후 `docker logs`에서 `Starting KistaApplication using Java 25` 확인 — `sun.misc.Unsafe`·native access 경고는 무해(Netty/Lettuce)
  - `git push`는 사용자 요청 시에만
