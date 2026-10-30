# Continuidade — Tique 0.8.0

Atualizado em 30/10/2026. Prioridade do usuário: funcionalidades em incrementos pequenos, depois refinamento UI/UX. A demonstração financeira e os exemplos nativos são sintéticos. A captura diagnóstica é temporária e opcional; a 0.8.0 implementa um livro permanente cifrado de uma conta, com confirmação manual e recuperação.

## Incremento 0.8.0 — livro permanente, conferência e recuperação

Autorizado pelo usuário após a proposta de uma conta real BRL, receitas/despesas manuais e confirmação das notificações. Depois, propôs carregar saldos/dados exportados manualmente do Nubank. Sem novos dados reais disponíveis no repositório; documentos oficiais confirmam emissão de extratos pela conta PF, mas não validam o layout do arquivo do usuário. Esta etapa implementa abertura e conferência manual, sem importação real ou ajuste automático. Próximo incremento após aprovação: arquivo exportado, staging/prévia/reconciliação, sem exigir transformação pelo usuário. Fontes: [blog oficial](https://blog.nubank.com.br/extrato-bancario/) e [anúncio oficial de exportação](https://comunidade.nubank.com.br/novidades/post/exporte-extratos-diretamente-de-sua-conta-nubank-pelo-app-rbRYnw8qndyPd2S).

[ADR 0011](adr/0011-primeiro-livro-financeiro-real.md): schema do catálogo 1→2 preserva chave e IDs, com um livro SQLCipher nativo e contrapartidas equilibradas, UUIDs idempotentes e vínculo único da observação. Publicação/captura/evidência extraída e campos aprovados são preservados; não copia o conjunto de payload bruto ao livro. A descrição aprovada permanece como dado financeiro, sem promessa de anonimização. Retenção/limpeza diagnóstica não apaga lançamentos. Categorias/tags compartilham transações e backup do livro. Anulação mantém original/par/motivo e exclui efeito ativo, sem estorno bancário. Conferência mostra diferença sem criar receita/despesa. `FinanceRules` usa centavos inteiros e BigInteger para validar limites de saldos/agregados antes do commit. Tipos financeiros não suportados ficam em revisão.

`FinanceBoxes` prepara restauração em banco/chave novos e troca ponteiro só depois de validar, preservando uma anterior sem mesclar. Perda da chave ativa não bloqueia backup portátil válido. Reiniciar livro mantém catálogo e preserva o livro anterior. `LocalRecoveryStore` foi generalizado com callback de validação e APIs de bytes; APIs e cenários sintéticos permanecem. Namespace/chave financeira próprios, três cópias a cada 24 horas com mudanças; primeiro snapshot automático somente depois de configurar a abertura. Cópias locais não sobrevivem à desinstalação; backup portátil é independente da chave do aparelho. Sem novas dependências, permissões ou serviços.

A tela inicial do APK agora é o livro; ferramentas sintéticas/diagnóstico/checks são acessadas por botão separado. Validação local: TypeScript estrito e 30 testes TypeScript aprovados; 54 JVM aprovados; 70 métodos Android compilados, sem runner no servidor. Bateria `isolated-native-checks/v5` exige 72 aprovadas para a configuração inicial e nova sessão diagnóstica. Não foi executada no A36 nesta entrega. [Roteiro 0.8.0](releases/v0.8.0-alpha.1.md) usa valores fictícios, incluindo backup/restauração, anulação e reinício para preservar o teste ao começar uso real. Logs finais `.local/finance-verified-check.log` e `.local/finance-verified-build.log`; limite de 12 GiB/16 CPUs mantido. Build final aprovado: APK Tique 0.8.0/code 10, 46.419.896 bytes, SHA-256 `e3ba41ef7e3c78fd5e3b5166a8e5b49d2e213abc295f1f764b3c112c4cde8610`, assinatura igual às versões anteriores. Inspeção final confirmou backup Android desabilitado, ausência de INTERNET, bundle offline, avisos e alinhamento 16 KiB das 23 bibliotecas. Publicação em preparação; validação física pendente. Não inferir aprovação a partir do checkpoint 0.7.1.

## Correção 0.7.1 — nove falhas na classificação

**Checkpoint aprovado pelo usuário:** após instalar a 0.7.1, relatou “agora passou tudo”. Confirma aprovação da bateria e do funcionamento conforme relato no A36/Android 16; não forneceu contadores transcritos, resultados individuais ou logs. A bateria desta versão contém 60 verificações. Não reclassificar essa aprovação como execução JUnit Android no servidor. Tag, fonte e anexos publicados permanecem imutáveis.

Usuário relatou nove falhas na bateria da 0.7.0. Identificou cenários de criação/ciclo de vida, persistência, duplicidade, validação, isolamento, cifragem e revisão, com códigos genéricos de classificação. Não inferir os demais contadores nem registrar aprovação física.

Causa identificada: `ClassificationCatalog.exists` passava `excluding=null` em dois argumentos de texto de `rawQuery` na criação. Bytecode do SQLCipher 4.19.1 conferido: `SQLiteDirectCursorDriver.query(String[])` chama `bindAllArgsAsStrings`, que chama `bindString`; este rejeita nulo antes de executar a condição SQL `? IS NULL`. Todos os nove casos que criam opções alcançam essa consulta. Correção usa cláusula e argumentos diferentes para criação (tipo/nome) e renomeação (tipo/nome/ID); o array possui tipo `Array<String>` sem elementos nulos. Não altera schema, IDs, chave ou dados.

Build 0.7.1/code 9 aprovado: 50 testes JVM sem falhas; 58 cenários Android compilados, sem execução JUnit. Inspeção, assinatura preservada, bundle offline, ausência de INTERNET, política de backup, avisos e alinhamento 16 KB aprovados. Log `.local/categories-fix-build.log`; limites 12 GiB/16 CPUs mantidos. SHA-256 `43b3cfc0f7b380fffac12a66484b6f079719649a66cac86bc4e2bca27de2b4d5`. TypeScript inalterado desde os 30 testes aprovados da 0.7.0; não executados novamente neste hotfix. Pré-release [`v0.7.1-alpha.1`](https://github.com/richardheise/Tique/releases/tag/v0.7.1-alpha.1) publicada e aprovada funcionalmente pelo usuário conforme checkpoint acima. Fonte `aab75fb65a540b35e2a3eb8855840640725c9181`, 204 arquivos conferidos byte a byte; fonte SHA-256 `80c174f91922a8f594133e204a4108dffdbe11314b3cf4f9483438dd604d7f89`. Release ID `429423636`, nove tamanhos e hashes remotos conferidos antes de publicar, API pública e download HTTP 200 conferidos sem autenticação. APK 46.327.700 bytes, snapshot em `artifacts/releases/v0.7.1-alpha.1/`. As notas públicas da 0.7.0 indicam o problema e a correção; anexos e tags históricos preservados. Não desinstalar ou apagar dados para aplicar. A bateria permanece com 60 verificações; os 58 cenários Android existentes abrangem esse fluxo; o usuário informou que a bateria passou após atualizar. [Roteiro curto](releases/v0.7.1-alpha.1.md).

## Incremento 0.7.0 — categorias e tags

Usuário aprovou uma categoria principal e tags opcionais múltiplas. Catálogo SQLCipher separado e permanente, IDs estáveis, criar/renomear/arquivar/reativar. Oito categorias iniciais, nomes até 80 caracteres, até 200 opções e 20 tags por revisão. Revisões antigas em texto livre preservadas; IDs opcionais no JSON não alteram o schema 2 do diagnóstico. Reprocessamento mantém a seleção. Configuração sobrevive à retenção e a apagar diagnóstico. [Decisão e consequências](adr/0010-categorias-e-tags-do-diagnostico.md).

Catálogo não é ledger, e seleção ainda expira com observação após sete dias. Catálogo e revisões reais não entram nos backups sintéticos; a interface informa a limitação e a perda do catálogo ao desinstalar. Runner nativo passa a `isolated-native-checks/v4`, 60 verificações (58 callbacks Android + limpeza e logs próprios). Doze cenários adicionados, ainda sem execução em aparelho. Build final aprovado: TypeScript estrito/30 testes TypeScript e 50 testes JVM sem falhas; 58 métodos Android compilados. APK 0.7.0/code 8, 46.327.692 bytes, SHA-256 `04cc41bcafa659303bf70ac2aba3a514c3c681fb6a444cd33b7da0f19164b8a3`, certificado igual, sem INTERNET, avisos/backup/alinhamento 16 KB aprovados. Log `.local/categories-release-final.log`; limites de 12 GiB/16 CPUs mantidos. Pré-release [`v0.7.0-alpha.1`](https://github.com/richardheise/Tique/releases/tag/v0.7.0-alpha.1) publicada; posteriormente o usuário relatou nove falhas de classificação, corrigidas no código da 0.7.1 e aguardando reteste. Fonte da tag: `b3a2dbad5a3c6e7bbeb595e42a8f386dc2e58d0f`, 203 arquivos conferidos byte a byte. Release ID `429386905`; nove tamanhos/hashes remotos conferidos antes de publicar, API pública e download sem autenticação HTTP 200 conferidos. Snapshot imutável em `artifacts/releases/v0.7.0-alpha.1/`. Arquivo fonte SHA-256 `c5e7ba1a40ae3e8e24b327dc6ee36e20f7e102c2ab49c3f12a6274f6271bcf20`. Commit de documentação posterior não move a tag nem altera os anexos. Não alegar aprovação da entrega nova a partir da aprovação 0.6.1.

O reteste 0.7.1 foi aprovado. Proposta para a próxima entrega, ainda sem implementação autorizada nesta rodada: primeiro histórico financeiro real de uma única conta BRL, confirmação manual das notificações e lançamentos manuais de receita/despesa, saldo e resumo mensal simples, proteção de recuperação/backup incluindo catálogo e lançamentos. Repetir confirmação/reprocessamento não pode duplicar, e retenção/limpeza do diagnóstico não pode apagar transações confirmadas. Guardar modalidades ainda não suportadas para revisão, sem converter cartão, transferências, aportes ou estornos em consumo/receita comum. Detalhar o recorte e os critérios antes de implementar; cartões, caixinhas, importação real e automação ficam para incrementos próprios.

## Mudança de marca — 30/10/2026

O aplicativo e repositório passam a se chamar **Tique**, em referência à deusa grega da fortuna. A identidade anterior FFortuna e a tag de conteúdo `v0.6.0-alpha.1` permanecem no histórico. O repositório atual é [github.com/richardheise/Tique](https://github.com/richardheise/Tique). O snapshot da versão de conteúdo 0.6.0 está preservado em `artifacts/releases/v0.6.0-alpha.1/`.

O build de marca 0.6.1 usa versionCode 7 e os artefatos locais `artifacts/tique-arm64.apk` e `artifacts/tique-source.tar.gz`; a tag imutável é `v0.6.1-alpha.1`. A pré-release foi publicada e o usuário relatou em 30/10/2026 que tudo funcionou no aparelho. Essa é aprovação funcional informada, sem contadores individuais fornecidos. Os registros de validação são os mesmos 50 testes JVM e 30 TypeScript aprovados, 46 cenários Android compilados e roteiro de 48 verificações nativas pendente. Os 48 resultados individuais da bateria não foram informados; não inferir uma execução JUnit Android.

Para permitir atualização por cima das instalações anteriores, foram preservados o application ID `org.ffortuna.capturepoc`, certificado de assinatura, bancos, chaves, formatos e protocolos internos, variáveis de ambiente e o caminho de trabalho local `/home/heise/FFortuna`. Esses nomes antigos são identificadores de compatibilidade, não a marca atual.

Verificação final da marca: TypeScript estrito e 30 testes TypeScript aprovados; 50 testes JVM aprovados; 46 cenários Android compilados, sem runner. APK `tique-arm64.apk` 0.6.1/code 7: 46.301.248 bytes, SHA-256 `2c4e61ca9a66526c26cf4ab6d022ebd851c2c35ca9d56c28b5da18595baf34e9`. Nome Tique e pacote `org.ffortuna.capturepoc` conferidos no aapt; certificado SHA-256 `8e4f3bab8b047372480c82d9d361b6273bfd459e76cc0d16d8ff6e201ea74f36`, igual às versões anteriores. Inspeção completa/avisos aprovada. Log `.local/tique-release-build.log`; limite rígido 12 GiB/16 CPUs mantido. Repo `richardheise/Tique` confirmado com ADMIN, remoto local novo e API do endereço antigo encaminhando para Tique. Validação funcional 0.6.1 relatada pelo usuário; não houve ADB ou runner JUnit neste servidor.

## Incremento 0.6.0

Autorizado pelo usuário em 30/10/2026. Repositório/áreas privadas inspecionados: não há amostras bancárias reais ou revisadas disponíveis. Portanto, não foram inventados templates Nubank. Implementado extrator genérico `notification-brl/1` para marcadores R$/BRL, duas casas decimais e agrupamento por ponto, com centavos inteiros seguros e evidência exata/campo/offsets UTF-16. Valores ambíguos, inválidos, truncados ou sem marcador não são escolhidos. Natureza/conta/data efetiva permanecem desconhecidas; não há lançamento automático.

`CandidateStore` compartilha conexão/executor da caixa diagnóstica. Migração transacional 1→2 cria parsing e edições separados, mantém originais/chave/revisões de exportação e pausa a sessão antiga para novo consentimento que inclua parsing. Observações novas são interpretadas nativamente após commit durável; falha de parsing deixa evidência recuperável e sinaliza pendência sem bloquear gravações futuras. Antigas entram apenas mediante “Interpretar original”. Um resultado por observação; reprocessar não duplica nem sobrescreve correções. Relação por identidade/revisão Android não comprova mesma transação financeira.

A interface oferece “Interpretar original” e revisão parcial/descartar, com campos reais; a cópia para exportar tem rótulo explícito de valores fictícios R$ 12,34. Prévia e edições são temporárias e expiram com a observação após 7 dias; não pertencem a um livro financeiro nem aos backups/exports sintéticos. O núcleo financeiro real continua bloqueado. Não perder essa limitação na comunicação. Decisão no [ADR 0009](adr/0009-previa-brl-no-diagnostico.md).

Checkpoint necessário: [roteiro 0.6.0 no A36](previa-brl.md), com 48 verificações sintéticas (38 cenários anteriores +8 novos, mais limpeza e logs próprios), migração sem reset, valor/evidência do original, edição salva após reprocessar/reabrir e coleta com interpretação ao fundo. **A execução Android 0.6.0 ainda está pendente**. A aprovação funcional 0.5.0 não valida os novos cenários.

Verificações locais finais: TypeScript estrito, 30 testes TypeScript e 50 testes JVM aprovados. 46 cenários de dispositivo compilados; nenhum runner Android executado neste servidor. APK 0.6.0/versionCode 6 gerado/inspecionado com mesma assinatura: 46.301.268 bytes; SHA-256 `b82bd30d7fe4ba79fd9a7a38f6cbd8acb6016762ef747a9f179997fdcb983815`. Sem INTERNET/SMS/READ_LOGS/QUERY_ALL_PACKAGES/permissões amplas de armazenamento; bundle offline, avisos e 23 bibliotecas alinhadas a 16 KB. Logs `.local/candidate-js-final-check.log` e `.local/candidate-release-build.log`. Sem aparelho ADB/emulador.

Pré-release `v0.6.0-alpha.1` publicada e imutável sob a marca anterior FFortuna, com fonte/checksums e roteiro. O snapshot local está em `artifacts/releases/v0.6.0-alpha.1/`. Não mover a tag ou substituir anexos antigos.

## Incremento 0.5.0

Autorizado pelo usuário em 26/10/2026 (“manda a ver”), depois da aprovação integral do checkpoint 0.4.0. Implementados diagnóstico com consentimento explícito, verificação local de pacote/versão/assinatura instalada, coleta nativa sem JavaScript, caixa SQLCipher/Keystore separada, identidade/revisões e resumo de grupo só com metadados. Não é um segundo livro financeiro. Nenhum parser/lançamento bancário nesta entrega.

Padrões: 24 horas por sessão, limite total de 200 observações retidas, até 20 revisões e retenção de 7 dias quando o núcleo executar. Somente notificações novas do pacote `com.nu.production` no mesmo perfil Android. BOOT_COUNT/relógio monotônico impedem continuar após reinício ou ampliar a janela atrasando o relógio. A assinatura é observada no aparelho, sem alegar certificado oficial. Filtro/consentimento/prazo/assinatura precedem leitura de texto; só cinco campos textuais limitados são serializados.

As 40 verificações desta versão devem passar antes de iniciar uma sessão real. O resultado não é autorização persistente: iniciar outra sessão após encerrar o processo exige repetir a bateria. Sessão já consentida pode continuar no mesmo boot dentro do prazo original. Captura real continua inicialmente desativada. Relatório 0.4.0 não vale como aprovação da 0.5.0.

Revisão com sugestões determinísticas para identificadores/valores; confirmação manual obrigatória porque nomes/locais/texto livre não são anonimizáveis por garantia. Exporta somente campos revisados com senha (mesmo envelope PBKDF2/GCM já testado), sem IDs/horários/assinatura/grupos/originais. Conferir arquivo decifra/valida em memória sem importar. Sem temporário em claro, envio, novas dependências ou permissões amplas. FLAG_SECURE na janela e limpeza de campos ao background. Diagnóstico não integra os backups sintéticos; originais efêmeros não têm restauração portátil.

Em 30/10/2026, o usuário aprovou o funcionamento do roteiro 0.5.0 no Samsung Galaxy A36 SM-A366E/DS, Android 16. Confirmou captura de notificações, valores corretos no original e substituição por R$ 12,34 somente na sugestão, além de revisão/exportação funcionando. Não forneceu contadores numéricos da bateria desta versão, logs ou relatório exportado; não registrar 40/0/0 como resultado explicitamente informado. O runner JUnit Android continua sem execução neste servidor.

O usuário relatou inicialmente valores R$ 12,34 e esclareceu que o original estava correto; a diferença existia só na sugestão. É o mascaramento intencional para exportação, não alteração do original. Não aplicar essa transformação ao futuro parser/livro financeiro. A interface pode explicar melhor a separação em um incremento futuro; nenhum APK foi alterado por essa confirmação.

Checkpoint funcional aprovado conforme relato do usuário. Próximo incremento pequeno: primeiro parser determinístico, fundamentado em amostras revisadas e transformadas em fixtures sintéticas, com testes de valores/confiança/natureza/duplicidade. Não inventar formatos Nubank nem solicitar originais, senhas ou conteúdo financeiro nesta conversa. A aprovação da coleta não equivale à validação de parsing. Política/roteiro em [diagnostico-local.md](diagnostico-local.md) e [ADR 0008](adr/0008-diagnostico-real-temporario.md).

Verificações locais finais: TypeScript estrito, 30 testes TypeScript e 34 testes JVM aprovados. 38 testes instrumentados compilados, sem runner Android. APK interno 0.5.0/versionCode 5 gerado/inspecionado com a mesma assinatura: 46.266.508 bytes, SHA-256 `45e1132681c0335b2b1112e16a215bada3fcfc26fcc99026c5b8986da1d44a38`. Sem INTERNET/SMS/READ_LOGS/QUERY_ALL_PACKAGES/permissões amplas de armazenamento; avisos, 23 bibliotecas alinhadas a 16 KB, consulta restrita e configuração FLAG_SECURE conferidos. Logs locais `.local/diagnostic-js-final-check.log` e `.local/diagnostic-release-build.log`. Não há aparelho ADB; nenhum emulador iniciado.

[Pré-release v0.5.0-alpha.1](https://github.com/richardheise/Tique/releases/tag/v0.5.0-alpha.1) publicada com nove anexos conferidos por tamanho/digest remoto. [Download público do APK](https://github.com/richardheise/Tique/releases/download/v0.5.0-alpha.1/ffortuna-capture-poc-arm64.apk) confirmado HTTP 200, 46.266.508 bytes. Fonte exato dos 184 arquivos da tag no commit `72b1491203e081f56fb3d75cdc5db4e82671a4f6`, com licenças/recibo/checksums; sem amostras reais ou credenciais. Tags e anexos anteriores preservados. Snapshot local em `artifacts/releases/v0.5.0-alpha.1/`. Atualizações posteriores desta documentação não alteram o APK/fonte da tag.

## Entrega anterior disponível (0.4.0)

[Pré-release v0.4.0-alpha.1](https://github.com/richardheise/Tique/releases/tag/v0.4.0-alpha.1) · [APK direto](https://github.com/richardheise/Tique/releases/download/v0.4.0-alpha.1/ffortuna-capture-poc-arm64.apk).

Publicada, não rascunho, com nove anexos conferidos por tamanho e digest SHA-256 remoto. O download público sem autenticação respondeu HTTP 200. Código da tag imutável: `697fee04fa083de77c5903c18258986ca3ff1a47`. APK interno 0.4.0, versionCode 4, pacote `org.ffortuna.capturepoc`, arm64-v8a/API 26+. Atualizar por cima da 0.3.0; mesma assinatura. Tamanho 46.207.716 bytes; SHA-256 `0ef198733e42f5e227a7b7bba4e7a22096da3d76603f618d801142fd68977085`.

O usuário aprovou até três cópias privadas cifradas, sem bruto, no máximo uma a cada 24 horas quando os registros/parsing mudarem. O núcleo Kotlin as cria quando executar, sem gerenciamento manual de arquivos. Índice/snapshots são autenticados, gravados atomicamente e verificados antes de publicar; perda da chave/adulteração bloqueia sem sobrescrever dados. A consulta do estado autentica as cópias. Listar depois de apagar/desativar não recria chave/arquivos. Relógio regressivo adia a próxima cópia.

A política aparece na tela normal. “Configurações avançadas”, fechadas por padrão, reúne lista/restauração local, ativação/desativação/descarte, backup portátil com senha, caixas anteriores e diagnóstico. Cópias internas dependem da instalação/Keystore e não sobrevivem à desinstalação; backup portátil salvo fora do app pode sobreviver. Ambos cobrem somente a caixa sintética, não o livro Expo Go. Desativar preserva cópias; apagar cópias desativa primeiro e mantém as caixas. Restaurar cria nova caixa, preserva a anterior e pausa captura, sem mesclar.

O usuário também aprovou retenção de bruto de 30 dias desde a captura na caixa ativa e na anterior inativa. Limpeza compara Instants, preserva IDs/parsing e verifica truncamento de WAL quando aplicável. Se a chave anterior estiver indisponível, mostra “limpeza bloqueada”, pausa captura e exige recuperação do acesso ou descarte explícito da caixa anterior. Não recriar bancos ausentes sobre material existente. Detalhes/alternativas no [ADR 0007](adr/0007-recuperacao-local-e-retencao.md).

## Validação anterior (0.4.0)

- TypeScript estrito e 30 testes TypeScript aprovados.
- 22 testes JVM aprovados. 28 testes instrumentados compilados, não executados pelo runner Android.
- APK inspecionado: assinatura igual, backup automático Android desativado, sem INTERNET/READ_LOGS/acesso amplo ao armazenamento, bundle offline, avisos de licença e 23 bibliotecas nativas alinhadas a 16 KB.
- Inventário reconferido: 71 pacotes npm, 189 coordenadas Maven e 24 suplementos C/C++; zero pendências de textos identificadas. [Revisão](revisao-licencas-apk.md).
- Fonte do arquivo corresponde aos 169 arquivos da tag, conferidos byte a byte. Nove anexos com checksums, incluindo APK, fonte, licenças e recibo; não há chaves, credenciais ou dados reais.

**Checkpoint 0.4.0 aprovado pelo usuário em 26/10/2026 no Samsung Galaxy A36 SM-A366E/DS, Android 16.** Relatou **30 verificações aprovadas, zero falhas e zero não verificadas** na bateria da interface e confirmou que o restante do roteiro também funcionou. É evidência informada pelo usuário; não foram fornecidos logs, relatório exportado, resultados individuais ou tempos. Não inferir testes adicionais além desse roteiro.

As 30 verificações incluem 28 callbacks compartilhados com a suíte instrumentada, limpeza de sandbox interrompido e janela de logs próprios. O resultado de logs cobre a janela e as sondas daquele processo; não certifica ausência universal de logs. O relatório fica em memória. O runner JUnit Android continua sem execução neste servidor, sem aparelho ADB/KVM; não reclassificar a execução da interface como instrumentação JUnit.

Esse checkpoint autorizou a implementação do diagnóstico 0.5.0 descrito acima. Não habilita automaticamente coleta bancária nem comprova parsing Nubank.

## Execução e limites

```sh
cd /home/heise/FFortuna
nvm use
npm ci
npm run check
npm run native:build -- --compile-device-tests
```

O script impõe limite rígido por systemd/cgroup de 12 GiB e até 16 CPUs, autorizado pelo usuário. Não aumentar automaticamente. Gradle sequencial, heap 3 GiB, C++ limitado a 16 jobs/linker a um e Metro a dois workers. Recusar build sem confirmar limites/pools Ninja. Houve reinício do PC numa tentativa anterior; não foi confirmada causa por OOM. Os builds finais passaram com os limites. Não iniciar compilações/emuladores paralelos.

Toolchain ignorada em `.local/android-toolchain`: Temurin 17.0.21+9, SDK 36, BuildTools 36/35, NDK 27.1.12297006, CMake 3.22.1. Chaves de assinatura em `.local/keys`: preservar, não sobrescrever/apagar/enviar ao Git. Android gerado/ignorado; fonte Kotlin em `modules/ffortuna-capture`. Logs locais da entrega: `.local/security-js-check.log` e `.local/security-final-build.log`.

```sh
(cd artifacts && sha256sum -c SHA256SUMS)
GH_CONFIG_DIR=/home/heise/FFortuna/.local/github-cli/config .local/github-cli/gh release view v0.6.1-alpha.1 --repo richardheise/Tique
```

## Git e publicação

Branch main, origin fetch HTTPS e push SSH `git@github.com:richardheise/Tique.git`. Fonte/nota/roteiro enviados e tag `v0.6.1-alpha.1` publicada. Atualizações posteriores da documentação não mudam a tag ou o fonte do APK. Usar outra tag para nova compilação distribuída; não mover tags/substituir anexos anteriores.

GitHub CLI autenticado como `richardheise`: `.local/github-cli/gh`, sempre com `GH_CONFIG_DIR=/home/heise/FFortuna/.local/github-cli/config`. Configuração restrita/ignorada; não imprimir/copiar credenciais. Essa autenticação é independente das contas Codex. Notas atuais em [releases/v0.5.0-alpha.1.md](releases/v0.5.0-alpha.1.md). Artefatos permanentes em `artifacts/`, com snapshots por versão em `artifacts/releases/`.

## Histórico e pendências preservadas

A demonstração Expo Go foi aprovada no celular em 22/10; inclui conta BRL, renda prevista, receitas/despesas, caixinhas/modalidades, compromissos iniciais do cartão, parsing/revisão e OFX sintético. O livro SQLite não é cifrado e só pode receber informações inventadas. O núcleo protegido planejado terá Kotlin como dono de um único livro; a prova atual não mantém outro livro financeiro.

Na prova 0.2.0, o A36 autorizou/conectou o listener, mas a caixa falhou por `execSQL(PRAGMA secure_delete=ON)`: esse setter retorna uma linha. Correção 0.2.1 usa cursor, preserva dados/chave e mostra códigos sem conteúdo sensível. O usuário aprovou todo o roteiro de captura depois da correção; nenhum reset foi necessário. Não regredir essa chamada. `temp_store=MEMORY`, por outro lado, não retorna linha e usa execute; `cipher_log_level=NONE` retorna texto e usa executeForString, conforme o driver verificado.

O usuário aprovou os oito passos manuais da 0.3.0 no A36/Android 16 em 25/10, incluindo senha errada, caixas anteriores, reinstalação sem a chave antiga e persistência/integridade. Isso não equivale a executar os testes instrumentados nem valida a 0.4.0. Não pedir repetição integral da 0.3.0 sem mudança relevante/falha. Tag anterior `v0.3.0-alpha.1` no commit `11174020cc5df9236041674fd44783b2f3225a2c` preservada com seus nove anexos em `artifacts/releases/v0.3.0-alpha.1/`.

Permanecem 18 alertas npm altos de duas falhas upstream sem patch na auditoria de 22/10; zero moderados após a correção aplicada. [Dependências](dependencias.md). Não usar audit fix --force indiscriminadamente. Expo-doctor não foi aprovado. Nenhum código/asset Finan+ ou PennyWise foi copiado; original AGPL-3.0-only, avisos de terceiros preservados.

Publicação Tique 0.6.1 concluída em 30/10/2026: [release](https://github.com/richardheise/Tique/releases/tag/v0.6.1-alpha.1) e [APK direto](https://github.com/richardheise/Tique/releases/download/v0.6.1-alpha.1/tique-arm64.apk). Nove anexos comparados por tamanho/SHA-256 remoto; download público HTTP 200. Fonte dos 194 arquivos da tag no commit `0d42e2abc3e0f8d2527d9e7e5760182535631dd2`, conferido byte a byte. Fonte/artefatos locais em `artifacts/releases/v0.6.1-alpha.1/`. APK anterior 0.6.0 também consultado no repositório renomeado e seu hash original permaneceu igual. Nome/marca atualizados, protocolos/assinatura/dados legados preservados no código; teste de atualização e roteiro de 48 verificações no A36 seguem pendentes.
