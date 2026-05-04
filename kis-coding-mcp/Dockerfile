# 공식 KIS Code Assistant MCP 서버 Dockerfile
# 출처: https://github.com/koreainvestment/open-trading-api/tree/main/MCP/KIS%20Code%20Assistant%20MCP
# HTTP MCP 모드(포트 8081)로 실행 → Claude Code에서 http://localhost:8081/mcp 로 연결

FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y gcc g++ git && rm -rf /var/lib/apt/lists/*

RUN pip install uv

# 공식 레포에서 MCP/KIS Code Assistant MCP 디렉토리만 sparse-checkout
RUN git clone --depth=1 --filter=blob:none --no-checkout \
    https://github.com/koreainvestment/open-trading-api.git /tmp/kis-repo && \
    cd /tmp/kis-repo && \
    git sparse-checkout init --cone && \
    git sparse-checkout set "MCP/KIS Code Assistant MCP" && \
    git checkout && \
    cp -r "MCP/KIS Code Assistant MCP/." /app/ && \
    rm -rf /tmp/kis-repo

# 의존성 설치
RUN if [ -f uv.lock ]; then uv sync --frozen; else uv sync; fi

ENV PYTHONPATH=/app
ENV PORT=8081

EXPOSE 8081

CMD ["uv", "run", "server.py"]
