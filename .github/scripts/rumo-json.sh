#!/usr/bin/env bash
# Gera o rumo.json (manifesto do app) para publicar ao lado do APK: versão, hash, tamanho e endereço de download.
# O app lê esse arquivo para saber se há versão nova (releases/latest/download/rumo.json no canal estável e
# releases/download/nightly/rumo.json no nightly).
#
# Uso: bash rumo-json.sh <apk> <versionName> <versionCode> <url do apk> <arquivo de saída> [release notes]
# As release notes (release-notes/next.md ou X.Y.Z.md, validadas por release-notes.sh) viram o campo "notes": uma lista
# de textos curtos que o app mostra no aviso de versão nova. Sem o arquivo, a lista sai vazia.
set -euo pipefail

apk="$1"
name="$2"
code="$3"
url="$4"
output="$5"
notes_file="${6:-}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
notes="[]"
if [ -n "$notes_file" ]; then
  notes="$(bash "$script_dir/release-notes.sh" "$notes_file" | jq -R . | jq -s .)"
fi

jq -n \
  --argjson notes "$notes" \
  --arg versionName "$name" \
  --argjson versionCode "$code" \
  --arg sha256 "$(sha256sum "$apk" | cut -d' ' -f1)" \
  --argjson size "$(stat -c%s "$apk")" \
  --arg url "$url" \
  --arg builtAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg commit "${GITHUB_SHA:0:7}" \
  '{versionName: $versionName, versionCode: $versionCode, sha256: $sha256, size: $size, url: $url, builtAt: $builtAt, commit: $commit, notes: $notes}' \
  > "$output"

cat "$output"
