"""
Gera o contorno de um estado para o recorte dos dados (osmium extract -p, pmtiles extract --region): o limite
administrativo do OpenStreetMap (Nominatim, ODbL) com uma margem para continuar andando além da divisa, simplificado.
Sempre um único polígono (o maior; ilhas pequenas saem), que é o que as ferramentas de recorte aceitam sem surpresa.

Uso: python3 contorno.py <relação OSM> "<nome>" <saida.geojson> [margem em graus (0,07 ≈ 8 km)] [simplificação em graus]
Precisa de rede e de shapely. Os contornos prontos ficam em dados/regioes/.
"""

import json
import sys
import urllib.request

MARGEM = 0.07
SIMPLIFICACAO = 0.004


def baixar(relacao):
    url = f"https://nominatim.openstreetmap.org/lookup?osm_ids=R{relacao}&format=geojson&polygon_geojson=1"
    req = urllib.request.Request(url, headers={"User-Agent": "rumo-car-launcher (github.com/claytoncarmo/rumo-car-launcher)"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["features"][0]["geometry"]


def contorno(geometria, margem=MARGEM, simplificacao=SIMPLIFICACAO):
    """Geometria GeoJSON do limite -> geometria GeoJSON (Polygon) com margem, simplificada e sem ilhas pequenas."""
    from shapely.geometry import mapping, shape

    forma = shape(geometria).buffer(margem).simplify(simplificacao)
    if forma.geom_type == "MultiPolygon":
        partes = sorted(forma.geoms, key=lambda p: p.area, reverse=True)
        forma = partes[0]
    return mapping(forma)


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) < 3:
        sys.exit(__doc__)
    relacao, nome, saida = args[0], args[1], args[2]
    margem = float(args[3]) if len(args) > 3 else MARGEM
    simpl = float(args[4]) if len(args) > 4 else SIMPLIFICACAO
    geom = contorno(baixar(relacao), margem, simpl)
    feature = {
        "type": "Feature",
        "properties": {"nome": nome, "origem": f"OSM relation {relacao} (ODbL), com margem de ~{round(margem * 111)} km"},
        "geometry": {"type": geom["type"], "coordinates": json.loads(json.dumps(geom["coordinates"]))},
    }
    with open(saida, "w", encoding="utf-8") as f:
        json.dump(feature, f, ensure_ascii=False, separators=(",", ":"))
    pontos = sum(len(anel) for anel in geom["coordinates"])
    print(f"{saida}: {pontos} pontos")
