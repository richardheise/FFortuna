import Constants from 'expo-constants';
import type { ObservedEvent } from '../core/types';
import { requireOptionalNativeModule } from 'expo';

export interface NativeObservation extends Omit<ObservedEvent, 'payload'> {
  payload: string | null; sourcePackage: string;
  state: 'pending' | 'processed'; parserVersion: string | null; parsedJson: string | null;
}
export interface BoxRetentionStatus {
  status: 'ready' | 'blocked' | 'none'; removed?: number; remaining?: number;
  nextExpiry?: string | null; failureCode?: string | null;
}
export interface RetentionStatus {
  blocked: boolean; active?: BoxRetentionStatus; previous?: BoxRetentionStatus;
}
export interface LocalRecoveryStatus {
  status: 'ready' | 'disabled' | 'blocked'; enabled: boolean; count: number;
  latestAt: string | null; failureCode: string | null;
}
export interface LocalRecoveryEntry { id: string; createdAt: string; count: number }
export interface SecurityCheckCase {
  id: string; title: string; status: 'passed' | 'failed' | 'not_verified'; code: string | null;
}
export interface SecurityReport {
  ranAt: string; syntheticOnly: boolean; model: string; sdk: number; scope: string;
  passed: number; failed: number; notVerified: number; cases: SecurityCheckCase[];
}
export interface CaptureSnapshot {
  enabled: boolean; authorized: boolean; connected: boolean; notificationsAllowed: boolean;
  storageReady: boolean; sourcePackage: string; lastError: string | null; syntheticOnly: true;
  previousBoxAvailable: boolean; diagnostic?: DiagnosticStatus;
  retention?: RetentionStatus;
  localRecovery?: LocalRecoveryStatus;
  securityReport?: SecurityReport | null;
  cipherVersion?: string; schemaVersion?: number; total?: number; pending?: number; unavailablePending?: number;
  observations?: NativeObservation[];
}
export type BackupResult = { status: 'saved' | 'restored' | 'cancelled' | 'error'; code?: string; count?: number; unavailable?: number };
export interface CaptureModule {
  dailyState(): Promise<DailyState>;
  openBankApp(): Promise<void>;
  reconnectCapture(): Promise<void>;
  startTracking(): Promise<FinanceResult>;
  startDailyCapture(signer: string): Promise<DiagnosticResult>;
  chooseStatement(): Promise<StatementResult>;
  importSharedStatement(): Promise<StatementResult>;
  dismissSharedStatement(): Promise<void>;
  linkObserved(id: string, transactionId: string): Promise<FinanceResult>;
  financeSnapshot(month: string): Promise<FinanceResult>;
  setupFinance(fieldsJson: string): Promise<FinanceResult>;
  createFinanceTransaction(fieldsJson: string): Promise<FinanceResult>;
  createFinanceReserve(fieldsJson: string): Promise<FinanceResult>;
  moveFinanceReserve(fieldsJson: string): Promise<FinanceResult>;
  transferFinanceReserve(fieldsJson: string): Promise<FinanceResult>;
  updateFinanceReserve(fieldsJson: string): Promise<FinanceResult>;
  createFinanceCard(fieldsJson: string): Promise<FinanceResult>;
  createFinanceCommitment(fieldsJson: string): Promise<FinanceResult>;
  createCardPurchase(fieldsJson: string): Promise<FinanceResult>;
  payFinanceCommitment(fieldsJson: string): Promise<FinanceResult>;
  listFinanceRules(): Promise<FinanceRuleListResult>;
  saveFinanceRule(fieldsJson: string): Promise<FinanceRuleMutationResult>;
  setFinanceRuleEnabled(id: string, enabled: boolean): Promise<FinanceRuleMutationResult>;
  previewFinanceRule(id: string): Promise<FinanceRulePreviewResult>;
  reclassifyFinanceTransactions(fieldsJson: string): Promise<FinanceReclassificationResult>;
  recordFinanceBalance(fieldsJson: string): Promise<FinanceResult>;
  cancelFinanceTransaction(fieldsJson: string): Promise<FinanceResult>;
  restartFinance(): Promise<FinanceResult>;
  confirmDiagnosticCandidate(id: string, fieldsJson: string): Promise<FinanceResult>;
  confirmObservedBatch(itemsJson: string): Promise<ObservedBatchResult>;
  exportFinanceBackup(password: string): Promise<BackupResult>;
  restoreFinanceBackup(password: string): Promise<BackupResult>;
  financeRecoveryEntries(): Promise<LocalRecoveryEntry[]>;
  restoreFinanceRecovery(id: string): Promise<BackupResult>;
  setFinanceRecoveryEnabled(enabled: boolean): Promise<void>;
  clearFinanceRecovery(): Promise<void>;
  switchPreviousFinance(): Promise<FinanceResult>;
  discardPreviousFinance(): Promise<FinanceResult>;
  snapshot(): Promise<CaptureSnapshot>;
  setEnabled(value: boolean): Promise<CaptureSnapshot>;
  emit(scenario: 'single' | 'same' | 'update' | 'group' | 'delayed'): Promise<void>;
  recover(): Promise<CaptureSnapshot>;
  verify(): Promise<boolean>;
  reset(): Promise<void>;
  replay(): void;
  openSettings(): void;
  requestPostPermission(): void;
  exportBackup(password: string): Promise<BackupResult>;
  restoreBackup(password: string): Promise<BackupResult>;
  switchPreviousBox(): Promise<void>;
  discardPreviousBox(): Promise<void>;
  maintainRetention(): Promise<CaptureSnapshot>;
  localRecoveryEntries(): Promise<LocalRecoveryEntry[]>;
  setLocalRecoveryEnabled(value: boolean): Promise<void>;
  clearLocalRecovery(): Promise<void>;
  restoreLocalRecovery(id: string): Promise<BackupResult>;
  runSecurityChecks(): Promise<SecurityReport>;
  classifications(): Promise<ClassificationResult>;
  createClassification(kind: ClassificationItem['kind'], name: string): Promise<ClassificationResult>;
  renameClassification(id: string, name: string): Promise<ClassificationResult>;
  archiveClassification(id: string, archived: boolean): Promise<ClassificationResult>;
  diagnosticCandidate(id: string, force: boolean): Promise<DiagnosticResult>;
  saveDiagnosticCandidate(id: string, fieldsJson: string): Promise<DiagnosticResult>;
  diagnosticSnapshot(): Promise<DiagnosticStatus>;
  startDiagnostic(expectedSigner: string): Promise<DiagnosticResult>;
  stopDiagnostic(): Promise<DiagnosticResult>;
  clearDiagnostic(): Promise<DiagnosticResult>;
  diagnosticSamples(): Promise<DiagnosticResult>;
  diagnosticSample(id: string): Promise<DiagnosticResult>;
  reviewDiagnostic(id: string, fieldsJson: string, confirmed: boolean): Promise<DiagnosticResult>;
  excludeDiagnosticReview(id: string): Promise<DiagnosticResult>;
  verifyDiagnosticExport(password: string): Promise<DiagnosticResult>;
  exportDiagnostic(password: string): Promise<DiagnosticResult>;
}
export interface FinanceResult { status: 'ok' | 'error'; code?: string; snapshot?: FinanceSnapshot; id?: string; transactionId?: string }
export interface FinanceTransaction {
  id: string; scope?: 'finance' | 'extended'; kind: 'income' | 'expense' | 'transfer' | 'reserve_move' | 'reserve_opening' | 'reserve_adjustment' | 'card_purchase' | 'commitment_opening' | 'card_payment'; amountCents: number; effectiveDate: string; description: string;
  categoryId: string | null; category: string; tagIds: string[]; tags: string[]; sourceEventId: string | null;
  sourceType?: 'manual' | 'notification' | 'statement' | 'card'; resourceId?: string | null; resourceName?: string | null;
  commitmentId?: string | null; invoiceMonth?: string | null; direction?: string | null;
  targetResourceId?: string | null; targetResourceName?: string | null;
  voided: boolean; cancellationReason: string | null;
}
export interface FinanceSnapshot {
  bookId?: string; configured: boolean; profile: { openingDate: string; openingBalanceCents: number } | null;
  balanceCents: number; month: string; incomeCents: number; expenseCents: number; total: number;
  byCategory: Array<{ categoryId: string | null; name: string; amountCents: number }>;
  transactions: FinanceTransaction[];
  assetsCents?: number; debtCents?: number; reservesCents?: number;
  reserves?: FinanceReserve[]; cards?: FinanceCard[]; commitments?: FinanceCommitment[];
  balanceCheck: { date: string; amountCents: number; calculatedCents: number; differenceCents: number } | null;
  recovery: LocalRecoveryStatus; previousAvailable: boolean; securityChecksPassed: boolean;
}
export interface FinanceReserve { id: string; name: string; modality: string; balanceCents: number; updatedOn: string }
export interface FinanceCard { id: string; name: string; closingDay: number; dueDay: number; debtCents: number }
export interface FinanceCommitment { id: string; description: string; dueDate: string; amountCents: number; paidCents: number; remainingCents: number; cardId: string | null; cardName: string | null; invoiceMonth: string | null }
export interface FinanceRule { id: string; description: string; categoryId: string | null; category: string; tagIds: string[]; enabled: boolean; createdAt: string; updatedAt: string }
export interface FinanceRuleListResult { status: 'ok' | 'error'; code?: string; rules?: FinanceRule[] }
export interface FinanceRuleMutationResult { status: 'ok' | 'error'; code?: string; rule?: FinanceRule }
export interface FinanceRulePreviewResult { status: 'ok' | 'error'; code?: string; matches?: FinanceTransaction[] }
export interface FinanceReclassificationResult { status: 'ok' | 'error'; code?: string; updated?: number; snapshot?: FinanceSnapshot }

