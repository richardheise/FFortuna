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
  onNew?: (kind: 'income' | 'expense') => void;
  onBalance?: () => void;
  hidden: boolean; onToggleValues: () => void;
};
const percentage = (bps: number) => { const tenths = Math.floor((bps + 5) / 10); return `${Math.floor(tenths / 10)},${tenths % 10}%`; };

export function FinanceDashboard({ snapshot, busy, onCategory, onMovements, hidden, onToggleValues }: Props) {
  const [expanded, setExpanded] = useState(false);
  const model = useMemo(() => {
    try { return buildDashboard(snapshot); } catch { return null; }
  }, [snapshot]);
  const money = (value: number) => hidden ? '••••' : formatBRL(value);
  if (!model) return <Card><Text style={styles.error}>Não foi possível carregar o resumo. Os registros foram preservados.</Text><Action title="Abrir movimentos" secondary onPress={() => onMovements()} /></Card>;
  const categories = expanded ? model.categories : model.categories.slice(0, 5);
  return <>
    <Card style={{ borderLeftWidth: 3, borderLeftColor: colors.gold }}>
      <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}>
        <Text style={dashboard.kicker}>RESULTADO DOS REGISTROS</Text>
        <Pressable accessibilityRole="button" accessibilityLabel={hidden ? 'Mostrar valores do resultado' : 'Ocultar valores do resultado'} onPress={onToggleValues} style={dashboard.linkTarget}>
          <Text style={dashboard.link}>{hidden ? 'Mostrar valores' : 'Ocultar valores'}</Text>
        </Pressable>
      </View>
      <Text style={dashboard.balance}>{money(model.resultCents)}</Text>
      <Text style={styles.small}>Entradas menos despesas registradas</Text>
    </Card>
    <View style={styles.wrap}>
      {model.comparison.map(row => <Pressable key={row.kind} accessibilityRole="button" accessibilityLabel={`${row.label} do mês: ${money(row.amountCents)}. Abrir movimentos.`} disabled={busy} onPress={() => onMovements(row.kind)} style={dashboard.metric}>
        <Text style={{ color: row.kind === 'income' ? colors.cyan : colors.gold, fontSize: 14 }}>{row.kind === 'income' ? '↗' : '↙'} {row.label}</Text>
        <Text style={dashboard.metricValue}>{money(row.amountCents)}</Text>
      </Pressable>)}
    </View>
    <Card>
      <Text style={styles.heading}>Gastos por categoria</Text>
      {categories.length === 0 ? <Text style={styles.small}>Nenhuma despesa confirmada neste mês.</Text> : <>
        <Text style={styles.small}>Parte das despesas do mês.</Text>
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
      <Text style={styles.small}>Totais do mês.</Text>
      {model.comparison.map(row => <Pressable key={row.kind} accessibilityRole="button" accessibilityLabel={`${row.label}: ${money(row.amountCents)}. Ver movimentos.`} disabled={busy} onPress={() => onMovements(row.kind)} style={dashboard.chartRow}>
        <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}><Text style={styles.body}>{row.label}</Text><Text style={styles.label}>{money(row.amountCents)}</Text></View>
        <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={[dashboard.track, { height: 12 }]}>
          {!hidden && <View style={{ height: 12, width: `${row.widthBps / 100}%`, backgroundColor: row.kind === 'income' ? colors.cyan : colors.gold }} />}
        </View>
      </Pressable>)}
      {snapshot.incomeCents === 0 && snapshot.expenseCents === 0 && <Text style={styles.small}>Sem movimentos ativos para comparar.</Text>}
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
