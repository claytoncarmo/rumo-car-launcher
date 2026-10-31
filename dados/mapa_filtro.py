"""
Enxuga o mapa.pmtiles: deixa só o que o estilo do app desenha (DriveMap.kt).

O mapa-múndi do Protomaps traz prédios, pontos de interesse, fronteiras, nomes de lugares, trilhas, calçadas, parques
etc. O Rumo só desenha água (polígonos e rios), as vias para carro e o nome delas; o resto só ocupa espaço. Este passo
abre cada tile, tira o que não é usado e grava um pmtiles novo, mais de 3 vezes menor.

Se mudar o que o estilo desenha, mude também `filtrar`.

Uso: python3 mapa_filtro.py <entrada.pmtiles> <saída.pmtiles>
"""

import gzip
import os
import sys
from multiprocessing import Pool

# Vias que o estilo desenha (o resto de "roads" são trilhas, calçadas, trilhos, pistas de aeroporto...)
VIAS = {"minor_road", "major_road", "highway"}
# Vias de serviço (garagens, estacionamentos) ficam fora do desenho; continuam no vias.db para o limite de velocidade
DETALHE_FORA = {"service"}
# Água em polígono: tudo menos piscinas e fontes. Em linha: só rios e canais (riachos pesam metade da água)
AGUA_FORA = {"swimming_pool", "fountain"}
AGUA_LINHA = {"river", "canal"}
# O estilo só escreve o nome das ruas a partir do zoom 14
NOME_A_PARTIR_DO_ZOOM = 14

POLIGONOS = ("Polygon", "MultiPolygon")
# Coordenadas na grade do tile (y para baixo), sem converter para graus
DECODE = {"y_coord_down": True}


def filtrar(z, camadas):
    """camadas: nome -> {"extent": int, "features": [{"geometry": geojson, "properties": dict}]}. Devolve o que fica."""
    saida = {}
    agua = []
    for f in camadas.get("water", {}).get("features", []):
        tipo = f["geometry"]["type"]
        kind = f["properties"].get("kind")
        if tipo in POLIGONOS:
            ficar = kind not in AGUA_FORA
        else:
            ficar = tipo in ("LineString", "MultiLineString") and kind in AGUA_LINHA
        if ficar:
            agua.append({"geometry": f["geometry"], "properties": {"kind": kind}})
    if agua:
        saida["water"] = {"extent": camadas["water"].get("extent", 4096), "features": agua}

    vias = []
    for f in camadas.get("roads", {}).get("features", []):
        p = f["properties"]
        if p.get("kind") not in VIAS or p.get("kind_detail") in DETALHE_FORA:
            continue
        props = {"kind": p["kind"]}
        if z >= NOME_A_PARTIR_DO_ZOOM and p.get("name"):
            props["name"] = p["name"]
        vias.append({"geometry": f["geometry"], "properties": props})
    if vias:
        saida["roads"] = {"extent": camadas["roads"].get("extent", 4096), "features": vias}
    return saida


def _codificar(camadas):
    import mapbox_vector_tile as mvt
    from shapely.geometry import shape

    layers = []
    opcoes = {}
    for nome, camada in camadas.items():
        feicoes = [{"geometry": shape(f["geometry"]), "properties": f["properties"]} for f in camada["features"]]
        layers.append({"name": nome, "features": feicoes})
        opcoes[nome] = {"extents": camada["extent"]}
    padrao = {"y_coord_down": True, "on_invalid_geometry": lambda g: None}
    return gzip.compress(mvt.encode(layers, per_layer_options=opcoes, default_options=padrao), mtime=0)


def processar(item):
    import mapbox_vector_tile as mvt

    tileid, z, dados = item
    camadas = mvt.decode(gzip.decompress(dados), default_options=DECODE)
    ficou = filtrar(z, camadas)
    return tileid, (_codificar(ficou) if ficou else None)


def converter(entrada, saida, processos=None):
    from pmtiles.reader import MmapSource, Reader, all_tiles
    from pmtiles.tile import zxy_to_tileid
    from pmtiles.writer import write

    with open(entrada, "rb") as fh:
        fonte = MmapSource(fh)
        leitor = Reader(fonte)
        cabecalho = dict(leitor.header())
        metadados = leitor.metadata()

        def tiles():
            for (z, x, y), dados in all_tiles(fonte):
                yield zxy_to_tileid(z, x, y), z, dados

        total = gravados = 0
        with write(saida) as escritor, Pool(processos or os.cpu_count()) as pool:
            # imap mantém a ordem dos tiles, que o pmtiles pede crescente
            for tileid, novo in pool.imap(processar, tiles(), chunksize=32):
                total += 1
                if novo:
                    escritor.write_tile(tileid, novo)
                    gravados += 1
                if total % 20000 == 0:
                    print(f"{total} tiles lidos, {gravados} gravados", flush=True)
            metadados = dict(metadados)
            metadados["vector_layers"] = [
                {"id": "water", "fields": {"kind": "String"}},
                {"id": "roads", "fields": {"kind": "String", "name": "String"}},
            ]
            escritor.finalize(cabecalho, metadados)
        print(f"{total} tiles lidos, {gravados} gravados; {os.path.getsize(entrada) / 1e6:.0f} MB -> "
              f"{os.path.getsize(saida) / 1e6:.0f} MB", flush=True)
        return total, gravados


if __name__ == "__main__":
    converter(sys.argv[1], sys.argv[2])
