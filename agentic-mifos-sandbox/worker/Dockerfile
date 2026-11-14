# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
#
# The OpenFn worker with the adaptors the jobs use built in, from a lockfile
# (package.json, package-lock.json): the worker installs nothing at run time
# and needs no route to npm. LIGHTNING_WORKER_IMAGE in .env tests another
# worker version (docker-compose.yml passes it when it is set).
ARG LIGHTNING_WORKER_IMAGE=openfn/ws-worker:v1.29.5
FROM ${LIGHTNING_WORKER_IMAGE}
# Owned by root: the worker (node) reads the adaptors and cannot change them.
USER root
COPY package.json package-lock.json link-adaptors.js /opt/openfn/repo/
RUN cd /opt/openfn/repo \
 && npm ci --omit=dev --ignore-scripts --no-audit --no-fund --cache /tmp/npm-cache \
 && node link-adaptors.js /opt/openfn/repo \
 && rm -rf link-adaptors.js package-lock.json /tmp/npm-cache
USER node
ENV WORKER_REPO_DIR=/opt/openfn/repo
