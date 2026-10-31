"""
Gera o dados.json (manifesto dos dados offline) e a pasta de arquivos avulsos que o workflow publica.

O app baixa cada arquivo direto para a pasta Rumo do aparelho, sem descompactar, e só os que mudaram. Cada arquivo do
manifesto tem o caminho de destino dentro da pasta Rumo, o tamanho, o SHA-256 e o endereço de download. Nome do arquivo
publicado = destino com "/" trocado por "_" (as releases do GitHub não aceitam pastas).

Uso: python3 manifesto.py <pasta Rumo> <pasta de saída> <url base> <id da região> "<nome da região>"
"""

import hashlib
import json
import os
import shutil
import sys
import time

# LEIAME.txt só serve a quem copia o pacote à mão; o app não precisa dele
IGNORAR = {"LEIAME.txt"}


def sha256(caminho):
    h = hashlib.sha256()
    with open(caminho, "rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            h.update(bloco)
    return h.hexdigest()


def arquivos(pasta):
    """(destino, caminho completo) de cada arquivo útil da pasta Rumo, em ordem estável."""
    achados = []
    for raiz, _, nomes in os.walk(pasta):
        for nome in nomes:
            completo = os.path.join(raiz, nome)
            destino = os.path.relpath(completo, pasta).replace(os.sep, "/")
            if destino not in IGNORAR:
                achados.append((destino, completo))
    return sorted(achados)


def gerar(pasta, saida, url_base, regiao_id, regiao_nome):
    os.makedirs(saida, exist_ok=True)
    lista = []
    for destino, completo in arquivos(pasta):
        avulso = destino.replace("/", "_")
        shutil.copyfile(completo, os.path.join(saida, avulso))
        lista.append({
            "destino": destino,
            "tamanho": os.path.getsize(completo),
            "sha256": sha256(completo),
            "url": f"{url_base.rstrip('/')}/{avulso}",
        })
    manifesto = {
        "versao": 1,
        "geradoEm": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "regioes": [{"id": regiao_id, "nome": regiao_nome, "arquivos": lista}],
    }
    with open(os.path.join(saida, "dados.json"), "w", encoding="utf-8") as f:
        json.dump(manifesto, f, ensure_ascii=False, indent=2)
    return manifesto


if __name__ == "__main__":
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    gerar(*sys.argv[1:])
