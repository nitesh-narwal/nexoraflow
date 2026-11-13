#!/usr/bin/env bash
# Runs the complete test suite (unit + integration + acceptance). Integration tests start PostgreSQL through
# Testcontainers, so a running Docker daemon is required.
#
# If your Docker CLI points at Docker Desktop but the daemon you want is the system one, export
#   DOCKER_HOST=unix:///var/run/docker.sock
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./mvnw verify "$@"
