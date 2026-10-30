# FFortuna — Finanças pessoais

Vocabulário do aplicativo para distinguir fatos observados, decisões do usuário e seus efeitos financeiros.

## Linguagem

**Conta financeira**:
Local em que o usuário mantém dinheiro, como uma conta bancária ou dinheiro em espécie.
_Evitar_: conta de acesso, cartão

**Evento observado**:
Registro de uma observação recebida de uma fonte, como uma notificação ou uma simulação. A observação não comprova, por si só, uma movimentação financeira.
_Evitar_: transação confirmada, extrato completo

**Evidência de origem**:
Informação preservada sobre a fonte e o conteúdo de uma observação que fundamenta uma interpretação financeira.
_Evitar_: lançamento

**Resultado de parsing**:
Interpretação dos campos de uma evidência, com indicação de sua procedência e das incertezas de extração.
_Evitar_: confirmação, correção do usuário

**Candidato a transação**:
Proposta de movimentação que ainda aguarda revisão e não altera o saldo.
_Evitar_: transação realizada, lançamento confirmado

**Transação confirmada**:
Registro financeiro aceito pelo usuário ou por critérios explícitos de automação, com efeito no livro financeiro.
_Evitar_: notificação, candidato

**Metadados do usuário**:
Informações acrescentadas pelo usuário, como categoria, tags e notas.
_Evitar_: conteúdo original da evidência

**Correção do usuário**:
Alteração consciente de um campo proposto ou registrado, preservada como decisão distinta da interpretação original.
_Evitar_: nova evidência bancária

**Horário de publicação**:
Momento em que a fonte publicou a observação.
_Evitar_: horário efetivo

**Horário de captura**:
Momento em que o aplicativo recebeu a observação.
_Evitar_: horário de publicação, horário efetivo

**Horário efetivo**:
Momento atribuído à movimentação financeira, quando conhecido.
_Evitar_: horário de notificação

**Natureza financeira**:
Significado econômico de uma movimentação, como receita, despesa ou transferência.
_Evitar_: Pix, categoria, meio de pagamento

**Meio de pagamento**:
Forma de movimentar ou pagar um valor, como Pix ou cartão.
_Evitar_: natureza financeira

**Vínculo de reconciliação**:
Relação que identifica registros de origens diferentes como evidências da mesma movimentação financeira.
_Evitar_: exclusão de duplicatas, igualdade de valor e dia

**Compromisso previsto**:
Movimentação esperada que ainda não constitui uma transação realizada.
_Evitar_: saldo disponível, transação confirmada

**Renda prevista**:
Valor que o usuário espera receber em um período, distinto dos recebimentos já realizados.
_Evitar_: saldo, receita recebida

**Receita recebida**:
Ingresso de renda efetivamente realizado, distinto de transferências entre recursos do próprio usuário.
_Evitar_: renda prevista, resgate

**Caixinha**:
Organização de dinheiro guardado para um objetivo, distinta da conta de uso diário e dos produtos financeiros em que o valor está aplicado.
_Evitar_: despesa, modalidade de investimento

**Modalidade de investimento**:
Tipo de aplicação financeira associado a uma parcela do dinheiro guardado.
_Evitar_: nome da caixinha, categoria de despesa

**Livro financeiro**:
Conjunto dos registros que representam os efeitos financeiros das transações confirmadas e suas contrapartidas.
_Evitar_: histórico de notificações, extrato bancário

**Contrapartida**:
Registro complementar que explica a origem ou o destino de um valor em uma movimentação equilibrada.
_Evitar_: outra transação independente

**Posição informada**:
Valor de um recurso financeiro declarado pelo usuário em uma data de referência, sem presumir o histórico das movimentações que o produziram.
_Evitar_: rendimento apurado, dinheiro disponível para resgate

**Saldo de abertura**:
Valor existente em uma conta na data escolhida para iniciar o acompanhamento, distinto das receitas e despesas ocorridas depois desse marco.
_Evitar_: receita inicial, salário

**Compromisso inicial do cartão**:
Valor que já estava por pagar no cartão ao iniciar o acompanhamento, associado a um vencimento e sem presumir os detalhes das compras anteriores.
_Evitar_: gasto novo, compra parcelada identificada, fatura fechada verificada

**Lote de importação**:
Conjunto de registros provenientes de um carregamento manual de arquivo, com origem e período identificados para conferência.
_Evitar_: extrato completo garantido, transações já confirmadas

**Aporte na caixinha**:
Movimentação de dinheiro da conta de uso diário para uma aplicação associada a uma caixinha.
_Evitar_: despesa, consumo

**Resgate da caixinha**:
Movimentação de dinheiro de uma aplicação associada a uma caixinha para a conta de uso diário, cuja composição pode incluir principal, rendimento e descontos.
_Evitar_: receita comum, salário

**Ajuste de posição**:
Diferença explicitamente registrada para alinhar um recurso à posição informada pelo usuário, sem atribuir automaticamente sua causa a rendimento, receita ou despesa.
_Evitar_: rendimento apurado, correção silenciosa de saldo

**Lançamento automático**:
Confirmação de uma transação por critérios explícitos de segurança dos campos financeiros e da identidade da observação, sem exigir ação individual do usuário.
_Evitar_: completude do extrato, categoria necessariamente certa

**Backup portátil**:
Arquivo cifrado com senha independente da chave local, que permite restaurar registros em outra instalação. Na prova nativa, preserva observações e parsing sintéticos, sem payloads brutos.
_Evitar_: backup automático do Android, cópia da chave do Keystore

**Caixa anterior**:
Caixa cifrada preservada ao ativar uma restauração. Permanece inativa e não é mesclada à caixa ativa; pode ser reativada se sua chave local estiver disponível ou descartada explicitamente.
_Evitar_: segundo livro financeiro, backup portátil

**Recuperação local automática**:
Até três cópias cifradas privadas dos registros e parsing, sem bruto, criadas pelo núcleo em dias de uso com alterações, no máximo uma por 24 horas. Permitem restaurar pela interface e dependem da instalação e de sua chave local.
_Evitar_: backup portátil, proteção contra desinstalação

**Retenção de bruto**:
Prazo para remover somente os payloads originais das evidências, preservando os registros e resultados de parsing. Na prova, são 30 dias desde a captura, aplicados quando o núcleo executa também à caixa anterior.
_Evitar_: apagar transações, prazo garantido com aplicativo encerrado

**Diagnóstico temporário de notificações**:
Coleta local com consentimento explícito e prazo limitado, para examinar a evidência realmente entregue pelo Android. Preserva dados numa caixa cifrada separada e não interpreta nem lança transações.
_Evitar_: captura bancária validada, extrato completo, acesso à conta bancária

**Amostra revisada**:
Campos textuais selecionados de uma observação de diagnóstico, com sugestões de remoção de dados pessoais e confirmação manual antes da exportação cifrada. As regras automáticas não garantem anonimização de texto livre.
_Evitar_: payload bruto, transação confirmada, anonimização garantida

**Prévia temporária de parsing**:
Interpretação revisável do original de uma observação de diagnóstico, com versão e evidências por campo. Não constitui transação, não altera saldo e expira com a observação do diagnóstico.
_Evitar_: lançamento confirmado, histórico financeiro definitivo, cópia anonimizada

**Correção da prévia**:
Campos salvos pelo usuário em uma área separada do resultado do parser. Têm precedência na interface e permanecem após reprocessar a mesma observação, sem alterar o original.
_Evitar_: texto original, nova notificação, confirmação de transação
