import type { Database } from './database';

export const SCHEMA_VERSION = 2;

export const migrations = [
  `
  CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
  CREATE TABLE commands (id TEXT PRIMARY KEY, request_json TEXT NOT NULL, result_json TEXT NOT NULL);
  CREATE TABLE accounts (
    id TEXT PRIMARY KEY, name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('cash','reserve','liability','income','expense','equity')),
    modality TEXT NOT NULL DEFAULT '', updated_on TEXT NOT NULL
  );
  CREATE TABLE events (
    id TEXT PRIMARY KEY, source TEXT NOT NULL CHECK(source IN ('simulation','ofx')),
    source_key TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0),
    published_at TEXT, captured_at TEXT NOT NULL, payload TEXT NOT NULL,
    UNIQUE(source, source_key, revision)
  );
  CREATE TABLE parses (
    event_id TEXT NOT NULL REFERENCES events(id), parser_version TEXT NOT NULL,
    result_json TEXT NOT NULL, PRIMARY KEY(event_id, parser_version)
  );
  CREATE TABLE candidates (
    id TEXT PRIMARY KEY, event_id TEXT NOT NULL REFERENCES events(id),
    source TEXT NOT NULL, source_key TEXT NOT NULL, revision INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('pending','confirmed','dismissed','conflict','linked')),
    parsed_json TEXT NOT NULL, reason TEXT NOT NULL, transaction_id TEXT, decision_json TEXT,
    UNIQUE(source,source_key)
  );
  CREATE TABLE transactions (
    id TEXT PRIMARY KEY, operation_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL,
    effective_date TEXT NOT NULL, description TEXT NOT NULL, category TEXT NOT NULL,
    amount_cents INTEGER NOT NULL CHECK(typeof(amount_cents) = 'integer' AND amount_cents BETWEEN 0 AND 9007199254740991),
    notes TEXT NOT NULL, candidate_id TEXT UNIQUE REFERENCES candidates(id),
    created_at TEXT NOT NULL, posted INTEGER NOT NULL DEFAULT 0 CHECK(posted IN (0,1))
  );
  CREATE TABLE postings (
    id INTEGER PRIMARY KEY, transaction_id TEXT NOT NULL REFERENCES transactions(id),
    account_id TEXT NOT NULL REFERENCES accounts(id),
    amount_cents INTEGER NOT NULL CHECK(typeof(amount_cents) = 'integer' AND amount_cents BETWEEN -9007199254740991 AND 9007199254740991)
  );
  CREATE INDEX postings_account ON postings(account_id,transaction_id);
  CREATE INDEX transaction_date ON transactions(effective_date);
  CREATE TRIGGER balanced_before_post BEFORE UPDATE OF posted ON transactions WHEN NEW.posted = 1 BEGIN
    SELECT CASE WHEN (SELECT COUNT(*) FROM postings WHERE transaction_id = NEW.id) < 2
      OR (SELECT SUM(amount_cents) FROM postings WHERE transaction_id = NEW.id) != 0
      THEN RAISE(ABORT,'Lançamento desequilibrado') END;
  END;
  CREATE TRIGGER immutable_transaction BEFORE UPDATE ON transactions WHEN OLD.posted = 1 BEGIN
    SELECT RAISE(ABORT,'Transação confirmada é imutável');
  END;
  CREATE TRIGGER immutable_posting_insert BEFORE INSERT ON postings
    WHEN (SELECT posted FROM transactions WHERE id = NEW.transaction_id) = 1 BEGIN
    SELECT RAISE(ABORT,'Lançamento confirmado é imutável');
  END;
  CREATE TRIGGER immutable_posting_update BEFORE UPDATE ON postings
    WHEN (SELECT posted FROM transactions WHERE id = OLD.transaction_id) = 1 BEGIN
    SELECT RAISE(ABORT,'Lançamento confirmado é imutável');
  END;
  CREATE TRIGGER immutable_posting_delete BEFORE DELETE ON postings
    WHEN (SELECT posted FROM transactions WHERE id = OLD.transaction_id) = 1 BEGIN
    SELECT RAISE(ABORT,'Lançamento confirmado é imutável');
  END;
  CREATE TRIGGER immutable_transaction_delete BEFORE DELETE ON transactions WHEN OLD.posted = 1 BEGIN
    SELECT RAISE(ABORT,'Transação confirmada é imutável');
  END;
  CREATE TABLE commitments (
    id TEXT PRIMARY KEY, description TEXT NOT NULL, due_date TEXT NOT NULL,
    amount_cents INTEGER NOT NULL CHECK(amount_cents > 0 AND amount_cents <= 9007199254740991)
  );
  CREATE TABLE commitment_payments (
    transaction_id TEXT PRIMARY KEY REFERENCES transactions(id),
    commitment_id TEXT NOT NULL REFERENCES commitments(id), amount_cents INTEGER NOT NULL CHECK(amount_cents > 0)
  );
  CREATE TABLE financial_sources (
    identity TEXT PRIMARY KEY, identity_json TEXT NOT NULL,
    candidate_id TEXT NOT NULL REFERENCES candidates(id), transaction_id TEXT REFERENCES transactions(id)
  );
  CREATE TABLE evidence_links (
    event_id TEXT PRIMARY KEY REFERENCES events(id), transaction_id TEXT NOT NULL REFERENCES transactions(id)
  );
  CREATE TABLE import_previews (
    id TEXT PRIMARY KEY, name TEXT NOT NULL, content TEXT NOT NULL,
    event_ids_json TEXT NOT NULL, created_at TEXT NOT NULL
  );
  CREATE TABLE opening_revisions (
    id TEXT PRIMARY KEY, old_date TEXT NOT NULL, old_balance_cents INTEGER NOT NULL,
    new_date TEXT NOT NULL, new_balance_cents INTEGER NOT NULL, created_at TEXT NOT NULL
  );
  `,
  `
  CREATE TRIGGER transactions_start_draft BEFORE INSERT ON transactions WHEN NEW.posted != 0 BEGIN
    SELECT RAISE(ABORT,'Confirme o lançamento somente após gravar suas contrapartidas');
  END;
  CREATE TABLE conflicts (
    id INTEGER PRIMARY KEY, candidate_id TEXT NOT NULL REFERENCES candidates(id),
    event_id TEXT NOT NULL REFERENCES events(id), reason TEXT NOT NULL,
    resolved INTEGER NOT NULL DEFAULT 0 CHECK(resolved IN (0,1)), UNIQUE(candidate_id,event_id)
  );
  CREATE INDEX candidates_status ON candidates(status);
  `,
] as const;

export async function migrate(db: Database): Promise<void> {
  await db.exec('PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL;');
  await db.transaction(async (tx) => {
    const row = await tx.get<{ user_version: number }>('PRAGMA user_version');
    const version = row?.user_version ?? 0;
    if (version > SCHEMA_VERSION) throw new Error('Banco de uma versão mais nova. Atualize o aplicativo; seus dados foram preservados.');
    for (let index = version; index < migrations.length; index++) {
      const sql = migrations[index];
      if (sql === undefined) throw new Error('Migração indisponível.');
      await tx.exec(sql);
      await tx.exec(`PRAGMA user_version = ${index + 1}`);
    }
  });
}
