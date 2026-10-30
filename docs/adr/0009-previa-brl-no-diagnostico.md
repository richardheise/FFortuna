# Prévia BRL temporária no diagnóstico

Incremento 0.6 autorizado pelo usuário em 30/10/2026. Não há amostras reais ou revisadas disponíveis neste ambiente; portanto, esta etapa não inventa regras de notificações Nubank. O parser é genérico e limitado a evidências textuais de valores BRL.

`notification-brl/1` reconhece somente valores com marcador `R$` ou `BRL`, vírgula decimal seguida por exatamente dois dígitos e, opcionalmente, grupos de milhar separados por ponto. Espaços Unicode podem separar o marcador do número, mas não agrupam os dígitos. O valor é calculado em centavos inteiros, limitado a `9.007.199.254.740.991`, máximo inteiro exato do JavaScript. Não há conversão por ponto flutuante. Valores sem marcador, múltiplos ou inválidos ficam sem escolha. Incompletos, negativos, zero, truncados, agrupamentos inválidos ou acima do limite também não são escolhidos. Evidências preservam o trecho original exato, o campo e offsets UTF-16, junto à versão do parser.

O parser não infere natureza, conta ou data efetiva. Data de publicação não equivale à data efetiva. Nenhum valor, mesmo quando sua extração é consistente e única, cria lançamento automaticamente. Valores nulos permanecem desconhecidos. A natureza `other` ou `transfer` não é convertida em receita nem altera saldo.

`CandidateStore` usa a conexão e o executor serial do `DiagnosticStore`. A migração transacional do schema 1 para 2 cria tabelas separadas de parsing e edição; preserva originais, chave e revisões de exportação existentes e desativa a sessão antiga, pois o consentimento 0.5 não cobria parsing automático. Novas observações só são parseadas após commit durável e com consentimento explícito vigente. Falha no parsing não apaga a notificação nem impede a coleta. Registros antigos só são parseados após ação explícita “Interpretar”. Há uma prévia por ID de observação/revisão. Reprocessar substitui somente a linha do parser; correções do usuário ficam em tabela separada e são preservadas.

Uma nova revisão de notificação pode representar atualização ou uma operação financeira distinta. O vínculo entre revisões é apresentado para revisão humana; não equivale a deduplicação financeira. Revisão local pode ser parcial ou descartada. Nem revisão confirmada nem estado `dismissed` são transação confirmada.

Prévia e edição são dados temporários do diagnóstico, não livro financeiro nem balanço. Expiram em até sete dias junto com a observação de origem, via chaves estrangeiras em cascata. Não entram em backups, arquivos exportados ou postagem remota. A prévia usa o original; não reutiliza a máscara sintética `R$ 12,34` usada em sugestões de redação da exportação revisada.

A captura real continua protegida pela bateria nativa aprovada exigida no escopo novo de 48 verificações, `isolated-native-checks/v3`. A aprovação anterior de 40 verificações pertence a outro escopo e não libera esta coleta. Não se afirma que as novas verificações foram executadas no aparelho.

Alternativas rejeitadas: esperar por amostras antes de implementar qualquer leitura genérica impediria testar a extração de valores explicitamente marcados; adivinhar palavras-chave ou templates Nubank sem amostras inventaria comportamento; criar agora um ledger completo misturaria diagnóstico com processamento financeiro. Templates validados e migração para o livro financeiro ficam para outra fase, com evidência e desenho próprios. Não há nova dependência; a implementação usa APIs e bibliotecas já presentes.

Fontes: [Unicode CLDR, português](https://www.unicode.org/cldr/charts/latest/summary/pt.html), [ECMAScript `Number.MAX_SAFE_INTEGER`](https://tc39.es/ecma262/#sec-number.max_safe_integer) e [SQLite Foreign Key Support](https://www.sqlite.org/foreignkeys.html).
