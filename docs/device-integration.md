# Integração com o aparelho de referência

Como o Rumo obtém do multimídia (fabricante **HCN**, Android 9) o que o Android não expõe: o farol, a fonte de áudio e a estação do rádio. Tudo foi descoberto lendo os serviços do sistema do próprio aparelho; os valores abaixo foram observados nele e podem variar em outros modelos ou firmwares.

O perfil de hardware está em [`home-screen-study.md`](home-screen-study.md#aparelho-de-referência-medido).

## Como falar com os serviços

Os serviços do fabricante são serviços de sistema comuns (AIDL por binder) e um app normal consegue chamá-los, sem permissão especial:

1. `ServiceManager.getService(nome)`, por reflexão (API oculta, disponível no Android 9).
2. A interface tem um descritor (`IBinder.interfaceDescriptor`). As classes do fabricante `android.carstatus.*`, `android.carsource.*`, `android.sourceservice.*` e `android.radio.*` estão no próprio sistema e se leem por reflexão; as do app de rádio (`com.hcn.autoradio.*`) só existem no APK dele e se carregam com um `PathClassLoader` apontando para o `sourceDir` do pacote.
3. O número de cada chamada é a constante `TRANSACTION_<método>` da classe `Stub` da interface. Com ele se monta a chamada em binder puro: `writeInterfaceToken(descritor)`, argumentos, `transact(código)`, `readException()` e a resposta.
4. Para receber avisos, o app registra um `Binder` próprio que implementa a interface de callback; no `onTransact`, o método é achado pelo número e os argumentos são lidos pelos tipos da assinatura.

Em `device/Aidl.kt` há um leitor genérico que faz isso para qualquer interface (chama métodos sem argumentos, decodifica objetos e recebe callbacks).

## Serviços

### `carstatus` — estado do carro

Interface `android.carstatus.ICarStatus`.

| Transação | Método | Uso |
|---|---|---|
| 1 | `getParkingStatus()` | freio de mão (int) |
| 2 | `getReversing()` | marcha à ré (int) |
| 3 | `getAccStatus()` | ignição (int) |
| 7 | `getHeadLightStatus()` | **farol aceso** (boolean) |

Há também setters (`setReversing`, `setParkingStatus`, `setDashboardBrightness`, `setLed`…), que o Rumo não usa.

O farol **não** liga o modo noturno do Android (`uiMode`) nem muda ajustes ou propriedades legíveis (`Settings`, `getprop`); o sinal só aparece aqui. O Android Auto (ZLINK) usa o mesmo serviço para escurecer o mapa. O Rumo pergunta o farol a cada 2 s, com ele na tela, e escurece o tema.

### `sourceinfo` — fonte de áudio

Interface `android.sourceservice.ISourceService`: `setcallback` (1), `removecallback` (2), `getMusicInfo` (4). Callback `ISourceCallback`: `onMusicEvent(MusicInfo)` (1) e `onSourceChanged(String)` (2).

`MusicInfo` tem `className`, `duration`, `fileName`, `position` e `state`. A fonte aparece assim:

| Fonte | `onSourceChanged` | `MusicInfo.className` |
|---|---|---|
| Android Auto | `com.zjinnova.zlink` | `com.zjinnova.zlink` ou a `MainActivity` dele (`state` = `start`) |
| Rádio | `com.hcn.autoradio` | `.RadioMain` |
| Rádio parado, ou mexendo no sintonizador | `""` (vazio) | `""` |

Avisa a cada troca de fonte, mesmo trocada fora do Rumo. **Não informa a faixa nem a estação** (`fileName` e `position` vêm vazios). O ZLINK repete `onMusicEvent` quase a cada segundo. Vazio quer dizer que não há fonte no momento; o Rumo mantém no card a última fonte não vazia.

### `fmradio` — estação do rádio

Interface `android.radio.IRadioPlayer`. Números usados ou relevantes:

| Transação | Método | Observação |
|---|---|---|
| 1 / 2 | `setcallback` / `removecallback` | ouvinte `IRadioCallback` |
| 9 | `getFreq()` | frequência em kHz (`102700` = 102,7 MHz) |
| 11 | `getRadioInfo()` | objeto `RadioInfo` (abaixo) |
| 14 | `setMute(boolean)` | mudo do rádio |
| 15 | `getPreset(int)` | memórias da banda (formato não confirmado) |
| 18 | `getUiband()` | banda (2 = FM) |

`RadioInfo`: `mFreq` (kHz), `mIndexof` (memória 0–5, ou −1 fora delas), `mIsLocal`, `mIsStereo`, `mPSname` (vem sempre `null`), `mPTYtype`, `mRDSstate`, `mRTtype` (texto RDS), `mScanType` (4 durante a busca) e `mUiBand` (`FM-3`).

O ouvinte `IRadioCallback` avisa **cada troca de estação**, inclusive cada passo do sintonizador e cada frequência percorrida durante a busca:

- `onEvent(int, RadioInfo)`: nova estação.
- `onStringEvent(int, String)`: o primeiro número é o tipo: **38 = texto RDS**, 4 = estado do RDS, 34 = tipo de programa (PTY).

**Texto da estação (RDS):**
- Só chega **com um ouvinte registrado**. Sem ele, nem `getRadioInfo().mRTtype` traz texto.
- É o que a emissora manda, e chega 1 a 2 s depois de a estação firmar. Costuma girar entre o nome da rádio, nomes de cidade e slogans (na 95,9: "Gaspar", "RFC News", "Indaial"). Nem toda emissora manda, e o próprio app de rádio do fabricante só mostra a frequência.
- Logo após trocar de estação, `mRTtype` ainda traz o texto da estação anterior: só vale o que mudar depois da troca.

### App de rádio (`com.hcn.autoradio`) — comandos

O app oferece um serviço para outros apps: ação `com.hcn.autoradio.FM_PLUG_SERVICE` (`FMPlugService`), interface `com.hcn.autoradio.IRadioServiceAPI`. A permissão `hcn.permission.FM_DATA_SERVICE` é concedida e a `FM_PLUG_SERVICE` é negada, mas a ligação funciona.

| Transação | Método | Observação |
|---|---|---|
| 18 | `getCurrentFreq()` | kHz |
| 17 | `getCurrentBand()` | 2 = FM |
| 22 | `IsSeek()` | verdadeiro enquanto a busca anda |
| 25 | `requestPlayAudio()` | pede o rádio como fonte de áudio |
| 14 | `gotoFreq(int)` | sintoniza; a unidade (kHz, dezenas ou centenas de kHz) é descoberta na primeira sintonia conferindo no `fmradio` |
| 15 | `gotoFreq2(String)` | sintoniza por texto (`"95.9"`) |
| 9 / 10 | `onSeekDownEvent` / `onSeekUpEvent` | busca a próxima estação com sinal; **os nomes estão trocados**: `Up` busca para baixo e `Down`, para cima |
| 11 / 12 | `onManualUpEvent` / `onManualDownEvent` | passo manual |
| 6 | `onASEvent` | "AS" (auto store): **sobrescreve as 6 memórias; não usar** |

Comportamento:
- **Ligar o rádio:** ligar o serviço com `BIND_AUTO_CREATE` liga o rádio, que assume o áudio. Sem esse flag, só conecta se o rádio já estiver rodando.
- **Android Auto:** quando o ZLINK toma o áudio, o serviço do rádio morre (`DeadObjectException`). Se a ligação ficar aberta, o Android recria o serviço e o rádio volta brigando com o Android Auto: ao receber `onServiceDisconnected`, solte a ligação e só religue por ação do usuário.
- **Atualizar o app:** o serviço do rádio existe enquanto o Rumo está ligado nele. Ao atualizar o app (ou se o Android encerrar o processo) a ligação cai, o serviço morre e o rádio fica mudo, enquanto o card ainda diz que toca. Ao abrir, com o rádio como fonte e sem o mudo do Rumo, o app liga de novo (`resumeRadio`), e o play do card só para o rádio se o Rumo estiver ligado no serviço (`radioPlayStarts`).
- **Busca de estações:** a faixa FM vai de 87,5 a 107,9 MHz e as estações do Brasil ficam em frequências de final ímpar (passo de 0,2 MHz). Para listar as estações, vá para 87,5 e repita a busca para cima até dar a volta.
- **Parar o rádio:** a tecla play/pausa não chega ao app de rádio (não tem `MediaSession`). A saída é o mudo do `fmradio` (`setMute`, transação 14); ainda a validar no aparelho.
- O app tem também `registerRadioClientBinder`/`unRegisterRadioClientBinder` (1 e 2) e um ouvinte `IRadioCallBack.onEvent(int, String)`: o Rumo não os usa; o ouvinte foi aceito mas não mandou avisos.

### `mcu_service` — placa da MCU

Interface `android.carsource.IMcuManager`: `setcallback(IMcuListener, grupo)` (1), `removecallback` (2), além de `injectKeyEvent`, `requestBeep` e outros comandos de hardware. Os grupos são constantes `GID_*` de `android.carsource.McuConstant` (do tipo `byte`; `GID_SYS_STATUS` vale −112).

Registrado em todos os grupos, o único tráfego observado foram eventos `onEvent(valor, 1 ou 5)` compatíveis com **toques na tela** (aperta e solta, com a posição empacotada em `valor`). Nada do carro nem do rádio passa por aqui. O Rumo não usa este serviço.

### ZLINK (Android Auto)

- `AudioManager.dispatchMediaKeyEvent` funciona com anterior, play/pausa e próxima, mesmo sem `MediaSession` útil: é como os botões do card controlam o Android Auto.
- Sessão de mídia **vazia** e notificação **sem texto**: não há título nem artista para ler.
- O ZLINK transmite um broadcast `com.zjinnova.zlink` com o extra `status` (`MAIN_PAGE_SHOW`, `MAIN_PAGE_HIDDEN`, `MAIN_AUDIO_START`, `MAIN_AUDIO_STOP`) e `phoneBTMac`. Dá para escutá-lo com um receptor registrado em tempo de execução; o Rumo ainda não usa.

## Outros comportamentos do aparelho

- Qualquer som tocado por um app silencia a música enquanto toca, em todos os canais e com ou sem foco de áudio. Não é regra do app.
- A barra do sistema esconde com o modo imersivo, e o Rumo continua como tela inicial depois de reiniciar.

## Diagnósticos embutidos no launcher

Para investigar outro aparelho (ou outra versão de firmware) o Rumo traz ferramentas que gravam relatórios em texto:

| Como abrir | O que faz | Relatório |
|---|---|---|
| Toque longo no botão de tema (barra de cima) | Por 20 s, enquanto se acende e apaga o farol: ouve a MCU e os avisos (broadcasts) do sistema, compara os ajustes e lista as classes do fabricante e as interfaces dos serviços do carro | `headlight-test.txt` |
| Toque longo no card de mídia | Por 60 s: liga nos serviços do rádio, lê as funções de leitura, registra os ouvintes e anota o que muda ao trocar de estação | `radio-test.txt` |
| Toque longo no título "Rádio" (tela de rádio) | Registro dos comandos mandados ao rádio | `radio-commands-test.txt` |

Os relatórios vão para `Rumo/` na memória interna e para `Android/data/app.rumo/files/` de cada pendrive ou cartão. O código está em `device/` (`BroadcastHunt`, `CarSourceProbe`, `NightSignal`, `RadioProbe`).

**Receita para um aparelho novo:** liste os serviços (`ServiceManager.listServices`) e filtre por nomes de carro, MCU e rádio; leia o `interfaceDescriptor` de cada um; carregue a classe da interface e do `Stub` e liste as constantes `TRANSACTION_*`; chame primeiro só as funções de leitura (`get…`/`is…`); procure nos APKs do fabricante nomes de ação de broadcast e de propriedades; só depois registre ouvintes.
