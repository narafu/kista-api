## 자주 쓰는 명령어

### macOS에서 Gradle 직접 실행
# WSL 래퍼 불필요 — macOS에서는 아래처럼 직접 실행
```bash
./gradlew bootJar
./gradlew test
./gradlew compileJava
```
# WSL 래퍼(`wsl -d Ubuntu bash -c ...`)는 Windows Claude Code 환경 전용

### Render 배포 모니터링
```bash
# 헬스 체크
curl https://kista-api.onrender.com/actuator/health
# 배포 실패 진단: mcp__render__list_deploys → status 확인 후 아래 에러 로그 조회
# mcp__render__list_logs resource=["srv-..."] type=["app"] level=["error"] — 에러만 필터링
# 서비스 ID: srv-d7sir2jbc2fs73cptpm0 (kista 워크스페이스 tea-d7sbrv3rjlhs7389pr60)
# Render MCP 로그 조회 전 반드시 먼저: mcp__render__select_workspace ownerID=tea-d7sbrv3rjlhs7389pr60
# 증상: "Connection to localhost:5432 refused" = DB_URL 환경변수 미설정
```

### Git 구조 (Claude Code 세션 필수 지식)
# kista-api와 kista-ui는 각각 독립 git 저장소 — 루트 /kista에는 git 없음
# 커밋 전 author 확인 필수: git config user.name (올바른 값: narafu), git config user.email (올바른 값: narafu@kakao.com) — 글로벌 config 자동 적용됨, 별도 설정 불필요
# application-local.yml은 .gitignore에 포함 — git add 불가, Edit 도구로 직접 수정

### Claude Code 웹 앱 (claude.ai/code) WSL2 내부 환경 전용
# 이 Claude Code 세션은 이미 WSL2 내부 → `wsl -d Ubuntu bash -c ...` 명령어 사용 불가
# Java 기본 미설치 — /tmp/jdk-21.0.5+11/ 에 JDK 캐시됨 (재부팅 시 소멸, 세션마다 확인 필요)
# JDK 없으면 재다운로드:
# curl -L "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz" | tar -xz -C /tmp/
#
# 파일 편집: /mnt/d/src/study/kista/kista-api/ (Windows D: 드라이브)
# Gradle 실행: /home/user/workspace/kista/ (Linux 네이티브 경로 — 성능·경로 호환)
# 편집 후 Gradle 실행 전 반드시 rsync 동기화:
# rsync -av --include='*.java' --include='*/' --exclude='*' \
#   /mnt/d/src/study/kista/kista-api/src/ /home/user/workspace/kista/src/
# build 설정 변경 시 추가 동기화 (spring-security 등 의존성 누락 방지):
# cp /mnt/d/src/study/kista/kista-api/build.gradle.kts /home/user/workspace/kista/
# cp /mnt/d/src/study/kista/kista-api/gradle/libs.versions.toml /home/user/workspace/kista/gradle/
# Gradle 실행 (JAVA_HOME 명시 필수):
# JAVA_HOME=/tmp/jdk-21.0.5+11 PATH="/tmp/jdk-21.0.5+11/bin:$PATH" \
#   bash /home/user/workspace/kista/kista-api/gradlew compileJava --no-daemon -p /home/user/workspace/kista/kista-api
# JAVA_HOME=/tmp/jdk-21.0.5+11 PATH="/tmp/jdk-21.0.5+11/bin:$PATH" \
#   bash /home/user/workspace/kista/kista-api/gradlew test --tests 'com.kista.architecture.*' --no-daemon -p /home/user/workspace/kista/kista-api

### Claude Code Bash 툴에서 Gradle 실행 (Windows/WSL 전용)
# WSL Ubuntu에 Java가 없어도 Git Bash에서 `bash gradlew ...` 직접 실행 가능
# — Gradle toolchain이 JDK 21을 ~/.gradle/jdks/에 자동 다운로드 (첫 실행 ~30초)
# WSL workspace(/home/user/workspace/kista)가 없어도 Git Bash에서 바로 실행됨:
bash gradlew compileTestJava --no-daemon
bash gradlew test --no-daemon
bash gradlew test --tests 'com.kista.domain.*' --no-daemon
# WSL 래퍼는 WSL 내 Java가 설치된 경우에만 필요
# wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew compileJava"
# wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew test --tests 'com.kista.SomeTest'"

