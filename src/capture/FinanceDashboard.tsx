import { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Action, Card, colors, styles } from '../ui/common';
import { formatBRL } from '../core/money';
import { buildDashboard } from './dashboard';
import type { FinanceSnapshot } from './native';

type Props = {
  snapshot: FinanceSnapshot; busy: boolean;
  onCategory: (key: string) => void;
  onMovements: (kind?: 'income' | 'expense') => void;
  onNew: (kind: 'income' | 'expense') => void;
  onBalance: () => void;
  hidden: boolean; onToggleValues: () => void;
};
const percentage = (bps: number) => { const tenths = Math.floor((bps + 5) / 10); return `${Math.floor(tenths / 10)},${tenths % 10}%`; };

export function FinanceDashboard({ snapshot, busy, onCategory, onMovements, onNew, onBalance, hidden, onToggleValues }: Props) {
  const [expanded, setExpanded] = useState(false);
  const model = useMemo(() => {
    try { return buildDashboard(snapshot); } catch { return null; }
  }, [snapshot]);
  const money = (value: number) => hidden ? '••••' : formatBRL(value);
  const check = snapshot.balanceCheck;
  if (!model) return <Card><Text style={styles.error}>Não foi possível conferir os totais do resumo. Os registros foram preservados.</Text><Action title="Abrir movimentos" secondary onPress={() => onMovements()} /></Card>;
  const categories = expanded ? model.categories : model.categories.slice(0, 5);
  return <>
    <Card style={{ borderLeftWidth: 3, borderLeftColor: colors.gold }}>
      <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}>
        <Text style={dashboard.kicker}>SALDO CALCULADO</Text>
        <Pressable accessibilityRole="button" accessibilityLabel={hidden ? 'Mostrar valores do resumo' : 'Ocultar valores do resumo'} onPress={onToggleValues} style={dashboard.linkTarget}>
          <Text style={dashboard.link}>{hidden ? 'Mostrar valores' : 'Ocultar valores'}</Text>
        </Pressable>
      </View>
      <Text style={dashboard.balance}>{money(snapshot.balanceCents)}</Text>
      <Text style={styles.small}>Conta disponível · todos os movimentos registrados</Text>
      <Pressable accessibilityRole="button" disabled={busy} onPress={onBalance} style={dashboard.linkTarget}>
        <Text style={{ color: check?.differenceCents ? colors.red : colors.cyan, fontSize: 13 }}>
          {check ? `Conferência de ${check.date.split('-').reverse().join('/')} · ${check.differenceCents === 0 ? 'sem diferença' : `diferença ${money(check.differenceCents)}`}` : 'Saldo ainda não conferido · conferir'}
        </Text>
      </Pressable>
    </Card>
    <View style={styles.wrap}>
      {model.comparison.map(row => <Pressable key={row.kind} accessibilityRole="button" accessibilityLabel={`${row.label} do mês: ${money(row.amountCents)}. Abrir movimentos.`} disabled={busy} onPress={() => onMovements(row.kind)} style={dashboard.metric}>
        <Text style={{ color: row.kind === 'income' ? colors.cyan : colors.gold, fontSize: 14 }}>{row.kind === 'income' ? '↗' : '↙'} {row.label}</Text>
        <Text style={dashboard.metricValue}>{money(row.amountCents)}</Text>
      </Pressable>)}
    </View>
    <Card>
      <Text style={styles.small}>Resultado do mês selecionado · receitas menos despesas</Text>
      <Text style={{ color: model.resultCents < 0 ? colors.red : colors.cyan, fontSize: 23, fontWeight: '700' }}>{money(model.resultCents)}</Text>
    </Card>
    <Card>
      <Text style={styles.heading}>Gastos por categoria</Text>
      {categories.length === 0 ? <Text style={styles.small}>Nenhuma despesa confirmada neste mês.</Text> : <>
        <Text style={styles.small}>Participação nas despesas do mês. Toque para filtrar os movimentos.</Text>
        {categories.map(row => <Pressable key={row.key} accessibilityRole="button" accessibilityLabel={`${row.name}: ${money(row.amountCents)}, ${hidden ? 'percentual oculto' : percentage(row.shareBps)}. Ver despesas.`} disabled={busy} onPress={() => onCategory(row.key)} style={dashboard.chartRow}>
          <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}>
            <Text style={[styles.body, { flexShrink: 1 }]}>{row.name || 'A classificar'}</Text>
            <Text style={styles.label}>{money(row.amountCents)}</Text>
          </View>
          <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={dashboard.track}>
            {!hidden && <View style={{ height: 6, width: `${row.shareBps / 100}%`, backgroundColor: colors.gold }} />}
          </View>
          {!hidden && <Text style={[styles.small, { textAlign: 'right' }]}>{percentage(row.shareBps)}</Text>}
        </Pressable>)}
        {model.categories.length > 5 && <Action secondary title={expanded ? 'Mostrar cinco categorias' : `Ver todas (${model.categories.length})`} onPress={() => setExpanded(value => !value)} />}
      </>}
    </Card>
    <Card>
      <Text style={styles.heading}>Receitas × despesas</Text>
      <Text style={styles.small}>Comparação do mês selecionado, na mesma escala.</Text>
      {model.comparison.map(row => <Pressable key={row.kind} accessibilityRole="button" accessibilityLabel={`${row.label}: ${money(row.amountCents)}. Ver movimentos.`} disabled={busy} onPress={() => onMovements(row.kind)} style={dashboard.chartRow}>
        <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}><Text style={styles.body}>{row.label}</Text><Text style={styles.label}>{money(row.amountCents)}</Text></View>
        <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={[dashboard.track, { height: 12 }]}>
          {!hidden && <View style={{ height: 12, width: `${row.widthBps / 100}%`, backgroundColor: row.kind === 'income' ? colors.cyan : colors.gold }} />}
        </View>
      </Pressable>)}
      {snapshot.incomeCents === 0 && snapshot.expenseCents === 0 && <Text style={styles.small}>Sem movimentos ativos para comparar.</Text>}
    </Card>
    <View style={styles.wrap}>
      <Action title="+ Receita" secondary disabled={busy} onPress={() => onNew('income')} />
      <Action title="+ Despesa" disabled={busy} onPress={() => onNew('expense')} />
    </View>
    <Card>
      <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}><Text style={styles.heading}>Movimentos recentes</Text><Action title="Ver todos" secondary disabled={busy} onPress={() => onMovements()} /></View>
      {model.recent.length === 0 && <Text style={styles.small}>Os movimentos confirmados deste mês aparecerão aqui.</Text>}
      {model.recent.map(row => <View key={row.id} style={{ gap: 4, borderTopWidth: 1, borderColor: colors.line, paddingTop: 12 }}>
        <Text style={styles.label}>{row.description || 'Sem descrição'}</Text>
        <Text style={styles.small}>{row.effectiveDate.split('-').reverse().join('/')} · {row.category}</Text>
        <Text style={{ color: row.kind === 'income' ? colors.cyan : colors.ink, fontWeight: '600', fontSize: 16 }}>{row.kind === 'income' ? '+ ' : '− '}{money(row.amountCents)}</Text>
      </View>)}
    </Card>
  </>;
}

const dashboard = StyleSheet.create({
  kicker: { color: colors.gold, fontSize: 12, fontWeight: '700', letterSpacing: 1 },
  balance: { color: colors.ink, fontSize: 34, fontWeight: '700', fontVariant: ['tabular-nums'] },
  metric: { flexGrow: 1, flexBasis: 140, minHeight: 106, padding: 18, gap: 14, backgroundColor: colors.card, borderRadius: 4, borderWidth: 1, borderColor: colors.line },
  metricValue: { color: colors.ink, fontSize: 22, fontWeight: '700', fontVariant: ['tabular-nums'] },
  link: { color: colors.cyan, fontSize: 12 }, linkTarget: { minHeight: 48, justifyContent: 'center' },
  chartRow: { gap: 8, minHeight: 64, paddingVertical: 8 }, track: { height: 6, backgroundColor: colors.pale, width: '100%' },
});
