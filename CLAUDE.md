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
- `device/`: acesso ao aparelho (Gps, Apps, MapData, Alerts, Aidl, CarStatus, RadioStation, RadioControl) e os diagnósticos (BroadcastHunt, CarSourceProbe, McuTap, NightSignal, RadioProbe).
- `ui/`: telas em Compose (Home, RadioSheet, DriveMap, Theme, Icons).
- `HomeActivity.kt`: liga tudo.

## Decisões tomadas (não reabrir sem o dono pedir)

- **Navegação fica no Android Auto** (ZLINK5, `com.zjinnova.zlink`). O mapa da tela inicial é **passivo**: rua atual, placa de limite da via e, acima do limite, a velocidade em vermelho. Tocar no mapa abre o ZLINK.
- **Radares:** aviso curto ao entrar na zona; acima do limite do radar, bipes que aceleram e sobem de tom conforme se aproxima. Param ao reduzir ou passar.
- **Alertas sonoros só com o launcher na tela.** Nada por cima do Android Auto: sem balão/overlay e sem serviço em segundo plano.
- **Dados:** limites pelo OpenStreetMap offline + padrões do CTB quando faltar (marcados "estimado"); radares por fontes de uso permitido (OSM, ANTT, dados abertos de prefeituras; HERE como opção paga). Não embutir a base do Maparadar sem autorização.
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

- Validar o **mudo do `fmradio`** (`setMute`) como "parar" do play do card de mídia no rádio.
- Testar andando: rua e limite em cruzamentos e ruas paralelas, avisos de radar, desempenho do mapa e a velocidade do GPS contra o painel do carro.
- Completar os radares além do OSM (ANTT, dados abertos de prefeituras).
- Fonte Barlow ainda não adicionada; o cartão inteligente só tem a página Viagem; telefone/Bluetooth não integrados.
- Validar no aparelho o painel Rumo (7 toques no relógio): TLS e relógio do aparelho nas conexões https, tela de "instalar apps desconhecidos" no firmware, instalação por cima e troca do mapa com ele aberto (a tela é recriada).
- Validar no aparelho o **Reiniciar o Android** do painel Rumo (seção Sistema): tenta `PowerManager.reboot` (precisa ser app privilegiado) e `su`; se o aparelho não tiver nenhum dos dois, só avisa. Se falhar, a mensagem diz o motivo de cada tentativa; aí o jeito é o botão RST da unidade.
- Mais de um estado nos dados: o formato do `dados.json` já tem regiões, mas o app usa só a primeira; falta unir várias regiões no app (vias com ids próprios, várias fontes de mapa) e escolher no painel.
- Servidor de relatórios na rede, para dispensar o pendrive nos testes.
