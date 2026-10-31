import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import maparadar_manifesto as mm


class ManifestoMapaRadarTest(unittest.TestCase):
    def test_copia_identica_e_manifesto_com_hash(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as d:
            csv = Path(d) / "entrada.csv"
            conteudo = "-48.5,-27.6,Radar Fixo - 60 kmh@60\n-48.6,-27.7,Pedagio - 0 kmh@0\n".encode()
            csv.write_bytes(conteudo)
            m = mm.gerar(csv, Path(d) / "saida", "https://exemplo.org/rumo-maparadar/")
            self.assertEqual((Path(d) / "saida" / "maparadar.csv").read_bytes(), conteudo)
            self.assertEqual(m["arquivo"]["sha256"], hashlib.sha256(conteudo).hexdigest())
            self.assertEqual(m["arquivo"]["tamanho"], len(conteudo))
            self.assertEqual(m["arquivo"]["url"], "https://exemplo.org/rumo-maparadar/maparadar.csv")
            self.assertEqual(m["linhas"], 2)
            lido = json.loads((Path(d) / "saida" / "maparadar.json").read_text(encoding="utf-8"))
            self.assertEqual(lido["arquivo"]["destino"], "maparadar.csv")


if __name__ == "__main__":
    unittest.main()
