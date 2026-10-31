import unittest

import contorno


def quadrado(x, y, lado):
    return [[x, y], [x + lado, y], [x + lado, y + lado], [x, y + lado], [x, y]]


class ContornoTest(unittest.TestCase):
    def test_a_margem_aumenta_o_poligono(self):
        from shapely.geometry import shape

        original = {"type": "Polygon", "coordinates": [quadrado(0, 0, 1)]}
        saida = shape(contorno.contorno(original, margem=0.1, simplificacao=0.001))
        self.assertGreater(saida.area, 1.2)
        self.assertTrue(saida.contains(shape(original)))

    def test_fica_so_o_maior_poligono(self):
        original = {"type": "MultiPolygon", "coordinates": [[quadrado(0, 0, 1)], [quadrado(5, 5, 0.1)]]}
        saida = contorno.contorno(original, margem=0.01, simplificacao=0.001)
        self.assertEqual(saida["type"], "Polygon")
        xs = [p[0] for p in saida["coordinates"][0]]
        self.assertLess(max(xs), 2)


if __name__ == "__main__":
    unittest.main()
