# Prévia de valores BRL — roteiro 0.6.0

Este roteiro descreve a versão de conteúdo 0.6.0, incluída no build de marca Tique 0.6.1 (versionCode 7). A nova marca preserva o application ID e os dados do app anterior para permitir atualização. O build 0.6.1 ainda não foi publicado nem validado no aparelho.

A 0.6.0 interpreta o texto original de uma observação em uma prévia revisável, dentro do diagnóstico cifrado. Não há amostras revisadas disponíveis neste ambiente para fundamentar templates do Nubank. O parser `notification-brl/1` é genérico: reconhece valores explicitamente marcados por `R$` ou `BRL`, com vírgula e dois decimais, sem adivinhar a movimentação.

Exemplos deste documento e dos testes são inventados; não representam frases reais do Nubank. `R$ 99,90` é convertido em 9990 centavos inteiros. Milhares com ponto e espaço Unicode entre marcador/número são aceitos. Números sem marcador, formatos incompletos/estrangeiros, negativos, zero e valores acima de 9.007.199.254.740.991 centavos não são escolhidos. Mais de uma possibilidade, repetição no mesmo campo ou truncamento deixam o valor desconhecido; repetições idênticas em campos distintos podem representar o mesmo texto expandido. A evidência mostra o trecho original e o campo que sustentaram a interpretação.

Natureza, conta e data efetiva não são inferidas. Uma mensagem com um preço também pode ser oferta ou saldo; encontrar BRL não prova uma transação. Pix é meio de pagamento, não receita/despesa. Transferência entre recursos próprios, pagamento de fatura, estorno, aporte ou reembolso não devem ser classificados como receita/despesa comum. A interface permite deixar a natureza indefinida ou usar “Outra” nesta prévia; nenhum desses campos muda saldo.

## Testar no A36

1. Atualize por cima da 0.5.0, sem desinstalar. A migração preserva os originais, a chave e as revisões de exportação; pausa a sessão antiga porque o consentimento anterior não cobria interpretação automática. Em **Configurações avançadas**, execute as **48 verificações nativas isoladas**. Informe somente contadores/códigos. Os contadores da versão anterior não substituem os oito cenários novos.
2. Em uma observação individual já existente, toque **Interpretar original**. Deve mostrar o texto com o valor real, versão do parser, trecho de evidência, motivo e valor candidato. Se houver várias possibilidades ou formato não reconhecido, o campo fica vazio para revisão. Resumos de grupo não oferecem essa ação.
3. Confira que natureza e data efetiva começam indefinidas. Corrija valor, natureza, descrição e categoria; informe data efetiva somente se souber. **Salvar revisão** guarda esses campos cifrados e separados do parser. É permitido deixar campos desconhecidos em branco: isso não confirma transação. Você também pode marcar a candidatura como descartada, preservando o original.
4. Toque **Reprocessar** depois de salvar. O resultado bruto do parser continua visível, mas sua correção deve permanecer no formulário. Não deve aparecer outro candidato para a mesma observação. Feche/reabra o painel e o app: a revisão salva permanece durante a retenção. Edição ainda não salva impede reprocessar; sair para segundo plano limpa a edição da interface.
5. Caso surja outra observação com a mesma identidade Android, a interface mostra a relação e preserva a revisão anterior. Isso pode ser atualização ou outra movimentação legítima; o app não conclui que sejam a mesma transação nem soma/deduplica financeiramente. Observações iguais com identidades próprias permanecem separadas.
6. Para novas notificações, consinta novamente com a sessão após as 48 verificações. O novo aviso informa a interpretação preliminar. Aguarde uma notificação do uso normal, inclusive com interface fechada/tela bloqueada. Ao voltar, a lista deve indicar quando a prévia foi interpretada; o processamento ocorre em Kotlin após gravar a evidência, sem depender de JavaScript aberto. Use **Interpretar original** para conferir e corrigir.
7. Confira a separação: **Preparar cópia para exportação** ainda substitui valores reconhecidos por `R$ 12,34`, enquanto a prévia do parser usa o original. Nem a prévia nem suas correções entram nessa exportação ou nos backups sintéticos. Não é necessário repetir todo o roteiro de senha/backup 0.5.0, já aprovado, salvo se encontrar uma falha nova.

**As prévias e correções desta etapa são temporárias: expiram junto com a observação de diagnóstico após 7 dias da captura, quando o núcleo executar. Não são um histórico financeiro definitivo.** Não use a revisão como único registro da sua vida financeira. Não criamos um segundo livro ou colocamos dados reais no banco Expo Go. O próximo passo depende de validar estas interpretações e de obter fixtures sintéticas fundamentadas em amostras revisadas para regras específicas, antes do livro financeiro real.

## Verificação local e limites

```sh
nvm use
npm ci
npm run check
npm run native:build -- --compile-device-tests
```

O build continua limitado a 12 GiB e até 16 CPUs. Não há dependência nova, serviço remoto ou permissão nova. APK offline sem INTERNET, com a mesma assinatura e dados SQLCipher/Keystore. Expo Go mantém a demonstração sintética e não executa esse módulo.

Testes relevantes incluem limites monetários, evidência UTF-16, valores incompletos/ambíguos, original versus máscara, migração 1→2 preservando chave/dados, reabertura, correções após reprocessar, atualizações e limpeza em cascata. Testes JVM rodam no ambiente de build; os cenários Android compilam e compartilham callbacks com a bateria da interface, mas o runner JUnit não foi executado neste servidor. Não declarar parsing Nubank validado nem captura completa. Nunca envie originais, senhas ou informações financeiras nesta conversa.

Decisão e alternativas: [ADR 0009](adr/0009-previa-brl-no-diagnostico.md). Política de coleta/exportação: [diagnóstico local](diagnostico-local.md).
