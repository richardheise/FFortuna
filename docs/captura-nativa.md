# Prova de captura nativa — Android

Esta entrega acrescenta um APK de prova exclusivamente sintético. A demonstração financeira continua no Expo Go. O APK usa `org.ffortuna.capturepoc`, com tela de diagnóstico própria; não cria outro livro financeiro nem aceita notificações bancárias ou arquivos pessoais.

Aparelho informado pelo usuário: Samsung Galaxy A36, SM-A366E/DS, Android 16. O usuário instalou a versão 0.2.0 e encontrou a falha descrita abaixo. Após a correção 0.2.1 disponibilizada, o usuário relatou em 25/10/2026 que testou o APK e todos os itens do roteiro manual passaram. Esse relato é da prova nativa, separado da aprovação anterior da demonstração Expo Go. Também em 25/10/2026, após instalar a versão 0.3.0, relatou sucesso em todos os oito passos do roteiro de backup/restauração, incluindo reinstalação e persistência.

## Funcionamento

Kotlin implementa NotificationListenerService, emissor de notificações fictícias, caixa cifrada e recuperação. A interface usa Expo Modules API para comandos e consultas. A captura não depende de callbacks para JavaScript.

Antes de ler extras/conteúdo, o listener verifica consentimento local, pacote próprio e ausência de flag de resumo de grupo. Só aceita o marcador `ffortuna.synthetic.v1` e uma geração UUID do emissor. O acesso Android ao listener é amplo; o filtro local não transforma a permissão do sistema em permissão por aplicativo. Nenhum banco é selecionável nesta prova.

Uma fila nativa única é dona das migrações, gravações, parsing e leituras. A observação é confirmada no banco antes de interpretar. O processamento posterior só modifica pendências; reabrir a caixa recupera as pendências. O envelope conserva fonte, pacote, ID local, identidade, revisão, publicação, captura e payload. O parsing preserva versão, campos e evidências; a data efetiva vem da fixture e não é inferida da publicação. A interpretação não gera lançamento financeiro neste incremento.

A identidade combina a chave Android da notificação e a geração criada pelo emissor. O fingerprint considera payload e publicação. Reentrega idêntica é idempotente; atualização preserva observações anteriores com nova revisão. Duas compras iguais com gerações distintas permanecem separadas. Resumos de grupo são ignorados, e dispensar uma notificação não apaga o registro.

## Cifra, chave e retenção

SQLCipher Community 4.19.1 abre a caixa nativa; `cipher_version` não vazio é obrigatório. A chave aleatória de 32 bytes é encapsulada por AES-256-GCM com uma chave não exportável do Android Keystore. AAD vincula o encapsulamento à caixa. Só ciphertext/IV são persistidos em `noBackupFilesDir`; a chave em memória é necessária para abrir o banco.

Não exigir biometria por operação permite usar a chave com a tela bloqueada depois do primeiro desbloqueio. A caixa usa armazenamento CE e serviço sem Direct Boot; não promete captura antes desse desbloqueio. Esta prova não implementa bloqueio biométrico da interface.

Ausência/invalidação da chave, autenticação GCM incorreta ou banco de versão futura bloqueiam a operação sem gerar uma chave substituta sobre o banco. Reset exige confirmação explícita e apaga apenas a caixa ativa e sua chave, preservando uma eventual caixa anterior e os backups externos. A versão 0.3.0 acrescenta backup portátil com senha, sem payloads brutos. A restauração cria outra chave local e não precisa da chave da instalação original. O roteiro manual de backup foi aprovado no A36; dados reais continuam bloqueados até concluir também os testes instrumentados, a verificação de arquivos/logs e a retenção das caixas inativas.

Backup automático é desabilitado e regras de extração excluem banco, arquivos e preferências de cloud/device-transfer. A configuração final do APK precisa ser inspecionada; o comportamento de fabricantes ainda exige teste. Não há logs de conteúdo emitidos pelo código. O logger Java do SQLCipher é desativado; isso não comprova por si a ausência de todos os logs das dependências.

