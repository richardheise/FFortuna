# Tique — proposta visual e de navegação, revisão 02

Status: **proposta para discussão, ainda não aplicada ao APK**. O usuário pediu menos curvas, um amarelo mais intenso puxando para laranja, manutenção do ciano e uso de off-white. A revisão 02 aplica essa direção; hierarquia, menus e composição continuam em avaliação. A validação física do livro 0.8.0 continua pendente e não deve ser considerada aprovada por esta discussão.

![Três telas conceituais, com dados fictícios](tique-interface-proposta-v2.png)

O [SVG autoral](tique-interface-proposta-v2.svg) é a fonte editável da imagem. São conceitos de telas Android, sem código de produto, dados bancários, assets ou capturas de concorrentes. Valores, movimentos e estados de conferência da imagem são fictícios.

## Direção visual

Interface escura, moderna e geométrica. O preto predomina; o ouro âmbar intenso marca identidade, ações e seleção; o ciano comunica informações, vínculos e receitas. Os valores e textos principais usam off-white quente. Um símbolo angular próprio, com oito faces e raios retos, acompanha o nome Tique. A marca usa tipografia simples; o desenho definitivo pode ser refinado depois.

| Uso | Cor |
| --- | --- |
| Fundo principal | `#080B10` |
| Cartões | `#121820` |
| Superfície elevada | `#1B2430` |
| Ouro âmbar / amarelo alaranjado | `#FFB020` |
| Ciano claro | `#9DECF4` |
| Texto principal off-white | `#F3EFE6` |
| Texto secundário | `#A2AEBE` |
| Divisórias | `#263240` |

Botões em ouro âmbar usam texto preto e preenchimento sólido. Cartões, campos, filtros e ações passam a retângulos com cantos de cerca de 4 dp; as barras dos gráficos têm extremidades retas. O botão central `+` também é retangular, com raio pequeno, e os ícones seguem traços mais angulares. As superfícies escuras mantêm a separação por contraste e linhas finas. Vermelho/coral pode ser usado funcionalmente para diferença negativa ou erro, sempre acompanhado de texto/ícone. Despesa normal não deve ser apresentada como erro.

Tipografia sem serifa, com números bem legíveis, alinhamento consistente de valores e espaço entre seções. Na implementação, respeitar escala de fonte do Android, área segura e alvos de toque de pelo menos 48 dp. Não depender só de cor para distinguir receita, despesa, seleção ou anulação. A composição apresentada é referência, não um tamanho fixo de tela.

## Navegação principal

Barra inferior com quatro destinos e um botão central de ação:

| Destino | Conteúdo |
| --- | --- |
| **Início** | Mês selecionado, saldo calculado da conta, receitas, despesas, resultado do mês, categorias e acesso aos movimentos recentes. |
| **Movimentos** | Histórico, busca, filtros por natureza/categoria/tag, detalhes e anulação de registro. |
| **+ Novo** | Painel curto para receita ou despesa. Outros tipos aparecem quando houver suporte financeiro correto. |
| **Revisão** | Observações/candidatos pendentes, campos incertos, evidência original e confirmação explícita no livro. |
| **Mais** | Conta, categorias/tags, conferência de saldo, proteção/backup, captura e configurações. |

O botão `+` abre uma ação; não é uma quinta página. Revisão recebe um contador somente quando houver itens pendentes. Categorias, tags, backup e testes não disputam espaço com o dashboard.

Em **Mais**, organizar por tarefas: **Finanças** (conta, categorias/tags, conferir saldo), **Proteção e dados** (backup/recuperação e, quando pronta, importação de extrato) e **Configurações** (captura/permissões, privacidade/aparência e ferramentas avançadas). Diagnóstico, testes e descarte de caixas ficam na área avançada. As cópias automáticas permanecem como padrão; tarefas com arquivos são opcionais.

Contas adicionais, cartões, faturas, caixinhas e planejamento poderão entrar em uma seção Carteira/Planejamento quando esses recursos estiverem implementados. Não apresentar páginas vazias ou controles desabilitados para simular recursos futuros. Busca e filtros na imagem também são propostas de interação, não funcionalidades já entregues no APK atual.

## Composição do dashboard

1. Seletor de mês acessível no topo, com voltar/avançar.
2. Cartão de **saldo calculado da conta disponível**, com controle para ocultar valores e estado real da última conferência. Saldo é atual, enquanto receitas/despesas/resultados se referem ao mês selecionado; os rótulos devem deixar esse recorte claro.
3. Dois cartões compactos para receitas e despesas do mês; resultado aparece separado como receitas menos despesas, sem chamar essa diferença de patrimônio, lucro ou economia garantida.
4. Gastos por categoria em barras horizontais ordenadas, com valor e percentual. Tocar em uma categoria abre os movimentos filtrados. Barras favorecem comparar valores e nomes longos; gráfico de rosca pode ser uma alternativa posterior, sem necessidade nesta primeira interface.
5. Movimentos recentes após as categorias, acessíveis ao rolar. O conceito estático mostra a primeira área visível; não é uma lista completa do conteúdo da tela.

Só usar dados do livro confirmado. Anulados não entram nos totais; transferências e pagamentos de fatura não viram despesas para alimentar gráficos. Meses vazios exibem estado vazio, sem dados inventados ou tendências fictícias. Uma diferença de conferência permanece visível, sem ajustar saldo automaticamente.

Na revisão, o resumo abre o formulário para conferir valor, natureza, categoria, data efetiva e conta. O visual não deve sugerir que o parser reconheceu campos que continuam desconhecidos; “Revisar e confirmar” não elimina as validações financeiras existentes. A captura segue com o consentimento e os limites reais da versão instalada.

## Histórico visual

A [primeira proposta](tique-interface-proposta.png) e seu [SVG](tique-interface-proposta.svg) foram preservados para comparação. A revisão 02 substitui o dourado suave e os cartões arredondados por ouro âmbar mais saturado, off-white e geometria mais reta. O ciano e os destinos de navegação foram mantidos. Nenhuma mudança foi aplicada ao código do produto ou ao APK.

## Referências consultadas

- [Organizze — apresentação oficial](https://www.organizze.com.br/): organização de contas/movimentos e entendimento de gastos por categoria. A referência aqui é a clareza das tarefas financeiras.
- [Wallet — dashboard e cartões](https://support.budgetbakers.com/hc/en-us/articles/7150077480850-Add-or-Modify-Dashboard-Cards-Widgets): organização da visão geral em cartões. Personalização com arrastar cartões fica fora desta proposta inicial.
- [Mobills — gerenciamento de categorias](https://mobills.zendesk.com/hc/pt-br/articles/360051317613-Como-criar-categorias-para-despesas-e-receitas): acesso às categorias pelo menu Mais e distinção entre receitas e despesas.

As referências são conceituais. Não foram reutilizados código, ícones, logos, screenshots ou textos de interface desses aplicativos. Serviços e integrações dos produtos de referência não fazem parte da proposta técnica do Tique.
