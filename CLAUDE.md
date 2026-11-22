# Rumo · contexto para o Claude Code

Launcher (tela inicial) próprio para o multimídia Android aftermarket de um Honda. Converse e escreva em **português do Brasil**.

## Mapa do repositório

| Caminho | O que é |
|---|---|
| `android/launcher/` | O app (Kotlin + Jetpack Compose só com `foundation`, `minSdk 26`, MapLibre). Detalhes em `android/README.md` |
| `android/webradio/` | App à parte só com o player do rádio online (`app.rumo.webradio`, sem tela); o protocolo com o Rumo fica em `android/shared/` |
| `data/` | Gerador dos dados offline (mapa, vias, limites e radares do OpenStreetMap). Ver `data/README.md` |
| `docs/home-screen-study.md` | Estudo das telas iniciais das marcas, segurança, princípios, interface proposta e viabilidade |
| `docs/device-integration.md` | **Como falar com os serviços do multimídia** (farol, fonte de áudio, rádio): números de transação, formatos e comportamentos |
| `prototype/index.html` | Protótipo HTML interativo, mantido em dia com o app (dados de exemplo); vai para o site em `/prototipo/` |
| `release-notes/` | Novidades de cada versão, mostradas no popup de atualização do app (`next.md` = nightly e próxima estável; `X.Y.Z.md` = cada estável). **Regras de escrita em `release-notes/README.md`** |
| `site/` | Página inicial do GitHub Pages (`site/index.html`, estática); o workflow `pages.yml` publica junto com o protótipo. Ao mudar o app de forma visível, atualize o protótipo e as funções listadas na página. A página tem português (texto-base no HTML), inglês e espanhol (dicionários em `site/i18n.js`, por chave `data-i18n`): abre no idioma do navegador, com seletor no topo. Ao mudar um texto, ajuste as três línguas; o protótipo continua só em português |

Código do app (`android/launcher/src/main/java/app/rumo/`):
- `android/shared/`: o que entra nos dois apps (protocolo do rádio online e os sons dos avisos, `Alerts`).
- `core/`: lógica pura e testada (Geo, Roads, Radars, TripMeter, Formatting, Favorites, RadioNames, AudioOwner, WebRadio, Elm327, ObdPids).
- `device/`: acesso ao aparelho (Gps, Apps, MapData, Aidl, CarStatus, RadioStation, RadioControl, WebRadioService/Player/Catalog, ObdLink/ObdScan) e os diagnósticos (BroadcastHunt, CarSourceProbe, NightSignal, RadioProbe).
- `ui/`: telas em Compose (Home, RadioSheet, DriveMap, Theme, Icons).
- `HomeActivity.kt`: liga tudo.

## Decisões tomadas (não reabrir sem o dono pedir)

