# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
#
# One-shot sandbox bootstrap (see bootstrap.py).
FROM python:3.13-alpine
RUN pip install --no-cache-dir pyyaml==6.0.2
WORKDIR /app
COPY bootstrap.py .
# Same uid as the Lightning image, so it can read the API token lightning-setup writes.
USER 1000:1000
ENTRYPOINT ["python", "-u", "/app/bootstrap.py"]