O bruto de todas as observações, inclusive pendentes, expira após 30 dias da captura, quando o núcleo executar. Metadados e parsing existente são preservados; observações sem bruto ficam identificadas e não entram na contagem de pendências processáveis. Desde a 0.4.0, a limpeza inclui a caixa anterior sem ativá-la. Chave anterior indisponível sinaliza limpeza bloqueada e pausa captura; seu descarte exige ação explícita. Os cenários sintéticos de retenção passaram na bateria da interface no A36, conforme relato do usuário em 26/10/2026. Não há promessa de execução de limpeza durante force-stop. A verificação de integridade/cabeçalho é diagnóstica e não substitui a validação de todos os arquivos e do backup para dados reais.

## Compilar localmente

O ambiente preparado tem JDK Temurin 17.0.21+9, SDK API 36, Build Tools 36.0.0, NDK 27.1.12297006 e CMake 3.22.1 em `.local/android-toolchain`. O Gradle também solicitou Build Tools 35.0.0 para dependências. Checksums dos downloads e licença padrão gratuita do SDK aceita estão no README local dessa pasta. Nenhum EAS, conta Expo ou serviço de build é necessário.

```sh
cd /home/heise/FFortuna
nvm use
npm ci
npm run native:build
```

`FFORTUNA_JAVA_HOME` e `ANDROID_HOME` podem apontar para instalações próprias. A compilação requer rede para baixar ferramentas/dependências; a operação do APK empacotado é local. O script usa modo capture-poc e embute JavaScript no build release, dispensando Metro no celular. Permissão INTERNET é removida no manifesto por configuração; a ausência deve ser conferida no APK final.

O script exige Linux/systemd com controle de memória e falha sem o limite confirmado. Toda a árvore do build recebe MemoryMax=12 GiB, MemoryHigh=10 GiB, zero swap e cota de até 16 CPUs, conforme autorização posterior do usuário. Gradle usa um worker, sem projetos paralelos, heap de 3 GiB e Kotlin no processo. O pool de compilação do Ninja é limitado a 16 jobs; o linker continua em um e o Metro em dois workers, verificado antes de compilar o app. Não elevar os limites automaticamente. O computador reiniciou durante uma tentativa anterior; os registros consultados não confirmaram a causa. A proteção é uma exigência desta sessão, não uma otimização opcional.

O projeto `android/` é gerado e ignorado no Git. Um fingerprint de configuração preserva esse diretório entre builds equivalentes; alterações em config/plugin/manifesto do projeto podem exigir novo prebuild. O código Kotlin original fica em `modules/ffortuna-capture`, fora do diretório gerado.

A assinatura usa uma chave privada local em `.local/keys/`. Preserve essa pasta para instalar atualizações da prova sem trocar a assinatura. Não use a chave padrão pública do template, não envie chaves/senhas ao Git e não trate essa assinatura de teste como decisão de distribuição em loja.

Artefato esperado: `artifacts/ffortuna-capture-poc-arm64.apk`, com SHA-256 ao lado. Versão 0.3.0 (versionCode 3), build concluído e inspeções aprovadas em 25/10/2026. APK com 46.147.788 bytes. SHA-256: `46778d77d2294031697f99f52a736f0f218341564f9859b4732e7779597b42f6`.

## Instalar no A36

Transfira o APK do servidor SSH para o computador/celular usando seu acesso existente; não é necessário publicar o arquivo na internet. Por exemplo, no computador local, substituindo o destino pelo mesmo host usado no SSH:

```sh
scp usuario@servidor:/home/heise/FFortuna/artifacts/ffortuna-capture-poc-arm64.apk .
```

Instale pelo gerenciador de arquivos ou por ADB com depuração USB autorizada:

```sh
adb install -r ffortuna-capture-poc-arm64.apk
```

