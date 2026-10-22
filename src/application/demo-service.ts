import { getDemoOfx } from '../demo/scenarios';
import type { Database, SqlSession } from '../persistence/database';
import { migrate } from '../persistence/migrations';
import { parseOfx } from '../processing/ofx';
import { parseEvent } from '../processing/parser';
import { validDate } from '../core/dates';
import { addCents, cents } from '../core/money';
import type {
  Candidate, Commitment, ConfirmImportInput, Draft, EvidenceDetail, FinanceService,
  FinancialTransaction, ImportPreview, ImportRow, ObservedEvent, ParseResult,
  ProcessingResult, Profile, Reserve, ReviewInput, Snapshot,
} from '../core/types';
export type { FinanceService } from '../core/types';

export interface Environment { newId: () => string; now: () => string; today: () => string }
type CandidateRow = {
  id: string; event_id: string; source_key: string; revision: number; status: Candidate['status'];
  parsed_json: string; transaction_id: string | null; decision_json: string | null; reason: string;
};
type EventRow = {
  id: string; source: ObservedEvent['source']; source_key: string; revision: number;
  published_at: string | null; captured_at: string; payload: string;
};
type SourceRow = { identity: string; identity_json: string; candidate_id: string; transaction_id: string | null };
type Posting = { account: string; amount: number };
type PostInput = {
  operationId: string; kind: string; date: string; description: string; category: string;
  amount: number; notes?: string; candidateId?: string; postings: Posting[];
};

