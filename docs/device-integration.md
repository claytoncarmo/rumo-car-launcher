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
- **Som curto x som contínuo (avisos com o FM):** um som curto (o aviso de entrada, ~0,75 s, `AudioTrack` estático) só abaixa o FM, que toca junto; uma faixa contínua (os bipes, bipe + silêncio escrito na mesma faixa por vários segundos) faz o aparelho trocar a fonte: o FM para e não volta, e há picote. Visto pelo app do rádio online e pelo Rumo. Por isso cada bipe toca como um som curto separado, sem faixa entre eles (a validar no carro).
- **O firmware encerra o "app de mídia anterior" a cada troca de fonte:** com o Rumo declarando uma sessão de mídia (Media3 `MediaSessionService`, mesmo em outro processo e sem tocar), o `sourceinfo` passou a informar `app.rumo` como fonte e, quando o rádio (`requestPlayAudio()`) ou o ZLINK pegavam a fonte, o pacote `app.rumo` inteiro era encerrado, sem erro no app. Tirar a sessão de mídia não bastou, nem tocar sem foco de áudio: o `sourceinfo` marca `app.rumo` no instante em que o player começa a tocar som do tipo mídia (`USAGE_MEDIA`), e o encerramento vem na troca seguinte; quando o `sourceinfo` não chegou a marcar o Rumo, a troca não o encerra. Os alertas de radar (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, também no volume de música) nunca foram marcados. O tipo do som também não importa (`USAGE_GAME` foi marcado no mesmo instante): o firmware marca qualquer pacote que toca som contínuo. Por isso o player do rádio online mora em outro pacote (`app.rumo.webradio`, módulo `android/webradio`), e é esse app que o firmware encerra na troca de fonte; e, com esse app instalado, **todo som do Rumo sai dele** (os avisos de radar), tocando o online ou não: um aviso tocado pelo próprio Rumo o marcava como fonte (`sourceinfo` = `app.rumo`) e a troca seguinte, para o online ou para o FM, encerrava o Rumo. O Rumo fica ligado nesse app enquanto está na tela e liga de novo se o firmware o encerrar (com o app instalado, **o Rumo nunca toca som**: com a ligação caída, o aviso espera ela voltar, em ~0,3 s; um bipe tocado pelo Rumo nesse intervalo o marcou como fonte e a troca seguinte o encerrou); com o FM tocando, um aviso tocado por esse app faz o rádio ceder a vez (o aparelho o trata como app de música) e não voltar sozinho, então o Rumo manda o FM tocar de novo quando o aviso termina; e, por garantia, logo depois do online o Rumo só liga o serviço do rádio (que já o põe para tocar), sem `requestPlayAudio()`.
- **Android Auto:** quando o ZLINK toma o áudio, o serviço do rádio morre (`DeadObjectException`). Se a ligação ficar aberta, o Android recria o serviço e o rádio volta brigando com o Android Auto: ao receber `onServiceDisconnected`, solte a ligação e só religue por ação do usuário.
- **Atualizar o app:** o serviço do rádio existe enquanto o Rumo está ligado nele. Ao atualizar o app (ou se o Android encerrar o processo) a ligação cai, o serviço morre e o rádio fica mudo, enquanto o card ainda diz que toca. Ao abrir, com o rádio como fonte e sem o mudo do Rumo, o app liga de novo (`resumeRadio`), e o play do card só para o rádio se o Rumo estiver ligado no serviço (`radioPlayStarts`).
- **Busca de estações:** a faixa FM vai de 87,5 a 107,9 MHz e as estações do Brasil ficam em frequências de final ímpar (passo de 0,2 MHz). Para listar as estações, vá para 87,5 e repita a busca para cima até dar a volta.
- **Parar o rádio:** a tecla play/pausa não chega ao app de rádio (não tem `MediaSession`), e o mudo do `fmradio` (`setMute`, transação 14) sozinho não calou o rádio no aparelho. O `IRadioServiceAPI` e o `IRadioPlayer` não têm comando de parar. O que cala é soltar a ligação com o serviço do rádio: ele só existe enquanto alguém está ligado nele (foi o que fez o rádio calar ao atualizar o app). O Rumo faz as duas coisas (mudo e soltar a ligação) e, para voltar, liga de novo, o que põe o rádio para tocar. Ainda a validar no aparelho.
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

