# Continuidade — checkpoint 0.4.0

Atualizado em 26/10/2026. Prioridade do usuário: funcionalidades em incrementos pequenos, depois refinamento UI/UX. A demonstração financeira e a prova nativa são exclusivamente sintéticas; dados reais continuam bloqueados.

## Entrega disponível

[Pré-release v0.4.0-alpha.1](https://github.com/richardheise/FFortuna/releases/tag/v0.4.0-alpha.1) · [APK direto](https://github.com/richardheise/FFortuna/releases/download/v0.4.0-alpha.1/ffortuna-capture-poc-arm64.apk).

Publicada, não rascunho, com nove anexos conferidos por tamanho e digest SHA-256 remoto. O download público sem autenticação respondeu HTTP 200. Código da tag imutável: `697fee04fa083de77c5903c18258986ca3ff1a47`. APK interno 0.4.0, versionCode 4, pacote `org.ffortuna.capturepoc`, arm64-v8a/API 26+. Atualizar por cima da 0.3.0; mesma assinatura. Tamanho 46.207.716 bytes; SHA-256 `0ef198733e42f5e227a7b7bba4e7a22096da3d76603f618d801142fd68977085`.

O usuário aprovou até três cópias privadas cifradas, sem bruto, no máximo uma a cada 24 horas quando os registros/parsing mudarem. O núcleo Kotlin as cria quando executar, sem gerenciamento manual de arquivos. Índice/snapshots são autenticados, gravados atomicamente e verificados antes de publicar; perda da chave/adulteração bloqueia sem sobrescrever dados. A consulta do estado autentica as cópias. Listar depois de apagar/desativar não recria chave/arquivos. Relógio regressivo adia a próxima cópia.

A política aparece na tela normal. “Configurações avançadas”, fechadas por padrão, reúne lista/restauração local, ativação/desativação/descarte, backup portátil com senha, caixas anteriores e diagnóstico. Cópias internas dependem da instalação/Keystore e não sobrevivem à desinstalação; backup portátil salvo fora do app pode sobreviver. Ambos cobrem somente a caixa sintética, não o livro Expo Go. Desativar preserva cópias; apagar cópias desativa primeiro e mantém as caixas. Restaurar cria nova caixa, preserva a anterior e pausa captura, sem mesclar.

O usuário também aprovou retenção de bruto de 30 dias desde a captura na caixa ativa e na anterior inativa. Limpeza compara Instants, preserva IDs/parsing e verifica truncamento de WAL quando aplicável. Se a chave anterior estiver indisponível, mostra “limpeza bloqueada”, pausa captura e exige recuperação do acesso ou descarte explícito da caixa anterior. Não recriar bancos ausentes sobre material existente. Detalhes/alternativas no [ADR 0007](adr/0007-recuperacao-local-e-retencao.md).

## Validação e próximo checkpoint

- TypeScript estrito e 30 testes TypeScript aprovados.
- 22 testes JVM aprovados. 28 testes instrumentados compilados, não executados pelo runner Android.
- APK inspecionado: assinatura igual, backup automático Android desativado, sem INTERNET/READ_LOGS/acesso amplo ao armazenamento, bundle offline, avisos de licença e 23 bibliotecas nativas alinhadas a 16 KB.
- Inventário reconferido: 71 pacotes npm, 189 coordenadas Maven e 24 suplementos C/C++; zero pendências de textos identificadas. [Revisão](revisao-licencas-apk.md).
- Fonte do arquivo corresponde aos 169 arquivos da tag, conferidos byte a byte. Nove anexos com checksums, incluindo APK, fonte, licenças e recibo; não há chaves, credenciais ou dados reais.

**Checkpoint 0.4.0 aprovado pelo usuário em 26/10/2026 no Samsung Galaxy A36 SM-A366E/DS, Android 16.** Relatou **30 verificações aprovadas, zero falhas e zero não verificadas** na bateria da interface e confirmou que o restante do roteiro também funcionou. É evidência informada pelo usuário; não foram fornecidos logs, relatório exportado, resultados individuais ou tempos. Não inferir testes adicionais além desse roteiro.

As 30 verificações incluem 28 callbacks compartilhados com a suíte instrumentada, limpeza de sandbox interrompido e janela de logs próprios. O resultado de logs cobre a janela e as sondas daquele processo; não certifica ausência universal de logs. O relatório fica em memória. O runner JUnit Android continua sem execução neste servidor, sem aparelho ADB/KVM; não reclassificar a execução da interface como instrumentação JUnit.

Próximo incremento pequeno: diagnóstico real temporário e cifrado com consentimento explícito, seleção da fonte e pacote Nubank verificado no aparelho, retenção limitada e exportação explícita anonimizada. Amostras reais devem permanecer locais e fora do Git. A aprovação atual fecha o checkpoint sintético; não habilita automaticamente captura bancária nem comprova parsing Nubank.

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
GH_CONFIG_DIR=/home/heise/FFortuna/.local/github-cli/config .local/github-cli/gh release view v0.4.0-alpha.1 --repo richardheise/FFortuna
```

## Git e publicação

Branch main, origin fetch HTTPS e push SSH `git@github.com:richardheise/FFortuna.git`. Fonte/nota/roteiro enviados e tag `v0.4.0-alpha.1` publicada. Atualizações posteriores da documentação não mudam a tag ou o fonte do APK. Usar outra tag para nova compilação distribuída; não mover tags/substituir anexos anteriores.

GitHub CLI autenticado como `richardheise`: `.local/github-cli/gh`, sempre com `GH_CONFIG_DIR=/home/heise/FFortuna/.local/github-cli/config`. Configuração restrita/ignorada; não imprimir/copiar credenciais. Essa autenticação é independente das contas Codex. Notas em [releases/v0.4.0-alpha.1.md](releases/v0.4.0-alpha.1.md). Artefatos permanentes em `artifacts/`, com snapshots por versão em `artifacts/releases/`.

## Histórico e pendências preservadas

A demonstração Expo Go foi aprovada no celular em 22/10; inclui conta BRL, renda prevista, receitas/despesas, caixinhas/modalidades, compromissos iniciais do cartão, parsing/revisão e OFX sintético. O livro SQLite não é cifrado e só pode receber informações inventadas. O núcleo protegido planejado terá Kotlin como dono de um único livro; a prova atual não mantém outro livro financeiro.

Na prova 0.2.0, o A36 autorizou/conectou o listener, mas a caixa falhou por `execSQL(PRAGMA secure_delete=ON)`: esse setter retorna uma linha. Correção 0.2.1 usa cursor, preserva dados/chave e mostra códigos sem conteúdo sensível. O usuário aprovou todo o roteiro de captura depois da correção; nenhum reset foi necessário. Não regredir essa chamada. `temp_store=MEMORY`, por outro lado, não retorna linha e usa execute; `cipher_log_level=NONE` retorna texto e usa executeForString, conforme o driver verificado.

O usuário aprovou os oito passos manuais da 0.3.0 no A36/Android 16 em 25/10, incluindo senha errada, caixas anteriores, reinstalação sem a chave antiga e persistência/integridade. Isso não equivale a executar os testes instrumentados nem valida a 0.4.0. Não pedir repetição integral da 0.3.0 sem mudança relevante/falha. Tag anterior `v0.3.0-alpha.1` no commit `11174020cc5df9236041674fd44783b2f3225a2c` preservada com seus nove anexos em `artifacts/releases/v0.3.0-alpha.1/`.

Permanecem 18 alertas npm altos de duas falhas upstream sem patch na auditoria de 22/10; zero moderados após a correção aplicada. [Dependências](dependencias.md). Não usar audit fix --force indiscriminadamente. Expo-doctor não foi aprovado. Nenhum código/asset Finan+ ou PennyWise foi copiado; original AGPL-3.0-only, avisos de terceiros preservados.
