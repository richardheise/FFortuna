# Retomada — prova nativa iniciada em 23/10/2026

O usuário aprovou a demonstração financeira no celular e autorizou a prova nativa seguinte. Prioridade: funcionalidades antes do refinamento UI/UX, em incrementos pequenos.

Em 25/10/2026, confirmou instalação do APK no A36, acesso Android concedido e listener conectado. No teste inicial, a caixa cifrada ficou indisponível, impedindo ativação/publicação. Identificada a chamada incorreta `execSQL` para `PRAGMA secure_delete=ON`, que retorna uma linha e é rejeitada pelo driver. Correção 0.2.1 usa cursor, preserva chave/banco, mostra requisitos pendentes e códigos de falha sem conteúdo sensível. Depois da disponibilização da correção, o usuário relatou que testou o APK e todos os itens do roteiro manual passaram no A36. Essa é evidência de teste manual informado pelo usuário; os testes instrumentados continuam sem execução automatizada. Não foi necessário orientar reset.

## Incremento 0.3.0 — backup da caixa sintética

O usuário autorizou backup/restauração e aceitou senha independente, ausência de bruto e restauração em nova caixa com preservação da anterior, sem mesclar. Informou também que desinstalou a prova: sem backup anterior, os exemplos e a chave local foram perdidos; a nova instalação pode gerar fixtures novamente.

Implementados PBKDF2-HMAC-SHA256/600.000 + AES-256-GCM, formato autenticado/versionado/limitado, seletor SAF sem cache em claro, validação antes da importação, staging SQLCipher recuperável e troca de ponteiro serial/atômica com captura desativada. No máximo uma caixa anterior, com ações explícitas para alternar/descartar. A senha não é persistida; inputs do contrato Android contêm apenas ação/nome público. Os registros sem bruto não entram na fila processável. Backup não inclui o livro financeiro Expo Go nem habilita bancos reais.

