"""
Gera o contorno de um estado para o recorte dos dados (osmium extract -p, pmtiles extract --region): o limite
administrativo do OpenStreetMap (Nominatim, ODbL) com uma margem para continuar andando além da divisa, simplificado.
Sempre um único polígono (o maior; ilhas pequenas saem), que é o que as ferramentas de recorte aceitam sem surpresa.

Uso: python3 contour.py <relação OSM> "<nome>" <saida.geojson> [margem em graus (0,07 ≈ 8 km)] [simplificação em graus]
Precisa de rede e de shapely. Os contornos prontos ficam em data/regions/.
"""

import json
import sys
import urllib.request

MARGIN = 0.07
SIMPLIFICATION = 0.004


def download(relation):
    url = f"https://nominatim.openstreetmap.org/lookup?osm_ids=R{relation}&format=geojson&polygon_geojson=1"
    req = urllib.request.Request(url, headers={"User-Agent": "rumo-car-launcher (github.com/claytoncarmo/rumo-car-launcher)"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["features"][0]["geometry"]


def outline(geometry, margin=MARGIN, simplification=SIMPLIFICATION):
    """Geometria GeoJSON do limite -> geometria GeoJSON (Polygon) com margem, simplificada e sem ilhas pequenas."""
    from shapely.geometry import mapping, shape

    area = shape(geometry).buffer(margin).simplify(simplification)
    if area.geom_type == "MultiPolygon":
        parts = sorted(area.geoms, key=lambda p: p.area, reverse=True)
        area = parts[0]
    return mapping(area)


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) < 3:
        sys.exit(__doc__)
    relation, name, output = args[0], args[1], args[2]
    margin = float(args[3]) if len(args) > 3 else MARGIN
    simplification = float(args[4]) if len(args) > 4 else SIMPLIFICATION
    geometry = outline(download(relation), margin, simplification)
    # As propriedades "nome" e "grupo" dos arquivos em data/regions/ são lidas pelo workflow map-data.yml
    feature = {
        "type": "Feature",
        "properties": {"nome": name, "origem": f"OSM relation {relation} (ODbL), com margem de ~{round(margin * 111)} km"},
        "geometry": {"type": geometry["type"], "coordinates": json.loads(json.dumps(geometry["coordinates"]))},
    }
    with open(output, "w", encoding="utf-8") as f:
        json.dump(feature, f, ensure_ascii=False, separators=(",", ":"))
    points = sum(len(ring) for ring in geometry["coordinates"])
    print(f"{output}: {points} pontos")
