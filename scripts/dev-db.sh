#!/usr/bin/env bash
# Starts (or restarts) a throw-away local PostgreSQL 16 matching the application defaults.
#   scripts/dev-db.sh [port]      stop with:  docker rm -f nexoraflow-dev-db
set -euo pipefail
port="${1:-5432}"
docker rm -f nexoraflow-dev-db >/dev/null 2>&1 || true
docker run -d --name nexoraflow-dev-db \
  -e POSTGRES_DB=nexoraflow -e POSTGRES_USER=nexoraflow -e POSTGRES_PASSWORD=nexoraflow_dev \
  -p "${port}:5432" postgres:16-alpine
echo "PostgreSQL listening on localhost:${port} (database nexoraflow, user nexoraflow)."
echo "If you used a port other than 5432:  export NEXORA_DB_URL=jdbc:postgresql://localhost:${port}/nexoraflow"
