# Dados offline

O Rumo funciona sem internet: o mapa, as vias com limite de velocidade e os radares vêm de uma pasta `Rumo` copiada para o aparelho. O workflow **Dados do mapa (OSM)** gera essa pasta a partir do OpenStreetMap e a publica em [Releases](../../../releases) como a pré-release **"Rumo · dados do mapa (SC)"** (`rumo-dados-sc.zip`).

Hoje o pacote cobre **Santa Catarina**. Ele é refeito todo dia 1 do mês e a cada mudança em `dados/` ou no workflow.

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
| `mapa.pmtiles` | Mapa vetorial (recorte do build diário do [Protomaps](https://docs.protomaps.com/basemaps/downloads), até o zoom 15), lido pelo MapLibre |
| `vias.db` | SQLite com as vias, o limite de velocidade por sentido e os radares (gerado por `vias.py`) |
| `fontes/` | Fontes (glifos) dos nomes de rua no mapa |
| `LEIAME.txt` | Região, data e origem do mapa |

Tamanhos em Santa Catarina: `mapa.pmtiles` ~310 MB, `vias.db` ~100 MB, pacote ~430 MB.

## `vias.py`

Lê um recorte do OpenStreetMap (`.osm.pbf`, só com `highway` e `highway=speed_camera`) e grava o `vias.db`:

| Tabela | Conteúdo |
|---|---|
| `info` | região, data e contagens |
| `ways` | uma linha por via: nome, referência (ex.: SC-401), tipo, mão única, limite nos dois sentidos e se cada limite é estimado |
| `segs` | trechos das vias por célula de 0,01° (`cell`), com os pontos em micrograus (`lat, lon` como inteiros de 32 bits) |
| `radars` | posição, limite fiscalizado e sentido (0–359°, ou −1 para os dois) |

A célula é calculada igual no gerador (`cell_of`) e no app (`core/Geo.kt`), e o app só lê as células perto do carro.

**Limite da via:** vem do `maxspeed` do OSM (e de `maxspeed:forward`/`backward`). Sem ele, o gerador usa os padrões do CTB pelo tipo da via e marca como **estimado**: urbanas (trânsito rápido 80, arterial 60, coletora 40, local 30) e rodovias (pista dupla 110, pista simples 100, estrada 60; alças no máximo 60). A zona urbana é inferida pela densidade de ruas residenciais na célula. Só cerca de 11% das vias de Santa Catarina têm `maxspeed` no OSM, concentrados nas vias principais.

**Radares:** pontos `highway=speed_camera`, com `direction` (ou a mão da via) para o sentido e `maxspeed` (ou o limite da via) para o limite. Santa Catarina tem cerca de 840 no OSM.

## Brasil inteiro

Estimativa a partir do OSM (2 GB para o Brasil contra 409 MB da região Sul): o mapa ficaria em 4 a 5 GB, mais ~1,2 GB de vias e radares, ou 6 a 7 GB no total. Cabe no aparelho (50 GB livres), mas passa do limite de 2 GB por arquivo das releases do GitHub, então teria de ser dividido em partes ou publicado com menos zooms.

## Testes

```bash
python3 -m unittest discover -s dados -p "*_test.py" -v
```

O gerador precisa de `pyosmium` (`pip install osmium`). O workflow roda os testes antes de gerar.

## Licença dos dados

© colaboradores do [OpenStreetMap](https://www.openstreetmap.org/copyright), sob a [ODbL](https://opendatacommons.org/licenses/odbl/). O pacote não é parte do código-fonte (GPL): é gerado e distribuído à parte, com o crédito no `LEIAME.txt` e no próprio mapa.
