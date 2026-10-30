import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AppState, Text, View } from 'react-native';
import { Action, Card, Chip, ErrorBox, Field, styles } from '../ui/common';
import { formatBRL, moneyInput, parseBRL } from '../core/money';
import type {
  CaptureModule, DiagnosticResult, NotificationCandidate, NotificationCandidateFields,
} from './native';

type Props = {
  native: CaptureModule;
  busy: boolean;
  run: (task: () => Promise<unknown>) => Promise<void>;
  sampleId: string;
  onClose: () => void;
};
type Editor = { amount: string; kind: NotificationCandidateFields['kind']; effectiveDate: string; description: string; category: string };

const kinds: Array<{ value: NonNullable<Editor['kind']>; label: string }> = [
  { value: 'income', label: 'Receita' }, { value: 'expense', label: 'Despesa' },
  { value: 'transfer', label: 'Transferência' }, { value: 'other', label: 'Outra (fatura, estorno, aporte…)' },
];
const codeMessages: Record<string, string> = {
  SAMPLE_MISSING: 'A observação expirou ou não está disponível.',
  CANDIDATE_MISSING: 'A candidatura não está disponível.',
  INVALID_CANDIDATE_REVIEW: 'Os campos da revisão não foram aceitos.',
  GROUP_SUMMARY: 'Resumo de grupo não gera candidatura.',
  INVALID_REVIEW: 'Os campos da revisão não foram aceitos.',
  TEXT_LIMIT: 'Um dos campos excede o limite permitido.',
  STORAGE: 'Não foi possível acessar o diagnóstico local.',
  DIAGNOSTIC_STORAGE: 'Não foi possível acessar o diagnóstico local.',
  INVALID_AMOUNT: 'Informe um valor em reais válido ou deixe o campo vazio.',
  INVALID_DATE: 'Informe uma data válida no formato AAAA-MM-DD ou deixe vazio.',
};
const confidenceLabel = (value: string) => value === 'certain' ? 'extração consistente' : value === 'missing' ? 'ausente' : 'incerta';
const safeError = (code?: string) => code ? `${codeMessages[code] ?? 'Não foi possível concluir a operação.'} (${code})` : 'Não foi possível concluir a operação.';

function toEditor(fields: NotificationCandidateFields): Editor {
  let amount = '';
  if (fields.amountCents !== null && Number.isSafeInteger(fields.amountCents) && fields.amountCents >= 0) {
    try { amount = moneyInput(fields.amountCents); } catch { amount = ''; }
  }
  return { amount, kind: fields.kind, effectiveDate: fields.effectiveDate ?? '', description: fields.description ?? '', category: fields.category ?? '' };
}

function sameFields(left: NotificationCandidateFields, right: NotificationCandidateFields) {
  return left.amountCents === right.amountCents && left.kind === right.kind && left.effectiveDate === right.effectiveDate &&
    left.description === right.description && left.category === right.category;
}

