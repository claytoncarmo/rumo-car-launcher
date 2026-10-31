import hashlib
import json
import os
import tempfile
import unittest

import manifesto


def escrever(caminho, conteudo):
    os.makedirs(os.path.dirname(caminho), exist_ok=True)
    with open(caminho, "wb") as f:
        f.write(conteudo)


class ManifestoTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = self.tmp.name
        self.saida = os.path.join(base, "saida")
        self.comum = os.path.join(base, "comum")
        self.sc = os.path.join(base, "sc")
        self.pr = os.path.join(base, "pr")
        escrever(os.path.join(self.comum, "fontes", "NotoSansMedium", "0-255.pbf"), b"fonte")
        escrever(os.path.join(self.sc, "regioes", "sc", "mapa.pmtiles"), b"mapa" * 100)
        escrever(os.path.join(self.sc, "regioes", "sc", "vias.db"), b"vias")
        escrever(os.path.join(self.sc, "LEIAME.txt"), b"texto")
        escrever(os.path.join(self.pr, "regioes", "pr", "mapa.pmtiles"), b"mapa pr")
        escrever(os.path.join(self.pr, "regioes", "pr", "vias.db"), b"vias pr")
        self.regioes = [("sc", "Santa Catarina", self.sc), ("pr", "Paraná", self.pr)]

    def tearDown(self):
        self.tmp.cleanup()

    def test_lista_as_regioes_com_hash_tamanho_e_endereco(self):
        m = manifesto.gerar(self.saida, "https://exemplo.org/dados/", self.comum, self.regioes)
        self.assertEqual(m["versao"], 2)
        self.assertEqual([(r["id"], r["nome"]) for r in m["regioes"]], [("sc", "Santa Catarina"), ("pr", "Paraná")])
        sc = {a["destino"]: a for a in m["regioes"][0]["arquivos"]}
        self.assertEqual(sorted(sc), ["regioes/sc/mapa.pmtiles", "regioes/sc/vias.db"])
        vias = sc["regioes/sc/vias.db"]
        self.assertEqual(vias["tamanho"], 4)
        self.assertEqual(vias["sha256"], hashlib.sha256(b"vias").hexdigest())
        self.assertEqual(vias["url"], "https://exemplo.org/dados/regioes_sc_vias.db")

    def test_fontes_vao_em_comum_e_nao_em_cada_regiao(self):
        m = manifesto.gerar(self.saida, "https://exemplo.org", self.comum, self.regioes)
        self.assertEqual([a["destino"] for a in m["comum"]], ["fontes/NotoSansMedium/0-255.pbf"])
        for r in m["regioes"]:
            self.assertTrue(all(a["destino"].startswith(f"regioes/{r['id']}/") for a in r["arquivos"]))

    def test_publica_arquivos_avulsos_sem_pastas(self):
        manifesto.gerar(self.saida, "https://exemplo.org", self.comum, self.regioes)
        self.assertEqual(
            sorted(os.listdir(self.saida)),
            [
                "dados.json", "fontes_NotoSansMedium_0-255.pbf",
                "regioes_pr_mapa.pmtiles", "regioes_pr_vias.db",
                "regioes_sc_mapa.pmtiles", "regioes_sc_vias.db",
            ],
        )
        with open(os.path.join(self.saida, "dados.json"), encoding="utf-8") as f:
            comum = json.load(f)["comum"]
        self.assertTrue(comum[0]["url"].endswith("/fontes_NotoSansMedium_0-255.pbf"))

    def test_ignora_o_leiame(self):
        m = manifesto.gerar(self.saida, "https://exemplo.org", self.comum, self.regioes)
        self.assertNotIn("LEIAME.txt", [a["destino"] for a in m["regioes"][0]["arquivos"]])


if __name__ == "__main__":
    unittest.main()
