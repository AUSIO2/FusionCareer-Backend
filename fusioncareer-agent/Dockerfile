FROM python:3.11-slim

WORKDIR /app

RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g; s|security.debian.org|mirrors.tuna.tsinghua.edu.cn|g' \
      /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends \
    libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml .
COPY app/ app/
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple --timeout 300 ".[ocr]"

COPY UPSTREAM.md ./
COPY THIRD_PARTY_LICENSES/ THIRD_PARTY_LICENSES/

RUN useradd --create-home --uid 10001 fusioncareer \
    && mkdir -p /data/agent/runtime /data/wechat /data/paddle \
    && chown -R fusioncareer:fusioncareer /app /data

ENV AGENT_RUNTIME_DIR=/data/agent/runtime \
    WECHAT_CONFIG_ROOT=/data/wechat \
    PADDLE_PDX_CACHE_HOME=/data/paddle

USER fusioncareer

EXPOSE 8900

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8900/api/health', timeout=3)"]

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8900"]
