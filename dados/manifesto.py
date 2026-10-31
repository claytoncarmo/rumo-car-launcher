"""
Gera o dados.json (manifesto dos dados offline) e a pasta de arquivos avulsos que o workflow publica.

O app baixa cada arquivo direto para a pasta Rumo do aparelho, sem descompactar, e só os que mudaram. Cada arquivo do
manifesto tem o caminho de destino dentro da pasta Rumo, o tamanho, o SHA-256 e o endereço de download. Nome do arquivo
publicado = destino com "/" trocado por "_" (as releases do GitHub não aceitam pastas).

O manifesto tem uma lista de regiões (estados), cada uma com seus arquivos (`regioes/<id>/mapa.pmtiles` e
`regioes/<id>/vias.db`), e os arquivos comuns a todas (`comum`: as fontes dos nomes de rua).

Uso: python3 manifesto.py <pasta de saída> <url base> <pasta dos comuns> <id>=<nome>=<pasta Rumo da região>[=<grupo>] ...
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


def listar(pasta, saida, url_base):
    """Copia os arquivos da pasta Rumo para a saída (nomes avulsos) e devolve a lista do manifesto."""
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
    return lista


def gerar(saida, url_base, pasta_comum, regioes):
    """[regioes]: lista de (id, nome, pasta Rumo da região[, grupo]). A pasta dos comuns pode ser None."""
    os.makedirs(saida, exist_ok=True)
    manifesto = {
        "versao": 2,
        "geradoEm": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "comum": listar(pasta_comum, saida, url_base) if pasta_comum else [],
        "regioes": [
            {"id": r[0], "nome": r[1], "grupo": r[3] if len(r) > 3 else "", "arquivos": listar(r[2], saida, url_base)}
            for r in regioes
        ],
    }
    with open(os.path.join(saida, "dados.json"), "w", encoding="utf-8") as f:
        json.dump(manifesto, f, ensure_ascii=False, indent=2)
    return manifesto


if __name__ == "__main__":
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    saida, url, comum = sys.argv[1:4]
    gerar(saida, url, comum, [tuple(a.split("=", 3)) for a in sys.argv[4:]])
