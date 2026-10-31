import { Text } from '../ui/Typography';
import { useEffect, useRef, useState } from 'react';
import { Alert, AppState, Pressable,  View } from 'react-native';
import { Action, Card, Chip, ErrorBox, Field, styles } from '../ui/common';
import type { CaptureModule, ClassificationItem, FinanceTransaction } from './native';

type Scope = 'finance' | 'extended';
type Transaction = FinanceTransaction & { scope?: Scope };
type Rule = { id: string; description: string; categoryId: string | null; category: string; tagIds: string[]; enabled: boolean; createdAt: string; updatedAt: string };
type RuleInput = { description: string; categoryId: string | null; category: string; tagIds: string[] };
type Result<T> = { status: 'ok' | 'error'; code?: string } & T;
type MetadataApi = {
  listFinanceRules(): Promise<Result<{ rules?: Rule[] }>>;
  saveFinanceRule(fieldsJson: string): Promise<Result<{ rule?: Rule }>>;
  setFinanceRuleEnabled(id: string, enabled: boolean): Promise<Result<{ rule?: Rule }>>;
  previewFinanceRule(id: string): Promise<Result<{ matches?: Transaction[] }>>;
  reclassifyFinanceTransactions(fieldsJson: string): Promise<Result<{ updated?: number }>>;
};
type Props = {
  native: CaptureModule; busy: boolean; run: (task: () => Promise<unknown>) => Promise<void>;
  onChanged?: (() => void) | undefined;
  initialRule?: RuleInput | undefined;
  valuesHidden?: boolean;
};

const errors: Record<string, string> = {
  RULE_DUPLICATE: 'Já existe uma regra para esta descrição.',
  RULE_MISSING: 'A regra não está mais disponível. Atualize a lista.',
  RULE_DISABLED: 'Ative a regra antes de aplicar.',
  RULE_MISMATCH: 'Os registros mudaram. Faça uma nova prévia.',
  INVALID_CLASSIFICATION: 'Escolha categorias e tags disponíveis.',
  INVALID_RULE_DESCRIPTION: 'Informe uma descrição válida.',
};