/** Never look up a custom native module in Expo Go. Only the PoC screen calls this. */
export function captureModule(): CaptureModule | null {
  if (Constants.executionEnvironment === 'storeClient') return null;
  return requireOptionalNativeModule<CaptureModule>('FFortunaCapture');
}

export interface DiagnosticSource {
  installed: boolean; enabled: boolean; packageName: string; label: string;
  versionName: string | null; signer: string | null;
}
export interface DiagnosticStatus {
  status: 'absent' | 'ready' | 'blocked' | 'error'; mode?: 'daily' | 'diagnostic'; code?: string; active: boolean; securityChecksPassed: boolean;
  authorized: boolean; connected: boolean; source: DiagnosticSource;
  startedAt: string | null; expiresAt: string | null;
  count: number; reviewedCount: number; limitReached: boolean; failureCode: string | null; processingFailureCode?: string | null;
}
export interface StatementResult {
  status: 'ok' | 'error' | 'cancelled'; code?: string; count?: number; existing?: number;
  rowCount?: number; confirmed?: number; pending?: number; months?: string[];
}
export interface ObservedBatchItem {
  id: string; kind: 'income' | 'expense'; categoryId: string | null; category: string; tagIds: string[];
}
export interface ObservedBatchResult { status: 'ok' | 'error'; code?: string; registered?: number; months?: string[] }
export interface DailyState extends DiagnosticStatus {
  pendingCount: number; configured: boolean; incomingFile: boolean;
  imports: Array<{ id: string; format: string; capturedAt: string; count: number }>;
}
export interface DiagnosticFields { title: string; text: string; bigText: string; subText: string; textLines: string[] }
export interface DiagnosticSampleInfo {
  description?: string; amountCents?: number | null; effectiveDate?: string | null;
  mode?: string; origin?: 'notification' | 'statement';
  transactionId?: string | null;
  id: string; revision: number; publishedAt: string; capturedAt: string;
  isSummary: boolean; truncated: boolean; hasText: boolean; hasReview: boolean; candidateParsedAt?: string | null; candidateReviewStatus?: string | null;
}
export interface DiagnosticSample extends DiagnosticSampleInfo { original: DiagnosticFields; suggestion: DiagnosticFields }
export interface DiagnosticResult {
  status: 'ok' | 'error' | 'saved' | 'cancelled' | 'verified'; code?: string; count?: number;
  samples?: DiagnosticSampleInfo[]; sample?: DiagnosticSample; candidate?: NotificationCandidate;
}

