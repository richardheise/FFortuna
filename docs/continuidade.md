# Retomada — 22/10/2026

Sessão encerrada a pedido do usuário. O usuário aprovou a demonstração no celular e definiu: priorizar funcionalidades, em incrementos pequenos, antes do refinamento de interface/UI/UX.

## Onde paramos

- FFortuna: demonstração Android no Expo Go, SDK 57, exclusivamente sintética. Conta, receitas/despesas, resumo mensal, caixinhas, compromissos do cartão, captura simulada, revisão/evidências, reprocessamento e três importações OFX sintéticas implementados.
- O usuário começou a preparar/testar por SSH. Foi orientado a usar `npm start -- --tunnel`, com Expo Go Android compatível. O usuário informou que olhou a implementação no celular e aprovou; não detalhou aparelho/Android nem o percurso executado.
- Ainda não existe APK próprio, captura Kotlin, acesso ao Nubank, importação de arquivos pessoais ou banco real cifrado. Não usar dados financeiros reais nos formulários.
- Correção das dependências preparada no código e lockfile: decoder seguro 0.5.0 com ponte CommonJS; autorização restrita do postinstall do esbuild 0.28.2.
- **A instalação ativa não foi alterada** para preservar o teste do usuário. Será necessário parar o Metro e executar `npm ci` ao retomar. O banco da demonstração no celular não foi tocado.
- Audit reduzido de 21 para 18 alertas, todos altos, causados por duas falhas upstream ainda sem patch. Detalhes, fontes e comandos em [dependencias.md](dependencias.md).

## Evidência disponível

- Demonstração original: TypeScript, 27 testes, bundle Android e percurso de interface em Chromium aprovados. Não equivale a teste Android.
- Correção das dependências: cópia isolada com instalação limpa, TypeScript, 30 testes, compatibilidade de pacotes Expo e bundle Android aprovados. Interoperabilidade da ponte também verificada em bundle JavaScript Android do Metro executado em VM Node.
- Aprovação de uso no celular foi relatada pelo usuário. Compatibilidade da correção posterior de dependências, persistência após encerramento e cada critério do roteiro não foram documentados individualmente; não afirmar esses resultados por inferência.
- Licença AGPL-3.0-only. Nenhum código/assets das referências Finan+/PennyWise foi copiado. Avisos de dependências foram regenerados; continuam 53 lacunas de textos a resolver antes de distribuir APK.

## Primeira ação na próxima sessão

Após encerrar o Metro anterior, no workspace:

```sh
cd /home/heise/FFortuna
nvm use
npm ci
npm run check
npm start -- --tunnel
```

O túnel usa serviço externo para servir o ambiente de desenvolvimento; o banco fica no aparelho. Se o pacote ngrok ainda não estiver instalado no Node ativo, seguir a orientação do CLI. O fluxo e os saldos esperados estão em [teste-no-celular.md](teste-no-celular.md). Registrar modelo/Android/Expo Go e o resultado, sem alegar execução antes disso.

Com a demonstração aprovada, o próximo incremento acordado é a prova nativa local: preparar SDK/JDK, gerar build Android próprio, capturar notificações sintéticas com NotificationListenerService e persistir de forma cifrada sem depender do JavaScript. Amostras reais continuam bloqueadas até completar as condições de segurança do [alinhamento aceito](alinhamento-inicial.md).

## Git e entrega

O primeiro commit em `main`, `913faa0`, reúne o código e a documentação desta entrega. Em 23/10/2026, o usuário informou o remoto `https://github.com/richardheise/FFortuna.git`, configurado como `origin`, e o workspace passou a ser `/home/heise/FFortuna`. O destino da publicação é `origin/main`; a confirmação final deve refletir o resultado efetivo do push. Não criar outro repositório ou alterar sua visibilidade por suposição.

Os temporários de validação não são necessários para executar os comandos acima; nenhuma dependência do projeto aponta para `/tmp`.
