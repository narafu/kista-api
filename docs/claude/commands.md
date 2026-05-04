## 자주 쓰는 명령어

### Claude Code Bash 툴에서 Gradle 실행
# 직접 `bash gradlew ...` 실행 시 Gradle이 UNC 경로(\\wsl.localhost\...)에 .gradle 캐시를 못 만들어 실패
# 반드시 아래 WSL 래퍼 패턴 사용:
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

### .mcp.json 경로 이식성
- args에 절대경로 하드코딩 금지 — `"command": "sh", "args": ["-c", "node ${HOME}/workspace/..."]` 패턴으로 Mac/WSL 공용화
