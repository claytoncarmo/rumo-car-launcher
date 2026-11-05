# App Android (`launcher/`)

Kotlin + Jetpack Compose (só `foundation`, sem Material), `minSdk 26`, `targetSdk 34`, pacote `app.rumo`, só `armeabi-v7a` (o aparelho de referência é 32 bits; o APK com o MapLibre fica bem menor). Mapa com **MapLibre 11.13.5**: a linha 11 usa OpenGL ES 3 (o Mali-T720 tem 3.1) e a 13 passou a usar Vulkan por padrão.

## Código

`launcher/src/main/java/app/rumo/`:

| Pasta | O que tem |
|---|---|
| `core/` | Lógica pura, com testes em `src/test`: `Geo` (distâncias, grade de células), `Roads` (rua atual por *map matching*, limite, velocidade acima), `Radars` (zona de aviso e tipos de ponto), `MapaRadar` (CSV do MapaRadar processado no aparelho: recorte, sentido, junção e cache), `UserPoints` (pontos que o usuário marca no aparelho: formato do arquivo, trocar/apagar, lugar do "+"), `TripMeter`, `Formatting`, `Favorites` (lista própria e ajuste fino do FM), `RadioNames` (cache de textos RDS: vale até 50 km do lugar onde foi guardado, sem prazo), `AudioOwner` (pacote → fonte) |
| `device/` | Acesso ao aparelho: `Gps`, `Apps`, `MapData` (lê a pasta `Rumo`: vários estados ao mesmo tempo), `Alerts` (sons), `Aidl` (serviços do fabricante em binder puro), `CarStatus` (farol), `RadioStation` (estação), `RadioControl` (comandos) e os diagnósticos (`BroadcastHunt`, `CarSourceProbe`, `NightSignal`, `RadioProbe`) |
| `ui/` | Telas em Compose: `Home` (cards, dock, folhas), `RadioSheet`, `DriveMap` (MapLibre), `Theme`, `Icons` |
| `HomeActivity.kt` | Liga tudo: ciclo de vida, GPS, serviços, preferências |

O que o app pede ao usuário e ao sistema: localização (GPS), leitura e escrita de arquivos (pasta `Rumo` e relatórios), as permissões do rádio `hcn.permission.FM_*`, e `REQUEST_DELETE_PACKAGES` (desinstalar pela gaveta). Preferências ficam em `SharedPreferences` (`rumo`): tema, favoritas, nomes do rádio (gravados no máximo a cada minuto e ao sair da tela), lista da procura, unidade do `gotoFreq` (`radio_goto_unit`) e o registro dos dados baixados (`data_<arquivo>` = SHA-256 e data de modificação: arquivo trocado depois é conferido de novo). A última posição, gravada a cada 15 s, fica num arquivo à parte (`rumo_position`).

Threads: a conta da via e do radar roda numa thread só (`drive`), só a cada posição nova do GPS (a contagem de satélites é um estado à parte); as chamadas ao `sourceinfo` e ao `fmradio` rodam em outra (`radioIpc`), para os ouvintes entrarem e saírem na ordem; os comandos de sintonia passam por um `Mutex` (um por vez) e a ligação com o serviço do rádio, por outro. Os ouvintes de binder (`AidlTap`) só aceitam avisos de uid ou app do sistema. No mapa, os radares (dezenas de milhares) vão para a fonte uma vez por estilo, os pontos do usuário ficam numa fonte à parte e o tema troca só as cores das camadas.

A integração com os serviços do multimídia está em [`../docs/device-integration.md`](../docs/device-integration.md); os dados offline, em [`../data/README.md`](../data/README.md).

## Compilar

Precisa de JDK 17 e do Android SDK:

```bash
cd android
./gradlew :launcher:assembleDebug          # APK em launcher/build/outputs/apk/debug/ (o CI publica o assembleRelease)
./gradlew :launcher:testDebugUnitTest      # testes unitários
```

Sem as variáveis de assinatura abaixo, o APK sai com a chave de depuração do SDK e a versão `0.0.0-dev`.

## Versões e CI

Três workflows em `.github/workflows/`:

| Workflow | Quando roda | O que publica |
|---|---|---|
| `nightly.yml` | A cada push na `main` que mexe no app | Substitui a pré-release **nightly** (`rumo.apk`). Versão `última-tag-nightly.N` (ex.: `0.1.0-nightly.57`), com N = número de commits |
| `release.yml` | Ao criar uma tag `vX.Y.Z` | Release **estável** com `rumo-X.Y.Z.apk` e `rumo.apk` (nome fixo, para `releases/latest/download/rumo.apk`). Tag com sufixo (`v1.0.0-rc1`) sai como pré-release |
| `map-data.yml` | Mudança em `data/`, todo dia 1 e sob demanda | Pré-release **rumo-data** com `rumo-data-sc.zip` |

