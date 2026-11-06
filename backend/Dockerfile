FROM python:3.14-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    VEXTO_DATA_DIR=/data

WORKDIR /opt/vexto
COPY backend/requirements.txt backend/requirements.txt
RUN pip install --no-cache-dir -r backend/requirements.txt \
    && mkdir /data && chown 10001:10001 /data
COPY backend/*.py backend/*.sql backend/

USER 10001:10001
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "backend.api:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
