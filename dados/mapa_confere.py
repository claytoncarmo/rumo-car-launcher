"""
Confere o mapa filtrado contra o bruto: numa amostra de tiles, o que `mapa_filtro.filtrar` manda ficar tem de estar no
tile gravado (a recodificação descarta, sem avisar, geometrias que o shapely considera inválidas).

Uso: python3 mapa_confere.py <bruto.pmtiles> <filtrado.pmtiles> [amostra] [perda máxima em %]
"""

import gzip
import random
import sys

import mapa_filtro as mf


def contar(camadas):
    return {nome: len(c["features"]) for nome, c in camadas.items()}


def conferir(bruto, filtrado, amostra=400, perda_maxima=1.0):
    import mapbox_vector_tile as mvt
    from pmtiles.reader import MmapSource, all_tiles

    with open(bruto, "rb") as fb, open(filtrado, "rb") as ff:
        novos = {coord: dados for coord, dados in all_tiles(MmapSource(ff))}
        chaves = [coord for coord, _ in all_tiles(MmapSource(fb))]
        random.seed(7)
        esperado, achado = {}, {}
        sem_tile = 0
        escolhidos = set(random.sample(chaves, min(amostra, len(chaves))))
        for coord, dados in all_tiles(MmapSource(fb)):
            if coord not in escolhidos:
                continue
            ficaria = mf.filtrar(coord[0], mvt.decode(gzip.decompress(dados), default_options=mf.DECODE))
            if not ficaria:
                continue
            if coord not in novos:
                sem_tile += 1
                continue
            real = mvt.decode(gzip.decompress(novos[coord]), default_options=mf.DECODE)
            for nome, n in contar(ficaria).items():
                esperado[nome] = esperado.get(nome, 0) + n
                achado[nome] = achado.get(nome, 0) + len(real.get(nome, {}).get("features", []))
    ok = sem_tile == 0
    print(f"amostra de {min(amostra, len(chaves))} tiles; tiles que deviam existir e sumiram: {sem_tile}")
    for nome in sorted(esperado):
        perda = 100 * (esperado[nome] - achado[nome]) / max(esperado[nome], 1)
        print(f"  {nome}: esperadas {esperado[nome]}, gravadas {achado[nome]} (perda {perda:.2f}%)")
        ok = ok and perda <= perda_maxima
    return ok


if __name__ == "__main__":
    args = sys.argv[1:]
    ok = conferir(args[0], args[1], int(args[2]) if len(args) > 2 else 400, float(args[3]) if len(args) > 3 else 1.0)
    sys.exit(0 if ok else 1)
