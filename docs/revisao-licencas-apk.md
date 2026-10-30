# Revisão de licenças — APK de captura sintética

Revisão técnica concluída em 23/10/2026 e reconferida em 26/10/2026 na versão 0.6.0 para o APK `capture-poc`, release, arm64-v8a. Não foram encontradas pendências de textos de licença ou de atribuição no inventário identificado abaixo. Isso não equivale a validar o funcionamento no celular ou a certificar juridicamente qualquer distribuição futura.

## Escopo e evidências

| Entrada examinada | Cobertura |
| --- | --- |
| Source map composto pelo Metro/Hermes | 63 pacotes npm com código JavaScript. |
| Autolinking React Native/Expo | 22 pacotes com integração nativa; união com JavaScript: 71 pacotes, incluindo o módulo original FFortuna. |
| Grafo Android release e POMs | 189 coordenadas Maven, incluindo BOMs/metadados. É um conjunto conservador; não significa que cada artefato inteiro seja distribuído. |
| AAR/JAR do grafo | LICENSE/NOTICE, inclusive dentro de `classes.jar`; 53 ocorrências de avisos extraídas. |
| Bibliotecas `.so` do APK | Todas as 23 vinculadas ao artefato publicado ou ao projeto que as compila. |
| Componentes C/C++ incorporados | 24 registros suplementares de bibliotecas, subcomponentes e avisos. |
| Textos resultantes | 81 textos distintos, deduplicados sem remover atribuições. |

O inventário verificável está em [apk-components.json](../legal/apk-components.json), com versões, hashes dos artefatos, origem dos textos e pendências. Os suplementos npm, Maven, C/C++ e os avisos extraídos ficam em `legal/`. Licença de pasta superior não é herdada automaticamente; POMs seguem somente a herança explícita de parent do Maven. Os textos dos monorrepositórios foram associados às versões/commits publicados.

O inventário npm amplo de produção inclui ferramentas e dependências opcionais que não entram neste APK. Das 53 lacunas inicialmente apontadas ali, sete pertenciam ao JavaScript empacotado: quatro subpacotes React Native, Expo Router, `@expo/ui` e `standard-navigation`. Os sete receberam textos upstream com evidência versionada. As 46 lacunas restantes desse inventário amplo não aparecem na união de pacotes identificada neste APK. Isso não dispensa nova revisão se a distribuição ou suas dependências mudarem.

Em `standard-navigation@0.0.5`, o pacote publicado já declarava MIT, mas seu gitHead não continha LICENSE. O texto foi obtido do commit upstream posterior que corrigiu essa ausência, com essa cronologia explícita em [npm-supplements.json](../legal/npm-supplements.json). Não se atribuiu esse arquivo retroativamente ao commit publicado.

## Obrigações contempladas

- Código original FFortuna: AGPL-3.0-only, texto completo em `LICENSE` e no APK. Distribuir o fonte correspondente à mesma revisão do APK, com os arquivos necessários à compilação; não basta apontar para uma branch que depois pode mudar.
- Dependências MIT/ISC/BSD/Apache e fontes de ícones: textos e copyrights preservados. Avisos próprios encontrados nos arquivos distribuídos foram mantidos além do texto genérico da licença. As fontes Material Symbols conservam também a licença Apache 2.0.
- SQLCipher Android/core, LibTomCrypt e runtime C++ do NDK: textos específicos incluídos. O SQLCipher usado aqui emprega LibTomCrypt; não se presumiu OpenSSL. Para Zstd foi selecionada a alternativa BSD disponibilizada pelo upstream.
- IJG: incluído o reconhecimento exigido por libjpeg, junto de seus demais textos.
- Public Suffix List do OkHttp 4.9.2: MPL-2.0 e NOTICE preservados; a fonte correspondente está em [legal/source/okhttp-4.9.2-public_suffix_list.dat](../legal/source/okhttp-4.9.2-public_suffix_list.dat). O recurso comprimido do APK coincide com o do tag upstream, SHA-256 `a1ef3d0e4ec2cbce14c1884ab0f3a4fef46e258de83530dc562ebb805bdb12fd`.

