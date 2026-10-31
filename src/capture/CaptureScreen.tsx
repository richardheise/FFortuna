import { Text } from '../ui/Typography';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Modal, Pressable, ScrollView,  View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Action, Card, Chip, ErrorBox, ScreenScroll, colors, formatMonth, styles } from '../ui/common';
import { captureModule, type DailyState, type StatementResult } from './native';
import { FinanceNavigation, TiqueMark, type MainTab } from './FinanceNavigation';
import { FinancePanel, type FinanceView } from './FinancePanel';
import { SourcesPanel } from './SourcesPanel';
import { DailyInbox } from './DailyInbox';
import { AboutPanel } from './AboutPanel';
import { CaptureTools } from './CaptureTools';

const errors: Record<string, string> = {
  ACCESS_REQUIRED: 'Autorize o acesso no Android e aguarde a conexão.',
  SOURCE_CHANGED: 'Confira se o Nubank está instalado e habilitado.',
  SAMPLE_LIMIT: 'A caixa de entrada atingiu sua capacidade. A captura foi pausada; seus registros foram preservados.',
  INVALID_STATEMENT: 'Este arquivo não pôde ser lido. Escolha um extrato CSV ou OFX da conta, sem editar o arquivo.',
  STATEMENT_TOO_LARGE: 'O extrato excede 2 MiB. Exporte um período menor.',
  STATEMENT_IO: 'Não foi possível abrir o arquivo. Baixe-o no aparelho e tente novamente.',
  NO_SHARED_FILE: 'Compartilhe o arquivo novamente ou use Importar extrato.',
  RECOVERY_BLOCKED: 'A recuperação local precisa de atenção nas configurações.',
  DIAGNOSTIC_STORAGE: 'Não foi possível abrir os dados protegidos. Eles foram preservados.',
};
const currentMonth = () => {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
};
export function CaptureScreen() {
  const [native] = useState(captureModule);
  const [tab, setTab] = useState<MainTab>('home');
  const [state, setState] = useState<DailyState | null>(null);
  const [overlay, setOverlay] = useState<FinanceView | 'tools' | 'about' | null>(null);
  const [movementFilter, setMovementFilter] = useState<'all' | 'pending' | 'uncategorized'>('all');
  const [selectedMonth, setSelectedMonth] = useState(currentMonth);
  const [availableMonths, setAvailableMonths] = useState<string[]>([]);
  const [entryKind, setEntryKind] = useState<'income' | 'expense'>('expense');
  const [entryRequest, setEntryRequest] = useState(0);
  const [epoch, setEpoch] = useState(0);
  const [inboxGeneration, setInboxGeneration] = useState(0);
  const [busy, setBusy] = useState(false);
  const [hidden, setHidden] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const busyRef = useRef(false);
  const tabRef = useRef<MainTab>('home');
  const scrollRef = useRef<ScrollView>(null);
  const scrollY = useRef(0);
  const scrollPositions = useRef<Record<MainTab, number>>({ transactions: 0, home: 0 });
  const hadShared = useRef(false);
  const active = useRef(AppState.currentState !== 'background');
  const alive = useRef(true);
  const generation = useRef(0);
  const changed = useCallback(() => setEpoch(value => value + 1), []);
  useEffect(() => { changed(); }, [state?.pendingCount, changed]);
  const refresh = useCallback(async () => {
    if (!native) return;
    const token = ++generation.current;
    const next = await native.dailyState();
    if (!alive.current || !active.current || token !== generation.current) return;
    if (next.status === 'error') throw new Error(next.code ?? 'STORAGE');
    setState(next);
  }, [native]);
  useEffect(() => {
    alive.current = true;
    void refresh().catch(() => setError('Não foi possível abrir os dados protegidos. Acesse as configurações para recuperação.'));
    const listener = AppState.addEventListener('change', value => {
      active.current = value === 'active';
      if (!active.current) { generation.current++; setHidden(true); setError(null); setMessage(null); }
      else { changed(); void refresh().catch(() => setError('Não foi possível atualizar o estado da captura.')); }
    });
    const timer = setInterval(() => { if (active.current && !busyRef.current) void refresh().catch(() => {}); }, 15_000);
    return () => { alive.current = false; generation.current++; listener.remove(); clearInterval(timer); };
  }, [changed, refresh]);
  useEffect(() => {
    if (!state?.incomingFile) { hadShared.current = false; return; }
    if (!hadShared.current && !overlay && !busy) { hadShared.current = true; setOverlay('more'); }
  }, [state?.incomingFile, overlay, busy]);
  const run = useCallback(async (task: () => Promise<unknown>) => {
    if (busyRef.current) throw new Error('BUSY');
    busyRef.current = true; setBusy(true);
    try { await task(); try { await refresh(); } catch { if (active.current) setError('Não foi possível atualizar a captura.'); } }
    finally { busyRef.current = false; if (alive.current) setBusy(false); }
  }, [refresh]);
  async function act(task: () => Promise<{ status?: string; code?: string } | void>, success?: string) {
    if (!native || busyRef.current) return;
    setError(null); setMessage(null);
    try {
      await run(async () => {
        const result = await task();
        if (result?.status === 'error') throw new Error(result.code ?? 'STORAGE');
      });
      changed(); if (active.current && success) setMessage(success);
    } catch (failure) {
      if (active.current) setError(errors[failure instanceof Error ? failure.message : ''] ?? 'Não foi possível concluir. Seus dados foram preservados.');
    }
  }
  async function manual(kind: 'income' | 'expense' = 'expense') {
    if (!native) return;
    if (!state?.configured) {
      let prepared = false;
      await act(async () => { const result = await native.startTracking(); prepared = result.status === 'ok'; return result; });
      if (!prepared) return;
    }
    setEntryKind(kind); setEntryRequest(value => value + 1); setOverlay('entry');
  }
  function switchTab(next: MainTab) {
    scrollPositions.current[tabRef.current] = scrollY.current;
    tabRef.current = next;
    setTab(next);
    scrollY.current = scrollPositions.current[next];
    requestAnimationFrame(() => scrollRef.current?.scrollTo({ y: scrollPositions.current[next], animated: false }));
  }
  function navigate(view: FinanceView) {
    if (view === 'home' || view === 'transactions') {
      setOverlay(null); switchTab(view); if (view === 'transactions') setMovementFilter('all'); changed();
    } else setOverlay(view);
  }
  function addMovement() {
    Alert.alert('Adicionar ao Tique', 'Escolha como começar.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Lançar manualmente', onPress: () => void manual() },
      { text: 'Importar extrato', onPress: () => void importFile(false) },
    ]);
  }
  function activate() {
    if (!native || !state?.source.signer) return;
    const signer = state.source.signer;
    Alert.alert('Receber notificações do Nubank?', 'O Tique guardará no aparelho as notificações recebidas a partir da ativação, mesmo com o app fechado. O original fica cifrado por até 30 dias; pendências e registros ficam até você removê-los. Você pode pausar em Fontes ou revogar o acesso no Android. Notificações podem faltar; nenhum lançamento será confirmado automaticamente.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Ativar', onPress: () => void act(() => native.startDailyCapture(signer), 'Captura ativada.') },
    ]);
  }
  async function importFile(shared: boolean) {
    let result: StatementResult | null = null;
    await act(async () => {
      result = await (shared ? native!.importSharedStatement() : native!.chooseStatement());
      return result;
    });
    const received = result as StatementResult | null;
    if (received?.status === 'ok') {
      setOverlay(null); switchTab('transactions'); setMovementFilter('pending');
      const months = [...new Set((received.months ?? []).filter(value => /^\d{4}-\d{2}$/.test(value)))];
      if (months.length) setAvailableMonths(current => [...new Set([...current, ...months])].sort());
      const total = received.rowCount ?? received.count ?? 0;
      const pending = received.pending ?? total;
      const confirmed = received.confirmed ?? 0;
      const existing = received.existing ?? 0;
      setMessage(`${total} movimentos recebidos · ${pending} a revisar · ${confirmed} já registrados${existing ? ` · ${existing} já recebidos antes` : ''}. Receber não registra pendências.`);
    }
  }
  const goReview = () => { setOverlay(null); switchTab('transactions'); setMovementFilter('pending'); changed(); };
  const batchConfirmed = useCallback((months: string[]) => {
    const valid = [...new Set(months.filter(value => /^\d{4}-\d{2}$/.test(value)))].sort();
    setAvailableMonths(current => [...new Set([...current, ...valid])].sort());
    setMovementFilter('all');
    changed();
    if (valid.length) setMessage('Movimentos confirmados. Veja o período importado em Análises.');
  }, [changed]);
  function viewImportedPeriod(month?: string) {
    if (month) setSelectedMonth(month);
    setOverlay(null);
    switchTab('home');
  }
  const bookChanged = useCallback(() => {
    setInboxGeneration(value => value + 1);
    setAvailableMonths([]);
    setMovementFilter('all');
    setSelectedMonth(currentMonth());
  }, []);
  const captureActive = !!state && state.active && state.mode === 'daily' && state.authorized && state.connected;
  const captureAttention = !!state && (state.status === 'blocked' || state.status === 'error' || !state.source.installed || !state.source.enabled || !state.source.signer || (state.mode === 'daily' && state.active && (!state.authorized || !state.connected)));
  const captureLabel = !state ? 'Captura · …' : captureAttention ? 'Captura · Atenção' : captureActive ? 'Captura · Ativa' : 'Captura · Pausada';
  if (!native) return <SafeAreaView style={styles.page}><Text style={styles.body}>Abra o APK Tique para usar seus registros. O Expo Go oferece somente a demonstração.</Text></SafeAreaView>;
  return <SafeAreaView style={styles.page}>
    <View style={[styles.row, { justifyContent: 'space-between', paddingHorizontal: 12, paddingVertical: 8 }]}>
      <View style={styles.row}><TiqueMark /><Text style={styles.title}>tique</Text></View>
      <View style={styles.row}>
        <Pressable accessibilityRole="button" accessibilityLabel={`${captureLabel}. Abrir dados e captura`} disabled={busy} onPress={() => setOverlay('more')} style={{ minHeight: 48, justifyContent: 'center', paddingHorizontal: 6 }}>
          <Text style={{ color: captureAttention ? colors.red : captureActive ? colors.cyan : colors.muted, fontSize: 11 }}>{captureLabel}</Text>
        </Pressable>
        <Pressable accessibilityRole="button" accessibilityLabel={hidden ? 'Mostrar valores' : 'Ocultar valores'} onPress={() => setHidden(value => !value)} style={{ minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: colors.cyan, fontSize: 20 }}>{hidden ? '◉' : '◎'}</Text></Pressable>
        <Pressable accessibilityRole="button" accessibilityLabel="Dados e captura" disabled={busy} onPress={() => setOverlay('more')} style={{ minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: colors.gold, fontSize: 26 }}>⚙</Text></Pressable>
      </View>
    </View>
    <ScrollView ref={scrollRef} keyboardShouldPersistTaps="handled" contentContainerStyle={styles.scroll} scrollEventThrottle={100} onScroll={event => {
      const offset = event.nativeEvent.contentOffset.y;
      scrollY.current = offset;
      scrollPositions.current[tabRef.current] = offset;
    }}>
      <ErrorBox message={error} />
      {message && <Text accessibilityRole="alert" style={styles.small}>{message}</Text>}
      {tab === 'transactions' && <>
        <View style={[styles.row, { justifyContent: 'space-between' }]}>
          <Text style={styles.heading}>Movimentos</Text>
          <Action title="+" accessibilityLabel="Adicionar movimento ou importar extrato" disabled={busy} onPress={addMovement} />
        </View>
        <View style={styles.wrap}>
          <Chip title="Todos" active={movementFilter === 'all'} onPress={() => setMovementFilter('all')} />
          <Chip title="A revisar" active={movementFilter === 'pending'} onPress={() => setMovementFilter('pending')} />
          <Chip title="A classificar" active={movementFilter === 'uncategorized'} onPress={() => setMovementFilter('uncategorized')} />
        </View>
        {availableMonths.length > 0 && <Action title="Ver período dos registros" secondary disabled={busy} onPress={() => viewImportedPeriod(availableMonths.length === 1 ? availableMonths[0] : undefined)} />}
      </>}
      <View style={tab === 'transactions' && movementFilter !== 'uncategorized' ? undefined : { display: 'none' }}>
        <Text style={styles.small}>Pendências · todos os períodos</Text>
        <DailyInbox key={inboxGeneration} native={native} busy={busy} run={run} epoch={epoch} onChanged={changed} onBatchConfirmed={batchConfirmed} valuesHidden={hidden} />
      </View>
      {tab === 'transactions' && movementFilter !== 'pending' && <Text style={styles.heading}>Registrados · {formatMonth(selectedMonth)}</Text>}
      {tab === 'home' && <Card>
        <Text style={styles.small}>{state?.pendingCount ?? 0} pendências fora dos totais.</Text>
        {!!state?.pendingCount && <Action title="Revisar pendências" secondary disabled={busy} onPress={goReview} />}
        {availableMonths.length > 0 && <View style={{ gap: 8 }}>
          <Text style={styles.label}>Períodos disponíveis</Text>
          {availableMonths.map(month => <Action key={month} title={`Ver ${formatMonth(month)}`} secondary disabled={busy} onPress={() => setSelectedMonth(month)} />)}
        </View>}
      </Card>}
      <View style={tab === 'home' || (tab === 'transactions' && movementFilter !== 'pending') ? undefined : { display: 'none' }}>
        <FinancePanel native={native} busy={busy} run={run} view={tab === 'home' ? 'home' : 'transactions'} onNavigate={navigate} onTools={() => setOverlay('tools')} valuesHidden={hidden} onToggleValues={() => setHidden(value => !value)} epoch={epoch} onChanged={changed} selectedMonth={selectedMonth} onSelectedMonthChange={setSelectedMonth} uncategorizedOnly={tab === 'transactions' && movementFilter === 'uncategorized'} onBookChanged={bookChanged} />
      </View>
    </ScrollView>
    <FinanceNavigation active={tab} disabled={busy} onTab={next => { switchTab(next); setMessage(null); setError(null); }} />
    <Modal visible={overlay !== null} animationType="slide" onRequestClose={() => { if (!busyRef.current) { setOverlay(null); changed(); } }}>
      {overlay === 'tools' ? <CaptureTools onClose={() => { setOverlay('more'); changed(); void refresh().catch(() => {}); }} /> : <SafeAreaView style={styles.page}>
        <View style={[styles.row, { justifyContent: 'space-between', padding: 16 }]}><Text style={styles.heading}>{overlay === 'entry' ? 'Novo movimento' : overlay === 'categories' ? 'Categorias e tags' : overlay === 'balance' ? 'Conferência de saldo' : overlay === 'resources' ? 'Caixinhas e cartões' : overlay === 'metadata' ? 'Classificar movimentos' : overlay === 'rules' ? 'Regras' : overlay === 'about' ? 'Sobre o Tique' : 'Dados e captura'}</Text><Action title="×" accessibilityLabel="Fechar painel" secondary disabled={busy} onPress={() => { setOverlay(null); changed(); }} /></View>
        <ScreenScroll>
          {overlay === 'more' && <Action title="Sobre o Tique" secondary onPress={() => setOverlay('about')} />}
          {overlay === 'about' && <AboutPanel />}
          {overlay === 'more' && <SourcesPanel state={state} busy={busy} onActivate={activate} onPause={() => void act(() => native.stopDiagnostic(), 'Captura pausada.')} onPermission={() => native.openSettings()} onReconnect={() => void act(() => native.reconnectCapture())} onImport={() => void importFile(false)} onBank={() => void act(() => native.openBankApp())} onReceive={() => void importFile(true)} onDismissShared={() => void act(() => native.dismissSharedStatement())} onManual={() => void manual()} onReview={goReview} />}
          {overlay !== 'about' && <FinancePanel native={native} busy={busy} run={run} view={overlay ?? 'more'} onNavigate={navigate} onTools={() => setOverlay('tools')} entryKind={entryKind} entryRequest={entryRequest} valuesHidden={hidden} onToggleValues={() => setHidden(value => !value)} onChanged={changed} selectedMonth={selectedMonth} onSelectedMonthChange={setSelectedMonth} onBookChanged={bookChanged} />}
        </ScreenScroll>
      </SafeAreaView>}
    </Modal>
  </SafeAreaView>;
}
