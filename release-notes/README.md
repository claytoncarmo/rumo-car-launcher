# Release notes

Texto das novidades de cada versão. O app mostra essas linhas num popup quando avisa que há versão nova, **dentro do carro**, então precisam ser lidas em dois segundos.

## Onde ficam

| Arquivo | Para quê |
|---|---|
| `next.md` | Novidades desde a última versão estável. Vai no **nightly** e, ao lançar a estável, vira o arquivo dela |
| `X.Y.Z.md` | Novidades da versão estável X.Y.Z (ex.: `0.2.0.md`). O `release.yml` exige o arquivo da tag: sem ele a publicação falha |

O CI copia as linhas para o campo `notes` do `rumo.json` da publicação (é de lá que o app lê) e, na estável, também para o texto da release no GitHub.

## Como escrever

- **Uma linha por item, começando com `- `.** Linhas em branco e as que começam com `#` são ignoradas; qualquer outra linha faz o CI falhar.
- **No máximo 6 itens, até 80 caracteres cada.** Menos é melhor.
- **Fale do que o motorista percebe**, em português do Brasil, no presente ou como substantivo: "Aviso de nova versão no topo da tela", não "Adicionado `UpdateNotice` no `StatusBar`".
- **Sem detalhe técnico**: nada de nomes de classe, serviço, arquivo, commit ou número de versão do Android.
- **Correções e desempenho viram uma linha geral**: "Correções de erros" e "Mais fluidez", sem listar cada um. Várias correções = uma linha só.
- **Novidade grande ganha linha própria**, com o nome que o usuário vê na tela: "Radares: aviso configurável (Configurações da home)".
- **Só entra o que muda para quem usa.** Mudança de código, documentação, site ou protótipo não entra.
- Não repita linhas de uma versão estável para a outra: cada arquivo conta só o que é novo nela.

Exemplo de `0.2.0.md`:

```
- Aviso de nova versão no topo da tela, com as novidades
- Rádio: nomes das estações e procura mais rápida
- Mapa mais fluido
- Correções de erros
```

## Ao mexer no app

Toda mudança que o usuário percebe (e toda correção) entra no `next.md` **no mesmo commit** da mudança, para o nightly já trazer as novidades. Se o `next.md` já tem "Correções de erros" ou "Mais fluidez", não repita.

## Ao lançar uma versão estável

1. Revise o `next.md`: junte o que ficou repetido e corte o que passou de 6 itens.
2. Renomeie para `release-notes/X.Y.Z.md` (o mesmo número da tag) e crie um `next.md` novo, vazio (só uma linha `# Próxima versão`).
3. Commit, `git tag vX.Y.Z`, `git push origin vX.Y.Z`.

Quem usa a estável só é avisado de versão estável nova e vê o `X.Y.Z.md`; quem usa o nightly é avisado de cada nightly nova e vê o `next.md` do momento do build.