- **Navegação fica no Android Auto** (ZLINK5, `com.zjinnova.zlink`). O mapa da tela inicial é **passivo**: rua atual, placa de limite da via (na direita, onde a mão do motorista não tampa) e radar; acima do limite, a velocidade fica em vermelho só no cartão Viagem (o indicador vermelho do mapa saiu na 0.5.x, por excesso de indicadores). Tocar no mapa abre o ZLINK.
- **Radares:** aviso curto ao entrar na zona; acima do limite do radar, bipes que aceleram e sobem de tom conforme se aproxima. Param ao reduzir ou passar.
- **Avisos de radar ajustáveis** em Configurações da home › Configurações: distância do aviso (padrão 300 m) e margem de velocidade (padrão 0, de 0 a 10 km/h; vale só para o som: o vermelho aparece assim que passa do limite).
- **Aviso de versão nova:** com o Rumo na tela, consulta a cada 4 horas (e ao voltar, se passou o prazo) só o canal da versão instalada: estável avisa só estável nova; nightly, só nightly nova. Acende um ícone de download ao lado do botão de tema; tocar (só parado) abre o popup com as novidades (`notes` do `rumo.json`) e o botão de baixar e instalar. Nada em segundo plano nem notificação do Android.
- **Alertas sonoros só com o launcher na tela.** Nada por cima do Android Auto: sem balão/overlay e sem serviço em segundo plano.
- **Pontos do usuário:** o "+" no card do mapa marca radar, lombada, polícia ou pedágio que falta, **só no aparelho** (`Rumo/my-points.csv` e `files/my-points.csv`, sobrevivem a atualizar o app; não vão para o repositório nem para as Releases). Posição e rumo são os do toque no "+". Detalhes em `android/README.md`.
- **Dados:** limites pelo OpenStreetMap offline + padrões do CTB quando faltar (marcados "estimado"); radares: OSM junto com a base do MapaRadar (`data/maparadar.csv`, CC BY-NC-ND 4.0, uso pessoal; o Clayton atualiza o arquivo à mão). **A licença proíbe distribuir alterações:** o projeto publica só a cópia ORIGINAL do CSV (release `rumo-maparadar`, workflow `maparadar.yml`) e o app a processa no aparelho (recorte pelos contornos dos estados, sentido pela via, junção com o OSM, cache em `radars.bin`); nada derivado dele vai para o `roads.db` nem para as Releases. Outras fontes possíveis: ANTT, dados abertos de prefeituras.
- **Rádio:** tela própria e favoritas próprias (lista do Rumo, em ordem de frequência, com nome dado pelo dono; só com o carro parado). A procura de estações repete a busca do rádio; **nunca usar o "AS" (auto store)** e não gravar nas memórias do aparelho sem o dono pedir. Abrir a tela de rádio já põe o rádio para tocar (sem botão "Ouvir rádio").
- **Rádio online:** aba Online na tela de rádio, só com rede (Wi-Fi do aparelho ou roteador do celular). Catálogo aberto Radio Browser; favoritas online próprias; Explorar com Perto de você, Notícias, Esportes, Música e Todas. O player mora num **app à parte** (`app.rumo.webradio`, instalado pela própria aba Online): o firmware encerra o pacote que tocou mídia quando outra fonte pega o som, e assim não leva a tela inicial. Toca em primeiro plano (com a notificação do Android) para continuar fora do Rumo, como o FM; o Android Auto tem prioridade (o player pausa quando ele pega o som). Tocar o online cala o FM e vice-versa. Se o Wi-Fi cai, para e oferece a mesma emissora no FM quando a frequência está no nome e tem sinal aqui.
- **Carro (OBD2):** página "Carro" no cartão inteligente (abas Viagem | Carro) e folha com tudo o que o scanner lê (Bluetooth ELM327, conexão sem parear, só leitura: nunca apaga falhas). Liga no scanner escolhido só com o Rumo na tela; procurar scanner só parado. Consumo estimado pelo MAF, com ajuste de calibração do dono. A velocidade do GPS continua a do cartão Viagem (a do carro só aparece na folha, até o Clayton comparar com o painel). **Troca de óleo** na folha Carro: tipo do óleo (mineral 5.000 km/6 meses, semissintético 7.500/6, sintético 10.000/12, ajustáveis), o que vier primeiro; na primeira vez pede data e km da última troca e o km do painel; os km andam pelo PID 31. Aviso (uma vez por dia, ao abrir o Rumo) e ponto na aba Carro quando vence ou falta pouco (500 km ou 15 dias).
- **Fonte e estação:** a fonte de áudio vem do serviço `sourceinfo` e a estação do `fmradio` (ver `docs/device-integration.md`); o tema automático segue o farol (`carstatus`).
- **O carro não tem controles de som no volante:** tudo é pela tela. Roteiros de teste não devem citar volante.

## Aparelho de referência

HCN, Android **9 (API 28)** embora exiba "15", 32 bits (`armeabi-v7a`), 8× Cortex-A53 1,5 GHz, Mali-T720 (GLES 3.1), 4 GB, tela 1280×720 a 160 dpi (1 dp = 1 px). Perfil completo no estudo; integração em `docs/device-integration.md`. Orçamento de desempenho conservador: sem desfoques, sombras grandes nem animações contínuas; mapa a 30 qps.

## Convenções

- **Idioma:** todo código novo (nomes de arquivos, pastas, classes, funções, variáveis, constantes, testes, chaves e arquivos novos, ids de workflows) é **em inglês**; comentários, KDoc, documentação, commits e textos da interface ficam em português do Brasil. Isso inclui o que o app grava (chaves de preferências, `Rumo/regions/…`, campos do `data.json`, tags das releases); só `Rumo` e `MapaRadar` (nomes próprios) ficam. Cuidado ao renomear algo já no campo: regras em `CONTRIBUTING.md`.
- Commits em português, mensagem descritiva, com `Co-Authored-By` quando feitos com o Claude.
- **Não há Android SDK nesta máquina:** compilar e testar é pelo CI (GitHub Actions). Para ver uma mudança no aparelho, faça push na `main` e use o APK do **nightly**; versões estáveis saem por tag `vX.Y.Z`.
- O dono autorizou **commitar e dar push na `main` sem pedir confirmação** e acompanhar o CI. Apagar releases, force push e ações fora deste repositório continuam pedindo confirmação.
- **Release notes (todo commit que muda o app, todo release):** a mudança que o usuário percebe e toda correção entram em `release-notes/next.md` **no mesmo commit**. As notas são lidas dentro do carro, então são **sucintas**: no máximo 6 itens de até 80 caracteres, um `- ` por linha, em português do Brasil, só o que o motorista percebe, **sem detalhe técnico** (nada de classe, serviço, arquivo ou commit). Várias correções viram uma linha ("Correções de erros"); várias melhorias de desempenho, outra ("Mais fluidez"). Mudança de código, documentação, site ou protótipo não entra. **Ao lançar uma estável:** revisar o `next.md`, renomear para `release-notes/X.Y.Z.md` (o número da tag) e criar um `next.md` novo só com `# Próxima versão`; o `release.yml` falha sem o arquivo da tag. O CI põe as linhas no `notes` do `rumo.json`, de onde o app tira o popup de atualização. Regras completas e exemplos em `release-notes/README.md`.
- Não commitar chaves nem segredos. A chave de assinatura vem de segredos do repositório (ver `android/README.md`).
- Documentação: guardar só o que ensina algo útil (como funciona, por quê). Sem diário de versões nem relato do que não funcionou. Ao descobrir algo novo sobre o aparelho, atualizar `docs/device-integration.md`.
- Testes no aparelho: o dono manda fotos e os relatórios `teste-*.txt` do pendrive (ver "Diagnósticos" em `docs/device-integration.md`).

