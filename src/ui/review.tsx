import { Text } from './Typography';
import React, { useState } from 'react';
import { View } from 'react-native';
import type { Candidate, ConfirmImportInput, FinanceService, ImportDecision, ImportPreview, MovementKind, Reserve, ReviewInput, Snapshot, TransferDirection } from '../core/types';
import { makeDemoEvent, makeUpdatedEvent } from '../demo/scenarios';
import { formatBRL, moneyInput, parseBRL } from '../core/money';
import { formatDate, today, validDate } from '../core/dates';
import { Action, Card, Chip, ErrorBox, Field, SectionTitle, styles } from './common';
import { TransactionForm } from './forms';

type Props = { service: FinanceService; snapshot: Snapshot; busy: boolean; run: (task: () => Promise<void>) => Promise<void>; refresh: (month?: string) => Promise<void>; newId: () => string };
export function Review({ service, snapshot, busy, run, refresh, newId }: Props) {
  const [scenarioBusy, setScenarioBusy] = useState(false);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [variant, setVariant] = useState<'current'|'overlap'|'history'>('current');
  const [decisions, setDecisions] = useState<Record<string, ImportDecision>>({});
  const [openingDate, setOpeningDate] = useState(''); const [openingAmount, setOpeningAmount] = useState(''); const [acknowledged, setAcknowledged] = useState(false);
  const [invalidRows, setInvalidRows] = useState<string[]>([]);
  const pending = snapshot.candidates.filter(item => item.status === 'pending' || item.status === 'conflict');
  const processed = snapshot.candidates.filter(item => item.status === 'confirmed' || item.status === 'linked' || item.status === 'dismissed').slice(0, 5);
  async function simulate(scenario: 'safe'|'review'|'same-purchase') {
    setScenarioBusy(true);
    try {
      const event = makeDemoEvent(scenario, today(), newId());
      await run(async () => { await service.observe(event); await refresh(snapshot.month); });
    } finally { setScenarioBusy(false); }
  }
  async function reprocess(candidate: Candidate) {
    setScenarioBusy(true);
    try { await run(async () => { await service.reprocess(candidate.eventId); await refresh(snapshot.month); }); }
    finally { setScenarioBusy(false); }
  }
  async function updateEvent(candidate: Candidate) {
    setScenarioBusy(true);
    try { await run(async () => { const detail = await service.evidence(candidate.id); await service.observe(makeUpdatedEvent(detail.event, newId())); await refresh(snapshot.month); }); }
    finally { setScenarioBusy(false); }
  }
  async function stage(next: typeof variant) {
    await run(async () => {
      setVariant(next);
      try {
        const result = await service.stageDemoImport(next);
        setPreview(result);
        setInvalidRows([]); setImportError(null);
        setDecisions(Object.fromEntries(result.rows.map(row => [row.eventId, { eventId: row.eventId, review: row.status === 'new' ? rowInput(row.draft) : null, linkTransactionId: row.status === 'linked' ? row.transactionId : null, skip: row.status === 'ambiguous' }])));
        setOpeningDate(result.earliestDate); setOpeningAmount(''); setAcknowledged(false);
      } catch (e) {
        const message = e instanceof Error ? e.message : 'Não foi possível preparar esta importação.';
        setImportError(message);
        throw e;
      }
    });
  }
  function updateDecision(eventId: string, patch: Partial<ImportDecision>) {
    setDecisions(current => ({ ...current, [eventId]: { ...current[eventId]!, ...patch } }));
  }
  async function confirm() {
    if (!preview) return;
    try {
      const opening = preview.requiresOpening ? { date: validDate(openingDate), balanceCents: parseBRL(openingAmount), acknowledged } : null;
      if (opening && !acknowledged) throw new Error('Confirme a ciência do saldo de abertura antes de importar.');
      for (const row of preview.rows) {
        const decision = decisions[row.eventId];
        if (!decision || decision.skip || row.status === 'linked') continue;
        if (!decision.review) throw new Error('Escolha se cada linha será criada, vinculada ou ignorada.');
        validDate(decision.review.effectiveDate);
        if (!decision.review.description.trim() || !decision.review.category.trim()) throw new Error('Preencha a descrição e a categoria de cada linha a importar.');
        if (decision.review.kind === 'transfer' && (!decision.review.reserveId || !decision.review.direction)) throw new Error('Escolha caixinha e direção para cada transferência.');
      }
      if (preview.rows.some(row => invalidRows.includes(row.eventId) && !decisions[row.eventId]?.skip && !decisions[row.eventId]?.linkTransactionId)) throw new Error('Corrija os valores incompletos das linhas antes de confirmar.');
      const input: ConfirmImportInput = { operationId: newId(), previewId: preview.id, decisions: preview.rows.map(row => decisions[row.eventId] ?? { eventId: row.eventId, review: null, linkTransactionId: null, skip: true }), opening };
      await run(async () => {
        try {
          await service.confirmImport(input);
        } catch (e) {
          setImportError(e instanceof Error ? e.message : 'Não foi possível confirmar a importação.');
          throw e;
        }
        setPreview(null);
        await refresh(snapshot.month);
      });
    } catch (e) {
      // Validation is presented next to the import controls.
      setImportError(e instanceof Error ? e.message : 'Não foi possível confirmar a importação.');
    }
  }
  const [importError, setImportError] = useState<string | null>(null);
  return <View style={{ gap: 14 }}>
    <Card><Text style={styles.title}>Revisão</Text><Text style={styles.body}>Confira o que veio da simulação antes de transformar em lançamento. Os dados são fictícios.</Text><View style={styles.wrap}>{(['safe','review','same-purchase'] as const).map(s => <Action key={s} title={s === 'safe' ? 'Simular seguro' : s === 'review' ? 'Simular revisão' : 'Simular compra repetida'} secondary onPress={() => void simulate(s)} disabled={busy || scenarioBusy} />)}</View><Text style={styles.small}>A importação abaixo usa apenas três arquivos de exemplo embutidos.</Text></Card>
    <Card><SectionTitle title="Caixa de entrada" /><Text style={styles.small}>Pendente ou com possível conflito.</Text>{pending.length ? pending.map(candidate => <CandidateReview key={candidate.id} candidate={candidate} reserves={snapshot.reserves} service={service} run={run} busy={busy || scenarioBusy} refresh={() => refresh(snapshot.month)} reprocess={() => reprocess(candidate)} updateEvent={() => updateEvent(candidate)} />) : <Text style={styles.small}>Tudo revisado por enquanto.</Text>}</Card>
    <Card><SectionTitle title="Últimos processados" /><Text style={styles.small}>Reprocesse uma fonte para conferir que a decisão anterior foi preservada.</Text>{processed.length ? processed.map(candidate => <View key={candidate.id} style={{ borderTopWidth: 1, borderTopColor: '#DCE3D9', paddingTop: 10, gap: 6 }}><View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 8 }}><Text style={[styles.body, { flex: 1, fontWeight: '700' }]}>{candidate.parsed.draft.description || 'Movimentação sem descrição'}</Text><Text style={styles.small}>{candidate.status === 'confirmed' ? 'Confirmado' : candidate.status === 'linked' ? 'Vinculado' : 'Descartado'}</Text></View><Text style={styles.small}>{candidate.reason || candidate.parsed.reason}</Text><View style={styles.wrap}><Action title="Reprocessar" secondary onPress={() => void reprocess(candidate)} disabled={busy || scenarioBusy} />{candidate.sourceKey.startsWith('ffortuna:synthetic:') && <Action title="Atualizar evento fictício" secondary onPress={() => void updateEvent(candidate)} disabled={busy || scenarioBusy} />}</View></View>) : <Text style={styles.small}>Nenhum candidato processado ainda.</Text>}</Card>
    <Card><SectionTitle title="Importação de demonstração" /><Text style={styles.warning}>Importação bancária de demonstração; suporte ao Nubank não foi validado. Os totais podem ser parciais.</Text><View style={styles.wrap}>{(['current','overlap','history'] as const).map(v => <Chip key={v} title={v === 'current' ? 'Atual' : v === 'overlap' ? 'Com sobreposição' : 'Histórico'} active={variant === v} onPress={() => void stage(v)} />)}</View>
        {preview && <View style={{ gap: 12 }}><Text style={styles.body}>{preview.name} · {preview.rows.length} linhas</Text>{preview.warnings.map(w => <Text key={w} style={styles.warning}>{w}</Text>)}{preview.rows.map(row => <ImportRowReview key={row.eventId} row={row} reserves={snapshot.reserves} decision={decisions[row.eventId]} onInvalidChange={invalid => setInvalidRows(current => invalid ? [...new Set([...current, row.eventId])] : current.filter(id => id !== row.eventId))} onChange={patch => updateDecision(row.eventId, patch)} />)}
        {preview.requiresOpening && <View style={{ gap: 9, paddingTop: 10 }}><Text style={styles.heading}>Saldo de abertura necessário</Text><Text style={styles.small}>Informe o saldo antes da primeira linha importada, em {formatDate(preview.earliestDate)}.</Text><Field label="Data do saldo (AAAA-MM-DD)" value={openingDate} onChangeText={setOpeningDate} /><Field label="Saldo de abertura (R$)" value={openingAmount} onChangeText={setOpeningAmount} keyboardType="decimal-pad" /><Chip title={acknowledged ? '✓ Entendi: o saldo é anterior às linhas' : 'Confirmar que o saldo é anterior às linhas'} active={acknowledged} onPress={() => setAcknowledged(!acknowledged)} /></View>}
        <ErrorBox message={importError} /><Action title={busy ? 'Importando…' : 'Confirmar importação'} onPress={() => void confirm()} disabled={busy} />
      </View>}
    </Card>
  </View>;
}
function rowInput(draft: { amountCents: number | null; kind: MovementKind | null; effectiveDate: string | null; description: string; category: string; reserveId: string | null; direction: TransferDirection | null }): ReviewInput {
  return { amountCents: draft.amountCents ?? 0, kind: draft.kind ?? 'expense', effectiveDate: draft.effectiveDate ?? today(), description: draft.description, category: draft.category || 'A classificar', notes: '', reserveId: draft.reserveId, direction: draft.direction };
}
function CandidateReview({ candidate, reserves, service, run, busy, refresh, reprocess, updateEvent }: { candidate: Candidate; reserves: Reserve[]; service: FinanceService; run: Props['run']; busy: boolean; refresh: () => Promise<void>; reprocess: () => Promise<void>; updateEvent: () => Promise<void> }) {
  const draft = candidate.parsed.draft;
  const initial: Partial<ReviewInput> = { ...(draft.amountCents == null ? {} : { amountCents: draft.amountCents }), ...(draft.kind == null ? {} : { kind: draft.kind }), effectiveDate: draft.effectiveDate ?? '', description: draft.description, category: draft.category || 'A classificar', reserveId: draft.reserveId, direction: draft.direction };
  const [loading, setLoading] = useState(false);
  const [evidence, setEvidence] = useState<{ original: string; extracted: string; correction: string } | null>(null);
  const [showEvidence, setShowEvidence] = useState(false);
  const conflict = candidate.status === 'conflict';
  async function toggleEvidence() {
    if (showEvidence) { setShowEvidence(false); return; }
    setShowEvidence(true);
    if (evidence) return;
    await run(async () => {
      const detail = await service.evidence(candidate.id);
      setEvidence({ original: detail.event.payload, extracted: JSON.stringify({ draft: detail.parsed.draft, confidence: detail.parsed.confidence, fieldEvidence: detail.parsed.fieldEvidence, reason: detail.parsed.reason }, null, 2), correction: detail.decision ? JSON.stringify(detail.decision, null, 2) : 'Sem correção manual' });
    });
  }
  async function dismiss() { setLoading(true); try { await run(async () => { await service.dismissCandidate(candidate.id); await refresh(); }); } finally { setLoading(false); } }
  return <View style={{ borderTopWidth: 1, borderTopColor: '#DCE3D9', paddingTop: 12, gap: 8 }}><View style={{ flexDirection: 'row', justifyContent: 'space-between' }}><Text style={[styles.body, { fontWeight: '700' }]}>{draft.description || 'Movimentação sem descrição'}</Text><Text style={styles.small}>{conflict ? 'Conflito' : 'Pendente'}</Text></View><Text style={styles.small}>{draft.effectiveDate ? formatDate(draft.effectiveDate) : 'Data não reconhecida'} · {draft.amountCents == null ? 'Valor ausente' : formatBRL(draft.amountCents)}</Text><Text style={styles.small}>{candidate.reason || candidate.parsed.reason}</Text>
    {conflict ? <><Text style={styles.warning}>Já existe um lançamento compatível. Para evitar duplicidade, mantenha o lançamento existente e descarte este candidato.</Text><Action title={loading || busy ? 'Atualizando…' : 'Manter lançamento existente'} onPress={() => void dismiss()} disabled={loading || busy} secondary /></> : <TransactionForm reserves={reserves} initial={initial} requireKind busy={loading || busy} submitLabel="Confirmar lançamento" onSave={async input => { setLoading(true); try { await run(async () => { await service.confirmCandidate(candidate.id, input); await refresh(); }); } finally { setLoading(false); } }} />}
    <Action title={showEvidence ? 'Ocultar origem e campos extraídos' : 'Ver origem e campos extraídos'} secondary onPress={() => void toggleEvidence()} disabled={busy} />
    {showEvidence && (evidence ? <View style={{ gap: 7 }}><Text style={styles.small}>Original</Text><Text selectable style={styles.small}>{evidence.original}</Text><Text style={styles.small}>Extraído</Text><Text selectable style={[styles.small, { fontFamily: 'monospace' }]}>{evidence.extracted}</Text><Text style={styles.small}>Correção</Text><Text selectable style={[styles.small, { fontFamily: 'monospace' }]}>{evidence.correction}</Text></View> : <Text style={styles.small}>Carregando evidência…</Text>)}
    <View style={styles.wrap}><Action title="Reprocessar" secondary onPress={() => void reprocess()} disabled={busy} /><Action title="Atualizar evento fictício" secondary onPress={() => void updateEvent()} disabled={busy} /></View>
  </View>;
}
function ImportRowReview({ row, reserves, decision, onChange, onInvalidChange }: { row: ImportPreview['rows'][number]; reserves: Reserve[]; decision: ImportDecision | undefined; onChange: (patch: Partial<ImportDecision>) => void; onInvalidChange: (invalid: boolean) => void }) {
  const d = decision?.review ?? rowInput(row.draft);
  const [notes, setNotes] = useState(d.notes);
  const status = row.status === 'new' ? 'Novo' : row.status === 'linked' ? 'Vinculado' : 'Ambíguo';
  function patchReview(partial: Partial<ReviewInput>) { onChange({ review: { ...d, ...partial } }); }
  if (row.status === 'linked') return <View style={{ borderTopWidth: 1, borderTopColor: '#DCE3D9', paddingTop: 12, gap: 6 }}><Text style={[styles.body, { fontWeight: '700' }]}>{row.draft.description || row.externalId}</Text><Text style={styles.small}>Vinculado · {row.draft.effectiveDate ? formatDate(row.draft.effectiveDate) : 'sem data'} · {row.draft.amountCents == null ? 'sem valor' : formatBRL(row.draft.amountCents)}</Text><Text style={styles.small}>Já importado; saldo preservado. Transação: {row.transactionId ?? 'vínculo existente'}.</Text></View>;
  return <View style={{ borderTopWidth: 1, borderTopColor: '#DCE3D9', paddingTop: 12, gap: 8 }}><Text style={[styles.body, { fontWeight: '700' }]}>{row.draft.description || row.externalId}</Text><Text style={styles.small}>{status} · {row.draft.effectiveDate ?? 'sem data'} · {row.draft.amountCents == null ? 'sem valor' : formatBRL(row.draft.amountCents)}</Text>
    <View style={styles.wrap}><Chip title="Despesa" active={d.kind === 'expense'} onPress={() => patchReview({ kind: 'expense', direction: null, reserveId: null })} /><Chip title="Receita" active={d.kind === 'income'} onPress={() => patchReview({ kind: 'income', direction: null, reserveId: null })} /><Chip title="Transferência" active={d.kind === 'transfer'} onPress={() => patchReview({ kind: 'transfer', direction: 'deposit' })} /></View>
    {d.kind === 'transfer' && <View style={{ gap: 8 }}><View style={styles.wrap}><Chip title="Guardar" active={d.direction === 'deposit'} onPress={() => patchReview({ direction: 'deposit' })} /><Chip title="Resgatar" active={d.direction === 'withdraw'} onPress={() => patchReview({ direction: 'withdraw' })} /></View><View style={styles.wrap}>{reserves.map(reserve => <Chip key={reserve.id} title={reserve.name} active={d.reserveId === reserve.id} onPress={() => patchReview({ reserveId: reserve.id })} />)}</View></View>}
    <ImportAmountField initialValue={moneyInput(d.amountCents)} onAmount={amountCents => { onInvalidChange(false); patchReview({ amountCents }); }} onInvalid={() => onInvalidChange(true)} />
    <Field label="Data (AAAA-MM-DD)" value={d.effectiveDate} onChangeText={value => patchReview({ effectiveDate: value })} />
    <Field label="Categoria" value={d.category} onChangeText={value => patchReview({ category: value })} />
    <Field label="Descrição" value={d.description} onChangeText={value => patchReview({ description: value })} />
    <Field label="Observações" value={notes} onChangeText={value => { setNotes(value); patchReview({ notes: value }); }} placeholder="Opcional" />
    {row.status === 'ambiguous' && <View style={styles.wrap}><Chip title="Ignorar linha" active={Boolean(decision?.skip)} onPress={() => onChange({ skip: true, review: null, linkTransactionId: null })} />{row.matchingTransactionIds.map(id => <Chip key={id} title={`Vincular ${id.slice(0, 8)}`} active={decision?.linkTransactionId === id} onPress={() => onChange({ skip: false, linkTransactionId: id, review: d })} />)}<Chip title="Criar lançamento revisado" active={!decision?.skip && !decision?.linkTransactionId} onPress={() => onChange({ skip: false, linkTransactionId: null, review: d })} /></View>}
    {row.status === 'new' && <View style={styles.wrap}><Chip title="Importar esta linha" active={!decision?.skip} onPress={() => onChange({ skip: false, review: { ...d, notes } })} /><Chip title="Ignorar" active={Boolean(decision?.skip)} onPress={() => onChange({ skip: true })} /></View>}
  </View>;
}
function ImportAmountField({ initialValue, onAmount, onInvalid }: { initialValue: string; onAmount: (amountCents: number) => void; onInvalid: () => void }) {
  const [value, setValue] = useState(initialValue);
  const [error, setError] = useState<string | null>(null);
  return <View style={{ gap: 4 }}><Field label="Valor (R$)" value={value} onChangeText={text => { setValue(text); try { onAmount(parseBRL(text)); setError(null); } catch { onInvalid(); setError('Valor incompleto. Use um valor como 123,45.'); } }} keyboardType="decimal-pad" /><ErrorBox message={error} /></View>;
}
