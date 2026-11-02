# Estudo: telas iniciais de multimídia automotiva

**Projeto:** Rumo, um launcher para multimídia Android aftermarket (instalação num Honda)
**Data:** outubro de 2026
**Protótipo:** [`prototype/index.html`](../prototype/index.html), publicado em [claytoncarmo.github.io/rumo-car-launcher/prototipo](https://claytoncarmo.github.io/rumo-car-launcher/prototipo/)
**Nota (nov/2026):** a seção 6 descreve o desenho proposto no estudo (com clima, chamadas, mensagens, cartão inteligente e personalização). O protótipo foi atualizado depois para mostrar o que o app faz hoje (rádio com favoritas, pontos marcados, volume flutuante, Viagem e configurações da home) e deixou de simular esses extras; o que o app tem está no [README](../README.md).
**Integração com o aparelho de referência:** [`device-integration.md`](device-integration.md)

---

## 1. Contexto e objetivo

O carro recebeu uma central multimídia Android de mercado (não original). Nenhum launcher disponível agradou: os de fábrica desses aparelhos e os da Play Store tendem a ser poluídos, com texto pequeno e pouca integração com os apps que realmente usamos dirigindo (Waze/Maps, Spotify, telefone).

Objetivo deste estudo:

1. Levantar como as principais marcas resolvem a **tela inicial** do multimídia e quais recursos oferecem.
2. Separar o que funciona do que atrapalha, à luz das diretrizes de segurança.
3. Definir princípios e uma proposta de interface, prototipada em HTML, para depois ser implementada em Android.

### Restrições do hardware alvo

| Item | Situação típica em multimídias Android aftermarket |
|---|---|
| Resolução | 1024×600 (9"/10" básicos), 1280×720 (HD) e 2000×1200 (QLED "2K") |
| Android | 10 a 13 na maioria dos aparelhos atuais; alguns ainda 8.1 |
| Volante e botões | Tratados pela MCU do aparelho, não pelo launcher |
| Rádio, Bluetooth, câmera de ré | Apps proprietários do fabricante do aparelho (FYT, TEYES etc.) |
| Dados do carro (CAN) | Via decodificador CAN e app do fabricante; sem API padrão |
| Ar-condicionado | Continua nos comandos físicos do Honda; o launcher não controla |

### Aparelho de referência (medido)

O aparelho em que o Rumo é desenvolvido e testado. A tela "Informações" do fabricante mostra alguns valores que o sistema contradiz.

| Item | Tela "Informações" | Medido |
|---|---|---|
| Android | 15 | **Android 9 (API 28)**, patch de segurança 2021‑09; o "15" é só o nome exibido |
| Processador | SD‑MT8163 | MediaTek (placa `SD-MT8163p3_bsp`), **8× Cortex‑A53 a 1,5 GHz** |
| GPU | — | **Mali‑T720**, OpenGL ES 3.1 |
| Sistema | — | **32 bits** (`armeabi-v7a`) |
| Memória | 4 GB | 4,0 GB informados pelo sistema (1,0 GB livre; limite de 128 MB por app) |
| Armazenamento | 64 GB | 53,6 GB (50,3 GB livres) |
| Tela | 1280×720, 10" IPS | 1280×720 px, **160 dpi (1 dp = 1 px)**, 56,7 Hz; com a barra do sistema sobram 1280×660 |
| Toque | Goodix GT9xx 1080×600 | Preciso, inclusive nos cantos |
| Fabricante do software | — | **HCN** (apps `com.hcn.*`); firmware genérico |
| Tela inicial | — | Vêm instaladas Launcher3, AGAMA, Car Launcher e uma sem nome (provavelmente a de fábrica); nenhuma é padrão até o usuário escolher |
| Android Auto | — | **ZLINK5** (`com.zjinnova.zlink`) |
| Som com música tocando | — | A música **fica muda** durante qualquer som do app, **inclusive sem pedir foco de áudio**: é regra do próprio aparelho, não do app |
| Música pelo ZLINK | — | Sessão de mídia **vazia** (sem estado nem título), notificação **sem texto** e comandos da sessão ignorados. **Teclas de mídia funcionam** (anterior, play/pausa, próxima) |
| Rádio do aparelho | — | Sem sessão de mídia e sem texto na notificação; o app de rádio não recebe a tecla play/pausa |

O estado do carro (farol), a fonte de áudio e a estação do rádio vêm de serviços do fabricante, descritos em [`device-integration.md`](device-integration.md).

**Consequências para o projeto:**
- Alvo real é **Android 9**: `minSdk 26`/`28` serve; não há `RoleManager` (a escolha da tela inicial é pelo seletor do sistema, "Sempre").
- **32 bits** e 8 núcleos A53 modestos: Compose funciona, mas com orçamento de desempenho conservador (sem desfoques, sombras grandes ou animações contínuas; mapa simples, 30 qps). MapLibre tem versão `armeabi-v7a` e roda em OpenGL ES 3 (linha 11; a 13 passou a usar Vulkan por padrão). Dados offline cabem com folga: 50 GB livres.
- **1 dp = 1 px** e 1280×720 dp: o layout do protótipo (altura lógica de 720) vale sem conversão.
- Barra do sistema: o modo imersivo funciona; na tela inicial o Rumo usa tela cheia com o próprio relógio.

Essas restrições pesam no desenho: **o launcher não substitui os apps**, ele organiza e mostra o essencial deles na primeira tela.

---

## 2. Sistemas analisados

Para cada sistema: como é a tela inicial, recursos de destaque, pontos fortes e fracos, e o que levamos para o Rumo.

### 2.1 Tesla (Model 3/Y, software 2024–2026)

- **Tela inicial:** o mapa ocupa a maior parte da tela; a visualização do carro fica do lado do motorista; uma barra inferior fixa reúne clima, volume e um **dock de apps personalizável** (arrastar e soltar). O player aparece como cartão sobre o mapa.
- **Novidades 2026:** ícones do dock cerca de 21% maiores (de 40 para 44 px) e um atalho "mais apps" dinâmico que mostra quatro apps recentes ou populares que não estão no dock.
- **Funciona:** mapa sempre visível; dock estável e personalizável; visual consistente.
- **Atrapalha:** funções básicas (limpadores, retrovisores, porta-luvas) dentro de menus; dependência quase total da tela; ícones historicamente pequenos.
- **Para o Rumo:** mapa como protagonista, dock fixo, atalhos "recentes" que se adaptam ao uso.

### 2.2 Mercedes‑Benz MBUX / MB.OS ("Zero Layer")

- **Tela inicial:** a **Zero Layer** coloca informações e sugestões na primeira camada, sem entrar em menus, com o mapa ao fundo. Os cartões de sugestão (chamados internamente de *Magic Modules*) aparecem por contexto: ligar para alguém num horário habitual, acionar a casa inteligente, etc.
- **Novidades:** no novo GLC (MB.OS, 4ª geração do MBUX), a Zero Layer passa a mostrar também apps usados recentemente e o mapa pode ser trocado por uma imagem de fundo. O Hyperscreen do GLC tem 39,1". O assistente virtual aparece como um avatar.
- **Funciona:** reduz toques; o sistema "adivinha" a próxima ação.
- **Atrapalha:** muita animação e densidade visual; sugestões erradas viram ruído.
- **Para o Rumo:** o conceito de **camada zero** e um cartão de sugestões, com no máximo três itens visíveis.

### 2.3 BMW iDrive 8.5/9 e Panoramic iDrive (Neue Klasse, iX3)

- **Tela inicial:** no iDrive 8.5 ("QuickSelect"), o mapa fica ao fundo com uma coluna de atalhos à esquerda. No **Panoramic iDrive**, uma faixa na base do para‑brisa (Panoramic Vision) recebe **até seis widgets** arrastados a partir da tela central; na tela central o mapa permanece como fundo e uma barra inferior traz Home, música, navegação, telefone, todos os apps e ajustes do carro.
- **Funciona:** personalização com **limite de espaços**; mapa constante; barra de atalhos fixa.
- **Atrapalha:** o fim do controlador rotativo iDrive tirou um comando físico muito elogiado; há curva de aprendizado.
- **Para o Rumo:** personalizar dentro de limites (layouts prontos, número fixo de cartões) e manter atalhos sempre no mesmo lugar.

### 2.4 Apple CarPlay (iOS 26) e CarPlay Ultra

- **Tela inicial:** o **Dashboard** combina mapa, "Tocando agora" e sugestões da Siri; o **dock fica no lado do motorista** e mostra apps recentes.
- **iOS 26:** tela de **widgets** (até três, conforme a tela), **Live Activities** no Dashboard, **chamada recebida em formato compacto** (não cobre a navegação), Tapbacks no Mensagens e o visual Liquid Glass.
- **CarPlay Ultra** (lançado em 2025 com a Aston Martin): ocupa todas as telas do carro, inclusive instrumentos e clima, com temas da marca.
- **Funciona:** consistência; poucas opções por tela; interrupções discretas.
- **Para o Rumo:** **chamada e mensagem como faixa compacta**, dock do lado do motorista, poucos widgets.

### 2.5 Android Auto e Android Automotive (Google built‑in)

- **Android Auto "Coolwalk" (2022):** tela dividida, com mapa grande e cartões de mídia e sugestões, mais uma barra lateral com atalhos; adapta‑se a telas largas e verticais.
- **Redesign 2026** (anunciado em maio de 2026): Material 3 Expressive, papéis de parede, **widgets na tela inicial** (clima, casa inteligente, contatos favoritos), Gemini no carro, vídeo HD com o carro parado e Google Maps de borda a borda.
- **Android Automotive / Google built‑in** (Volvo, Polestar, GM, Renault, **Honda Accord 2023 Touring**): no Accord, a tela de 12,3" tem uma coluna de botões virtuais à esquerda (Home, Voltar, Assistente, Maps, FM, espelhamento) e um launcher com oito ícones por vez, com widgets como relógio e áudio.
- **Diretrizes de design (Android for Cars):** alvo de toque mínimo de **76×76 dp**, com **23–24 dp** entre alvos; texto primário de **32 dp** e secundário de **24 dp**; contraste mínimo de **4,5:1**.
- **Para o Rumo:** cartões com estados claros, barra lateral, e as **regras de distração** (listas curtas, teclado bloqueado em movimento).

### 2.6 Hyundai / Kia (ccNC e Pleos Connect)

- **ccNC:** tela inicial em widgets personalizáveis, na tela panorâmica dupla.
- **Pleos Connect** (apresentado em abril de 2026, baseado em Android Automotive, estreia no Grandeur coreano com tela de 17"): a tela é dividida em **informações de condução**, **área de apps** e uma **barra inferior com apps recentes ou fixados**; widgets são reposicionados com toque de três dedos; loja própria (Pleos Playground). A Hyundai afirma que manterá comandos físicos.
- **Para o Rumo:** barra de recentes/fixados e a decisão explícita de **não depender só do toque**.

### 2.7 Volkswagen (MIB/ID e ID. Polo 2026)

- **Tela inicial:** widgets grandes e personalizáveis mais atalhos para funções e submenus.
- **Lição pública:** os controles deslizantes de toque (volume e temperatura, sem iluminação) dos ID.3/ID.4 foram muito criticados. No **ID. Polo** (2026) a VW trouxe de volta uma fileira de **botões físicos** para clima e pisca‑alerta e um **botão giratório** de volume que também troca faixa/estação. Há ainda um modo retrô "Golf I", com o player em forma de fita cassete.
- **Para o Rumo:** o launcher não deve tentar substituir comandos que o Honda já tem fisicamente (volume, clima, volante).

### 2.8 Toyota (Toyota Audio Multimedia)

- **Até 2025:** não há uma "home" tradicional; uma **barra vertical à esquerda** concentra navegação, áudio e espelhamento. Avaliações (ex.: Cars.com, 4Runner 2025) sentem falta de uma tela inicial.
- **2026 (RAV4):** passa a ter **home com widgets personalizáveis** (navegação, modo de condução, áudio, dispositivos, clima).
- **Para o Rumo:** barra vertical do lado do motorista funciona bem; uma home com resumo faz falta quando não existe.

### 2.9 Honda

- **Honda Display Audio / Honda Connect** (Civic, City, HR‑V, WR‑V no Brasil): grade de ícones tradicional, espelhamento CarPlay/Android Auto; o Civic de 11ª geração voltou a ter **botão físico de volume**.
- **Accord 2023 Touring:** Google built‑in em 12,3" (ver 2.5).
- **Série 0 (2026):** sistema operacional próprio **ASIMO OS**, com atualização remota; detalhes de interface ainda não divulgados.
- **Para o Rumo:** respeitar a ergonomia Honda (comandos no volante e volume físico) e o motorista à **esquerda** (Brasil).

### 2.10 Launchers aftermarket (o que já existe para esses aparelhos)

| Launcher | Pontos citados |
|---|---|
| Launcher de fábrica (FYT/TS10, TEYES etc.) | Muitos ícones e relógios decorativos, hierarquia fraca, visual skeuomórfico |
| AGAMA | O mais recomendado; widgets personalizáveis; os melhores widgets ficam na versão paga; o Waze não aparece dentro do widget de navegação |
| CarWebGuru | Gratuito com anúncios; muito configurável; visual datado |
| Car Launcher Pro, AutoZen | Alternativas mais simples |

**Problemas recorrentes:** texto e alvos de toque pequenos; enfeites que competem com a informação (velocímetros falsos, fundos animados); personalização livre que gera layouts ruins; integração fraca com o app de navegação; nenhum "modo em movimento".

---

## 3. Matriz comparativa

Avaliação qualitativa da **tela inicial** de cada sistema. ● presente e bem resolvido · ◐ parcial · ○ ausente.

| Recurso | Tesla | MBUX | BMW | CarPlay | Android Auto 2026 | AAOS (Accord) | Pleos | Toyota 2026 | Launchers aftermarket | **Rumo** |
|---|---|---|---|---|---|---|---|---|---|---|
| Mapa/navegação na home | ● | ● | ● | ● | ● | ◐ | ● | ◐ | ◐ | ◐ mapa passivo; navegação no Android Auto |
| Mídia na home | ● | ◐ | ◐ | ● | ● | ● | ◐ | ● | ● | ● |
| Sugestões por contexto | ◐ | ● | ◐ | ● | ● | ◐ | ◐ | ○ | ○ | ● |
| Widgets configuráveis (com limite) | ○ | ◐ | ● | ● | ● | ◐ | ● | ● | ◐ (livre demais) | ● |
| Atalhos fixos no lado do motorista | ● | ◐ | ● | ● | ● | ● | ◐ | ● | ◐ | ● |
| Chamada compacta (não cobre o mapa) | ◐ | ◐ | ◐ | ● | ◐ | ◐ | ◐ | ◐ | ○ | ● |
| Bloqueio de teclado/vídeo em movimento | ● | ● | ● | ● | ● | ● | ● | ● | ○ | ● |
| Tema dia/noite automático | ● | ● | ● | ● | ● | ● | ● | ● | ◐ | ● |
| Convive com comandos físicos | ○ | ◐ | ◐ | ● | ● | ● | ● | ● | ● | ● |

---

## 4. Segurança e ergonomia

- **NHTSA, diretrizes de distração visual‑manual (Fase 1, 2013, voluntárias):** olhares de até **2 s** e no máximo **12 s** de olhos fora da estrada por tarefa; recomenda bloquear digitação manual de texto, navegação web e vídeo com o carro em movimento. A Fase 2 proposta trata de dispositivos portáteis e **aftermarket**, o nosso caso.
- **Android for Cars:** alvos de **76×76 dp**, **23–24 dp** entre alvos, texto **32/24 dp**, contraste **≥ 4,5:1**.

**Como isso vira regra no Rumo:**

| Regra | Aplicação |
|---|---|
| Tudo crítico a 1 toque | Abrir o Android Auto, tocar/pausar, retornar ligação, atender: direto na home |
| Alvos ≥ 76 px na grade de 720 px | Botões de mídia, itens do cartão, dock, placa de limite e alertas (76–96 px) |
| Texto primário 20–44 px, números grandes | Velocidade acima do limite em 44 px, distância do radar em 36 px, velocidade da Viagem em 96 px, relógio em 36 px |
| Bloqueio em movimento (configurável) | Teclado, busca, vídeo, navegador, loja e listas longas (máx. 3–4 itens) |
| Mensagem não é lida na tela em movimento | Só remetente; ação "Ouvir" (TTS) e "Responder" por voz |
| Interrupções compactas | Chamada e mensagem em faixa no topo; o mapa continua visível |
| Alerta sonoro sem olhar | Radar à frente: um aviso curto. Acima do limite do radar: bipes repetidos até reduzir |

---

## 5. Princípios de design do Rumo

1. **Camada zero.** Rua atual, limite da via, radares, mídia e a próxima ação provável ficam na tela inicial. Abrir um app é exceção.
2. **Dock do lado do motorista.** Fixo, cinco destinos (Início, Android Auto, Música, Telefone, Apps) e o botão de voz sempre no mesmo lugar, embaixo, onde a mão alcança sem olhar.
3. **Cartão inteligente.** Uma pilha com páginas (Sugestões, Viagem, Clima, Favoritos); desliza para trocar e muda sozinha quando o contexto pede (ex.: ao começar a andar sem rota, mostra a Viagem).
4. **Personalização com limites.** Três layouts testados, cor de destaque, lado do dock, apps padrão e páginas do cartão. Não há grade livre de widgets, que é o que estraga os launchers atuais.
5. **Legível em um olhar.** Tipografia Barlow (inspirada na sinalização viária) e Barlow Semi Condensed para números; alto contraste; ícones de traço uniforme.
6. **Noite de verdade.** Tema escuro automático; âmbar como cor padrão porque luz quente ofusca menos à noite; **Painel Noturno** inspirado no *Night Panel* da Saab: só velocidade, limite da via, radar e música, em âmbar sobre preto.
7. **Não brigar com o carro.** Volume, clima e volante continuam físicos; o launcher não duplica isso na home.
8. **Orquestrar, não substituir.** Android Auto, Spotify e o telefone continuam sendo os apps; o Rumo mostra o essencial deles e abre o app quando necessário.
9. **Navegação no Android Auto, velocidade no Rumo.** A navegação já funciona bem no Android Auto, então o mapa da tela inicial é passivo. Com o Rumo na tela, ele cuida de limite da via, velocidade acima dele e radares; com o Android Auto aberto, o Rumo não interfere.

---

## 6. Interface proposta (protótipo)

### 6.1 Estrutura da tela

```
+--------+----------------------------------------+------------------------------+
|        | 14:32  qui., 29 de out.  27°   [pílula ao vivo]   4G BT Wi-Fi [!] [=] |  barra de status
| Início +----------------------------------------+------------------------------+
|        | SENTIDO NORTE        [Android Auto ->] | [capa]  SPOTIFY              |
|Android | Av. Getúlio Vargas                     |         Oceano · Djavan      |  card de mídia
|  Auto  |                                        | ========------------         |
| Música |                                        |   |<     ( || )     >|       |
|        |        MAPA PASSIVO (segue o carro)    +------------------------------+
|Telefone|                [radar]                 | SUGESTÕES          o . . .   |
|        |                   ^                    | [tel]  Retornar: Ana Paula   |  cartão inteligente
|  Apps  |                                        | [aa]   Abrir Android Auto    |
|        |  (60)  (72)  [radar 350 m · 60 km/h]   | [agd]  Revisão do carro      |
| (voz)  |  limite  sua velocidade   alerta       |                              |
+--------+----------------------------------------+------------------------------+
   dock               card do mapa                       coluna direita
```

- A tela é desenhada numa **altura lógica de 720 px**; a largura acompanha a proporção do aparelho (1024×600, 1280×720 e 2000×1200 cabem sem reorganizar). Os cards usam *container queries* e se adaptam ao espaço.
- **Layouts:** *Mapa em foco* (padrão), *Equilibrado* (três colunas) e *Mídia em foco* (capa grande, mapa menor).

### 6.2 Componentes

| Componente | O que mostra | Interações |
|---|---|---|
| Barra de status | Hora, data, clima, sinal, Bluetooth, Wi‑Fi, notificações, ajustes | Pílula ao vivo: música ou chamada quando uma tela cobre a home |
| Dock | Início, Android Auto, Música, Telefone, Apps, Voz | Indica a tela aberta; telefone fica verde durante a chamada |
| Card do mapa | Mapa passivo que segue o carro; rua atual e sentido; placa de limite da via; sua velocidade em vermelho quando acima do limite; radar à frente com distância | Toque em qualquer ponto abre o Android Auto |
| Card de mídia | Capa, fonte, faixa, progresso, controles | Toque abre o player completo (Spotify/YouTube Music/Deezer, Rádio FM com memórias, Pendrive, Bluetooth) |
| Cartão inteligente | Sugestões, Viagem (velocidade, bússola, distância, tempo, média), Clima, Favoritos | Deslizar ou tocar nos pontos troca a página |
| Ajustes rápidos | Brilho, volume, Wi‑Fi, Bluetooth, tema, Painel Noturno, som do alerta de radar, Android Auto, Não perturbe, desligar tela, notificações | Abre pelo sino ou pelo ícone de ajustes |
| Personalizar | Layout, cor, tema, lado do dock, som do radar, distância do aviso, tolerância do vermelho, app de música, páginas do cartão, bloqueio em movimento | Toque longo em qualquer card, ou pelos ajustes rápidos |

### 6.3 Estados e fluxos simulados no protótipo

- **Limite e radar:** a placa muda (com um leve destaque) ao trocar de via; acima do limite aparece a velocidade em vermelho; perto de um radar aparece o alerta com a distância (ver 6.4).
- **Android Auto:** tocar no mapa abre uma tela simulada do Android Auto; o Rumo fica em silêncio até você voltar.
- **Em movimento:** cartão vai para "Viagem" (velocidade, placa do limite, bússola); teclado, busca e apps de vídeo/navegador/loja ficam bloqueados; listas encurtam.
- **Chamada:** faixa compacta com Recusar/Atender; a música pausa e volta depois; durante a ligação, pílula verde na barra de status.
- **Mensagem:** faixa com Ouvir (leitura em voz alta em pt‑BR) e Responder por voz; em movimento, o texto não aparece.
- **Voz:** painel com exemplos de comandos executáveis (limite da via, tocar artista, ligar, previsão do tempo, rádio).
- **Painel Noturno e tela desligada.**

### 6.4 Limite de velocidade e radares

| Situação | Na tela | Som |
|---|---|---|
| Dentro do limite | Placa R‑19 com o limite da via (canto inferior do mapa) | Nenhum |
| Acima do limite (+ tolerância escolhida: 0, 5 ou 7 km/h) | Sua velocidade em vermelho ao lado da placa, pulsando | Nenhum |
| Radar ou lombada eletrônica a menos de 300/500/800 m | Alerta amarelo com distância, tipo e limite do radar; ícone no mapa | Um aviso curto (dois tons); opção de voz: "Radar fixo a 500 metros. Limite 60." |
| Acima do limite do radar dentro da zona de aviso | Alerta fica vermelho: "Reduza · radar 60 km/h" | Bipes que aceleram e ficam mais agudos conforme o radar se aproxima, como um sensor de estacionamento: de ~1 por segundo (1,3 kHz) na borda da zona até ~8 por segundo (2 kHz) em cima do radar. Param ao ficar no limite ou passar o radar |
| Android Auto aberto | Nada: o Rumo não desenha por cima do Android Auto | Nenhum: os alertas só funcionam com o Rumo na tela; com o Android Auto aberto, quem avisa é o Waze/Maps |

Detalhes:
- O vermelho usa 1 km/h de histerese para não piscar exatamente no limite.
- Os bipes do radar usam o limite exato do radar, sem tolerância.
- O protótipo simula as vias com os padrões do CTB para vias urbanas (arterial 60, coletora 40, local 30) e cinco equipamentos no percurso. O botão **Acima do limite** força a simulação a passar do limite para mostrar os alertas repetidos.

---

## 7. Viabilidade no Android

| Recurso | Como implementar | Risco |
|---|---|---|
| Ser a tela inicial | `Activity` com `intent-filter` `MAIN` + `HOME` + `DEFAULT`; o usuário escolhe o Rumo como padrão | Baixo; alguns aparelhos reativam o launcher de fábrica e exigem ajuste no menu do fabricante |
| Mídia (fonte, estação e controles) | Apps com `MediaSession` (Spotify, YouTube Music, Deezer): `NotificationListenerService` + `MediaSessionManager.getActiveSessions()` + `MediaController`. No aparelho de referência o Android Auto e o rádio não publicam sessão: a fonte vem do serviço `sourceinfo`, a estação do `fmradio` e os controles por teclas de mídia (`AudioManager.dispatchMediaKeyEvent`); ver `device-integration.md` | Depende do fabricante do aparelho |
| Abrir o Android Auto | Abrir o app que faz o Android Auto no aparelho (o nome varia: app próprio do fabricante, ZLink, AutoKit…); pacote escolhido nas configurações | Baixo |
| Mapa passivo na home | MapLibre com mapas OSM, posição e rumo pelo GPS do aparelho; sem rota | Baixo |
| Rua atual e limite da via | Associar a posição GPS à via mais próxima (*map matching*) numa extração offline do OpenStreetMap; usar a etiqueta `maxspeed`. Sem `maxspeed`, estimar pelos padrões do CTB (urbanas: trânsito rápido 80, arterial 60, coletora 40, local 30; rodovias: pista dupla 110, pista simples 100) e mostrar como estimado | **Médio**: a cobertura de `maxspeed` no Brasil é desigual |
| Base de radares | Pontos `highway=speed_camera` do OpenStreetMap, bases comunitárias brasileiras (verificar licença) e importação de arquivo pelo usuário; atualização periódica | **Alto**: a qualidade da base define a utilidade do recurso |
| Alertas sonoros | Só com o Rumo em primeiro plano, sem serviço em segundo plano; tons sintetizados (`AudioTrack`) no canal de orientação de navegação (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`); voz com `TextToSpeech`. No aparelho de referência qualquer som silencia a música enquanto toca | Baixo |
| Velocidade, rumo, viagem | `Location.getSpeed()` e `getBearing()` | Baixo |
| Clima | API Open‑Meteo (gratuita, sem chave) | Baixo |
| Tema automático | Farol aceso, lido do serviço `carstatus` do fabricante (o mesmo sinal que escurece o Android Auto); sem ele, pelo horário (escuro das 18h às 6h) ou pelo modo noturno do sistema | Depende do fabricante |
| Telefone | O Bluetooth desses aparelhos é um app próprio; integrar por intents/broadcasts do fabricante e, no mínimo, abrir o app de telefone | **Alto**: depende da marca do aparelho |
| Notificações e resposta por voz | `NotificationListenerService`, `TextToSpeech` e `RemoteInput` das ações de resposta (WhatsApp expõe) | Médio |
| Assistente | Intent `ACTION_VOICE_COMMAND` (Google) e/ou `SpeechRecognizer` para comandos próprios | Médio |
| Lista de apps | `PackageManager` (`MAIN`/`LAUNCHER`); marcar quais exigem carro parado | Baixo |
| Detecção de movimento | Velocidade do GPS acima de ~5 km/h (e freio de mão, se o aparelho expuser) | Baixo |
| Brilho e volume | `Settings.System` (permissão `WRITE_SETTINGS`) e `AudioManager` | Baixo |
| Dados do carro (CAN) e OBD2 | CAN só via app do fabricante; OBD2 opcional com adaptador ELM327 Bluetooth | Fora do escopo inicial |

**Pilha adotada:** Kotlin + Jetpack Compose (só `foundation`, sem Material), `minSdk 26` (Android 8), MapLibre para o mapa offline, layout em dp com a mesma grade do protótipo.

**Dá para usar os dados do Google Maps ou do Waze?** Não, na prática:

| Fonte | Limite de velocidade | Radares | Situação |
|---|---|---|---|
| Google Maps Platform (Roads API) | Sim, endpoint Speed Limits | Não há API | Acesso restrito: exige licença Asset Tracking, voltada a frotas grandes; Google não garante a precisão |
| Waze | Não há API pública | Não há API pública | Limites e radares são da comunidade e ficam dentro do app. O programa Waze for Cities entrega um feed de alertas e congestionamentos só a parceiros (órgãos públicos) |
| Waze/Maps dentro do Android Auto | — | — | A tela é projetada pelo celular; o aparelho não tem acesso aos dados |
| HERE (Map Attributes API, Safety Cameras API) | Sim | Sim (fixos e móveis, com limite) | Comercial, com plano gratuito para testes; o mais completo para os dois casos |
| TomTom (Snap to Roads API) | Sim | Produto separado, comercial | Plano freemium para limites |
| OpenStreetMap | Sim (`maxspeed`), cobertura desigual | Sim (`highway=speed_camera`), cobertura baixa | Gratuito e pode ser usado offline |

**Proposta:** OpenStreetMap offline como base (grátis, sem internet), com estimativa pelo CTB onde faltar limite; avaliar HERE para radares e para completar os limites, guardando em cache para gastar poucas consultas.

**Observações sobre radares:**
- Os avisos sonoros do Rumo tocam só com o launcher na tela. Com o Android Auto aberto, os avisos ficam com o Waze/Google Maps, sem duplicar.
- O CTB proíbe conduzir com dispositivo antirradar (art. 230, III). O aviso do Rumo não detecta sinal nenhum: compara a posição do GPS com uma lista de locais, como fazem os apps de navegação.

---

## 8. Fontes

- Android for Cars, princípios visuais: https://developers.google.com/cars/design/design-foundations/visual-principles
- Android for Cars, princípios de design: https://docs.partner.android.com/drivingux/foundations/design-principles
- AAOS, diretrizes para telas personalizadas de mídia: https://developers.google.com/cars/design/automotive-os/apps/media/guidelines-custom-screens
- NHTSA, diretrizes de distração (resumo): https://www.kbb.com/car-news/nhtsa-issues-new-distracted-driving-guidelines
- NHTSA Fase 2 (portáteis e aftermarket): https://swov.nl/en/publicatie/visual-manual-nhtsa-driver-distraction-guidelines-portable-and-aftermarket-devices
- MBUX Zero Layer: https://www.mercedes-benz.co.za/passengercars/technology/mbux-zero-layer.html
- GLC com MB.OS: https://www.automotiveworld.com/news/mercedes-benz-inside-the-all-new-electric-glc-an-exceptional-experience-powered-by-mb-os-that-feels-just-like-home/
- BMW Panoramic iDrive: https://www.bmwblog.com/2025/01/07/bmw-idrive-x-reveal-details/ e https://www.autoexpress.co.uk/bmw/365522/new-bmw-panoramic-idrive-revealed-latest-iteration-tech-changed-motoring
- CarPlay no iOS 26: https://www.macrumors.com/2025/07/11/ios-26-adds-these-10-features-to-carplay e https://techcrunch.com/2025/06/09/apple-adds-widgets-and-message-tapbacks-to-carplay-with-ios-26
- Android Auto 2026: https://androidauthority.com/android-auto-google-io-2026-features-material-3-widgets-video-apps-3665474 e https://engadget.com/2170136/android-auto-is-getting-a-big-makeover-for-2026
- Honda Accord 2023 com Google built‑in: https://9to5google.com/2022/12/15/android-automotive-honda-accord/
- Honda ASIMO OS: https://www.automotiveworld.com/news/honda-0-saloon-and-honda-0-suv-prototypes-make-global-debut-at-ces-honda-announces-new-asimo-operating-system/
- Hyundai Pleos Connect: https://insideevs.com/news/794540/hyundai-kia-genesis-pleos-connect-infotainment/ e https://www.completecar.ie/car-news/article/14856/Hyundais-new-Pleos-software-steps-up
- Tesla, atualização Spring 2026: https://www.notateslaapp.com/news/4076/a-closer-look-at-teslas-larger-spring-update-icons-and-how-much-bigger-they-are
- VW ID. Polo e botões físicos: https://www.engadget.com/transportation/evs/volkswagen-is-bringing-physical-buttons-back-to-the-dashboard-with-the-id-polo-ev-190246116.html e https://jalopnik.com/2069259/volkswagen-id-polo-interior-design/
- Toyota Audio Multimedia: https://cars.com/articles/what-do-we-think-of-our-2025-toyota-4runners-multimedia-system-519454/ e https://pressroom.toyota.com/?p=109970
- CTB, Lei 9.503/1997 (limites do art. 61 e art. 230, III): https://www.planalto.gov.br/ccivil_03/leis/l9503compilado.htm
- Google Roads API, Speed Limits (licença Asset Tracking): https://developers.google.com/maps/documentation/roads/speed-limits e https://developers.google.com/maps/solutions/asset-tracking/asset-tracking-plan
- Waze, radares e dados para parceiros: https://support.google.com/waze/answer/7052887 e https://doc.arcgis.com/en/velocity/ingest/waze.htm
- HERE Map Attributes e Safety Cameras: https://docs.here.com/map-attributes/docs/concepts e https://docs.here.com/safety-cameras/docs/api-reference
- TomTom Snap to Roads: https://developer.tomtom.com/snap-to-roads-api/documentation/product-information/introduction
- OpenStreetMap, etiqueta `maxspeed`: https://wiki.openstreetmap.org/wiki/Key:maxspeed
- OpenStreetMap, radares (`highway=speed_camera`): https://wiki.openstreetmap.org/wiki/Tag:highway%3Dspeed_camera
- Launchers para multimídia Android: https://android-headunits.com/android-head-unit-launcher/ e https://www.team-bhp.com/forum/car-entertainment/210960-android-head-unit-buying-guide-post5551374.html

Observação: parte das fontes é cobertura de lançamento e sites de fãs; detalhes de recursos recém‑anunciados (Pleos, Android Auto 2026, Panoramic iDrive) podem mudar até chegarem aos carros.
