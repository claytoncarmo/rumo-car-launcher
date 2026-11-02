#!/usr/bin/env bash
# Lê um arquivo de release notes (release-notes/next.md ou release-notes/X.Y.Z.md), confere as regras de
# release-notes/README.md e imprime os itens, um por linha, sem o "- " do começo.
# Linhas em branco e as que começam com "#" são ignoradas; as demais precisam ser "- texto".
#
# Uso: bash release-notes.sh <arquivo> [required]
#   required: o arquivo precisa existir e ter pelo menos um item (release estável). Sem isso, arquivo ausente ou
#   vazio não imprime nada e termina com sucesso (nightly).
set -euo pipefail
export LC_ALL=C.UTF-8   # ${#linha} conta caracteres, não bytes

file="$1"
required="${2:-}"
max_items=6
max_chars=80

if [ ! -f "$file" ]; then
  if [ "$required" = "required" ]; then
    echo "::error::Faltam as release notes: crie $file (veja release-notes/README.md)" >&2
    exit 1
  fi
  exit 0
fi

count=0
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in
    "" | "#"*) continue ;;
    "- "*) ;;
    *)
      echo "::error file=$file::Linha fora do formato (use \"- texto\"): $line" >&2
      exit 1
      ;;
  esac
  item="${line#- }"
  if [ "${#item}" -gt "$max_chars" ]; then
    echo "::error file=$file::Item com mais de $max_chars caracteres (${#item}): $item" >&2
    exit 1
  fi
  count=$((count + 1))
  printf '%s\n' "$item"
done < "$file"

if [ "$count" -gt "$max_items" ]; then
  echo "::error file=$file::Mais de $max_items itens ($count): as notas precisam ser curtas" >&2
  exit 1
fi
if [ "$count" -eq 0 ] && [ "$required" = "required" ]; then
  echo "::error file=$file::Nenhum item nas release notes" >&2
  exit 1
fi
