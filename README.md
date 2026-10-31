# Rumo · launcher para multimídia Android

Tela inicial própria para centrais multimídia Android aftermarket, feita para um Honda. A ideia central é a **camada zero**: rua atual, limite da via, radares, música e a próxima ação ficam na tela inicial, sem abrir nenhum app. A navegação fica no Android Auto; o launcher organiza o essencial e, enquanto está na tela, cuida da velocidade.

Foi desenvolvido e testado num aparelho HCN com Android 9 ([detalhes](docs/estudo-telas-iniciais.md#aparelho-de-referência-medido)). O farol, a fonte de áudio e a estação do rádio vêm de serviços do fabricante ([como](docs/integracao-aparelho.md)); em outro aparelho o Rumo roda, com alternativas mais simples (tema pelo horário, fonte estimada pelo último app de áudio, rádio sem frequência).

## O que faz

- **Tela inicial** em Compose, de dia e de noite: dock (Início, Android Auto, Rádio, Música, Telefone, Apps, voz), relógio, tema automático pelo farol, escuro ou claro.
- **Mapa passivo offline** no card principal (MapLibre, dados do OpenStreetMap), com a rua atual, a placa de limite da via e a velocidade em vermelho acima dele. Tocar abre o Android Auto.
- **Radares:** aviso curto ao entrar na zona e, acima do limite do radar, bipes que aceleram e sobem de tom ao se aproximar. Só com o Rumo na tela; com o Android Auto aberto, o Rumo não interfere.
- **Mídia:** o card sabe se o som é do Android Auto ou do rádio, mesmo trocado fora do Rumo, e controla pelos botões anterior, play e próxima.
- **Rádio próprio:** frequência, memória e texto da emissora; busca e ajuste fino; **favoritas** com nome e **procura de estações**. Abrir a tela já põe o rádio para tocar.
- **Viagem:** velocidade, rumo, distância, tempo em movimento e média.
- **Apps:** gaveta com todos os apps; toque longo abre detalhes e desinstalar.

## Instalar

1. Em [Releases](../../releases), baixe o `rumo.apk`: da **última versão estável** ou do **nightly** (pré-release compilada a cada mudança, pode ser instável).
2. Copie para o aparelho (por exemplo num pendrive), abra e instale; permita "instalar apps desconhecidos" se pedir.
3. Aperte Início e escolha o Rumo como tela inicial ("Sempre").
4. Para o mapa, os limites e os radares, copie os dados offline: veja [`dados/README.md`](dados/README.md).

## Compilar

Precisa de JDK 17 e do Android SDK (ou Android Studio): `cd android && ./gradlew :launcher:assembleDebug`. O CI compila, testa e publica sozinho; versões, assinatura e lançamentos estão em [`android/README.md`](android/README.md).

## Estrutura

| Caminho | O que é |
|---|---|
| [`android/`](android/README.md) | O app (`launcher/`) e o projeto Gradle |
| [`dados/`](dados/README.md) | Gerador dos dados offline (OpenStreetMap) |
| [`docs/estudo-telas-iniciais.md`](docs/estudo-telas-iniciais.md) | Estudo das telas iniciais das marcas, segurança, princípios, interface e viabilidade |
| [`docs/integracao-aparelho.md`](docs/integracao-aparelho.md) | Serviços do multimídia (farol, fonte, rádio) e como investigá-los |
| [`prototype/index.html`](prototype/index.html) | Protótipo interativo em HTML (abra no navegador; os dados são de exemplo) |

## Licença

O código do Rumo está sob a **GNU General Public License v3.0** (veja [`LICENSE`](LICENSE)): você pode usar, estudar, modificar e redistribuir, e qualquer versão derivada que for distribuída precisa continuar aberta sob a mesma licença.

Copyright © 2026 Clayton Carmo.

- **Dados do mapa:** o mapa e as vias vêm do [OpenStreetMap](https://www.openstreetmap.org/copyright) (© colaboradores do OpenStreetMap), sob a **ODbL**. Não ficam neste repositório: são gerados e distribuídos à parte (ver `dados/`), com o crédito no app.
- **Interoperabilidade:** o Rumo conversa com serviços do fabricante do multimídia (HCN) — farol, fonte de áudio, rádio — por engenharia reversa de interoperabilidade, para funcionar no aparelho do próprio autor. Esses serviços e seus apps não fazem parte deste projeto e têm os direitos dos respectivos donos.
- Bibliotecas de terceiros mantêm as suas próprias licenças (ex.: MapLibre, Jetpack Compose).
