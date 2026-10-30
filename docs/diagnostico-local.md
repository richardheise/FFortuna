# Diagnóstico local — roteiro 0.5.0

A partir da 0.6.0, veja o [roteiro de prévias BRL](previa-brl.md): a migração pausa o consentimento antigo e a nova coleta requer 48 verificações. O texto abaixo registra a entrega original 0.5.0, já aprovada funcionalmente pelo usuário; a revisão de exportação continua separada da prévia de valores reais.

Esta entrega permite coletar amostras reais somente para desenvolver/verificar parsers depois. Não interpreta movimentações, não cria transações nem calcula saldo. O livro financeiro no Expo Go e os exemplos da prova continuam exclusivamente sintéticos. O suporte de parsing Nubank permanece não validado.

## Política padrão

A instalação/atualização começa sem consentimento para a coleta real. Nas configurações avançadas, o usuário confere o aplicativo encontrado no aparelho, seu pacote `com.nu.production`, versão e assinatura local, e escolhe iniciar. A assinatura registra a origem instalada e permite detectar mudança; não é uma certificação do publicador. A consulta de pacotes é restrita a essa origem, sem listar todos os aplicativos. Não há nova permissão de acesso amplo, SMS, acessibilidade ou INTERNET.

Cada sessão dura até 24 horas, usando relógios de parede e monotônico. Reinício do celular, indisponibilidade da origem, alteração da assinatura, limite ou revogação do acesso param a coleta. Alterar o relógio para antes do início também invalida a sessão; atrasá-lo não estende o limite monotônico. Matar/recriar apenas o processo pode continuar a sessão no mesmo boot, sem JavaScript ativo. Force-stop pode impedir a captura até reabrir o app; não se promete recuperar notificações perdidas.

São no máximo 200 observações na caixa retida e 20 revisões selecionadas. Notificações precisam ter publicação posterior ao início; não há coleta retroativa do histórico. A origem, consentimento, prazo e assinatura são verificados antes do texto. O diagnóstico permite todo texto de notificação dessa origem durante a janela, inclusive mensagens não financeiras; ainda não existe parser confiável para filtrar movimentações. Não são lidas notificações de outros aplicativos.

A caixa SQLCipher tem chave própria encapsulada pelo Keystore. Preserva publicação, captura, identidade, revisões e campos textuais permitidos: título, texto, texto expandido, subtexto e linhas. São no máximo 4096 caracteres por campo, 32 linhas e 16 mil caracteres no conjunto. Imagens, ações, objetos completos, RemoteViews e extras arbitrários não são serializados. Resumos de grupo guardam só metadados. Texto vazio pode refletir o que Android entregou; não é prova de ausência da notificação nem autorização para contornar a proteção do sistema.

Originais e revisões expiram após 7 dias da captura, quando o núcleo executar. A limpeza abrange apenas essa caixa. “Parar” mantém registros; “Apagar registros temporários” remove caixa/chave/revisões do diagnóstico e não altera exemplos, cópias locais ou backups sintéticos. Não há backup automático do bruto diagnóstico; sua perda não modifica um livro financeiro. Cópias internas/reinstalação não restauram essas amostras. Os arquivos exportados fora do app ficam sob controle do usuário e não são apagados pela retenção interna.

## Testar no A36

