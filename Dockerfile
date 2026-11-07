FROM python:3.10-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt-get/lists/*

COPY ai-training/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN pip install --no-cache-dir kagglehub

COPY ai-training/ ./ai-training/

EXPOSE 8000

CMD ["uvicorn", "ai-training.main:app", "--host", "0.0.0.0", "--port", "8000"]
