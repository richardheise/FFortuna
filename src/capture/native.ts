import Constants from 'expo-constants';
import type { ObservedEvent } from '../core/types';
import { requireOptionalNativeModule } from 'expo';

export interface NativeObservation extends Omit<ObservedEvent, 'payload'> {
  payload: string | null; sourcePackage: string;
  state: 'pending' | 'processed'; parserVersion: string | null; parsedJson: string | null;
}
export interface CaptureSnapshot {
  enabled: boolean; authorized: boolean; connected: boolean; notificationsAllowed: boolean;
  storageReady: boolean; sourcePackage: string; lastError: string | null; syntheticOnly: true;
  previousBoxAvailable: boolean;
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
}

/** Never look up a custom native module in Expo Go. Only the PoC screen calls this. */
export function captureModule(): CaptureModule | null {
  if (Constants.executionEnvironment === 'storeClient') return null;
  return requireOptionalNativeModule<CaptureModule>('FFortunaCapture');
}