export function RulesPanel({ native, busy, run, onChanged, initialRule, valuesHidden = false }: Props) {
  const [rules, setRules] = useState<Rule[]>([]);
  const [items, setItems] = useState<ClassificationItem[]>([]);
  const [description, setDescription] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [category, setCategory] = useState('A classificar');
  const [tagIds, setTagIds] = useState<string[]>([]);
  const [previewRuleId, setPreviewRuleId] = useState<string | null>(null);
  const [matches, setMatches] = useState<Transaction[]>([]);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [visibleMatches, setVisibleMatches] = useState(20);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [localBusy, setLocalBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const generation = useRef(0);
  const mounted = useRef(false);
  const active = useRef(AppState.currentState === 'active');
  const api = native as CaptureModule & MetadataApi;
  const disabled = busy || localBusy || !ready;

  useEffect(() => {
    if (!initialRule) return;
    setEditingId(null); setDescription(initialRule.description); setCategoryId(initialRule.categoryId);
    setCategory(initialRule.category); setTagIds([...initialRule.tagIds]); setError(null);
  }, [initialRule]);

  useEffect(() => {
    mounted.current = true;
    async function load() {
      const request = ++generation.current;
      try {
        const [catalog, saved] = await Promise.all([native.classifications(), api.listFinanceRules()]);
        if (!mounted.current || !active.current || generation.current !== request) return;
        if (catalog.status !== 'ok' || !catalog.items || saved.status !== 'ok' || !saved.rules) {
          setError('Não foi possível carregar regras e categorias.'); return;
        }
        setItems(catalog.items); setRules(saved.rules); setReady(true); setError(null);
      } catch { if (mounted.current && active.current && generation.current === request) setError('Não foi possível carregar regras e categorias.'); }
    }
    void load();
    const listener = AppState.addEventListener('change', state => {
      generation.current += 1; active.current = state === 'active';
      setRules([]); setItems([]); setMatches([]); setSelectedIds([]); setVisibleMatches(20); setPreviewRuleId(null); setReady(false); setError(null); setMessage(null); setLocalBusy(false);
      if (state === 'active') void load();
      else { setDescription(''); setCategoryId(null); setCategory('A classificar'); setTagIds([]); setEditingId(null); }
    });
    return () => { mounted.current = false; generation.current += 1; listener.remove(); };
  }, [native]);

  function resetForm() {
    setEditingId(null); setDescription(''); setCategoryId(null); setCategory('A classificar'); setTagIds([]); setError(null);
  }
  function saveRule() {
    if (disabled) return;
    const request = ++generation.current;
    setLocalBusy(true); setError(null); setMessage(null); setMatches([]); setSelectedIds([]); setPreviewRuleId(null);
    void run(async () => {
      try {
        const result = await api.saveFinanceRule(JSON.stringify({ id: editingId, description, categoryId, category, tagIds }));
        if (!mounted.current || !active.current || generation.current !== request) return;
        if (result.status !== 'ok' || !result.rule) { setError(errors[result.code ?? ''] ?? 'Não foi possível salvar a regra.'); return; }
        setRules(current => [result.rule!, ...current.filter(item => item.id !== result.rule!.id)]);
        resetForm(); setMessage('Regra salva. Ela só será aplicada após prévia e confirmação.'); onChanged?.();
      } catch { if (mounted.current && active.current && generation.current === request) setError('Não foi possível salvar a regra.'); }
      finally { if (mounted.current && active.current && generation.current === request) setLocalBusy(false); }
    });
  }
  function editRule(rule: Rule) {
    if (disabled) return;
    setMatches([]); setSelectedIds([]); setPreviewRuleId(null); setMessage(null);
    setEditingId(rule.id); setDescription(rule.description); setCategoryId(rule.categoryId); setCategory(rule.category); setTagIds([...rule.tagIds]); setError(null);
  }
  function toggleEnabled(rule: Rule) {
    if (disabled) return;
    const request = ++generation.current;
    setLocalBusy(true); setError(null); setMessage(null);
    void run(async () => {
      try {
        const result = await api.setFinanceRuleEnabled(rule.id, !rule.enabled);
        if (!mounted.current || !active.current || generation.current !== request) return;
        if (result.status !== 'ok' || !result.rule) { setError(errors[result.code ?? ''] ?? 'Não foi possível atualizar a regra.'); return; }
        setRules(current => current.map(item => item.id === rule.id ? result.rule! : item));
        if (previewRuleId === rule.id) { setPreviewRuleId(null); setMatches([]); setSelectedIds([]); }
        onChanged?.();
      } catch { if (mounted.current && active.current && generation.current === request) setError('Não foi possível atualizar a regra.'); }
      finally { if (mounted.current && active.current && generation.current === request) setLocalBusy(false); }
    });
  }
  function preview(rule: Rule) {
    if (disabled || !rule.enabled) return;
    const request = ++generation.current;
    setLocalBusy(true); setError(null); setMessage(null); setMatches([]); setSelectedIds([]); setVisibleMatches(20); setPreviewRuleId(null);
    void run(async () => {
      try {
        const result = await api.previewFinanceRule(rule.id);
        if (!mounted.current || !active.current || generation.current !== request) return;
        if (result.status !== 'ok' || !result.matches) { setError(errors[result.code ?? ''] ?? 'Não foi possível preparar a prévia.'); return; }
        const validMatches = result.matches.filter(match => !match.voided && (match.kind === 'income' || match.kind === 'expense' || match.kind === 'card_purchase'));
        setMatches(validMatches); setSelectedIds(validMatches.map(match => match.id)); setPreviewRuleId(rule.id);
        if (validMatches.length === 0) setMessage('Não há movimentos com essa descrição.');
      } catch { if (mounted.current && active.current && generation.current === request) setError('Não foi possível preparar a prévia.'); }
      finally { if (mounted.current && active.current && generation.current === request) setLocalBusy(false); }
    });
  }
  function toggleMatch(id: string) {
    if (disabled) return;
    setSelectedIds(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id]);
  }
  function apply(rule: Rule) {
    if (disabled || previewRuleId !== rule.id || !selectedIds.length) return;
    const selected = matches.filter(item => selectedIds.includes(item.id));
    Alert.alert('Aplicar regra?', `Categoria e tags serão atualizadas em ${selected.length} movimentos. Tipo, valor, data e origem ficam iguais. Valor igual não prova duplicidade.`, [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Aplicar aos selecionados', onPress: () => {
        const request = ++generation.current;
        setLocalBusy(true); setError(null); setMessage(null);
        void run(async () => {
          try {
            const result = await api.reclassifyFinanceTransactions(JSON.stringify({
              ruleId: rule.id,
              items: selected.map(item => ({ transactionId: item.id, scope: item.scope ?? (item.kind === 'card_purchase' ? 'extended' : 'finance'), categoryId: rule.categoryId, category: rule.category, tagIds: rule.tagIds })),
            }));
            if (!mounted.current || !active.current || generation.current !== request) return;
            if (result.status !== 'ok') { setError(errors[result.code ?? ''] ?? 'Não foi possível aplicar a regra. Faça uma nova prévia.'); return; }
            setMatches([]); setSelectedIds([]); setPreviewRuleId(null); setMessage(`${result.updated ?? selected.length} movimentos atualizados.`); onChanged?.();
          } catch { if (mounted.current && active.current && generation.current === request) setError('Não foi possível aplicar a regra. Faça uma nova prévia.'); }
          finally { if (mounted.current && active.current && generation.current === request) setLocalBusy(false); }
        });
      } },
    ]);
  }
  function toggleTag(id: string) {
    if (disabled) return;
    setTagIds(current => current.includes(id) ? current.filter(value => value !== id) : current.length < 20 ? [...current, id] : current);
    if (tagIds.length >= 20 && !tagIds.includes(id)) setError('Selecione até 20 tags.');
  }

  return <Card>
    <Text style={styles.heading}>Regras de classificação</Text>
    <Text style={styles.small}>Use a descrição do movimento. A regra só sugere categoria e tags; você confere e escolhe antes de aplicar.</Text>
    <ErrorBox message={error} />
    {message && <Text style={styles.small} accessibilityRole="text">{message}</Text>}
    {!ready && !error && <Text style={styles.small}>Carregando…</Text>}
    <Field label="Descrição exata" value={description} onChangeText={setDescription} editable={!disabled} maxLength={500} autoCorrect={false} autoComplete="off" importantForAutofill="no" />
    <Text style={styles.label}>Categoria principal</Text>
    <View style={styles.wrap}><Chip title="A classificar" active={categoryId === null} onPress={() => { if (!disabled) { setCategoryId(null); setCategory('A classificar'); } }} />
      {items.filter(item => item.kind === 'category' && !item.archived).map(item => <Chip key={item.id} title={item.name} active={categoryId === item.id} onPress={() => { if (!disabled) { setCategoryId(item.id); setCategory(item.name); } }} />)}</View>
    <Text style={styles.label}>Tags opcionais</Text>
    <View style={styles.wrap}>{items.filter(item => item.kind === 'tag' && !item.archived).map(item => <Chip key={item.id} title={item.name} active={tagIds.includes(item.id)} onPress={() => toggleTag(item.id)} />)}</View>
    <View style={styles.wrap}>
      <Action title={editingId ? 'Salvar alteração' : 'Salvar regra'} disabled={disabled || !description.trim()} onPress={saveRule} />
      {editingId && <Action title="Cancelar edição" secondary disabled={busy || localBusy} onPress={resetForm} />}
    </View>
    {rules.map(rule => <View key={rule.id} style={{ gap: 8, paddingTop: 10, borderTopWidth: 1, borderColor: '#263240' }}>
      <Text style={styles.body}>{rule.description}</Text>
      <Text style={styles.small}>{rule.category} · {rule.tagIds.length ? `${rule.tagIds.length} tag${rule.tagIds.length === 1 ? '' : 's'}` : 'sem tags'} · {rule.enabled ? 'Ativa' : 'Pausada'}</Text>
      <View style={styles.wrap}>
        <Action title="Editar" secondary disabled={disabled} onPress={() => editRule(rule)} />
        <Action title={rule.enabled ? 'Pausar' : 'Ativar'} secondary disabled={disabled} onPress={() => toggleEnabled(rule)} />
        <Action title="Prévia de movimentos" secondary disabled={disabled || !rule.enabled} onPress={() => preview(rule)} />
      </View>
      {previewRuleId === rule.id && <View style={{ gap: 8 }}>
        <Text style={styles.warning}>Confira cada movimento. Valor igual não prova duplicidade. Só categoria e tags serão alteradas.</Text>
        {matches.slice(0, visibleMatches).map(tx => {
          const checked = selectedIds.includes(tx.id);
          return <Pressable key={`${tx.scope ?? 'finance'}:${tx.id}`} accessibilityRole="checkbox" accessibilityState={{ checked, disabled: tx.voided }} accessibilityLabel={`${tx.description}, ${tx.effectiveDate}, ${money(tx.amountCents, valuesHidden)}`} onPress={() => !tx.voided && toggleMatch(tx.id)} disabled={disabled || tx.voided} style={[styles.row, { minHeight: 56, paddingVertical: 7 }]}>
            <View style={{ width: 24, height: 24, borderWidth: 1, borderColor: checked ? '#FFB020' : '#A2AEBE', backgroundColor: checked ? '#FFB020' : 'transparent', alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: '#080B10' }}>{checked ? '✓' : ''}</Text></View>
            <View style={{ flex: 1, gap: 3 }}><Text numberOfLines={1} style={styles.body}>{tx.description}</Text><Text style={styles.small}>{tx.effectiveDate} · {money(tx.amountCents, valuesHidden)} · {tx.kind === 'card_purchase' ? 'Compra no cartão' : tx.kind === 'income' ? 'Receita' : 'Despesa'}</Text></View>
          </Pressable>;
        })}
        {matches.length > visibleMatches && <Action title="Mostrar mais movimentos" secondary disabled={disabled} onPress={() => setVisibleMatches(value => value + 20)} />}
        <Action title={`Aplicar aos ${selectedIds.length} selecionados`} disabled={disabled || !selectedIds.length || !matches.length} onPress={() => apply(rule)} />
      </View>}
    </View>)}
    {ready && rules.length === 0 && <Text style={styles.small}>Nenhuma regra salva.</Text>}
  </Card>;
}

function money(cents: number, hidden = false) {
  if (hidden) return '••••';
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(cents / 100);
}
