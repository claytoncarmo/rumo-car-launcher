import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import maparadar_manifest as mm


class MapaRadarManifestTest(unittest.TestCase):
    def test_identical_copy_and_manifest_with_hash(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as d:
            csv = Path(d) / "entrada.csv"
            content = "-48.5,-27.6,Radar Fixo - 60 kmh@60\n-48.6,-27.7,Pedagio - 0 kmh@0\n".encode()
            csv.write_bytes(content)
            m = mm.generate(csv, Path(d) / "out", "https://exemplo.org/rumo-maparadar/")
            self.assertEqual((Path(d) / "out" / "maparadar.csv").read_bytes(), content)
            self.assertEqual(m["file"]["sha256"], hashlib.sha256(content).hexdigest())
            self.assertEqual(m["file"]["size"], len(content))
            self.assertEqual(m["file"]["url"], "https://exemplo.org/rumo-maparadar/maparadar.csv")
            self.assertEqual(m["lines"], 2)
            read = json.loads((Path(d) / "out" / "maparadar.json").read_text(encoding="utf-8"))
            self.assertEqual(read["file"]["dest"], "maparadar.csv")


if __name__ == "__main__":
    unittest.main()
