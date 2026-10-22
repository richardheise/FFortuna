# Testar a demonstração no Android

O usuário informou em 22/10/2026 que testou e aprovou a demonstração no celular, sem detalhar aparelho ou cada etapa. A correção posterior das dependências ainda precisa de instalação e conferência.

Este roteiro usa exclusivamente valores inventados. Ele é um roteiro a executar no aparelho, não um relato de teste físico já realizado. A entrega roda dentro do Expo Go; ainda não existe APK próprio do FFortuna.

## Preparar

No computador, use Node 24 e execute:

```sh
cd /home/heise/OpenMoney
nvm use
npm ci
npm start -- --lan
```

Instale Expo Go compatível com SDK 57 no Android, mantenha computador e celular na mesma rede e escaneie o QR. Não é necessário criar conta Expo. Se houver incompatibilidade de SDK, consulte [Expo Go para Android](https://expo.dev/go). Não atualize pacotes isoladamente para tentar corrigir isso.

Se a conexão LAN falhar, confira isolamento da rede Wi-Fi, VPN e acesso à porta exibida pelo Metro (normalmente 8081). Metro precisa permanecer ativo para servir/recarregar esta versão de desenvolvimento. Isso é distinto do futuro APK próprio, que deverá funcionar sem computador.

Se o servidor está remoto e você está por SSH, use `npm start -- --tunnel`. O CLI pode solicitar o pacote ngrok; a instalação global é `npm install --global @expo/ngrok`. Servidor e celular precisam de internet, mas não da mesma rede. O túnel é um serviço externo do ambiente de desenvolvimento.

O badge **DEMONSTRAÇÃO · Só dados fictícios** deve ficar visível. Não deve haver pedido de acesso a notificações, login bancário ou seleção de extrato pessoal.

## Percurso principal

Comece em um banco de demonstração novo. Use a data de hoje em todos os lançamentos deste percurso e selecione o mês atual no topo. Não repita botões de simulação exceto quando indicado: cada clique cria uma observação nova.

| Ação | Resultado esperado |
| --- | --- |
| Em Resumo, informe saldo inicial `1.000,00`, abertura hoje e renda prevista `3.000,00`. Toque Criar meu resumo. | Saldo R$ 1.000,00; renda prevista R$ 3.000,00; receitas e despesas zero. |
| Em Novo lançamento, escolha Receita, valor `500,00`, descrição Renda fictícia e categoria Renda. Salve. | Saldo R$ 1.500,00; receitas R$ 500,00. |
| Cadastre Despesa de `100,00`, descrição Mercado fictício e categoria Alimentação. | Saldo R$ 1.400,00; despesas R$ 100,00; categoria Alimentação R$ 100,00. |
| Em Revisão, toque Simular seguro uma vez. | Café fictício de R$ 19,90 lançado automaticamente; saldo R$ 1.380,10. |
| Toque Simular revisão. | Candidato pendente sem efeito no saldo. Valor e natureza não vêm confirmados. |
| Escolha Despesa, valor `15,00`, descrição Café corrigido e categoria Lazer. Confirme. | Saldo R$ 1.365,10; despesas R$ 134,90; caixa de entrada sem esse pendente. |
| Em Histórico, abra Ver evidência da origem do café corrigido. | Conteúdo marcado sintético, extração e decisão humana distintos. |
| Em Revisão, Últimos processados, toque Reprocessar no evento corrigido. | Mesmo saldo e um único lançamento; correções preservadas. |
| No mesmo evento, toque Atualizar evento fictício. | Conflito na caixa de entrada; o lançamento corrigido continua em R$ 15,00. Toque Manter lançamento existente. Saldo inalterado. |
| Toque Simular compra repetida duas vezes. | Duas compras legítimas adicionais de R$ 19,90, com identidades distintas; saldo R$ 1.325,30. |

Para conferir o filtro, abra outro mês no topo: receitas/despesas/histórico devem seguir o período; **saldo atual** continua acumulado. Um mês inválido deve apresentar mensagem, sem alterar o mês consultado.

## Caixinhas e crédito comprometido

Continue o percurso anterior, no mês atual:

| Ação em Meu dinheiro | Resultado esperado |
| --- | --- |
| Nova caixinha: nome Reserva fictícia, modalidade CDB fictício, posição inicial `200,00`, data hoje. | Conta continua R$ 1.325,30; caixinha R$ 200,00. É uma posição já existente. |
| Na caixinha, Ação → Guardar / resgatar → Guardar `50,00`. | Conta R$ 1.275,30; caixinha R$ 250,00; gastos inalterados. |
| Resgate `20,00`. | Conta R$ 1.295,30; caixinha R$ 230,00; receitas inalteradas. |
| Atualizar valor: novo valor `240,00`, motivo Conferência fictícia, data hoje. | Prévia de diferença +R$ 10,00; caixinha R$ 240,00; conta inalterada; sem presumir rendimento/receita. |
| Nova dívida: Cartão fictício, vencimento hoje ou futuro, valor `300,00`. | Dívida R$ 300,00; conta e despesas inalteradas. |
| Registre pagamento parcial de `100,00`, data hoje. | Conta R$ 1.195,30; dívida restante R$ 200,00; despesas inalteradas. |
| Reabra o pagamento e quite os `200,00` restantes. | Conta R$ 995,30; dívida zero; sem nova despesa. |

## Importação sintética e sobreposição

Em Revisão, Importação de demonstração, toque **Atual**. A prévia deve ter uma receita de R$ 500,00 e uma despesa de R$ 50,00. Revise natureza, categoria, descrição e data; o sinal do OFX é sugestão e não autorização para lançamento automático.

No percurso acima, as duas linhas podem aparecer como **Ambíguo**: a receita de R$ 500,00 coincide com a receita manual, e a despesa de R$ 50,00 coincide com um aporte na mesma data. Isso não prova duplicidade. Para este roteiro, trate os exemplos como movimentos diferentes e selecione explicitamente **Criar lançamento revisado** em cada linha ambígua. Não vincule a despesa ao aporte. Linhas ambíguas começam ignoradas até sua escolha; num caso real, seria necessário conferir se representam o mesmo movimento.

Confirme: saldo esperado **R$ 1.445,30**. Toque Atual novamente: as duas linhas devem aparecer vinculadas; confirmar novamente mantém o saldo. Toque **Com sobreposição**: duas linhas já vinculadas e uma nova despesa de R$ 25,00. Confirme apenas a nova: saldo **R$ 1.420,30**. A decisão e os metadados anteriores devem permanecer. Execute este trecho no mesmo dia: as identidades das fixtures são estáveis para a data de referência.

Para histórico anterior, toque **Histórico**. São dois movimentos anteriores à abertura de hoje: +R$ 500,00 e −R$ 300,00. A nova abertura precisa ser **R$ 800,00**, antes da primeira linha, pois R$ 800 + R$ 200 = posição inicial conhecida de R$ 1.000. Confirme a ciência do saldo anterior às linhas e importe. O saldo atual deve continuar **R$ 1.420,30**. Uma abertura incoerente deve bloquear a operação inteira, sem importar parcialmente. Esse exemplo usa a abertura original de R$ 1.000,00; outros valores exigem sua própria conferência.

## Persistência e conclusão

Feche completamente o Expo Go, reabra o projeto com o Metro ativo e confira saldo, caixinhas, dívida, histórico e vínculos. Isso testa a integração real de persistência no aparelho. Recarregar o bundle não deve apagar o banco; limpar os dados do Expo Go ou desinstalá-lo pode apagá-lo. Não há backup nesta demonstração.

Abra **Sobre e licenças · AGPLv3**, consulte AGPLv3 e Bibliotecas e feche. Nenhuma consulta financeira ou de licenças deve exigir backend do FFortuna.

Registre modelo do aparelho, versão Android/Expo Go, ações feitas, esperado e observado. Use somente prints sintéticos. O relato de aprovação não documenta verificações individuais; registrar na próxima rodada: layout/teclado, execução do expo-sqlite no Expo Go, persistência após encerramento e compatibilidade com o Expo Go instalado.

## O que já foi verificado no computador

- `npm run check`: TypeScript estrito e 30 testes aprovados na cópia isolada (incluindo 3 testes de segurança/compatibilidade de URLs), com SQLite real via Node, reabertura de arquivo e migração.
- `npx expo install --check`: conjunto de dependências compatível com SDK 57.
- `npm run export:android`: bundle Android/Hermes gerado; não produz APK nem comprova execução Android.
- Percurso adicional da interface no Chromium/RN Web com serviço real e SQLite em memória: formulários, saldo, correções, reprocessamento, conflito, compras iguais, caixinhas, dívida, sobreposição e licenças. Esse teste não cobre runtime Android.

A captura nativa, banco real cifrado, pacote Nubank, conteúdo real de notificações e arquivos Nubank não foram implementados/validados nesta etapa. O próximo incremento é a prova nativa local no celular, começando com dados sintéticos e persistência cifrada.
