import json
import os
import tempfile
import unittest

import maparadar as mr


def linha(lon, lat, desc):
    return [str(lon), str(lat), desc]


class ParseTest(unittest.TestCase):
    def test_radar_fixo_com_limite(self):
        p = mr.parse_linha(linha(-48.5, -27.6, "Radar Fixo - 60 kmh@60"), 7)
        self.assertEqual((p.id, p.kind, p.lim), (-7, mr.RADAR_FIXO, 60))
        self.assertAlmostEqual(p.lat, -27.6)
        self.assertAlmostEqual(p.lon, -48.5)

    def test_tipos_sem_acento(self):
        casos = {
            "Semaforo com Radar - 40 kmh@40": (mr.SEMAFORO_RADAR, 40),
            "Radar Movel - 60 kmh@60": (mr.RADAR_MOVEL, 60),
            "Semaforo com Camera - 0 kmh@0": (mr.SEMAFORO_CAMERA, 0),
            "Policia Rodoviaria - 0 kmh@0": (mr.POLICIA, 0),
            "Pedagio - 0 kmh@0": (mr.PEDAGIO, 0),
        }
        for desc, (kind, lim) in casos.items():
            p = mr.parse_linha(linha(-48, -27, desc), 1)
            self.assertEqual((p.kind, p.lim), (kind, lim), desc)

    def test_lombada_comum_e_eletronica(self):
        comum = mr.parse_linha(linha(-48, -27, "Lombada - 0 kmh@0"), 1)
        self.assertEqual((comum.kind, comum.lim), (mr.LOMBADA, 0))
        eletronica = mr.parse_linha(linha(-48, -27, "Lombada - 50 kmh@50"), 2)
        self.assertEqual((eletronica.kind, eletronica.lim), (mr.LOMBADA_ELETRONICA, 50))

    def test_so_quem_fiscaliza_guarda_limite(self):
        p = mr.parse_linha(linha(-48, -27, "Pedagio - 80 kmh@80"), 1)
        self.assertEqual(p.lim, 0)

    def test_linhas_ruins_sao_ignoradas(self):
        ruins = [
            ["a", "b", "Radar Fixo - 60 kmh@60"],
            linha(-48, -27, "Tipo Novo - 60 kmh@60"),
            linha(-48, -27, "Radar Fixo"),
            linha(-48, -27, "Radar Fixo - 300 kmh@300"),
            linha(-10, -27, "Radar Fixo - 60 kmh@60"),  # fora do Brasil
            ["-48", "-27"],
        ]
        for campos in ruins:
            self.assertIsNone(mr.parse_linha(campos, 1), campos)


class ArquivoTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)

    def tearDown(self):
        self.tmp.cleanup()

    def test_le_o_csv_sem_repetidos_e_com_id_pela_linha(self):
        csv = os.path.join(self.tmp.name, "m.csv")
        with open(csv, "w", encoding="utf-8") as f:
            f.write("-48.500000,-27.600000,Radar Fixo - 60 kmh@60\n")
            f.write("-48.500000,-27.600000,Radar Fixo - 60 kmh@60\n")  # repetido
            f.write("lixo\n")
            f.write("-48.510000,-27.610000,Pedagio - 0 kmh@0\n")
        pontos = mr.ler(csv)
        self.assertEqual([p.kind for p in pontos], [mr.RADAR_FIXO, mr.PEDAGIO])
        self.assertEqual([p.id for p in pontos], [-1, -4])

    def test_recorta_pelo_contorno(self):
        quadrado = [[[-49, -28], [-48, -28], [-48, -27], [-49, -27], [-49, -28]]]
        caminho = os.path.join(self.tmp.name, "estado.geojson")
        with open(caminho, "w", encoding="utf-8") as f:
            json.dump({"type": "Feature", "properties": {}, "geometry": {"type": "Polygon", "coordinates": quadrado}}, f)
        dentro = mr.Ponto(-1, -27.5, -48.5, mr.RADAR_FIXO, 60)
        fora = mr.Ponto(-2, -25.0, -48.5, mr.RADAR_FIXO, 60)
        self.assertEqual(mr.dentro([dentro, fora], caminho), [dentro])


class MesclarTest(unittest.TestCase):
    def sem_via(self, lat, lon):
        return None

    def test_sentido_do_radar_do_osm_que_e_o_mesmo_ponto(self):
        osm = [(100, -27.6000, -48.5000, 60, 90)]
        pontos = [mr.Ponto(-1, -27.60002, -48.50002, mr.RADAR_FIXO, 60)]
        linhas = mr.mesclar(osm, pontos, self.sem_via)
        self.assertEqual(linhas, [(-1, -27.60002, -48.50002, 60, 90, mr.RADAR_FIXO)])

    def test_radar_do_osm_sem_par_continua(self):
        osm = [(100, -27.6, -48.5, 60, 90)]
        pontos = [mr.Ponto(-1, -27.7, -48.5, mr.RADAR_FIXO, 60)]
        linhas = mr.mesclar(osm, pontos, self.sem_via)
        self.assertEqual(sorted(l[0] for l in linhas), [-1, 100])
        self.assertEqual(next(l for l in linhas if l[0] == 100)[5], 0)

    def test_via_de_mao_unica_da_o_sentido(self):
        pontos = [mr.Ponto(-1, -27.6, -48.5, mr.RADAR_FIXO, 60)]
        linhas = mr.mesclar([], pontos, lambda lat, lon: (270.4, True))
        self.assertEqual(linhas[0][4], 270)

    def test_via_de_mao_dupla_vale_os_dois_sentidos(self):
        pontos = [mr.Ponto(-1, -27.6, -48.5, mr.RADAR_FIXO, 60)]
        linhas = mr.mesclar([], pontos, lambda lat, lon: (270.0, False))
        self.assertEqual(linhas[0][4], -1)

    def test_pedagio_nao_come_o_radar_do_osm_ao_lado(self):
        osm = [(100, -27.6, -48.5, 60, 90)]
        pontos = [mr.Ponto(-1, -27.6, -48.5, mr.PEDAGIO, 0)]
        linhas = mr.mesclar(osm, pontos, self.sem_via)
        self.assertEqual(len(linhas), 2)

    def test_um_radar_do_osm_so_casa_com_um_ponto(self):
        osm = [(100, -27.6, -48.5, 60, 90)]
        pontos = [mr.Ponto(-1, -27.6, -48.5, mr.RADAR_FIXO, 60), mr.Ponto(-2, -27.6, -48.50001, mr.RADAR_FIXO, 60)]
        linhas = mr.mesclar(osm, pontos, self.sem_via)
        self.assertEqual(sorted(l[4] for l in linhas), [-1, 90])


if __name__ == "__main__":
    unittest.main()
