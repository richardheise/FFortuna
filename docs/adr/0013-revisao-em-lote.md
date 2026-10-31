# Revisão explícita em lote e duas abas

Decisão de 31/10/2026, para a entrega solicitada em `docs/prompt-sol-uso-diario.md`.

Movimentos e Análises são os destinos diários. Dados e captura fica nas configurações, mantendo o compartilhamento Android e a importação opcionais.

Receber um extrato continua criando evidências e candidatos, sem confirmar a natureza pelo sinal. Uma seleção manual com natureza explícita permite confirmar vários candidatos, usando as mesmas validações e proveniência da confirmação individual. Categoria desconhecida não bloqueia o registro. Conflitos e possíveis vínculos exigem atenção individual. Tipos ainda sem representação financeira permanecem pendentes.

O núcleo nativo serializa os comandos e grava o lote no mesmo livro com uma transação. Repetições usam a identidade de cada evento. Falha não deve deixar parte dos lançamentos gravada. Não se cria um segundo livro nem se altera a política de backup: registros confirmados e vínculos entram no backup financeiro; staging permanece local.

Análises consultam apenas registros confirmados e exibem o mês. Depois de confirmar, a interface oferece os meses presentes no conjunto, sem somar candidatos aos totais. Nenhum período é considerado completo automaticamente.

Alternativas rejeitadas: confirmar todo arquivo pelo sinal distorceria renda e consumo; confirmar linha por linha impede uso conveniente; executar vários comandos independentes sem indicar resultados parciais arriscaria repetir trabalho após falhas. A transação em lote exige limites e validação integral, em troca de recuperação e resultado claros.

Regras reutilizáveis, edição de metadados de confirmados, novas comparações e suporte a caixinhas/cartões permanecem no backlog do prompt. Não são pré-requisitos desta entrega.
