import unittest

from vias import CELL, KIND, cell_of, ctb_default, parse_speed, seg_cells, seg_dist_m


class ViasTest(unittest.TestCase):
    def test_limite_numerico(self):
        self.assertEqual(parse_speed("60"), 60)
        self.assertEqual(parse_speed(" 80 "), 80)
        self.assertEqual(parse_speed("40 km/h"), 40)
        self.assertEqual(parse_speed("30 mph"), 48)

    def test_zonas_e_invalidos(self):
        self.assertEqual(parse_speed("BR:urban"), "urban")
        self.assertEqual(parse_speed("BR:rural"), "rural")
        self.assertIsNone(parse_speed("signals"))
        self.assertIsNone(parse_speed("none"))
        self.assertIsNone(parse_speed(None))
        self.assertIsNone(parse_speed("999"))

    def test_padroes_do_ctb(self):
        urbano = lambda k, oneway=False: ctb_default(KIND[k], False, True, oneway, None, None)
        self.assertEqual(urbano("primary"), 60)
        self.assertEqual(urbano("tertiary"), 40)
        self.assertEqual(urbano("residential"), 30)
        self.assertEqual(urbano("motorway"), 80)
        rural = lambda k, oneway=False, paved=None, ref=None: ctb_default(KIND[k], False, False, oneway, paved, ref)
        self.assertEqual(rural("trunk", oneway=True), 110)
        self.assertEqual(rural("primary"), 100)
        self.assertEqual(rural("tertiary", ref="SC-401"), 100)
        self.assertEqual(rural("unclassified"), 60)
        self.assertEqual(rural("unclassified", paved=False, ref="SC-110"), 60)
        # alças: no máximo 60
        self.assertEqual(ctb_default(KIND["motorway"], True, False, True, None, None), 60)

    def test_celulas(self):
        a = cell_of(-27.5951, -48.5480)
        self.assertEqual(a, cell_of(-27.5901, -48.5401))
        self.assertNotEqual(a, cell_of(-27.6001, -48.5480))
        # segmento longo passa pelas células do meio
        cells = seg_cells(-27.0, -49.0, -27.0, -49.0 + 5 * CELL)
        self.assertGreaterEqual(len(cells), 6)

    def test_distancia_ao_segmento(self):
        # ponto 0,0001° ao norte de um segmento leste-oeste: ~11 m
        d = seg_dist_m(-27.0, -49.0, -27.0001, -49.001, -27.0001, -48.999)
        self.assertAlmostEqual(d, 11.05, delta=0.2)


if __name__ == "__main__":
    unittest.main()
