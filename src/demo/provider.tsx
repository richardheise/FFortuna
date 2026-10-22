import * as Crypto from 'expo-crypto';
import { createContext, useCallback, useContext, useEffect, useRef, useState, type PropsWithChildren } from 'react';
import { today } from '../core/dates';
import { createFinanceService } from '../application/demo-service';
import type { FinanceService, Snapshot } from '../core/types';
import { openDemoDatabase } from '../persistence/expo';

interface FinanceContextValue {
  service: FinanceService;
  snapshot: Snapshot | null;
  refresh: (month?: string) => Promise<void>;
  newId: () => string;
  loading: boolean;
  error: string | null;
}
const Context = createContext<FinanceContextValue | null>(null);
let runtime: Promise<FinanceService> | null = null;

function loadDemo(): Promise<FinanceService> {
  if (!runtime) {
    runtime = (async () => {
      const db = await openDemoDatabase();
      try { return await createFinanceService(db, { newId: Crypto.randomUUID, now: () => new Date().toISOString(), today }); }
      catch (error) { await db.close(); throw error; }
    })().catch((error: unknown) => { runtime = null; throw error; });
  }
  return runtime;
}

/** No native capture adapter or file picker is imported by the synthetic application. */
export function FinanceProvider({ children }: PropsWithChildren) {
  const [service, setService] = useState<FinanceService | null>(null);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const selectedMonth = useRef<string | undefined>(undefined);
  const refresh = useCallback(async (month?: string) => {
    if (!service) return;
    if (month !== undefined) selectedMonth.current = month;
    const next = await service.snapshot(selectedMonth.current);
    setSnapshot(next);
  }, [service]);

  useEffect(() => {
    let active = true;
    void loadDemo().then(async (next) => {
      const initial = await next.snapshot();
      if (active) { setService(next); setSnapshot(initial); setLoading(false); }
    }).catch(() => {
      if (active) { setError('Não foi possível abrir o banco de demonstração. Os dados foram preservados. Feche e abra o aplicativo para tentar novamente.'); setLoading(false); }
    });
    return () => { active = false; };
  }, []);

  return <Context.Provider value={{ service: service!, snapshot, refresh, newId: Crypto.randomUUID, loading, error }}>{children}</Context.Provider>;
}

export function useFinance(): FinanceContextValue {
  const context = useContext(Context);
  if (!context) throw new Error('FinanceProvider ausente.');
  return context;
}
