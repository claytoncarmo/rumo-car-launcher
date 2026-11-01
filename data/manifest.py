"""
Gera o data.json (manifesto dos dados offline) e a pasta de arquivos avulsos que o workflow publica.

O app baixa cada arquivo direto para a pasta Rumo do aparelho, sem descompactar, e só os que mudaram. Cada arquivo do
manifesto tem o caminho de destino dentro da pasta Rumo, o tamanho, o SHA-256 e o endereço de download. Nome do arquivo
publicado = destino com "/" trocado por "_" (as releases do GitHub não aceitam pastas).

O manifesto tem uma lista de regiões (estados), cada uma com seus arquivos (`regions/<id>/map.pmtiles` e
`regions/<id>/roads.db`), e os arquivos comuns a todas (`common`: as fontes dos nomes de rua).

Uso: python3 manifest.py <pasta de saída> <url base> <pasta dos comuns> <id>=<nome>=<pasta Rumo da região>[=<grupo>] ...
"""

import hashlib
import json
import os
import shutil
import sys
import time

# README.txt só serve a quem copia o pacote à mão; o app não precisa dele
IGNORE = {"README.txt"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def files(folder):
    """(destino, caminho completo) de cada arquivo útil da pasta Rumo, em ordem estável."""
    found = []
    for root, _, names in os.walk(folder):
        for name in names:
            full = os.path.join(root, name)
            dest = os.path.relpath(full, folder).replace(os.sep, "/")
            if dest not in IGNORE:
                found.append((dest, full))
    return sorted(found)


def list_files(folder, output, base_url):
    """Copia os arquivos da pasta Rumo para a saída (nomes avulsos) e devolve a lista do manifesto."""
    entries = []
    for dest, full in files(folder):
        flat = dest.replace("/", "_")
        shutil.copyfile(full, os.path.join(output, flat))
        entries.append({
            "dest": dest,
            "size": os.path.getsize(full),
            "sha256": sha256(full),
            "url": f"{base_url.rstrip('/')}/{flat}",
        })
    return entries


def generate(output, base_url, common_dir, regions):
    """[regions]: lista de (id, nome, pasta Rumo da região[, grupo]). A pasta dos comuns pode ser None."""
    os.makedirs(output, exist_ok=True)
    manifest = {
        "version": 3,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "common": list_files(common_dir, output, base_url) if common_dir else [],
        "regions": [
            {"id": r[0], "name": r[1], "group": r[3] if len(r) > 3 else "", "files": list_files(r[2], output, base_url)}
            for r in regions
        ],
    }
    with open(os.path.join(output, "data.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


if __name__ == "__main__":
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    output, url, common = sys.argv[1:4]
    generate(output, url, common, [tuple(a.split("=", 3)) for a in sys.argv[4:]])
