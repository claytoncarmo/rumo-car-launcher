import gzip
import os
import tempfile
import unittest

import mapa_filtro as mf


def feicao(tipo, coords, **props):
    return {"geometry": {"type": tipo, "coordinates": coords}, "properties": props}


QUADRADO = [[[0, 0], [100, 0], [100, 100], [0, 100], [0, 0]]]
LINHA = [[0, 10], [200, 10]]


def camadas():
    return {
        "water": {"extent": 4096, "features": [
            feicao("Polygon", QUADRADO, kind="lake"),
            feicao("Polygon", QUADRADO, kind="swimming_pool"),
            feicao("LineString", LINHA, kind="river"),
            feicao("LineString", LINHA, kind="stream"),
            feicao("Point", [5, 5], kind="water"),
        ]},
        "roads": {"extent": 4096, "features": [
            feicao("LineString", LINHA, kind="minor_road", kind_detail="residential", name="Rua A", oneway="yes"),
            feicao("LineString", LINHA, kind="minor_road", kind_detail="service", name="Garagem"),
            feicao("LineString", LINHA, kind="path", kind_detail="footway"),
            feicao("LineString", LINHA, kind="rail"),
            feicao("LineString", LINHA, kind="highway", kind_detail="motorway", name="BR-101"),
        ]},
        "buildings": {"extent": 4096, "features": [feicao("Polygon", QUADRADO, height=10)]},
        "landuse": {"extent": 4096, "features": [feicao("Polygon", QUADRADO, kind="park")]},
        "pois": {"extent": 4096, "features": [feicao("Point", [1, 1], kind="cafe")]},
    }


class FiltroTest(unittest.TestCase):
    def test_fica_so_o_que_o_estilo_desenha(self):
        out = mf.filtrar(15, camadas())
        self.assertEqual(set(out), {"water", "roads"})
        agua = [(f["geometry"]["type"], f["properties"]["kind"]) for f in out["water"]["features"]]
        self.assertEqual(agua, [("Polygon", "lake"), ("LineString", "river")])
        vias = [f["properties"] for f in out["roads"]["features"]]
        self.assertEqual(vias, [{"kind": "minor_road", "name": "Rua A"}, {"kind": "highway", "name": "BR-101"}])

    def test_nome_so_a_partir_do_zoom_14(self):
        out = mf.filtrar(13, camadas())
        self.assertTrue(all("name" not in f["properties"] for f in out["roads"]["features"]))

    def test_tile_sem_nada_util_some(self):
        self.assertEqual(mf.filtrar(14, {"buildings": camadas()["buildings"]}), {})

    def test_converte_um_pmtiles(self):
        import mapbox_vector_tile as mvt
        from pmtiles.reader import MmapSource, Reader, all_tiles
        from pmtiles.tile import Compression, TileType, zxy_to_tileid
        from pmtiles.writer import write
        from shapely.geometry import shape

        def tile(c):
            layers = [
                {"name": n,
                 "features": [{"geometry": shape(f["geometry"]), "properties": f["properties"]} for f in l["features"]]}
                for n, l in c.items()
            ]
            return gzip.compress(mvt.encode(layers, default_options={"y_coord_down": True}), mtime=0)

        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as d:
            entrada, saida = os.path.join(d, "in.pmtiles"), os.path.join(d, "out.pmtiles")
            vazio = {"buildings": camadas()["buildings"]}
            with write(entrada) as w:
                w.write_tile(zxy_to_tileid(14, 3, 4), tile(camadas()))
                w.write_tile(zxy_to_tileid(14, 3, 5), tile(vazio))
                w.finalize({
                    "tile_type": TileType.MVT, "tile_compression": Compression.GZIP,
                    "min_lon_e7": -540000000, "min_lat_e7": -290000000, "max_lon_e7": -480000000, "max_lat_e7": -250000000,
                    "center_zoom": 14, "center_lon_e7": -510000000, "center_lat_e7": -270000000,
                }, {"vector_layers": [{"id": "buildings"}]})
            self.assertEqual(mf.converter(entrada, saida, processos=1), (2, 1))
            with open(saida, "rb") as fh:
                fonte = MmapSource(fh)
                self.assertEqual(Reader(fonte).header()["max_zoom"], 14)
                self.assertEqual([l["id"] for l in Reader(fonte).metadata()["vector_layers"]], ["water", "roads"])
                tiles = list(all_tiles(fonte))
                self.assertEqual([k for k, _ in tiles], [(14, 3, 4)])
                lido = mvt.decode(gzip.decompress(tiles[0][1]), default_options={"y_coord_down": True})
                self.assertEqual(set(lido), {"water", "roads"})
                self.assertEqual(len(lido["roads"]["features"]), 2)
            import mapa_confere

            self.assertTrue(mapa_confere.conferir(entrada, saida, amostra=10))


if __name__ == "__main__":
    unittest.main()
