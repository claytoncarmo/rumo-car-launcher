import hashlib
import json
import os
import tempfile
import unittest

import manifest


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(content)


# As chaves do manifesto (comum, regioes, arquivos, destino, tamanho…) e o layout regioes/<id>/ são o contrato com o app
# já instalado: ficam em português mesmo com o código em inglês.
class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = self.tmp.name
        self.output = os.path.join(base, "out")
        self.common = os.path.join(base, "common")
        self.sc = os.path.join(base, "sc")
        self.pr = os.path.join(base, "pr")
        write(os.path.join(self.common, "fontes", "NotoSansMedium", "0-255.pbf"), b"fonte")
        write(os.path.join(self.sc, "regioes", "sc", "mapa.pmtiles"), b"mapa" * 100)
        write(os.path.join(self.sc, "regioes", "sc", "vias.db"), b"vias")
        write(os.path.join(self.sc, "LEIAME.txt"), b"texto")
        write(os.path.join(self.pr, "regioes", "pr", "mapa.pmtiles"), b"mapa pr")
        write(os.path.join(self.pr, "regioes", "pr", "vias.db"), b"vias pr")
        self.regions = [("sc", "Santa Catarina", self.sc), ("pr", "Paraná", self.pr)]

    def tearDown(self):
        self.tmp.cleanup()

    def test_lists_regions_with_hash_size_and_url(self):
        m = manifest.generate(self.output, "https://exemplo.org/dados/", self.common, self.regions)
        self.assertEqual(m["versao"], 2)
        self.assertEqual([(r["id"], r["nome"]) for r in m["regioes"]], [("sc", "Santa Catarina"), ("pr", "Paraná")])
        sc = {a["destino"]: a for a in m["regioes"][0]["arquivos"]}
        self.assertEqual(sorted(sc), ["regioes/sc/mapa.pmtiles", "regioes/sc/vias.db"])
        roads = sc["regioes/sc/vias.db"]
        self.assertEqual(roads["tamanho"], 4)
        self.assertEqual(roads["sha256"], hashlib.sha256(b"vias").hexdigest())
        self.assertEqual(roads["url"], "https://exemplo.org/dados/regioes_sc_vias.db")

    def test_fonts_go_in_common_and_not_in_each_region(self):
        m = manifest.generate(self.output, "https://exemplo.org", self.common, self.regions)
        self.assertEqual([a["destino"] for a in m["comum"]], ["fontes/NotoSansMedium/0-255.pbf"])
        for r in m["regioes"]:
            self.assertTrue(all(a["destino"].startswith(f"regioes/{r['id']}/") for a in r["arquivos"]))

    def test_publishes_loose_files_without_folders(self):
        manifest.generate(self.output, "https://exemplo.org", self.common, self.regions)
        self.assertEqual(
            sorted(os.listdir(self.output)),
            [
                "dados.json", "fontes_NotoSansMedium_0-255.pbf",
                "regioes_pr_mapa.pmtiles", "regioes_pr_vias.db",
                "regioes_sc_mapa.pmtiles", "regioes_sc_vias.db",
            ],
        )
        with open(os.path.join(self.output, "dados.json"), encoding="utf-8") as f:
            common = json.load(f)["comum"]
        self.assertTrue(common[0]["url"].endswith("/fontes_NotoSansMedium_0-255.pbf"))

    def test_ignores_the_readme(self):
        m = manifest.generate(self.output, "https://exemplo.org", self.common, self.regions)
        self.assertNotIn("LEIAME.txt", [a["destino"] for a in m["regioes"][0]["arquivos"]])


if __name__ == "__main__":
    unittest.main()
