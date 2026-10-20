FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TOKENIZERS_PARALLELISM=false HF_HUB_OFFLINE=1
WORKDIR /app
RUN pip install --no-cache-dir torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
COPY decision/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
COPY app/__init__.py app/decision.py app/decision_tools.json ./app/
COPY decision_api.py .
RUN useradd --uid 10001 --create-home router
USER router
CMD ["uvicorn", "decision_api:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
