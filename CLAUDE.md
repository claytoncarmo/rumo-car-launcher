# Rumo · contexto para o Claude Code

Launcher (tela inicial) próprio para o multimídia Android aftermarket de um Honda. Converse e escreva em **português do Brasil**.

## Mapa do repositório

| Caminho | O que é |
|---|---|
| `android/launcher/` | O app (Kotlin + Jetpack Compose só com `foundation`, `minSdk 26`, MapLibre). Detalhes em `android/README.md` |
| `dados/` | Gerador dos dados offline (mapa, vias, limites e radares do OpenStreetMap). Ver `dados/README.md` |
| `docs/estudo-telas-iniciais.md` | Estudo das telas iniciais das marcas, segurança, princípios, interface proposta e viabilidade |
| `docs/integracao-aparelho.md` | **Como falar com os serviços do multimídia** (farol, fonte de áudio, rádio): números de transação, formatos e comportamentos |
| `prototype/index.html` | Protótipo HTML: referência visual e de comportamento |

Código do app (`android/launcher/src/main/java/app/rumo/`):
- `core/`: lógica pura e testada (Geo, Roads, Radars, TripMeter, Formatting, Favorites, RadioNames, AudioOwner).
- `device/`: acesso ao aparelho (Gps, Apps, MapData, Alerts, Aidl, CarStatus, RadioStation, RadioControl) e os diagnósticos (BroadcastHunt, CarSourceProbe, NightSignal, RadioProbe).
- `ui/`: telas em Compose (Home, RadioSheet, DriveMap, Theme, Icons).
- `HomeActivity.kt`: liga tudo.

## Decisões tomadas (não reabrir sem o dono pedir)

- **Navegação fica no Android Auto** (ZLINK5, `com.zjinnova.zlink`). O mapa da tela inicial é **passivo**: rua atual, placa de limite da via (na direita, onde a mão do motorista não tampa) e radar; acima do limite, a velocidade fica em vermelho só no cartão Viagem (o indicador vermelho do mapa saiu na 0.5.x, por excesso de indicadores). Tocar no mapa abre o ZLINK.
- **Radares:** aviso curto ao entrar na zona; acima do limite do radar, bipes que aceleram e sobem de tom conforme se aproxima. Param ao reduzir ou passar.
- **Alertas sonoros só com o launcher na tela.** Nada por cima do Android Auto: sem balão/overlay e sem serviço em segundo plano.
- **Dados:** limites pelo OpenStreetMap offline + padrões do CTB quando faltar (marcados "estimado"); radares: OSM junto com a base do MapaRadar (`dados/maparadar.csv`, CC BY-NC-ND 4.0, uso pessoal; o Clayton atualiza o arquivo à mão e o workflow recorta por estado). **Atenção à cláusula ND:** o recorte por estado vai nas Releases públicas; se isso virar problema, tirar `--maparadar` do `dados.yml` (ver `dados/README.md`). Outras fontes possíveis: ANTT, dados abertos de prefeituras.
- **Rádio:** tela própria e favoritas próprias (lista do Rumo, em ordem de frequência, com nome dado pelo dono; só com o carro parado). A procura de estações repete a busca do rádio; **nunca usar o "AS" (auto store)** e não gravar nas memórias do aparelho sem o dono pedir. Abrir a tela de rádio já põe o rádio para tocar (sem botão "Ouvir rádio").
- **Fonte e estação:** a fonte de áudio vem do serviço `sourceinfo` e a estação do `fmradio` (ver `docs/integracao-aparelho.md`); o tema automático segue o farol (`carstatus`).
- **O carro não tem controles de som no volante:** tudo é pela tela. Roteiros de teste não devem citar volante.

## Aparelho de referência

