# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
#
# Loan origination service (LOS) of the agentic Mifos sandbox (see README.md).
FROM python:3.15-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py rules.py store.py ./
RUN useradd --system --uid 10001 los
USER los
EXPOSE 8000
# No access log: search URLs carry phone numbers and national IDs.
ENTRYPOINT ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
