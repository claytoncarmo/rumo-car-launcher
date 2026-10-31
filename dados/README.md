# Dados offline

O Rumo funciona sem internet: o mapa, as vias com limite de velocidade e os radares vêm de uma pasta `Rumo` copiada para o aparelho. O workflow **Dados do mapa (OSM)** gera essa pasta a partir do OpenStreetMap e a publica em [Releases](../../../releases) como a pré-release **"Rumo · dados do mapa (Sul e Sudeste)"**, com um `rumo-dados-<estado>.zip` por estado.

Hoje o pacote cobre os estados do **Sul** (PR, SC, RS) e do **Sudeste** (SP, RJ, MG, ES). Cada estado tem seu contorno em `regioes/<id>.geojson` (limite do OSM com ~8 km de folga para andar além da divisa; `contorno.py` gera) e é um job próprio do workflow, em paralelo. Os dados são refeitos todo dia 1 do mês e a cada mudança em `dados/` ou no workflow. Para incluir outro estado: um contorno novo (`python3 contorno.py <relação OSM> "<nome>" regioes/<id>.geojson`, com o `grupo` nas propriedades), uma linha na matriz e em `ESTADOS` do workflow.

## Instalar no aparelho

**Pelo app (recomendado):** abra o painel Rumo (7 toques no relógio, com o carro parado, ou toque em "Sem dados do mapa" no card do mapa). Em "Mapas por estado" estão os estados disponíveis, agrupados em Sul e Sudeste, com o tamanho de cada um. Marque os que quer e toque no botão: o app baixa os marcados que faltam (ou estão desatualizados) e remove os desmarcados que estavam instalados. Os estados instalados vêm marcados. Cada arquivo vai direto para `/sdcard/Rumo`, com retomada, confere o SHA-256 e depois só baixa de novo o que mudou. Arquivos copiados à mão são reconhecidos pelo hash. Vários estados valem ao mesmo tempo: o mapa desenha todos e a rua atual e o limite vêm das vias de todos, então cruzar a divisa não muda nada.

**À mão:**

1. Baixe o `rumo-dados-<estado>.zip` de cada estado que quer (ex.: `rumo-dados-sc.zip`) e descompacte no computador: sai uma pasta `Rumo` com as fontes e `regioes/<estado>`.
2. Copie a pasta `Rumo` para a **memória interna** do aparelho (a raiz, ficando `/sdcard/Rumo`); com vários estados, copie cada um para a mesma pasta `Rumo` (as fontes se repetem e podem ser sobrescritas). O Rumo também acha a pasta na raiz de um pendrive ou cartão, mas o mapa fica mais rápido na memória interna.
3. Abra o Rumo e permita o acesso aos arquivos. Se a pasta for copiada com o Rumo aberto, basta apertar Início.

## Manifesto (`dados.json`)

O workflow publica, ao lado dos zips, cada arquivo como asset solto e um `dados.json` (`manifesto.py`, `versao` 2) com os arquivos `comum` (as fontes) e a lista de regiões (`id`, `nome`, `grupo`); em cada arquivo, `destino` dentro da pasta Rumo, `tamanho`, `sha256` e `url`. O app só aceita https, destinos dentro da pasta Rumo e ids de região só com letras minúsculas, números e sublinhado (o id vira a pasta `regioes/<id>`).

## O que tem na pasta