HCN, Android **9 (API 28)** embora exiba "15", 32 bits (`armeabi-v7a`), 8× Cortex-A53 1,5 GHz, Mali-T720 (GLES 3.1), 4 GB, tela 1280×720 a 160 dpi (1 dp = 1 px). Perfil completo no estudo; integração em `docs/integracao-aparelho.md`. Orçamento de desempenho conservador: sem desfoques, sombras grandes nem animações contínuas; mapa a 30 qps.

## Convenções

- Commits em português, mensagem descritiva, com `Co-Authored-By` quando feitos com o Claude.
- **Não há Android SDK nesta máquina:** compilar e testar é pelo CI (GitHub Actions). Para ver uma mudança no aparelho, faça push na `main` e use o APK do **nightly**; versões estáveis saem por tag `vX.Y.Z`.
- O dono autorizou **commitar e dar push na `main` sem pedir confirmação** e acompanhar o CI. Apagar releases, force push e ações fora deste repositório continuam pedindo confirmação.
- Não commitar chaves nem segredos. A chave de assinatura vem de segredos do repositório (ver `android/README.md`).
- Documentação: guardar só o que ensina algo útil (como funciona, por quê). Sem diário de versões nem relato do que não funcionou. Ao descobrir algo novo sobre o aparelho, atualizar `docs/integracao-aparelho.md`.
- Testes no aparelho: o dono manda fotos e os relatórios `teste-*.txt` do pendrive (ver "Diagnósticos" em `docs/integracao-aparelho.md`).

## Pendências

A lista viva de tarefas (incluindo suporte a outros aparelhos) está em `TODO.md`.

- Validar o **mudo do `fmradio`** (`setMute`) como "parar" do play do card de mídia no rádio.
- Testar andando: rua e limite em cruzamentos e ruas paralelas, avisos de radar, desempenho do mapa e a velocidade do GPS contra o painel do carro.
- Completar os radares além do OSM (ANTT, dados abertos de prefeituras).
- Fonte Barlow ainda não adicionada; o cartão inteligente só tem a página Viagem; telefone/Bluetooth não integrados.
- Validar no aparelho o painel Rumo (7 toques no relógio): TLS e relógio do aparelho nas conexões https, tela de "instalar apps desconhecidos" no firmware, instalação por cima e troca do mapa com ele aberto (a tela é recriada).
- **Reiniciar o Android** (painel Rumo › Sistema): `PowerManager.reboot` e `su` falharam no aparelho (31/out/2026: sem permissão `REBOOT`, sem root). A 0.5.x tenta o menu de energia por um serviço de acessibilidade (`device/PowerService.kt`: abre `GLOBAL_ACTION_POWER_DIALOG` e toca em "Reiniciar"; o Clayton liga o serviço "Rumo" em Acessibilidade) e tem o diagnóstico "Teste do reinício" (`device/RebootProbe.kt`, `teste-reinicio.txt`) que lista ganchos de reinício do fabricante. A validar no carro: se o firmware tem o menu de energia com "Reiniciar"; se não, olhar o relatório.
- **Estados (Sul e Sudeste)**: o painel Rumo lista os estados do `dados.json` (versão 2: `comum` + regiões com `grupo`), o usuário marca e aplica (baixa os marcados, remove os desmarcados). Dados em `Rumo/regioes/<id>/{mapa.pmtiles,vias.db}` e `Rumo/fontes`; `MapData` lê todos os estados (um `mapa.pmtiles` por fonte no estilo, vias de todos na busca da rua, radares sem repetir); formato antigo na raiz de `Rumo/` ainda é lido até haver um estado novo. A validar no carro.
- **Não se registrar no `mcu_service`** (`setcallback`): em 31/out/2026 os botões do aparelho (volume, home, power) pararam logo depois de o Teste do farol se registrar em 42 grupos da MCU e não voltaram com o restart do Android. Causa não confirmada (pode ser coincidência ou estado guardado na MCU). O registro saiu do app (`McuTap.kt` está no histórico do git); não recolocar sem o Clayton pedir.
- Servidor de relatórios na rede, para dispensar o pendrive nos testes.
