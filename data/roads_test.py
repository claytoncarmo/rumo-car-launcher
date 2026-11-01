import unittest

from roads import CELL, KIND, cell_of, ctb_default, decode_points, encode_points, parse_speed, seg_cells, seg_dist_m


class RoadsTest(unittest.TestCase):
    def test_numeric_limit(self):
        self.assertEqual(parse_speed("60"), 60)
        self.assertEqual(parse_speed(" 80 "), 80)
        self.assertEqual(parse_speed("40 km/h"), 40)
        self.assertEqual(parse_speed("30 mph"), 48)

    def test_zones_and_invalid_values(self):
        self.assertEqual(parse_speed("BR:urban"), "urban")
        self.assertEqual(parse_speed("BR:rural"), "rural")
        self.assertIsNone(parse_speed("signals"))
        self.assertIsNone(parse_speed("none"))
        self.assertIsNone(parse_speed(None))
        self.assertIsNone(parse_speed("999"))

    def test_ctb_defaults(self):
        urban = lambda k, oneway=False: ctb_default(KIND[k], False, True, oneway, None, None)
        self.assertEqual(urban("primary"), 60)
        self.assertEqual(urban("tertiary"), 40)
        self.assertEqual(urban("residential"), 30)
        self.assertEqual(urban("motorway"), 80)
        rural = lambda k, oneway=False, paved=None, ref=None: ctb_default(KIND[k], False, False, oneway, paved, ref)
        self.assertEqual(rural("trunk", oneway=True), 110)
        self.assertEqual(rural("primary"), 100)
        self.assertEqual(rural("tertiary", ref="SC-401"), 100)
        self.assertEqual(rural("unclassified"), 60)
        self.assertEqual(rural("unclassified", paved=False, ref="SC-110"), 60)
        # alças: no máximo 60
        self.assertEqual(ctb_default(KIND["motorway"], True, False, True, None, None), 60)

    def test_cells(self):
        a = cell_of(-27.5951, -48.5480)
        self.assertEqual(a, cell_of(-27.5901, -48.5401))
        self.assertNotEqual(a, cell_of(-27.6001, -48.5480))
        # segmento longo passa pelas células do meio
        cells = seg_cells(-27.0, -49.0, -27.0, -49.0 + 5 * CELL)
        self.assertGreaterEqual(len(cells), 6)

    def test_points_round_trip(self):
        pts = [(-27595100, -48548000), (-27595000, -48547900), (-27594000, -48549500), (0, 0), (2_000_000_000, -2_000_000_000)]
        self.assertEqual(decode_points(encode_points(pts)), pts)
        self.assertEqual(decode_points(encode_points([])), [])

    def test_points_fixed_format(self):
        # O mesmo vetor está em core/PointsTest.kt: os dois lados têm de concordar byte a byte
        blob = encode_points([(-27595100, -48548000), (-27595000, -48547900)])
        self.assertEqual(blob.hex(), "b7c5a81abfa2a62ec801c801")

    def test_distance_to_segment(self):
        # ponto 0,0001° ao norte de um segmento leste-oeste: ~11 m
        d = seg_dist_m(-27.0, -49.0, -27.0001, -49.001, -27.0001, -48.999)
        self.assertAlmostEqual(d, 11.05, delta=0.2)


if __name__ == "__main__":
    unittest.main()