### Scanner OBD2 pelo Bluetooth (ELM327)

O aparelho esconde o menu de Bluetooth, mas o Android oferece o adaptador ("Car BT") a apps comuns: dá para procurar, listar pareados e abrir a porta serial (SPP) com as permissões `BLUETOOTH`, `BLUETOOTH_ADMIN` e localização (o Android 9 exige a localização para procurar). Resultados no carro (nightly 91/92, scanner "OBDII", ELM327 v1.5):

- **Sem parear e sem PIN:** `createInsecureRfcommSocketToServiceRecord(SPP)` conecta direto; a tentativa "segura" falha (precisa do pareamento). O Rumo guarda só o endereço e conecta sempre assim.
- **Procura:** uma procura de ~20 s acha o scanner (com o carro ligado e o scanner na tomada). A procura pesa no Bluetooth do aparelho, que também faz as chamadas: só com o carro parado e nunca em laço.
- **Protocolo do carro (Honda):** detectado sozinho (`ATDPN` = `A7`). PIDs do modo 01 que este carro informa: 01 03 04 05 06 0B 0C 0D 0E 0F 10 11 13 15 1F 21 2E 2F 30 31 33 34 42 43 44 45 47 49 4A 4C (e blocos 20/40). Não informa taxa de combustível (5E), temperatura do óleo (5C) nem ambiente (46).
- **Chave desligada (23/nov/2026, `obd-ignition.log`):** o multimídia continua ligado uns segundos; o carro para de responder e, 6 a 16 s depois, o scanner também (nem a tensão responde): ele desliga ou dorme junto com a chave, sem ficar gastando a bateria.
- **Ritmo das leituras:** cada pergunta a um ELM327 clone leva de 100 a 300 ms. Dois truques, testados a cada conexão (o resultado vai para o `obd-ignition.log`): o "1" no fim do pedido (`010D1`: o scanner responde na primeira resposta, sem esperar o prazo) e vários PIDs num pedido só (`010C0D`, só em CAN; o Rumo monta grupos cuja resposta cabe num quadro, até 7 bytes, `Elm327.singleFrameGroups`, porque resposta em vários quadros é onde os clones mais erram e o "1" poderia cortá-la; `Elm327.multiPayload` lê os dois formatos). No scanner do Clayton ("OBDII", ELM327 v1.5) os dois funcionam. Falhando seguido, o Rumo desliga o truque e pergunta um a um; se o enlace cair com o modo rápido ligado, a rodada mínima dobra (até 800 ms). Toda rodada: rotação, velocidade, ar, pedal (49) e carga (04); temperatura a cada 3 s, tanque a cada 10 s, contador e tempo desde a partida a cada 30 s. Fora da aba Motor, uma rodada por segundo; com ela na tela, rodadas seguidas (no mínimo 200 ms cada).
- **Consumo:** só pelo fluxo de ar (MAF, PID 10): litros/hora = MAF × 3600 / (13,2 × 750); erra uns 10 % e o "ajuste do consumo" corrige.
- **Km do painel:** o carro não informa o hodômetro pelo OBD2 genérico. O lembrete de troca de óleo usa a "distância desde que as falhas foram apagadas" (PID 31, lida a cada 30 ciclos): a central conta mesmo com o Rumo desligado, e o Rumo soma a diferença entre leituras ao km do painel que o dono informou. O contador zera ao apagar falhas ou desligar a bateria (aí a soma recomeça do zero).
- **Viagens:** o "tempo desde a partida" (PID 1F, lido a cada 30 ciclos junto com o 31) dá a hora em que o motor ligou; uma hora de partida diferente (mais de 3 min) é viagem nova. Como o Rumo só lê com ele na tela, os km que o contador andou entre a última leitura de uma viagem e a primeira da seguinte vão para a anterior quando a nova tem até 3 min de motor, e para a nova quando não.
- **Só leitura:** o Rumo nunca apaga falhas (modo 04) nem manda comandos que alterem o carro. Ignição desligada: o scanner responde, o carro não (`UNABLE TO CONNECT`/`NO DATA`); o Rumo espera.

