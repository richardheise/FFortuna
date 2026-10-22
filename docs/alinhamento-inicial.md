# FFortuna — Entendimento consolidado

Status: recorte confirmado pelo usuário; demonstração implementada. A discussão começou em 21/10/2026. TypeScript, 30 testes e bundle Android verificados. O usuário aprovou a demonstração no celular em 22/10/2026; a correção posterior de dependências aguarda instalação e conferência no aparelho. Execução e limites atuais em [README](../README.md) e [roteiro no aparelho](teste-no-celular.md).

## Objetivo e decisões

FFortuna homenageia a deusa romana Fortuna; o primeiro F vem de free, em referência a software livre. O objetivo é organizar a vida financeira do usuário a partir de sua única conta Nubank, caixinhas e crédito já comprometido. A captura deve ser a entrada principal no modo real, automatizando os casos seguros e concentrando a revisão nas exceções; cadastro manual e importação são alternativas permanentes.

| Decisão | Resultado |
| --- | --- |
| Plataforma (Q1) | Expo/React Native, TypeScript estrito e integração Android Kotlin. |
| Modelo financeiro (Q2) | Livro interno mínimo equilibrado, sem exigir conhecimento contábil na interface. |
| Primeiro ambiente (Q3) | Demonstração no Expo Go exclusivamente com dados sintéticos. |
| Licença (Q4) | AGPLv3, identificador AGPL-3.0-only; implementação original. |
| Visão mensal (Q5) | Renda prevista separada de recebimentos; gastos por categoria, saldo da conta e posições das caixinhas. |
| Crédito (Q6–Q7) | Um cartão com compromissos iniciais por vencimento, sem reconstrução obrigatória das compras antigas. |
| Início (Q8) | Posição atual com data de referência, indicando cobertura parcial do histórico. |
| Importação (Q9) | Arquivo que o Nubank exporta, interpretado pelo app, sem edição de planilha nem mapeamento de colunas pelo usuário. |
| Histórico anterior (Q10) | Rever explicitamente abertura e saldo do período, mantendo a posição atual como conferência, sem ajuste oculto. |
| Persistência (Q11) | Kotlin responsável pelo único banco do modo real; adaptador TypeScript/expo-sqlite apenas para demonstração. |
| Caixinhas (Q12) | Guardar/Resgatar transferem recursos; Atualizar valor informado registra ajuste explícito, sem presumir rendimento. |
| Automação demonstrada (Q13) | Incluir fixture sintética segura lançada automaticamente e fixture incompleta revisável. Substitui a proposta inicial de confirmação humana de todos os eventos. |
| Tela bloqueada (Q14) | Chave utilizável pelo núcleo sem biometria por operação; bloqueio da interface separado. |
| Retenção (Q15) | O usuário delegou a escolha; manter padrão de 30 dias para conteúdo bruto de notificações. |

ADRs relevantes: [plataforma](adr/0001-expo-com-captura-kotlin.md), [licença](adr/0002-licenca-agpl.md), [livro financeiro](adr/0003-livro-equilibrado-minimo.md), [banco nativo](adr/0004-nucleo-nativo-dono-do-banco.md) e [chave para segundo plano](adr/0005-chave-para-segundo-plano.md). Vocabulário em [GLOSSARY.md](../GLOSSARY.md).

## Primeira entrega executável: demonstração no Expo Go

Uma aplicação pequena, persistente, em português brasileiro e BRL. Todo campo e dado desta etapa é sintético; indicação permanente de demonstração, sem permissão de captura, importação arbitrária de arquivos pessoais ou carregamento do módulo nativo indisponível.

1. Iniciar uma conta fictícia, com data e saldo de abertura informados.
2. Informar renda mensal prevista sem criar automaticamente receita recebida.
3. Cadastrar receita/despesa manual, consultar saldo, histórico e resumo mensal por categoria.
4. Informar posições por caixinha e modalidade, com data de referência; guardar, resgatar e atualizar posição com efeitos distintos.
5. Informar compromissos já existentes de um cartão por vencimento; consultar total/distribuição mensal e registrar pagamento sem nova despesa.
6. Simular evidências inequivocamente fictícias: uma segura gera lançamento automático; outra incompleta gera candidato para revisão de valor, natureza, descrição, categoria e destino aplicável.
7. Manter vínculo entre transação e evidência, mostrar por que houve automação/revisão e preservar correções no reprocessamento.
8. Demonstrar importação com arquivo sintético fornecido pelo projeto, reconhecimento automático, prévia, confirmação e tratamento de duplicações/ambiguidades. OFX é o primeiro alvo técnico, condicionado à validação posterior do que o Nubank realmente exporta.
9. Demonstrar histórico anterior ao início com conferência explícita da abertura, sem somar novamente o saldo atual.
10. Reiniciar e preservar dados, vínculos e decisões.

