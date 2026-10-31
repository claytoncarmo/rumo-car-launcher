"""
Prepara a publicação do maparadar.csv: uma cópia IDÊNTICA do arquivo (a licença CC BY-NC-ND não deixa distribuir
alterações) e o maparadar.json, com tamanho, SHA-256 e endereço, que o app lê para baixar e conferir.

O app processa o CSV no próprio aparelho (core/MapaRadar.kt): recorta pelos estados instalados e junta com os radares
do OSM. Nada derivado do arquivo é publicado.

Uso: python3 maparadar_manifesto.py <maparadar.csv> <pasta de saída> <url base>
"""

import hashlib
import json
import shutil
import sys
import time
from pathlib import Path


def gerar(csv, saida, url_base):
    csv, saida = Path(csv), Path(saida)
    saida.mkdir(parents=True, exist_ok=True)
    copia = saida / "maparadar.csv"
    shutil.copyfile(csv, copia)
    dados = copia.read_bytes()
    manifesto = {
        "versao": 1,
        "geradoEm": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "linhas": dados.count(b"\n"),
        "arquivo": {
            "destino": "maparadar.csv",
            "tamanho": len(dados),
            "sha256": hashlib.sha256(dados).hexdigest(),
            "url": f"{url_base.rstrip('/')}/maparadar.csv",
        },
    }
    (saida / "maparadar.json").write_text(json.dumps(manifesto, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifesto


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    gerar(*sys.argv[1:])
