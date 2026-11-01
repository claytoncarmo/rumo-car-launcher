"""
Enxuga o map.pmtiles: deixa só o que o estilo do app desenha (DriveMap.kt).

O mapa-múndi do Protomaps traz prédios, pontos de interesse, fronteiras, nomes de lugares, trilhas, calçadas, parques
etc. O Rumo só desenha água (polígonos e rios), as vias para carro e o nome delas; o resto só ocupa espaço. Este passo
abre cada tile, tira o que não é usado e grava um pmtiles novo, mais de 3 vezes menor.

Se mudar o que o estilo desenha, mude também `filter_layers`.

Uso: python3 map_filter.py <entrada.pmtiles> <saída.pmtiles>
"""

import gzip
import os
import sys
from multiprocessing import Pool

# Vias que o estilo desenha (o resto de "roads" são trilhas, calçadas, trilhos, pistas de aeroporto...)
ROADS = {"minor_road", "major_road", "highway"}
# Vias de serviço (garagens, estacionamentos) ficam fora do desenho; continuam no roads.db para o limite de velocidade
DETAIL_OUT = {"service"}
# Água em polígono: tudo menos piscinas e fontes. Em linha: só rios e canais (riachos pesam metade da água)
WATER_OUT = {"swimming_pool", "fountain"}
WATER_LINE = {"river", "canal"}
# O estilo só escreve o nome das ruas a partir do zoom 14
NAME_FROM_ZOOM = 14

POLYGONS = ("Polygon", "MultiPolygon")
# Coordenadas na grade do tile (y para baixo), sem converter para graus
DECODE = {"y_coord_down": True}


def filter_layers(z, layers):
    """layers: nome -> {"extent": int, "features": [{"geometry": geojson, "properties": dict}]}. Devolve o que fica."""
    out = {}
    water = []
    for f in layers.get("water", {}).get("features", []):
        geometry_type = f["geometry"]["type"]
        kind = f["properties"].get("kind")
        if geometry_type in POLYGONS:
            keep = kind not in WATER_OUT
        else:
            keep = geometry_type in ("LineString", "MultiLineString") and kind in WATER_LINE
        if keep:
            water.append({"geometry": f["geometry"], "properties": {"kind": kind}})
    if water:
        out["water"] = {"extent": layers["water"].get("extent", 4096), "features": water}

    roads = []
    for f in layers.get("roads", {}).get("features", []):
        p = f["properties"]
        if p.get("kind") not in ROADS or p.get("kind_detail") in DETAIL_OUT:
            continue
        props = {"kind": p["kind"]}
        if z >= NAME_FROM_ZOOM and p.get("name"):
            props["name"] = p["name"]
        roads.append({"geometry": f["geometry"], "properties": props})
    if roads:
        out["roads"] = {"extent": layers["roads"].get("extent", 4096), "features": roads}
    return out


def _encode(layers):
    import mapbox_vector_tile as mvt
    from shapely.geometry import shape

    encoded = []
    options = {}
    for name, layer in layers.items():
        features = [{"geometry": shape(f["geometry"]), "properties": f["properties"]} for f in layer["features"]]
        encoded.append({"name": name, "features": features})
        options[name] = {"extents": layer["extent"]}
    default = {"y_coord_down": True, "on_invalid_geometry": lambda g: None}
    return gzip.compress(mvt.encode(encoded, per_layer_options=options, default_options=default), mtime=0)


def process(item):
    import mapbox_vector_tile as mvt

    tile_id, z, data = item
    layers = mvt.decode(gzip.decompress(data), default_options=DECODE)
    kept = filter_layers(z, layers)
    return tile_id, (_encode(kept) if kept else None)


def convert(source, target, processes=None):
    from pmtiles.reader import MmapSource, Reader, all_tiles
    from pmtiles.tile import zxy_to_tileid
    from pmtiles.writer import write

    with open(source, "rb") as fh:
        pm_source = MmapSource(fh)
        reader = Reader(pm_source)
        header = dict(reader.header())
        metadata = reader.metadata()

        def tiles():
            for (z, x, y), data in all_tiles(pm_source):
                yield zxy_to_tileid(z, x, y), z, data

        total = written = 0
        with write(target) as writer, Pool(processes or os.cpu_count()) as pool:
            # imap mantém a ordem dos tiles, que o pmtiles pede crescente
            for tile_id, new in pool.imap(process, tiles(), chunksize=32):
                total += 1
                if new:
                    writer.write_tile(tile_id, new)
                    written += 1
                if total % 20000 == 0:
                    print(f"{total} tiles lidos, {written} gravados", flush=True)
            metadata = dict(metadata)
            metadata["vector_layers"] = [
                {"id": "water", "fields": {"kind": "String"}},
                {"id": "roads", "fields": {"kind": "String", "name": "String"}},
            ]
            writer.finalize(header, metadata)
        print(f"{total} tiles lidos, {written} gravados; {os.path.getsize(source) / 1e6:.0f} MB -> "
              f"{os.path.getsize(target) / 1e6:.0f} MB", flush=True)
        return total, written


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2])
