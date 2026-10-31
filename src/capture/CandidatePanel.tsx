import { Text } from '../ui/Typography';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, AppState,  View } from 'react-native';
import { Action, Card, Chip, ErrorBox, Field, colors, styles } from '../ui/common';
import { formatBRL, moneyInput, parseBRL } from '../core/money';
import { ClassificationPanel } from './ClassificationPanel';
import type {
  CaptureModule, DiagnosticFields, DiagnosticResult, FinanceResult, NotificationCandidate, NotificationCandidateFields,
} from './native';

type Props = {
  native: CaptureModule;
  busy: boolean;
  run: (task: () => Promise<unknown>) => Promise<void>;
  sampleId: string;
  onClose: () => void;
  onConfirmedMonth?: (month: string) => void;
};
type Editor = { amount: string; kind: NotificationCandidateFields['kind']; effectiveDate: string; description: string; category: string; categoryId: string | null; tagIds: string[] };
type ReviewCandidate = Omit<NotificationCandidate, 'temporary' | 'original'> & {
  temporary: boolean;
  original?: DiagnosticFields;
  originalAvailable?: boolean;
  origin?: 'notification' | 'statement';
  possibleMatches?: Array<{ id: string; description: string; amountCents: number; effectiveDate: string; kind: string; scope?: 'finance' | 'extended' }>;
};

const kinds: Array<{ value: NonNullable<Editor['kind']>; label: string }> = [
  { value: 'income', label: 'Receita' }, { value: 'expense', label: 'Despesa' },
  { value: 'transfer', label: 'Transferência' }, { value: 'other', label: 'Outra (fatura, estorno, aporte…)' },
];
const codeMessages: Record<string, string> = {
  SAMPLE_MISSING: 'Esta revisão expirou ou não está disponível.',
  CANDIDATE_MISSING: 'A revisão não está disponível.',
  INVALID_CANDIDATE_REVIEW: 'Não foi possível salvar a revisão.',
  GROUP_SUMMARY: 'Este resumo não é um movimento.',
  INVALID_REVIEW: 'Os campos da revisão não foram aceitos.',
  TEXT_LIMIT: 'Um dos campos excede o limite permitido.',
  STORAGE: 'Não foi possível acessar os dados locais.',
  DIAGNOSTIC_STORAGE: 'Não foi possível acessar os dados locais.',
  INVALID_AMOUNT: 'Informe um valor em reais válido ou deixe o campo vazio.',
  INVALID_DATE: 'Informe uma data válida no formato AAAA-MM-DD ou deixe vazio.',
  INVALID_CLASSIFICATION: 'A categoria ou as tags não foram aceitas.',
  CLASSIFICATION_MISSING: 'Uma opção não está disponível. Reabra a revisão.',
  CLASSIFICATION_ARCHIVED: 'Uma opção foi arquivada. Escolha uma opção ativa.',
  ACCOUNT_REQUIRED: 'Configure primeiro o saldo de abertura no livro financeiro.',
  DATE_BEFORE_OPENING: 'A data efetiva é anterior à abertura do livro. Confira o período e não duplique movimentos incluídos no saldo inicial.',
  FUTURE_DATE: 'Esta entrega registra movimentos realizados, com data até hoje.',
  UNSUPPORTED_MOVEMENT: 'Este tipo de movimentação ainda não pode ser confirmado no livro.',
  CONFIRMATION_CONFLICT: 'Já existe uma confirmação diferente. O movimento original foi preservado.',
  AMOUNT_LIMIT: 'A operação ultrapassaria um limite monetário seguro.',
  TRANSACTION_LIMIT: 'O livro atingiu o limite desta entrega. Exporte um backup e mantenha os dados.',
  INVALID_SOURCE: 'A origem deste movimento não pôde ser conferida.',
};
const safeError = (code?: string) => codeMessages[code ?? ''] ?? 'Não foi possível concluir a operação.';

function todayValue() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

function displayDate(value: string) {
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  return dateOnly ? `${dateOnly[3]}/${dateOnly[2]}/${dateOnly[1]}` : value;
}

function matchKindLabel(kind: string) {
  if (kind === 'income') return 'Receita';
  if (kind === 'expense') return 'Despesa';
  if (kind === 'card_purchase') return 'Compra no cartão';
  if (kind === 'card_payment') return 'Pagamento de fatura';
  if (kind === 'reserve_move') return 'Movimento da caixinha';
  if (kind === 'transfer') return 'Transferência';
  return 'Outro movimento';
}