Resultado local: TypeScript + 30 testes TypeScript e 13 testes JVM aprovados. Treze testes instrumentados compilados, não executados (incluem restauração sem chave original, staging interrompido, caixas anteriores, AtomicFile e probes sintéticos em journal/WAL/SHM). APK 0.3.0 gerado, assinado e inspecionado; roteiro físico de backup ainda pendente. Usar o [roteiro 0.3.0](captura-nativa.md#backup-portátil--roteiro-030). A aprovação física anterior é da captura 0.2.1; não atribuir à versão nova.

## Estado atual

- Demonstração Expo Go preservada, exclusivamente sintética; 30 testes TypeScript aprovados após aplicar o lockfile corrigido.
- Implementado módulo Kotlin/Expo Modules API com listener filtrado para o próprio emissor fictício, gravação durável em SQLCipher 4.19.1, chave encapsulada pelo Keystore, fila de escrita nativa, recuperação, identidade/revisões e tela de diagnóstico.
- A prova usa `org.ffortuna.capturepoc`, não abre o livro financeiro da demonstração e não aceita bancos ou arquivos pessoais. Dados reais continuam bloqueados, inclusive diagnóstico real, até concluir as condições de segurança.
- Treze testes JVM Kotlin executados e aprovados. Treze testes instrumentados compilados; execução real ainda pendente. `adb devices -l` não encontrou aparelho conectado; KVM ausente, sem emulador iniciado.
- Aparelho informado: Samsung Galaxy A36 SM-A366E/DS, Android 16. O roteiro está em [captura-nativa.md](captura-nativa.md).
- Toolchain local instalada e ignorada em `.local/android-toolchain`: Temurin17.0.21+9, SDK36, BuildTools36/35, NDK27.1.12297006, CMake3.22.1. Chave de assinatura local em `.local/keys`; não apagar, sobrescrever ou enviar ao Git.
- Houve reinício do PC durante tentativa de compilação; não foi confirmada causa por OOM nos registros consultados. Build foi interrompido e ganhou limite rígido de memória/CPU, checks do Ninja e Metro limitado. Após isso, o usuário autorizou até 12 GiB e 16 CPUs. Manter esses tetos; não aumentar automaticamente. Projetos Gradle seguem sequenciais, C++ limitado a 16 jobs e linker a um.
- APK release 0.3.0 (versionCode 3) gerado e inspecionado em 25/10/2026: `artifacts/ffortuna-capture-poc-arm64.apk`, SHA-256 `46778d77d2294031697f99f52a736f0f218341564f9859b4732e7779597b42f6`. Assinatura, backup desabilitado, ausência de INTERNET, bundle offline e 23 bibliotecas nativas alinhadas a 16 KB verificados. Os treze testes de dispositivo foram compilados; o APK leva os avisos revisados. A assinatura coincide com a versão 0.2.0. Instalação/acesso/conexão da versão anterior confirmados, mas captura bloqueada pela falha de inicialização; o roteiro de captura 0.2.1 foi aprovado pelo usuário no A36; o novo roteiro 0.3.0 aguarda validação e o aparelho não está conectado ao ADB deste ambiente.

## Executar

```sh
cd /home/heise/FFortuna
nvm use
npm ci
npm run check
npm run native:build
```

O build requer systemd/cgroup e recusa iniciar sem confirmar o limite rígido. O script configura e confere os pools do Ninja antes de compilar; preserve esse check. Android é gerado/ignorado, enquanto o código fonte do módulo fica em `modules/ffortuna-capture`.

Artefato esperado: `artifacts/ffortuna-capture-poc-arm64.apk` com SHA-256 e relatório de inspeção ao lado. Instalação por transferência local/ADB, sem publicar APK automaticamente. O usuário informou aprovação de todos os itens do roteiro manual no A36 após a correção. Não foram fornecidos resultados individuais, logs ou execução instrumentada; não extrapolar esse relato para garantia de captura sem perdas ou suporte a outros aparelhos.

## Pendências preservadas

- 18 alertas altos no npm decorrentes de duas falhas upstream sem patch na auditoria de 22/10; zero moderados após a correção preparada. Ver [dependencias.md](dependencias.md).
- Revisão técnica de licenças concluída para o APK: 71 pacotes npm, 189 coordenadas Maven, 23 bibliotecas nativas; zero pendências de textos identificadas. Sete lacunas resolvidas; as 46 restantes do inventário npm amplo não pertencem a este APK. Avisos incluídos no APK e na interface. Evidências e condições de distribuição em [revisao-licencas-apk.md](revisao-licencas-apk.md). Pré-release [`v0.3.0-alpha.1`](https://github.com/richardheise/FFortuna/releases/tag/v0.3.0-alpha.1) publicada em 25/10/2026 com nove anexos. Os hashes informados pelo GitHub foram comparados com os arquivos locais.
- Backup portátil cifrado/restauração implementados para a caixa sintética, ainda sem validação física. Segurança dos arquivos auxiliares foi coberta por testes de dispositivo compilados, não executados. Amostras reais e suporte Nubank continuam bloqueados; validar também retenção de caixas inativas e logs/temporários. Não importar dados financeiros reais no Expo Go.
- Próximo passo: executar o roteiro 0.3.0 no A36, incluindo backup local, senha errada, alternância e restauração após reinstalar sem a chave original. Depois validar instrumentação, retenção de caixas inativas e arquivos/logs antes de diagnóstico real.

## Git

Remoto `origin`: https://github.com/richardheise/FFortuna.git, push por SSH, branch main. Checkpoint de código `11174020cc5df9236041674fd44783b2f3225a2c`, tag imutável `v0.3.0-alpha.1`. O código foi enviado e a pré-release foi publicada em 25/10/2026 após autenticar o GitHub CLI como `richardheise`. A publicação contém nove anexos, incluindo APK, fonte correspondente, licenças, recibo e checksums; a consulta pública confirmou que é pré-release, não rascunho, com digest correto do APK. As atualizações posteriores desta documentação não alteram o APK nem a tag do fonte.

O CLI fica em `.local/github-cli/gh`; sua configuração separada usa `GH_CONFIG_DIR=/home/heise/FFortuna/.local/github-cli/config`, com acesso restrito e ignorada pelo Git. Essa autenticação é independente das contas Codex. Não copiar credenciais para o repositório. As notas públicas estão em [releases/v0.3.0-alpha.1.md](releases/v0.3.0-alpha.1.md).

Checkpoint de validação: testar o roteiro manual 0.3.0 no A36 e informar resultados de salvar, senha errada, alternância e reinstalação/restauração. Nenhuma amostra real deve ser capturada antes dessas condições. Os artefatos locais estão em `artifacts/`, com recibo e checksums.

## Conferir a publicação

[Pré-release](https://github.com/richardheise/FFortuna/releases/tag/v0.3.0-alpha.1) · [download direto do APK](https://github.com/richardheise/FFortuna/releases/download/v0.3.0-alpha.1/ffortuna-capture-poc-arm64.apk).

```sh
GH_CONFIG_DIR=/home/heise/FFortuna/.local/github-cli/config .local/github-cli/gh release view v0.3.0-alpha.1 --repo richardheise/FFortuna
(cd artifacts && sha256sum -c SHA256SUMS)
```

Os nove anexos foram conferidos por tamanho e digest SHA-256 antes da publicação. O acesso público também foi verificado após publicar. Para uma versão nova, gerar novos fonte/checksums e usar outra tag; não mover a tag desta distribuição.