ADB permite instalação de desenvolvimento sem cadastro de desenvolvedor. As políticas de verificação/instalação do Android variam por região e canal; se houver bloqueio na instalação manual, siga o [fluxo oficial](https://developer.android.com/developer-verification/guides/faq) apropriado, sem desabilitar proteções globais.

Abra **FFortuna · Captura sintética**. Autorize as notificações do emissor fictício, ative o consentimento local e abra o acesso a notificações do Android para conceder o acesso a esta prova. Se aparecer Configuração restrita, consulte [orientação oficial do Android](https://support.google.com/android/answer/12623953) para permitir essa configuração especificamente neste app. Volte ao diagnóstico e confira caixa pronta, autorizado, conectado e emissor autorizado. Use Atualizar estado se a conexão ainda estiver em andamento.

## Roteiro funcional

Comece com a caixa vazia. Os totais abaixo pressupõem uma execução de cada botão, sem publicações extras:

| Ação | Resultado esperado |
| --- | --- |
| Publicar exemplo | 1 observação interpretada; nenhuma transação financeira. |
| Reler notificações ativas e repetir | Continua 1 observação. |
| Atualizar último exemplo | 2 observações; mesma fonte/geração, nova revisão, bruto e parsing anteriores preservados. |
| Duas compras iguais | Total 4; duas identidades adicionais, apesar do mesmo valor/descrição. |
| Dois filhos + resumo | Total 6; resumo não entra como movimentação. |
| Dispensar as notificações na cortina e atualizar | Total continua 6. |
| Agendar exemplo com app fechado | A partir de 15 segundos, sujeito a atrasos do Android. Vá à tela inicial e bloqueie; depois reabra/atualize: total esperado 7. Não usar force-stop nessa etapa. |
| Reler/reprocessar novamente | Sem duplicação; nenhuma pendência quando o processamento terminar. |
| Verificar cifra e integridade | SQLCipher ativo, integridade e cabeçalho verificados pelo diagnóstico. |
| Fechar/reabrir app e reiniciar aparelho | Dados anteriores preservados; após primeiro desbloqueio, verificar reconexão e publicar outro exemplo. |

Registre o horário de publicação e captura separadamente. O agendamento não é exato e pode atrasar em Doze/economia de bateria. Faça a primeira rodada com configurações padrão da Samsung; depois compare com economia de bateria, registrando eventuais diferenças. Não presumir ausência de perdas ou atribuir falha ao banco antes de conferir a conexão do listener.

### Botões desabilitados e correção 0.2.1

Em 25/10/2026, a instalação no A36 foi confirmada. O usuário concedeu o acesso às notificações e viu conexão ativa, mas a caixa aparecia indisponível e o consentimento não podia ser ativado. Não foi registrado sucesso de captura nesse teste.

Foi identificado um erro determinístico da versão 0.2.0: `PRAGMA secure_delete=ON` retorna uma linha, mas a inicialização o executava com `execSQL`. O [driver da versão 4.19.1](https://github.com/sqlcipher/sqlcipher-android/blob/v4.19.1/sqlcipher/src/main/jni/sqlcipher/android_database_SQLiteConnection.cpp) rejeita `SQLITE_ROW` nesse caminho. A versão 0.2.1 usa consulta com cursor e verifica o retorno, mantendo a proteção ativada. A abertura falhava antes das migrações; a atualização reaproveita a chave e a caixa existentes, sem reset automático.

Instale a versão 0.2.1 por cima da anterior, com a mesma assinatura; não desinstale nem apague os dados para aplicar essa correção. Volte à seção 1, confira **Caixa cifrada: pronta** e toque em **Concordo: ativar captura somente sintética**. Confira também o acesso Android e a permissão do emissor, depois use **Publicar exemplo**. Após as orientações de atualização, o usuário informou aprovação do roteiro manual no aparelho.

A tela atualizada explica cada requisito pendente junto dos botões. Uma falha da caixa exibe um identificador como `[SECURITY/SQLiteException]`, com etapa e tipo da exceção, sem expor mensagem original, SQL, caminho, payload ou chave. Se continuar indisponível, registre esse identificador; não use o botão de apagar como tentativa de reparo.

Teste também revogação e reconcessão do acesso, desativação do consentimento e processo encerrado. Para force-stop, o comportamento esperado é interrupção até reabrir o app e, se necessário, reconceder/reconectar o acesso. Não prometer captura durante force-stop; alarmes também podem ser cancelados. Após interrupção, o reprocessamento recupera o que já chegou ao banco; notificações que não chegaram não podem ser reconstruídas integralmente.

## Testes e limites de evidência

- Em 25/10/2026, o usuário relatou que todos os oito passos do roteiro 0.3.0 funcionaram perfeitamente no A36/Android 16: backup local, rejeição de senha errada, restauração em nova caixa, alternância, reinstalação sem a chave antiga e persistência/integridade após reabrir. É validação manual do usuário, separada da execução instrumentada.

- Em 25/10/2026, o usuário relatou aprovação de todos os itens do roteiro manual no Samsung Galaxy A36 SM-A366E/DS, Android 16, após receber a correção 0.2.1. Não forneceu resultados individuais ou logs; não se atribui esse relato à execução dos testes instrumentados.

- TypeScript e 30 testes da demonstração/URLs passaram após aplicar o lockfile corrigido.
- Treze testes JVM Kotlin foram executados e passaram: filtro/identidade, privacidade do diagnóstico, PBKDF2/AES-GCM, vetor independente OpenSSL com senha UTF-8, senha errada, adulteração, custo/cabeçalho/tamanhos inválidos, identidades e validação do modelo portátil.
- Treze testes instrumentados foram escritos: persistência SQLCipher, retomada, perda/tamper/recuperação atômica de chave, regressão 0.2.0, backup sem bruto, restauração sem chave antiga, preservação/alternância da caixa anterior, staging interrompido, dados inválidos e buscas de canários sintéticos em banco/chave/backup/journal/WAL/SHM. Compilaram com `npm run native:build -- --compile-device-tests`; a execução exige dispositivo/emulador e não foi realizada.
- `adb devices -l` não encontrou aparelho conectado neste ambiente; `/dev/kvm` não está disponível. Nenhum emulador foi iniciado, preservando recursos do PC.
- Build release local e inspeção final aprovados: assinatura, ausência de INTERNET, backup desabilitado, JavaScript embutido, avisos de licença incluídos e 23 bibliotecas ELF/ZIP alinhadas a 16 KB. Relatório em `artifacts/apk-inspection.json`. Esses checks não comprovam execução no aparelho.

Para repetir só os testes JVM: `npm run native:test`. Os testes de dispositivo estão em `modules/ffortuna-capture/android/src/androidTest`. Os resultados locais do JUnit ficam em `modules/ffortuna-capture/android/build/test-results/`.

A prova não aceita dados reais e não valida o pacote/conteúdo Nubank. O backup portátil cobre somente esta caixa sintética, não o banco financeiro da demonstração Expo Go. Antes de amostras reais, completar as condições do alinhamento de segurança, incluindo restauração sem a chave original, perda de chave, logs/temporários, retenção e verificação de todos os arquivos. O roteiro 0.3.0 abaixo foi aprovado manualmente pelo usuário no A36. A retenção de caixas inativas e a bateria sintética foram aprovadas pelo usuário no roteiro 0.4.0. Próximo incremento: diagnóstico protegido temporário com consentimento e pacote verificado no aparelho. O runner JUnit Android permanece sem execução; o teste sintético não valida parsing Nubank.

A [revisão de licenças do APK](revisao-licencas-apk.md) registra componentes, textos e fonte correspondente para distribuição.

## Backup portátil — roteiro 0.3.0

A seção **4. Backup e restauração** salva somente as observações e resultados de parsing da prova. Não exporta payloads brutos, senha, chave SQLCipher/Keystore ou consentimento. O arquivo `.ffortuna` é cifrado antes de sair do app; não há cópia em claro ou em cache. A [decisão de formato](adr/0006-backup-portatil-da-caixa.md) registra KDF, limites e alternativas.

Escolha **Armazenamento interno → Download**, ou outra pasta local, no seletor Android. O app solicita destinos locais, mas o seletor e seus provedores são componentes externos; `EXTRA_LOCAL_ONLY` não garante comportamento de todos os provedores. Não escolha nuvem. A operação pode ser testada em modo avião e não exige EAS, Metro, conta externa, permissão de armazenamento amplo ou INTERNET no APK. Um erro/interrupção do provedor pode deixar um arquivo cifrado incompleto, que não será aceito como backup válido.

Use uma frase de teste com 12 a 128 caracteres. Não há recuperação de senha nem normalização automática de espaços/acentos. Os campos são limpos ao iniciar a operação ou sair da tela; autofill é desativado. Isso não é uma promessa de remoção de todas as cópias de memória das plataformas.

1. Instale a versão 0.3.0. Como o app foi desinstalado, a caixa começará vazia. Autorize novamente quando necessário, ative a captura e publique um exemplo e duas compras iguais: total esperado **3**. Anote um ID e seu resultado de parsing.
2. Digite a senha e a confirmação. Toque **Criar backup cifrado**, escolha uma pasta local e espere a confirmação de salvamento. Guarde arquivo e senha fora dos dados privados do app. O arquivo não deve entrar no Git; `.ffortuna` está ignorado.
3. Publique mais um exemplo: total **4**. Tente restaurar o arquivo com outra senha válida em comprimento: deve ocorrer erro de autenticação e o total continuar **4**, sem caixa substituída.
4. Informe a senha correta no primeiro campo e toque **Restaurar backup em nova caixa**. Confirme e escolha o arquivo: total **3**, mesmos IDs/horários/parsing do backup, captura **desativada**, caixa anterior preservada. O bruto não é restaurado. Se o backup tiver observações pendentes, elas aparecem como **sem bruto**, sem reprocessamento possível; os resultados já interpretados continuam disponíveis.
5. Use **Voltar à caixa anterior**: total **4**. Volte novamente: total **3**. Nenhuma mesclagem deve ocorrer. Uma segunda restauração exige **Descartar caixa anterior** com confirmação; restaurar o mesmo arquivo continua produzindo **3**, sem duplicar.
6. Para provar independência do Keystore, confira que o arquivo está em uma pasta local fora do app e que você guardou a senha. Desinstale **apenas a prova sintética**, reinstale este APK e restaure o arquivo. O total deve voltar a **3**, com identidades/parsing preservados e captura desativada, apesar da perda da chave anterior. Reabrir/reiniciar deve preservar a caixa restaurada.
7. Cancele os seletores de salvar/abrir: a caixa deve continuar igual. Teste senha errada também depois da reinstalação. Registre os tempos de criar/restaurar no A36, pois o custo de 600.000 iterações precisa de medição física.

A restauração autentica o arquivo completo, valida o formato sintético e importa em transação numa caixa SQLCipher nova. Um marcador durável permite limpar staging interrompido na próxima abertura. Somente depois da verificação de integridade são publicados o novo ponteiro ativo, a caixa anterior e o consentimento desativado, numa gravação de preferências. Não há processamento concorrente pelo JavaScript. A caixa anterior também é cifrada, mas depende da chave local antiga; se ela foi perdida, ainda será possível descartar a caixa antiga e manter a restauração.

O usuário aprovou **todos os oito passos do roteiro manual 0.3.0 no A36/Android 16 em 25/10/2026**, após receber as instruções nesta conversa. Não foram medidos tempos nem executados os testes instrumentados pelo agente. Os 13 testes instrumentados compilaram e usam um sandbox próprio da biblioteca; sua execução segue pendente. Os anexos da pré-release e seus checksums preservam o snapshot original de publicação; a aprovação posterior é registrada nesta documentação e nas notas públicas.

## Recuperação automática e segurança — roteiro 0.4.0

Atualize por cima da versão 0.3.0, sem desinstalar: a assinatura e o pacote são os mesmos. Este incremento permanece exclusivamente sintético. O roteiro de backup portátil 0.3.0 já foi aprovado pelo usuário; seus controles agora ficam em **Configurações avançadas**, sem exigir uso no dia a dia.

O padrão mantém até **3 cópias privadas cifradas**, sem bruto, no máximo uma por **24 horas** e apenas com alteração nos registros/parsing. A primeira cópia é criada quando houver registros e o núcleo executar. Não é necessário escolher uma pasta ou senha. As cópias internas são perdidas ao desinstalar e dependem de sua chave local; somente o **backup portátil com senha**, salvo fora do app, pode proteger a reinstalação. As cópias cobrem a caixa sintética, não o livro Expo Go.

1. Abra o APK e confira a política/resumo de recuperação local na tela normal. Deve mostrar “pronta”, inicialmente zero cópias se a caixa estiver vazia. Com registros existentes, a primeira cópia deve surgir automaticamente. Feche e reabra: registros e cópia permanecem.
2. Em **Configurações avançadas**, liste as cópias locais. Anote a quantidade de observações da mais recente. Publique um novo exemplo, aguarde e atualize o estado: o total aumenta, mas não deve haver outra cópia antes de 24 horas. A rotação dos dias é testada com relógio isolado no passo 5; não altere o relógio do celular.
3. Restaure a cópia listada e confirme: volta à quantidade anotada, preserva IDs/parsing, sem bruto; captura desativada e caixa anterior preservada. Se já houver caixa anterior, a interface exige voltar a ela ou descartá-la explicitamente antes de outra restauração. Use **Voltar à caixa anterior** para recuperar o exemplo posterior, sem mesclar. Nenhuma busca de arquivos é necessária.
4. Opcionalmente, desative as cópias e confira que a lista continua disponível. **Apagar cópias locais e desativar** exige confirmação, remove somente as cópias e mantém observações. Reative para voltar ao padrão. Não é necessário apagar nada para continuar o roteiro.
5. Em **Configurações avançadas → Verificações de segurança**, execute a bateria e aguarde. A captura é pausada. São **30 verificações**: 28 cenários compartilhados com a suíte instrumentada, limpeza de testes interrompidos e janela dos logs próprios. Todos usam arquivos/chaves isolados e fixtures sintéticas; não alteram a caixa ativa. Informe os contadores aprovados/falhas/não verificados e, se houver, os IDs/códigos das falhas. Logs indisponíveis podem aparecer como “não verificado”; isso não equivale a aprovação nem deve ser escondido. Os testes incluem rotação/recuperação das cópias, adulteração, retenção ativa/anterior, chave perdida, banco ausente/incorreto, WAL e staging interrompido.
6. Confira que o total e IDs da caixa ativa permanecem como antes da bateria, que a captura ficou desativada e que **Verificar cifra e integridade** funciona. Reative a captura sintética, publique um exemplo e confirme persistência após reabrir. O APK continua sem Metro e sem INTERNET; pode testar em modo avião.

A retenção de bruto é de 30 dias da captura e ocorre quando o núcleo executar, inclusive na caixa anterior inativa. Horários e parsing permanecem. Chave antiga indisponível sinaliza **limpeza bloqueada** e pausa captura; nenhuma caixa é recriada sobre dados existentes. O descarte explícito da anterior fica nas configurações avançadas. Isso evita exigir controle de arquivos pelo usuário e evita esconder uma falha da política.

**Evidências desta versão:** testes JVM/TypeScript e inspeção do APK são executados no ambiente de build. Os 28 testes instrumentados foram compilados. Em 26/10/2026, o usuário relatou **30 verificações aprovadas, zero falhas e zero não verificadas** na bateria da interface no A36/Android 16 e confirmou o funcionamento do restante do roteiro. Não foram fornecidos logs, relatório exportado ou tempos. A bateria compartilha os callbacks da suíte, mas não é execução pelo runner JUnit; essa execução instrumentada continua pendente, sem aparelho ADB neste servidor. Um log aprovado cobre somente as sondas e a janela desse processo; não é garantia universal. Dados reais continuam bloqueados.

## Fontes técnicas

[Expo: build local](https://docs.expo.dev/guides/local-app-production/), [Expo Modules API](https://docs.expo.dev/modules/overview/), [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [SQLCipher Android 4.19.1](https://github.com/sqlcipher/sqlcipher-android/tree/v4.19.1), [Keystore](https://developer.android.com/privacy-and-security/keystore) e [páginas Android de 16 KB](https://developer.android.com/guide/practices/page-sizes).
