#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$project_root"

compose=(docker compose -f compose.yaml -f compose.decision.yaml)

if [[ ! -f server/.env ]]; then
  printf 'Error: server/.env is missing. Create it from server/.env.example first.\n' >&2
  exit 1
fi

services=(greta-service)
if [[ -n "$("${compose[@]}" ps --all --quiet pixel-decision)" ]]; then
  services+=(pixel-decision)
fi

printf 'Restarting: %s\n' "${services[*]}"
"${compose[@]}" restart "${services[@]}"