Interface com resumo simples, histórico, revisão e formulários compactos; usar navegação padrão do Expo Router se adequada ao SDK selecionado. Não construir gráficos elaborados ou um editor completo de regras. Categorias incertas podem ficar A classificar. Regras determinísticas precedem qualquer tentativa futura de inteligência; não haverá IA nesta entrega.

O demonstrador cresceu para incluir a visão financeira solicitada, mas usa posições manuais e compromissos agregados. Motor completo de faturas/parcelamentos, novas compras parceladas, recorrências, orçamento, reconciliação avançada, projeção 30/60/90, despesas compartilhadas, importação de fatura/PDF e apuração de investimentos ficam para incrementos posteriores. Formatos adicionais não entram automaticamente no recorte.

## Regras financeiras e cobertura

BRL em centavos inteiros. Entrada monetária é interpretada como dígitos decimais, sem conversão monetária por ponto flutuante. Validar limites seguros e cada soma antes de gravar; qualquer valor e acumulador que atravesse TypeScript deve ser representável como inteiro seguro. No núcleo Kotlin também validar operações e conversões, sem confiar apenas na capacidade de Long ou INTEGER do SQLite.

Todo lançamento confirmado tem contrapartidas equilibradas. Saldos de abertura, passivo inicial e ajustes têm contrapartidas próprias; não são salário ou consumo. A interface não apresenta plano contábil configurável. Pagamento do cartão reduz dinheiro e obrigação; transferência própria, aporte e saque não são despesas; resgate e estorno não são receitas comuns. Tipos ainda não suportados ficam em revisão, sem conversão conveniente para receita/despesa.

Posições de caixinhas são valores informados, sem garantia de líquido resgatável ou liquidez. Guardar/Resgatar são movimentos patrimoniais. Atualizar valor informado calcula e exibe a diferença em relação à posição acompanhada, considerando movimentos conhecidos, antes de registrar ajuste explícito. Não inferir rendimento, impostos ou investimento de origem quando a evidência os omitir.

Compromissos anteriores do cartão não geram novos gastos no mês do cadastro. Sem as compras originais, não se conhece sua distribuição por categoria; vencimento não é data de consumo. O resumo identifica limites de cobertura e não calcula limite bancário disponível ou custo de financiamento.

Início rápido usa posição atual; ausência de registros não significa gasto zero. Incorporar histórico anterior exige nova abertura explícita e conferência com a posição atual já informada. Divergências permanecem visíveis para resolução; nenhuma receita, despesa ou correção de saldo é criada silenciosamente. Extrato importado não garante completude do período.

## Contrato comum de processamento

Demonstração, captura nativa e importação compartilham o mesmo contrato observável de entrada, processamento e resultado. Parsers são específicos da fonte; o simulador não finge ser uma notificação real do Nubank.

- Evento observado: identificador local, fonte, identidade disponível na fonte, revisão, publicação, captura e conteúdo/evidência. Horário efetivo é um campo distinto e pode ser desconhecido.
- Resultado de parsing: versão do parser, campos extraídos, referências às evidências e confiança por campo; não sobrescreve a observação original.
- Candidato: proposta revisável sem efeito no saldo, incluindo ambiguidades e campos insuficientes.
- Transação confirmada: decisão humana ou automática, contrapartidas, metadados e vínculo de origem. Correções têm procedência própria, distinta da extração.
- Reconciliação: vínculo entre evidências de uma movimentação, sem apagar a origem nem somar seus efeitos novamente. Reconciliação avançada fica para depois.

