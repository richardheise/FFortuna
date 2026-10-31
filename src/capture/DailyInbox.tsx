import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, Modal, Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { formatBRL } from '../core/money';
import { Action, Card, ErrorBox, ScreenScroll, colors, styles } from '../ui/common';
import { CandidatePanel } from './CandidatePanel';
import type { CaptureModule, DiagnosticResult, DiagnosticSampleInfo } from './native';

type Props = {
  native: CaptureModule;
  busy: boolean;
  run: (task: () => Promise<unknown>) => Promise<void>;
  epoch: number;
  onChanged: () => void;
};
type InboxSample = DiagnosticSampleInfo & {
  description?: string;
  amountCents?: number | null;
  origin?: 'notification' | 'statement';
  mode?: string;
  effectiveDate?: string | null;
};

const PAGE_SIZE = 20;

function displayDate(value: string): string | null {
  if (!value) return null;
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (dateOnly) return `${dateOnly[3]}/${dateOnly[2]}/${dateOnly[1]}`;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleString('pt-BR');
}

export function DailyInbox({ native, busy, run, epoch, onChanged }: Props) {
  const [samples, setSamples] = useState<InboxSample[]>([]);
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const mounted = useRef(false);
  const active = useRef(AppState.currentState !== 'background' && AppState.currentState !== 'inactive');
  const nativeRef = useRef(native);
  const runRef = useRef(run);
  const changedRef = useRef(onChanged);
  const busyRef = useRef(busy);
  const lastEpoch = useRef(epoch);
  const needsRefresh = useRef(false);
  nativeRef.current = native;
  runRef.current = run;
  changedRef.current = onChanged;
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
        setError(result?.code ? `Não foi possível carregar as pendências (${result.code}).` : 'Não foi possível carregar as pendências.');
        return;
      }
      const next = ((result.samples ?? []) as InboxSample[]).filter(sample =>
        !sample.isSummary && !sample.transactionId && sample.candidateReviewStatus !== 'dismissed');
      setSamples(next);
      setVisibleCount(PAGE_SIZE);
    } catch (failure) {
      if (failure instanceof Error && failure.message === 'BUSY') { needsRefresh.current = true; return; }
      if (mounted.current && active.current && generation.current === request) {
        setSamples([]);
        setError('Não foi possível carregar as pendências.');
      }
    } finally {
      if (mounted.current && active.current && generation.current === request) setLoading(false);
    }
  }, []);

  const clearForBackground = useCallback(() => {
    generation.current += 1;
    if (!mounted.current) return;
    setSamples([]);
    setVisibleCount(PAGE_SIZE);
    setSelectedId(null);
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
      void refresh();
    }
  }, [epoch, refresh]);

  const pendingCount = samples.length;
  const visible = samples.slice(0, visibleCount);
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
      {!loading && pendingCount === 0 && !error && <Text style={styles.small}>Nenhuma pendência.</Text>}
      {visible.map(sample => {
        const description = sample.description?.trim() || 'Sem descrição';
        const amount = typeof sample.amountCents === 'number' && Number.isSafeInteger(sample.amountCents)
          ? formatBRL(sample.amountCents)
          : null;
        const origin = sample.origin === 'statement' || (!sample.origin && sample.mode === 'statement') ? 'Extrato' : 'Notificação';
        const effective = sample.effectiveDate ? displayDate(sample.effectiveDate) : null;
        const captured = displayDate(sample.capturedAt);
        const dateLabel = effective ? effective : captured ? `Recebida ${captured}` : null;
        return <Pressable key={sample.id} accessibilityRole="button" accessibilityLabel={`Revisar: ${description}${amount ? `, ${amount}` : ''}, ${origin}${dateLabel ? `, ${dateLabel}` : ''}`} disabled={busy} onPress={() => setSelectedId(sample.id)} style={{ minHeight: 64, justifyContent: 'center', gap: 4, borderTopWidth: 1, borderColor: colors.line, paddingVertical: 10, opacity: busy ? 0.55 : 1 }}>
          <Text numberOfLines={2} style={styles.label}>{description}</Text>
          <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}>
            <Text style={styles.small}>{origin}{dateLabel ? ` · ${dateLabel}` : ''}</Text>
            {amount && <Text style={styles.label}>{amount}</Text>}
          </View>
        </Pressable>;
      })}
      {pendingCount > visibleCount && <Action secondary title="Mostrar mais" onPress={() => setVisibleCount(count => count + PAGE_SIZE)} />}
    </Card>

    <Modal visible={!!selectedId} animationType="slide" onRequestClose={() => { if (!busyRef.current) closeReview(); }}>
      <SafeAreaView style={styles.page}>
        <ScreenScroll>
          <View style={[styles.row, { justifyContent: 'space-between' }]}>
            <Text style={styles.title}>Revisar pendência</Text>
            <Action title="Voltar" secondary disabled={busy} onPress={closeReview} />
          </View>
          {selectedId && <CandidatePanel key={selectedId} native={native} busy={busy} run={run} sampleId={selectedId} onClose={closeReview} />}
        </ScreenScroll>
      </SafeAreaView>
    </Modal>
  </>;
}
