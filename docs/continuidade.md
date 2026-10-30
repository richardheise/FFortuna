# Continuidade — Tique 0.6.1

Atualizado em 30/10/2026. Prioridade do usuário: funcionalidades em incrementos pequenos, depois refinamento UI/UX. A demonstração financeira e os exemplos nativos são sintéticos. A 0.5.0 implementa diagnóstico real temporário opcional, sem parser ou lançamentos; o núcleo financeiro real continua bloqueado.

## Mudança de marca — 30/10/2026

O aplicativo e repositório passam a se chamar **Tique**, em referência à deusa grega da fortuna. A identidade anterior FFortuna e a tag de conteúdo `v0.6.0-alpha.1` permanecem no histórico. O repositório atual é [github.com/richardheise/Tique](https://github.com/richardheise/Tique). O snapshot da versão de conteúdo 0.6.0 está preservado em `artifacts/releases/v0.6.0-alpha.1/`.

O build de marca 0.6.1 usa versionCode 7 e os artefatos locais `artifacts/tique-arm64.apk` e `artifacts/tique-source.tar.gz`; a tag prevista é `v0.6.1-alpha.1`. A pré-release ainda não foi publicada e a versão 0.6.1 não foi validada no aparelho. Os registros de validação são os mesmos 50 testes JVM e 30 TypeScript aprovados, 46 cenários Android compilados e roteiro de 48 verificações nativas pendente. Não interpretar esses dados como aprovação de aparelho da 0.6.1.

Para permitir atualização por cima das instalações anteriores, foram preservados o application ID `org.ffortuna.capturepoc`, certificado de assinatura, bancos, chaves, formatos e protocolos internos, variáveis de ambiente e o caminho de trabalho local `/home/heise/FFortuna`. Esses nomes antigos são identificadores de compatibilidade, não a marca atual.

Verificação final da marca: TypeScript estrito e 30 testes TypeScript aprovados; 50 testes JVM aprovados; 46 cenários Android compilados, sem runner. APK `tique-arm64.apk` 0.6.1/code 7: 46.301.248 bytes, SHA-256 `2c4e61ca9a66526c26cf4ab6d022ebd851c2c35ca9d56c28b5da18595baf34e9`. Nome Tique e pacote `org.ffortuna.capturepoc` conferidos no aapt; certificado SHA-256 `8e4f3bab8b047372480c82d9d361b6273bfd459e76cc0d16d8ff6e201ea74f36`, igual às versões anteriores. Inspeção completa/avisos aprovada. Log `.local/tique-release-build.log`; limite rígido 12 GiB/16 CPUs mantido. Repo `richardheise/Tique` confirmado com ADMIN, remoto local novo e API do endereço antigo encaminhando para Tique. Validação física 0.6.1 segue pendente.

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
GH_CONFIG_DIR=/home/heise/FFortuna/.local/github-cli/config .local/github-cli/gh release view v0.5.0-alpha.1 --repo richardheise/Tique
```

## Git e publicação

Branch main, origin fetch HTTPS e push SSH `git@github.com:richardheise/Tique.git`. Fonte/nota/roteiro enviados e tag `v0.5.0-alpha.1` publicada. Atualizações posteriores da documentação não mudam a tag ou o fonte do APK. Usar outra tag para nova compilação distribuída; não mover tags/substituir anexos anteriores.

GitHub CLI autenticado como `richardheise`: `.local/github-cli/gh`, sempre com `GH_CONFIG_DIR=/home/heise/FFortuna/.local/github-cli/config`. Configuração restrita/ignorada; não imprimir/copiar credenciais. Essa autenticação é independente das contas Codex. Notas atuais em [releases/v0.5.0-alpha.1.md](releases/v0.5.0-alpha.1.md). Artefatos permanentes em `artifacts/`, com snapshots por versão em `artifacts/releases/`.

## Histórico e pendências preservadas

A demonstração Expo Go foi aprovada no celular em 22/10; inclui conta BRL, renda prevista, receitas/despesas, caixinhas/modalidades, compromissos iniciais do cartão, parsing/revisão e OFX sintético. O livro SQLite não é cifrado e só pode receber informações inventadas. O núcleo protegido planejado terá Kotlin como dono de um único livro; a prova atual não mantém outro livro financeiro.

Na prova 0.2.0, o A36 autorizou/conectou o listener, mas a caixa falhou por `execSQL(PRAGMA secure_delete=ON)`: esse setter retorna uma linha. Correção 0.2.1 usa cursor, preserva dados/chave e mostra códigos sem conteúdo sensível. O usuário aprovou todo o roteiro de captura depois da correção; nenhum reset foi necessário. Não regredir essa chamada. `temp_store=MEMORY`, por outro lado, não retorna linha e usa execute; `cipher_log_level=NONE` retorna texto e usa executeForString, conforme o driver verificado.

O usuário aprovou os oito passos manuais da 0.3.0 no A36/Android 16 em 25/10, incluindo senha errada, caixas anteriores, reinstalação sem a chave antiga e persistência/integridade. Isso não equivale a executar os testes instrumentados nem valida a 0.4.0. Não pedir repetição integral da 0.3.0 sem mudança relevante/falha. Tag anterior `v0.3.0-alpha.1` no commit `11174020cc5df9236041674fd44783b2f3225a2c` preservada com seus nove anexos em `artifacts/releases/v0.3.0-alpha.1/`.

Permanecem 18 alertas npm altos de duas falhas upstream sem patch na auditoria de 22/10; zero moderados após a correção aplicada. [Dependências](dependencias.md). Não usar audit fix --force indiscriminadamente. Expo-doctor não foi aprovado. Nenhum código/asset Finan+ ou PennyWise foi copiado; original AGPL-3.0-only, avisos de terceiros preservados.
