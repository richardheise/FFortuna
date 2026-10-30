import React, { useState } from 'react';
import { Text, View } from 'react-native';
import type { FinanceService, Snapshot } from '../core/types';
import { moneyInput, parseBRL, formatBRL } from '../core/money';
import { today, validDate } from '../core/dates';
import { Action, Card, ErrorBox, Field, SectionTitle, colors, styles } from './common';
import { TransactionForm } from './forms';

type Props = { service: FinanceService; snapshot: Snapshot; month: string; busy: boolean; run: (task: () => Promise<void>) => Promise<void>; newId: () => string; refresh: (month?: string) => Promise<void> };
export function Overview({ service, snapshot, month, busy, run, newId, refresh }: Props) {
  const [openingDate, setOpeningDate] = useState(today());
  const [openingBalance, setOpeningBalance] = useState('');
  const [income, setIncome] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  async function setup() {
    try {
      setLocalError(null); validDate(openingDate);
      const balanceCents = parseBRL(openingBalance);
      const expectedIncomeCents = parseBRL(income || '0');
      await run(async () => { await service.setup({ operationId: newId(), openingDate, balanceCents, expectedIncomeCents }); await refresh(month); });
    } catch (e) { setLocalError(e instanceof Error ? e.message : 'Confira os dados do saldo inicial.'); }
  }
  const profile = snapshot.profile;
  return <View style={{ gap: 14 }}>
    {!profile ? <Card><Text style={styles.title}>Vamos começar</Text><Text style={styles.body}>Informe o saldo da conta e desde quando ele vale. Esse saldo é o que você tinha antes dos lançamentos desta data.</Text><Field label="Saldo inicial (R$)" value={openingBalance} onChangeText={setOpeningBalance} keyboardType="decimal-pad" placeholder="0,00" /><Field label="Data de abertura (AAAA-MM-DD)" value={openingDate} onChangeText={setOpeningDate} placeholder="2026-01-01" /><Field label="Renda prevista por mês (R$)" value={income} onChangeText={setIncome} keyboardType="decimal-pad" placeholder="0,00" /><ErrorBox message={localError} /><Action title={busy ? 'Salvando…' : 'Criar meu resumo'} onPress={() => void setup()} disabled={busy} /></Card> : <>
      <Card style={{ backgroundColor: colors.card }}><Text style={styles.small}>SALDO ATUAL DA CONTA</Text><Text style={{ fontSize: 31, fontWeight: '700', color: colors.cyan }}>{formatBRL(snapshot.balanceCents)}</Text><Text style={styles.small}>Com lançamentos desde {profile.openingDate}</Text></Card>
      <View style={{ flexDirection: 'row', gap: 10 }}><Card style={{ flex: 1 }}><Text style={styles.small}>Receitas recebidas</Text><Text style={{ color: colors.cyan, fontSize: 18, fontWeight: '700' }}>{formatBRL(snapshot.receivedCents)}</Text></Card><Card style={{ flex: 1 }}><Text style={styles.small}>Despesas</Text><Text style={{ color: colors.red, fontSize: 18, fontWeight: '700' }}>{formatBRL(snapshot.spentCents)}</Text></Card></View>
      <Card><SectionTitle title="Resumo mensal" /><View style={{ flexDirection: 'row', justifyContent: 'space-between' }}><Text style={styles.body}>Renda prevista</Text><Text style={[styles.body, { fontWeight: '700' }]}>{formatBRL(profile.expectedIncomeCents)}</Text></View><Text style={styles.small}>A renda prevista é uma estimativa e não entra nas receitas recebidas.</Text></Card>
      <Card><SectionTitle title="Gastos por categoria" />{snapshot.categories.length ? snapshot.categories.map(item => <View key={item.name} style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 5 }}><Text style={styles.body}>{item.name}</Text><Text style={[styles.body, { fontWeight: '600' }]}>{formatBRL(item.amountCents)}</Text></View>) : <Text style={styles.small}>Ainda não há despesas neste mês.</Text>}</Card>
      <Action title={formOpen ? 'Fechar lançamento' : '＋ Novo lançamento'} onPress={() => setFormOpen(!formOpen)} secondary />
      {formOpen && <Card><Text style={styles.heading}>Lançamento manual</Text><TransactionForm reserves={snapshot.reserves} busy={adding || busy} onSave={async input => { setAdding(true); try { await run(async () => { await service.addManual({ ...input, operationId: newId() }); await refresh(month); setFormOpen(false); }); } finally { setAdding(false); } }} /></Card>}
      <Card><SectionTitle title="Caixinhas e compromissos" /><View style={{ flexDirection: 'row', justifyContent: 'space-between' }}><Text style={styles.body}>Guardado</Text><Text style={[styles.body, { fontWeight: '700' }]}>{formatBRL(snapshot.reservesCents)}</Text></View><View style={{ flexDirection: 'row', justifyContent: 'space-between' }}><Text style={styles.body}>Dívidas</Text><Text style={[styles.body, { fontWeight: '700' }]}>{formatBRL(snapshot.debtCents)}</Text></View></Card>
    </>}
  </View>;
}
