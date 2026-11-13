#!/usr/bin/env bash
# Builds the image, runs the whole pipeline inside Docker and copies the results out of the Docker volume:
#   <out>/data/**                     the dataset, the ground truth, the manifests and the reports
#   <out>/docs/validation-report.md   the human-readable validation and evaluation report
# <out> defaults to the repository root. Nothing is bind-mounted, so this works with Docker Desktop (which only allows mounts
# from shared folders) and with a checkout on any path.
#
#   scripts/full-pipeline-docker.sh [output-dir]        NEXORA_SIZE=SMALL|MEDIUM|LARGE and NEXORA_SEED are honoured
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-.}"

docker compose --profile tools run --rm --build pipeline

holder=$(docker create -v nexoraflow-data:/app/data nexoraflow:1.0.0)   # a stopped container, only used to copy the files out
trap 'docker rm -f "$holder" >/dev/null' EXIT
mkdir -p "$out/data" "$out/docs"
docker cp "$holder:/app/data/." "$out/data/"
if [ -f "$out/data/docs/validation-report.md" ]; then
  mv "$out/data/docs/validation-report.md" "$out/docs/validation-report.md"
fi
rm -rf "$out/data/docs"
echo "Dataset copied to $out/data, report to $out/docs/validation-report.md"