| Arquivo | O que é |
|---|---|
| `regioes/<estado>/mapa.pmtiles` | Mapa vetorial (recorte do build diário do [Protomaps](https://docs.protomaps.com/basemaps/downloads) pelo contorno do estado, até o zoom 14, filtrado por `mapa_filtro.py`), lido pelo MapLibre |
| `regioes/<estado>/vias.db` | SQLite com as vias, o limite de velocidade por sentido e os radares (gerado por `vias.py`) |
| `fontes/` | Fontes (glifos) dos nomes de rua no mapa |
| `regioes/<estado>/contorno.geojson` | Contorno do estado (com a folga da divisa), para recortar os radares do MapaRadar no app |
| `LEIAME.txt` | Estado, data e origem do mapa (só nos zips) |

Tamanhos em Santa Catarina: `mapa.pmtiles` ~37 MB, `vias.db` ~38 MB, pacote ~62 MB (o recorte bruto do Protomaps tem 119 MB). Os demais estados estão na tabela de tamanhos do resumo de cada execução do workflow. Quem já tinha o formato antigo (`mapa.pmtiles` e `vias.db` direto em `Rumo/`, só SC) continua funcionando até baixar um estado; depois o app apaga os arquivos antigos.

## `mapa_filtro.py`

O mapa-múndi do Protomaps traz prédios, pontos de interesse, fronteiras, nomes de lugares, trilhas, calçadas e parques que o Rumo não desenha. O filtro abre cada tile e deixa só o que o estilo do app (`DriveMap.kt`) usa:

- **água:** polígonos (menos piscinas e fontes) e linhas de rios e canais; sem riachos;
- **vias:** `minor_road`, `major_road` e `highway`, sem as de serviço (garagens, estacionamentos), com o nome a partir do zoom 14;
- tudo mais (prédios, lugares, fronteiras, parques, terra, trilhas, trilhos) sai.

Se o estilo passar a desenhar outra coisa, mude `filtrar` junto. `mapa_confere.py` compara uma amostra de tiles do resultado com o que devia ficar e falha o workflow se sumir mais de 1% das feições (a recodificação descarta geometrias inválidas sem avisar). O zoom 14 é o máximo: acima dele o MapLibre amplia o último nível, e o app chega ao zoom 16,5 nas ruas da cidade.

## Radares e outros pontos: MapaRadar

`maparadar.csv` é a base do [MapaRadar](https://maparadar.com.br), mantida **à mão**: para atualizar, troque o arquivo (mesmo formato, sem cabeçalho: `longitude,latitude,Tipo - N kmh@N`) e faça o commit. O workflow `maparadar.yml` roda só nessa mudança e publica a release **"Rumo · MapaRadar (arquivo original)"** (`rumo-maparadar`) com uma **cópia idêntica** do CSV e um `maparadar.json` (tamanho e SHA-256). O arquivo tem ~40 mil pontos do Brasil inteiro.

**O app baixa o arquivo original e o processa no aparelho** (`core/MapaRadar.kt`, `MapData.loadMapaRadar`): lê o CSV, recorta pelos contornos dos estados instalados (cada estado leva o seu `contorno.geojson`), acha em cada ponto a via mais perto para saber o sentido e junta com os radares do OSM. O resultado fica em cache no aparelho e só se refaz quando o CSV ou os dados dos estados mudam. Os radares do OSM aparecem logo; os do MapaRadar entram quando o processamento termina (alguns segundos na primeira vez). Nada derivado do CSV é publicado.

| Tipo no CSV | `kind` | No app |
|---|---|---|
| Radar Fixo | 1 | amarelo, com o limite; aviso, "acima do limite" e bipes |
| Semaforo com Radar | 2 | laranja, igual ao radar fixo |
| Radar Movel | 3 | laranja escuro, igual ao radar fixo |
| Lombada, com limite | 5 | lombada eletrônica: amarelo, igual ao radar fixo |
| Lombada, 0 km/h | 4 | lombada comum: cinza, só aviso na tela (zona de 300 m, sem som) |
| Semaforo com Camera | 6 | vermelho (S), só aviso na tela |
| Policia Rodoviaria | 7 | azul (P), aviso com som, sem limite |
| Pedagio | 8 | verde ($), só aviso na tela |

`kind` 0 é um radar do OSM que não casou com nenhum ponto do MapaRadar (continua valendo, como radar fixo). O CSV não traz o sentido: vale o do radar do OSM a até 50 m (que é o mesmo ponto e sai da lista), senão o da via se for de mão única, senão os dois sentidos. O mesmo ponto tem o mesmo id (o número da linha, negativo) em todos os estados. Linhas fora do Brasil, de tipo novo ou sem o formato são ignoradas. Para copiar à mão, ponha o `maparadar.csv` original na pasta `Rumo`.

**Licença:** a base é CC BY-NC-ND 4.0 (uso pessoal, com atribuição, sem fins comerciais e **sem alterações**). Por isso o projeto só **distribui o arquivo original**, sem alterações, e a conversão acontece no aparelho de cada pessoa (adaptação privada). O `vias.db` e os zips das Releases têm só dados do OpenStreetMap. A atribuição está aqui, no painel do app, nas notas da release e no `LEIAME.txt` dos zips. Confira os termos de uso do site do MapaRadar antes de divulgar o projeto mais amplamente.

## `vias.py`

Lê um recorte do OpenStreetMap (`.osm.pbf`, só com `highway` e `highway=speed_camera`) e grava o `vias.db`:

| Tabela | Conteúdo |
|---|---|
| `info` | região, data e contagens |
| `ways` | uma linha por via: nome, referência (ex.: SC-401), tipo, mão única, limite nos dois sentidos e se cada limite é estimado |
| `segs` | trechos das vias por célula de 0,01° (`cell`, `way`, `n`), sem rowid; os pontos (`pts`) em micrograus, cada um como diferença para o anterior, em zigue-zague e varint (`info.versao` = 2; a 1 guardava inteiros de 32 bits e o app ainda a lê) |
| `radars` | posição, limite fiscalizado (0 = não fiscaliza velocidade) e sentido (0–359°, ou −1 para os dois), e `kind` (tipo; no vias.db é sempre 0, os tipos 1 a 8 vêm do MapaRadar no aparelho). O vias.db antigo, sem `kind`, ainda é lido |

A célula é calculada igual no gerador (`cell_of`) e no app (`core/Geo.kt`), e o app só lê as células perto do carro.

**Limite da via:** vem do `maxspeed` do OSM (e de `maxspeed:forward`/`backward`). Sem ele, o gerador usa os padrões do CTB pelo tipo da via e marca como **estimado**: urbanas (trânsito rápido 80, arterial 60, coletora 40, local 30) e rodovias (pista dupla 110, pista simples 100, estrada 60; alças no máximo 60). A zona urbana é inferida pela densidade de ruas residenciais na célula. Só cerca de 11% das vias de Santa Catarina têm `maxspeed` no OSM, concentrados nas vias principais.

**Radares do OSM:** pontos `highway=speed_camera`, com `direction` (ou a mão da via) para o sentido e `maxspeed` (ou o limite da via) para o limite. Santa Catarina tem cerca de 600 no OSM; o MapaRadar tem mais (e outros tipos de ponto), e o app junta os dois no aparelho.

## Brasil inteiro

Com o filtro e o `vias.db` enxuto, Santa Catarina pesa ~75 MB. Estimando pela área e pela densidade de vias, o Brasil inteiro ficaria na casa de 1,5 a 2 GB no total; como os arquivos são por estado, o limite de 2 GB por arquivo das releases não é problema. Os próximos passos seriam as regiões Centro-Oeste, Nordeste e Norte (uma linha na matriz e um contorno por estado).

## Testes

```bash
python3 -m unittest discover -s dados -p "*_test.py" -v
```

O gerador precisa de `pyosmium`, e o filtro e o contorno de `pmtiles`, `mapbox-vector-tile` e `shapely` (`pip install osmium pmtiles mapbox-vector-tile==2.2.0 shapely`). O workflow roda os testes antes de gerar.

## Licença dos dados

Mapa e vias: © colaboradores do [OpenStreetMap](https://www.openstreetmap.org/copyright), sob a [ODbL](https://opendatacommons.org/licenses/odbl/). Radares, lombadas, pedágios e polícia rodoviária: [MapaRadar](https://maparadar.com.br), sob a [CC BY-NC-ND 4.0](https://creativecommons.org/licenses/by-nc-nd/4.0/), distribuídos só no arquivo original. O pacote não é parte do código-fonte (GPL): é gerado e distribuído à parte, com o crédito no `LEIAME.txt` e no próprio mapa.
