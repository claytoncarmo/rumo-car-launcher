import gzip
import os
import tempfile
import unittest

import map_filter as mf


def feature(geometry_type, coords, **props):
    return {"geometry": {"type": geometry_type, "coordinates": coords}, "properties": props}


SQUARE = [[[0, 0], [100, 0], [100, 100], [0, 100], [0, 0]]]
LINE = [[0, 10], [200, 10]]


def layers():
    return {
        "water": {"extent": 4096, "features": [
            feature("Polygon", SQUARE, kind="lake"),
            feature("Polygon", SQUARE, kind="swimming_pool"),
            feature("LineString", LINE, kind="river"),
            feature("LineString", LINE, kind="stream"),
            feature("Point", [5, 5], kind="water"),
        ]},
        "roads": {"extent": 4096, "features": [
            feature("LineString", LINE, kind="minor_road", kind_detail="residential", name="Rua A", oneway="yes"),
            feature("LineString", LINE, kind="minor_road", kind_detail="service", name="Garagem"),
            feature("LineString", LINE, kind="path", kind_detail="footway"),
            feature("LineString", LINE, kind="rail"),
            feature("LineString", LINE, kind="highway", kind_detail="motorway", name="BR-101"),
        ]},
        "buildings": {"extent": 4096, "features": [feature("Polygon", SQUARE, height=10)]},
        "landuse": {"extent": 4096, "features": [feature("Polygon", SQUARE, kind="park")]},
        "pois": {"extent": 4096, "features": [feature("Point", [1, 1], kind="cafe")]},
    }


class FilterTest(unittest.TestCase):
    def test_keeps_only_what_the_style_draws(self):
        out = mf.filter_layers(15, layers())
        self.assertEqual(set(out), {"water", "roads"})
        water = [(f["geometry"]["type"], f["properties"]["kind"]) for f in out["water"]["features"]]
        self.assertEqual(water, [("Polygon", "lake"), ("LineString", "river")])
        roads = [f["properties"] for f in out["roads"]["features"]]
        self.assertEqual(roads, [{"kind": "minor_road", "name": "Rua A"}, {"kind": "highway", "name": "BR-101"}])

    def test_name_only_from_zoom_14(self):
        out = mf.filter_layers(13, layers())
        self.assertTrue(all("name" not in f["properties"] for f in out["roads"]["features"]))

    def test_tile_with_nothing_useful_disappears(self):
        self.assertEqual(mf.filter_layers(14, {"buildings": layers()["buildings"]}), {})

    def test_converts_a_pmtiles(self):
        import mapbox_vector_tile as mvt
        from pmtiles.reader import MmapSource, Reader, all_tiles
        from pmtiles.tile import Compression, TileType, zxy_to_tileid
        from pmtiles.writer import write
        from shapely.geometry import shape

        def tile(c):
            encoded = [
                {"name": n,
                 "features": [{"geometry": shape(f["geometry"]), "properties": f["properties"]} for f in l["features"]]}
                for n, l in c.items()
            ]
            return gzip.compress(mvt.encode(encoded, default_options={"y_coord_down": True}), mtime=0)

        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as d:
            source, target = os.path.join(d, "in.pmtiles"), os.path.join(d, "out.pmtiles")
            empty = {"buildings": layers()["buildings"]}
            with write(source) as w:
                w.write_tile(zxy_to_tileid(14, 3, 4), tile(layers()))
                w.write_tile(zxy_to_tileid(14, 3, 5), tile(empty))
                w.finalize({
                    "tile_type": TileType.MVT, "tile_compression": Compression.GZIP,
                    "min_lon_e7": -540000000, "min_lat_e7": -290000000, "max_lon_e7": -480000000, "max_lat_e7": -250000000,
                    "center_zoom": 14, "center_lon_e7": -510000000, "center_lat_e7": -270000000,
                }, {"vector_layers": [{"id": "buildings"}]})
            self.assertEqual(mf.convert(source, target, processes=1), (2, 1))
            with open(target, "rb") as fh:
                pm_source = MmapSource(fh)
                self.assertEqual(Reader(pm_source).header()["max_zoom"], 14)
                self.assertEqual([l["id"] for l in Reader(pm_source).metadata()["vector_layers"]], ["water", "roads"])
                tiles = list(all_tiles(pm_source))
                self.assertEqual([k for k, _ in tiles], [(14, 3, 4)])
                read = mvt.decode(gzip.decompress(tiles[0][1]), default_options={"y_coord_down": True})
                self.assertEqual(set(read), {"water", "roads"})
                self.assertEqual(len(read["roads"]["features"]), 2)
            import map_check

            self.assertTrue(map_check.check(source, target, sample=10))


if __name__ == "__main__":
    unittest.main()
