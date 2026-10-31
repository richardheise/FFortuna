import { Text } from '../ui/Typography';
import { useCallback, useEffect, useRef, useState } from 'react';
import * as Crypto from 'expo-crypto';
import { Alert, AppState, Pressable,  View } from 'react-native';
import { formatBRL, parseBRL } from '../core/money';
import { Action, Card, Chip, ErrorBox, Field, formatMonth, colors, styles } from '../ui/common';
import { ClassificationPanel } from './ClassificationPanel';
import { FinanceDashboard } from './FinanceDashboard';
import { MetadataPanel } from './MetadataPanel';
import { ResourcePanel } from './ResourcePanel';
import { RulesPanel } from './RulesPanel';
import { categoryKey, filterTransactions } from './dashboard';
import type { BackupResult, CaptureModule, FinanceResult, FinanceSnapshot, FinanceTransaction, LocalRecoveryEntry } from './native';

export type FinanceView = 'home' | 'transactions' | 'entry' | 'more' | 'balance' | 'categories' | 'resources' | 'metadata' | 'rules';
type RulePrefill = { description: string; categoryId: string | null; category: string; tagIds: string[] };
type Props = { native: CaptureModule; busy: boolean; run: (task: () => Promise<unknown>) => Promise<void>; view: FinanceView; onNavigate: (view: FinanceView) => void; onTools: () => void; entryKind?: 'income' | 'expense'; entryRequest?: number; valuesHidden: boolean; onToggleValues: () => void; epoch?: number; onChanged?: () => void; selectedMonth?: string; onSelectedMonthChange?: (month: string) => void; uncategorizedOnly?: boolean; onBookChanged?: () => void; initialRule?: RulePrefill; onCreateRule?: (input: RulePrefill) => void };
type ClassificationSelection = { categoryId: string | null; category: string; tagIds: string[] };
type ExtendedFinanceTransaction = Omit<FinanceTransaction, 'kind'> & {
  kind: string;
  scope?: 'finance' | 'extended';
  direction?: string | null;
  resourceName?: string | null;
  targetResourceName?: string | null;
  commitmentId?: string | null;
  invoiceMonth?: string | null;
};
function transactionKindLabel(item: ExtendedFinanceTransaction) {
  switch (item.kind) {
    case 'income': return 'Receita';
    case 'expense': return 'Despesa';
    case 'card_purchase': return 'Compra no cartão';
    case 'card_payment': return 'Pagamento de fatura';
    case 'commitment_opening': return 'Dívida já existente';
    case 'reserve_opening': return 'Posição inicial da caixinha';
    case 'reserve_move': return item.direction === 'withdraw' ? 'Resgate da caixinha' : 'Aporte na caixinha';
    case 'reserve_adjustment': return item.direction === 'decrease' ? 'Redução informada da posição' : 'Aumento informado da posição';
    case 'transfer': return item.targetResourceName ? `Transferência · ${item.resourceName ?? 'origem'} → ${item.targetResourceName}` : 'Transferência';
    default: return 'Movimento';
  }
}
function transactionFlow(item: ExtendedFinanceTransaction): 'in' | 'out' | 'none' {
  if (item.kind === 'income' || (item.kind === 'reserve_move' && item.direction === 'withdraw')) return 'in';
  if (item.kind === 'expense' || item.kind === 'card_purchase' || item.kind === 'card_payment' || (item.kind === 'reserve_move' && item.direction === 'deposit')) return 'out';
  return 'none';
}

