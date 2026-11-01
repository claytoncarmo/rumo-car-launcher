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

### Nomes que já estão no campo continuam como estão

Alguns nomes foram publicados ou gravados no aparelho antes desta regra e o app instalado depende deles. Renomear sem migração quebraria o que já funciona, então **eles ficam em português** até haver uma migração planejada:

| O quê | Nomes |
|---|---|
| Chaves de preferências (`SharedPreferences`) | `tema`, `ultima_lat`, `ultima_lon`, `ultimo_rumo`, `ultima_fonte`, `favoritas`, `radio_nomes`, `radio_encontradas`, `radio_mudo` e o prefixo `dados_` (hash de cada arquivo de dados instalado) |
| Pasta `Rumo` no aparelho | `regioes/<id>/`, `fontes/`, `mapa.pmtiles`, `vias.db`, `contorno.geojson`, `maparadar.csv` |
| Manifesto dos dados (`dados.json`) | as chaves que o app lê (`regioes`, `comum`, `grupo`, `destino`, `tamanho` e as demais de `core/Releases.kt`) |
| Releases | as tags `rumo-dados` e `rumo-maparadar` e os nomes dos arquivos publicados (`dados.json`, `rumo-dados-<estado>.zip`, `maparadar.json`) |
| Tabelas do `vias.db` | já são em inglês (`ways`, `segs`, `radars`, `info`) |

Quem **cria** um contrato novo (uma chave, um arquivo, um campo de manifesto) nomeia em inglês. Quem quiser trocar um nome da tabela acima precisa fazer o app ler os dois nomes por um tempo (ou migrar o que já foi gravado) e registrar a mudança em `TODO.md`.

## Código

- O build Android só roda onde há Android SDK: o CI (GitHub Actions) compila, testa e publica. Veja `android/README.md`.
- Lógica sem dependência do Android vai em `core/`, com testes em `src/test`; o que fala com o aparelho vai em `device/`; telas em `ui/`.
- Mantenha o estilo do arquivo em que está mexendo (comentários, nomes, formatação).
- Commits em português, com mensagem que explique o porquê; um assunto por commit.
- Não commite chaves nem segredos.
- Os dados do mapa vêm do OpenStreetMap e do MapaRadar; respeite as licenças descritas em `data/README.md` (o CSV do MapaRadar não pode ser alterado nem redistribuído em outro formato).
