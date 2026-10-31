import { Text } from '../ui/Typography';
import { useEffect, useRef, useState } from 'react';
import { Alert, AppState, Pressable,  View } from 'react-native';
import { Action, Card, Chip, ErrorBox, styles } from '../ui/common';
import type { CaptureModule, ClassificationItem, FinanceTransaction } from './native';

type Scope = 'finance' | 'extended';
type Transaction = FinanceTransaction & { scope?: Scope };
type MetadataApi = {
  reclassifyFinanceTransactions(fieldsJson: string): Promise<{ status: 'ok' | 'error'; code?: string; updated?: number }>;
};
type Selection = { categoryId: string | null; category: string; tagIds: string[] };
type Props = {
  native: CaptureModule; busy: boolean; run: (task: () => Promise<unknown>) => Promise<void>;
  transactions: FinanceTransaction[]; onSaved: () => void;
  onCreateRule?: (input: { description: string; categoryId: string | null; category: string; tagIds: string[] }) => void;
  initialSelectedIds?: string[];
  valuesHidden?: boolean;
};

const failure = (code?: string) => ({
  NOT_FOUND: 'O movimento não está mais disponível.',
  TRANSACTION_VOIDED: 'Movimentos cancelados não podem ser reclassificados.',
  INVALID_CLASSIFICATION: 'Escolha categorias e tags disponíveis.',
  RULE_MISMATCH: 'A regra mudou. Reabra a prévia antes de aplicar.',
}[code ?? ''] ?? 'Não foi possível salvar a classificação.');

