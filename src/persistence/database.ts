export type SqlValue = string | number | null;

export interface SqlSession {
  exec(sql: string): Promise<void>;
  run(sql: string, ...params: SqlValue[]): Promise<void>;
  get<T>(sql: string, ...params: SqlValue[]): Promise<T | null>;
  all<T>(sql: string, ...params: SqlValue[]): Promise<T[]>;
}

export interface Database extends SqlSession {
  transaction<T>(work: (session: SqlSession) => Promise<T>): Promise<T>;
  close(): Promise<void>;
}
