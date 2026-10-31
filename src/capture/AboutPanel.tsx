import { Text } from '../ui/Typography';
import { Card, styles } from '../ui/common';
export function AboutPanel() {
  return <>
    <Card><Text style={styles.heading}>Sobre o Tique</Text><Text style={styles.body}>Acompanhe suas entradas e gastos, organize suas categorias e veja para onde vai seu dinheiro.</Text></Card>
    <Card><Text style={styles.heading}>Como os movimentos chegam</Text><Text style={styles.body}>Adicione manualmente, importe um extrato ou autorize as notificações do Nubank. Confira os itens recebidos antes de confirmar. Notificações podem faltar, e os gráficos mostram apenas os registros confirmados.</Text><Text style={styles.small}>Transferências, valores guardados e pagamentos de fatura não são novos gastos. Compras no cartão entram como gasto uma vez.</Text></Card>
    <Card><Text style={styles.heading}>Seus dados ficam no aparelho</Text><Text style={styles.body}>O Tique funciona sem internet, sem conta externa e sem enviar seus dados. O armazenamento é criptografado. O app não conhece sua senha bancária nem movimenta seu dinheiro.</Text><Text style={styles.small}>O Android pode interromper a captura. Acesso a notificações não dá acesso ao seu saldo ou ao histórico do banco. Valores de caixinhas e dívidas dependem dos seus registros.</Text></Card>
    <Card><Text style={styles.heading}>Cópias e recuperação</Text><Text style={styles.body}>O app mantém cópias locais dos registros confirmados. Elas ajudam a recuperar alterações, mas são removidas ao desinstalar. Para trocar de aparelho, salve uma cópia com senha fora do app.</Text><Text style={styles.small}>Guarde o arquivo e a senha. Pendências ainda não confirmadas e o texto original das notificações não entram nessas cópias. Originais são apagados após 30 dias de acordo com a política de retenção.</Text></Card>
    <Card><Text style={styles.heading}>Software livre</Text><Text style={styles.body}>Tique é software livre, sob a licença AGPL-3.0. Fonte Space Grotesk, de Florian Karsten, sob SIL Open Font License. Código e avisos estão disponíveis nas configurações e no repositório richardheise/Tique.</Text></Card>
  </>;
}
