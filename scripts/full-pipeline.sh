#!/usr/bin/env bash
# Complete deterministic pipeline on the local database: reset -> generate -> validate -> evaluate -> export -> reports.
#   scripts/full-pipeline.sh [size] [seed]
# Writes ./data/** and ./docs/validation-report.md.
set -euo pipefail
cd "$(dirname "$0")/.."
exec scripts/nexoraflow.sh full-pipeline "${1:-${NEXORA_SIZE:-MEDIUM}}" "${2:-${NEXORA_SEED:-20261022}}"
