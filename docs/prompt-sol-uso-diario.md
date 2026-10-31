# Prompt para o Sol — uso diário, importação e análises

Preparado em 31/10/2026. Este documento é uma instrução para a próxima implementação, não um registro de funcionalidades entregues.

---

Você vai continuar o desenvolvimento do **Tique**, aplicativo Android livre de acompanhamento de movimentações financeiras. Trabalhe no repositório existente `/home/heise/FFortuna`, remoto `richardheise/Tique`. Converse em português brasileiro. Implemente a primeira entrega descrita abaixo e conclua suas verificações; as ideias posteriores são referência, não obrigação de implementar tudo agora.

## Contexto e objetivo

O usuário usa Nubank e um Samsung A36 SM-A366E/DS com Android 16. Quer acompanhar o movimento do dinheiro, sem reproduzir exatamente o saldo bancário. Extratos são opcionais, mas ele quer exportar/compartilhar um arquivo diretamente para o Tique, sem transformar dados manualmente.

A versão publicada de referência é `v0.10.0-alpha.1`, versionCode 13. Verifique o estado atual do repositório antes de assumir que essa ainda é a última versão. O usuário relatou que o importador funcionou, mas os dashboards não mudaram depois da importação. Também considerou a aba Fontes desnecessária no cotidiano e propôs manter apenas Movimentos e Análises.

Hoje importar gera candidatos pendentes; os gráficos leem somente transações confirmadas do mês selecionado. Isso explica gráficos inalterados enquanto os itens não foram confirmados, mas não prova ausência de um defeito de atualização depois da confirmação. Investigue ambos os caminhos. O relato do usuário não constitui aprovação de todos os testes da versão.

O objetivo desta entrega é tornar **importar → revisar em lote → registrar → visualizar nas análises** um fluxo curto e compreensível, sem sacrificar correção financeira.

## Antes de alterar

- Leia as instruções globais, `/home/heise/.codex/RTK.md`, os `AGENTS.md` aplicáveis e o estado do Git. Preserve alterações existentes.
- Leia os trechos atuais de `docs/continuidade.md`, `docs/adr/0012-uso-diario-e-entrada-de-extratos.md`, `docs/design/tique-interface-proposta.md`, `GLOSSARY.md` e as notas da última release. Documentos históricos não substituem as novas prioridades deste prompt.
- Inspecione o código e confirme hipóteses verificáveis sem perguntar ao usuário. Resolva escolhas rotineiras com seu julgamento. Pergunte apenas diante de ambiguidade que mude o significado financeiro ou coloque dados existentes em risco.
- Não recrie o projeto, não troque a stack nem atualize dependências por conveniência. Se uma API precisar ser pesquisada, consulte documentação oficial atual.

## Primeira entrega obrigatória

### 1. Duas abas para o cotidiano

- **Movimentos** será a tela inicial, inclusive em instalação nova. Ofereça começar manualmente ou importar sem exigir saldo inicial, extrato ou autorização de notificações.
- **Análises** será a segunda e única outra aba principal.
- Mova Fontes para a engrenagem, como **Dados e captura**, com importação, autorização, ativação/pausa, estado da conexão e reconexão. Importar também deve continuar acessível pelo compartilhamento Android; um atalho contextual no botão `+` é aceitável.
- Mantenha uma lista coerente de movimentos, com filtros **Todos**, **A revisar** e **A classificar**, além dos filtros úteis existentes. Diferencie pendência de natureza/valor e ausência apenas de categoria. Uma transação confirmada sem categoria participa dos totais.
- Ao confirmar um candidato, ele deve aparecer como registrado sem uma segunda linha para a mesma movimentação. Uma evidência vinculada a registro existente também não cria outra movimentação.
- Preserve período, filtros e posição da lista entre abas/detalhes, sem enfraquecer a proteção existente ao colocar o app em segundo plano. Troca/restauração do livro precisa invalidar o estado correspondente.
- Compartilhamento recebido durante edição não pode descartar o formulário. Respeite voltar do Android, teclado, áreas seguras, estados vazios e botões ocupados.
- Exiba estado discreto da captura; interrupções relevantes devem oferecer ação para resolver. Não crie uma tela técnica permanente no fluxo normal.

### 2. Importação com prévia e revisão em lote

