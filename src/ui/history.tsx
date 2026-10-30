import React, { useState } from 'react';
import { Text, View } from 'react-native';
import type { FinanceService, Snapshot } from '../core/types';
import { formatBRL } from '../core/money';
import { formatDate } from '../core/dates';
import { Card, SectionTitle, colors, styles } from './common';

type Props = { snapshot: Snapshot; service: FinanceService; run: (task: () => Promise<void>) => Promise<void> };
export function History({ snapshot, service, run }: Props) {
  const [opened, setOpened] = useState<string | null>(null);
  const [evidence, setEvidence] = useState<Record<string, string>>({});
  async function toggle(candidateId: string | null) {
    if (!candidateId) return;
    if (opened === candidateId) { setOpened(null); return; }
    setOpened(candidateId);
    if (evidence[candidateId]) return;
    await run(async () => {
      const detail = await service.evidence(candidateId);
      const original = detail.event.payload;
      const correction = detail.decision ? JSON.stringify(detail.decision, null, 2) : 'Sem correção manual';
      const extracted = JSON.stringify(detail.parsed.draft, null, 2);
      setEvidence(prev => ({ ...prev, [candidateId]: `Original: ${original}\n\nExtraído: ${extracted}\n\nCorreção: ${correction}` }));
    });
  }
  const transactions = snapshot.transactions.filter(item => item.effectiveDate.startsWith(snapshot.month)).sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate));
  return <View style={{ gap: 12 }}><Card><Text style={styles.heading}>Movimentações</Text><Text style={styles.small}>Lançamentos confirmados no período selecionado.</Text><View style={{ flexDirection: 'row', justifyContent: 'space-between' }}><Text style={styles.body}>Saldo atual da conta</Text><Text style={[styles.body, { fontWeight: '700' }]}>{formatBRL(snapshot.balanceCents)}</Text></View></Card>{transactions.length ? transactions.map(item => <Card key={item.id}>
    <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 8 }}><View style={{ flex: 1, gap: 3 }}><Text style={[styles.body, { fontWeight: '700' }]}>{item.description}</Text><Text style={styles.small}>{formatDate(item.effectiveDate)} · {item.category}</Text></View><Text style={[styles.body, { fontWeight: '700', color: item.cashDeltaCents < 0 ? colors.red : colors.cyan }]}>{formatBRL(item.amountCents)}</Text></View>
    <Text style={styles.small}>Valor do lançamento: {formatBRL(item.amountCents)} · impacto na conta: {formatBRL(item.cashDeltaCents)}{item.sourceCount > 1 ? ` · ${item.sourceCount} fontes` : ''}</Text>
    {item.candidateId && <><Text onPress={() => void toggle(item.candidateId)} accessibilityRole="button" style={{ color: colors.cyan, fontWeight: '700', minHeight: 44, paddingVertical: 12, textAlignVertical: 'center' }}>{opened === item.candidateId ? 'Ocultar evidência' : 'Ver evidência da origem'}</Text>{opened === item.candidateId && (evidence[item.candidateId] ? <Text selectable style={[styles.small, { fontFamily: 'monospace' }]}>{evidence[item.candidateId]}</Text> : <Text style={styles.small}>Carregando evidência…</Text>)}</>}
  </Card>) : <Card><Text style={styles.small}>Nenhum lançamento em {snapshot.month}.</Text></Card>}</View>;
}
