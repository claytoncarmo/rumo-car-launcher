"""
Lê o maparadar.csv (base do MapaRadar, mantida à mão em dados/maparadar.csv), recorta pelo contorno de um estado e
junta com os radares do OpenStreetMap para o vias.db.

O CSV não tem cabeçalho: `longitude,latitude,Tipo - N kmh@N` (sem acentos), um ponto por linha, sem sentido.
Tipos: Radar Fixo, Semaforo com Radar, Radar Movel, Lombada (com 0 km/h é lombada comum; com limite, eletrônica),
Semaforo com Camera, Policia Rodoviaria, Pedagio.

Licença da base: CC BY-NC-ND 4.0 (uso pessoal, com atribuição, sem fins comerciais, sem alterações): ver dados/README.md.
"""

import csv
import json
import re
import unicodedata
from collections import namedtuple

# Códigos gravados em radars.kind (core/Radars.kt tem os mesmos). 0 = radar do OSM sem tipo.
RADAR_FIXO, SEMAFORO_RADAR, RADAR_MOVEL, LOMBADA, LOMBADA_ELETRONICA, SEMAFORO_CAMERA, POLICIA, PEDAGIO = range(1, 9)

TIPOS = {
    "radar fixo": RADAR_FIXO,
    "semaforo com radar": SEMAFORO_RADAR,
    "radar movel": RADAR_MOVEL,
    "lombada": LOMBADA,
    "semaforo com camera": SEMAFORO_CAMERA,
    "policia rodoviaria": POLICIA,
    "pedagio": PEDAGIO,
}
# Os que fiscalizam velocidade (o radar do OSM a até FUNDE_M deles é o mesmo ponto)
FISCALIZAM = {RADAR_FIXO, SEMAFORO_RADAR, RADAR_MOVEL, LOMBADA_ELETRONICA}
FUNDE_M = 50.0

# Caixa do Brasil, com folga: pontos fora disso são erro de digitação
LAT = (-34.5, 6.0)
LON = (-74.5, -33.5)

Ponto = namedtuple("Ponto", "id lat lon kind lim")

_DESC = re.compile(r"^(?P<tipo>.+?)\s*-\s*(?P<lim>\d+)\s*kmh\s*(?:@\s*\d+)?\s*$")


def _sem_acento(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower().strip()


def parse_linha(campos, numero):
    """[lon, lat, descrição] -> Ponto, ou None se a linha não presta. [numero] (da linha no arquivo) vira o id (negativo,
    para nunca bater com um id do OSM; o mesmo ponto tem o mesmo id em todos os estados)."""
    if len(campos) != 3:
        return None
    try:
        lon, lat = float(campos[0]), float(campos[1])
    except ValueError:
        return None
    m = _DESC.match(campos[2].strip())
    if not m or not (LAT[0] <= lat <= LAT[1] and LON[0] <= lon <= LON[1]):
        return None
    tipo = TIPOS.get(_sem_acento(m["tipo"]))
    if tipo is None:
        return None
    lim = int(m["lim"])
    if lim > 130:
        return None
    if tipo == LOMBADA and lim > 0:
        tipo = LOMBADA_ELETRONICA
    if tipo not in FISCALIZAM:
        lim = 0
    return Ponto(-numero, lat, lon, tipo, lim)


def ler(caminho):
    """Todos os pontos do CSV, sem os repetidos (mesma posição e tipo)."""
    vistos, pontos = set(), []
    with open(caminho, encoding="utf-8", newline="") as f:
        for numero, campos in enumerate(csv.reader(f), start=1):
            p = parse_linha(campos, numero)
            if p is None:
                continue
            chave = (round(p.lat, 5), round(p.lon, 5), p.kind)
            if chave not in vistos:
                vistos.add(chave)
                pontos.append(p)
    return pontos


def contorno_de(caminho):
    """Geometria (shapely, preparada) do GeoJSON de um contorno de estado."""
    from shapely.geometry import shape
    from shapely.prepared import prep

    with open(caminho, encoding="utf-8") as f:
        dado = json.load(f)
    geometria = dado["geometry"] if dado.get("type") == "Feature" else dado
    return prep(shape(geometria))


def dentro(pontos, caminho_contorno):
    """Só os pontos dentro do contorno (o do estado, com a folga da divisa)."""
    from shapely.geometry import Point

    area = contorno_de(caminho_contorno)
    return [p for p in pontos if area.contains(Point(p.lon, p.lat))]


def _dist_m(lat1, lon1, lat2, lon2):
    import math

    dy = (lat2 - lat1) * 111_320.0
    dx = (lon2 - lon1) * 111_320.0 * math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot(dx, dy)


def mesclar(osm, pontos, via_em, cell=0.01):
    """Junta os radares do OSM com os do MapaRadar.

    [osm]: [(id, lat, lon, lim, bearing)]; [pontos]: [Ponto]; [via_em]: (lat, lon) -> (bearing da via, mão única) ou
    None se não há via perto. Devolve linhas (id, lat, lon, lim, bearing, kind).

    O MapaRadar não traz o sentido: vale o do radar do OSM que fica a até FUNDE_M (esse radar é o mesmo ponto e sai da
    lista), senão o da via se ela for de mão única, senão "os dois" (-1). O que só o OSM tem continua (tipo 0, radar
    fixo de tipo desconhecido).
    """
    grade = {}
    for i, (_, lat, lon, _, _) in enumerate(osm):
        grade.setdefault((round(lat / cell), round(lon / cell)), []).append(i)
    usados = set()
    saida = []
    for p in pontos:
        sentido = -1
        if p.kind in FISCALIZAM:
            cc = (round(p.lat / cell), round(p.lon / cell))
            melhor = None
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    for i in grade.get((cc[0] + dy, cc[1] + dx), ()):
                        if i in usados:
                            continue
                        d = _dist_m(p.lat, p.lon, osm[i][1], osm[i][2])
                        if d <= FUNDE_M and (melhor is None or d < melhor[0]):
                            melhor = (d, i)
            if melhor is not None:
                usados.add(melhor[1])
                sentido = osm[melhor[1]][4]
        if sentido < 0:
            via = via_em(p.lat, p.lon)
            if via is not None and via[1]:
                sentido = int(round(via[0])) % 360
        saida.append((p.id, p.lat, p.lon, p.lim, sentido, p.kind))
    for i, (nid, lat, lon, lim, b) in enumerate(osm):
        if i not in usados:
            saida.append((nid, lat, lon, lim, b, 0))
    return saida
