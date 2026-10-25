# FFortuna

Aplicativo Android livre para organização financeira pessoal no Brasil. O primeiro **F** vem de *free*; **Fortuna** homenageia a deusa romana da fortuna.

O projeto tem uma **demonstração financeira no Expo Go** e uma [prova nativa Android](docs/captura-nativa.md), ambas exclusivamente com dados fictícios. A prova usa APK próprio e caixa SQLCipher/Kotlin, sem gerar lançamentos financeiros. Inclui conta em BRL, lançamentos manuais, resumo mensal por categoria, caixinhas, compromissos iniciais do cartão, processamento de eventos sintéticos e importação OFX sintética com prévia. Os dados persistem localmente em SQLite.

Pré-release experimental: [baixar APK Android 0.3.0](https://github.com/richardheise/FFortuna/releases/download/v0.3.0-alpha.1/ffortuna-capture-poc-arm64.apk) · [fonte, checksums e avisos](https://github.com/richardheise/FFortuna/releases/tag/v0.3.0-alpha.1). O usuário aprovou o roteiro manual de backup/restauração no A36/Android 16 em 25/10/2026, incluindo reinstalação.

## Executar no celular

Use Node 24, npm e Expo Go para Android compatível com SDK 57. Com nvm instalado:

```sh
cd /home/heise/FFortuna
nvm use
npm ci
npm start -- --lan
```

Computador e celular devem estar na mesma rede. Abra o Expo Go e escaneie o QR do terminal. Não exige conta Expo, EAS ou assinatura. O [roteiro no celular](docs/teste-no-celular.md) traz entradas e resultados esperados.

O projeto fixa Expo 57 / React Native 0.86 / React 19.2; foi seguida a [orientação oficial durante a transição do Expo Go](https://docs.expo.dev/get-started/create-a-project/). Se o Expo Go instalado informar incompatibilidade, use uma [versão Android compatível](https://expo.dev/go), sem misturar dependências de SDKs diferentes. A combinação foi conferida com `npx expo install --check`.

Expo Go é o aplicativo hospedeiro desta demonstração. APK da prova disponível em `artifacts/ffortuna-capture-poc-arm64.apk`, versão 0.3.0 gerada e inspecionada localmente. O roteiro de captura 0.2.1 foi aprovado pelo usuário no Galaxy A36/Android 16; o roteiro de backup/restauração 0.3.0 também foi aprovado em 25/10/2026. O módulo Kotlin e o APK da prova sintética usam um [build Android próprio](https://docs.expo.dev/guides/local-app-development/); Expo Go não executa módulos nativos personalizados. Os comandos e critérios estão em [captura-nativa.md](docs/captura-nativa.md). O modo financeiro com dados reais ainda não está habilitado.

## O que funciona

- Conta fictícia com data e saldo de abertura; renda mensal prevista separada dos recebimentos.
- Receita, despesa e movimentação entre conta e caixinha; saldo atual, histórico filtrado por mês e gastos por categoria.
- Caixinhas por nome/modalidade, posição inicial, guardar, resgatar e ajuste explícito com motivo.
- Dívidas iniciais do cartão por vencimento, pagamentos parciais e saldo restante, sem criar nova despesa.
- Evento sintético seguro lançado automaticamente; evento incompleto revisável; evidência, campos extraídos e correção preservados.
- Reprocessamento idempotente, confirmação repetida sem duplicação, compras iguais independentes e atualização conflitante sem alterar decisão anterior.
- Três OFX embutidos: período atual, sobreposição e histórico anterior. Prévia, revisão, vínculo assistido e conferência explícita da abertura histórica.
- Migrações versionadas e persistência local. Licença e avisos disponíveis no botão Sobre e licenças.
- Na prova nativa 0.3.0: backup da caixa sintética protegido por senha, sem bruto, com restauração em nova caixa e opção de voltar à anterior. O backup não inclui o banco financeiro do Expo Go.

O saldo atual é acumulado; receitas, despesas e categorias seguem o mês selecionado. Alterar a renda prevista não deposita dinheiro. Posições iniciais, transferências, ajustes e pagamentos de dívida não inflam receita ou consumo. Caixinhas são posições informadas; não calculam rentabilidade, impostos ou liquidez. Compromissos iniciais não reconstroem compras antigas nem parcelamentos.

Estado da sessão e próximos passos em [continuidade](docs/continuidade.md). O checkpoint experimental [`v0.3.0-alpha.1`](https://github.com/richardheise/FFortuna/releases/tag/v0.3.0-alpha.1) está publicado com nove anexos, incluindo APK, fonte correspondente e checksums. As [notas](docs/releases/v0.3.0-alpha.1.md) registram o escopo e as validações pendentes. Para acesso por SSH, use `npm start -- --tunnel`; esse modo usa um intermediário de desenvolvimento e dispensa estar na mesma rede.

## Verificação

```sh
npm run check
npm run export:android
```

Executados na validação mais recente em cópia isolada: TypeScript estrito, **30 testes de comportamento aprovados** e exportação Android pelo Metro/Hermes. Os testes usam SQLite real via `node:sqlite`, incluindo banco em arquivo, reabertura, migrações, restrições do livro, limites monetários, importação atômica e processamento recuperável. A exportação produz um **bundle em `dist/`, não um APK**.

Uma checagem adicional no Chromium, com interface React Native Web e serviço real sobre SQLite em memória, percorreu os formulários, revisão, caixinhas, pagamentos, importações e licenças. Esse harness temporário não é uma plataforma web do produto e não valida módulos nativos, teclado Android ou persistência do Expo Go. O usuário testou e aprovou a demonstração no celular em 22/10/2026; modelo, versão Android e percurso detalhado não foram registrados. A correção posterior das dependências ainda precisa ser instalada e conferida no aparelho. A toolchain Android e o ADB foram instalados para a prova nativa; nenhum aparelho está conectado e os testes instrumentados ainda não foram executados. `expo-doctor` foi interrompido sem resultado; não é um check aprovado. O servidor Metro iniciou, mas a sondagem HTTP local não foi confirmada neste ambiente.

O ajuste das dependências reduziu o audit de 21 para 18 alertas; os 18 restantes decorrem de duas falhas upstream ainda sem patch. A aprovação do esbuild está limitada à versão instalada. Veja [tratamento e instruções para aplicar](docs/dependencias.md). A instalação ativa foi preservada durante o teste do usuário; execute `npm ci` após parar o Metro.

## Arquitetura

Monólito modular, sem backend. Não há API remota, conta externa, telemetria do aplicativo ou credenciais bancárias.

| Local | Responsabilidade |
| --- | --- |
| `src/core/` | Contratos tipados, valores em centavos inteiros e datas. |
| `src/application/demo-service.ts` | Operações financeiras, processamento, classificação determinística e importação da demonstração. |
| `src/processing/` | Parsing sintético e subconjunto OFX, confiança e evidência por campo. |
| `src/persistence/` | Migrações, transações e adaptador expo-sqlite. |
| `src/demo/` | Fixtures, captura simulada e inicialização do ambiente sintético. |
| `app/`, `src/ui/`, `src/legal/` | Navegação, formulários, consultas e avisos locais. |

O serviço da demonstração é o único escritor: serializa comandos e consultas, executa migrações antes de liberar o uso e confirma livro, vínculos e estado atomicamente. Eventos são persistidos antes do processamento. Identidade da fonte/revisão, unicidade e comandos idempotentes protegem contra repetição; valor/data servem como indício de ambiguidade, nunca como prova de duplicação. Lançamentos confirmados são equilibrados e imutáveis no banco.

O contrato distingue evento observado, parsing versionado, candidato, decisão humana, transação e vínculos. Publicação, captura e data efetiva são campos distintos. Os parsers atuais aceitam apenas fontes sintéticas reconhecidas; **não há parser Nubank validado**.

No modo real planejado, Kotlin será dono do único banco SQLCipher, das migrações e do processamento, mesmo sem JavaScript ativo. A interface chamará operações pela Expo Modules API. O adaptador TypeScript existe apenas para dados sintéticos, sem sincronizar dois livros. O custo dessa escolha é manter duas implementações de serviço e verificar equivalência dos contratos; Kotlin integral simplificaria essa integração. Veja os [ADRs](docs/adr/) e o [alinhamento aceito](docs/alinhamento-inicial.md).

## Privacidade e limites desta entrega

Use **somente informações inventadas** em todos os formulários. O banco `ffortuna-synthetic-v1.db` desta demonstração não é criptografado. Não há seletor de arquivos pessoais nem acesso a notificações. Os três OFX são gerados pelo projeto, sem alegar que reproduzem arquivos Nubank. O parser cobre um subconjunto limitado de OFX; a validação de uma exportação real ficará no modo protegido.

O núcleo não envia dados a terceiros. Downloads de dependências, Expo Go e Metro pertencem ao ambiente de desenvolvimento e podem precisar de rede; os scripts desativam a telemetria do CLI Expo. Isso não transforma o Expo Go em um APK release próprio offline. O APK da prova sintética foi inspecionado sem permissão INTERNET e traz JavaScript embutido; o usuário relatou aprovação do roteiro manual de captura sintética no A36. Os testes instrumentados ainda não foram executados.

Dados reais continuarão bloqueados até validar SQLCipher, Keystore, arquivos auxiliares, política de backup, restauração sem a chave original, retenção e ausência de conteúdo financeiro em logs/temporários em claro. Bloqueio biométrico da interface não substitui criptografia. Nunca adicione extratos, amostras reais, bancos, backups, keystores ou credenciais ao Git.

## Próximo incremento

Prioridade confirmada pelo usuário: funcionalidades em incrementos pequenos antes de refinar UI/UX. O roteiro manual da prova sintética no Galaxy A36/Android 16 foi aprovado pelo usuário após a correção 0.2.1. A versão 0.3.0 acrescenta backup portátil criptografado/restauração da caixa sintética. O usuário aprovou todos os oito passos do [roteiro de backup](docs/captura-nativa.md#backup-portátil--roteiro-030), incluindo senha errada, alternância, reinstalação e persistência. Próximo incremento proposto: fechar a retenção das caixas inativas e a verificação de segurança nativa antes do diagnóstico protegido de notificações reais. Testes instrumentados e política de retenção das caixas inativas seguem pendentes antes de aceitar amostras reais. O suporte Nubank ainda depende dessas condições e de amostras validadas.

## Licença

Código original sob [GNU AGPL v3](LICENSE), identificador `AGPL-3.0-only`. Dependências e fontes preservam suas licenças: [inventário npm amplo](THIRD_PARTY_NOTICES.md) e [revisão específica do APK](docs/revisao-licencas-apk.md). A revisão identificou zero pendências de textos nos componentes deste APK; avisos e AGPL estão incluídos nele. Uma distribuição deve levar o fonte correspondente à mesma revisão. Para atualizar os inventários e repetir a conferência, siga os comandos do relatório.

Finan+ e PennyWise foram referências conceituais: nenhum código ou asset desses projetos foi incorporado. Não há afiliação com eles ou com o Nubank. Termos do domínio estão em [GLOSSARY.md](GLOSSARY.md).
