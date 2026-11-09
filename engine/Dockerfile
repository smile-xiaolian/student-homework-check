FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY checker ./checker
RUN useradd --create-home --uid 10001 checker
USER checker
CMD ["python", "-m", "checker.worker"]
