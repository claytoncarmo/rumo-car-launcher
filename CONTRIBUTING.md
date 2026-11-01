# Como contribuir

## Idioma

**Todo código novo é em inglês.** Isso vale para o que o computador lê e o que fica no repositório:

- nomes de arquivos, pastas e pacotes (`SettingsSheet.kt`, `data/roads.py`, `docs/device-integration.md`);
- classes, objetos, funções, variáveis, constantes, parâmetros e campos (`openSettings`, `minePoints`, `RadarKind.ENFORCING`);
- nomes de testes (`fixedRadarWithLimit`, `test_margin_grows_the_polygon`);
- chaves e identificadores novos: chaves de preferências, campos de JSON, nomes de arquivos gravados no aparelho (relatórios, caches, bancos), tags e arquivos de releases, ids de workflows, jobs e steps, variáveis de ambiente;
- nomes de ramos.

**Em português do Brasil** ficam o que uma pessoa lê:

- comentários e KDoc;
- documentação (`README.md`, `CLAUDE.md`, `docs/`, `TODO.md`…);
- mensagens de commit e descrições de PR;
- textos da interface (o que aparece na tela, nos avisos e nos relatórios de diagnóstico) e os textos das notas de release.

Nomes próprios não se traduzem: `Rumo`, `MapaRadar` (o serviço de onde vem o CSV de radares), `ZLINK`, `CTB` (Código de Trânsito Brasileiro) e os nomes que o fabricante do aparelho deu aos seus serviços e pacotes.

### O que o app grava também é em inglês

Chaves de preferências (`theme`, `favorites`…), a estrutura da pasta `Rumo` no aparelho (`regions/<id>/map.pmtiles`, `roads.db`, `contour.geojson`, `fonts/`), os campos dos manifestos (`data.json`, `maparadar.json`), as chaves da tabela `info` do `roads.db` e as tags e os arquivos das releases seguem a regra. Não se traduzem os nomes próprios: `Rumo`, `MapaRadar` (por isso `maparadar.csv`, `maparadar.json` e a tag `rumo-maparadar`) e os nomes do fabricante do aparelho.

**Cuidado ao renomear algo que já está no campo.** Trocar o nome de uma chave de preferência, de um arquivo gravado no aparelho ou de um campo de manifesto faz o app instalado perder o que tinha guardado (ou deixar de ler os dados publicados). Faça só com uma migração, ou avisando que o app recomeça do zero. Foi o caso da tradução geral de nomes (novembro de 2026): o app novo não lê as preferências antigas e baixa os dados de novo, e `Updater.removeOldLayout` apaga a pasta antiga (`regioes/`, `fontes/`) quando os dados novos estão no aparelho.

## Código

- O build Android só roda onde há Android SDK: o CI (GitHub Actions) compila, testa e publica. Veja `android/README.md`.
- Lógica sem dependência do Android vai em `core/`, com testes em `src/test`; o que fala com o aparelho vai em `device/`; telas em `ui/`.
- Mantenha o estilo do arquivo em que está mexendo (comentários, nomes, formatação).
- Commits em português, com mensagem que explique o porquê; um assunto por commit.
- Não commite chaves nem segredos.
- Os dados do mapa vêm do OpenStreetMap e do MapaRadar; respeite as licenças descritas em `data/README.md` (o CSV do MapaRadar não pode ser alterado nem redistribuído em outro formato).
