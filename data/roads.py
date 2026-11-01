"""
Gera o vias.db do Rumo a partir de um recorte do OpenStreetMap (.osm.pbf).

Tabelas:
  info(k, v)                       região, data dos dados, contagens
  ways(id, name, ref, kind, oneway, fwd, bwd, est)
                                   limite em km/h por sentido (fwd = sentido do desenho da via);
                                   est: bit 1 = fwd estimado, bit 2 = bwd estimado (padrão do CTB)
  segs(cell, way, n, pts)          trechos da via dentro de uma célula de 0,01° (n numera os trechos da via na
                                   célula); pts = pontos (lat, lon) em micrograus, cada um como diferença para o
                                   anterior, em zigue-zague e varint (encode_points). info.versao = 2; a versão 1
                                   guardava pares int32 LE, sem n
  radars(id, lat, lon, lim, bearing, kind)
                                   bearing = sentido fiscalizado em graus (0 = norte), -1 = os dois; lim = limite
                                   fiscalizado (0 = ponto sem fiscalização de velocidade); kind = tipo: aqui é sempre
                                   0 (radar do OSM sem tipo). O app junta, no aparelho, os pontos do MapaRadar (tipos 1 a 8,
                                   core/MapaRadar.kt) e usa a mesma coluna

Uso: python3 roads.py entrada.osm.pbf saida.db "Santa Catarina"
"""

import math
import re
import sqlite3
import struct
import sys
import time
from array import array

CELL = 0.01  # grau; o app usa a mesma grade (core/Geo.kt)

KINDS = [
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "unclassified", "residential", "living_street", "service", "road",
]
KIND = {k: i for i, k in enumerate(KINDS)}
SKIP_SERVICE = {"parking_aisle", "driveway", "drive-through", "emergency_access"}
UNPAVED = {
    "unpaved", "dirt", "earth", "ground", "gravel", "fine_gravel", "compacted",
    "grass", "sand", "mud", "pebblestone", "rock",
}
PAVED = {"paved", "asphalt", "concrete", "concrete:plates", "paving_stones", "sett", "cobblestone", "chipseal"}
# Célula com pelo menos tantas ruas residenciais conta como área urbana (para os padrões do CTB)
URBAN_RESIDENTIAL = 6


def encode_points(points):
    """Pontos (lat, lon em micrograus inteiros) -> bytes: diferença para o ponto anterior (o primeiro, para 0,0) de cada
    coordenada, em zigue-zague e varint (7 bits por byte, bit alto = tem mais). core/Points.kt decodifica."""
    out = bytearray()
    prev_lat = prev_lon = 0
    for lat, lon in points:
        for d in (lat - prev_lat, lon - prev_lon):
            z = (d << 1) ^ (d >> 63)
            while z >= 0x80:
                out.append((z & 0x7F) | 0x80)
                z >>= 7
            out.append(z)
        prev_lat, prev_lon = lat, lon
    return bytes(out)


def decode_points(blob):
    """Inverso de encode_points (usado nos testes)."""
    nums = []
    z = shift = 0
    for b in blob:
        z |= (b & 0x7F) << shift
        if b & 0x80:
            shift += 7
        else:
            nums.append((z >> 1) ^ -(z & 1))
            z = shift = 0
    points = []
    lat = lon = 0
    for i in range(0, len(nums), 2):
        lat += nums[i]
        lon += nums[i + 1]
        points.append((lat, lon))
    return points


def cell_of(lat, lon):
    return int(math.floor((lat + 90) / CELL)) * 36000 + int(math.floor((lon + 180) / CELL))


def parse_speed(v):
    """Número em km/h, "BR:urban"/"BR:rural" (zona sem número) ou None."""
    if not v:
        return None
    v = v.strip()
    m = re.match(r"^(\d+(?:\.\d+)?)\s*(mph|km/h|kmh)?$", v)
    if m:
        n = float(m.group(1))
        if m.group(2) == "mph":
            n *= 1.609344
        n = int(round(n))
        return n if 5 <= n <= 150 else None
    low = v.lower()
    if low.startswith("br:"):
        if "urban" in low:
            return "urban"
        if "rural" in low:
            return "rural"
    return None