- Depois da leitura válida, apresente o período do arquivo, quantidade de itens, itens já conhecidos/vinculados e pendências. Use termos claros: “Recebido” ou “A revisar” não significa “Registrado”.
- Ofereça seleção múltipla, edição conjunta de natureza/categoria/tags quando aplicável e **Confirmar selecionados**. Mostrar valor, data e descrição permite revisar sem abrir cada linha.
- Nenhuma seleção ampla pode confirmar natureza apenas pelo sinal positivo/negativo do arquivo. A ação humana deve explicitar a natureza aplicada aos itens selecionados. Itens ambíguos ou conflitantes não entram silenciosamente na seleção pronta para confirmar.
- Valor e data válidos, natureza suportada e ausência de conflito são pré-requisitos para confirmar. Categoria desconhecida pode continuar “A classificar”.
- Transferência própria, aporte, resgate, pagamento de fatura, estorno e reembolso não podem ser reduzidos automaticamente a receita/despesa comum. Enquanto não houver suporte financeiro correspondente, mantenha-os identificados para revisão e fora desses totais, sem apagá-los. Não implemente um núcleo completo de cartões/investimentos para resolver esta entrega.
- Sugestões de vínculo devem permitir associar extrato a transação já registrada por notificação ou manualmente. Valor e dia iguais não provam duplicidade. Não descarte duas compras legítimas iguais.
- Reimportar, tocar duas vezes, retomar após falha ou reprocessar não pode duplicar lançamentos nem perder correções. Resolva concorrência e idempotência no núcleo nativo, não somente desabilitando um botão React.
- Prefira uma operação nativa atômica para o conjunto selecionado: valide antes de gravar e preserve seleção/correções se falhar. Se precisar dividir conjuntos, documente e implemente recuperação por identificadores estáveis, com resultado inequívoco do que foi confirmado. Não apresente sucesso integral após resultado parcial.
- O resultado da operação deve indicar registrados, vinculados e ainda pendentes. Não exponha detalhes internos ou conteúdo financeiro em logs.

### 3. Análises atualizadas e período explícito

- Confirmação individual ou em lote, vínculo, anulação e correção relevante devem atualizar os dados consumidos pelas duas abas. Audite corridas entre refresh, navegação, retorno do fundo e conclusão de comandos nativos.
- Depois da confirmação, ofereça **Ver período importado**. Se o arquivo abranger vários meses, permita escolher um dos meses presentes, sem inventar um filtro de intervalo que o núcleo ainda não suporta.
- Mantenha o período visível e mostre quantas pendências estão fora dos totais. Não afirme que um mês está completo somente porque um arquivo foi importado.
- Os gráficos continuam baseados no livro confirmado, sem misturar valores provisórios. Categorias “A classificar” entram nos totais; natureza incerta não entra.
- Tocar em uma categoria deve abrir os movimentos do mesmo período e filtro. Preserve a opção de ocultar valores.
- Reproduza também o caso de transação já confirmada no mês exibido: se não aparece, corrija a falha real de atualização ou consulta, sem atribuí-la à revisão pendente.

## Ideias para os próximos incrementos

Registre brevemente como backlog, sem implementá-las todas nesta entrega:

1. **Reclassificar registros confirmados**, individualmente e em lote, alterando categoria/tags sem anular e recriar a transação. Manter evidência original e auditoria das correções. Diferenciar mudança de metadados de mudança de valor/natureza/data.
2. **Regras locais reutilizáveis**, oferecidas por escolha explícita ao corrigir uma classificação. Começar por contraparte/estabelecimento e categoria, com prévia de correspondências, edição e desativação. Não transformar uma regra de categoria em prova de valor, natureza ou identidade. Reprocessamento preserva correções humanas.
3. **Análises adicionais**: comparação com o mesmo trecho do mês anterior, maiores categorias/estabelecimentos e valor ainda sem categoria. Indicar cobertura incompleta; ausência de registros não comprova ausência de gastos. Evitar gráficos que repetem a mesma informação.
4. **Tratamento financeiro próprio para transferências e caixinhas**, seguido de cartão/fatura, antes de automatizar esses casos. Pagamento de fatura não repete despesa; resgate não vira salário; aporte não vira consumo.

