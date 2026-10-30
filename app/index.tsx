import { CaptureScreen } from '../src/capture/CaptureScreen';
import React, { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFinance } from '../src/demo/provider';
import { History } from '../src/ui/history';
import { Overview } from '../src/ui/overview';
import { Review } from '../src/ui/review';
import { Wallet } from '../src/ui/wallet';
import { LegalNotices } from '../src/legal/LegalNotices';
import { colors, ErrorBox, Field, ScreenScroll, styles } from '../src/ui/common';

type Tab = 'Resumo' | 'Histórico' | 'Revisão' | 'Meu dinheiro';
const tabs: Tab[] = ['Resumo', 'Histórico', 'Revisão', 'Meu dinheiro'];
function currentMonth() { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`; }
export default function Home() {
  return process.env.EXPO_PUBLIC_FFORTUNA_MODE === 'capture-poc' ? <CaptureScreen /> : <DemoHome />;
}
function DemoHome() {
  const { service, snapshot, refresh, newId, loading, error } = useFinance();
  const [tab, setTab] = useState<Tab>('Resumo');
  const [month, setMonth] = useState(currentMonth());
  const [monthDraft, setMonthDraft] = useState(currentMonth());
  const [monthError, setMonthError] = useState<string | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [legalOpen, setLegalOpen] = useState(false);
  const busyRef = useRef(false);
  const run = useCallback(async (task: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true; setBusy(true); setLocalError(null);
    try { await task(); }
    catch (e) { setLocalError(e instanceof Error ? e.message : 'A operação não foi concluída. Tente novamente.'); }
    finally { busyRef.current = false; setBusy(false); }
  }, []);
  async function selectMonth() {
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(monthDraft)) { setMonthError('Use o formato AAAA-MM, por exemplo 2026-01.'); return; }
    setMonthError(null);
    await run(async () => { await refresh(monthDraft); setMonth(monthDraft); });
  }
  const content = !snapshot ? <View style={{ alignItems: 'center', paddingVertical: 40, gap: 12 }}>{loading ? <ActivityIndicator color={colors.green} /> : null}<Text style={styles.body}>{loading ? 'Preparando sua demonstração…' : 'Banco de demonstração indisponível. Reabra o aplicativo para tentar novamente.'}</Text></View> : tab === 'Resumo' ? <Overview service={service} snapshot={snapshot} month={month} busy={busy} run={run} newId={newId} refresh={refresh} /> : tab === 'Histórico' ? <History snapshot={snapshot} service={service} run={run} /> : tab === 'Revisão' ? <Review service={service} snapshot={snapshot} busy={busy} run={run} refresh={refresh} newId={newId} /> : <Wallet service={service} snapshot={snapshot} busy={busy} run={run} newId={newId} refresh={refresh} />;
  return <SafeAreaView style={styles.page} edges={['top','left','right','bottom']}>
    <View style={{ paddingHorizontal: 18, paddingTop: 6, paddingBottom: 10, gap: 12, backgroundColor: colors.paper }}>
      <View style={{ gap: 7, alignItems: 'flex-start' }}><Text style={{ color: colors.ink, fontSize: 25, fontWeight: '800', letterSpacing: -0.4 }}>Tique</Text><View style={{ borderRadius: 99, backgroundColor: '#E5EFE7', paddingHorizontal: 11, paddingVertical: 7 }}><Text style={{ color: colors.green, fontSize: 10, fontWeight: '800', letterSpacing: 0.4 }}>DEMONSTRAÇÃO · Só dados fictícios</Text></View></View>
      <View style={{ flexDirection: 'row', gap: 8 }}>{tabs.map(item => <Pressable key={item} accessibilityRole="tab" accessibilityState={{ selected: tab === item }} onPress={() => setTab(item)} style={{ minHeight: 44, flex: 1, borderRadius: 13, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 3, backgroundColor: tab === item ? colors.green : '#E9E8E0' }}><Text style={{ fontSize: 12, fontWeight: '700', color: tab === item ? colors.white : colors.ink }}>{item}</Text></Pressable>)}</View>
      <View style={{ flexDirection: 'row', alignItems: 'flex-end', gap: 10 }}><View style={{ flex: 1 }}><Field label="Mês (AAAA-MM)" value={monthDraft} onChangeText={value => { setMonthDraft(value); setMonthError(null); }} keyboardType="numbers-and-punctuation" placeholder="2026-01" returnKeyType="done" onSubmitEditing={() => void selectMonth()} /></View><Pressable accessibilityRole="button" onPress={() => void selectMonth()} disabled={busy} style={{ minHeight: 48, paddingHorizontal: 14, borderRadius: 13, backgroundColor: colors.green, alignItems: 'center', justifyContent: 'center', opacity: busy ? 0.5 : 1 }}><Text style={{ color: colors.white, fontWeight: '700' }}>Abrir</Text></Pressable></View><ErrorBox message={monthError} />
    </View>
    <ScreenScroll><ErrorBox message={error ?? localError} />{content}<Pressable accessibilityRole="button" onPress={() => setLegalOpen(true)} style={{ minHeight: 44, justifyContent: 'center', alignItems: 'center' }}><Text style={styles.small}>Sobre e licenças · AGPLv3</Text></Pressable></ScreenScroll>
    <LegalNotices visible={legalOpen} onClose={() => setLegalOpen(false)} />
  </SafeAreaView>;
}