## Diagnósticos embutidos no launcher

Para investigar outro aparelho (ou outra versão de firmware) o Rumo traz ferramentas que gravam relatórios em texto:

| Como abrir | O que faz | Relatório |
|---|---|---|
| Toque longo no botão de tema (barra de cima) | Por 20 s, enquanto se acende e apaga o farol: ouve a MCU e os avisos (broadcasts) do sistema, compara os ajustes e lista as classes do fabricante e as interfaces dos serviços do carro | `headlight-test.txt` |
| Toque longo no card de mídia | Por 60 s: liga nos serviços do rádio, lê as funções de leitura, registra os ouvintes e anota o que muda ao trocar de estação | `radio-test.txt` |
| Toque longo no botão "FM" (tela de rádio) | Registro dos comandos mandados ao rádio (FM e online), o da sessão anterior e o log do Android com as linhas do Rumo | `radio-commands-test.txt` |
| Configurações da home › Teste do Bluetooth (OBD2) | Vê se um app comum usa o Bluetooth do aparelho (que esconde o menu): lista pareados, procura dispositivos e, com um scanner OBD2 (ELM327) à vista, tenta parear (PIN 1234 e 0000), conectar na porta serial e ler versão, tensão, rotação, temperatura e os PIDs do carro (só leitura) | `obd-bluetooth-test.txt` |
| (sempre ligado, sem botão) Registro do scanner com a chave desligada | Com o Rumo na tela, anota cada mudança do scanner e do carro (conectou, carro parou de responder, enlace caiu, scanner não encontrado) com a tensão da bateria, e uma batida a cada 30 s enquanto o carro está mudo ou o scanner sem conexão. Serve para saber se o scanner continua ligado, dorme ou desliga com a chave desligada | `obd-ignition.log` (e `obd-ignition-previous.log`) |
| (sempre ligado, sem botão) Registro do jeito de dirigir | Com o scanner lendo: cada leitura que somou pontos (velocidade, aceleração, giro, pedal cru e curso, carga, intervalo entre leituras), cada troca de rótulo e um resumo por minuto (leituras, intervalo médio, maiores aceleração, freada, giro, pedal e carga, e o pedal solto e no fundo aprendidos). Serve para calibrar os limites do indicador com o carro de verdade | `driving.log` (e `driving-previous.log`) |
| Sozinho, quando o Rumo fecha | Erro Java na hora; na abertura seguinte completa com o trecho do log do Android do fechamento (pega também erro nativo) e os comandos do rádio de antes de fechar, e avisa | `crash-report.txt` |

Os relatórios vão para `Rumo/` na memória interna (precisa da permissão de armazenamento, pedida na abertura) e para `Android/data/app.rumo/files/` de cada pendrive ou cartão. A gravação é atômica (um `.tmp` trocado pelo definitivo: o carro pode desligar no meio) e avisa o índice de mídia, senão alguns gerenciadores de arquivos não mostram o arquivo novo. O código está em `device/` (`BroadcastHunt`, `CarSourceProbe`, `NightSignal`, `RadioProbe`, `CrashReport`). Um app comum lê no logcat só as próprias linhas, mas inclusive as do processo que acabou de morrer: é assim que o relatório acha o fechamento.

**Receita para um aparelho novo:** liste os serviços (`ServiceManager.listServices`) e filtre por nomes de carro, MCU e rádio; leia o `interfaceDescriptor` de cada um; carregue a classe da interface e do `Stub` e liste as constantes `TRANSACTION_*`; chame primeiro só as funções de leitura (`get…`/`is…`); procure nos APKs do fabricante nomes de ação de broadcast e de propriedades; só depois registre ouvintes.
