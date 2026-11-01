"""
Gera o dados.json (manifesto dos dados offline) e a pasta de arquivos avulsos que o workflow publica.

O app baixa cada arquivo direto para a pasta Rumo do aparelho, sem descompactar, e só os que mudaram. Cada arquivo do
manifesto tem o caminho de destino dentro da pasta Rumo, o tamanho, o SHA-256 e o endereço de download. Nome do arquivo
publicado = destino com "/" trocado por "_" (as releases do GitHub não aceitam pastas).

O manifesto tem uma lista de regiões (estados), cada uma com seus arquivos (`regioes/<id>/mapa.pmtiles` e
`regioes/<id>/vias.db`), e os arquivos comuns a todas (`comum`: as fontes dos nomes de rua).

Os nomes do manifesto (dados.json, versao, comum, regioes, arquivos, destino, tamanho…) e do layout da pasta Rumo ficam
em português: apps já instalados os leem (core/Releases.kt). Só os identificadores deste script são em inglês.

Uso: python3 manifest.py <pasta de saída> <url base> <pasta dos comuns> <id>=<nome>=<pasta Rumo da região>[=<grupo>] ...
"""

import hashlib
import json
import os
import shutil
import sys
import time

# LEIAME.txt só serve a quem copia o pacote à mão; o app não precisa dele
IGNORE = {"LEIAME.txt"}


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
            "destino": dest,
            "tamanho": os.path.getsize(full),
            "sha256": sha256(full),
            "url": f"{base_url.rstrip('/')}/{flat}",
        })
    return entries


def generate(output, base_url, common_dir, regions):
    """[regions]: lista de (id, nome, pasta Rumo da região[, grupo]). A pasta dos comuns pode ser None."""
    os.makedirs(output, exist_ok=True)
    manifest = {
        "versao": 2,
        "geradoEm": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "comum": list_files(common_dir, output, base_url) if common_dir else [],
        "regioes": [
            {"id": r[0], "nome": r[1], "grupo": r[3] if len(r) > 3 else "", "arquivos": list_files(r[2], output, base_url)}
            for r in regions
        ],
    }
    with open(os.path.join(output, "dados.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


if __name__ == "__main__":
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    output, url, common = sys.argv[1:4]
    generate(output, url, common, [tuple(a.split("=", 3)) for a in sys.argv[4:]])
