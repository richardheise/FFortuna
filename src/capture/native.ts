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
  status: 'absent' | 'ready' | 'blocked'; active: boolean; securityChecksPassed: boolean;
  authorized: boolean; connected: boolean; source: DiagnosticSource;
  startedAt: string | null; expiresAt: string | null;
  count: number; reviewedCount: number; limitReached: boolean; failureCode: string | null; processingFailureCode?: string | null;
}
export interface DiagnosticFields { title: string; text: string; bigText: string; subText: string; textLines: string[] }
export interface DiagnosticSampleInfo {
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
}
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
  previousSampleId: string | null; relatedReviewExists: boolean; hasLaterRevision: boolean; temporary: true; transactionId: null;
}
