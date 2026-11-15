FROM python:3.12-slim
WORKDIR /app
COPY server/requirements*.txt /tmp/
RUN pip install --no-cache-dir -r /tmp/requirements-lock.txt
ENV ANALYSIS_MODE=gemini GEMINI_MODEL=gemini-3.8-live
COPY server /app/server
EXPOSE 10000
CMD ["sh", "-c", "exec uvicorn server.app:app --host 0.0.0.0 --port ${PORT:-10000} --workers 1 --ws-max-size 70000 --ws-max-queue 8 --no-access-log"]
