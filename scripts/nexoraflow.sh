#!/usr/bin/env bash
# Runs a NexoraFlow pipeline command against the database configured by NEXORA_DB_* (defaults: localhost:5432).
#
#   scripts/nexoraflow.sh <command> [size] [seed]
#
#   commands: generate-all | generate-master | generate-leave | generate-expense | generate-anomalies
#             validate | evaluate | export | reproducibility-check | full-pipeline | reset
#   size:     SMALL | MEDIUM (default) | LARGE        seed: default 20261022
#
# Examples:
#   scripts/nexoraflow.sh full-pipeline                 # the challenge baseline dataset + validation + evaluation + exports
#   scripts/nexoraflow.sh generate-all SMALL 42
#   scripts/nexoraflow.sh validate
set -euo pipefail
cd "$(dirname "$0")/.."

command="${1:-full-pipeline}"
size="${2:-${NEXORA_SIZE:-MEDIUM}}"
seed="${3:-${NEXORA_SEED:-20261022}}"

# Java 21 is required (the build targets release 21).
if [[ -z "${JAVA_HOME:-}" ]] && ! java -version 2>&1 | grep -q 'version "21'; then
  echo "warning: Java 21 is expected; found: $(java -version 2>&1 | head -1)" >&2
fi

exec ./mvnw -q spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Duser.timezone=UTC" \
  -Dspring-boot.run.arguments="--nexoraflow.cli.command=${command} --nexoraflow.generator.size=${size} --nexoraflow.generator.seed=${seed} --spring.main.web-application-type=none"