def ctb_default(kind, link, urban, oneway, paved, ref):
    """Padrão do CTB (art. 61) pelo tipo de via quando o OSM não tem o limite."""
    k = KINDS[kind]
    if urban:
        v = {
            "motorway": 80,                 # via de trânsito rápido
            "trunk": 80 if oneway else 60,
            "primary": 60, "secondary": 60,  # arteriais
            "tertiary": 40,                  # coletora
        }.get(k, 30)                         # locais
    else:
        is_highway = paved is True or (paved is None and ref is not None)
        if k in ("motorway", "trunk", "primary", "secondary"):
            v = 110 if oneway else 100       # pista dupla / simples
        elif k in ("tertiary", "unclassified", "road"):
            v = 100 if is_highway else 60    # rodovia / estrada
        else:
            v = 30
    return min(v, 60) if link else v


class Collector:
    def __init__(self):
        self.ways = []        # (id, name, ref, kind, link, oneway, tags dict, lats array, lons array, node ids array)
        self.cameras = {}     # node id -> (lat, lon, maxspeed, direction)

    def node(self, n):
        if n.tags.get("highway") == "speed_camera" and n.location.valid():
            self.cameras[n.id] = (
                n.location.lat, n.location.lon,
                n.tags.get("maxspeed"), n.tags.get("direction") or n.tags.get("camera:direction"),
            )

    def way(self, w):
        hw = w.tags.get("highway")
        if not hw:
            return
        link = hw.endswith("_link")
        base = hw[:-5] if link else hw
        if base not in KIND or w.tags.get("area") == "yes":
            return
        if base == "service" and w.tags.get("service") in SKIP_SERVICE:
            return
        lats, lons, ids = array("d"), array("d"), array("q")
        for nd in w.nodes:
            if not nd.location.valid():
                continue
            lats.append(nd.location.lat)
            lons.append(nd.location.lon)
            ids.append(nd.ref)
        if len(lats) < 2:
            return
        tags = {k: w.tags.get(k) for k in (
            "name", "ref", "oneway", "junction", "maxspeed", "maxspeed:forward",
            "maxspeed:backward", "surface", "maxspeed:type", "source:maxspeed",
        )}
        oneway = tags["oneway"] in ("yes", "1", "true") or tags["junction"] in ("roundabout", "circular") \
            or base == "motorway"
        if tags["oneway"] == "-1":
            oneway = True
            lats.reverse(); lons.reverse(); ids.reverse()
            fw, bw = tags["maxspeed:backward"], tags["maxspeed:forward"]
            tags["maxspeed:forward"], tags["maxspeed:backward"] = fw, bw
        if tags["oneway"] == "no":
            oneway = False
        self.ways.append((w.id, tags["name"], tags["ref"], KIND[base], link, oneway, tags, lats, lons, ids))


def seg_cells(a_lat, a_lon, b_lat, b_lon):
    """Células que o segmento atravessa (amostras a cada meia célula)."""
    steps = max(1, int(max(abs(b_lat - a_lat), abs(b_lon - a_lon)) / (CELL / 2)) + 1)
    out = []
    for i in range(steps + 1):
        t = i / steps
        c = cell_of(a_lat + (b_lat - a_lat) * t, a_lon + (b_lon - a_lon) * t)
        if c not in out:
            out.append(c)
    return out


def bearing(a_lat, a_lon, b_lat, b_lon):
    dx = (b_lon - a_lon) * math.cos(math.radians((a_lat + b_lat) / 2))
    dy = b_lat - a_lat
    return (math.degrees(math.atan2(dx, dy)) + 360) % 360


def dist_m(a_lat, a_lon, b_lat, b_lon):
    dx = (b_lon - a_lon) * math.cos(math.radians((a_lat + b_lat) / 2)) * 111320
    dy = (b_lat - a_lat) * 110540
    return math.hypot(dx, dy)


