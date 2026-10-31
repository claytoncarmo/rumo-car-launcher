#!/usr/bin/env bash
# Gera o rumo.json (manifesto do app) para publicar ao lado do APK: versão, hash, tamanho e endereço de download.
# O app lê esse arquivo para saber se há versão nova (releases/latest/download/rumo.json no canal estável e
# releases/download/nightly/rumo.json no nightly).
#
# Uso: bash rumo-json.sh <apk> <versionName> <versionCode> <url do apk> <arquivo de saída>
set -euo pipefail

apk="$1"
nome="$2"
codigo="$3"
url="$4"
saida="$5"

jq -n \
  --arg versionName "$nome" \
  --argjson versionCode "$codigo" \
  --arg sha256 "$(sha256sum "$apk" | cut -d' ' -f1)" \
  --argjson size "$(stat -c%s "$apk")" \
  --arg url "$url" \
  --arg builtAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg commit "${GITHUB_SHA:0:7}" \
  '{versionName: $versionName, versionCode: $versionCode, sha256: $sha256, size: $size, url: $url, builtAt: $builtAt, commit: $commit}' \
  > "$saida"

cat "$saida"