Cada publicação do app leva um `rumo.json` (versão, `versionCode`, tamanho, SHA-256, endereço do APK, data, commit e `notes`, a lista curta de novidades; gerado por `.github/scripts/rumo-json.sh`). As `notes` vêm de `release-notes/next.md` (nightly) ou `release-notes/X.Y.Z.md` (estável), validadas por `.github/scripts/release-notes.sh`; as regras de escrita estão em `release-notes/README.md`.

**Aviso de versão nova:** `HomeActivity` consulta o `rumo.json` do canal da versão instalada (`noticeChannel` em `core/UpdateNotice.kt`) a cada 4 horas com o Rumo na tela (15 s depois de abrir, se já passou o prazo; falhou, repete em 30 min; a hora da última consulta boa fica em `update_checked_at` e a versão achada em `update_found`, então o aviso sobrevive a reiniciar). Achou uma `versionCode` maior: acende o ícone de download na barra de cima e `ui/UpdatePopup.kt` mostra as `notes` e o botão de baixar e instalar (só com o carro parado). A consulta manual da aba Atualizações também atualiza o aviso. Build local (`0.0.0-dev`) não consulta. As configurações da home do app leem os manifestos em endereços fixos: `releases/latest/download/rumo.json` (estável; o GitHub ignora pré-releases no `latest`, então só existe depois da primeira tag `vX.Y.Z`), `releases/download/nightly/rumo.json` e `releases/download/rumo-data/data.json` (dados offline, ver `data/README.md`).

A seção **Sistema** das configurações da home tem o botão **Reiniciar o Android** (dois toques, só parado; `device/Reboot.kt`). Um app comum não pode reiniciar o Android: o botão tenta `PowerManager.reboot` (permissão `REBOOT`, só de apps do sistema) e `su`; no aparelho do Clayton nenhum dos dois vale. Então usa o serviço de acessibilidade "Rumo" (`device/PowerService.kt`, ligado em Configurações › Acessibilidade), que abre o menu de energia e toca em "Reiniciar" por até 10 s depois do pedido, sem ler nem guardar nada. O diagnóstico **Teste do reinício** grava `reboot-test.txt` com os ganchos de reinício que achar (componentes, textos nos apps do fabricante, métodos da MCU, serviços).

**Configurações (engrenagem na barra de cima):** o toque abre um menu com duas opções: **Configurações da home** (`ui/SettingsSheet.kt`, só com o carro parado: versão e atualização do app, mapas por estado, reinício do Android e diagnósticos) e **Configurações do sistema** (os ajustes do sistema). Antes, a tela abria com 7 toques no relógio. A tela da home tem quatro abas: **Configurações** (avisos de radar e reinício do Android), **Pontos** (os pontos marcados no mapa: lista dos últimos adicionados primeiro; tocar num ponto abre a edição de tipo, limite, sentido e posição, com um mapa pequeno em volta do ponto e, em cima dele, os botões −5 m / +5 m que movem o ponto ao longo da rua (voltando para a rua mais perto, nas curvas); o lado do mapa não rola e "Voltar à lista" e "Apagar ponto" ficam sempre à vista (`DriveMap(preview = true)`: zoom 19 por padrão, com botões + e − de 16 a 21, o sentido do ponto para cima, TextureView e 15 quadros por segundo; `ui/PointsTab.kt`); apagar pede dois toques), **Atualizações** (app por canal e mapas por estado; consulta sozinha ao entrar na aba e de novo quando a internet volta, via `ConnectivityManager`; sem conexão, mantém a última consulta e avisa) e **Debug** (diagnósticos e a versão instalada). Os **avisos de radar** têm dois ajustes, guardados nas preferências (`radar_zone_m`, `radar_margin_kmh`): a distância em que o aviso e os bipes começam (padrão 300 m, de 100 a 1000 m, de 50 em 50; lombada, pedágio e câmera de semáforo continuam avisando a no máximo 300 m) e a margem de velocidade do som (padrão 0, de 0 a 10 km/h): os bipes só começam acima do limite do radar mais a margem (com 2 km/h, num radar de 50 km/h, acima de 52); o aviso em vermelho aparece assim que passa do limite (51 km/h num radar de 50), sem margem (`RadarAlert.alarm` e `.sound`). Valem na hora, sem reiniciar (`RadarSettings` e `RadarWatch` em `core/Radars.kt`). O botão **Testar o som (7 s)** toca o aviso e os bipes do longe ao perto (`HomeActivity.testSound`, sem depender do GPS) e, se o áudio falhar, mostra o erro (`Alerts.lastError`).