COMPASS = {"N": 0, "NNE": 22, "NE": 45, "ENE": 67, "E": 90, "ESE": 112, "SE": 135, "SSE": 157,
           "S": 180, "SSW": 202, "SW": 225, "WSW": 247, "W": 270, "WNW": 292, "NW": 315, "NNW": 337}


def build(src, dst, region):
    import osmium

    started = time.time()
    c = Collector()

    class Handler(osmium.SimpleHandler):
        def node(self, n):
            c.node(n)

        def way(self, w):
            c.way(w)

    Handler().apply_file(src, locations=True)
    print(f"lidas {len(c.ways)} vias e {len(c.cameras)} radares em {time.time() - started:.0f} s", flush=True)

    # Densidade de ruas residenciais por célula: separa área urbana de rural
    residential = {}
    for w in c.ways:
        if KINDS[w[3]] in ("residential", "living_street"):
            m = len(w[7]) // 2
            cc = cell_of(w[7][m], w[8][m])
            residential[cc] = residential.get(cc, 0) + 1

    db = sqlite3.connect(dst)
    db.executescript("""
        PRAGMA page_size = 4096;
        CREATE TABLE info(k TEXT PRIMARY KEY, v TEXT);
        CREATE TABLE ways(id INTEGER PRIMARY KEY, name TEXT, ref TEXT, kind INTEGER, oneway INTEGER,
                          fwd INTEGER, bwd INTEGER, est INTEGER);
        -- Sem rowid e sem índice à parte: a chave (cell, way, n) já atende "WHERE cell = ?" e poupa ~15 MB
        CREATE TABLE segs(cell INTEGER, way INTEGER, n INTEGER, pts BLOB, PRIMARY KEY(cell, way, n)) WITHOUT ROWID;
        CREATE TABLE radars(id INTEGER PRIMARY KEY, lat REAL, lon REAL, lim INTEGER, bearing INTEGER, kind INTEGER);
    """)

    grid = {}       # cell -> [(way index, lats, lons)] para achar a via dos radares soltos
    on_way = {}     # node id do radar -> (way index, bearing da via no ponto)
    limits = []     # por via: (fwd, bwd)
    segs = []       # (cell, way, n, pts): gravados em ordem da chave, que é bem mais rápido
    n_est = 0
    for wi, (wid, name, ref, kind, link, oneway, tags, lats, lons, ids) in enumerate(c.ways):
        m = len(lats) // 2
        urban = residential.get(cell_of(lats[m], lons[m]), 0) >= URBAN_RESIDENTIAL
        surface = tags["surface"]
        paved = True if surface in PAVED else False if surface in UNPAVED else None

        def limit(tag):
            v = parse_speed(tags[tag]) if tags[tag] else None
            if v is None:
                v = parse_speed(tags["maxspeed"])
            zone = v if isinstance(v, str) else None
            if isinstance(v, int):
                return v, False
            u = urban if zone is None else zone == "urban"
            return ctb_default(kind, link, u, oneway, paved, ref), True

        fwd, fe = limit("maxspeed:forward")
        bwd, be = limit("maxspeed:backward")
        est = (1 if fe else 0) | (2 if be else 0)
        n_est += 1 if fe else 0
        limits.append((fwd, bwd))
        db.execute("INSERT INTO ways VALUES (?,?,?,?,?,?,?,?)",
                   (wi, name, ref, kind, 1 if oneway else 0, fwd, bwd, est))

        # Trechos por célula, em sequências contínuas de pontos
        runs = {}  # cell -> lista de [i0, i1]
        for i in range(len(lats) - 1):
            for cc in seg_cells(lats[i], lons[i], lats[i + 1], lons[i + 1]):
                lst = runs.setdefault(cc, [])
                if lst and lst[-1][1] == i:
                    lst[-1][1] = i + 1
                else:
                    lst.append([i, i + 1])
        for cc, lst in runs.items():
            for n, (i0, i1) in enumerate(lst):
                pts = encode_points(
                    (int(round(lats[i] * 1e6)), int(round(lons[i] * 1e6))) for i in range(i0, i1 + 1)
                )
                segs.append((cc, wi, n, pts))
                grid.setdefault(cc, []).append((wi, i0, i1))

        for i, nid in enumerate(ids):
            if nid in c.cameras and nid not in on_way:
                j0, j1 = (i, i + 1) if i + 1 < len(ids) else (i - 1, i)
                on_way[nid] = (wi, bearing(lats[j0], lons[j0], lats[j1], lons[j1]))

    # Radares do OSM: limite da placa ou da via; sentido pela tag direction, pela mão única ou "os dois"
    osm_radars = []
    for nid, (lat, lon, maxspeed, direction) in c.cameras.items():
        hit = on_way.get(nid) or nearest_way(c.ways, grid, lat, lon)
        if hit is None:
            continue
        wi, way_bearing = hit
        oneway = c.ways[wi][5]
        d = (direction or "").strip()
        if d == "forward":
            b = way_bearing
        elif d == "backward":
            b = (way_bearing + 180) % 360
        elif re.match(r"^\d+(\.\d+)?$", d):
            b = float(d) % 360
        elif d.upper() in COMPASS:
            b = COMPASS[d.upper()]
        elif oneway:
            b = way_bearing
        else:
            b = -1
        lim = parse_speed(maxspeed)
        if not isinstance(lim, int):
            fwd, bwd = limits[wi]
            if b == -1:
                lim = min(fwd, bwd)
            else:
                ahead = abs((b - way_bearing + 180) % 360 - 180) <= 90
                lim = fwd if ahead else bwd
        osm_radars.append((nid, lat, lon, lim, int(round(b)) if b >= 0 else -1))

    db.executemany("INSERT INTO radars VALUES (?,?,?,?,?,0)", osm_radars)
    n_radar = len(osm_radars)

    segs.sort(key=lambda r: r[:3])
    db.executemany("INSERT INTO segs VALUES (?,?,?,?)", segs)
    # As chaves de info ficam em português: vias.db já está no campo e o app lê info.versao (device/MapData.kt)
    info = {
        "versao": "2",
        "regiao": region,
        "gerado": time.strftime("%Y-%m-%d"),
        "vias": str(len(c.ways)),
        "vias_com_limite": str(len(c.ways) - n_est),
        "radares": str(n_radar),
    }
    db.executemany("INSERT INTO info VALUES (?,?)", info.items())
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(info, f"em {time.time() - started:.0f} s", flush=True)
    return info


