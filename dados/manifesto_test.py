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
        self.rumo = os.path.join(self.tmp.name, "Rumo")
        self.saida = os.path.join(self.tmp.name, "saida")
        escrever(os.path.join(self.rumo, "mapa.pmtiles"), b"mapa" * 100)
        escrever(os.path.join(self.rumo, "vias.db"), b"vias")
        escrever(os.path.join(self.rumo, "fontes", "NotoSansMedium", "0-255.pbf"), b"fonte")
        escrever(os.path.join(self.rumo, "LEIAME.txt"), b"texto")

    def tearDown(self):
        self.tmp.cleanup()

    def test_lista_os_arquivos_com_hash_tamanho_e_endereco(self):
        m = manifesto.gerar(self.rumo, self.saida, "https://exemplo.org/dados/", "sc", "Santa Catarina")
        self.assertEqual(m["versao"], 1)
        regiao = m["regioes"][0]
        self.assertEqual((regiao["id"], regiao["nome"]), ("sc", "Santa Catarina"))
        por_destino = {a["destino"]: a for a in regiao["arquivos"]}
        self.assertEqual(
            sorted(por_destino), ["fontes/NotoSansMedium/0-255.pbf", "mapa.pmtiles", "vias.db"]
        )
        vias = por_destino["vias.db"]
        self.assertEqual(vias["tamanho"], 4)
        self.assertEqual(vias["sha256"], hashlib.sha256(b"vias").hexdigest())
        self.assertEqual(vias["url"], "https://exemplo.org/dados/vias.db")

    def test_publica_arquivos_avulsos_sem_pastas(self):
        manifesto.gerar(self.rumo, self.saida, "https://exemplo.org", "sc", "SC")
        self.assertEqual(
            sorted(os.listdir(self.saida)),
            ["dados.json", "fontes_NotoSansMedium_0-255.pbf", "mapa.pmtiles", "vias.db"],
        )
        with open(os.path.join(self.saida, "dados.json"), encoding="utf-8") as f:
            lista = json.load(f)["regioes"][0]["arquivos"]
        fonte = next(a for a in lista if a["destino"].startswith("fontes/"))
        self.assertTrue(fonte["url"].endswith("/fontes_NotoSansMedium_0-255.pbf"))

    def test_ignora_o_leiame(self):
        m = manifesto.gerar(self.rumo, self.saida, "https://exemplo.org", "sc", "SC")
        self.assertNotIn("LEIAME.txt", [a["destino"] for a in m["regioes"][0]["arquivos"]])


if __name__ == "__main__":
    unittest.main()
