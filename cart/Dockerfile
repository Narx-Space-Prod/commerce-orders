FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f AS dependencies
WORKDIR /build
COPY requirements*.txt ./
RUN pip wheel --require-hashes --no-cache-dir --wheel-dir=/wheels -r requirements-dev.txt

FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f AS test
WORKDIR /src
COPY --from=dependencies /wheels /wheels
COPY requirements*.txt ./
RUN pip install --require-hashes --no-cache-dir --no-index --find-links=/wheels -r requirements-dev.txt
COPY app.py ./
COPY tests ./tests
ENV PYTHONDONTWRITEBYTECODE=1
RUN python -m pytest -q tests -o cache_dir=/tmp/pytest-cache

FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f AS runtime
WORKDIR /app
COPY --from=dependencies /wheels /wheels
COPY requirements.txt ./
RUN pip install --require-hashes --no-cache-dir --no-index --find-links=/wheels -r requirements.txt && rm -rf /wheels
COPY --from=test --chown=10001:10001 /src/app.py ./
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001:10001
EXPOSE 8082
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8082", "--no-access-log"]