As regras de preservação de licença/NOTICE vêm dos próprios textos, incluindo [Apache 2.0, seção 4](https://www.apache.org/licenses/LICENSE-2.0), [AGPL v3](https://www.gnu.org/licenses/agpl-3.0.html) e [MPL 2.0](https://www.mozilla.org/en-US/MPL/2.0/). Não foi copiado código dos projetos Finan+ ou PennyWise.

Os avisos revisados estão acessíveis em **Sobre e licenças → Bibliotecas** no modo de captura. Também existem como assets `assets/ffortuna-legal/THIRD_PARTY_NOTICES.txt` e `LICENSE.txt`, comparados byte a byte com os arquivos locais durante a inspeção do APK. A demonstração Expo Go continua mostrando o inventário npm amplo.

## Repetir e atualizar

Com Node 24, dependências instaladas, toolchain e saídas da compilação disponíveis:

```sh
node scripts/generate-notices.mjs
python3 scripts/audit-apk-notices.py --check
npm run typecheck
```

O `--check` compara o inventário, os textos da interface e os assets com os inputs atuais, sem regenerá-los. Em outra máquina, os POMs pai ausentes do cache podem precisar de download do Maven Central. Isso pertence à revisão/build, não à operação do APK.

Depois de mudar dependências, primeiro obtenha o novo source map/grafo release. Execute `python3 scripts/audit-apk-notices.py` para atualizar os textos, investigue qualquer componente não resolvido e recompile com `npm run native:build`. A inspeção final exige avisos compatíveis com o APK e interrompe a entrega se encontrar divergência. Uma alteração de dependências pode fazer o primeiro build falhar nesse check até os textos serem atualizados; isso é esperado. O auditor usa o APK anterior por padrão; `--apk caminho.apk` permite examinar o recém-gerado em `android/app/build/outputs/apk/release/app-release.apk`.

## Artefatos e publicação

APK 0.3.0 recompilado com os avisos, backup e restauração: `artifacts/ffortuna-capture-poc-arm64.apk`, 46.147.788 bytes, SHA-256 `46778d77d2294031697f99f52a736f0f218341564f9859b4732e7779597b42f6`.

Verificados: TypeScript, inventário sem pendências identificadas, avisos dentro do APK, assinatura, backup desabilitado, bundle offline, ausência de INTERNET e alinhamento ELF/ZIP a 16 KB das 23 bibliotecas. Após a correção 0.2.1, o usuário relatou aprovação do roteiro manual no Galaxy A36/Android 16 em 25/10/2026. Também em 25/10/2026, o usuário aprovou os oito passos do roteiro manual 0.3.0 de backup/restauração no mesmo aparelho, incluindo reinstalação. Os testes instrumentados continuam não executados.

Os artefatos locais incluem os avisos separados, um arquivo com o fonte atual e recibo com hashes em `artifacts/license-review.json`. O arquivo de fonte exclui ferramentas locais, bancos, chaves, credenciais e saídas de compilação. As dependências são fixadas pelo lockfile e pelos arquivos de build; não se afirma reprodução binária byte a byte.

A versão 0.3.0 usa somente APIs criptográficas JCA do Android e os contratos de atividade do Expo já instalado, sem novas dependências distribuídas. O inventário e os avisos foram reconferidos e permanecem sem pendências identificadas.

A revisão de licenças não havia publicado release/tag. No checkpoint posterior, o usuário autorizou commit/push e avaliação da pré-release `v0.3.0-alpha.1`. A [pré-release](https://github.com/richardheise/Tique/releases/tag/v0.3.0-alpha.1) foi publicada após autenticar o GitHub CLI, com nove anexos e seus hashes remotos conferidos. O fonte corresponde ao commit `11174020cc5df9236041674fd44783b2f3225a2c` da tag `v0.3.0-alpha.1`; atualizações posteriores de documentação não mudam esse snapshot. Se houver mudança de código após este checkpoint, recompilar e atualizar fonte/checksums antes de anexar. O nome e as notas devem explicitar **captura exclusivamente sintética**, sem suporte Nubank ou dados financeiros reais.

## Reconferência 0.4.0

APK 0.4.0 (versionCode 4), 46.207.716 bytes, SHA-256 `0ef198733e42f5e227a7b7bba4e7a22096da3d76603f618d801142fd68977085`. A recuperação automática/retenção e a bateria isolada usam APIs existentes Android/JCA; não foram adicionadas dependências. Inventário reconferido durante a inspeção final: 71 pacotes npm, 189 coordenadas Maven, 23 bibliotecas nativas, 24 suplementos C/C++ e zero pendências identificadas. Avisos dentro do APK coincidem com os arquivos revisados. Assinatura igual à 0.3.0; pacote e requisitos Android mantidos. Também foi verificada a ausência de READ_LOGS e permissões amplas de armazenamento.

TypeScript estrito, 30 testes TypeScript e 22 JVM aprovados; 28 testes de dispositivo compilados, não executados. Em 26/10/2026, o usuário aprovou o roteiro 0.4.0 no A36/Android 16 e relatou 30 verificações da interface aprovadas, zero falhas e zero não verificadas. É execução dos callbacks pela interface, informada pelo usuário, sem logs; o runner JUnit não foi executado. A nova distribuição usa `v0.4.0-alpha.1`, sem mover tags ou substituir anexos anteriores. Fonte exato, recibo, licenças e checksums acompanham o APK.

A [pré-release v0.4.0-alpha.1](https://github.com/richardheise/Tique/releases/tag/v0.4.0-alpha.1) foi publicada com nove anexos, conferidos por tamanho e digest remoto. Fonte correspondente ao commit `697fee04fa083de77c5903c18258986ca3ff1a47`; download público do APK confirmado HTTP 200. A tag e os anexos 0.3.0 permanecem intactos.

## Reconferência 0.5.0

APK 0.5.0/versionCode 5: 46.266.508 bytes, SHA-256 `45e1132681c0335b2b1112e16a215bada3fcfc26fcc99026c5b8986da1d44a38`. Diagnóstico local usa APIs Android/JCA já presentes, sem dependências novas. O inventário foi reconferido na inspeção final: 71 pacotes npm, 189 coordenadas Maven, 23 bibliotecas nativas e 24 suplementos C/C++, sem pendências identificadas. Avisos/AGPL no APK coincidem byte a byte com os textos revisados. Fonte, licenças, recibo e checksums devem acompanhar a nova tag.

TypeScript estrito, 30 testes TypeScript e 34 JVM aprovados; 38 testes de dispositivo compilados, sem execução pelo runner neste servidor. A 0.4.0 foi aprovada pelo usuário. Em 30/10/2026, o usuário aprovou também o fluxo funcional 0.5.0 no A36/Android 16, incluindo captura, original correto e revisão/exportação; não informou os contadores da bateria desta versão. Trata-se de coleta real opcional e consentida, sem parser/lançamentos: não rotular o APK inteiro como exclusivamente sintético. Nenhum dado real foi coletado no ambiente de desenvolvimento ou adicionado aos anexos.

A [pré-release v0.5.0-alpha.1](https://github.com/richardheise/Tique/releases/tag/v0.5.0-alpha.1) foi publicada em 26/10/2026 com nove anexos, conferidos por tamanho e SHA-256 remoto. O download público do APK respondeu HTTP 200. Fonte correspondente à tag no commit `72b1491203e081f56fb3d75cdc5db4e82671a4f6`, com 184 arquivos conferidos byte a byte. A aprovação funcional 0.5.0 foi informada pelo usuário em 30/10/2026; a publicação em si não equivale a testes e o runner JUnit Android permanece sem execução.

## Reconferência 0.6.0

APK 0.6.0/versionCode 6: 46.301.268 bytes, SHA-256 `b82bd30d7fe4ba79fd9a7a38f6cbd8acb6016762ef747a9f179997fdcb983815`. Extrator genérico BRL, migração e revisão usam dependências já presentes, sem componentes adicionais. Inventário reconferido na inspeção final: 71 pacotes npm, 189 coordenadas Maven, 23 bibliotecas nativas, 24 suplementos C/C++ e zero pendências identificadas. Avisos e AGPL dentro do APK coincidem com os textos revisados.

TypeScript estrito, 30 testes TypeScript e 50 JVM aprovados; 46 cenários Android compilados, sem execução do runner neste servidor. A 0.6.0 aguarda a bateria de 48 verificações no A36 e o novo roteiro de revisão. Parsing genérico não é suporte Nubank validado. Prévia/correções continuam temporárias, sem livro financeiro ou lançamento automático. Não houve amostra real no ambiente de desenvolvimento ou nos anexos.

## Mudança de marca para Tique — 0.6.1

Em 30/10/2026, o usuário solicitou renomear app/repositório para Tique. URL atual: https://github.com/richardheise/Tique. A mudança não altera a licença AGPL-3.0-only nem os avisos de terceiros. Só o prefácio próprio do arquivo de avisos e o link do fonte foram atualizados; o restante do texto de licenças foi comparado e preservado. O auditor --check aprovou o inventário sem alterações de dependências ou pendências identificadas.

O APK novo usa `tique-arm64.apk` e o fonte usa `tique-source.tar.gz`. O namespace Android, os formatos e caminhos internos legados permanecem para atualização sem perda de dados. O snapshot anterior 0.6.0 foi preservado em `artifacts/releases/v0.6.0-alpha.1/`; não substituir seus anexos ou mover a tag. A inspeção final e os novos hashes acompanham a entrega 0.6.1.

Resultado final 0.6.1: 50 testes JVM e 30 TypeScript aprovados, 46 cenários Android compilados; nenhuma execução pelo runner neste servidor. APK `tique-arm64.apk` de 46.301.248 bytes, SHA-256 `2c4e61ca9a66526c26cf4ab6d022ebd851c2c35ca9d56c28b5da18595baf34e9`. Nome Tique e assinatura igual verificados; inspeção de permissões/backup/offline/16 KB e comparação dos avisos aprovada. A nova tag leva fonte correspondente, preservando as distribuições antigas.

A [pré-release Tique v0.6.1-alpha.1](https://github.com/richardheise/Tique/releases/tag/v0.6.1-alpha.1) foi publicada com nove anexos conferidos por tamanho/digest remoto e download público HTTP 200. Fonte da tag no commit `0d42e2abc3e0f8d2527d9e7e5760182535631dd2`, 194 arquivos conferidos byte a byte. A renomeação do repositório não substituiu tags ou anexos anteriores. Publicar não equivale à validação no aparelho.


## Incremento 0.7.0 — categorias e tags

Catálogo e seleções usam as bibliotecas SQLCipher/Keystore e Expo Modules já presentes, sem novas dependências ou permissões. Inspeção e auditor `--check` do APK final aprovados: 71 pacotes npm, 189 coordenadas Maven, 23 bibliotecas nativas e 24 suplementos C/C++, zero pendências identificadas. Avisos e licença no APK conferidos byte a byte; assinatura preservada, backup Android desabilitado, bundle offline, sem INTERNET e alinhamento ELF/ZIP 16 KB aprovado.

Publicada a pré-release `v0.7.0-alpha.1`, fonte `b3a2dbad5a3c6e7bbeb595e42a8f386dc2e58d0f`, 203 arquivos conferidos. APK 0.7.0/code 8, 46.327.692 bytes, SHA-256 `04cc41bcafa659303bf70ac2aba3a514c3c681fb6a444cd33b7da0f19164b8a3`. Nove anexos remotos com tamanhos/hashes conferidos; API pública e download HTTP 200 sem autenticação. 30 testes TypeScript e 50 JVM aprovados; 58 cenários Android compilados, não executados por JUnit. Bateria da interface de 60 verificações e roteiro funcional desta versão aguardam validação física. A aprovação funcional informada pelo usuário nesta sessão refere-se à 0.6.1, sem contadores individuais. Catálogo e revisões reais permanecem fora dos backups sintéticos, limitação explícita na interface e no ADR 0010.


## Correção 0.7.1 — argumentos da consulta de classificação

Corrige parâmetros nulos de `rawQuery(String[])` sem nova dependência, permissão, schema ou chave. Inspeção e auditoria do APK aprovadas, mesmo inventário e avisos, mesma assinatura, sem INTERNET, backup Android desabilitado e 23 bibliotecas alinhadas a 16 KB. APK 0.7.1/code 9, 46.327.700 bytes, SHA-256 `43b3cfc0f7b380fffac12a66484b6f079719649a66cac86bc4e2bca27de2b4d5`. 50 testes JVM aprovados e 58 cenários Android compilados; JUnit Android não executado. TypeScript inalterado desde os 30 testes aprovados da 0.7.0, sem nova execução neste hotfix. Fonte `aab75fb65a540b35e2a3eb8855840640725c9181`, 204 arquivos conferidos; nove anexos remotos com tamanhos/hashes verificados e download público HTTP 200. A 0.7.0 teve nove falhas relatadas pelo usuário; a 0.7.1 aguarda nova execução das 60 verificações e do roteiro no aparelho. Tags e anexos anteriores preservados.


## Incremento 0.8.0 — livro e backup financeiro

Nenhuma dependência nova: livro, snapshots e restauração usam SQLCipher, Keystore/JCA e Expo Modules já presentes; UUID da interface usa o expo-crypto existente. O auditor `--check` do APK final manteve 71 pacotes npm, 189 coordenadas Maven, 23 bibliotecas nativas e 24 suplementos C/C++, zero pendências de textos identificadas. Avisos e AGPL no APK conferidos byte a byte. Pacote e certificado preservados, nome visível Tique, backup Android desabilitado também nas regras de transferência, bundle offline, sem INTERNET e bibliotecas alinhadas a 16 KiB.

54 testes JVM e 30 TypeScript aprovados, 70 cenários Android compilados. Os 72 checks locais da interface e o roteiro funcional ainda precisam ser executados no aparelho para a 0.8.0. Não há amostras reais no ambiente nem parser Nubank validado. APK 46.420.924 bytes, SHA-256 `3510dd68ce32ee74461c80d8bd6ac7caff5c40d7460ccb9b3a9d39007c73d979`. Fonte da nova tag, recibos, licenças e checksums devem acompanhar a distribuição, preservando tags e anexos antigos.


Publicação final: `v0.8.0-alpha.2`, APK versionCode 11, fonte `3cd74d93a82c96b3a48b5735679375ec86db9f32` (214 arquivos conferidos byte a byte). Nove hashes/tamanhos de anexos remotos conferidos antes da publicação e download público HTTP 200 sem autenticação. O primeiro candidato, `v0.8.0-alpha.1`, foi preservado como rascunho não publicado para ajustar a recarga do catálogo após restauração; nenhuma tag ou asset histórico foi substituído. A 0.8.0 ainda aguarda execução da bateria e do roteiro no celular.