Automação exige critérios determinísticos verificáveis para movimentação, valor, natureza, conta/origem/destino aplicáveis e identidade sem conflito. Data efetiva desconhecida não pode ser substituída silenciosamente pela publicação; informação temporal insuficiente para um lançamento seguro exige revisão. Categoria incerta sozinha não bloqueia confirmação. As regras e seus motivos serão visíveis, sem alegar probabilidades calibradas a partir de fixtures.

Reentrega da mesma observação/revisão retorna o resultado existente; nova revisão guarda uma nova interpretação. Atualização contraditória preserva confirmação/correções e abre conflito para revisão. Duas compras iguais com identidades distintas são preservadas. Notificação e extrato da mesma movimentação exigem vínculo, não um segundo lançamento. Valor/dia/descrição não bastam para excluir registros. A dispensa de notificação não apaga transação; resumo de grupo não vira compra independente.

Confirmar é um comando idempotente: grava contrapartidas, vínculo e estado de processamento na mesma transação do banco. Restrição de unicidade e comparação da revisão sustentam essa garantia além do bloqueio de botão. Falha antes do commit não deixa lançamento parcial; repetir após commit devolve o resultado confirmado. Atualizações de campos financeiros não podem desequilibrar o livro ou substituir decisões anteriores sem rastro.

## Persistência, módulos e segunda etapa

Monólito modular: domínio financeiro, captura, parsing, classificação, persistência e interface com responsabilidades separadas. Projeção/reconciliação têm fronteiras previstas; não criar módulos vazios ou infraestrutura apenas para antecipar o roadmap.

No Expo Go, um adaptador TypeScript executa o fluxo sintético usando expo-sqlite, com migrações versionadas, comandos de escrita serializados, transações e restrições de integridade. Na implementação real, Kotlin é dono de um único banco SQLCipher Community, migrações, captura durável, processamento recuperável e operações financeiras. A interface chama operações tipadas via Expo Modules API; não abre outro livro. A migração de esquema termina antes de liberar comandos/processamento. Falha de migração/chave bloqueia gravação e deve ficar visível.

Preferir evidências e livro em tabelas do mesmo banco nativo cifrado, evitando protocolo entre bancos nesta etapa. Primeiro persistir a observação; depois processar, com recuperação por estado persistido e unicidade. Kotlin e JavaScript não competem como escritores independentes do banco real. O adaptador de demonstração não é sincronizado com o modo real; os mesmos cenários devem verificar equivalência dos contratos, sem afirmar equivalência com Kotlin antes de implementar e executar seus testes.

Logo após a demonstração, executar prova nativa no celular físico: build Android local, sem EAS/conta Expo, módulo Kotlin/Expo Modules API e NotificationListenerService. Consentimento explícito, seleção de bancos, pacote Nubank confirmado no aparelho, filtro antes de guardar conteúdo e estado de autorização/conexão visível. Não usar expo-notifications para ler notificações externas nem depender de eventos enviados somente ao JavaScript. Sem foreground service permanente por conveniência.

A prova começa com notificações sintéticas de um emissor de teste e armazenamento cifrado. Amostras reais só após cumprir as condições de segurança abaixo. Validar o conteúdo do Nubank, inclusive resgates, antes de habilitar automação de cada padrão. Ausência de campos ou de notificações permanece uma limitação; não prometer captura sem perdas. Essa prova vem antes de ampliar dashboards, investimentos ou importadores adicionais.

## Importação Nubank sem preparo técnico

No modo real, selecionar o arquivo exportado pelo Nubank, reconhecer o formato suportado e mostrar prévia financeira. Não pedir ao usuário que escreva parser, converta planilha, ajuste separadores ou associe colunas. Arquivo não reconhecido recebe mensagem clara; não tentar interpretar com regras genéricas inseguras. A primeira entrega exercita esse caminho com fixture sintética, sem seletor arbitrário de arquivos no Expo Go.