const todayLocal = () => {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
};
const currentMonthLocal = () => todayLocal().slice(0, 7);
function moveMonth(month: string, offset: number) {
  const [yearText = '2000', indexText = '1'] = month.split('-');
  const year = Number(yearText);
  const index = Number(indexText);
  const date = new Date(year, index - 1 + offset, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}
function parseSignedBRL(value: string): number {
  const text = value.trim().replace(/^−/, '-');
  const negative = text.startsWith('-');
  const magnitude = parseBRL(negative ? text.slice(1).trim() : text);
  const result = negative ? -magnitude : magnitude;
  if (!Number.isSafeInteger(result)) throw new Error('INVALID_AMOUNT');
  return result;
}
function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T12:00:00`);
  return !Number.isNaN(date.getTime()) && `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` === value;
}
function localDateLabel(value: string) {
  const date = new Date(`${value}T12:00:00`);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString();
}

const errorMessages: Record<string, string> = {
  SECURITY_CHECKS_REQUIRED: 'Execute as verificações nativas aprovadas antes de configurar o livro.',
  ACCOUNT_REQUIRED: 'Configure a conta antes de registrar movimentos.',
  ACCOUNT_EXISTS: 'A conta financeira já está configurada.',
  UNSUPPORTED_MOVEMENT: 'Esse tipo de movimento ainda não é aceito.',
  DATE_BEFORE_OPENING: 'A data precisa ser igual ou posterior à abertura do livro.',
  FUTURE_DATE: 'Datas futuras ainda não são aceitas.',
  CONFIRMATION_CONFLICT: 'A conferência não corresponde ao estado atual do livro.',
  TRANSACTION_LIMIT: 'O limite de movimentos foi atingido.',
  ACCOUNT_CONFIRMATION_REQUIRED: 'Confirme a conta antes de continuar.',
  RECOVERY_BLOCKED: 'A recuperação local está bloqueada; confira o estado das cópias.',
  INVALID_AMOUNT: 'Informe um valor BRL válido dentro do limite seguro.',
  AMOUNT_LIMIT: 'O total ultrapassaria o limite monetário seguro.',
  INVALID_DATE: 'Confira a data informada.',
  INVALID_FIELDS: 'Confira os campos de texto.',
  NOT_CONFIGURED: 'Configure primeiro o saldo de abertura.',
  NOT_FOUND: 'O registro solicitado não está disponível.',
  PREVIOUS_EXISTS: 'Já existe uma caixa financeira anterior preservada. Volte a ela ou descarte-a antes de restaurar outra.',
  NO_PREVIOUS: 'Não há caixa financeira anterior disponível.',
  PASSWORD_LENGTH: 'Use uma senha de 12 a 128 caracteres.',
  INVALID_FILE: 'O arquivo não é um backup financeiro válido.',
  AUTH_FAILED: 'Senha incorreta ou arquivo alterado. Nenhuma caixa foi substituída.',
  TOO_LARGE: 'O backup excede os limites permitidos.',
  INVALID_DATA: 'Os dados do backup não foram aceitos.',
  IO: 'Não foi possível concluir a operação de arquivo.',
  BUSY: 'Outra operação de backup está em andamento.',
  STORAGE: 'O livro está indisponível no momento; seus dados foram preservados.',
  DISABLED: 'A recuperação local está desativada.',
  INVALID_INPUT: 'A cópia de recuperação não está disponível.',
  KEY_UNAVAILABLE: 'A chave de recuperação está indisponível; os dados foram preservados.',
  INDEX_INVALID: 'O índice de recuperação está inválido; os dados foram preservados.',
  SNAPSHOT_INVALID: 'A cópia de recuperação não passou na verificação; os dados foram preservados.',
  STORAGE_IO: 'Não foi possível acessar a recuperação local.',
  UNKNOWN_FILE: 'Há arquivos desconhecidos na pasta de recuperação; nada foi apagado.',
  CLEANUP_FAILED: 'A limpeza não terminou; as cópias restantes foram preservadas.',
  PREFERENCES_IO: 'Não foi possível salvar a configuração de recuperação.',
};
function failure(code?: string) { return code ? `${errorMessages[code] ?? 'Operação não concluída.'} (${code})` : 'Operação não concluída.'; }

export function FinancePanel({ native, busy, run, view, onNavigate, onTools, entryKind, entryRequest, valuesHidden, onToggleValues, epoch, onChanged, selectedMonth, onSelectedMonthChange, uncategorizedOnly = false, onBookChanged, initialRule, onCreateRule }: Props) {
  const [internalMonth, setInternalMonth] = useState(currentMonthLocal);
  const month = selectedMonth ?? internalMonth;
  const [visibleCount, setVisibleCount] = useState(50);
  const [catalogEpoch, setCatalogEpoch] = useState(0);
  const [historyKind, setHistoryKind] = useState<'all' | 'income' | 'expense'>('all');
  const [categoryFilter, setCategoryFilter] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [detailsId, setDetailsId] = useState<string | null>(null);
  const [metadataSelectedIds, setMetadataSelectedIds] = useState<string[]>([]);
  const previousBook = useRef<string | undefined>(undefined);
  const [snapshot, setSnapshot] = useState<FinanceSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [localBusy, setLocalBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [rulePrefill, setRulePrefill] = useState<RulePrefill | undefined>(initialRule);
  const [openingDate, setOpeningDate] = useState(todayLocal);
  const [openingBalance, setOpeningBalance] = useState('0,00');
  const [kind, setKind] = useState<'income' | 'expense'>('expense');
  const [effectiveDate, setEffectiveDate] = useState(todayLocal);
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [cancellationId, setCancellationId] = useState<string | null>(null);
  const [cancellationReason, setCancellationReason] = useState('');
  const [classification, setClassification] = useState<ClassificationSelection>({ categoryId: null, category: 'A classificar', tagIds: [] });
  const [balanceDate, setBalanceDate] = useState(todayLocal);
  const [balanceAmount, setBalanceAmount] = useState('');
  const [advanced, setAdvanced] = useState(false);
  const [recoveryEntries, setRecoveryEntries] = useState<LocalRecoveryEntry[]>([]);
  const [recoveryPassword, setRecoveryPassword] = useState('');
  const [backupPassword, setBackupPassword] = useState('');
  const [backupConfirmation, setBackupConfirmation] = useState('');
  const generation = useRef(0);
  const mounted = useRef(false);
  const active = useRef(AppState.currentState !== 'background' && AppState.currentState !== 'inactive');
  const monthRef = useRef(month); monthRef.current = month;
  const nativeRef = useRef(native); nativeRef.current = native;
  const runRef = useRef(run); runRef.current = run;
  const commandId = useRef<string | null>(null);
  const clearBackupSecret = useRef<(() => void) | null>(null);
  const operationToken = useRef(0);

  const refresh = useCallback(async (requestedMonth = monthRef.current) => {
    const request = ++generation.current;
    setLoading(true);
    try {
      const result = await nativeRef.current.financeSnapshot(requestedMonth);
      if (!mounted.current || !active.current || generation.current !== request) return;
      if (result.status === 'error' || !result.snapshot) {
        setError(failure(result.code));
        setSnapshot(null);
      } else {
        setSnapshot(result.snapshot);
        setError(null);
      }
    } catch {
      if (mounted.current && active.current && generation.current === request) {
        setSnapshot(null); setError('Não foi possível abrir o livro financeiro local.');
      }
    } finally {
      if (mounted.current && active.current && generation.current === request) setLoading(false);
    }
  }, []);

  const clearPrivate = useCallback((rerender = true) => {
    generation.current += 1;
    operationToken.current += 1;
    commandId.current = null;
    clearBackupSecret.current?.();
    clearBackupSecret.current = null;
    if (rerender && mounted.current) {
      setSnapshot(null); setRecoveryEntries([]); setOpeningBalance(''); setAmount(''); setDescription('');
      setCancellationId(null); setCancellationReason('');
      setClassification({ categoryId: null, category: 'A classificar', tagIds: [] }); setBalanceAmount('');
      setOpeningDate(todayLocal()); setEffectiveDate(todayLocal()); setBalanceDate(todayLocal()); setKind('expense');
      setRecoveryPassword(''); setBackupPassword(''); setBackupConfirmation(''); setAdvanced(false);
      setError(null); setMessage(null); setQuery(''); setCategoryFilter(null); setHistoryKind('all'); setLoading(false); setLocalBusy(false);
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void refresh(month);
    const subscription = AppState.addEventListener('change', state => {
      active.current = state === 'active';
      if (!active.current) clearPrivate();
      else if (mounted.current) void refresh(monthRef.current);
    });
    return () => { subscription.remove(); mounted.current = false; clearPrivate(false); };
  }, [clearPrivate, refresh]);

  useEffect(() => { if (mounted.current && active.current) void refresh(month); }, [month, refresh, epoch]);
  useEffect(() => { if (selectedMonth && selectedMonth !== internalMonth) setInternalMonth(selectedMonth); }, [selectedMonth, internalMonth]);
  useEffect(() => { setRulePrefill(initialRule); }, [initialRule]);
  useEffect(() => {
    if (snapshot?.bookId && previousBook.current && snapshot.bookId !== previousBook.current) { resetAfterBoxChange(); onBookChanged?.(); }
    if (snapshot?.bookId) previousBook.current = snapshot.bookId;
  }, [snapshot?.bookId, onBookChanged]);
  useEffect(() => {
    if (!entryKind) return;
    commandId.current = null;setKind(entryKind);setAmount('');setDescription('');setEffectiveDate(todayLocal());
    setClassification({ categoryId: null, category: 'A classificar', tagIds: [] });
  }, [entryKind, entryRequest]);

  const loadRecoveryEntries = useCallback(async () => {
    const request = generation.current;
    try {
      const entries = await nativeRef.current.financeRecoveryEntries();
      if (mounted.current && active.current && generation.current === request) setRecoveryEntries(entries);
    } catch {
      if (mounted.current && active.current && generation.current === request) setError('Não foi possível listar as cópias locais.');
    }
  }, []);
  useEffect(() => { if (advanced && active.current) void loadRecoveryEntries(); }, [advanced, loadRecoveryEntries]);

  function edited() { commandId.current = null; setError(null); setMessage(null); }
  function editAmount(value: string) { edited(); setAmount(value); }
  function editDescription(value: string) { edited(); setDescription(value); }
  function editClassification(value: ClassificationSelection) { edited(); setClassification(value); }
  function editKind(value: 'income' | 'expense') { edited(); setKind(value); }
  function editEffectiveDate(value: string) { edited(); setEffectiveDate(value); }

  function resetAfterBoxChange() {
    commandId.current = null;
    setHistoryKind('all');setCategoryFilter(null);setQuery('');
    setAmount('');setDescription('');setEffectiveDate(todayLocal());
    setClassification({ categoryId: null, category: 'A classificar', tagIds: [] });
    setCancellationId(null);setCancellationReason('');setBalanceAmount('');setBalanceDate(todayLocal());
    setOpeningBalance('0,00');setOpeningDate(todayLocal());setVisibleCount(50);
    setCatalogEpoch(value => value + 1);
  }

  async function perform<T extends FinanceResult | BackupResult | void>(task: () => Promise<T>, after?: (result: T) => void, success?: string) {
    if (!mounted.current || !active.current || busy || localBusy) return;
    const request = generation.current;
    const token = ++operationToken.current;
    setLocalBusy(true); setError(null); setMessage(null);
    try {
      await runRef.current(async () => {
        try {
          const result = await task();
          if (!mounted.current || !active.current || generation.current !== request) return;
          if (result && 'status' in result && result.status === 'error') setError(failure(result.code));
          else {
            if(result && 'status' in result && result.status === 'restored') resetAfterBoxChange();
            if (after) after(result); if (success) setMessage(success); onChanged?.();
          }
        } catch {
          if (mounted.current && active.current && generation.current === request) setError('Não foi possível concluir a operação financeira.');
        }
      });
      if (mounted.current && active.current) { await refresh(monthRef.current); onChanged?.(); }
    } catch {
      if (mounted.current && active.current && generation.current === request) setError('Não foi possível concluir a operação financeira.');
    } finally {
      if (mounted.current && active.current && operationToken.current === token) setLocalBusy(false);
    }
  }

  function consentSetup() {
    if (!snapshot?.securityChecksPassed) return;
    if (!validDate(openingDate)) { setError(failure('INVALID_DATE')); return; }
    let openingBalanceCents: number;
    try { openingBalanceCents = parseSignedBRL(openingBalance); } catch { setError(failure('INVALID_AMOUNT')); return; }
    Alert.alert('Começar seu livro?', 'O saldo informado vale antes dos lançamentos desta data. Caixinhas e dívidas são registradas à parte. Nenhum dado será importado automaticamente.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Concordo e configurar', onPress: () => {
        const fieldsJson = JSON.stringify({ openingDate, openingBalanceCents });
        void perform(() => nativeRef.current.setupFinance(fieldsJson), undefined, 'Livro financeiro configurado localmente.');
      } },
    ]);
  }

  function submitTransaction() {
    if (!validDate(effectiveDate)) { setError(failure('INVALID_DATE')); return; }
    let amountCents: number;
    try { amountCents = parseBRL(amount); if (amountCents <= 0) throw new Error('INVALID_AMOUNT'); }
    catch { setError(failure('INVALID_AMOUNT')); return; }
    if (!description.trim()) { setError(failure('INVALID_FIELDS')); return; }
    const command = commandId.current ?? Crypto.randomUUID();
    commandId.current = command;
    let payloadJson = JSON.stringify({ commandId: command, amountCents, kind, effectiveDate, description: description.trim(),
      category: classification.category, categoryId: classification.categoryId, tagIds: classification.tagIds });
    void perform(() => nativeRef.current.createFinanceTransaction(payloadJson), result => {
      if (result && 'status' in result && result.status === 'ok') {
        commandId.current = null; setAmount(''); setDescription(''); setClassification({ categoryId: null, category: 'A classificar', tagIds: [] });
        setHistoryKind('all');setCategoryFilter(null);setQuery('');setVisibleCount(50);onNavigate('transactions');
      }
    }, 'Movimento manual salvo.').finally(() => { payloadJson = ''; });
  }

  function submitBalanceCheck() {
    if (!validDate(balanceDate)) { setError(failure('INVALID_DATE')); return; }
    let amountCents: number;
    try { amountCents = parseSignedBRL(balanceAmount); } catch { setError(failure('INVALID_AMOUNT')); return; }
    const fieldsJson = JSON.stringify({ date: balanceDate, amountCents });
    void perform(() => nativeRef.current.recordFinanceBalance(fieldsJson), undefined, 'Conferência registrada; ela não altera o saldo nem cria movimento.');
  }

  function requestCancellation(item: FinanceTransaction) {
    Alert.alert('Anular este registro?', 'Isso anula somente o registro no livro. Não cancela uma operação bancária e não é um estorno.', [
      { text: 'Voltar', style: 'cancel' },
      { text: 'Informar motivo', style: 'destructive', onPress: () => {
        if (!mounted.current || !active.current) return;
        setCancellationId(item.id); setCancellationReason(''); setError(null);
      } },
    ]);
  }

  function submitCancellation() {
    if (!cancellationId || !cancellationReason.trim()) { setError(failure('INVALID_FIELDS')); return; }
    const fieldsJson = JSON.stringify({ id: cancellationId, reason: cancellationReason.trim() });
    void perform(() => nativeRef.current.cancelFinanceTransaction(fieldsJson), result => {
      if (result.status === 'ok') { setCancellationId(null); setCancellationReason(''); }
    }, 'Registro anulado com motivo de auditoria.');
  }

  function portableBackup(mode: 'export' | 'restore') {
    const password = backupPassword;
    if (password.length < 12 || password.length > 128 || (mode === 'export' && password !== backupConfirmation)) {
      setError('Use uma senha de 12 a 128 caracteres e confirme-a para exportar.'); return;
    }
    setBackupPassword(''); setBackupConfirmation('');
    let secret = password;
    clearBackupSecret.current = () => { secret = ''; };
    if (mode === 'restore') {
      if (snapshot?.previousAvailable) { secret = ''; clearBackupSecret.current = null; setError(failure('PREVIOUS_EXISTS')); return; }
      Alert.alert('Restaurar backup financeiro?', 'O livro atual será substituído e ficará guardado como versão anterior. Os registros não serão misturados. A captura temporária não faz parte do backup.', [
        { text: 'Cancelar', style: 'cancel', onPress: () => { secret = ''; clearBackupSecret.current = null; } },
        { text: 'Escolher backup', style: 'destructive', onPress: () => {
          void perform(() => nativeRef.current.restoreFinanceBackup(secret), result => {
            if (result && 'status' in result && result.status === 'cancelled') setMessage('Seleção cancelada.');
            else if (result && 'status' in result && result.status === 'restored') setMessage('Backup restaurado em caixa separada; a anterior foi preservada.');
          }).finally(() => { secret = ''; clearBackupSecret.current = null; });
        } },
      ]);
    } else {
      void perform(() => nativeRef.current.exportFinanceBackup(secret), result => {
        if (result && 'status' in result && result.status === 'saved') setMessage('Backup protegido por senha salvo no local escolhido. Guarde o arquivo e a senha.');
        else if (result && 'status' in result && result.status === 'cancelled') setMessage('Seleção cancelada.');
      }).finally(() => { secret = ''; clearBackupSecret.current = null; });
    }
  }

  function restoreLocal(entry: LocalRecoveryEntry) {
    if (snapshot?.previousAvailable) { setError(failure('PREVIOUS_EXISTS')); return; }
    Alert.alert('Restaurar cópia local?', 'Uma nova caixa será ativada e a caixa atual ficará preservada como anterior. Registros não serão mesclados. Cópias locais incluem livro e catálogo, sem notificações do diagnóstico.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Restaurar cópia', style: 'destructive', onPress: () => void perform(() => nativeRef.current.restoreFinanceRecovery(entry.id), result => {
        if (result.status === 'restored') setMessage('Cópia restaurada; a caixa anterior foi preservada.');
      }).finally(() => { void loadRecoveryEntries(); }) },
    ]);
  }

  function toggleRecovery() {
    const enabled = !snapshot?.recovery.enabled;
    Alert.alert(enabled ? 'Ativar recuperação local?' : 'Desativar recuperação local?', enabled
      ? 'Serão mantidas até três cópias cifradas, no máximo uma a cada 24 horas com alterações.'
      : 'As cópias existentes serão preservadas; nenhuma nova cópia será criada.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: enabled ? 'Ativar' : 'Desativar', onPress: () => void perform(async () => { await nativeRef.current.setFinanceRecoveryEnabled(enabled); }) },
    ]);
  }

  function clearRecovery() {
    Alert.alert('Apagar cópias locais?', 'Apaga somente as cópias locais do livro e desativa novas cópias. O livro ativo e a caixa anterior não serão alterados.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Apagar cópias', style: 'destructive', onPress: () => void perform(() => nativeRef.current.clearFinanceRecovery(), () => setRecoveryEntries([]), 'Cópias locais apagadas e recuperação desativada.') },
    ]);
  }

  function switchPrevious() {
    Alert.alert('Voltar à caixa financeira anterior?', 'A caixa anterior será ativada e a atual ficará preservada. Registros não serão mesclados.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Alternar caixas', style: 'destructive', onPress: () => void perform(() => nativeRef.current.switchPreviousFinance(), result => { if(result.status === 'ok') resetAfterBoxChange(); }, 'Caixas alternadas sem mesclar registros.') },
    ]);
  }

  function discardPrevious() {
    Alert.alert('Descartar a caixa financeira anterior?', 'Apaga permanentemente somente a caixa anterior. O livro ativo e o catálogo compartilhado são preservados.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Descartar caixa anterior', style: 'destructive', onPress: () => void perform(() => nativeRef.current.discardPreviousFinance(), undefined, 'Caixa anterior descartada.') },
    ]);
  }

  function restartBook() {
    Alert.alert('Recomeçar o livro?', 'Um livro vazio será criado. O atual ficará guardado como versão anterior; os registros não serão misturados. A captura temporária será pausada.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Criar livro vazio', style: 'destructive', onPress: () => void perform(() => nativeRef.current.restartFinance(), result => { if(result.status === 'ok') resetAfterBoxChange(); }, 'Novo livro vazio criado; configure o saldo de abertura.') },
    ]);
  }

  const blocked = busy || localBusy || loading;
  const ready = snapshot?.configured && snapshot.month === month;
  const activeCategoryFilter = uncategorizedOnly ? categoryKey(null, 'A classificar') : categoryFilter;
  const filtered = snapshot ? filterTransactions(snapshot.transactions, { kind: historyKind, category: activeCategoryFilter, query }) : [];
  const transactions = filtered.slice(0, visibleCount);
  const selectedCategory = snapshot?.byCategory.find(row => categoryKey(row.categoryId, row.name) === activeCategoryFilter)?.name;
  function openMovements(kind: 'all' | 'income' | 'expense' = 'all', category: string | null = null) {
    setHistoryKind(kind);setCategoryFilter(category);setQuery('');setVisibleCount(50);onNavigate('transactions');
  }
  function monthStep(step: number) { setVisibleCount(50);setCategoryFilter(null);const next = moveMonth(month, step);setInternalMonth(next);onSelectedMonthChange?.(next); }
  function metadataSaved() { void refresh(monthRef.current); onChanged?.(); }
  function createRuleFromCorrection(input: RulePrefill) { setRulePrefill(input); if (onCreateRule) onCreateRule(input); else onNavigate('rules'); }
  return <>
    {error && <Card><ErrorBox message={error} /><Action title="Tentar novamente" secondary disabled={blocked} onPress={() => void refresh(monthRef.current)} /></Card>}
    {message && <Text accessibilityRole="alert" style={styles.small}>{message}</Text>}
    {loading && <Text style={styles.small}>Carregando registros…</Text>}
    {snapshot && !snapshot.configured && view !== 'more' && view !== 'categories' && <Card>
      <Text style={styles.heading}>Seu histórico começa aqui</Text>
      <Text style={styles.small}>Registre entradas e despesas sem informar saldo bancário.</Text>
      <Action title="Começar" disabled={blocked} onPress={() => void perform(() => nativeRef.current.startTracking(), undefined, 'Pronto para registrar.')} />
    </Card>}
    {snapshot?.configured && (view === 'home' || view === 'transactions') && <View style={[styles.row, { backgroundColor: colors.card, paddingHorizontal: 8, borderRadius: 4 }]}>
      <Action title="‹" accessibilityLabel="Mês anterior" secondary disabled={blocked} onPress={() => monthStep(-1)} />
      <Text style={[styles.body, { flex: 1, textAlign: 'center', textTransform: 'capitalize' }]}>{formatMonth(month)}</Text>
      <Action title="›" accessibilityLabel="Próximo mês" secondary disabled={blocked} onPress={() => monthStep(1)} />
    </View>}
    {ready && view === 'home' && <FinanceDashboard native={native} snapshot={snapshot} busy={blocked} hidden={valuesHidden} onToggleValues={onToggleValues}
      onCategory={key => openMovements('expense', key)} onMovements={kind => openMovements(kind)}
      onNew={nextKind => { editKind(nextKind);onNavigate('entry'); }} onBalance={() => onNavigate('balance')} />}
    {ready && view === 'transactions' && <Card>
      <Field label="Buscar descrição, categoria ou tag" value={query} onChangeText={value => { setVisibleCount(50);setQuery(value); }} editable={!blocked} autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={100} />
      <View style={styles.wrap}>{(['all','expense','income'] as const).map(value => <Chip key={value} title={value === 'all' ? 'Tudo' : value === 'expense' ? 'Despesas' : 'Receitas'} active={historyKind === value} onPress={() => { if(!blocked) { setHistoryKind(value);setVisibleCount(50); } }} />)}</View>
      {!!categoryFilter && !uncategorizedOnly && <Action secondary title={`${selectedCategory ?? 'Categoria selecionada'} · limpar filtro`} disabled={blocked} onPress={() => setCategoryFilter(null)} />}
      <Text style={styles.small}>{filtered.length} registros</Text>
      {transactions.length === 0 && <Text style={styles.small}>Nenhum movimento encontrado.</Text>}
      {transactions.map((item: FinanceTransaction, index: number) => {
        const extended = item as ExtendedFinanceTransaction;
        const flow = transactionFlow(extended);
        const amountPrefix = flow === 'in' ? '+ ' : flow === 'out' ? '− ' : '↔ ';
        const reclassifiable = !item.voided && (extended.kind === 'income' || extended.kind === 'expense' || extended.kind === 'card_purchase');
        const cancellable = !item.voided && extended.scope !== 'extended' && (extended.kind === 'income' || extended.kind === 'expense');
        return <View key={`${extended.scope ?? 'finance'}:${item.id}`} style={{ borderTopWidth: 1, borderColor: colors.line, paddingTop: 14, gap: 7 }}>
        {index === 0 || transactions[index-1]?.effectiveDate !== item.effectiveDate ? <Text style={styles.small}>{localDateLabel(item.effectiveDate)}</Text> : null}
        <Pressable accessibilityRole="button" accessibilityLabel={`Detalhes de ${item.description}`} onPress={() => setDetailsId(value => value === item.id ? null : item.id)} style={{ minHeight: 48, justifyContent: 'center', gap: 6 }}>
        <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}><Text style={[styles.label, { flexShrink: 1 }]}>{item.description || 'Sem descrição'}</Text><Text style={{ color: item.voided ? colors.muted : flow === 'in' ? colors.cyan : colors.ink, fontSize: 16, fontWeight: '600', textDecorationLine: item.voided ? 'line-through' : 'none' }}>{amountPrefix}{valuesHidden ? '••••' : formatBRL(item.amountCents)}</Text></View>
        <Text style={styles.small}>{transactionKindLabel(extended)}{extended.kind !== 'transfer' && extended.resourceName ? ` · ${extended.resourceName}` : ''}{extended.invoiceMonth ? ` · fatura ${extended.invoiceMonth}` : ''}</Text>
        {(extended.kind === 'income' || extended.kind === 'expense' || extended.kind === 'card_purchase') && <Text style={styles.small}>{item.category}{item.tags.length ? ` · ${item.tags.join(', ')}` : ''}</Text>}
        <Text style={styles.small}>{extended.sourceType === 'statement' ? 'Extrato' : extended.sourceType === 'card' ? 'Cartão' : extended.sourceEventId ? 'Notificação' : 'Manual'}</Text>
        </Pressable>
        {detailsId === item.id && <View style={{ gap: 8 }}>
          {item.voided ? <Text style={styles.small}>Anulado · {item.cancellationReason}</Text> : <>
            {reclassifiable && <Action title="Reclassificar" secondary disabled={blocked} onPress={() => { setMetadataSelectedIds([item.id]); onNavigate('metadata'); }} />}
            {cancellable && <Action title="Anular registro" secondary disabled={blocked} onPress={() => requestCancellation(item)} />}
          </>}
        </View>}
        {cancellationId === item.id && <View style={{ gap: 8 }}>
          <Field label="Motivo da anulação" value={cancellationReason} onChangeText={setCancellationReason} editable={!blocked} maxLength={500} multiline autoCorrect={false} autoComplete="off" importantForAutofill="no" />
          <Action title="Confirmar anulação" disabled={blocked || !cancellationReason.trim()} onPress={submitCancellation} />
          <Action title="Voltar" secondary disabled={blocked} onPress={() => { setCancellationId(null);setCancellationReason(''); }} />
        </View>}
      </View>;
      })}
      {filtered.length > transactions.length && <Action secondary title={`Mostrar mais (${transactions.length} de ${filtered.length})`} disabled={blocked} onPress={() => setVisibleCount(count => count + 50)} />}
    </Card>}
    {snapshot?.configured && view === 'entry' && <>
      <Card>
        <Text style={styles.heading}>Registrar movimento manual</Text>

        <View style={styles.wrap}>
          <Chip title="Despesa" active={kind === 'expense'} onPress={() => editKind('expense')} />
          <Chip title="Receita" active={kind === 'income'} onPress={() => editKind('income')} />
        </View>
        <Field label="Valor em BRL" value={amount} onChangeText={editAmount} editable={!blocked} keyboardType="decimal-pad" maxLength={32} autoCorrect={false} autoComplete="off" importantForAutofill="no" placeholder="12,34" />
        <Field label="Data efetiva (AAAA-MM-DD)" value={effectiveDate} onChangeText={editEffectiveDate} editable={!blocked} maxLength={10} autoCorrect={false} autoComplete="off" importantForAutofill="no" />
        <Field label="Descrição" value={description} onChangeText={editDescription} editable={!blocked} maxLength={500} autoCorrect={false} autoComplete="off" importantForAutofill="no" />
        <ClassificationPanel key={catalogEpoch} native={native} busy={blocked} run={run} selection={classification} onChange={editClassification} />
        <Action title="Salvar movimento" disabled={blocked || !amount.trim() || !description.trim() || !validDate(effectiveDate)} onPress={submitTransaction} />
      </Card>

    </>}
    {snapshot?.configured && view === 'balance' && <>
      <Card>
        <Text style={styles.heading}>Conferir saldo de uma data</Text>
        <Text style={styles.small}>A conferência compara o saldo informado ao final da data com o calculado. Não cria ajuste, receita ou despesa.</Text>
        <Field label="Data (AAAA-MM-DD)" value={balanceDate} onChangeText={setBalanceDate} editable={!blocked} maxLength={10} autoCorrect={false} autoComplete="off" importantForAutofill="no" />
        <Field label="Saldo informado em BRL (pode ser negativo)" value={balanceAmount} onChangeText={setBalanceAmount} editable={!blocked} keyboardType="decimal-pad" maxLength={32} autoCorrect={false} autoComplete="off" importantForAutofill="no" />
        <Action title="Registrar conferência" secondary disabled={blocked || !balanceAmount.trim() || !validDate(balanceDate)} onPress={submitBalanceCheck} />
        {snapshot.balanceCheck && <View style={{ gap: 4 }}>
          <Text style={styles.label}>Conferência de {localDateLabel(snapshot.balanceCheck.date)}</Text>
          <Text style={styles.body}>Informado: {formatBRL(snapshot.balanceCheck.amountCents)} · calculado: {formatBRL(snapshot.balanceCheck.calculatedCents)}</Text>
          <Text style={styles.small}>Diferença: {formatBRL(snapshot.balanceCheck.differenceCents)}. O saldo calculado permanece inalterado.</Text>
        </View>}
      </Card>

    </>}
    {view === 'categories' && <ClassificationPanel key={catalogEpoch} native={native} busy={blocked} run={run} />}
    {snapshot && view === 'resources' && <>
      <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}><Text style={styles.heading}>Caixinhas e cartões</Text><View style={styles.row}><Action title={valuesHidden ? 'Mostrar' : 'Ocultar'} accessibilityLabel={valuesHidden ? 'Mostrar valores' : 'Ocultar valores'} secondary disabled={blocked} onPress={onToggleValues} /><Action title="Voltar" secondary disabled={blocked} onPress={() => onNavigate('more')} /></View></View>
      <ResourcePanel native={native} busy={blocked} run={run} snapshot={snapshot} month={month} refresh={refresh} valuesHidden={valuesHidden} onChanged={onChanged} />
    </>}
    {snapshot && view === 'metadata' && <>
      <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}><Text style={styles.heading}>Reclassificação</Text><View style={styles.row}><Action title={valuesHidden ? 'Mostrar' : 'Ocultar'} accessibilityLabel={valuesHidden ? 'Mostrar valores' : 'Ocultar valores'} secondary disabled={blocked} onPress={onToggleValues} /><Action title="Voltar" secondary disabled={blocked} onPress={() => onNavigate('more')} /></View></View>
      <MetadataPanel native={native} busy={blocked} run={run} transactions={snapshot.transactions} initialSelectedIds={metadataSelectedIds} valuesHidden={valuesHidden} onSaved={() => { setMetadataSelectedIds([]); metadataSaved(); }} onCreateRule={createRuleFromCorrection} />
    </>}
    {snapshot && view === 'rules' && <>
      <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}><Text style={styles.heading}>Regras locais</Text><View style={styles.row}><Action title={valuesHidden ? 'Mostrar' : 'Ocultar'} accessibilityLabel={valuesHidden ? 'Mostrar valores' : 'Ocultar valores'} secondary disabled={blocked} onPress={onToggleValues} /><Action title="Voltar" secondary disabled={blocked} onPress={() => onNavigate('more')} /></View></View>
      <RulesPanel native={native} busy={blocked} run={run} initialRule={rulePrefill} valuesHidden={valuesHidden} onChanged={metadataSaved} />
    </>}
    {view === 'more' && <>
      <Card>
        <Text style={styles.heading}>Seus registros</Text>

        <Action title="Categorias e tags" secondary disabled={blocked} onPress={() => onNavigate('categories')} />
        <Action title="Caixinhas, cartões e faturas" secondary disabled={blocked} onPress={() => onNavigate('resources')} />
        <Action title="Reclassificar movimentos" secondary disabled={blocked} onPress={() => onNavigate('metadata')} />
        <Action title="Regras de classificação" secondary disabled={blocked} onPress={() => onNavigate('rules')} />
        <Text style={styles.small}>O Tique não consulta sua conta bancária.</Text>
      </Card>
    <Card>
        <Action title={advanced ? 'Fechar opções avançadas' : 'Opções avançadas'} secondary disabled={busy} onPress={() => setAdvanced(value => !value)} />
        {advanced && <>
          <Action title="Conferência de saldo (opcional)" secondary disabled={blocked || !snapshot?.configured} onPress={() => onNavigate('balance')} />
          <Action secondary title="Avançado: captura, permissões e verificações" disabled={busy || localBusy} onPress={onTools} />
          <Text style={styles.heading}>Cópias neste aparelho</Text>
          <Text style={styles.small}>O app mantém até três cópias recentes. Elas podem ser perdidas ao desinstalar ou trocar de aparelho. Para levar seus dados, salve um backup em outro lugar.</Text>
          <Text style={styles.small}>Estado: {snapshot?.recovery.status ?? 'indisponível'} · {snapshot?.recovery.count ?? recoveryEntries.length} cópias · {snapshot?.recovery.enabled ? 'ativada' : 'desativada'}{snapshot?.recovery.failureCode ? ` · ${snapshot.recovery.failureCode}` : ''}</Text>
          <View style={styles.wrap}>
            <Action title={snapshot?.recovery.enabled ? 'Desativar novas cópias' : 'Ativar cópias locais'} secondary disabled={busy || localBusy || loading || !snapshot} onPress={toggleRecovery} />
            <Action title="Apagar cópias locais" secondary disabled={busy || localBusy || loading || !!snapshot && snapshot.recovery.status !== 'blocked' && (snapshot.recovery.count ?? recoveryEntries.length) === 0} onPress={clearRecovery} />
            <Action title="Atualizar lista" secondary disabled={busy || localBusy || loading} onPress={() => void loadRecoveryEntries()} />
          </View>
          {recoveryEntries.map(entry => <View key={entry.id} style={{ borderTopWidth: 1, borderColor: colors.line, paddingTop: 8, gap: 6 }}>
            <Text style={styles.small}>{localDateLabel(entry.createdAt.slice(0, 10))} · {entry.count} registros</Text>
            <Action title="Restaurar cópia local" secondary disabled={busy || localBusy || loading || !!snapshot?.previousAvailable} onPress={() => restoreLocal(entry)} />
          </View>)}
          {snapshot?.previousAvailable && <Text style={styles.warning}>Há uma caixa financeira anterior. Volte a ela ou descarte-a antes de restaurar outra caixa.</Text>}

          <Text style={styles.heading}>Backup portátil</Text>
          <Text style={styles.small}>O arquivo exige senha para abrir e não inclui a captura temporária. Salve-o em local seguro; o Tique não envia nem compartilha o arquivo.</Text>
          <Field label="Senha do backup (12 a 128 caracteres)" value={backupPassword} onChangeText={setBackupPassword} editable={!blocked} secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={128} />
          <Field label="Confirmar senha para exportar" value={backupConfirmation} onChangeText={setBackupConfirmation} editable={!blocked} secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={128} />
          <View style={styles.wrap}>
            <Action title="Salvar backup protegido por senha" disabled={blocked || backupPassword.length < 12 || backupPassword.length > 128 || backupPassword !== backupConfirmation} onPress={() => portableBackup('export')} />
            <Action title="Restaurar backup" secondary disabled={busy || localBusy || loading || backupPassword.length < 12 || backupPassword.length > 128 || !!snapshot?.previousAvailable} onPress={() => portableBackup('restore')} />
          </View>
          {snapshot?.previousAvailable && <View style={styles.wrap}>
            <Action title="Voltar à caixa anterior" secondary disabled={busy || localBusy || loading} onPress={switchPrevious} />
            <Action title="Descartar caixa anterior" secondary disabled={busy || localBusy || loading} onPress={discardPrevious} />
          </View>}
          {snapshot?.configured && <Action title="Recomeçar livro com novo saldo" secondary disabled={busy || localBusy || loading || snapshot.previousAvailable} onPress={restartBook} />}
        </>}
      </Card>
    </>}
  </>;
}
