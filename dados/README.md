# Dados offline

O Rumo funciona sem internet: o mapa, as vias com limite de velocidade e os radares vêm de uma pasta `Rumo` copiada para o aparelho. O workflow **Dados do mapa (OSM)** gera essa pasta a partir do OpenStreetMap e a publica em [Releases](../../../releases) como a pré-release **"Rumo · dados do mapa (SC)"** (`rumo-dados-sc.zip`).

Hoje o pacote cobre **Santa Catarina** (contorno do estado em `regioes/sc.geojson`, com ~8 km de folga para andar além da divisa). Ele é refeito todo dia 1 do mês e a cada mudança em `dados/` ou no workflow.

## Instalar no aparelho

**Pelo app (recomendado):** abra o painel Rumo (7 toques no relógio, com o carro parado, ou toque em "Sem dados do mapa" no card do mapa) e use **Baixar** em "Dados do mapa". O app baixa cada arquivo direto para `/sdcard/Rumo`, com retomada, confere o SHA-256 e depois só baixa de novo o que mudou. Arquivos copiados à mão são reconhecidos pelo hash.

**À mão:**

1. Baixe `rumo-dados-sc.zip` e descompacte no computador: sai uma pasta `Rumo`.
2. Copie a pasta `Rumo` inteira para a **memória interna** do aparelho (a raiz, ficando `/sdcard/Rumo`). O Rumo também acha a pasta na raiz de um pendrive ou cartão, mas o mapa fica mais rápido na memória interna.
3. Abra o Rumo e permita o acesso aos arquivos. Se a pasta for copiada com o Rumo aberto, basta apertar Início.

## Manifesto (`dados.json`)

O workflow publica, ao lado do zip, cada arquivo como asset solto e um `dados.json` (`manifesto.py`) com a lista de regiões: para cada arquivo, `destino` dentro da pasta Rumo, `tamanho`, `sha256` e `url`. O app só aceita https e destinos dentro da pasta Rumo. O formato já prevê várias regiões, mas hoje há uma só (SC).

## O que tem na pasta

| Arquivo | O que é |
|---|---|
| `mapa.pmtiles` | Mapa vetorial (recorte do build diário do [Protomaps](https://docs.protomaps.com/basemaps/downloads) pelo contorno do estado, até o zoom 14, filtrado por `mapa_filtro.py`), lido pelo MapLibre |
| `vias.db` | SQLite com as vias, o limite de velocidade por sentido e os radares (gerado por `vias.py`) |
| `fontes/` | Fontes (glifos) dos nomes de rua no mapa |
| `LEIAME.txt` | Região, data e origem do mapa |

Tamanhos em Santa Catarina: `mapa.pmtiles` ~37 MB, `vias.db` ~38 MB, pacote ~62 MB (o recorte bruto do Protomaps tem 119 MB).

## `mapa_filtro.py`

O mapa-múndi do Protomaps traz prédios, pontos de interesse, fronteiras, nomes de lugares, trilhas, calçadas e parques que o Rumo não desenha. O filtro abre cada tile e deixa só o que o estilo do app (`DriveMap.kt`) usa:

- **água:** polígonos (menos piscinas e fontes) e linhas de rios e canais; sem riachos;
- **vias:** `minor_road`, `major_road` e `highway`, sem as de serviço (garagens, estacionamentos), com o nome a partir do zoom 14;
- tudo mais (prédios, lugares, fronteiras, parques, terra, trilhas, trilhos) sai.

Se o estilo passar a desenhar outra coisa, mude `filtrar` junto. `mapa_confere.py` compara uma amostra de tiles do resultado com o que devia ficar e falha o workflow se sumir mais de 1% das feições (a recodificação descarta geometrias inválidas sem avisar). O zoom 14 é o máximo: acima dele o MapLibre amplia o último nível, e o app chega ao zoom 16,5 nas ruas da cidade.

## `vias.py`

Lê um recorte do OpenStreetMap (`.osm.pbf`, só com `highway` e `highway=speed_camera`) e grava o `vias.db`:

| Tabela | Conteúdo |
|---|---|
| `info` | região, data e contagens |
| `ways` | uma linha por via: nome, referência (ex.: SC-401), tipo, mão única, limite nos dois sentidos e se cada limite é estimado |
| `segs` | trechos das vias por célula de 0,01° (`cell`, `way`, `n`), sem rowid; os pontos (`pts`) em micrograus, cada um como diferença para o anterior, em zigue-zague e varint (`info.versao` = 2; a 1 guardava inteiros de 32 bits e o app ainda a lê) |
| `radars` | posição, limite fiscalizado e sentido (0–359°, ou −1 para os dois) |

A célula é calculada igual no gerador (`cell_of`) e no app (`core/Geo.kt`), e o app só lê as células perto do carro.

**Limite da via:** vem do `maxspeed` do OSM (e de `maxspeed:forward`/`backward`). Sem ele, o gerador usa os padrões do CTB pelo tipo da via e marca como **estimado**: urbanas (trânsito rápido 80, arterial 60, coletora 40, local 30) e rodovias (pista dupla 110, pista simples 100, estrada 60; alças no máximo 60). A zona urbana é inferida pela densidade de ruas residenciais na célula. Só cerca de 11% das vias de Santa Catarina têm `maxspeed` no OSM, concentrados nas vias principais.

**Radares:** pontos `highway=speed_camera`, com `direction` (ou a mão da via) para o sentido e `maxspeed` (ou o limite da via) para o limite. Santa Catarina tem cerca de 840 no OSM.

## Brasil inteiro

Com o filtro e o `vias.db` enxuto, Santa Catarina pesa ~75 MB. Estimando pela área e pela densidade de vias, o Brasil inteiro ficaria na casa de 1,5 a 2 GB no total (hoje o limite de 2 GB por arquivo das releases não seria problema, porque os arquivos seriam por estado).

## Testes

```bash
python3 -m unittest discover -s dados -p "*_test.py" -v
```

O gerador precisa de `pyosmium`, e o filtro de `pmtiles`, `mapbox-vector-tile` e `shapely` (`pip install osmium pmtiles mapbox-vector-tile==2.2.0 shapely`). O workflow roda os testes antes de gerar.

## Licença dos dados

© colaboradores do [OpenStreetMap](https://www.openstreetmap.org/copyright), sob a [ODbL](https://opendatacommons.org/licenses/odbl/). O pacote não é parte do código-fonte (GPL): é gerado e distribuído à parte, com o crédito no `LEIAME.txt` e no próprio mapa.
