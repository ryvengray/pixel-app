#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$project_root"

compose=(docker compose -f compose.yaml -f compose.decision.yaml)

if [[ ! -f server/.env ]]; then
  printf 'Error: server/.env is missing. Create it from server/.env.example first.\n' >&2
  exit 1
fi

services=(greta-data-permissions greta-service pixel-decision)

printf 'Building and deploying: %s\n' "${services[*]}"
"${compose[@]}" up -d --build "${services[@]}"

printf '\nFollowing logs (Ctrl+C to stop): %s\n' "${services[*]}"
"${compose[@]}" logs --follow --tail=100 "${services[@]}"