## Pendências

A lista viva de tarefas (incluindo suporte a outros aparelhos) está em `TODO.md`.

- Validar o play do card de mídia no rádio como "parar": o mudo do `fmradio` sozinho não calou o rádio; o Rumo agora também solta a ligação com o serviço do rádio (que só existe enquanto alguém está ligado nele) e liga de novo ao tocar.
- **Rádio online em app à parte (15/nov/2026):** o firmware encerrava o Rumo a cada troca de fonte depois de ele tocar o online (com ou sem sessão de mídia, foco de áudio ou tipo do som). O player foi para `app.rumo.webradio`; a validar no carro: instalar pela aba Online, FM → Online → FM e Online → Android Auto sem o Rumo fechar (quem pode cair é o app do rádio online).
- **Rádio online a validar no carro:** se o som sai junto com o FM calado (sem os dois ao mesmo tempo), o que o `sourceinfo` informa com ele tocando (o Rumo mantém a fonte "Rádio online" com qualquer valor que não seja o ZLINK), o volume, a notificação de mídia no firmware e se o catálogo abre (TLS do aparelho).
- **Teste "avisos com o FM pelo Rumo"** (Configurações da home › Avisos de radar, desligado por padrão): com o FM, o aviso pelo app do online cala o FM (o aparelho o trata como app de música e troca a fonte); pelo Rumo (como na 0.2.0) o FM só abaixava. O teste diz se o aparelho abaixa o FM por o Rumo estar na tela ou por nunca ter tocado música (aí um terceiro app só de avisos resolveria). Risco conhecido: tocar o online logo depois de um aviso pelo Rumo pode fechá-lo (`sourceinfo` = `app.rumo`).
- **Página Carro a validar no carro:** conexão automática ao ligar, o que o scanner lê (rotação, temperatura, tanque, consumo agora e média), se a leitura atrapalha o Bluetooth do telefone e o desempenho com a folha aberta; troca de óleo: se o km anda junto com o painel (PID 31).
- Testar andando: rua e limite em cruzamentos e ruas paralelas, avisos de radar, desempenho do mapa e a velocidade do GPS contra o painel do carro.
- Completar os radares além do OSM (ANTT, dados abertos de prefeituras).
- Fonte Barlow ainda não adicionada; o cartão inteligente só tem a página Viagem; telefone/Bluetooth não integrados.
- Validar no aparelho as configurações da home (engrenagem › Configurações da home): TLS e relógio do aparelho nas conexões https, tela de "instalar apps desconhecidos" no firmware, instalação por cima e troca do mapa com ele aberto (a tela é recriada).
- **Reiniciar o Android** (Configurações da home › Sistema): `PowerManager.reboot` e `su` falharam no aparelho (31/out/2026: sem permissão `REBOOT`, sem root). As nightlies seguintes tentam o menu de energia por um serviço de acessibilidade (`device/PowerService.kt`: abre `GLOBAL_ACTION_POWER_DIALOG` e toca em "Reiniciar"; o Clayton liga o serviço "Rumo" em Acessibilidade) e tem o diagnóstico "Teste do reinício" (`device/RebootProbe.kt`, `reboot-test.txt`) que lista ganchos de reinício do fabricante. A validar no carro: se o firmware tem o menu de energia com "Reiniciar"; se não, olhar o relatório.
- **Estados (Sul e Sudeste)**: as configurações da home listam os estados do `data.json` (versão 3: `common` + `regions` com `group`), o usuário marca e aplica (baixa os marcados, remove os desmarcados). Dados em `Rumo/regions/<id>/{map.pmtiles,roads.db,contour.geojson}` e `Rumo/fonts`; `MapData` lê todos os estados (um `map.pmtiles` por fonte no estilo, vias de todos na busca da rua, radares sem repetir); a pasta com os nomes antigos (`regioes/`, `fontes/`) é apagada quando um estado novo está inteiro. A validar no carro.
- **Não se registrar no `mcu_service`** (`setcallback`): em 31/out/2026 os botões do aparelho (volume, home, power) pararam logo depois de o Teste do farol se registrar em 42 grupos da MCU e não voltaram com o restart do Android. Causa não confirmada (pode ser coincidência, estado guardado na MCU ou defeito físico). **Teste dos botões (31/out/2026):** nenhuma tecla (volume, início, voltar, power, mic) chega ao Android, nem o Início como tela inicial pedida, e o `getevent` não tem permissão em `/dev/input`; o toque na tela funciona. Ou seja, o problema está abaixo do Android (MCU ou botões), fora do alcance do app. O registro saiu do app (`McuTap.kt` está no histórico do git); não recolocar sem o Clayton pedir.
- Servidor de relatórios na rede, para dispensar o pendrive nos testes.
