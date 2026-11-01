import unittest

import contour


def square(x, y, side):
    return [[x, y], [x + side, y], [x + side, y + side], [x, y + side], [x, y]]


class ContourTest(unittest.TestCase):
    def test_margin_grows_the_polygon(self):
        from shapely.geometry import shape

        original = {"type": "Polygon", "coordinates": [square(0, 0, 1)]}
        result = shape(contour.outline(original, margin=0.1, simplification=0.001))
        self.assertGreater(result.area, 1.2)
        self.assertTrue(result.contains(shape(original)))

    def test_keeps_only_the_largest_polygon(self):
        original = {"type": "MultiPolygon", "coordinates": [[square(0, 0, 1)], [square(5, 5, 0.1)]]}
        result = contour.outline(original, margin=0.01, simplification=0.001)
        self.assertEqual(result["type"], "Polygon")
        xs = [p[0] for p in result["coordinates"][0]]
        self.assertLess(max(xs), 2)


if __name__ == "__main__":
    unittest.main()