Referências conceituais: [importação e duplicidades do Actual](https://actualbudget.org/docs/transactions/importing/) e [regras do Actual](https://actualbudget.org/docs/budgeting/rules/). Examine as fontes se precisar basear decisões nelas. Não copie código, assets ou marcas; não adote heurísticas de deduplicação que violem nossas invariantes.

## Restrições de arquitetura, dados e interface

- Preserve Expo/React Native/TypeScript estrito, Kotlin, processamento nativo independente de JavaScript, SQLite/SQLCipher e Keystore. Um livro financeiro único; caixa de evidências separada e consumo idempotente.
- Valores BRL em centavos inteiros com limites seguros. Não use ponto flutuante para cálculos financeiros. Publicação/captura de notificação não é data efetiva da transação.
- Preserve IDs, origens, versão do parser, evidências dos campos, correções, vínculos e dados instalados. Migrações devem funcionar sobre os bancos existentes; mantenha compatibilidade de leitura dos backups anteriores. Se acrescentar dados persistentes, avalie explicitamente inclusão, restauração e limites do backup.
- Não altere package/application ID, aliases de chave ou assinatura para a mudança de navegação. Não peça desinstalação ou limpeza de dados.
- Núcleo local/offline, sem backend, telemetria, credenciais bancárias, APIs privadas, scraping, acessibilidade, SMS ou IA remota/local. APK release continua sem INTERNET. Metro/development build são distintos do APK de uso.
- Nenhum dado real, extrato, chave, backup ou payload financeiro no Git/logs/temporários em claro. Fixtures devem ser explicitamente sintéticas, sem alegar que reproduzem notificações reais do Nubank.
- Preserve demonstração exclusivamente sintética no Expo Go, sem carregar módulo nativo ausente ou oferecer importação real. Não apresente validação web como teste do APK.
- Interface concisa, ícones com acessibilidade e rótulos curtos quando necessários. Preto `#080B10`, âmbar alaranjado `#FFB020`, ciano `#9DECF4`, off-white `#F3EFE6`; formas retas, cantos pequenos. Evite novos dashboards elaborados e dependências visuais desnecessárias.

## Arquivos de entrada para investigação

Na interface: `src/capture/CaptureScreen.tsx`, `FinanceNavigation.tsx`, `SourcesPanel.tsx`, `DailyInbox.tsx`, `CandidatePanel.tsx`, `FinancePanel.tsx`, `FinanceDashboard.tsx`, tipos/contratos e funções puras de dashboard.

No módulo `modules/ffortuna-capture/android/src/main/java/org/ffortuna/capture/`: `CaptureRuntime.kt`, `FinancialLedger.kt`, `ClassificationCatalog.kt`, `FinancialBackup.kt`, `StatementParser.kt`, `StatementStaging.kt`, `StatementIntake.kt`, `CandidateStore.kt`, `DiagnosticStore.kt` e ponte Expo. Investigue dependências antes de editar; esses nomes são pontos de entrada, não uma lista obrigatória de arquivos a modificar.

## Verificação e entrega

- Cubra comportamentos relevantes: importação ainda pendente não altera totais; confirmação em lote altera o mês correto; falta de categoria não exclui transação; repetição e retomada não duplicam; compras iguais legítimas permanecem; vínculo não reposta; conflito não confirma silenciosamente; correções sobrevivem; falha de lote não deixa estado financeiro incompleto.
- Verifique atualização após confirmação com retorno do fundo, arquivos de meses diferentes, persistência, migrações e restauração se afetadas. Preserve testes financeiros e de privacidade existentes.
- Use os scripts existentes: `npm run check`, `npm run native:test` e `npm run native:build -- --compile-device-tests`, conforme a mudança. Node 24 instalado em `/home/heise/.nvm/versions/node/v24.20.0/bin`. Inspecione scripts/ambiente antes de executar; o build existente limita recursos a 12 GiB e 16 CPUs. Não rode builds nativos em paralelo.
- Diferencie testes executados, testes somente compilados e verificações físicas pendentes. O usuário quer usar o app normalmente, sem outra bateria manual obrigatória. Não invente resultados no Samsung nem alegue suporte completo ao Nubank a partir de fixtures.
- Atualize continuidade e notas de uso. Crie ADR somente se houver decisão relevante nova de persistência/semântica; não produza documentação vazia ou excessiva.
- Conclua com commits claros e push conforme a autorização existente. Siga o processo de release já documentado: nova versão/code, mesma assinatura, inspeção offline/backup/permissões, avisos AGPL e terceiros, fonte correspondente, hashes e APK de atualização. Nunca sobrescreva tags ou anexos de versões publicadas. Publique a pré-release apenas após as verificações cabíveis; se algo impedir, registre o bloqueio exato e preserve o trabalho.
- No retorno ao usuário, diga de forma curta o que mudou, o que foi realmente verificado, limitações relevantes e o link do APK quando publicado. Não declare estas propostas prontas antes de implementá-las.