**Registro dos alertas (`device/AlertLog.kt`):** para saber por que um aviso sonoro não tocou, o launcher anota em `Rumo/alertas.log` (memória interna; ao sair da tela copia também para o pendrive; passando de 200 KB vira `alertas-anterior.log`) só os eventos: Rumo na tela ou fora, GPS que caiu ou voltou, radar que entrou ou saiu (id, tipo, distância, velocidade, limite, margem), bipes pedidos ou não, e o que o áudio respondeu (foco, estado da faixa, quadros realmente tocados, volume, mudo, música tocando). Se o laço dos bipes ficar preso numa escrita que o áudio do aparelho não consome (3 s sem avançar), `Alerts.alarm` solta a faixa e recomeça, e anota. Lendo o log de uma viagem: "radar: entrou" sem "aviso: … tocou N/N quadros" = a conta ou o áudio falhou; "bipes pedidos" sem "bipes: ligados" = laço preso; nenhum "tela: Rumo na tela" = o Rumo não estava na tela.

**Marcar ponto ("+" no card do mapa):** para radar, semáforo com radar, radar móvel, lombada (comum e eletrônica), semáforo com câmera, polícia e pedágio que faltam no OSM e no MapaRadar. Dois toques: o "+" (guarda a posição e o rumo daquele instante, não os de quando se escolhe o tipo) e o tipo (`ui/MarkSheet.kt`). O limite começa no da via atual e o sentido no do carro (parado ou sem rumo: os dois); os dois se ajustam na folha. O ponto é encaixado na rua mais perto (até 30 m) ao ser marcado, para não ficar de lado da pista, e entra na hora no mapa e nos avisos (`MapData.setMine`, junto com os radares do OSM e do MapaRadar) e fica **só neste aparelho**, em `Rumo/my-points.csv` (memória interna) e em `files/my-points.csv` do app (`device/UserPointsStore.kt`, vale o arquivo mais recente): atualizar o app não mexe nele, e a pasta Rumo ainda sobrevive a desinstalar. Linhas `longitude,latitude,Tipo - N kmh@N,sentido,dia`: as três primeiras colunas são as do CSV do MapaRadar, dá para copiar e mandar ao fórum deles. "Meus pontos" lista (mais perto primeiro) e apaga com dois toques. Marcar o mesmo tipo a menos de 15 m troca o ponto (toque duplo). Os pontos não passam pelo cache `radars.bin` (entram depois dele) e, sem dados do mapa instalados, só ficam guardados.

**Volume flutuante:** arrastar o dedo para a direita no menu da esquerda abre um controle grande de volume (música: `AudioManager.adjustStreamVolume`; segurar repete) que recolhe sozinho em 4 s sem toque. **Teste dos botões** (Configurações da home › Diagnósticos, `ui/KeyTestSheet.kt`): lista as teclas que o Android entrega ao Rumo, a tela apagando/acendendo e, se der, o `getevent` do driver de entrada; não toca na MCU.

`build.yml` é o passo comum de compilar e testar (chamado pelos dois primeiros). Em todos os casos o `versionCode` é `1000 + número de commits`, sempre crescente.

**Lançar uma versão estável:**

1. Revise o `release-notes/next.md` (no máximo 6 itens curtos, sem jargão; ver `release-notes/README.md`), renomeie para `release-notes/X.Y.Z.md` e crie um `next.md` novo só com `# Próxima versão`. Commit e push.
2. Crie a tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

O número vem da tag; não há versão para editar no código. O `release.yml` falha logo, antes do build, se faltar o `release-notes/X.Y.Z.md` da tag ou se ele fugir das regras; o texto da release no GitHub é essa mesma lista. Pelo botão **Run workflow** do `release.yml` dá para compilar uma versão de teste sem publicar nada.

## Assinatura

A chave fixa do projeto fica fora do repositório, em segredos do GitHub Actions; com ela as versões novas instalam por cima das antigas. O `build.yml` grava a chave num arquivo temporário e passa estas variáveis ao Gradle:

| Segredo | O que é |
|---|---|
| `RUMO_KEYSTORE_B64` | O arquivo `.jks` em base64 |
| `RUMO_KEYSTORE_PASSWORD` | Senha do arquivo |
| `RUMO_KEY_ALIAS` | Alias da chave |
| `RUMO_KEY_PASSWORD` | Senha da chave |

Para trocar a chave (as instalações atuais precisam ser desinstaladas, porque a assinatura muda):

```bash
keytool -genkeypair -v -keystore rumo.jks -alias rumo -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 rumo.jks | gh secret set RUMO_KEYSTORE_B64
gh secret set RUMO_KEYSTORE_PASSWORD
gh secret set RUMO_KEY_ALIAS
gh secret set RUMO_KEY_PASSWORD
```

Pull requests de forks não têm acesso aos segredos: o APK deles sai com a chave de depuração do SDK.
