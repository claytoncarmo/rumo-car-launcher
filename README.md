# Rumo · launcher para multimídia Android

**Site e protótipo para testar no navegador: [claytoncarmo.github.io/rumo-car-launcher](https://claytoncarmo.github.io/rumo-car-launcher/)**

Tela inicial própria para centrais multimídia Android aftermarket, feita para um Honda. A ideia central é a **camada zero**: rua atual, limite da via, radares, música e a próxima ação ficam na tela inicial, sem abrir nenhum app. A navegação fica no Android Auto; o launcher organiza o essencial e, enquanto está na tela, cuida da velocidade.

Foi desenvolvido e testado num aparelho HCN com Android 9 ([detalhes](docs/home-screen-study.md#aparelho-de-referência-medido)). O farol, a fonte de áudio e a estação do rádio vêm de serviços do fabricante ([como](docs/device-integration.md)); em outro aparelho o Rumo roda, com alternativas mais simples (tema pelo horário, fonte estimada pelo último app de áudio, rádio sem frequência).

## O que faz

- **Tela inicial** em Compose, de dia e de noite: dock (Início, Android Auto, Rádio, Música, Telefone, Apps, voz), relógio, tema automático pelo farol, escuro ou claro.
- **Mapa passivo offline** no card principal (MapLibre, dados do OpenStreetMap), com a rua atual, a placa de limite da via. Tocar abre o Android Auto. Acima do limite, a velocidade fica em vermelho no cartão Viagem.
- **Radares:** aviso curto ao entrar na zona e, acima do limite do radar, bipes que aceleram e sobem de tom ao se aproximar. Só com o Rumo na tela; com o Android Auto aberto, o Rumo não interfere.
- **Mídia:** o card sabe se o som é do Android Auto ou do rádio, mesmo trocado fora do Rumo, e controla pelos botões anterior, play e próxima.
- **Rádio próprio:** frequência, memória e texto da emissora; busca e ajuste fino; **favoritas** com nome e **procura de estações**. Abrir a tela já põe o rádio para tocar.
- **Viagem:** velocidade, rumo, distância, tempo em movimento e média.
- **Apps:** gaveta com todos os apps; toque longo abre detalhes e desinstalar.

## Instalar

1. Em [Releases](../../releases), baixe o `rumo.apk`: da **última versão estável** ou do **nightly** (pré-release compilada a cada mudança, pode ser instável).
2. Copie para o aparelho (por exemplo num pendrive), abra e instale; permita "instalar apps desconhecidos" se pedir.
3. Aperte Início e escolha o Rumo como tela inicial ("Sempre").
4. Para o mapa, os limites e os radares, toque na engrenagem (canto de cima, à direita) e em **Configurações da home** (com o carro parado) ou toque em "Sem dados do mapa" no card do mapa e baixe os dados pelo wi-fi. Também dá para copiá-los à mão: veja [`data/README.md`](data/README.md).

## Atualizar

As **configurações da home** (engrenagem › Configurações da home, só abaixo de 5 km/h) mostram a versão instalada e as últimas publicadas nos canais **estável** e **nightly**. **Instalar** baixa o APK (com retomada se a conexão cair), confere o hash e abre o instalador do Android. A mesma tela baixa e atualiza os dados do mapa. O Android não deixa instalar uma versão mais antiga que a instalada: de nightly só se volta para a estável quando ela for mais nova.

## Outros aparelhos

Só foi testado no aparelho HCN acima. O que usa só Android comum, GPS e dados do OpenStreetMap deve funcionar em qualquer Android 8 ou superior: tela inicial, dock, apps, configurações da home (atualização e dados), mapa offline, rua e limite, radares, tema por horário, Viagem, volume flutuante e reinício pelo menu de energia. Hoje o APK só traz código nativo de 32 bits ARM (`armeabi-v7a`).

O que depende do fabricante da unidade e **não** deve aparecer em outro aparelho sem investigação própria:

- **Farol** (tema escuro automático): serviço `carstatus`. Sem ele, o tema segue o horário.
- **Fonte do áudio:** serviço `sourceinfo`. Sem ele, estima pelo "Acesso ao uso".
- **Rádio** (estação, nomes, busca, mudo): serviço `fmradio` e app `com.hcn.autoradio`.
- **Android Auto:** o atalho abre o ZLINK (`com.zjinnova.zlink`).

Outros fabricantes usam outros serviços para as mesmas coisas. Os diagnósticos das configurações da home (farol, rádio, reinício, botões) gravam um relatório com o que existe no aparelho novo e encurtam essa investigação. As tarefas para dar suporte a mais aparelhos estão em [`TODO.md`](TODO.md).

## Compilar

Precisa de JDK 17 e do Android SDK (ou Android Studio): `cd android && ./gradlew :launcher:assembleDebug`. O CI compila, testa e publica sozinho; versões, assinatura e lançamentos estão em [`android/README.md`](android/README.md).

## Estrutura

| Caminho | O que é |
|---|---|
| [`android/`](android/README.md) | O app (`launcher/`) e o projeto Gradle |
| [`data/`](data/README.md) | Gerador dos dados offline (OpenStreetMap) |
| [`docs/home-screen-study.md`](docs/home-screen-study.md) | Estudo das telas iniciais das marcas, segurança, princípios, interface e viabilidade |
| [`docs/device-integration.md`](docs/device-integration.md) | Serviços do multimídia (farol, fonte, rádio) e como investigá-los |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Como contribuir: código em inglês, textos e documentação em português, nomes que não mudam |
| [`prototype/index.html`](prototype/index.html) | Protótipo interativo em HTML, em dia com o app (abra no navegador; os dados são de exemplo) |
| [`site/`](site/index.html) | Página inicial do GitHub Pages; o workflow `pages.yml` publica junto com o protótipo |

## Licença

O código do Rumo está sob a **GNU General Public License v3.0** (veja [`LICENSE`](LICENSE)): você pode usar, estudar, modificar e redistribuir, e qualquer versão derivada que for distribuída precisa continuar aberta sob a mesma licença.

Copyright © 2026 Clayton Carmo.

- **Dados do mapa:** o mapa e as vias vêm do [OpenStreetMap](https://www.openstreetmap.org/copyright) (© colaboradores do OpenStreetMap), sob a **ODbL**. Não ficam neste repositório: são gerados e distribuídos à parte (ver `data/`), com o crédito no app.
- **Interoperabilidade:** o Rumo conversa com serviços do fabricante do multimídia (HCN) — farol, fonte de áudio, rádio — por engenharia reversa de interoperabilidade, para funcionar no aparelho do próprio autor. Esses serviços e seus apps não fazem parte deste projeto e têm os direitos dos respectivos donos.
- Bibliotecas de terceiros mantêm as suas próprias licenças (ex.: MapLibre, Jetpack Compose).