```bash
# 빌드
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew bootJar"   # build/libs/app.jar 생성

# 초기 환경 설정
cp .env.example .env                          # 환경변수 파일 복사 후 값 입력 필요

# 테스트
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew test"                              # 전체 테스트 (병렬 실행)
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew compileJava"                       # 컴파일만 빠르게 확인 (테스트 없이)
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew test --tests 'com.kista.architecture.*'"    # ArchUnit 규칙 테스트만
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew test --tests 'com.kista.domain.*'"          # 도메인 단위 테스트만
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew test --tests 'com.kista.adapter.out.kis.*'" # KIS Adapter 단위 테스트만
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew test --rerun-tasks"                # 캐시 무시하고 강제 재실행 (UP-TO-DATE 우회)
# 테스트 실패 진단: stdout보다 XML이 신뢰성 높음
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && grep -oP 'failures=\"\K[^\"]+' build/test-results/test/TEST-*.xml | grep -v ':0'"

# WSL 환경에서 gradlew CRLF 오류 시 (bad interpreter: /bin/sh^M)
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && sed -i 's/\r//' gradlew"

# Qodana 결과 JSON (브라우저 없이 이슈 목록 직접 확인)
cat /mnt/c/Users/USER/AppData/Local/Temp/qodana-converter/result-allProblems.json | python3 -m json.tool

# 실행
wsl -d Ubuntu bash -c "cd /home/user/workspace/kista && bash gradlew bootRun --args='--spring.profiles.active=local'"
docker-compose up -d                          # 앱 + PostgreSQL + Prometheus + Grafana
docker-compose up -d postgres                 # DB만 기동 (로컬 개발 시)
docker compose build <service> && docker compose up -d --force-recreate <service>  # 설정 변경 후 이미지 재빌드 + 컨테이너 강제 재생성
```

### GitHub 레포 rename 후 remote URL 업데이트
```bash
gh repo rename kista-api --repo narafu/kista --yes
git remote set-url origin git@github.com:narafu/kista-api.git
```
# gh rename 후 로컬 remote는 자동 업데이트 안 됨 — 반드시 set-url 필요

### Render 서비스 재생성 시 환경변수 설정
```bash
# mcp__render__update_environment_variables 로 한번에 주입 (serviceId 필요)
# 필수 15개: SPRING_PROFILES_ACTIVE=prod, KIS_APP_KEY/SECRET/HTS_ID/VTS,
#            KIS_ACCOUNT_NO/TYPE/SYMBOL/EXCHANGE_CODE,
#            TELEGRAM_BOT_TOKEN/CHAT_ID, DB_URL/USERNAME/PASSWORD, GEMINI_API_KEY
# 주의: mcp__render__update_web_service 는 서비스명/URL 변경 미지원 → 대시보드 직접 변경
#       URL이 안 바뀌면 서비스를 새로 생성하는 것이 가장 빠른 해결책
```

### kis-trade-mcp 재시작
```bash
# 소스: ~/workspace/open-trading-api/MCP/Kis Trading MCP
docker stop kis-trade-mcp && docker rm kis-trade-mcp
docker build -t kis-trade-mcp:latest ~/workspace/open-trading-api/MCP/Kis\ Trading\ MCP
docker run -d -p 3001:3000 --name kis-trade-mcp \
  --env-file ~/workspace/open-trading-api/MCP/Kis\ Trading\ MCP/.env.live \
  -e KIS_APP_KEY=<kista .env의 KIS_APP_KEY> \
  -e "KIS_APP_SECRET=<kista .env의 KIS_APP_SECRET>" \
  -e KIS_HTS_ID=<kista .env의 KIS_HTS_ID> \
  -e KIS_ACCT_STOCK=<kista .env의 KIS_ACCOUNT_NO> \
  kis-trade-mcp:latest
# KIS_ACCOUNT_NO → KIS_ACCT_STOCK (변수명 다름 주의)
```

### .mcp.json 경로 이식성
- args에 절대경로 하드코딩 금지 — `"command": "sh", "args": ["-c", "node ${HOME}/workspace/..."]` 패턴으로 Mac/WSL 공용화
- `env` 섹션 값은 쉘 확장 없이 리터럴 문자열로 전달됨 — `${HOME}` 써도 확장 안 됨
- 환경변수 참조가 필요한 값은 `sh -c "VAR=${HOME}/... node ..."` 형태로 args에 포함
- HTTP 타입 MCP `headers` 값도 리터럴 — 토큰 하드코딩 금지
- 인증이 필요한 MCP는 `stdio` 타입 + `sh -c "npx mcp-remote <url> --header \"Authorization: Bearer ${TOKEN_VAR}\""` 패턴, 토큰은 `~/.zshrc`에 `export TOKEN_VAR=...`
- `~/.claude/settings.json`은 `mcpServers` 미지원 — 글로벌 MCP 서버는 `~/.claude/.mcp.json`에 추가
- `/doctor` "Missing environment variables" 경고는 false positive — `sh`가 부모 환경에서 자동 상속
