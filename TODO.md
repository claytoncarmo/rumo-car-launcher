# Tarefas

Lista viva do que falta. O estado do projeto e as decisões ficam em [`CLAUDE.md`](CLAUDE.md).

## Suporte a outros aparelhos

Hoje só o HCN de referência foi testado (ver "Outros aparelhos" no [`README.md`](README.md)).

- [ ] **Perfis de aparelho:** tirar do código os nomes de serviços, pacotes e permissões do fabricante (`carstatus`, `sourceinfo`, `fmradio`, `com.hcn.autoradio`, `hcn.permission.*`, ZLINK) e deixá-los num perfil por fabricante, escolhido sozinho pelos serviços que existem no aparelho. Perfil "genérico": sem rádio, sem farol, sem fonte de áudio.
- [ ] **Falhar sem travar:** conferir que cada recurso do fabricante some ou cai no plano B quando o serviço não existe (farol → horário; fonte → Acesso ao uso; rádio → escondido). Testar num aparelho ou emulador sem HCN e cobrir com testes onde der.
- [ ] **Esconder o que o aparelho não tem:** dock e cards sem rádio, sem Android Auto e sem farol quando não houver.
- [ ] **Android Auto configurável:** escolher o app de navegação/projeção em vez de fixar o ZLINK.
- [ ] **Mais arquiteturas:** compilar também `arm64-v8a` (e `x86_64` para emulador); conferir o tamanho do APK com o MapLibre.
- [ ] **Roteiro para aparelho novo** (`docs/novo-aparelho.md`): ordem dos diagnósticos (farol, rádio, reinício, botões), o que olhar em cada relatório e como transformar o resultado num perfil.
- [x] **Mais de um estado nos dados:** Sul e Sudeste (PR, SC, RS, SP, RJ, MG, ES), lista e seleção no painel Rumo, vários mapas e vias ao mesmo tempo. Falta validar no carro (ver abaixo) e, se der certo, as outras regiões do país.

## A validar no aparelho

- [ ] Estados: atualizar o app, abrir o painel, marcar dois estados vizinhos, baixar, ver o mapa nos dois e atravessar a divisa (rua e limite continuam; sem sumir vias na faixa de folga). Conferir o tamanho de MG e SP e se o mapa continua fluido com mais de um estado.

- [ ] Volume flutuante: se o volume de música do Android muda o som nesta unidade (senão, achar outro caminho).
- [ ] Teste dos botões: o que o Android recebe dos botões do lado da tela (os botões pararam em 31/out/2026; ver `CLAUDE.md`).
- [ ] Reiniciar o Android pelo menu de energia (serviço de acessibilidade) e o relatório do Teste do reinício.
- [ ] Painel Rumo: TLS e relógio do aparelho nas conexões https, tela de "instalar apps desconhecidos", instalação por cima, troca do mapa com ele aberto.
- [ ] Mapa novo (zoom 14 ampliado até 16,5, sem parques, riachos e vias de serviço): ver se ficou bom andando.
- [ ] Mudo do `fmradio` (`setMute`) como "parar" do play do card de mídia no rádio.
- [ ] Rádio: se vale a procura parar uns 6 a 8 s em cada estação para ler o nome RDS.

## Outras

- [ ] Testar andando: rua e limite em cruzamentos e ruas paralelas, avisos de radar, desempenho do mapa e a velocidade do GPS contra o painel do carro.
- [ ] Radares além do OSM (ANTT, dados abertos de prefeituras).
- [ ] Primeira versão estável: `git tag v0.5.0 && git push origin v0.5.0`, para existir `releases/latest`.
- [ ] Fonte Barlow; cartão inteligente só tem a página Viagem; telefone e Bluetooth não integrados.
- [ ] Servidor de relatórios na rede, para dispensar o pendrive nos testes.
