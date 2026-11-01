"""
Prepara a publicação do maparadar.csv: uma cópia IDÊNTICA do arquivo (a licença CC BY-NC-ND não deixa distribuir
alterações) e o maparadar.json, com tamanho, SHA-256 e endereço, que o app lê para baixar e conferir.

O app processa o CSV no próprio aparelho (core/MapaRadar.kt): recorta pelos estados instalados e junta com os radares
do OSM. Nada derivado do arquivo é publicado.

Uso: python3 maparadar_manifest.py <maparadar.csv> <pasta de saída> <url base>
"""

import hashlib
import json
import shutil
import sys
import time
from pathlib import Path


def generate(csv, output, base_url):
    csv, output = Path(csv), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    copied = output / "maparadar.csv"
    shutil.copyfile(csv, copied)
    content = copied.read_bytes()
    manifest = {
        "version": 1,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "lines": content.count(b"\n"),
        "file": {
            "dest": "maparadar.csv",
            "size": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
            "url": f"{base_url.rstrip('/')}/maparadar.csv",
        },
    }
    (output / "maparadar.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    generate(*sys.argv[1:])