function toEditor(fields: NotificationCandidateFields): Editor {
  let amount = '';
  if (fields.amountCents !== null && Number.isSafeInteger(fields.amountCents) && fields.amountCents >= 0) {
    try { amount = moneyInput(fields.amountCents); } catch { amount = ''; }
  }
  return { amount, kind: fields.kind, effectiveDate: fields.effectiveDate ?? '', description: fields.description ?? '', category: fields.category ?? '', categoryId: fields.categoryId ?? null, tagIds: fields.tagIds ?? [] };
}

function sameFields(left: NotificationCandidateFields, right: NotificationCandidateFields) {
  return left.amountCents === right.amountCents && left.kind === right.kind && left.effectiveDate === right.effectiveDate &&
    left.description === right.description && left.category === right.category && (left.categoryId ?? null) === (right.categoryId ?? null) &&
    [...(left.tagIds ?? [])].sort().join(',') === [...(right.tagIds ?? [])].sort().join(',');
}

function validDate(value: string): boolean {
  if (!value) return true;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function CandidatePanel({ native, busy, run, sampleId, onClose, onConfirmedMonth }: Props) {
  const [candidate, setCandidate] = useState<ReviewCandidate | null>(null);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [baseline, setBaseline] = useState<NotificationCandidateFields | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [affectsAccount, setAffectsAccount] = useState(false);
  const [originOpen, setOriginOpen] = useState(false);
  const generation = useRef(0);
  const mounted = useRef(false);
  const active = useRef(AppState.currentState !== 'background' && AppState.currentState !== 'inactive');
  const nativeRef = useRef(native);
  const runRef = useRef(run);
  const confirmedMonthRef = useRef(onConfirmedMonth);
  const busyRef = useRef(busy);
  nativeRef.current = native;
  runRef.current = run;
  confirmedMonthRef.current = onConfirmedMonth;
  busyRef.current = busy;

  const clearSensitive = useCallback((rerender = true) => {
    generation.current += 1;
    if (rerender && mounted.current) {
      setCandidate(null);
      setEditor(null);
      setBaseline(null);
      setError(null);
      setMessage(null);
      setAffectsAccount(false);
      setOriginOpen(false);
      setLoading(false);setSaving(false);
    }
  }, []);

  const applyCandidate = useCallback((value: NotificationCandidate) => {
    const fields = value.financialReview ?? value.review ?? value.parsed.draft;
    const next = toEditor(fields);
    setCandidate(value as ReviewCandidate);
    setEditor(next);
    setBaseline(fields);
    setError(null);
  }, []);

  const load = useCallback(async (force: boolean) => {
    const request = ++generation.current;
    setLoading(true);
    setError(null);
    try {
      await runRef.current(async () => {
        const result: DiagnosticResult = await nativeRef.current.diagnosticCandidate(sampleId, force);
        if (!mounted.current || !active.current || generation.current !== request) return;
        if (result.status === 'error' || !result.candidate) {
          setError(safeError(result.code));
          return;
        }
        applyCandidate(result.candidate);
      });
    } catch {
      if (mounted.current && active.current && generation.current === request) setError('Não foi possível abrir a revisão.');
    } finally {
      if (mounted.current && active.current && generation.current === request) setLoading(false);
    }
  }, [applyCandidate, sampleId]);

  useEffect(() => {
    mounted.current = true;
    void load(false);
    const subscription = AppState.addEventListener('change', state => {
      active.current = state === 'active';
      if (!active.current) clearSensitive();
      else if (mounted.current) void load(false);
    });
    return () => {
      subscription.remove();
      mounted.current = false;
      clearSensitive(false);
    };
  }, [clearSensitive, load]);

  const currentFields = useMemo<NotificationCandidateFields | null>(() => {
    if (!editor) return null;
    let amountCents: number | null = null;
    if (editor.amount.trim()) {
      try { amountCents = parseBRL(editor.amount); } catch { return null; }
    }
    return { amountCents, kind: editor.kind, effectiveDate: editor.effectiveDate || null, description: editor.description, category: editor.category, categoryId: editor.categoryId, tagIds: editor.tagIds };
  }, [editor]);
  const dirty = !!(currentFields && baseline && !sameFields(currentFields, baseline)) || (!!editor && !currentFields);

  function update<K extends keyof Editor>(key: K, value: Editor[K]) {
    setEditor(current => current ? { ...current, [key]: value } : current);
    setAffectsAccount(false);
    setError(null);
    setMessage(null);
  }

  function save(status: 'reviewed' | 'dismissed', preserveEdits: boolean) {
    if (!candidate || (status === 'reviewed' && !editor)) return;
    const fields = status === 'dismissed' ? (candidate.review ?? candidate.parsed.draft) : preserveEdits ? currentFields : (candidate.review ?? candidate.parsed.draft);
    if (!fields) { setError(safeError('INVALID_AMOUNT')); return; }
    if (status === 'reviewed' && (fields.description.length > 500 || fields.category.length > 80)) { setError(safeError('TEXT_LIMIT')); return; }
    if (status === 'reviewed' && !validDate(fields.effectiveDate ?? '')) { setError(safeError('INVALID_DATE')); return; }
    const payload = {
      amountCents: fields.amountCents,
      kind: fields.kind,
      effectiveDate: fields.effectiveDate,
      description: fields.description,
      category: fields.category,
      categoryId: fields.categoryId ?? null,
      tagIds: fields.tagIds ?? [],
      status,
    };
    let payloadJson = JSON.stringify(payload);
    const candidateId = candidate.id;
    const expectedGeneration = generation.current;
    setSaving(true);
    setError(null);
    void runRef.current(async () => {
      try {
        const result: DiagnosticResult = await nativeRef.current.saveDiagnosticCandidate(candidateId, payloadJson);
        if (!mounted.current || !active.current || generation.current !== expectedGeneration) return;
        if (result.status === 'error') {
          setError(safeError(result.code));
        } else {
          setMessage(status === 'dismissed' ? 'Pendência descartada. A origem foi mantida.' : 'Revisão salva.');
          if(result.candidate) applyCandidate(result.candidate);
          else setError('Não foi possível atualizar a revisão. Abra-a novamente.');
        }
      } catch {
        if (mounted.current && active.current && generation.current === expectedGeneration) setError('Não foi possível salvar a revisão.');
      } finally {
        if (mounted.current && active.current && generation.current === expectedGeneration) setSaving(false);
      }
    }).finally(() => { payloadJson = ''; });
  }

  function confirmFinancial() {
    if(!candidate || !currentFields || candidate.transactionId) return;
    if(!currentFields.effectiveDate || !validDate(currentFields.effectiveDate) || currentFields.amountCents === null || currentFields.amountCents <= 0 || !['income','expense'].includes(currentFields.kind ?? '')) {
      setError('Informe um valor positivo, a data do movimento e Receita ou Despesa.');return;
    }
    const payload = JSON.stringify({ amountCents: currentFields.amountCents, kind: currentFields.kind,
      effectiveDate: currentFields.effectiveDate, description: currentFields.description, category: currentFields.category,
      categoryId: currentFields.categoryId ?? null, tagIds: currentFields.tagIds ?? [], affectsAccount: true });
    const id = candidate.id;const request = generation.current;const confirmedMonth = currentFields.effectiveDate.slice(0, 7);
    Alert.alert('Confirmar receita ou despesa?', 'Este movimento entrará nas análises. Compras no cartão, pagamento de fatura, transferências, aportes e estornos precisam de tratamento próprio; mantenha esses casos em revisão.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Confirmo receita/despesa', onPress: () => {
        if(!mounted.current || !active.current || generation.current !== request || busyRef.current) return;
        setSaving(true);setError(null);
        let confirmed = false;
        void runRef.current(async () => {
          try {
            const result: FinanceResult = await nativeRef.current.confirmDiagnosticCandidate(id, payload);
            if(!mounted.current || !active.current || generation.current !== request) return;
            if(result.status === 'error') { setError(safeError(result.code));return; }
            confirmed = true;
          } catch { if(mounted.current && active.current && generation.current === request) setError('Não foi possível concluir. Reabra a prévia para conferir antes de repetir.'); }
          finally { if(mounted.current && active.current && generation.current === request) setSaving(false); }
        }).then(() => { if (confirmed && mounted.current && active.current && generation.current === request) { confirmedMonthRef.current?.(confirmedMonth); setTimeout(() => { if (mounted.current && active.current && generation.current === request) onClose(); }, 0); } });
      } },
    ]);
  }

  function linkObserved(matchId: string) {
    if (!candidate || !mounted.current || !active.current || busyRef.current || saving) return;
    const id = candidate.id;
    const request = generation.current;
    Alert.alert('Vincular movimento existente?', 'Valor igual não prova duplicidade. Isso só relaciona a notificação ao movimento escolhido; os dados financeiros não mudam.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Vincular', onPress: () => {
        if (!mounted.current || !active.current || generation.current !== request || busyRef.current) return;
        setSaving(true);setError(null);
        let linked = false;
        void runRef.current(async () => {
          try {
            const module = nativeRef.current as CaptureModule & { linkObserved: (sampleId: string, transactionId: string) => Promise<FinanceResult> };
            const result = await module.linkObserved(id, matchId);
            if (!mounted.current || !active.current || generation.current !== request) return;
            if (result.status === 'error') { setError(safeError(result.code));return; }
            linked = true;
          } catch {
            if (mounted.current && active.current && generation.current === request) setError('Não foi possível vincular. Confira o histórico antes de tentar novamente.');
          } finally {
            if (mounted.current && active.current && generation.current === request) setSaving(false);
          }
        }).then(() => { if (linked && mounted.current && active.current && generation.current === request) setTimeout(() => { if (mounted.current && active.current && generation.current === request) onClose(); }, 0); });
      } },
    ]);
  }

  const formatAmount = (value: number | null) => value === null || !Number.isSafeInteger(value) ? 'Não definido' : formatBRL(value);
  const originLabel = candidate?.origin === 'statement' ? 'Extrato' : 'Notificação';
  const originalAvailable = candidate?.originalAvailable !== false;
  const matches = candidate?.possibleMatches ?? [];

  return <Card>
    <Text style={styles.heading}>Revisar movimento</Text>
    <ErrorBox message={error} />
    {message && <Text style={styles.small}>{message}</Text>}
    {loading && <Text style={styles.small}>Carregando revisão…</Text>}
    {candidate && <>
      <Text style={styles.small}>{originLabel}</Text>
      {candidate.temporary && <Text style={styles.warning}>Esta prévia vence em 7 dias.</Text>}
      {candidate.previousSampleId && <Text style={styles.warning}>{candidate.origin === 'statement' ? 'Este item aparece em outra versão do extrato. Confira antes de continuar.' : 'Pode ser uma atualização ou outro movimento. Confira separadamente.'}</Text>}
      {candidate.relatedReviewExists && <Text style={styles.small}>Há outra revisão relacionada; ela não foi alterada.</Text>}
      {candidate.hasLaterRevision && <Text style={styles.small}>Há outra notificação parecida. Isso não confirma que seja o mesmo movimento.</Text>}
      {candidate.review && <Text style={styles.small}>{candidate.review.status === 'dismissed' ? 'Marcada para descartar.' : 'Revisão salva.'}</Text>}
      {candidate.transactionId && <Text style={styles.warning}>Este movimento já foi registrado. A revisão não altera o movimento existente.</Text>}

      <Field label="Valor" value={editor?.amount ?? ''} onChangeText={value => update('amount', value)} editable={!busy && !saving && !candidate.transactionId} keyboardType="decimal-pad" autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={32} placeholder="0,00" />

      <Text style={styles.label}>Tipo do movimento</Text>
      <View style={styles.wrap}>
        <Chip title="Não definida" active={editor?.kind === null} onPress={() => { if(!busy && !saving && !candidate.transactionId) update('kind', null); }} />
        {kinds.map(item => <Chip key={item.value} title={item.label} active={editor?.kind === item.value} onPress={() => { if(!busy && !saving && !candidate.transactionId) update('kind', item.value); }} />)}
      </View>
      <Field label="Descrição" value={editor?.description ?? ''} onChangeText={value => update('description', value)} editable={!busy && !saving && !candidate.transactionId} autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={500} />
      <View style={styles.wrap}>
        <View style={{ flexGrow: 1, flexBasis: 190 }}><Field label="Data do movimento" value={editor?.effectiveDate ?? ''} onChangeText={value => update('effectiveDate', value)} editable={!busy && !saving && !candidate.transactionId} autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={10} placeholder="AAAA-MM-DD" /></View>
        {!editor?.effectiveDate && <Action title="Usar hoje" secondary disabled={busy || saving || !!candidate.transactionId} onPress={() => update('effectiveDate', todayValue())} />}
      </View>

      {editor && <ClassificationPanel native={native} busy={busy || saving || !!candidate.transactionId} run={run} selection={{ categoryId: editor.categoryId, category: editor.category, tagIds: editor.tagIds }} onChange={selection => {
        setEditor(current => current ? { ...current, ...selection } : current);setAffectsAccount(false);setError(null);setMessage(null);
      }} />}
      {!candidate.transactionId && <>
        {!!currentFields?.kind && !['income','expense'].includes(currentFields.kind) && <Text style={styles.small}>Este tipo precisa de tratamento próprio. Mantenha a pendência salva para revisar depois.</Text>}
        <Action title="Confirmar" disabled={busy || saving || loading || !currentFields || !currentFields.effectiveDate || !['income','expense'].includes(currentFields.kind ?? '')} onPress={confirmFinancial} />
      </>}
      {matches.length > 0 && <View style={{ gap: 8 }}>
        <Text style={styles.heading}>Movimentos parecidos</Text>
        <Text style={styles.small}>Valor igual não prova duplicidade.</Text>
        {matches.map(match => <View key={match.id} style={{ borderTopWidth: 1, borderColor: colors.line, paddingTop: 10, gap: 4 }}>
          <Text numberOfLines={2} style={styles.label}>{match.description || 'Sem descrição'}</Text>
          <Text style={styles.small}>{displayDate(match.effectiveDate)} · {formatAmount(match.amountCents)} · {matchKindLabel(match.kind)}</Text>
          <Action title="Vincular" secondary disabled={busy || saving || loading || !!candidate.transactionId} onPress={() => linkObserved(match.id)} />
        </View>)}
      </View>}
      <View style={{ gap: 6 }}>
        <Action title={originOpen ? 'Ocultar origem' : 'Ver origem'} secondary onPress={() => setOriginOpen(value => !value)} />
        {originOpen && (originalAvailable && candidate.original ? <View style={{ gap: 5 }}>
          <Text style={styles.label}>{originLabel} original</Text>
          <Text style={styles.body}>{candidate.original.title || '—'}</Text>
          <Text style={styles.body}>{candidate.original.text || '—'}</Text>
          {!!candidate.original.bigText && candidate.original.bigText !== candidate.original.text && <Text style={styles.body}>{candidate.original.bigText}</Text>}
          {candidate.original.textLines.filter(line => line !== candidate.original?.text && line !== candidate.original?.bigText).map((line, index) => <Text key={index} style={styles.body}>{line}</Text>)}
          <Text style={styles.small}>{candidate.origin === 'statement' ? 'Dados do extrato. ' : 'A data da notificação pode ser diferente da data do movimento. '}Origem disponível por até {candidate.temporary ? '7' : '30'} dias.</Text>
          {candidate.original.subText ? <Text style={styles.small}>{candidate.original.subText}</Text> : null}
        </View> : <Text style={styles.small}>A origem não está mais disponível.</Text>)}
      </View>
      <View style={styles.wrap}>
        <Action title="Salvar revisão" disabled={busy || saving || loading || !!candidate.transactionId || !editor || !currentFields || !validDate(editor?.effectiveDate ?? '')} onPress={() => save('reviewed', true)} />
        <Action title="Descartar revisão" secondary disabled={busy || saving || loading || !!candidate.transactionId || !candidate} onPress={() => save('dismissed', false)} />
        <Action title="Refazer prévia" secondary disabled={busy || saving || loading || dirty || !candidate || !originalAvailable} onPress={() => void load(true)} />
        <Action title="Fechar" secondary disabled={busy || saving} onPress={() => { clearSensitive(); onClose(); }} />
      </View>
    </>}
    {!candidate && !loading && <Text style={styles.small}>Não foi possível abrir esta revisão.</Text>}
    {!candidate && <View style={styles.wrap}><Action title="Tentar novamente" secondary disabled={busy || loading} onPress={() => void load(false)} /><Action title="Fechar" secondary disabled={busy || loading} onPress={onClose} /></View>}
  </Card>;
}