def nearest_way(ways, grid, lat, lon, max_m=40):
    """Via mais próxima de um radar que não é nó de nenhuma via: (índice, bearing do trecho)."""
    best = None
    for cc in {cell_of(lat + dy, lon + dx) for dy in (-CELL / 2, 0, CELL / 2) for dx in (-CELL / 2, 0, CELL / 2)}:
        for wi, i0, i1 in grid.get(cc, []):
            lats, lons = ways[wi][7], ways[wi][8]
            for i in range(i0, i1):
                d = seg_dist_m(lat, lon, lats[i], lons[i], lats[i + 1], lons[i + 1])
                if d <= max_m and (best is None or d < best[0]):
                    best = (d, wi, bearing(lats[i], lons[i], lats[i + 1], lons[i + 1]))
    return None if best is None else (best[1], best[2])


def seg_dist_m(lat, lon, a_lat, a_lon, b_lat, b_lon):
    k = math.cos(math.radians(lat)) * 111320
    ax, ay = (a_lon - lon) * k, (a_lat - lat) * 110540
    bx, by = (b_lon - lon) * k, (b_lat - lat) * 110540
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    t = 0 if l2 == 0 else max(0, min(1, -(ax * dx + ay * dy) / l2))
    return math.hypot(ax + t * dx, ay + t * dy)


if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "")