OFX é o primeiro alvo: [anúncio na NuCommunity](https://comunidade.nubank.com.br/novidades/post/exporte-extratos-diretamente-de-sua-conta-nubank-pelo-app-rbRYnw8qndyPd2S) descreve PDF/OFX para contas PF e PJ; o [blog Nubank](https://www.blog.nubank.com.br/extrato-bancario/) descreve solicitação e envio do extrato. Isso não comprova o arquivo atual deste usuário. Não foi localizado schema Nubank nem validado arquivo real. A [especificação OFX](https://www.financialdataexchange.org/common/Uploaded%20files/OFX%20files/OFX%202.2.pdf), seção 3.2.3, define FITID no escopo da conta/instituição; sua presença/estabilidade em exportações Nubank exige validação local.

Mesma importação repetida não duplica transações. Períodos sobrepostos usam identidade estável validada ou comparação assistida; conflitos não são descartados. Prévia separa novos, já vinculados e ambíguos, preserva metadados/correções e mostra linhas inválidas. Sinal de valor não comprova natureza financeira. Pagamentos de cartão, aportes e resgates precisam dos vínculos adequados; não viram consumo/receita por padrão.

## Segurança e funcionamento offline

Núcleo local, sem backend, conta externa, assinatura, credenciais bancárias, telemetria, IA remota/local, SMS, API privada Nubank, scraping, acessibilidade ou automação bancária. Não enviar notificações/extratos a terceiros. Abrir URL externa e obter dependências de desenvolvimento são operações distintas do núcleo.

No modo real, gerar chave aleatória do SQLCipher, protegida por chave de encapsulamento não exportável no Keystore. O núcleo poderá usar a chave sem biometria por operação para trabalhar com a tela bloqueada, após o primeiro desbloqueio desde reinício. A chave do banco precisa ser usada na memória nativa. Biometria protege a interface separadamente; não confundir com criptografia. Não prometer captura antes do primeiro desbloqueio ou enquanto o sistema impedir o serviço.

Antes de qualquer dado real, inclusive diagnóstico: verificar cifragem de banco/evidências/anexos necessários; ausência de logs/caches/temporários financeiros em claro; backups automáticos incompatíveis bloqueados; backup portátil cifrado com senha independente do Keystore e cifra autenticada; restauração sem a chave original e tratamento de senha errada/perda de chave sem sobrescrever dados. Escolher e validar biblioteca mantida de derivação/cifragem na etapa nativa. SQLCipher não protege automaticamente todos os arquivos do app. Importação deve evitar a cópia em cache padrão do seletor para dados reais; o arquivo original fora do app continua sob controle do usuário.

Reter conteúdo bruto de notificações por até 30 dias, inclusive pendentes, removendo expirados também na próxima execução após inatividade. Sem prometer limpeza executável durante force-stop. Preservar registros financeiros, decisões, campos necessários e vínculos; reprocessamento integral exige bruto ainda disponível. Backup portátil não inclui bruto por padrão. Diagnóstico local, temporário e cifrado; exportação explícita, anonimizada e cifrada. Exportações externas ficam sob controle do usuário. Nunca colocar dados reais, chaves ou credenciais no Git.

Objetivo para o APK release: retirar INTERNET e verificar o manifesto final e a operação offline. Metro/development build podem precisar de rede; baixar dependências não exige que o APK final tenha backend, EAS ou conta Expo.

## Verificações e conclusão de cada etapa

Primeira entrega: testar parsing/formatação BRL e limites; receitas/despesas e saldos; equilíbrio das contrapartidas; abertura/caixinhas/ajustes/pagamentos sem inflar renda ou gastos; renda prevista sem crédito automático; automação sintética e revisão; confirmação repetida; reprocessamento; duas compras iguais; atualização conflitante/correções preservadas; reimportação/períodos sobrepostos; abertura histórica com divergência; persistência e migrações com reabertura do banco. Testar a implementação SQLite real do adaptador, além do domínio puro.

Prova nativa: tela bloqueada, Doze, economia de bateria, reinício e primeiro desbloqueio, término do processo, force-stop e suas limitações, revogação/reconcessão, agrupamentos/atualizações e recuperação após interrupção. Validar cifragem, perda/restauração de chave/backup, retenção, filtros de origem e funcionamento sem runtime JS ativo. Registrar o aparelho/Android e separar testes escritos, executados e não disponíveis.

Só declarar a primeira entrega pronta após verificações disponíveis e roteiro de teste no celular, com comandos exatos realmente validados, resultados e incertezas. Se o aparelho não estiver acessível, não declarar teste físico executado. Os comandos executáveis e resultados desta entrega estão no [README](../README.md); a validação física permanece pendente.

## Investigação e limites conhecidos

Workspace começou vazio, com Git local em main. O estado de encerramento e publicação fica em [continuidade](continuidade.md). Nenhum código das referências foi copiado. Instruções RTK.md e AGENTS.md aplicáveis foram consultadas; não foi encontrado AGENTS.md adicional na pasta do app. Skills oficiais lidas na revisão [5beb35b](https://github.com/mattpocock/skills/tree/5beb35b040e3de97e54fa9e386d7e42d15c295c2): grill-with-docs, dependências grilling/domain-modeling e formatos ADR/GLOSSARY. Não existe ferramenta Skill nesta sessão; carregamento feito por leitura explícita dos arquivos.

| Fonte | Constatação e limite |
| --- | --- |
| [Finan+ 36379aad](https://github.com/finanplus-web/finan_plus_android/tree/36379aad7921f20bb1cc1903fce5f57e045792f3) | GPL-3.0-or-later, Kotlin/Compose, centavos Long, contas/cartões separados e testes de faturas/projeção. Modelo com exceções de receita/despesa e arquivo cifrado; não adotar integralmente como livro/SQLCipher/captura. Nenhum teste da referência foi executado. |
| [PennyWise 7a3ac7b](https://github.com/sarim2000/pennywiseai-tracker/tree/7a3ac7b4742dc320de9cb6e73a8ea70b1c9b695e) | AGPL v3, parsers separados, evidências Room e testes; deduplicação por conteúdo/janelas pode juntar compras legítimas. Não comprovado Nubank/PT-BR; SMS/IA e outras integrações excedem nosso recorte. Nenhum teste da referência foi executado. |
| [Expo SQLite](https://docs.expo.dev/versions/latest/sdk/sqlite/) | SQLite persistente no Expo Go; SQLCipher requer build próprio. PRAGMA key pode ser ignorado sem SQLCipher: verificar sua presença antes de dados reais. |
| [Build local](https://docs.expo.dev/guides/local-app-development/) e [Modules API](https://docs.expo.dev/modules/overview/) | Build Android local e integração Kotlin são suportados; Expo Go não executa módulo próprio. Compatibilidade completa ainda não compilada. |
| [DocumentPicker](https://docs.expo.dev/versions/latest/sdk/document-picker/) | Disponível no Expo Go, mas a configuração padrão copia arquivo para cache sem promessa de cifragem. |
| [Keystore](https://developer.android.com/privacy-and-security/keystore) e [SQLCipher Community](https://www.zetetic.net/sqlcipher/community/) | Base para desenho nativo; não substituem testes de proteção dos arquivos, chaves e restauração. |
| [Notificações Nubank](https://comunidade.nubank.com.br/novidades/post/por-onde-andam-as-notificacoes-do-app-vem-ca-ver-DN9VtTNRy1FrB8O) | Há comunicação sobre notificações de transações, sem schema/garantia de cobertura de resgates e campos. Pacote e amostras ainda não validados no aparelho. |

Ambiente inicialmente inspecionado: Node 26.8.2, npm 11.19.1, pnpm, Java 27; sem adb/sdkmanager no PATH nem Android SDK nos locais usuais. Preparar toolchain Android e JDK compatíveis antes da prova nativa. A [orientação React Native](https://reactnative.dev/docs/next/set-up-your-environment) recomenda JDK 17; preferir Node LTS suportado.

O [anúncio SDK 58](https://expo.dev/changelog/sdk-58) informa transição do Expo Go nas lojas. A versão do projeto deve corresponder ao Expo Go efetivamente usado; revalidar versões oficiais antes de instalar, fixar o conjunto compatível e não misturar pacotes de latest com SDK anterior. Essa verificação é trabalho técnico, sem exigir decisão de versão do usuário.

AGPLv3 foi escolhida para o aplicativo original. Inspiração conceitual, reutilização de componente e obra derivada são situações distintas: licença escolhida não autoriza copiar código/assets/marcas. Verificar material, compatibilidade e avisos antes de qualquer reutilização. LICENSE, README e gitignore estão incluídos; o inventário e seus limites estão em [avisos de dependências](../THIRD_PARTY_NOTICES.md).

## Próximo passo

Executar o [roteiro da demonstração no celular](teste-no-celular.md). O incremento imediatamente seguinte é a prova nativa com build Android local e persistência cifrada; dados reais continuam bloqueados até as condições de segurança serem satisfeitas.