function text(value: string, label: string, max = 180): string {
  if (typeof value !== 'string' || !value.trim() || value.trim().length > max) {
    throw new Error(`${label}: preencha até ${max} caracteres.`);
  }
  return value.trim();
}
function positive(value: number): number {
  cents(value, true);
  if (value === 0) throw new Error('Informe um valor maior que zero.');
  return value;
}
function stable(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
      .map(([key, item]) => `${JSON.stringify(key)}:${stable(item)}`).join(',')}}`;
  }
  return JSON.stringify(value) ?? 'null';
}
function candidate(row: CandidateRow): Candidate {
  return {
    id: row.id, eventId: row.event_id, sourceKey: row.source_key, revision: row.revision,
    status: row.status, parsed: JSON.parse(row.parsed_json) as ParseResult,
    transactionId: row.transaction_id, reason: row.reason,
  };
}
function event(row: EventRow): ObservedEvent {
  return { id: row.id, source: row.source, sourceKey: row.source_key, revision: row.revision,
    publishedAt: row.published_at, capturedAt: row.captured_at, payload: row.payload };
}

/** Synthetic adapter only. The real app will implement this contract in Kotlin. */
class DemoFinanceService implements FinanceService {
  private tail: Promise<unknown> = Promise.resolve();
  constructor(private readonly db: Database, private readonly env: Environment) {}

  private queue<T>(work: () => Promise<T>): Promise<T> {
    const result = this.tail.then(work);
    this.tail = result.then(() => undefined, () => undefined);
    return result;
  }
  private write<T>(work: (tx: SqlSession) => Promise<T>): Promise<T> {
    return this.queue(() => this.db.transaction(work));
  }
  private command<T>(id: string, request: unknown, work: (tx: SqlSession) => Promise<T>): Promise<T> {
    text(id, 'Identificador da operação');
    const signature = stable(request);
    return this.write(async (tx) => {
      const prior = await tx.get<{ request_json: string; result_json: string }>('SELECT * FROM commands WHERE id = ?', id);
      if (prior) {
        if (prior.request_json !== signature) throw new Error('Essa operação já foi usada com outros dados. Atualize a tela.');
        return JSON.parse(prior.result_json) as T;
      }
      const result = await work(tx);
      await tx.run('INSERT INTO commands VALUES (?,?,?)', id, signature, JSON.stringify(result ?? null));
      return result;
    });
  }
  private async profile(tx: SqlSession): Promise<Profile> {
    const row = await tx.get<{ value: string }>("SELECT value FROM settings WHERE key = 'profile'");
    if (!row) throw new Error('Crie primeiro sua conta fictícia.');
    return JSON.parse(row.value) as Profile;
  }
  private async saveProfile(tx: SqlSession, profile: Profile): Promise<void> {
    await tx.run("INSERT INTO settings VALUES ('profile',?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", JSON.stringify(profile));
  }
  private async balance(tx: SqlSession, accountId: string): Promise<number> {
    const row = await tx.get<{ amount: number }>(`SELECT COALESCE(SUM(p.amount_cents),0) amount
      FROM postings p JOIN transactions t ON t.id = p.transaction_id WHERE t.posted = 1 AND p.account_id = ?`, accountId);
    return cents(row?.amount ?? 0);
  }
  private async assets(tx: SqlSession): Promise<number> {
    const rows = await tx.all<{ id: string }>("SELECT id FROM accounts WHERE kind IN ('cash','reserve') ORDER BY id");
    const values: number[] = [];
    for (const row of rows) values.push(await this.balance(tx, row.id));
    return addCents(...values);
  }
  private checkDate(date: string, openingDate?: string): string {
    validDate(date);
    if (date > this.env.today()) throw new Error('Uma transação realizada não pode ter data futura. Use compromissos para o que falta pagar.');
    if (openingDate && date < openingDate) throw new Error('Data anterior à abertura. Use a importação com revisão do saldo inicial.');
    return date;
  }
  private review(input: ReviewInput): ReviewInput {
    positive(input.amountCents);
    validDate(input.effectiveDate);
    if (!['income', 'expense', 'transfer'].includes(input.kind)) throw new Error('Selecione a natureza financeira.');
    if (input.kind === 'transfer' && (!input.reserveId || !['deposit', 'withdraw'].includes(input.direction ?? ''))) {
      throw new Error('Informe a caixinha e se é aporte ou resgate.');
    }
    if (input.kind !== 'transfer' && (input.reserveId !== null || input.direction !== null)) {
      throw new Error('Conta de destino só se aplica a transferências.');
    }
    if (typeof input.notes !== 'string' || input.notes.length > 500) throw new Error('Notas: use até 500 caracteres.');
    return { ...input, description: text(input.description, 'Descrição'),
      category: input.kind === 'transfer' ? 'Transferência' : text(input.category || 'A classificar', 'Categoria', 80), notes: input.notes.trim() };
  }
  private async reserve(tx: SqlSession, id: string): Promise<{ id: string; name: string; updated_on: string }> {
    const row = await tx.get<{ id: string; name: string; updated_on: string }>("SELECT * FROM accounts WHERE id = ? AND kind = 'reserve'", id);
    if (!row) throw new Error('Caixinha não encontrada.');
    return row;
  }
  private async post(tx: SqlSession, input: PostInput): Promise<string> {
    cents(input.amount, true);
    if (input.postings.length < 2 || addCents(...input.postings.map((p) => p.amount)) !== 0) {
      throw new Error('Lançamento desequilibrado. Nenhum dado foi salvo.');
    }
    for (const posting of input.postings) {
      cents(posting.amount);
      const exists = await tx.get('SELECT id FROM accounts WHERE id = ?', posting.account);
      if (!exists) throw new Error('Conta da contrapartida não encontrada.');
    }
    const byAccount = new Map<string, number>();
    for (const posting of input.postings) byAccount.set(posting.account, addCents(byAccount.get(posting.account) ?? 0, posting.amount));
    for (const [id, delta] of byAccount) addCents(await this.balance(tx, id), delta);
    const id = this.env.newId();
    await tx.run(`INSERT INTO transactions
      (id,operation_id,kind,effective_date,description,category,amount_cents,notes,candidate_id,created_at)
      VALUES (?,?,?,?,?,?,?,?,?,?)`, id, input.operationId, input.kind, input.date, input.description,
    input.category, input.amount, input.notes ?? '', input.candidateId ?? null, this.env.now());
    for (const posting of input.postings) await tx.run('INSERT INTO postings (transaction_id,account_id,amount_cents) VALUES (?,?,?)', id, posting.account, posting.amount);
    await tx.run('UPDATE transactions SET posted = 1 WHERE id = ?', id);
    await this.assets(tx); // Reject aggregate overflow inside the same transaction, before commit.
    return id;
  }
  private async postReview(tx: SqlSession, input: ReviewInput, operationId: string, candidateId?: string): Promise<string> {
    const review = this.review(input);
    const profile = await this.profile(tx);
    this.checkDate(review.effectiveDate, profile.openingDate);
    let postings: Posting[];
    if (review.kind === 'transfer') {
      const reserveId = review.reserveId!;
      const reserve = await this.reserve(tx, reserveId);
      if (review.effectiveDate < reserve.updated_on) throw new Error('Movimentação anterior à última posição desta caixinha. Confira a referência.');
      const from = review.direction === 'deposit' ? 'cash' : reserveId;
      const to = review.direction === 'deposit' ? reserveId : 'cash';
      if (await this.balance(tx, from) < review.amountCents) throw new Error('Saldo insuficiente para essa transferência.');
      postings = [{ account: from, amount: -review.amountCents }, { account: to, amount: review.amountCents }];
      await tx.run('UPDATE accounts SET updated_on = ? WHERE id = ?', review.effectiveDate, reserveId);
    } else {
      const cash = review.kind === 'income' ? review.amountCents : -review.amountCents;
      postings = [{ account: 'cash', amount: cash }, { account: review.kind, amount: -cash }];
    }
    return this.post(tx, { operationId, kind: review.kind, date: review.effectiveDate, description: review.description,
      category: review.category, amount: review.amountCents, notes: review.notes, ...(candidateId ? { candidateId } : {}), postings });
  }

  async setup(input: { operationId: string; openingDate: string; balanceCents: number; expectedIncomeCents: number }): Promise<void> {
    await this.command(input.operationId, { action: 'setup', ...input }, async (tx) => {
      if (await tx.get("SELECT key FROM settings WHERE key = 'profile'")) throw new Error('A conta fictícia já foi criada.');
      this.checkDate(input.openingDate);
      cents(input.balanceCents); cents(input.expectedIncomeCents, true);
      for (const [id, name, kind] of [
        ['cash', 'Conta fictícia em BRL', 'cash'], ['income', 'Receitas', 'income'], ['expense', 'Despesas', 'expense'],
        ['equity', 'Abertura e ajustes de posição', 'equity'], ['card', 'Cartão fictício', 'liability'],
      ]) await tx.run('INSERT INTO accounts (id,name,kind,updated_on) VALUES (?,?,?,?)', id!, name!, kind!, input.openingDate);
      await this.saveProfile(tx, { openingDate: input.openingDate, openingBalanceCents: input.balanceCents, expectedIncomeCents: input.expectedIncomeCents });
      await this.post(tx, { operationId: input.operationId, kind: 'opening', date: input.openingDate, description: 'Saldo de abertura informado', category: 'Posição inicial',
        amount: Math.abs(input.balanceCents), postings: [{ account: 'cash', amount: input.balanceCents }, { account: 'equity', amount: -input.balanceCents }] });
    });
  }
  async setExpectedIncome(amountCents: number): Promise<void> {
    cents(amountCents, true);
    await this.write(async (tx) => { await this.saveProfile(tx, { ...await this.profile(tx), expectedIncomeCents: amountCents }); });
  }
  addManual(input: ReviewInput & { operationId: string }): Promise<string> {
    return this.command(input.operationId, { action: 'manual', ...input }, (tx) => this.postReview(tx, input, input.operationId));
  }
  async createReserve(input: { operationId: string; name: string; modality: string; date: string; amountCents: number }): Promise<void> {
    await this.command(input.operationId, { action: 'reserve-opening', ...input }, async (tx) => {
      const profile = await this.profile(tx);
      this.checkDate(input.date, profile.openingDate); cents(input.amountCents, true);
      const name = text(input.name, 'Nome da caixinha', 80);
      const modality = text(input.modality, 'Modalidade', 80);
      const id = `reserve:${this.env.newId()}`;
      await tx.run("INSERT INTO accounts (id,name,kind,modality,updated_on) VALUES (?,?,'reserve',?,?)", id, name, modality, input.date);
      await this.post(tx, { operationId: input.operationId, kind: 'reserve_opening', date: input.date, description: `Posição inicial: ${name}`, category: 'Posição inicial',
        amount: input.amountCents, postings: [{ account: id, amount: input.amountCents }, { account: 'equity', amount: -input.amountCents }] });
    });
  }
  async moveReserve(input: { operationId: string; reserveId: string; direction: 'deposit' | 'withdraw'; amountCents: number; date: string }): Promise<void> {
    await this.command(input.operationId, { action: 'reserve-transfer', ...input }, async (tx) => {
      const reserve = await this.reserve(tx, input.reserveId);
      await this.postReview(tx, { amountCents: input.amountCents, kind: 'transfer', effectiveDate: input.date,
        description: `${input.direction === 'deposit' ? 'Guardar em' : 'Resgatar de'} ${reserve.name}`,
        category: 'Transferência', notes: '', reserveId: input.reserveId, direction: input.direction }, input.operationId);
    });
  }
  async updateReserve(input: { operationId: string; reserveId: string; amountCents: number; date: string; note: string }): Promise<void> {
    await this.command(input.operationId, { action: 'reserve-position', ...input }, async (tx) => {
      const profile = await this.profile(tx);
      this.checkDate(input.date, profile.openingDate); cents(input.amountCents, true);
      const reserve = await this.reserve(tx, input.reserveId);
      if (input.date < reserve.updated_on) throw new Error('A referência não pode ser anterior à última posição conhecida.');
      const delta = addCents(input.amountCents, -await this.balance(tx, reserve.id));
      await this.post(tx, { operationId: input.operationId, kind: 'adjustment', date: input.date,
        description: `Ajuste explícito: ${reserve.name}`, category: 'Ajuste de posição', amount: Math.abs(delta),
        notes: text(input.note, 'Motivo do ajuste', 500), postings: [{ account: reserve.id, amount: delta }, { account: 'equity', amount: -delta }] });
      await tx.run('UPDATE accounts SET updated_on = ? WHERE id = ?', input.date, reserve.id);
    });
  }
  async addCommitment(input: { operationId: string; description: string; dueDate: string; amountCents: number }): Promise<void> {
    await this.command(input.operationId, { action: 'commitment-opening', ...input }, async (tx) => {
      const profile = await this.profile(tx);
      validDate(input.dueDate); positive(input.amountCents);
      const description = text(input.description, 'Descrição do compromisso');
      const id = this.env.newId();
      await tx.run('INSERT INTO commitments VALUES (?,?,?,?)', id, description, input.dueDate, input.amountCents);
      await this.post(tx, { operationId: input.operationId, kind: 'debt_opening', date: profile.openingDate,
        description: `Crédito já comprometido: ${description}`, category: 'Dívida inicial', amount: input.amountCents,
        postings: [{ account: 'card', amount: -input.amountCents }, { account: 'equity', amount: input.amountCents }] });
    });
  }
  async payCommitment(input: { operationId: string; commitmentId: string; amountCents: number; date: string }): Promise<void> {
    await this.command(input.operationId, { action: 'commitment-payment', ...input }, async (tx) => {
      const profile = await this.profile(tx);
      this.checkDate(input.date, profile.openingDate); positive(input.amountCents);
      const debt = await tx.get<{ description: string; amount_cents: number }>('SELECT * FROM commitments WHERE id = ?', input.commitmentId);
      if (!debt) throw new Error('Compromisso não encontrado.');
      const paid = await tx.get<{ amount: number }>('SELECT COALESCE(SUM(amount_cents),0) amount FROM commitment_payments WHERE commitment_id = ?', input.commitmentId);
      if (input.amountCents > addCents(debt.amount_cents, -(paid?.amount ?? 0))) throw new Error('Pagamento maior que o compromisso restante.');
      const id = await this.post(tx, { operationId: input.operationId, kind: 'card_payment', date: input.date,
        description: `Pagamento: ${debt.description}`, category: 'Pagamento do cartão', amount: input.amountCents,
        postings: [{ account: 'cash', amount: -input.amountCents }, { account: 'card', amount: input.amountCents }] });
      await tx.run('INSERT INTO commitment_payments VALUES (?,?,?)', id, input.commitmentId, input.amountCents);
    });
  }

  private identity(parsed: ParseResult): string | null {
    return parsed.externalId && parsed.confidence.account === 'certain' ? `DEMO:cash:${parsed.externalId}` : null;
  }
  private identityData(parsed: ParseResult): string {
    return stable({ amount: parsed.draft.amountCents, date: parsed.draft.effectiveDate });
  }
  private async candidateRow(tx: SqlSession, id: string): Promise<CandidateRow> {
    const row = await tx.get<CandidateRow>('SELECT * FROM candidates WHERE id = ?', id);
    if (!row) throw new Error('Candidato não encontrado.');
    return row;
  }
  private async link(tx: SqlSession, row: CandidateRow, transactionId: string, review: ReviewInput | null): Promise<void> {
    await tx.run("UPDATE candidates SET transaction_id = ?, status = 'linked', decision_json = ?, reason = ? WHERE id = ?",
      transactionId, review ? JSON.stringify(review) : row.decision_json, 'Evidência vinculada a um lançamento existente; saldo preservado.', row.id);
    await tx.run('INSERT INTO evidence_links VALUES (?,?) ON CONFLICT(event_id) DO NOTHING', row.event_id, transactionId);
  }
  private async confirm(tx: SqlSession, id: string, input: ReviewInput): Promise<string> {
    const row = await this.candidateRow(tx, id);
    const review = this.review(input);
    if (row.transaction_id) {
      if (row.status === 'conflict') throw new Error('Uma atualização contradiz o lançamento. Mantenha a decisão anterior; não confirme outra transação.');
      if (row.decision_json && stable(JSON.parse(row.decision_json)) !== stable(review)) {
        throw new Error('O lançamento já foi confirmado com outros dados. A decisão anterior foi preservada.');
      }
      return row.transaction_id;
    }
    if (row.status === 'dismissed' || row.status === 'conflict') throw new Error('Este candidato foi descartado ou possui um conflito de origem.');
    const parsed = JSON.parse(row.parsed_json) as ParseResult;
    const identity = this.identity(parsed);
    const source = identity ? await tx.get<SourceRow>('SELECT * FROM financial_sources WHERE identity = ?', identity) : null;
    if (source?.transaction_id) {
      await this.link(tx, row, source.transaction_id, review);
      return source.transaction_id;
    }
    const transactionId = await this.postReview(tx, review, `candidate:${row.id}`, row.id);
    await tx.run("UPDATE candidates SET status = 'confirmed', transaction_id = ?, decision_json = ?, reason = ? WHERE id = ?",
      transactionId, JSON.stringify(review), 'Confirmado; evidência e decisões preservadas.', row.id);
    await tx.run('INSERT INTO evidence_links VALUES (?,?)', row.event_id, transactionId);
    if (identity) await tx.run('UPDATE financial_sources SET transaction_id = ? WHERE identity = ?', transactionId, identity);
    return transactionId;
  }
  private async persistObservation(tx: SqlSession, input: ObservedEvent): Promise<ObservedEvent> {
    await this.profile(tx);
    text(input.id, 'Identificador do evento'); text(input.sourceKey, 'Identidade da observação');
    if (!['simulation', 'ofx'].includes(input.source) || !Number.isSafeInteger(input.revision) || input.revision < 1) throw new Error('Fonte ou revisão inválida.');
    if (typeof input.payload !== 'string' || input.payload.length > 65536) throw new Error('Evidência muito grande.');
    if (!Number.isFinite(Date.parse(input.capturedAt)) || (input.publishedAt !== null && !Number.isFinite(Date.parse(input.publishedAt)))) throw new Error('Horário da observação inválido.');
    const sameId = await tx.get<EventRow>('SELECT * FROM events WHERE id = ?', input.id);
    if (sameId && stable(event(sameId)) !== stable(input)) throw new Error('Identificador local reutilizado para outro conteúdo.');
    const sameRevision = await tx.get<EventRow>('SELECT * FROM events WHERE source = ? AND source_key = ? AND revision = ?', input.source, input.sourceKey, input.revision);
    if (sameRevision && sameRevision.payload !== input.payload) throw new Error('Conteúdo alterado sem uma nova revisão.');
    const observed = sameRevision ? event(sameRevision) : input;
    if (!sameRevision) await tx.run('INSERT INTO events VALUES (?,?,?,?,?,?,?)', input.id, input.source, input.sourceKey, input.revision, input.publishedAt, input.capturedAt, input.payload);
    return observed;
  }
  private async process(tx: SqlSession, input: ObservedEvent, auto: boolean): Promise<ProcessingResult> {
    const observed = await this.persistObservation(tx, input);
    const parsed = parseEvent(observed);
    await tx.run('INSERT INTO parses VALUES (?,?,?) ON CONFLICT(event_id,parser_version) DO NOTHING', observed.id, parsed.parserVersion, JSON.stringify(parsed));
    let existing = await tx.get<CandidateRow>('SELECT * FROM candidates WHERE source = ? AND source_key = ?', input.source, input.sourceKey);
    if (existing && observed.revision < existing.revision) return { candidateId: existing.id, status: existing.status, transactionId: existing.transaction_id };
    if (existing && observed.revision === existing.revision && existing.status !== 'pending' &&
      (JSON.parse(existing.parsed_json) as ParseResult).parserVersion === parsed.parserVersion) {
      return { candidateId: existing.id, status: existing.status, transactionId: existing.transaction_id };
    }
    const old = existing;
    const id = existing?.id ?? this.env.newId();
    if (!existing) {
      await tx.run(`INSERT INTO candidates (id,event_id,source,source_key,revision,status,parsed_json,reason)
        VALUES (?,?,?,?,?,'pending',?,?)`, id, observed.id, input.source, input.sourceKey, observed.revision, JSON.stringify(parsed), parsed.reason);
    } else {
      await tx.run('UPDATE candidates SET event_id = ?, revision = ?, parsed_json = ?, reason = ? WHERE id = ?', observed.id, observed.revision, JSON.stringify(parsed), parsed.reason, id);
    }
    existing = await this.candidateRow(tx, id);
    const identity = this.identity(parsed);
    const source = identity ? await tx.get<SourceRow>('SELECT * FROM financial_sources WHERE identity = ?', identity) : null;
    if (source && source.candidate_id === id && !source.transaction_id && !old?.transaction_id) {
      source.identity_json = this.identityData(parsed);
      await tx.run('UPDATE financial_sources SET identity_json = ? WHERE identity = ?', source.identity_json, source.identity);
    }
    let conflictReason: string | null = null;
    if (old?.transaction_id) {
      const previous = JSON.parse(old.parsed_json) as ParseResult;
      if (stable(previous.draft) !== stable(parsed.draft) || this.identity(previous) !== this.identity(parsed)) conflictReason = 'Notificação atualizada contradiz uma decisão já confirmada. O lançamento foi mantido.';
      else {
        await tx.run('INSERT INTO evidence_links VALUES (?,?) ON CONFLICT(event_id) DO NOTHING', observed.id, old.transaction_id);
        return { candidateId: id, status: old.status, transactionId: old.transaction_id };
      }
    } else if (source && source.identity_json !== this.identityData(parsed)) {
      conflictReason = 'A mesma identidade de origem apareceu com outros valores ou data. Nenhuma nova transação foi lançada.';
    }
    if (conflictReason) {
      const transactionId = old?.transaction_id ?? source?.transaction_id ?? null;
      await tx.run("UPDATE candidates SET status = 'conflict', transaction_id = ?, reason = ? WHERE id = ?", transactionId, conflictReason, id);
      await tx.run('INSERT INTO conflicts (candidate_id,event_id,reason) VALUES (?,?,?) ON CONFLICT(candidate_id,event_id) DO NOTHING', id, observed.id, conflictReason);
      if (transactionId) await tx.run('INSERT INTO evidence_links VALUES (?,?) ON CONFLICT(event_id) DO NOTHING', observed.id, transactionId);
      return { candidateId: id, status: 'conflict', transactionId };
    }
    if (identity && !source) await tx.run('INSERT INTO financial_sources (identity,identity_json,candidate_id) VALUES (?,?,?)', identity, this.identityData(parsed), id);
    if (source?.transaction_id) {
      await this.link(tx, existing, source.transaction_id, null);
      return { candidateId: id, status: 'linked', transactionId: source.transaction_id };
    }
    if (old?.status === 'dismissed') {
      await tx.run("UPDATE candidates SET status = 'pending', reason = ? WHERE id = ?", 'Nova revisão de uma observação descartada; requer nova revisão humana.', id);
      return { candidateId: id, status: 'pending', transactionId: null };
    }
    await tx.run("UPDATE candidates SET status = 'pending' WHERE id = ?", id);
    const safe = ['movement', 'amount', 'nature', 'account', 'date'].every((key) => parsed.confidence[key as keyof ParseResult['confidence']] === 'certain');
    if (auto && observed.source === 'simulation' && safe && parsed.draft.kind && parsed.draft.amountCents && parsed.draft.effectiveDate) {
      const profile = await this.profile(tx);
      if (parsed.draft.effectiveDate < profile.openingDate || parsed.draft.effectiveDate > this.env.today()) {
        await tx.run('UPDATE candidates SET reason = ? WHERE id = ?', 'A data exige revisão da abertura ou não é uma movimentação realizada.', id);
        return { candidateId: id, status: 'pending', transactionId: null };
      }
      const transactionId = await this.confirm(tx, id, {
        amountCents: parsed.draft.amountCents, kind: parsed.draft.kind, effectiveDate: parsed.draft.effectiveDate,
        description: parsed.draft.description, category: parsed.draft.category || 'A classificar', notes: '', reserveId: parsed.draft.reserveId, direction: parsed.draft.direction,
      });
      await tx.run('UPDATE candidates SET reason = ? WHERE id = ?', 'Automático: fixture sintética com valor, natureza, conta e data seguros. Categoria incerta não impede o lançamento.', id);
      return { candidateId: id, status: 'confirmed', transactionId };
    }
    return { candidateId: id, status: 'pending', transactionId: null };
  }
  async observe(input: ObservedEvent): Promise<ProcessingResult> {
    // Evidence is committed before parsing/posting; a financial failure cannot erase the observation.
    const observed = await this.write((tx) => this.persistObservation(tx, input));
    return this.write((tx) => this.process(tx, observed, true));
  }
  reprocess(eventId: string): Promise<ProcessingResult> {
    return this.write(async (tx) => {
      const row = await tx.get<EventRow>('SELECT * FROM events WHERE id = ?', eventId);
      if (!row) throw new Error('Evidência não encontrada.');
      return this.process(tx, event(row), true);
    });
  }
  confirmCandidate(id: string, review: ReviewInput): Promise<string> { return this.write((tx) => this.confirm(tx, id, review)); }
  dismissCandidate(id: string): Promise<void> {
    return this.write(async (tx) => {
      const row = await this.candidateRow(tx, id);
      if (row.status === 'confirmed' || row.status === 'linked') throw new Error('Um lançamento confirmado não pode ser descartado como uma observação.');
      await tx.run('UPDATE candidates SET status = ?, reason = ? WHERE id = ?', row.transaction_id ? 'confirmed' : 'dismissed', row.transaction_id ? 'Conflito revisado: decisão anterior mantida.' : 'Descartado pelo usuário; nenhum lançamento criado.', id);
      await tx.run('UPDATE conflicts SET resolved = 1 WHERE candidate_id = ?', id);
    });
  }
  evidence(id: string): Promise<EvidenceDetail> {
    return this.queue(async () => {
      const row = await this.candidateRow(this.db, id);
      const original = await this.db.get<EventRow>('SELECT * FROM events WHERE id = ?', row.event_id);
      if (!original) throw new Error('Evidência indisponível.');
      return { event: event(original), parsed: JSON.parse(row.parsed_json) as ParseResult,
        decision: row.decision_json ? JSON.parse(row.decision_json) as ReviewInput : null, transactionId: row.transaction_id };
    });
  }

  stageDemoImport(variant: 'current' | 'overlap' | 'history'): Promise<ImportPreview> {
    return this.write(async (tx) => {
      const profile = await this.profile(tx);
      const fixture = getDemoOfx(variant, this.env.today());
      const parsed = parseOfx(fixture.text);
      if (parsed.institution !== 'DEMO' || parsed.account !== 'FICTICIA') throw new Error('A demonstração aceita apenas o arquivo fictício fornecido pelo projeto.');
      const id = this.env.newId();
      const rows: ImportRow[] = [];
      for (const row of parsed.rows) {
        const observed: ObservedEvent = { id: this.env.newId(), source: 'ofx', sourceKey: `ofx:${parsed.institution}:${parsed.account}:${row.externalId}`, revision: 1,
          publishedAt: null, capturedAt: this.env.now(), payload: JSON.stringify({ marker: 'ffortuna.ofx-row.v1', institution: parsed.institution, account: parsed.account, ...row }) };
        const processed = await this.process(tx, observed, false);
        const current = await this.candidateRow(tx, processed.candidateId);
        const draft = (JSON.parse(current.parsed_json) as ParseResult).draft;
        const matches = current.transaction_id ? [] : await tx.all<{ id: string }>(`SELECT t.id FROM transactions t
          JOIN postings p ON p.transaction_id = t.id WHERE t.posted = 1 AND p.account_id = 'cash'
          AND t.effective_date = ? AND p.amount_cents = ? AND t.kind IN ('income','expense','transfer','card_payment')`, row.effectiveDate, row.signedAmountCents);
        rows.push({ eventId: current.event_id, externalId: row.externalId, draft,
          status: current.transaction_id ? 'linked' : matches.length || current.status === 'conflict' ? 'ambiguous' : 'new',
          matchingTransactionIds: matches.map((match) => match.id), transactionId: current.transaction_id });
      }
      if (!rows.length) throw new Error('O arquivo não contém movimentações.');
      const earliestDate = rows.map((row) => row.draft.effectiveDate!).sort()[0]!;
      await tx.run('INSERT INTO import_previews VALUES (?,?,?,?,?)', id, fixture.name, fixture.text, JSON.stringify(rows.map((row) => row.eventId)), this.env.now());
      return { id, name: fixture.name, rows, earliestDate, requiresOpening: earliestDate < profile.openingDate,
        warnings: ['Arquivo fictício. Não comprova suporte ao layout Nubank.', 'O sinal do valor não comprova receita ou despesa: confirme a natureza.', ...(earliestDate < profile.openingDate ? ['Reveja a abertura do período; a posição atual será usada como conferência.'] : [])] };
    });
  }
  confirmImport(input: ConfirmImportInput): Promise<{ created: number; linked: number }> {
    return this.command(input.operationId, { action: 'import', ...input }, async (tx) => {
      const preview = await tx.get<{ event_ids_json: string }>('SELECT * FROM import_previews WHERE id = ?', input.previewId);
      if (!preview) throw new Error('Prévia não encontrada. Carregue novamente a fixture.');
      const expected = JSON.parse(preview.event_ids_json) as string[];
      if (new Set(input.decisions.map((d) => d.eventId)).size !== input.decisions.length ||
        input.decisions.length !== expected.length || input.decisions.some((d) => !expected.includes(d.eventId))) throw new Error('A decisão precisa contemplar cada linha da prévia uma vez.');
      const profile = await this.profile(tx);
      const selected: { row: CandidateRow; review: ReviewInput; link: string | null }[] = [];
      for (const decision of input.decisions) {
        const row = await tx.get<CandidateRow>('SELECT * FROM candidates WHERE event_id = ?', decision.eventId);
        if (!row) throw new Error('A prévia ficou desatualizada. Carregue novamente.');
        if (decision.skip || row.transaction_id) continue;
        if (row.status === 'conflict' || row.status === 'dismissed') throw new Error('Resolva o conflito ou descarte a linha na prévia.');
        if (!decision.review) throw new Error('Confirme a natureza financeira de cada linha nova.');
        const review = this.review(decision.review);
        if (decision.linkTransactionId) {
          const target = await tx.get<{ id: string; effective_date: string; delta: number }>(`SELECT t.id,t.effective_date,p.amount_cents delta FROM transactions t
            JOIN postings p ON p.transaction_id = t.id WHERE t.id = ? AND t.posted = 1 AND p.account_id = 'cash'`, decision.linkTransactionId);
          const cash = review.kind === 'income' || (review.kind === 'transfer' && review.direction === 'withdraw') ? review.amountCents : -review.amountCents;
          if (!target || target.effective_date !== review.effectiveDate || target.delta !== cash) throw new Error('A correspondência escolhida tem data ou valor diferente.');
        }
        selected.push({ row, review, link: decision.linkTransactionId });
      }
      const historical = selected.filter((item) => !item.link && item.review.effectiveDate < profile.openingDate);
      if (historical.length) {
        const opening = input.opening;
        if (!opening?.acknowledged) throw new Error('Revise e confirme explicitamente a abertura do período.');
        this.checkDate(opening.date); cents(opening.balanceCents);
        const earliest = historical.map((item) => item.review.effectiveDate).sort()[0]!;
        if (opening.date > earliest || opening.date >= profile.openingDate) throw new Error('A nova abertura precisa ser anterior ou igual ao primeiro movimento histórico.');
        const effects = historical.map(({ review }) => review.kind === 'income' || (review.kind === 'transfer' && review.direction === 'withdraw') ? review.amountCents : -review.amountCents);
        const calculated = addCents(opening.balanceCents, ...effects);
        if (calculated !== profile.openingBalanceCents) throw new Error('O saldo histórico não confere com a abertura já informada. Nenhum lançamento foi importado; confira valores e período.');
        await this.post(tx, { operationId: `${input.operationId}:reverse-opening`, kind: 'opening_revision', date: profile.openingDate,
          description: 'Revisão explícita da abertura anterior', category: 'Posição inicial', amount: Math.abs(profile.openingBalanceCents),
          postings: [{ account: 'cash', amount: -profile.openingBalanceCents }, { account: 'equity', amount: profile.openingBalanceCents }] });
        await this.post(tx, { operationId: `${input.operationId}:new-opening`, kind: 'opening', date: opening.date,
          description: 'Abertura histórica conferida pelo usuário', category: 'Posição inicial', amount: Math.abs(opening.balanceCents),
          postings: [{ account: 'cash', amount: opening.balanceCents }, { account: 'equity', amount: -opening.balanceCents }] });
        await tx.run('INSERT INTO opening_revisions VALUES (?,?,?,?,?,?)', this.env.newId(), profile.openingDate, profile.openingBalanceCents, opening.date, opening.balanceCents, this.env.now());
        await this.saveProfile(tx, { ...profile, openingDate: opening.date, openingBalanceCents: opening.balanceCents });
      }
      let created = 0;
      let linked = 0;
      for (const decision of input.decisions) {
        if (!decision.skip) continue;
        await tx.run("UPDATE candidates SET status = 'dismissed', reason = 'Linha ignorada explicitamente na importação.' WHERE event_id = ? AND transaction_id IS NULL AND status = 'pending'", decision.eventId);
      }
      for (const item of selected) {
        if (item.link) {
          await this.link(tx, item.row, item.link, item.review);
          const identity = this.identity(JSON.parse(item.row.parsed_json) as ParseResult);
          if (identity) await tx.run('UPDATE financial_sources SET transaction_id = ? WHERE identity = ?', item.link, identity);
          linked++;
        } else { await this.confirm(tx, item.row.id, item.review); created++; }
      }
      return { created, linked };
    });
  }

  snapshot(month = this.env.today().slice(0, 7)): Promise<Snapshot> {
    return this.queue(async () => {
      validDate(`${month}-01`);
      const profileRow = await this.db.get<{ value: string }>("SELECT value FROM settings WHERE key = 'profile'");
      if (!profileRow) return { profile: null, month, balanceCents: 0, reservesCents: 0, assetsCents: 0, debtCents: 0,
        receivedCents: 0, spentCents: 0, categories: [], reserves: [], commitments: [], transactions: [], candidates: [] };
      const profile = JSON.parse(profileRow.value) as Profile;
      const reserveRows = await this.db.all<{ id: string; name: string; modality: string; updated_on: string }>("SELECT * FROM accounts WHERE kind = 'reserve' ORDER BY name,id");
      const reserves: Reserve[] = [];
      for (const row of reserveRows) reserves.push({ id: row.id, name: row.name, modality: row.modality, updatedOn: row.updated_on, balanceCents: await this.balance(this.db, row.id) });
      const debtRows = await this.db.all<{ id: string; description: string; due_date: string; amount_cents: number; paid: number }>(`SELECT c.*, COALESCE(SUM(p.amount_cents),0) paid
        FROM commitments c LEFT JOIN commitment_payments p ON p.commitment_id = c.id GROUP BY c.id ORDER BY c.due_date,c.id`);
      const commitments: Commitment[] = debtRows.map((row) => ({ id: row.id, description: row.description, dueDate: row.due_date,
        amountCents: cents(row.amount_cents), paidCents: cents(row.paid), remainingCents: addCents(row.amount_cents, -row.paid) }));
      const rows = await this.db.all<{ id: string; kind: string; effective_date: string; description: string; category: string; amount_cents: number; notes: string; candidate_id: string | null; created_at: string; cash: number; sources: number }>(`SELECT t.*,
        COALESCE((SELECT SUM(p.amount_cents) FROM postings p WHERE p.transaction_id = t.id AND p.account_id = 'cash'),0) cash,
        (SELECT COUNT(*) FROM evidence_links e WHERE e.transaction_id = t.id) sources
        FROM transactions t WHERE posted = 1 ORDER BY effective_date DESC,created_at DESC,id DESC`);
      // Opening positions remain in the ledger; the movement history excludes their bookkeeping.
      const transactions: FinancialTransaction[] = rows.filter((row) => !['opening','opening_revision','reserve_opening','debt_opening'].includes(row.kind)).map((row) => ({ id: row.id, kind: row.kind, effectiveDate: row.effective_date,
        description: row.description, category: row.category, amountCents: cents(row.amount_cents), cashDeltaCents: cents(row.cash),
        notes: row.notes, candidateId: row.candidate_id, createdAt: row.created_at, sourceCount: row.sources }));
      const monthly = transactions.filter((row) => row.effectiveDate.startsWith(month));
      const categories = new Map<string, number>();
      for (const row of monthly.filter((item) => item.kind === 'expense')) categories.set(row.category, addCents(categories.get(row.category) ?? 0, row.amountCents));
      const candidateRows = await this.db.all<CandidateRow>('SELECT * FROM candidates ORDER BY rowid DESC');
      const balanceCents = await this.balance(this.db, 'cash');
      const reservesCents = addCents(...reserves.map((r) => r.balanceCents));
      return { profile, month, balanceCents, reservesCents, assetsCents: addCents(balanceCents, reservesCents),
        debtCents: addCents(...commitments.map((c) => c.remainingCents)),
        receivedCents: addCents(...monthly.filter((row) => row.kind === 'income').map((row) => row.amountCents)),
        spentCents: addCents(...monthly.filter((row) => row.kind === 'expense').map((row) => row.amountCents)),
        categories: [...categories.entries()].map(([name, amountCents]) => ({ name, amountCents })).sort((a, b) => b.amountCents - a.amountCents),
        reserves, commitments, transactions, candidates: candidateRows.map(candidate) };
    });
  }
}

export async function createFinanceService(db: Database, env: Environment): Promise<FinanceService> {
  await migrate(db);
  return new DemoFinanceService(db, env);
}
