import { DatabaseSync } from 'node:sqlite';
import type { Database, SqlSession, SqlValue } from '../../src/persistence/database';
import { createFinanceService } from '../../src/application/demo-service';

class TestDatabase implements Database {
  constructor(private readonly raw: DatabaseSync) {
    raw.exec('PRAGMA foreign_keys = ON;');
  }

  async exec(sql: string): Promise<void> { this.raw.exec(sql); }

  async run(sql: string, ...params: SqlValue[]): Promise<void> {
    this.raw.prepare(sql).run(...params);
  }

  async get<T>(sql: string, ...params: SqlValue[]): Promise<T | null> {
    return (this.raw.prepare(sql).get(...params) as T | undefined) ?? null;
  }

  async all<T>(sql: string, ...params: SqlValue[]): Promise<T[]> {
    return this.raw.prepare(sql).all(...params) as T[];
  }

  async transaction<T>(work: (session: SqlSession) => Promise<T>): Promise<T> {
    this.raw.exec('BEGIN IMMEDIATE');
    try {
      const result = await work(this);
      this.raw.exec('COMMIT');
      return result;
    } catch (error) {
      try { this.raw.exec('ROLLBACK'); } catch { /* Keep the original transaction error. */ }
      throw error;
    }
  }

  async close(): Promise<void> { this.raw.close(); }
}

export function openTestDatabase(path = ':memory:'): Database {
  return new TestDatabase(new DatabaseSync(path));
}

let idSequence = 0;
export function testEnvironment() {
  return {
    newId: () => `test-id-${++idSequence}`,
    now: () => '2026-10-21T12:00:00.000Z',
    today: () => '2026-10-21',
  };
}

export async function createTestService(db = openTestDatabase()) {
  return { db, service: await createFinanceService(db, testEnvironment()) };
}
