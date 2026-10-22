export type MovementKind = 'income' | 'expense' | 'transfer';
export type TransferDirection = 'deposit' | 'withdraw';
export type Confidence = 'certain' | 'uncertain' | 'missing';

/** Transport contract. Native capture will provide the same envelope, without a JS dependency. */
export interface ObservedEvent {
  id: string;
  source: 'simulation' | 'ofx';
  sourceKey: string;
  revision: number;
  publishedAt: string | null;
  capturedAt: string;
  payload: string;
}

export interface Draft {
  amountCents: number | null;
  kind: MovementKind | null;
  effectiveDate: string | null;
  effectiveAt: string | null;
  description: string;
  category: string;
  reserveId: string | null;
  direction: TransferDirection | null;
}

export interface ParseResult {
  parserVersion: string;
  draft: Draft;
  confidence: Record<'movement' | 'amount' | 'nature' | 'account' | 'date' | 'category', Confidence>;
  fieldEvidence: Record<string, { value: string; sourceField: string }>;
  externalId: string | null;
  reason: string;
}

export interface ReviewInput {
  amountCents: number;
  kind: MovementKind;
  effectiveDate: string;
  description: string;
  category: string;
  notes: string;
  reserveId: string | null;
  direction: TransferDirection | null;
}

export interface Candidate {
  id: string;
  eventId: string;
  sourceKey: string;
  revision: number;
  status: 'pending' | 'confirmed' | 'dismissed' | 'conflict' | 'linked';
  parsed: ParseResult;
  transactionId: string | null;
  reason: string;
}

export interface Profile {
  openingDate: string;
  openingBalanceCents: number;
  expectedIncomeCents: number;
}
export interface Reserve {
  id: string;
  name: string;
  modality: string;
  balanceCents: number;
  updatedOn: string;
}
export interface Commitment {
  id: string;
  description: string;
  dueDate: string;
  amountCents: number;
  paidCents: number;
  remainingCents: number;
}
export interface FinancialTransaction {
  id: string;
  kind: string;
  effectiveDate: string;
  description: string;
  category: string;
  amountCents: number;
  cashDeltaCents: number;
  notes: string;
  candidateId: string | null;
  sourceCount: number;
  createdAt: string;
}
export interface EvidenceDetail {
  event: ObservedEvent;
  parsed: ParseResult;
  decision: ReviewInput | null;
  transactionId: string | null;
}
export interface Snapshot {
  profile: Profile | null;
  month: string;
  balanceCents: number;
  reservesCents: number;
  assetsCents: number;
  debtCents: number;
  receivedCents: number;
  spentCents: number;
  categories: { name: string; amountCents: number }[];
  reserves: Reserve[];
  commitments: Commitment[];
  transactions: FinancialTransaction[];
  candidates: Candidate[];
}
export interface ProcessingResult {
  candidateId: string;
  status: Candidate['status'];
  transactionId: string | null;
}
export type DemoScenario = 'safe' | 'review' | 'same-purchase';

export interface OfxRow {
  externalId: string;
  effectiveDate: string;
  signedAmountCents: number;
  description: string;
}
export interface ParsedOfx {
  institution: string;
  account: string;
  currency: 'BRL';
  rows: OfxRow[];
}
export interface ImportRow {
  eventId: string;
  externalId: string;
  draft: Draft;
  status: 'new' | 'linked' | 'ambiguous';
  matchingTransactionIds: string[];
  transactionId: string | null;
}
export interface ImportPreview {
  id: string;
  name: string;
  rows: ImportRow[];
  requiresOpening: boolean;
  earliestDate: string;
  warnings: string[];
}
export interface ImportDecision {
  eventId: string;
  review: ReviewInput | null;
  linkTransactionId: string | null;
  skip: boolean;
}
export interface ConfirmImportInput {
  operationId: string;
  previewId: string;
  decisions: ImportDecision[];
  opening: { date: string; balanceCents: number; acknowledged: boolean } | null;
}

export interface FinanceService {
  snapshot(month?: string): Promise<Snapshot>;
  setup(input: { operationId: string; openingDate: string; balanceCents: number; expectedIncomeCents: number }): Promise<void>;
  setExpectedIncome(amountCents: number): Promise<void>;
  addManual(input: ReviewInput & { operationId: string }): Promise<string>;
  createReserve(input: { operationId: string; name: string; modality: string; date: string; amountCents: number }): Promise<void>;
  moveReserve(input: { operationId: string; reserveId: string; direction: TransferDirection; amountCents: number; date: string }): Promise<void>;
  updateReserve(input: { operationId: string; reserveId: string; amountCents: number; date: string; note: string }): Promise<void>;
  addCommitment(input: { operationId: string; description: string; dueDate: string; amountCents: number }): Promise<void>;
  payCommitment(input: { operationId: string; commitmentId: string; amountCents: number; date: string }): Promise<void>;
  observe(event: ObservedEvent): Promise<ProcessingResult>;
  reprocess(eventId: string): Promise<ProcessingResult>;
  confirmCandidate(candidateId: string, review: ReviewInput): Promise<string>;
  dismissCandidate(candidateId: string): Promise<void>;
  evidence(candidateId: string): Promise<EvidenceDetail>;
  stageDemoImport(variant: 'current' | 'overlap' | 'history'): Promise<ImportPreview>;
  confirmImport(input: ConfirmImportInput): Promise<{ created: number; linked: number }>;
}
