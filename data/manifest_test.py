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


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = self.tmp.name
        self.output = os.path.join(base, "out")
        self.common = os.path.join(base, "common")
        self.sc = os.path.join(base, "sc")
        self.pr = os.path.join(base, "pr")
        write(os.path.join(self.common, "fonts", "NotoSansMedium", "0-255.pbf"), b"fonte")
        write(os.path.join(self.sc, "regions", "sc", "map.pmtiles"), b"mapa" * 100)
        write(os.path.join(self.sc, "regions", "sc", "roads.db"), b"vias")
        write(os.path.join(self.sc, "README.txt"), b"texto")
        write(os.path.join(self.pr, "regions", "pr", "map.pmtiles"), b"mapa pr")
        write(os.path.join(self.pr, "regions", "pr", "roads.db"), b"vias pr")
        self.regions = [("sc", "Santa Catarina", self.sc), ("pr", "Paraná", self.pr)]

    def tearDown(self):
        self.tmp.cleanup()

    def test_lists_regions_with_hash_size_and_url(self):
        m = manifest.generate(self.output, "https://exemplo.org/data/", self.common, self.regions)
        self.assertEqual(m["version"], 3)
        self.assertEqual([(r["id"], r["name"]) for r in m["regions"]], [("sc", "Santa Catarina"), ("pr", "Paraná")])
        sc = {a["dest"]: a for a in m["regions"][0]["files"]}
        self.assertEqual(sorted(sc), ["regions/sc/map.pmtiles", "regions/sc/roads.db"])
        roads = sc["regions/sc/roads.db"]
        self.assertEqual(roads["size"], 4)
        self.assertEqual(roads["sha256"], hashlib.sha256(b"vias").hexdigest())
        self.assertEqual(roads["url"], "https://exemplo.org/data/regions_sc_roads.db")

    def test_fonts_go_in_common_and_not_in_each_region(self):
        m = manifest.generate(self.output, "https://exemplo.org", self.common, self.regions)
        self.assertEqual([a["dest"] for a in m["common"]], ["fonts/NotoSansMedium/0-255.pbf"])
        for r in m["regions"]:
            self.assertTrue(all(a["dest"].startswith(f"regions/{r['id']}/") for a in r["files"]))

    def test_publishes_loose_files_without_folders(self):
        manifest.generate(self.output, "https://exemplo.org", self.common, self.regions)
        self.assertEqual(
            sorted(os.listdir(self.output)),
            [
                "data.json", "fonts_NotoSansMedium_0-255.pbf",
                "regions_pr_map.pmtiles", "regions_pr_roads.db",
                "regions_sc_map.pmtiles", "regions_sc_roads.db",
            ],
        )
        with open(os.path.join(self.output, "data.json"), encoding="utf-8") as f:
            common = json.load(f)["common"]
        self.assertTrue(common[0]["url"].endswith("/fonts_NotoSansMedium_0-255.pbf"))

    def test_ignores_the_readme(self):
        m = manifest.generate(self.output, "https://exemplo.org", self.common, self.regions)
        self.assertNotIn("README.txt", [a["dest"] for a in m["regions"][0]["files"]])


if __name__ == "__main__":
    unittest.main()
