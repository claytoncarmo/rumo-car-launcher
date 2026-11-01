"""
Confere o mapa filtrado contra o bruto: numa amostra de tiles, o que `map_filter.filter_layers` manda ficar tem de estar
no tile gravado (a recodificação descarta, sem avisar, geometrias que o shapely considera inválidas).

Uso: python3 map_check.py <bruto.pmtiles> <filtrado.pmtiles> [amostra] [perda máxima em %]
"""

import gzip
import random
import sys

import map_filter as mf


def count(layers):
    return {name: len(c["features"]) for name, c in layers.items()}


def check(raw, filtered, sample=400, max_loss=1.0):
    import mapbox_vector_tile as mvt
    from pmtiles.reader import MmapSource, all_tiles

    with open(raw, "rb") as fr, open(filtered, "rb") as ff:
        new = {coord: data for coord, data in all_tiles(MmapSource(ff))}
        keys = [coord for coord, _ in all_tiles(MmapSource(fr))]
        random.seed(7)
        expected, found = {}, {}
        missing_tiles = 0
        chosen = set(random.sample(keys, min(sample, len(keys))))
        for coord, data in all_tiles(MmapSource(fr)):
            if coord not in chosen:
                continue
            would_stay = mf.filter_layers(coord[0], mvt.decode(gzip.decompress(data), default_options=mf.DECODE))
            if not would_stay:
                continue
            if coord not in new:
                missing_tiles += 1
                continue
            actual = mvt.decode(gzip.decompress(new[coord]), default_options=mf.DECODE)
            for name, n in count(would_stay).items():
                expected[name] = expected.get(name, 0) + n
                found[name] = found.get(name, 0) + len(actual.get(name, {}).get("features", []))
    ok = missing_tiles == 0
    print(f"amostra de {min(sample, len(keys))} tiles; tiles que deviam existir e sumiram: {missing_tiles}")
    for name in sorted(expected):
        loss = 100 * (expected[name] - found[name]) / max(expected[name], 1)
        print(f"  {name}: esperadas {expected[name]}, gravadas {found[name]} (perda {loss:.2f}%)")
        ok = ok and loss <= max_loss
    return ok


if __name__ == "__main__":
    args = sys.argv[1:]
    ok = check(args[0], args[1], int(args[2]) if len(args) > 2 else 400, float(args[3]) if len(args) > 3 else 1.0)
    sys.exit(0 if ok else 1)
