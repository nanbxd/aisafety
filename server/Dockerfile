FROM python:3.12-slim
WORKDIR /app
COPY server/requirements*.txt /tmp/
RUN pip install --no-cache-dir -r /tmp/requirements-asr.txt
ENV ASR_MODE=whisper ASR_MODEL=tiny ASR_DEVICE=cpu ASR_COMPUTE_TYPE=int8 ASR_CACHE=/opt/models
# Download at image build, not during the first incoming call. Larger models need
# a separate image build/cache and more memory; free-tier performance is unverified.
RUN python -c "from faster_whisper import WhisperModel; WhisperModel('tiny', device='cpu', compute_type='int8', download_root='/opt/models')"
COPY server /app/server
EXPOSE 10000
CMD ["sh", "-c", "exec uvicorn server.app:app --host 0.0.0.0 --port ${PORT:-10000} --workers 1 --ws-max-size 70000 --ws-max-queue 8 --no-access-log"]