function validDate(value: string): boolean {
  if (!value) return true;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function CandidatePanel({ native, busy, run, sampleId, onClose }: Props) {
  const [candidate, setCandidate] = useState<NotificationCandidate | null>(null);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [baseline, setBaseline] = useState<NotificationCandidateFields | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const generation = useRef(0);
  const mounted = useRef(false);
  const active = useRef(AppState.currentState !== 'background' && AppState.currentState !== 'inactive');
  const nativeRef = useRef(native);
  const runRef = useRef(run);
  nativeRef.current = native;
  runRef.current = run;

  const clearSensitive = useCallback((rerender = true) => {
    generation.current += 1;
    if (rerender && mounted.current) {
      setCandidate(null);
      setEditor(null);
      setBaseline(null);
      setError(null);
      setMessage(null);
      setLoading(false);setSaving(false);
    }
  }, []);

  const applyCandidate = useCallback((value: NotificationCandidate) => {
    const fields = value.review ?? value.parsed.draft;
    const next = toEditor(fields);
    setCandidate(value);
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
      if (mounted.current && active.current && generation.current === request) setError('Não foi possível carregar a candidatura.');
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
    return { amountCents, kind: editor.kind, effectiveDate: editor.effectiveDate || null, description: editor.description, category: editor.category };
  }, [editor]);
  const dirty = !!(currentFields && baseline && !sameFields(currentFields, baseline)) || (!!editor && !currentFields);

  function update<K extends keyof Editor>(key: K, value: Editor[K]) {
    setEditor(current => current ? { ...current, [key]: value } : current);
    setError(null);
    setMessage(null);
  }

  function save(status: 'reviewed' | 'dismissed', preserveEdits: boolean) {
    if (!candidate || !editor) return;
    const fields = preserveEdits ? currentFields : (candidate.review ?? candidate.parsed.draft);
    if (!fields) { setError(safeError('INVALID_AMOUNT')); return; }
    if (fields.description.length > 500 || fields.category.length > 80) { setError(safeError('TEXT_LIMIT')); return; }
    if (!validDate(fields.effectiveDate ?? '')) { setError(safeError('INVALID_DATE')); return; }
    const payload = {
      amountCents: fields.amountCents,
      kind: fields.kind,
      effectiveDate: fields.effectiveDate,
      description: fields.description,
      category: fields.category,
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
          setMessage(status === 'dismissed' ? 'Candidatura descartada; a observação original foi mantida.' : 'Revisão salva localmente.');
          if(result.candidate) applyCandidate(result.candidate);
          else setError('Resposta incompleta; reabra a prévia para conferir a revisão.');
        }
      } catch {
        if (mounted.current && active.current && generation.current === expectedGeneration) setError('Não foi possível salvar a revisão.');
      } finally {
        if (mounted.current && active.current && generation.current === expectedGeneration) setSaving(false);
      }
    }).finally(() => { payloadJson = ''; });
  }

  const formatAmount = (value: number | null) => value === null || !Number.isSafeInteger(value) ? 'Não definido' : formatBRL(value);
  const reason = candidate?.parsed.reasonCode ?? '';

  return <Card>
    <Text style={styles.heading}>Candidatura temporária para revisão</Text>
    <Text style={styles.warning}>Prévia temporária do diagnóstico, retida por até 7 dias. Não é uma transação, não altera saldo e não entra no backup sintético. O parser BRL é genérico: não usa templates Nubank nem infere automaticamente movimentação.</Text>
    <ErrorBox message={error} />
    {message && <Text style={styles.small}>{message}</Text>}
    {loading && <Text style={styles.small}>Carregando candidatura…</Text>}
    {candidate && <>
      <Text style={styles.small}>Observação {candidate.revision} · analisada {new Date(candidate.parsedAt).toLocaleString()} · parser {candidate.parsed.parserVersion}</Text>
      {candidate.previousSampleId && <Text style={styles.warning}>A identidade Android foi reutilizada. Pode ser uma atualização ou outra movimentação; confira cada observação separadamente.</Text>}
      {candidate.relatedReviewExists && <Text style={styles.warning}>Já existe uma revisão relacionada; ela não foi alterada por esta análise.</Text>}
      {candidate.hasLaterRevision && <Text style={styles.warning}>Existe uma observação posterior com a mesma identidade Android. Isso não prova que sejam a mesma movimentação.</Text>}

      {candidate.review && <Text style={styles.small}>Revisão local: {candidate.review.status === 'dismissed' ? 'marcada para descartar' : 'salva'}{candidate.reviewedAt ? ` · ${new Date(candidate.reviewedAt).toLocaleString()}` : ''}. Nenhuma transação confirmada.</Text>}
      <Text style={styles.small}>Publicada: {new Date(candidate.publishedAt).toLocaleString()} · capturada: {new Date(candidate.capturedAt).toLocaleString()}</Text>
      <Text style={styles.heading}>Original da notificação · valores reais</Text>
      <Text style={styles.label}>Título</Text><Text selectable={false} style={styles.body}>{candidate.original.title || '—'}</Text>
      <Text style={styles.label}>Texto</Text><Text selectable={false} style={styles.body}>{candidate.original.text || '—'}</Text>
      <Text style={styles.label}>Texto expandido</Text><Text selectable={false} style={styles.body}>{candidate.original.bigText || '—'}</Text>
      <Text style={styles.label}>Subtexto</Text><Text selectable={false} style={styles.body}>{candidate.original.subText || '—'}</Text>
      <Text style={styles.label}>Linhas</Text><Text selectable={false} style={styles.body}>{candidate.original.textLines.join('\n') || '—'}</Text>

      <Text style={styles.heading}>Prévia do parser</Text>
      <Text style={styles.body}>Valor candidato: {formatAmount(candidate.parsed.draft.amountCents)}</Text>
      <Text style={styles.small}>Motivo: {candidate.parsed.reason} · confiança da extração: valor {confidenceLabel(candidate.parsed.confidence.amount)}, natureza {confidenceLabel(candidate.parsed.confidence.nature)}, movimentação {confidenceLabel(candidate.parsed.confidence.movement)}, conta {confidenceLabel(candidate.parsed.confidence.account)}, data {confidenceLabel(candidate.parsed.confidence.date)}, categoria {confidenceLabel(candidate.parsed.confidence.category)}</Text>
      {candidate.parsed.amountEvidence.map((item, index) => <View key={`${item.sourceField}-${item.start}-${index}`} style={{ gap: 3 }}>
        <Text style={styles.label}>Evidência {index + 1} · {item.sourceField} · {item.start}–{item.end} · {formatAmount(item.amountCents)}</Text>
        <Text selectable={false} style={styles.body}>{item.observedText}</Text>
      </View>)}
      {candidate.parsed.amountEvidence.length === 0 && <Text style={styles.small}>Nenhum valor com evidência reconhecida.</Text>}

      <Text style={styles.heading}>Revisar campos</Text>
      <Field label="Valor (BRL; vazio = desconhecido)" value={editor?.amount ?? ''} onChangeText={value => update('amount', value)} editable={!busy && !saving} keyboardType="decimal-pad" autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={32} placeholder="12,34" />
      <Text style={styles.small}>Valor sugerido pelo parser: {formatAmount(candidate.parsed.draft.amountCents)} · motivo: {reason}</Text>
      <Text style={styles.label}>Natureza (não inferida automaticamente)</Text>
      <View style={styles.wrap}>
        <Chip title="Não definida" active={editor?.kind === null} onPress={() => { if(!busy && !saving) update('kind', null); }} />
        {kinds.map(item => <Chip key={item.value} title={item.label} active={editor?.kind === item.value} onPress={() => { if(!busy && !saving) update('kind', item.value); }} />)}
      </View>
      <Field label="Data efetiva (opcional, AAAA-MM-DD)" value={editor?.effectiveDate ?? ''} onChangeText={value => update('effectiveDate', value)} editable={!busy && !saving} autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={10} placeholder="AAAA-MM-DD" />
      <Text style={styles.small}>A data de publicação não é preenchida automaticamente. Transferência, pagamento de fatura, estorno e aporte não devem ser classificados como receita ou despesa comum.</Text>
      <Field label="Descrição" value={editor?.description ?? ''} onChangeText={value => update('description', value)} editable={!busy && !saving} autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={500} />
      <Field label="Categoria" value={editor?.category ?? ''} onChangeText={value => update('category', value)} editable={!busy && !saving} autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={80} />
      <View style={styles.wrap}>
        <Action title="Salvar revisão" disabled={busy || saving || loading || !editor || !currentFields || !validDate(editor?.effectiveDate ?? '')} onPress={() => save('reviewed', true)} />
        <Action title="Descartar candidatura" secondary disabled={busy || saving || loading || !candidate} onPress={() => save('dismissed', false)} />
        <Action title="Reprocessar" secondary disabled={busy || saving || loading || dirty || !candidate} onPress={() => void load(true)} />
        <Action title="Fechar candidatura" secondary disabled={busy || saving} onPress={() => { clearSensitive(); onClose(); }} />
      </View>
    </>}
    {!candidate && !loading && <Text style={styles.small}>A candidatura não está aberta. Atualize a observação ou feche este painel.</Text>}
    {!candidate && <View style={styles.wrap}><Action title="Tentar novamente" secondary disabled={busy || loading} onPress={() => void load(false)} /><Action title="Fechar" secondary disabled={busy || loading} onPress={onClose} /></View>}
  </Card>;
}
