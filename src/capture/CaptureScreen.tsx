import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Modal, Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Action, Chip, ErrorBox, ScreenScroll, colors, styles } from '../ui/common';
import { captureModule, type DailyState, type StatementResult } from './native';
import { FinanceNavigation, TiqueMark, type MainTab } from './FinanceNavigation';
import { FinancePanel, type FinanceView } from './FinancePanel';
import { SourcesPanel } from './SourcesPanel';
import { DailyInbox } from './DailyInbox';
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
export function CaptureScreen() {
  const [native] = useState(captureModule);
  const [tab, setTab] = useState<MainTab>('sources');
  const [state, setState] = useState<DailyState | null>(null);
  const [overlay, setOverlay] = useState<FinanceView | 'tools' | null>(null);
  const [movementMode, setMovementMode] = useState<'pending' | 'confirmed'>('pending');
  const [entryKind, setEntryKind] = useState<'income' | 'expense'>('expense');
  const [entryRequest, setEntryRequest] = useState(0);
  const [epoch, setEpoch] = useState(0);
  const [busy, setBusy] = useState(false);
  const [hidden, setHidden] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const busyRef = useRef(false);
  const overlayRef = useRef(overlay);
  overlayRef.current = overlay;
  const initialized = useRef(false);
  const hadShared = useRef(false);
  const active = useRef(AppState.currentState !== 'background');
  const alive = useRef(true);
  const generation = useRef(0);
  const changed = useCallback(() => setEpoch(value => value + 1), []);
  const refresh = useCallback(async () => {
    if (!native) return;
    const token = ++generation.current;
    const next = await native.dailyState();
    if (!alive.current || !active.current || token !== generation.current) return;
    if (next.status === 'error') throw new Error(next.code ?? 'STORAGE');
    setState(next);
    if (!initialized.current) { initialized.current = true; setTab(next.configured ? 'transactions' : 'sources'); }
    if (next.incomingFile && !hadShared.current && !overlayRef.current && !busyRef.current) { setTab('sources'); hadShared.current = true; }
    if (!next.incomingFile) hadShared.current = false;
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
    if (state?.incomingFile && !hadShared.current && !overlay && !busy) { setTab('sources'); hadShared.current = true; }
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
  function navigate(view: FinanceView) {
    if (view === 'home' || view === 'transactions') {
      setOverlay(null); setTab(view); if (view === 'transactions') setMovementMode('confirmed'); changed();
    } else setOverlay(view);
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
      setTab('transactions'); setMovementMode('pending');
      setMessage(received.count ? `${received.count} movimentos para revisar.` : 'Este arquivo já foi recebido. Nenhum registro duplicado.');
    }
  }
  const goReview = () => { setTab('transactions'); setMovementMode('pending'); changed(); };
  if (!native) return <SafeAreaView style={styles.page}><Text style={styles.body}>Abra o APK Tique para usar seus registros. O Expo Go oferece somente a demonstração.</Text></SafeAreaView>;
  return <SafeAreaView style={styles.page}>
    <View style={[styles.row, { justifyContent: 'space-between', paddingHorizontal: 20, paddingVertical: 8 }]}>
      <View style={styles.row}><TiqueMark /><Text style={styles.title}>tique</Text></View>
      <Pressable accessibilityRole="button" accessibilityLabel="Configurações" disabled={busy} onPress={() => setOverlay('more')} style={{ minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: colors.gold, fontSize: 26 }}>⚙</Text></Pressable>
    </View>
    <ScreenScroll>
      <ErrorBox message={error} />
      {message && <Text accessibilityRole="alert" style={styles.small}>{message}</Text>}
      {tab === 'sources' && <SourcesPanel state={state} busy={busy} onActivate={activate} onPause={() => void act(() => native.stopDiagnostic(), 'Captura pausada.')} onPermission={() => native.openSettings()} onReconnect={() => void act(() => native.reconnectCapture())} onImport={() => void importFile(false)} onBank={() => void act(() => native.openBankApp())} onReceive={() => void importFile(true)} onDismissShared={() => void act(() => native.dismissSharedStatement())} onManual={() => void manual()} onReview={goReview} />}
      {tab === 'transactions' && <>
        <View style={[styles.row, { justifyContent: 'space-between' }]}>
          <Text style={styles.heading}>Movimentos</Text>
          <Action title="+" accessibilityLabel="Adicionar gasto ou ganho" disabled={busy} onPress={() => void manual()} />
        </View>
        <View style={styles.wrap}><Chip title="A revisar" active={movementMode === 'pending'} onPress={() => setMovementMode('pending')} /><Chip title="Registrados" active={movementMode === 'confirmed'} onPress={() => setMovementMode('confirmed')} /></View>
      </>}
      <View style={tab === 'transactions' && movementMode === 'pending' ? undefined : { display: 'none' }}>
        <DailyInbox native={native} busy={busy} run={run} epoch={epoch} onChanged={changed} />
      </View>
      {tab === 'home' && !!state?.pendingCount && <Action title={`A revisar (${state.pendingCount})`} secondary disabled={busy} onPress={goReview} />}
      <View style={tab === 'home' || (tab === 'transactions' && movementMode === 'confirmed') ? undefined : { display: 'none' }}>
        <FinancePanel native={native} busy={busy} run={run} view={tab === 'home' ? 'home' : 'transactions'} onNavigate={navigate} onTools={() => setOverlay('tools')} valuesHidden={hidden} onToggleValues={() => setHidden(value => !value)} epoch={epoch} onChanged={changed} />
      </View>
    </ScreenScroll>
    <FinanceNavigation active={tab} disabled={busy} onTab={next => { setTab(next); setMessage(null); setError(null); }} />
    <Modal visible={overlay !== null} animationType="slide" onRequestClose={() => { if (!busyRef.current) { setOverlay(null); changed(); } }}>
      {overlay === 'tools' ? <CaptureTools onClose={() => { setOverlay('more'); changed(); void refresh().catch(() => {}); }} /> : <SafeAreaView style={styles.page}>
        <View style={[styles.row, { justifyContent: 'space-between', padding: 16 }]}><Text style={styles.heading}>{overlay === 'entry' ? 'Novo movimento' : overlay === 'categories' ? 'Categorias e tags' : overlay === 'balance' ? 'Conferência avançada' : 'Configurações'}</Text><Action title="×" accessibilityLabel="Fechar painel" secondary disabled={busy} onPress={() => { setOverlay(null); changed(); }} /></View>
        <ScreenScroll><FinancePanel native={native} busy={busy} run={run} view={overlay ?? 'more'} onNavigate={navigate} onTools={() => setOverlay('tools')} entryKind={entryKind} entryRequest={entryRequest} valuesHidden={hidden} onToggleValues={() => setHidden(value => !value)} onChanged={changed} /></ScreenScroll>
      </SafeAreaView>}
    </Modal>
  </SafeAreaView>;
}
