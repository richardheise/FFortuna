# Recursos financeiros e classificação na versão 1.0

Decisão de 31/10/2026, após o usuário autorizar os incrementos pendentes e a publicação da versão 1.0.

O livro cifrado permanece único. O esquema 4 estende o esquema 3 com recursos (caixinhas e cartões), compromissos/faturas e um diário equilibrado de movimentos patrimoniais. Os registros anteriores não são reescritos. Compras no cartão entram nas despesas pela data da compra; pagamento de fatura reduz caixa e dívida sem repetir a despesa. Dívida anterior é posição inicial, não consumo novo. Aporte/resgate e transferência entre caixinhas são movimentos internos, fora de receita/despesa. Posições informadas não são consulta ao Nubank nem garantia de saldo ou patrimônio completos.

Cartões têm fechamento/vencimento configuráveis; a primeira implementação limita dias a 1–28 para evitar datas inexistentes. Compra até o fechamento usa a fatura do mesmo mês; posterior usa a seguinte. O usuário deve conferir o ciclo praticado pelo banco. Pagamentos parciais preservam a dívida restante. Não há parcelamento automático, juros automáticos, cotações externas, contas bancárias múltiplas arbitrárias ou estorno automático. Movimentos estendidos ainda não oferecem anulação pela interface; não usar uma receita/despesa fictícia para compensá-los. Tais limites precisam constar nas notas de uso.

Reclassificação altera somente categoria/tags dos confirmados, com auditoria antes/depois. Não altera valor, natureza, data, proveniência ou pedido original idempotente. Regras locais usam descrição normalizada exata, podendo ser editadas e desativadas; aplicação a histórico exige prévia e confirmação. Regras não provam natureza, valor ou identidade financeira. Correções humanas prevalecem.

Backup financeiro versão 3 inclui recursos, compromissos, diário estendido, regras e auditoria. Leitura dos formatos 1 e 2 permanece; restauração mantém troca validada de caixa, sem mesclar dados. Novos formatos não são prometidos como restauráveis em APK antigo.

A interface inicia em Análises, seguida de Movimentos. Space Grotesk é incluída localmente sob OFL-1.1, com textos curtos nas tarefas e detalhes em Sobre o Tique. Comparações usam o mesmo trecho do mês atual e o mês anterior, sem assumir cobertura completa. Metas e previsões continuam futuras.

Alternativas rejeitadas: tratar todo débito como despesa duplicaria faturas/aportes; segundo livro exigiria reconciliação interna adicional; regravar pedidos originais ao reclassificar quebraria replay. A extensão mantém compatibilidade à custa de validação cruzada de recursos, compromissos e auditoria. A versão 1.0 identifica esta entrega; não constitui certificação de compatibilidade bancária ou declaração de testes físicos não executados.