export interface NotificationCandidateFields {
  amountCents: number | null; kind: 'income' | 'expense' | 'transfer' | 'other' | null;
  effectiveDate: string | null; description: string; category: string;
  categoryId?: string | null; tagIds?: string[];
}
export interface ClassificationItem { id: string; kind: 'category' | 'tag'; name: string; archived: boolean }
export interface ClassificationResult { status: 'ok' | 'error'; code?: string; items?: ClassificationItem[] }
export interface NotificationCandidateReview extends NotificationCandidateFields { status: 'reviewed' | 'dismissed' }
export interface NotificationCandidate {
  id: string; eventId: string; revision: number; publishedAt: string; capturedAt: string; parsedAt: string;
  parsed: {
    parserVersion: string; draft: NotificationCandidateFields;
    confidence: Record<'movement' | 'amount' | 'nature' | 'account' | 'date' | 'category', 'certain' | 'uncertain' | 'missing'>;
    fieldEvidence: Record<string, { value: string; sourceField: string; observedText: string; start: number; end: number }>;
    amountEvidence: Array<{ sourceField: string; start: number; end: number; observedText: string; amountCents: number | null }>;
    reasonCode: string; reason: string; automaticPostingAllowed: false;
  };
  original: DiagnosticFields; review: NotificationCandidateReview | null; reviewedAt: string | null;
  previousSampleId: string | null; relatedReviewExists: boolean; hasLaterRevision: boolean; temporary: boolean; transactionId: string | null;
  originalAvailable?: boolean; origin?: 'notification' | 'statement';
  possibleMatches?: Array<{ id: string; description: string; amountCents: number; effectiveDate: string; kind: FinanceTransaction['kind']; scope?: FinanceTransaction['scope'] }>;
  ruleExplanation?: { ruleId: string; description: string; text: string };
  financialReview?: NotificationCandidateFields | null;
}