1. Atualize por cima da 0.4.0, sem desinstalar. Abra **Configurações avançadas** e execute **Executar verificações nativas isoladas** antes de ativar coleta real. O início da sessão é bloqueado até a bateria desta versão passar no processo atual; iniciar outra sessão depois de encerrar o processo exige nova bateria. Uma sessão já ativa pode continuar dentro da janela original. A bateria 0.5.0 tem **40 verificações**, incluindo dez cenários novos com fixtures inventadas. Informe contadores/IDs/códigos se houver falha ou não verificado. Os 30 resultados aprovados na 0.4.0 não substituem os novos dez cenários.
2. Em **Diagnóstico temporário de notificações**, confira origem instalada, pacote, versão, autorização Android e conexão. A coleta deve estar parada antes do consentimento. Se o aplicativo estiver em outro perfil/clonado ou não for encontrado, informe isso; não tente habilitar outro pacote manualmente.
3. Toque **Consentir e iniciar até 24 horas** e leia o aviso. Uma indicação de diagnóstico ativo aparece também fora das opções avançadas. Aguarde uma notificação legítima no uso normal do Nubank; não é necessário criar uma operação bancária para testar.
4. Volte e toque **Atualizar diagnóstico**. Confira quantidade, horário publicado/capturado, revisão e marca de resumo. Feche a interface, bloqueie a tela e confira depois de outra notificação que o registro continuou sendo feito. Não encaminhe originais ou capturas de tela; o APK usa FLAG_SECURE para proteger a janela.
5. **Abrir e revisar** mostra o original apenas após o toque e propõe máscaras nos campos editáveis. Troque nomes, estabelecimentos, contas, locais, referências e frases pessoais por exemplos inventados. Valores reconhecidos em BRL viram `R$ 12,34`; CPF/CNPJ, telefone, e-mail, URL, UUID/chave Pix e datas reconhecidas recebem máscara. Essas regras não garantem anonimização: confira todos os campos e confirme a revisão manual para incluí-la na exportação. Fechar a revisão ou colocar o app em segundo plano limpa o texto da interface; não apaga o registro cifrado.
6. Com o editor fechado, informe uma senha e confirmação de 12 a 128 caracteres e toque **Exportar revisões cifradas**. Escolha uma pasta local no seletor Android. São exportadas somente as revisões confirmadas; originais, IDs reais, horários, chaves de grupo, assinatura, sessão e dados do aparelho não vão no arquivo. Não há compartilhamento/envio automático. O arquivo `.ffortuna-samples` continua cifrado; não é uma fixture pronta para o Git.
7. Informe a senha no primeiro campo e use **Conferir arquivo exportado**. Com senha errada válida em comprimento, deve falhar a autenticação. Com a correta, deve informar a quantidade de amostras válidas, sem importar ou alterar registros. Cancelar o seletor não altera a caixa. Esse formato não restaura a caixa sintética; “Restaurar backup” recusará seu conteúdo.
8. Pare a coleta. Novas notificações não devem aumentar o total. Opcionalmente apague o diagnóstico e confira que a contagem volta a zero e os exemplos sintéticos continuam presentes. Reiniciar o celular exige novo consentimento; reabrir somente o app preserva os registros. Não é necessário esperar sete dias ou alterar o relógio: esses limites são exercitados nos cenários isolados.

O checkpoint esperado é confirmar os 40 resultados sintéticos, identificação da origem e funcionamento local da coleta/revisão/exportação. Não envie dados reais nesta conversa. O usuário aprovou o fluxo funcional em 30/10/2026 no A36/Android 16, incluindo captura, original com valor correto e revisão/exportação. Não informou contadores da bateria, logs ou relatório exportado. A aprovação não equivale a execução pelo runner JUnit ou validação de parser bancário.

## Executar e verificar

```sh
nvm use
npm ci
npm run check
npm run native:build -- --compile-device-tests
```

O build local mantém o teto de 12 GiB e 16 CPUs por systemd/cgroup, sem EAS/conta Expo/serviço pago. O APK é release, com JavaScript embutido e sem INTERNET; downloads de ferramentas/dependências são etapas de desenvolvimento. Expo Go não executa o módulo e não pode receber amostras reais.

Decisão, alternativas e fontes oficiais no [ADR 0008](adr/0008-diagnostico-real-temporario.md). A exportação reutiliza o envelope do [ADR 0006](adr/0006-backup-portatil-da-caixa.md), com PBKDF2-HMAC-SHA256/600 mil iterações e AES-256-GCM. Arquivo cifrado em memória antes do SAF, sem temporário em claro; senhas não são persistidas. Autofill e correção estão desativados nos campos sensíveis. Isso não controla o comportamento de teclados ou garante apagar todas as strings/buffers internos das plataformas.

No relato do usuário, R$ 12,34 aparecia somente na sugestão, enquanto o original preservava o valor correto. Esse comportamento é intencional para preparar a exportação. O futuro processamento financeiro deve consumir o original; os valores substitutos nunca representam valores de transações reais.
