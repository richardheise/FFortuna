# Dependências: auditoria de 22/10/2026

O relatório inicial tinha 21 alertas: 3 moderados e 18 altos. São três falhas de origem propagadas pela árvore de dependências, não 21 falhas distintas do código financeiro.

A correção preparada mantém Expo 57, React Native 0.86 e Expo Router 57. Após instalação limpa na cópia isolada, `npm audit` passou a informar **18 altos, zero moderados e zero críticos**. Os dois avisos de origem ainda pendentes não têm versão corrigida publicada na data desta revisão.

| Origem | Estado e tratamento |
| --- | --- |
| `decode-uri-component` via `expo-router → query-string@7.1.3` | Corrigido com a versão upstream 0.5.0. [GHSA-vcc3-ghjq-m6fr](https://github.com/advisories/GHSA-vcc3-ghjq-m6fr) afeta versões até 0.4.2. |
| `braces@3.0.3` via Metro/micromatch | Pendente: 3.0.3 é a versão mais recente, e o [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) ainda não informa patch. Risco descrito: esgotamento da pilha com padrões profundamente aninhados. |
| `node-forge@1.4.0` via Expo CLI/code-signing-certificates | Pendente: 1.4.0 é a versão mais recente, e o [GHSA-86w9-cpqp-85rv](https://github.com/advisories/GHSA-86w9-cpqp-85rv) ainda não informa patch. Risco descrito: validação incorreta de assinaturas RSA PKCS#1 v1.5. |

Os dois pacotes pendentes pertencem à cadeia das ferramentas de desenvolvimento. Isso não prova que sejam inexploráveis no servidor Metro nem que um APK esteja livre deles; o audit examina versões/árvore, não o conteúdo de um APK. Manter o Metro como servidor de desenvolvimento e encerrar o túnel ao terminar. A criptografia financeira nativa ainda não existe e não usará esse código como substituto do SQLCipher/Keystore.

Não executar `npm audit fix --force` nesta árvore: a remediação calculada pelo npm sugere, entre outras mudanças, Expo 44.0.6 e React Native 0.72.17. Isso quebra a combinação de SDK atual. Não foram aplicadas exclusões de advisories ou redução de nível para esconder alertas. Reconsultar os dois upstreams no próximo incremento; a tarefa permanece aberta.

## Ponte de compatibilidade

A versão corrigida de `decode-uri-component` é ESM, enquanto `query-string@7` espera uma função CommonJS. Atualizar só o decoder ou forçar `query-string@9` quebra esse contrato do Router atual.

O pacote local `vendor/decode-uri-component-compat` adapta o export default para a função CommonJS e preserva a interpretação legada de `+` como espaço. Ele chama o algoritmo upstream 0.5.0, instalado pelo alias `decode-uri-component-fixed`; não copia nem reimplementa o decoder. O override aponta para a dependência direta local por `$decode-uri-component`, evitando resolução de caminho relativa ao pacote consumidor. A dependência upstream é exata e tem URL/integridade no lockfile.

A ponte é código original AGPL-3.0-only; o decoder upstream mantém MIT e seus avisos foram incluídos no inventário. Remover ponte, aliases e override quando uma versão compatível do Expo Router dispensar essa adaptação, repetindo os testes de URLs e bundle. Não trocar SDK apenas para fazer o contador do audit desaparecer.

## Aviso de scripts de instalação

Foi revisado o `postinstall: node install.js` de `esbuild@0.28.2`, usado pelo `tsx` nos testes. Ele prepara e verifica o binário da plataforma, podendo buscar o pacote do binário se a dependência opcional estiver ausente.

A política versionada é somente:

```json
"allowScripts": {
  "esbuild@0.28.2": true
}
```

O lockfile preserva `resolved` e `integrity`; o npm usa essa identidade para validar a aprovação por versão. Não liberar todas as versões nem usar um bypass global. Rever o script e a aprovação ao atualizar esbuild. A aprovação é suportada pelo npm 11.19.1 usado na validação. Veja [npm install-scripts](https://docs.npmjs.com/cli/v11/commands/npm-install-scripts/).

O aviso de funding é informativo e não exige correção.

## Aplicar depois do teste em andamento

O `node_modules` do workspace e o Metro em uso foram preservados. A correção já está no manifesto/lockfile, mas só passa a valer na instalação em uso depois de reinstalar.

Pare o Metro com Ctrl+C e execute:

```sh
nvm use
npm ci
npm run check
npm audit
npm start -- --tunnel
```

`npm audit` ainda retorna código diferente de zero pelos 18 avisos altos pendentes. Não encadear esse comando com `&&` se a intenção for iniciar o Metro mesmo conhecendo essas pendências. `npm install-scripts ls` permite conferir scripts ainda sem revisão.

## Verificação executada

Na cópia isolada, com os mesmos arquivos de código/manifestos entregues:

- `npm ci`: instalação limpa e sem aviso pendente do esbuild.
- `npm run check`: TypeScript estrito e 30 testes aprovados (27 financeiros/parsing e 3 de compatibilidade/segurança de URLs).
- `npx expo install --check`: dependências compatíveis com SDK 57.
- `npm run export:android`: bundle Android/Hermes gerado, sem produzir APK.
- Bundle JavaScript Android de um emissor de teste mínimo, gerado pelo Metro e executado em VM Node: export CommonJS/ESM, Unicode, parâmetros repetidos e entrada malformada longa funcionaram. Não é execução no Android/Hermes.
- `npm audit --json`: 18 altos, zero moderados, duas advisories de origem pendentes.

O teste de entrada malformada roda em subprocesso com limite de cinco segundos para poder interromper uma regressão de consumo excessivo. Os três testes novos estão em `tests/dependencies.test.ts`. A integração final no Expo Go físico continua pendente; não foi declarada como validada.
