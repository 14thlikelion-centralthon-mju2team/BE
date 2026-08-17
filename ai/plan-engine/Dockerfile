FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# Dependencies come from pyproject.toml so the image and CI resolve the same
# pinned versions. requirements.txt exists for local development only and is
# kept in sync by tests/test_dependency_contract.py.
COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir "pip==25.2" \
    && pip install --no-cache-dir .

RUN useradd --system --uid 10001 appuser
USER appuser

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
