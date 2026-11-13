#!/usr/bin/env bash
# Builds the image and runs the whole pipeline inside Docker; results appear in ./data and ./docs/validation-report.md.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose --profile tools run --rm --build pipeline
