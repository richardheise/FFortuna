import { Text } from '../ui/Typography';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Modal, Pressable,  View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { formatBRL } from '../core/money';
import { Action, Card, ErrorBox, ScreenScroll, colors, styles } from '../ui/common';
import { CandidatePanel } from './CandidatePanel';
import { ClassificationPanel } from './ClassificationPanel';
import { batchBlockReason, batchFields, hasPossibleBatchMatches } from './batch';
import type { CaptureModule, DiagnosticResult, DiagnosticSampleInfo, NotificationCandidate } from './native';

type Props = {
  valuesHidden?: boolean;
  native: CaptureModule;
  busy: boolean;
  run: (task: () => Promise<unknown>) => Promise<void>;
  epoch: number;
  onChanged: () => void;
  onBatchConfirmed?: (months: string[]) => void;
};
type InboxSample = DiagnosticSampleInfo & {
  description?: string;
  amountCents?: number | null;
  origin?: 'notification' | 'statement';
  mode?: string;
  effectiveDate?: string | null;
};

const PAGE_SIZE = 20;
type BatchKind = 'income' | 'expense' | null;
type BatchClassification = { categoryId: string | null; category: string; tagIds: string[] };

function todayIso() { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`; }

function displayDate(value: string): string | null {
  if (!value) return null;
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (dateOnly) return `${dateOnly[3]}/${dateOnly[2]}/${dateOnly[1]}`;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleString('pt-BR');
}

export function DailyInbox({ native, busy, run, epoch, onChanged, onBatchConfirmed, valuesHidden = false }: Props) {
  const [samples, setSamples] = useState<InboxSample[]>([]);
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedBatch, setSelectedBatch] = useState<string[]>([]);
  const [batchCandidates, setBatchCandidates] = useState<Record<string, NotificationCandidate>>({});
  const [blockedBatch, setBlockedBatch] = useState<Record<string, string>>({});
  const [checkingId, setCheckingId] = useState<string | null>(null);
  const [batchKind, setBatchKind] = useState<BatchKind>(null);
  const [batchMode, setBatchMode] = useState(false);
  const [classification, setClassification] = useState<BatchClassification>({ categoryId: null, category: 'A classificar', tagIds: [] });
  const [batchSaving, setBatchSaving] = useState(false);
  const [batchMessage, setBatchMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const batchGeneration = useRef(0);
  const mounted = useRef(false);
  const active = useRef(AppState.currentState !== 'background' && AppState.currentState !== 'inactive');
  const nativeRef = useRef(native);
  const runRef = useRef(run);
  const changedRef = useRef(onChanged);
  const batchConfirmedRef = useRef(onBatchConfirmed);
  const busyRef = useRef(busy);
  const lastEpoch = useRef(epoch);
  const needsRefresh = useRef(false);
  nativeRef.current = native;
  runRef.current = run;
  changedRef.current = onChanged;
  batchConfirmedRef.current = onBatchConfirmed;
  busyRef.current = busy;

  const refresh = useCallback(async () => {
    if (busyRef.current) { needsRefresh.current = true; return; }
    needsRefresh.current = false;
    const request = ++generation.current;
    setLoading(true);
    setError(null);
    try {
      const response: { value: DiagnosticResult | null } = { value: null };
      await runRef.current(async () => { response.value = await nativeRef.current.diagnosticSamples(); });
      if (!mounted.current || !active.current || generation.current !== request) return;
      const result = response.value;
      if (!result || result.status === 'error') {
        setSamples([]);
        setError('Não foi possível carregar as revisões.');
        return;
      }
      const next = ((result.samples ?? []) as InboxSample[]).filter(sample =>
        !sample.isSummary && !sample.transactionId && sample.candidateReviewStatus !== 'dismissed');
      setSamples(next);
      const ids = new Set(next.map(sample => sample.id));
      setSelectedBatch(current => current.filter(id => ids.has(id)));
      setBatchCandidates(current => Object.fromEntries(Object.entries(current).filter(([id]) => ids.has(id))));
      setBlockedBatch(current => Object.fromEntries(Object.entries(current).filter(([id]) => ids.has(id))));
      setVisibleCount(PAGE_SIZE);
    } catch (failure) {
      if (failure instanceof Error && failure.message === 'BUSY') { needsRefresh.current = true; return; }
      if (mounted.current && active.current && generation.current === request) {
        setSamples([]);
        setError('Não foi possível carregar as revisões.');
      }
    } finally {
      if (mounted.current && active.current && generation.current === request) setLoading(false);
    }
  }, []);

  const clearForBackground = useCallback(() => {
    generation.current += 1;
    batchGeneration.current += 1;
    if (!mounted.current) return;
    setSamples([]);
    setVisibleCount(PAGE_SIZE);
    setSelectedId(null);
    setSelectedBatch([]);
    setBatchCandidates({});
    setBlockedBatch({});
    setCheckingId(null);
    setBatchKind(null);
    setBatchMode(false);
    setClassification({ categoryId: null, category: 'A classificar', tagIds: [] });
    setBatchMessage(null);
    setBatchSaving(false);
    setError(null);
    setLoading(false);
  }, []);

  const closeReview = useCallback(() => {
    setSelectedId(null);
    changedRef.current();
  }, []);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    const subscription = AppState.addEventListener('change', state => {
      active.current = state === 'active';
      if (active.current) void refresh();
      else clearForBackground();
    });
    return () => {
      subscription.remove();
      mounted.current = false;
      generation.current += 1;
    };
  }, [clearForBackground, refresh]);

  useEffect(() => { if (!busy && needsRefresh.current && active.current) void refresh(); }, [busy, refresh]);
  useEffect(() => {
    if (lastEpoch.current !== epoch) {
      lastEpoch.current = epoch;
      if (active.current) void refresh();
    }
  }, [epoch, refresh]);

  const pendingCount = samples.length;
  const visible = samples.slice(0, visibleCount);

  async function toggleBatchSelection(sample: InboxSample) {
    if (busyRef.current || batchSaving || checkingId) return;
    setError(null);
    setBatchMessage(null);
    if (selectedBatch.includes(sample.id)) {
      setSelectedBatch(ids => ids.filter(id => id !== sample.id));
      return;
    }
    const cached = batchCandidates[sample.id];
    if (cached) {
      const reason = batchBlockReason(cached, todayIso());
      if (reason) { setSelectedId(sample.id); return; }
      setSelectedBatch(ids => [...ids, sample.id]);
      return;
    }
    setCheckingId(sample.id);
    const request = ++batchGeneration.current;
    try {
      const response: { value: DiagnosticResult | null } = { value: null };
      await runRef.current(async () => { response.value = await nativeRef.current.diagnosticCandidate(sample.id, false); });
      if (!mounted.current || !active.current || batchGeneration.current !== request) return;
      const result = response.value;
      const candidate = result?.candidate;
      if (!result || result.status === 'error' || !candidate) {
        setError('Não foi possível conferir esta revisão. Nenhuma seleção foi alterada.');
        return;
      }
      const reason = batchBlockReason(candidate, todayIso());
      if (reason) {
        setBatchCandidates(current => ({ ...current, [sample.id]: candidate }));
        setBlockedBatch(current => ({ ...current, [sample.id]: reason }));
        return;
      }
      setBatchCandidates(current => ({ ...current, [sample.id]: candidate }));
      setSelectedBatch(ids => [...ids, sample.id]);
      setBlockedBatch(current => { const next = { ...current }; delete next[sample.id]; return next; });
    } catch {
      if (mounted.current && active.current && batchGeneration.current === request) setError('Não foi possível conferir esta revisão. Nenhuma seleção foi alterada.');
    } finally {
      if (mounted.current && active.current && batchGeneration.current === request) setCheckingId(null);
    }
  }

  function confirmBatch() {
    if (!selectedBatch.length || !batchKind || busyRef.current || batchSaving) return;
    const request = ++batchGeneration.current;
    const ids = [...selectedBatch];
    const currentClassification = { ...classification, tagIds: [...classification.tagIds] };
    const items = ids.map(id => ({ id, kind: batchKind, ...currentClassification }));
    Alert.alert(
      'Confirmar movimentos selecionados?',
      `${ids.length} movimento(s) serão registrados como ${batchKind === 'income' ? 'receita' : 'despesa'} e entrarão nas análises. O sinal do arquivo não diz se é receita ou despesa. Transferências, faturas, investimentos, estornos e reembolsos exigem revisão individual.`,
      [
        { text: 'Voltar', style: 'cancel' },
        { text: 'Confirmar selecionados', onPress: () => {
          if (!mounted.current || !active.current || batchGeneration.current !== request || busyRef.current) return;
          setBatchSaving(true);
          setError(null);
          setBatchMessage(null);
          const operation = runRef.current(async () => {
            try {
              let payloadJson = JSON.stringify({ items });
              const result = await nativeRef.current.confirmObservedBatch(payloadJson);
              payloadJson = '';
              if (result.status === 'ok') changedRef.current();
              if (!mounted.current || !active.current || batchGeneration.current !== request) return;
              if (result.status !== 'ok') {
                setError('Não foi possível registrar o lote. Sua seleção foi preservada.');
                return;
              }
              setSelectedBatch([]);
              setBatchCandidates({});
              setBlockedBatch({});
              setBatchKind(null);
              setClassification({ categoryId: null, category: 'A classificar', tagIds: [] });
              setBatchMessage(`${result.registered ?? ids.length} movimento(s) registrado(s).`);
              setBatchMode(false);
              batchConfirmedRef.current?.(result.months ?? []);
            } catch {
              if (mounted.current && active.current && batchGeneration.current === request) setError('Não foi possível registrar o lote. Sua seleção foi preservada.');
            }
          });
          void operation.catch(() => {
            if (mounted.current && active.current && batchGeneration.current === request) setError('Não foi possível iniciar o lote. Sua seleção foi preservada.');
          }).finally(() => {
            if (mounted.current && active.current && batchGeneration.current === request) setBatchSaving(false);
          });
        } },
      ],
    );
  }

  return <>
    <Card style={{ gap: 8 }}>
      <View style={[styles.row, { justifyContent: 'space-between' }]}>
        <Text style={styles.heading}>A revisar ({pendingCount})</Text>
        <Pressable accessibilityRole="button" accessibilityLabel="Atualizar pendências" accessibilityState={{ disabled: busy || loading }} disabled={busy || loading} onPress={() => void refresh()} style={{ minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' }}>
          <Text style={{ color: colors.cyan, fontSize: 24 }}>↻</Text>
        </Pressable>
      </View>
      <ErrorBox message={error} />
      {loading && <Text style={styles.small}>Atualizando…</Text>}
      {!loading && pendingCount === 0 && !error && <Text style={styles.small}>Nenhuma revisão pendente.</Text>}
      {!!pendingCount && <View style={{ gap: 8, borderTopWidth: 1, borderColor: colors.line, paddingTop: 12 }}>
        <View style={[styles.row, { justifyContent: 'space-between' }]}>
          <Text style={styles.label}>Revisão em lote</Text>
          <Action secondary title={batchMode ? 'Cancelar seleção' : 'Selecionar'} disabled={busy || batchSaving} onPress={() => { setBatchMode(value => !value); setSelectedBatch([]); setBatchCandidates({}); setBlockedBatch({}); setBatchKind(null); setBatchMessage(null); }} />
        </View>
        {batchMode && <>
          <Text style={styles.small}>Selecione os movimentos. Todos serão registrados como o tipo escolhido; o sinal do extrato não define se é receita ou despesa.</Text>
          {selectedBatch.length > 0 && <>
            <Text style={styles.warning}>Transferências, faturas, investimentos, estornos e reembolsos exigem revisão individual.</Text>
            <View style={styles.wrap}>
              <Action title={batchKind === 'income' ? '✓ Receita' : 'Receita'} secondary disabled={busy || batchSaving} onPress={() => setBatchKind(batchKind === 'income' ? null : 'income')} />
              <Action title={batchKind === 'expense' ? '✓ Despesa' : 'Despesa'} secondary disabled={busy || batchSaving} onPress={() => setBatchKind(batchKind === 'expense' ? null : 'expense')} />
            </View>
            <ClassificationPanel native={native} busy={busy || batchSaving} run={run} selection={classification} onChange={setClassification} />
            <Text style={styles.small}>{selectedBatch.length} de {pendingCount} revisões selecionadas.</Text>
            <ErrorBox message={batchMessage} />
            <Action title={batchSaving ? 'Registrando…' : 'Confirmar selecionados'} disabled={busy || batchSaving || !batchKind} onPress={confirmBatch} />
          </>}
        </>}
        {!batchMode && <ErrorBox message={batchMessage} />}
      </View>}
      {visible.map(sample => {
        const candidate = batchCandidates[sample.id];
        const corrected = candidate ? batchFields(candidate) : null;
        const description = corrected?.description?.trim() || sample.description?.trim() || 'Sem descrição';
        const amountCents = corrected ? corrected.amountCents : sample.amountCents;
        const amount = typeof amountCents === 'number' && Number.isSafeInteger(amountCents)
          ? valuesHidden ? '••••' : formatBRL(amountCents)
          : null;
        const origin = sample.origin === 'statement' || (!sample.origin && sample.mode === 'statement') ? 'Extrato' : 'Notificação';
        const effectiveDate = corrected ? corrected.effectiveDate : sample.effectiveDate;
        const effective = effectiveDate ? displayDate(effectiveDate) : null;
        const captured = displayDate(sample.capturedAt);
        const dateLabel = effective ? effective : corrected ? 'Data do movimento não informada' : captured ? `Recebida ${captured}` : null;
        const selected = selectedBatch.includes(sample.id);
        const blocked = blockedBatch[sample.id];
        const possibleMatches = batchCandidates[sample.id]?.possibleMatches ?? [];
        return <View key={sample.id} style={{ minHeight: 64, justifyContent: 'center', gap: 4, borderTopWidth: 1, borderColor: colors.line, paddingVertical: 10 }}>
          <Pressable accessibilityRole="button" accessibilityLabel={`Revisar individualmente: ${description}${amount ? `, ${amount}` : ''}, ${origin}${dateLabel ? `, ${dateLabel}` : ''}`} disabled={busy || batchSaving} onPress={() => setSelectedId(sample.id)} style={{ minHeight: 44, justifyContent: 'center', gap: 4, opacity: busy ? 0.55 : 1 }}>
            <Text numberOfLines={2} style={styles.label}>{description}</Text>
            <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}>
              <Text style={styles.small}>{origin}{dateLabel ? ` · ${dateLabel}` : ''}</Text>
              {amount && <Text style={styles.label}>{amount}</Text>}
            </View>
          </Pressable>
          {batchMode && possibleMatches.length > 0 && <View style={{ gap: 4, paddingVertical: 6, paddingHorizontal: 10, backgroundColor: '#FFF4D6', borderRadius: 10 }}>
            <Text style={styles.warning}>Há um movimento parecido. Valor e dia iguais não provam duplicidade. Confira antes de incluí-lo; para vincular, abra a revisão individual.</Text>
            {possibleMatches.map(match => <Text key={match.id} style={styles.small}>{match.description || 'Movimento sem descrição'} · {valuesHidden ? '••••' : formatBRL(match.amountCents)} · {displayDate(match.effectiveDate) ?? 'Data não disponível'}</Text>)}
          </View>}
          {batchMode && <View style={styles.wrap}>
            <Action secondary title={checkingId === sample.id ? 'Conferindo…' : selected ? 'Remover do lote' : blocked ? 'Revisar individualmente' : 'Conferir para selecionar'} disabled={busy || batchSaving || (!!checkingId && checkingId !== sample.id)} onPress={() => void toggleBatchSelection(sample)} />
            {!!blocked && <Text style={styles.warning}>{blocked}</Text>}
          </View>}
        </View>;
      })}
      {pendingCount > visibleCount && <Action secondary title="Mostrar mais" onPress={() => setVisibleCount(count => count + PAGE_SIZE)} />}
    </Card>

    <Modal visible={!!selectedId} animationType="slide" onRequestClose={() => { if (!busyRef.current && !batchSaving) closeReview(); }}>
      <SafeAreaView style={styles.page}>
        <ScreenScroll>
          <View style={[styles.row, { justifyContent: 'space-between' }]}>
            <Text style={styles.title}>Revisar movimento</Text>
            <Action title="Voltar" secondary disabled={busy || batchSaving} onPress={closeReview} />
          </View>
          {selectedId && <CandidatePanel key={selectedId} native={native} busy={busy} run={run} sampleId={selectedId} onClose={closeReview} onConfirmedMonth={month => batchConfirmedRef.current?.([month])} />}
        </ScreenScroll>
      </SafeAreaView>
    </Modal>
  </>;
}
