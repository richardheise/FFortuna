import React, { useState } from 'react';
import { Text, View } from 'react-native';
import type { MovementKind, Reserve, ReviewInput, TransferDirection } from '../core/types';
import { formatBRL, moneyInput, parseBRL } from '../core/money';
import { today, validDate } from '../core/dates';
import { Action, Chip, ErrorBox, Field, Label, styles } from './common';

const categories = ['Moradia', 'Alimentação', 'Transporte', 'Saúde', 'Lazer', 'Renda', 'Outros'];
export function TransactionForm({ initial, onSave, busy, submitLabel = 'Salvar lançamento', reserves = [], requireKind = false }: { initial?: Partial<ReviewInput>; onSave: (input: ReviewInput) => Promise<void>; busy: boolean; submitLabel?: string; reserves?: Reserve[]; requireKind?: boolean }) {
  const [kind, setKind] = useState<MovementKind | null>(initial?.kind ?? (requireKind ? null : 'expense'));
  const [direction, setDirection] = useState<TransferDirection>(initial?.direction ?? 'deposit');
  const [reserveId, setReserveId] = useState<string | null>(initial?.reserveId ?? null);
  const [amount, setAmount] = useState(initial?.amountCents == null ? '' : moneyInput(initial.amountCents));
  const [date, setDate] = useState(initial?.effectiveDate ?? today());
  const [description, setDescription] = useState(initial?.description ?? '');
  const [category, setCategory] = useState(initial?.category ?? 'Outros');
  const [notes, setNotes] = useState(initial?.notes ?? '');
  const [error, setError] = useState<string | null>(null);
  async function submit() {
    try {
      setError(null);
      const selectedKind = kind;
      if (!selectedKind) throw new Error('Escolha se é despesa, receita ou movimentação.');
      const amountCents = parseBRL(amount);
      validDate(date);
      if (!description.trim()) throw new Error('Informe uma descrição.');
      if (selectedKind === 'transfer' && !reserveId) throw new Error('Escolha a caixinha desta movimentação.');
      await onSave({ amountCents, kind: selectedKind, effectiveDate: date, description: description.trim(), category, notes: notes.trim(), reserveId: selectedKind === 'transfer' ? reserveId : null, direction: selectedKind === 'transfer' ? direction : null });
    } catch (e) { setError(e instanceof Error ? e.message : 'Não foi possível salvar. Tente novamente.'); }
  }
  function chooseKind(next: MovementKind) {
    setKind(next);
    if (next !== 'transfer') { setReserveId(null); setDirection('deposit'); }
    else if (kind !== 'transfer') setDirection('deposit');
  }
  return <View style={{ gap: 11 }}>
    <View style={styles.wrap}>{(['expense','income','transfer'] as MovementKind[]).map(item => <Chip key={item} title={item === 'expense' ? 'Despesa' : item === 'income' ? 'Receita' : 'Movimentação'} active={kind === item} onPress={() => chooseKind(item)} />)}</View>
    {kind === 'transfer' && <View style={styles.wrap}><Chip title="Guardar" active={direction === 'deposit'} onPress={() => setDirection('deposit')} /><Chip title="Resgatar" active={direction === 'withdraw'} onPress={() => setDirection('withdraw')} /></View>}
    {kind === 'transfer' && <View style={{ gap: 7 }}><Label>Caixinha</Label>{reserves.length ? <View style={styles.wrap}>{reserves.map(reserve => <Chip key={reserve.id} title={reserve.name} active={reserveId === reserve.id} onPress={() => setReserveId(reserve.id)} />)}</View> : <Text style={styles.small}>Crie uma caixinha em Meu dinheiro para registrar movimentações.</Text>}</View>}
    <Field label="Valor (R$)" value={amount} onChangeText={setAmount} keyboardType="decimal-pad" placeholder="0,00" />
    <Field label="Data (AAAA-MM-DD)" value={date} onChangeText={setDate} autoCapitalize="none" placeholder="2026-01-31" />
    <Field label="Descrição" value={description} onChangeText={setDescription} placeholder="Ex.: mercado" />
    <View style={styles.wrap}>{categories.map(item => <Chip key={item} title={item} active={category === item} onPress={() => setCategory(item)} />)}</View>
    <Field label="Observação" value={notes} onChangeText={setNotes} placeholder="Opcional" />
    <ErrorBox message={error} />
    <Action title={busy ? 'Salvando…' : submitLabel} onPress={() => void submit()} disabled={busy} />
  </View>;
}

export function AmountDateForm({ amountLabel = 'Valor (R$)', initialAmount = '', initialDate = today(), onSave, busy, button = 'Salvar' }: { amountLabel?: string; initialAmount?: string; initialDate?: string; onSave: (amount: number, date: string) => Promise<void>; busy: boolean; button?: string }) {
  const [amount, setAmount] = useState(initialAmount);
  const [date, setDate] = useState(initialDate);
  const [error, setError] = useState<string | null>(null);
  async function submit() { try { setError(null); const cents = parseBRL(amount); validDate(date); await onSave(cents, date); } catch (e) { setError(e instanceof Error ? e.message : 'Confira os dados e tente novamente.'); } }
  return <View style={{ gap: 10 }}><Field label={amountLabel} value={amount} onChangeText={setAmount} keyboardType="decimal-pad" placeholder="0,00" /><Field label="Data (AAAA-MM-DD)" value={date} onChangeText={setDate} placeholder="2026-01-31" /><ErrorBox message={error} /><Action title={busy ? 'Salvando…' : button} onPress={() => void submit()} disabled={busy} /></View>;
}

export function AmountForm({ amountLabel = 'Valor (R$)', initialAmount = '', onSave, busy, button = 'Salvar' }: { amountLabel?: string; initialAmount?: string; onSave: (amount: number) => Promise<void>; busy: boolean; button?: string }) {
  const [amount, setAmount] = useState(initialAmount);
  const [error, setError] = useState<string | null>(null);
  async function submit() { try { setError(null); await onSave(parseBRL(amount)); } catch (e) { setError(e instanceof Error ? e.message : 'Confira o valor e tente novamente.'); } }
  return <View style={{ gap: 10 }}><Field label={amountLabel} value={amount} onChangeText={setAmount} keyboardType="decimal-pad" placeholder="0,00" /><ErrorBox message={error} /><Action title={busy ? 'Salvando…' : button} onPress={() => void submit()} disabled={busy} /></View>;
}

export function MoneyText({ cents, color }: { cents: number; color?: string }) { return <Text style={[styles.body, { color: color ?? styles.body.color, fontWeight: '700' }]}>{formatBRL(cents)}</Text>; }