export function MetadataPanel({ native, busy, run, transactions, onSaved, onCreateRule, initialSelectedIds, valuesHidden = false }: Props) {
  const [items, setItems] = useState<ClassificationItem[]>([]);
  const [ready, setReady] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [selection, setSelection] = useState<Selection>({ categoryId: null, category: 'A classificar', tagIds: [] });
  const [visible, setVisible] = useState(40);
  const [localBusy, setLocalBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const mounted = useRef(false);
  const active = useRef(AppState.currentState === 'active');
  const disabled = busy || localBusy || !ready;
  const api = native as CaptureModule & MetadataApi;
  const available = transactions as Transaction[];
  const eligible = available.filter(tx => !tx.voided && (tx.kind === 'income' || tx.kind === 'expense' || tx.kind === 'card_purchase'));

  useEffect(() => {
    mounted.current = true;
    const load = async () => {
      const request = ++generation.current;
      try {
        const result = await native.classifications();
        if (!mounted.current || !active.current || generation.current !== request) return;
        if (result.status !== 'ok' || !result.items) { setError('Não foi possível carregar categorias e tags.'); return; }
        setItems(result.items); setReady(true); setError(null);
      } catch { if (mounted.current && active.current && generation.current === request) setError('Não foi possível carregar categorias e tags.'); }
    };
    void load();
    const listener = AppState.addEventListener('change', state => {
      generation.current += 1;
      active.current = state === 'active'; setItems([]); setReady(false); setSelectedIds([]); setSelection({ categoryId: null, category: 'A classificar', tagIds: [] }); setError(null); setLocalBusy(false);
      if (state === 'active') void load();
    });
    return () => { mounted.current = false; generation.current += 1; listener.remove(); };
  }, [native]);

  useEffect(() => {
    if (!initialSelectedIds?.length) return;
    const initial = initialSelectedIds.filter(id => eligible.some(tx => tx.id === id && !tx.voided));
    setSelectedIds(initial);
    const first = eligible.find(tx => tx.id === initial[0]);
    if (first) setSelection({ categoryId: first.categoryId, category: first.category, tagIds: [...first.tagIds] });
  }, [initialSelectedIds, transactions]);

  function toggleTransaction(id: string) {
    if (disabled) return;
    const next = selectedIds.includes(id) ? selectedIds.filter(value => value !== id) : [...selectedIds, id];
    setSelectedIds(next);
    const nextSingle = next.length === 1 ? eligible.find(tx => tx.id === next[0]) : undefined;
    if (nextSingle) setSelection({ categoryId: nextSingle.categoryId, category: nextSingle.category, tagIds: [...nextSingle.tagIds] });
    else if (!next.length) setSelection({ categoryId: null, category: 'A classificar', tagIds: [] });
    setError(null);
  }
  function toggleTag(id: string) {
    if (disabled) return;
    setSelection(current => {
      const tagIds = current.tagIds.includes(id) ? current.tagIds.filter(value => value !== id) : [...current.tagIds, id];
      if (tagIds.length > 20) { setError('Selecione até 20 tags.'); return current; }
      return { ...current, tagIds };
    });
  }
  function apply() {
    if (disabled || !selectedIds.length) return;
    const records = eligible.filter(tx => selectedIds.includes(tx.id));
    if (records.length !== selectedIds.length) { setSelectedIds(records.map(tx => tx.id)); setError('A lista mudou. Confira a seleção e tente novamente.'); return; }
    const common = records.length === 1 ? 'este movimento' : `${records.length} movimentos`;
    Alert.alert('Salvar classificação?', `Só a categoria e as tags de ${common} serão alteradas. Tipo, valor, data e origem ficam iguais.`, [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Salvar', onPress: () => {
        const request = ++generation.current;
        setLocalBusy(true); setError(null);
        void run(async () => {
          try {
            const body = records.map(tx => ({ transactionId: tx.id, scope: tx.scope ?? 'finance', ...selection }));
            const result = await api.reclassifyFinanceTransactions(JSON.stringify({ items: body, ruleId: null }));
            if (!mounted.current || !active.current || generation.current !== request) return;
            if (result.status !== 'ok') { setError(failure(result.code)); return; }
            setSelectedIds([]); onSaved();
          } catch { if (mounted.current && active.current && generation.current === request) setError('Não foi possível salvar a classificação.'); }
          finally { if (mounted.current && active.current && generation.current === request) setLocalBusy(false); }
        });
      } },
    ]);
  }

  const oneSelected = selectedIds.length === 1 ? eligible.find(tx => tx.id === selectedIds[0]) : undefined;
  return <Card>
    <Text style={styles.heading}>Reclassificar movimentos</Text>
    <Text style={styles.small}>Só a categoria e as tags mudam. O movimento continua o mesmo.</Text>
    <ErrorBox message={error} />
    {!ready && !error && <Text style={styles.small}>Carregando categorias e tags…</Text>}
    {eligible.length === 0 ? <Text style={styles.small}>Não há movimentos confirmados neste período.</Text> : <>
      {eligible.slice(0, visible).map(tx => {
        const selected = selectedIds.includes(tx.id);
        return <Pressable key={`${tx.scope ?? 'finance'}:${tx.id}`} accessibilityRole="checkbox" accessibilityState={{ checked: selected }} accessibilityLabel={`${tx.description}, ${tx.effectiveDate}, ${formatMoney(tx.amountCents, valuesHidden)}`} onPress={() => toggleTransaction(tx.id)} disabled={disabled} style={[styles.row, { minHeight: 56, paddingVertical: 7, borderBottomWidth: 1, borderColor: '#263240' }]}>
          <View style={{ width: 24, height: 24, borderWidth: 1, borderColor: selected ? '#FFB020' : '#A2AEBE', backgroundColor: selected ? '#FFB020' : 'transparent', alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: '#080B10', fontWeight: '700' }}>{selected ? '✓' : ''}</Text></View>
          <View style={{ flex: 1, gap: 3 }}>
            <Text numberOfLines={1} style={styles.body}>{tx.description}</Text>
            <Text style={styles.small}>{tx.effectiveDate} · {formatMoney(tx.amountCents, valuesHidden)} · {tx.kind === 'income' ? 'Receita' : tx.kind === 'expense' ? 'Despesa' : 'Compra no cartão'}</Text>
          </View>
        </Pressable>;
      })}
      {eligible.length > visible && <Action title="Mostrar mais movimentos" secondary disabled={disabled} onPress={() => setVisible(value => value + 40)} />}
      <Text style={styles.label}>Categoria principal</Text>
      <View style={styles.wrap}><Chip title="A classificar" active={selection.categoryId === null} onPress={() => !disabled && setSelection(value => ({ ...value, categoryId: null, category: 'A classificar' }))} />
        {items.filter(item => item.kind === 'category' && (!item.archived || item.id === selection.categoryId)).map(item => <Chip key={item.id} title={`${item.name}${item.archived ? ' · arquivada' : ''}`} active={selection.categoryId === item.id} onPress={() => { if (!disabled && (!item.archived || item.id === selection.categoryId)) setSelection(value => ({ ...value, categoryId: item.id, category: item.name })); }} />)}</View>
      <Text style={styles.label}>Tags opcionais</Text>
      <View style={styles.wrap}>{items.filter(item => item.kind === 'tag' && (!item.archived || selection.tagIds.includes(item.id))).map(item => <Chip key={item.id} title={`${item.name}${item.archived ? ' · arquivada' : ''}`} active={selection.tagIds.includes(item.id)} onPress={() => toggleTag(item.id)} />)}</View>
      <Action title={`Salvar para ${selectedIds.length || 'nenhum'} movimento${selectedIds.length === 1 ? '' : 's'}`} disabled={disabled || !selectedIds.length} onPress={apply} />
      {oneSelected && onCreateRule && <Action secondary title="Criar regra desta correção" disabled={disabled} onPress={() => onCreateRule({ description: oneSelected.description, ...selection })} />}
    </>}
  </Card>;
}

function formatMoney(cents: number, hidden = false) {
  if (hidden) return '••••';
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(cents / 100);
}
