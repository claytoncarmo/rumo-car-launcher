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

O que o app pede ao usuário e ao sistema: localização (GPS), leitura e escrita de arquivos (pasta `Rumo` e relatórios), as permissões do rádio `hcn.permission.FM_*`, e `REQUEST_DELETE_PACKAGES` (desinstalar pela gaveta). Preferências ficam em `SharedPreferences` (`rumo`): tema, última posição, favoritas, nomes do rádio, lista da procura.

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
| `nightly.yml` | A cada push na `main` que mexe no app | Substitui a pré-release **nightly** (`rumo.apk`). Versão `última-tag-nightly.N` (ex.: `0.5.0-nightly.57`), com N = número de commits |
| `release.yml` | Ao criar uma tag `vX.Y.Z` | Release **estável** com `rumo-X.Y.Z.apk` e `rumo.apk` (nome fixo, para `releases/latest/download/rumo.apk`). Tag com sufixo (`v1.0.0-rc1`) sai como pré-release |
| `map-data.yml` | Mudança em `data/`, todo dia 1 e sob demanda | Pré-release **rumo-data** com `rumo-data-sc.zip` |

Cada publicação do app leva um `rumo.json` (versão, `versionCode`, tamanho, SHA-256, endereço do APK, data e commit; gerado por `.github/scripts/rumo-json.sh`). As configurações da home do app leem os manifestos em endereços fixos: `releases/latest/download/rumo.json` (estável; o GitHub ignora pré-releases no `latest`, então só existe depois da primeira tag `vX.Y.Z`), `releases/download/nightly/rumo.json` e `releases/download/rumo-data/data.json` (dados offline, ver `data/README.md`).

A seção **Sistema** das configurações da home tem o botão **Reiniciar o Android** (dois toques, só parado; `device/Reboot.kt`). Um app comum não pode reiniciar o Android: o botão tenta `PowerManager.reboot` (permissão `REBOOT`, só de apps do sistema) e `su`; no aparelho do Clayton nenhum dos dois vale. Então usa o serviço de acessibilidade "Rumo" (`device/PowerService.kt`, ligado em Configurações › Acessibilidade), que abre o menu de energia e toca em "Reiniciar" por até 10 s depois do pedido, sem ler nem guardar nada. O diagnóstico **Teste do reinício** grava `reboot-test.txt` com os ganchos de reinício que achar (componentes, textos nos apps do fabricante, métodos da MCU, serviços).

**Configurações (engrenagem na barra de cima):** o toque abre um menu com duas opções: **Configurações da home** (`ui/SettingsSheet.kt`, só com o carro parado: versão e atualização do app, mapas por estado, reinício do Android e diagnósticos) e **Configurações do Android** (os ajustes do sistema). Antes, a tela abria com 7 toques no relógio. A tela da home tem três abas: **Configurações** (avisos de radar e reinício do Android), **Atualizações** (app por canal e mapas por estado) e **Debug** (diagnósticos e a versão instalada). Os **avisos de radar** têm dois ajustes, guardados nas preferências (`radar_zone_m`, `radar_margin_kmh`): a distância em que o aviso e os bipes começam (padrão 300 m, de 100 a 1000 m, de 50 em 50; lombada, pedágio e câmera de semáforo continuam avisando a no máximo 300 m) e a margem de velocidade (padrão 0, de 0 a 20 km/h): o alarme, os bipes e o aviso em vermelho só começam acima do limite do radar mais a margem (com 2 km/h, num radar de 50 km/h, acima de 52). Valem na hora, sem reiniciar (`RadarSettings` e `RadarWatch` em `core/Radars.kt`).

**Marcar ponto ("+" no card do mapa):** para radar, semáforo com radar, radar móvel, lombada (comum e eletrônica), semáforo com câmera, polícia e pedágio que faltam no OSM e no MapaRadar. Dois toques: o "+" (guarda a posição e o rumo daquele instante, não os de quando se escolhe o tipo) e o tipo (`ui/MarkSheet.kt`). O limite começa no da via atual e o sentido no do carro (parado ou sem rumo: os dois); os dois se ajustam na folha. O ponto entra na hora no mapa e nos avisos (`MapData.setMine`, junto com os radares do OSM e do MapaRadar) e fica **só neste aparelho**, em `Rumo/my-points.csv` (memória interna) e em `files/my-points.csv` do app (`device/UserPointsStore.kt`, vale o arquivo mais recente): atualizar o app não mexe nele, e a pasta Rumo ainda sobrevive a desinstalar. Linhas `longitude,latitude,Tipo - N kmh@N,sentido,dia`: as três primeiras colunas são as do CSV do MapaRadar, dá para copiar e mandar ao fórum deles. "Meus pontos" lista (mais perto primeiro) e apaga com dois toques. Marcar o mesmo tipo a menos de 15 m troca o ponto (toque duplo). Os pontos não passam pelo cache `radars.bin` (entram depois dele) e, sem dados do mapa instalados, só ficam guardados.

**Volume flutuante:** arrastar o dedo para a direita no menu da esquerda abre um controle grande de volume (música: `AudioManager.adjustStreamVolume`; segurar repete) que recolhe sozinho em 4 s sem toque. **Teste dos botões** (Configurações da home › Diagnósticos, `ui/KeyTestSheet.kt`): lista as teclas que o Android entrega ao Rumo, a tela apagando/acendendo e, se der, o `getevent` do driver de entrada; não toca na MCU.

`build.yml` é o passo comum de compilar e testar (chamado pelos dois primeiros). Em todos os casos o `versionCode` é `1000 + número de commits`, sempre crescente.

**Lançar uma versão estável:**

```bash
git tag v0.5.0
git push origin v0.5.0
```

O número vem da tag; não há versão para editar no código. Pelo botão **Run workflow** do `release.yml` dá para compilar uma versão de teste sem publicar nada.

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
