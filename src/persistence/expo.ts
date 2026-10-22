import * as SQLite from 'expo-sqlite';
import type { Database, SqlSession, SqlValue } from './database';

function session(db: SQLite.SQLiteDatabase): SqlSession {
  return {
    exec: (sql) => db.execAsync(sql),
    run: async (sql, ...params) => { await db.runAsync(sql, params); },
    get: <T,>(sql: string, ...params: SqlValue[]) => db.getFirstAsync<T>(sql, params),
    all: <T,>(sql: string, ...params: SqlValue[]) => db.getAllAsync<T>(sql, params),
  };
}

/** Intentionally unencrypted: this adapter is exclusively for synthetic demonstration data. */
export async function openDemoDatabase(): Promise<Database> {
  const db = await SQLite.openDatabaseAsync('ffortuna-synthetic-v1.db');
  return {
    ...session(db),
    transaction: async <T,>(work: (tx: SqlSession) => Promise<T>): Promise<T> => {
      // The service serializes every operation and owns this connection. Expo's
      // exclusive helper opens a new connection without our foreign_keys PRAGMA.
      await db.execAsync('BEGIN IMMEDIATE');
      try {
        const result = await work(session(db));
        await db.execAsync('COMMIT');
        return result;
      } catch (error) {
        await db.execAsync('ROLLBACK');
        throw error;
      }
    },
    close: () => db.closeAsync(),
  };
}
