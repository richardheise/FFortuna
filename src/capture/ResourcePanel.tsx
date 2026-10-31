import { Text } from '../ui/Typography';
import { useEffect, useRef, useState } from 'react';
import * as Crypto from 'expo-crypto';
import { AppState,  View } from 'react-native';
import { formatBRL, moneyInput, parseBRL } from '../core/money';
import { Action, Card, Chip, ErrorBox, Field, colors, styles } from '../ui/common';
import { ClassificationPanel } from './ClassificationPanel';
import type { CaptureModule, FinanceCard, FinanceCommitment, FinanceReserve, FinanceResult, FinanceSnapshot } from './native';

type Props = {
  native: CaptureModule;
  busy: boolean;
  run: (task: () => Promise<unknown>) => Promise<void>;
  snapshot: FinanceSnapshot;
  month: string;
  refresh: (month?: string) => Promise<void>;
  valuesHidden?: boolean;
  onChanged?: (() => void) | undefined;
};
type Direction = 'deposit' | 'withdraw';
type ReserveAction = 'move' | 'transfer' | 'update';
type Classification = { categoryId: string | null; category: string; tagIds: string[] };

const today = () => {
  const value = new Date();
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
};
function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T12:00:00`);
  return !Number.isNaN(parsed.getTime()) && `${parsed.getFullYear()}-${String(parsed.getMonth() + 1).padStart(2, '0')}-${String(parsed.getDate()).padStart(2, '0')}` === value;
}
function parseAmount(value: string) {
  const amountCents = parseBRL(value);
  if (amountCents <= 0) throw new Error('Informe um valor maior que zero.');
  return amountCents;
}
function fieldsFingerprint(fields: Record<string, unknown>) {
  const serialized = JSON.stringify(fields);
  let first = 0x811c9dc5;
  let second = 0x9e3779b9;
  for (let index = 0; index < serialized.length; index += 1) {
    const code = serialized.charCodeAt(index);
    first = Math.imul(first ^ code, 0x01000193);
    second = Math.imul(second ^ code, 0x85ebca6b);
  }
  return `${serialized.length}:${first >>> 0}:${second >>> 0}`;
}

export function ResourcePanel({ native, busy, run, snapshot, month, refresh, valuesHidden = false, onChanged }: Props) {
  const reserves = snapshot.reserves ?? [];
  const cards = snapshot.cards ?? [];
  const commitments = snapshot.commitments ?? [];
  const displayMoney = (amountCents: number) => valuesHidden ? '••••' : formatBRL(amountCents);
  const mounted = useRef(false);
  const active = useRef(AppState.currentState === 'active');
  const generation = useRef(0);
  const commandIds = useRef(new Map<string, { fingerprint: string; id: string }>());
  const updateAmountPrefilled = useRef(false);
  const paymentAmountPrefilled = useRef(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [reserveForm, setReserveForm] = useState(false);
  const [reserveName, setReserveName] = useState('');
  const [reserveModality, setReserveModality] = useState('');
  const [reserveInitial, setReserveInitial] = useState('');
  const [reserveDate, setReserveDate] = useState(today());
  const [openReserveAction, setOpenReserveAction] = useState<string | null>(null);
  const [direction, setDirection] = useState<Direction>('deposit');
  const [moveAmount, setMoveAmount] = useState('');
  const [moveDate, setMoveDate] = useState(today());
  const [transferAmount, setTransferAmount] = useState('');
  const [transferDate, setTransferDate] = useState(today());
  const [transferTargetId, setTransferTargetId] = useState<string | null>(null);
  const [updateAmount, setUpdateAmount] = useState('');
  const [updateDate, setUpdateDate] = useState(today());
  const [updateReason, setUpdateReason] = useState('');
  const [cardForm, setCardForm] = useState(false);
  const [cardName, setCardName] = useState('');
  const [closingDay, setClosingDay] = useState('');
  const [dueDay, setDueDay] = useState('');
  const [purchaseCardId, setPurchaseCardId] = useState<string | null>(null);
  const [purchaseAmount, setPurchaseAmount] = useState('');
  const [purchaseDate, setPurchaseDate] = useState(today());
  const [purchaseDescription, setPurchaseDescription] = useState('');
  const [purchaseNotes, setPurchaseNotes] = useState('');
  const [purchaseClassification, setPurchaseClassification] = useState<Classification>({ categoryId: null, category: 'A classificar', tagIds: [] });
  const [commitmentForm, setCommitmentForm] = useState(false);
  const [commitmentDescription, setCommitmentDescription] = useState('');
  const [commitmentDate, setCommitmentDate] = useState(today());
  const [commitmentAmount, setCommitmentAmount] = useState('');
  const [commitmentCardId, setCommitmentCardId] = useState<string | null>(null);
  const [paymentId, setPaymentId] = useState<string | null>(null);
  const [paymentAmount, setPaymentAmount] = useState('');
  const [paymentDate, setPaymentDate] = useState(today());

  useEffect(() => {
    if (!valuesHidden) return;
    if (updateAmountPrefilled.current) { setUpdateAmount(''); updateAmountPrefilled.current = false; }
    if (paymentAmountPrefilled.current) { setPaymentAmount(''); paymentAmountPrefilled.current = false; }
  }, [valuesHidden]);

  function editUpdateAmount(value: string) { updateAmountPrefilled.current = false; setUpdateAmount(value); }
  function editPaymentAmount(value: string) { paymentAmountPrefilled.current = false; setPaymentAmount(value); }

  useEffect(() => {
    mounted.current = true;
    const subscription = AppState.addEventListener('change', state => {
      active.current = state === 'active';
      generation.current += 1;
      if (!active.current) {
        setReserveForm(false); setReserveName(''); setReserveModality(''); setReserveInitial(''); setReserveDate(today());
        setOpenReserveAction(null); setMoveAmount(''); setUpdateAmount(''); setUpdateReason(''); updateAmountPrefilled.current = false; paymentAmountPrefilled.current = false;
        setTransferAmount(''); setTransferTargetId(null);
        setCardForm(false); setCardName(''); setClosingDay(''); setDueDay(''); setPurchaseCardId(null);
        setPurchaseAmount(''); setPurchaseDescription(''); setPurchaseNotes(''); setPurchaseClassification({ categoryId: null, category: 'A classificar', tagIds: [] });
        setCommitmentForm(false); setCommitmentDescription(''); setCommitmentAmount(''); setCommitmentCardId(null);
        setPaymentId(null); setPaymentAmount(''); setError(null); setMessage(null); setSaving(false);
      } else if (mounted.current) void refresh(month);
    });
    return () => { mounted.current = false; generation.current += 1; subscription.remove(); };
  }, [month, refresh]);

  function commandIdFor(key: string, fields: Record<string, unknown>) {
    const fingerprint = fieldsFingerprint(fields);
    const prior = commandIds.current.get(key);
    if (prior?.fingerprint === fingerprint) return prior.id;
    const id = Crypto.randomUUID();
    commandIds.current.set(key, { fingerprint, id });
    return id;
  }
  function forgetCommandId(key: string, id: string) {
    if (commandIds.current.get(key)?.id === id) commandIds.current.delete(key);
  }

  async function mutate(task: () => Promise<FinanceResult>, success: string, commandKey: string, commandId: string, after?: () => void) {
    if (busy || saving || !active.current) return false;
    const request = generation.current;
    setSaving(true); setError(null); setMessage(null);
    try {
      const result: { current: FinanceResult | null } = { current: null };
      await run(async () => {
        result.current = await task();
        if (result.current.status === 'error') throw new Error(result.current.code ?? 'STORAGE');
      });
      if (!result.current || result.current.status === 'error') throw new Error(result.current?.code ?? 'STORAGE');
      forgetCommandId(commandKey, commandId);
      if (!mounted.current || !active.current || generation.current !== request) return false;
      await refresh(month);
      if (!mounted.current || !active.current || generation.current !== request) return false;
      onChanged?.();
      setMessage(success);
      after?.();
      return true;
    } catch (failure) {
      if (!mounted.current || !active.current || generation.current !== request) return false;
      const code = failure instanceof Error ? failure.message : '';
      setError(code === 'INVALID_AMOUNT' ? 'Confira o valor em reais.' : code === 'INVALID_DATE' ? 'Confira a data informada.' : 'Não foi possível salvar; os dados informados continuam disponíveis para tentar novamente.');
      return false;
    } finally { if (mounted.current && active.current && generation.current === request) setSaving(false); }
  }

  async function createReserve() {
    setError(null);
    if (!reserveName.trim() || !reserveModality.trim()) { setError('Informe nome e modalidade da caixinha.'); return; }
    if (!validDate(reserveDate)) { setError('Informe uma data válida.'); return; }
    let amountCents: number;
    try { amountCents = reserveInitial.trim() ? parseBRL(reserveInitial) : 0; } catch { setError('Confira a posição inicial em reais.'); return; }
    const commandKey = 'reserve:create'; const fields = { name: reserveName.trim(), modality: reserveModality.trim(), date: reserveDate, amountCents }; const id = commandIdFor(commandKey, fields);
    await mutate(() => native.createFinanceReserve(JSON.stringify({ commandId: id, ...fields })), 'Caixinha salva. A posição inicial não altera o saldo da conta.', commandKey, id, () => { setReserveForm(false); setReserveName(''); setReserveModality(''); setReserveInitial(''); });
  }

  async function moveReserve(reserve: FinanceReserve) {
    setError(null);
    if (!validDate(moveDate)) { setError('Confira a data informada.'); return; }
    let amountCents: number;
    try { amountCents = parseAmount(moveAmount); } catch (failure) { setError(failure instanceof Error ? failure.message : 'Confira o valor.'); return; }
    const success = direction === 'deposit' ? 'Valor guardado na caixinha.' : 'Valor resgatado para a conta.';
    const commandKey = `reserve:move:${reserve.id}`; const fields = { reserveId: reserve.id, direction, amountCents, date: moveDate }; const id = commandIdFor(commandKey, fields);
    await mutate(() => native.moveFinanceReserve(JSON.stringify({ commandId: id, ...fields })), success, commandKey, id, () => { setOpenReserveAction(null); setMoveAmount(''); });
  }

  async function transferReserve(reserve: FinanceReserve) {
    setError(null);
    if (!transferTargetId || transferTargetId === reserve.id) { setError('Escolha outra caixinha como destino.'); return; }
    if (!validDate(transferDate)) { setError('Confira a data informada.'); return; }
    let amountCents: number;
    try { amountCents = parseAmount(transferAmount); } catch (failure) { setError(failure instanceof Error ? failure.message : 'Confira o valor.'); return; }
    const commandKey = `reserve:transfer:${reserve.id}`; const fields = { fromReserveId: reserve.id, toReserveId: transferTargetId, amountCents, date: transferDate }; const id = commandIdFor(commandKey, fields);
    await mutate(() => native.transferFinanceReserve(JSON.stringify({ commandId: id, ...fields })), 'Valor transferido entre caixinhas; a conta não foi alterada.', commandKey, id, () => { setOpenReserveAction(null); setTransferAmount(''); setTransferTargetId(null); });
  }

  async function updateReserve(reserve: FinanceReserve) {
    setError(null);
    if (!validDate(updateDate)) { setError('Confira a data informada.'); return; }
    if (!updateReason.trim()) { setError('Explique o motivo da atualização.'); return; }
    let amountCents: number;
    try { amountCents = parseBRL(updateAmount); } catch { setError('Confira o novo valor.'); return; }
    const commandKey = `reserve:update:${reserve.id}`; const fields = { reserveId: reserve.id, amountCents, date: updateDate, note: updateReason.trim() }; const id = commandIdFor(commandKey, fields);
    await mutate(() => native.updateFinanceReserve(JSON.stringify({ commandId: id, ...fields })), 'Posição da caixinha atualizada; a diferença não é receita nem despesa.', commandKey, id, () => { setOpenReserveAction(null); setUpdateReason(''); });
  }

  async function createCard() {
    setError(null);
    if (!cardName.trim()) { setError('Informe o nome do cartão.'); return; }
    const closing = Number(closingDay); const due = Number(dueDay);
    if (!Number.isInteger(closing) || closing < 1 || closing > 28 || !Number.isInteger(due) || due < 1 || due > 28) { setError('Fechamento e vencimento devem ser dias de 1 a 28.'); return; }
    const commandKey = 'card:create'; const fields = { name: cardName.trim(), closingDay: closing, dueDay: due }; const id = commandIdFor(commandKey, fields);
    await mutate(() => native.createFinanceCard(JSON.stringify({ commandId: id, ...fields })), 'Cartão cadastrado.', commandKey, id, () => { setCardForm(false); setCardName(''); setClosingDay(''); setDueDay(''); });
  }

  async function createPurchase(card: FinanceCard) {
    setError(null);
    if (!validDate(purchaseDate)) { setError('Confira a data informada.'); return; }
    if (!purchaseDescription.trim()) { setError('Informe a descrição da compra.'); return; }
    let amountCents: number;
    try { amountCents = parseAmount(purchaseAmount); } catch (failure) { setError(failure instanceof Error ? failure.message : 'Confira o valor.'); return; }
    const commandKey = `card:purchase:${card.id}`; const fields = { cardId: card.id, amountCents, effectiveDate: purchaseDate, description: purchaseDescription.trim(), category: purchaseClassification.category, categoryId: purchaseClassification.categoryId, tagIds: purchaseClassification.tagIds, notes: purchaseNotes.trim() }; const id = commandIdFor(commandKey, fields);
    await mutate(() => native.createCardPurchase(JSON.stringify({ commandId: id, ...fields })), 'Compra registrada como despesa; o pagamento da fatura não contará novamente como despesa.', commandKey, id, () => { setPurchaseCardId(null); setPurchaseAmount(''); setPurchaseDescription(''); setPurchaseNotes(''); setPurchaseClassification({ categoryId: null, category: 'A classificar', tagIds: [] }); });
  }

  async function createCommitment() {
    setError(null);
    if (!commitmentDescription.trim()) { setError('Informe a descrição da dívida.'); return; }
    if (!validDate(commitmentDate)) { setError('Informe uma data válida.'); return; }
    let amountCents: number;
    try { amountCents = parseAmount(commitmentAmount); } catch (failure) { setError(failure instanceof Error ? failure.message : 'Confira o valor.'); return; }
    const commandKey = 'commitment:create'; const fields = { description: commitmentDescription.trim(), dueDate: commitmentDate, amountCents, cardId: commitmentCardId }; const id = commandIdFor(commandKey, fields);
    await mutate(() => native.createFinanceCommitment(JSON.stringify({ commandId: id, ...fields })), 'Dívida registrada sem criar uma despesa nova.', commandKey, id, () => { setCommitmentForm(false); setCommitmentDescription(''); setCommitmentAmount(''); setCommitmentCardId(null); });
  }

  async function payCommitment(commitment: FinanceCommitment) {
    setError(null);
    if (!validDate(paymentDate)) { setError('Confira a data informada.'); return; }
    let amountCents: number;
    try { amountCents = parseAmount(paymentAmount); } catch (failure) { setError(failure instanceof Error ? failure.message : 'Confira o valor.'); return; }
    if (amountCents > commitment.remainingCents) { setError(valuesHidden ? 'O valor não pode passar do restante da dívida.' : `O valor não pode passar do restante de ${displayMoney(commitment.remainingCents)}.`); return; }
    const commandKey = `commitment:payment:${commitment.id}`; const fields = { commitmentId: commitment.id, amountCents, date: paymentDate }; const id = commandIdFor(commandKey, fields);
    await mutate(() => native.payFinanceCommitment(JSON.stringify({ commandId: id, ...fields })), 'Pagamento registrado; ele reduz o saldo e a dívida, sem criar nova despesa.', commandKey, id, () => { setPaymentId(null); setPaymentAmount(''); });
  }

  return <View style={{ gap: 12 }}>
    <Card><Text style={styles.heading}>Caixinhas e cartões</Text><Text style={styles.small}>Confira antes de salvar: estas movimentações ainda não podem ser desfeitas.</Text><Text style={styles.small}>Valores dos seus registros. O Tique não consulta seu banco.</Text><View style={[styles.row, { justifyContent: 'space-between' }]}><Text style={styles.body}>Resultado da conta</Text><Text style={styles.label}>{displayMoney(snapshot.balanceCents)}</Text></View><View style={[styles.row, { justifyContent: 'space-between' }]}><Text style={styles.body}>Em caixinhas</Text><Text style={styles.label}>{displayMoney(snapshot.reservesCents ?? 0)}</Text></View><View style={[styles.row, { justifyContent: 'space-between' }]}><Text style={styles.body}>Dívidas informadas</Text><Text style={styles.label}>{displayMoney(snapshot.debtCents ?? 0)}</Text></View></Card>
    <ErrorBox message={error} />
    {message && <Text accessibilityRole="alert" style={styles.small}>{message}</Text>}
    <Card>
      <Text style={styles.heading}>Caixinhas</Text>
      <Text style={styles.small}>Informe o que já está guardado. Guardar e resgatar movem valor entre conta e caixinha; ajustar muda só a posição.</Text>
      {!reserves.length && <Text style={styles.small}>Nenhuma caixinha.</Text>}
      {reserves.map(reserve => <ReserveRow key={reserve.id} reserve={reserve} reserves={reserves} busy={busy || saving} valuesHidden={valuesHidden} displayMoney={displayMoney} open={openReserveAction === reserve.id} onToggle={() => { setError(null); setOpenReserveAction(value => value === reserve.id ? null : reserve.id); setMoveAmount(''); setTransferAmount(''); setTransferTargetId(null); updateAmountPrefilled.current = !valuesHidden; setUpdateAmount(valuesHidden ? '' : moneyInput(reserve.balanceCents)); setUpdateReason(''); }} direction={direction} setDirection={setDirection} moveAmount={moveAmount} setMoveAmount={setMoveAmount} moveDate={moveDate} setMoveDate={setMoveDate} transferAmount={transferAmount} setTransferAmount={setTransferAmount} transferDate={transferDate} setTransferDate={setTransferDate} transferTargetId={transferTargetId} setTransferTargetId={setTransferTargetId} updateAmount={valuesHidden && updateAmountPrefilled.current ? '' : updateAmount} setUpdateAmount={editUpdateAmount} updateDate={updateDate} setUpdateDate={setUpdateDate} updateReason={updateReason} setUpdateReason={setUpdateReason} onMove={() => void moveReserve(reserve)} onTransfer={() => void transferReserve(reserve)} onUpdate={() => void updateReserve(reserve)} />)}
      <Action secondary title={reserveForm ? 'Fechar cadastro' : '＋ Nova caixinha'} disabled={busy || saving} onPress={() => { setReserveForm(value => !value); setError(null); }} />
      {reserveForm && <View style={{ gap: 8 }}><Field label="Nome" value={reserveName} onChangeText={setReserveName} editable={!busy && !saving} /><Field label="Modalidade" value={reserveModality} onChangeText={setReserveModality} editable={!busy && !saving} placeholder="Ex.: reserva, CDB" /><Field label="Posição inicial (R$)" value={reserveInitial} onChangeText={setReserveInitial} editable={!busy && !saving} keyboardType="decimal-pad" placeholder="0,00" /><Field label="Data (AAAA-MM-DD)" value={reserveDate} onChangeText={setReserveDate} editable={!busy && !saving} /><ErrorBox message={error} /><Action title={saving ? 'Salvando…' : 'Criar caixinha'} disabled={busy || saving || !reserveName.trim() || !reserveModality.trim()} onPress={() => void createReserve()} /></View>}
    </Card>
    <Card>
      <Text style={styles.heading}>Cartões e faturas</Text>
      <Text style={styles.small}>Compras entram como despesa uma vez. Pagar a fatura reduz a conta e a dívida, sem contar outra despesa.</Text>
      {!cards.length && <Text style={styles.small}>Nenhum cartão cadastrado.</Text>}
      {cards.map(card => <View key={card.id} style={{ borderTopWidth: 1, borderColor: colors.line, paddingTop: 10, gap: 7 }}>
        <Text style={styles.label}>{card.name}</Text><Text style={styles.small}>Fecha dia {card.closingDay} · vence dia {card.dueDay} · faturas em aberto {displayMoney(card.debtCents)}</Text>
        <View style={styles.wrap}><Action secondary title={purchaseCardId === card.id ? 'Fechar compra' : 'Registrar compra'} disabled={busy || saving} onPress={() => { setPurchaseCardId(value => value === card.id ? null : card.id); setPurchaseAmount(''); setPurchaseDate(today()); setPurchaseDescription(''); setPurchaseNotes(''); setPurchaseClassification({ categoryId: null, category: 'A classificar', tagIds: [] }); setError(null); }} /></View>
        {purchaseCardId === card.id && <View style={{ gap: 8 }}><Field label="Valor da compra (R$)" value={purchaseAmount} onChangeText={setPurchaseAmount} editable={!busy && !saving} keyboardType="decimal-pad" /><Field label="Data da compra (AAAA-MM-DD)" value={purchaseDate} onChangeText={setPurchaseDate} editable={!busy && !saving} /><Field label="Descrição" value={purchaseDescription} onChangeText={setPurchaseDescription} editable={!busy && !saving} /><ClassificationPanel native={native} busy={busy || saving} run={run} selection={purchaseClassification} onChange={setPurchaseClassification} /><Field label="Observação opcional" value={purchaseNotes} onChangeText={setPurchaseNotes} editable={!busy && !saving} /><Action title={saving ? 'Salvando…' : 'Registrar compra no cartão'} disabled={busy || saving || !purchaseAmount.trim() || !purchaseDescription.trim()} onPress={() => void createPurchase(card)} /></View>}
      </View>)}
      <Action secondary title={cardForm ? 'Fechar cadastro' : '＋ Novo cartão'} disabled={busy || saving} onPress={() => { setCardForm(value => !value); setError(null); }} />
      {cardForm && <View style={{ gap: 8 }}><Field label="Nome do cartão" value={cardName} onChangeText={setCardName} editable={!busy && !saving} /><Field label="Dia de fechamento (1 a 28)" value={closingDay} onChangeText={setClosingDay} editable={!busy && !saving} keyboardType="number-pad" /><Field label="Dia de vencimento (1 a 28)" value={dueDay} onChangeText={setDueDay} editable={!busy && !saving} keyboardType="number-pad" /><Action title={saving ? 'Salvando…' : 'Criar cartão'} disabled={busy || saving || !cardName.trim() || !closingDay || !dueDay} onPress={() => void createCard()} /></View>}
    </Card>
    <Card>
      <Text style={styles.heading}>Faturas e dívidas existentes</Text>
      <Text style={styles.small}>Inclua apenas dívidas que ainda não estão nas compras do app. Isso não cria uma despesa nova.</Text>
      {!commitments.length && <Text style={styles.small}>Nenhuma fatura ou dívida em aberto.</Text>}
      {commitments.map(commitment => <CommitmentRow key={commitment.id} commitment={commitment} busy={busy || saving} displayMoney={displayMoney} open={paymentId === commitment.id} paymentAmount={valuesHidden && paymentAmountPrefilled.current ? '' : paymentAmount} setPaymentAmount={editPaymentAmount} setPaymentDate={setPaymentDate} paymentDate={paymentDate} onToggle={() => { setPaymentId(value => value === commitment.id ? null : commitment.id); paymentAmountPrefilled.current = !valuesHidden; setPaymentAmount(valuesHidden ? '' : moneyInput(commitment.remainingCents)); setPaymentDate(today()); setError(null); }} onPay={() => void payCommitment(commitment)} />)}
      <Action secondary title={commitmentForm ? 'Fechar dívida' : 'Registrar dívida existente'} disabled={busy || saving} onPress={() => { setCommitmentForm(value => !value); setError(null); }} />
      {commitmentForm && <View style={{ gap: 8 }}><Field label="Descrição" value={commitmentDescription} onChangeText={setCommitmentDescription} editable={!busy && !saving} /><Field label="Vencimento (AAAA-MM-DD)" value={commitmentDate} onChangeText={setCommitmentDate} editable={!busy && !saving} /><Field label="Valor inicial da dívida (R$)" value={commitmentAmount} onChangeText={setCommitmentAmount} editable={!busy && !saving} keyboardType="decimal-pad" /><Text style={styles.small}>Opcional: relacione a um cartão já cadastrado.</Text><View style={styles.wrap}><Chip title="Outra dívida" active={!commitmentCardId} onPress={() => setCommitmentCardId(null)} />{cards.map(card => <Chip key={card.id} title={card.name} active={commitmentCardId === card.id} onPress={() => setCommitmentCardId(card.id)} />)}</View><Action title={saving ? 'Salvando…' : 'Salvar dívida existente'} disabled={busy || saving || !commitmentDescription.trim() || !commitmentAmount.trim()} onPress={() => void createCommitment()} /></View>}
    </Card>
  </View>;
}

function ReserveRow({ reserve, reserves, busy, valuesHidden, displayMoney, open, onToggle, direction, setDirection, moveAmount, setMoveAmount, moveDate, setMoveDate, transferAmount, setTransferAmount, transferDate, setTransferDate, transferTargetId, setTransferTargetId, updateAmount, setUpdateAmount, updateDate, setUpdateDate, updateReason, setUpdateReason, onMove, onTransfer, onUpdate }: {
  reserve: FinanceReserve; reserves: FinanceReserve[]; busy: boolean; valuesHidden: boolean; displayMoney: (amountCents: number) => string; open: boolean; onToggle: () => void; direction: Direction; setDirection: (value: Direction) => void;
  moveAmount: string; setMoveAmount: (value: string) => void; moveDate: string; setMoveDate: (value: string) => void;
  transferAmount: string; setTransferAmount: (value: string) => void; transferDate: string; setTransferDate: (value: string) => void; transferTargetId: string | null; setTransferTargetId: (value: string | null) => void;
  updateAmount: string; setUpdateAmount: (value: string) => void; updateDate: string; setUpdateDate: (value: string) => void;
  updateReason: string; setUpdateReason: (value: string) => void; onMove: () => void; onTransfer: () => void; onUpdate: () => void;
}) {
  const [mode, setMode] = useState<ReserveAction>('move');
  const delta = (() => { try { return parseBRL(updateAmount) - reserve.balanceCents; } catch { return null; } })();
  return <View style={{ borderTopWidth: 1, borderColor: colors.line, paddingTop: 10, gap: 7 }}>
    <Text style={styles.label}>{reserve.name}</Text><Text style={styles.small}>{reserve.modality} · atualizada {reserve.updatedOn}</Text><Text style={styles.body}>{displayMoney(reserve.balanceCents)}</Text>
    <View style={styles.wrap}><Action secondary title={open ? 'Fechar ações' : 'Movimentar ou atualizar'} disabled={busy} onPress={onToggle} /></View>
    {open && <View style={{ gap: 8 }}>
      <View style={styles.wrap}><Chip title="Conta ↔ caixinha" active={mode === 'move'} onPress={() => setMode('move')} /><Chip title="Entre caixinhas" active={mode === 'transfer'} onPress={() => setMode('transfer')} /><Chip title="Atualizar posição" active={mode === 'update'} onPress={() => setMode('update')} /></View>
      {mode === 'move' ? <><View style={styles.wrap}><Chip title="Guardar" active={direction === 'deposit'} onPress={() => setDirection('deposit')} /><Chip title="Resgatar" active={direction === 'withdraw'} onPress={() => setDirection('withdraw')} /></View><Field label="Valor (R$)" value={moveAmount} onChangeText={setMoveAmount} editable={!busy} keyboardType="decimal-pad" /><Field label="Data (AAAA-MM-DD)" value={moveDate} onChangeText={setMoveDate} editable={!busy} /><Action title={direction === 'deposit' ? 'Guardar na caixinha' : 'Resgatar para conta'} disabled={busy || !moveAmount.trim()} onPress={onMove} /></>
        : mode === 'transfer' ? <><Text style={styles.small}>Transferir saldo entre caixinhas não altera a conta.</Text><View style={styles.wrap}>{reserves.filter(target => target.id !== reserve.id).map(target => <Chip key={target.id} title={target.name} active={transferTargetId === target.id} onPress={() => setTransferTargetId(target.id)} />)}</View>{reserves.length < 2 && <Text style={styles.small}>Cadastre outra caixinha para transferir.</Text>}<Field label="Valor (R$)" value={transferAmount} onChangeText={setTransferAmount} editable={!busy} keyboardType="decimal-pad" /><Field label="Data (AAAA-MM-DD)" value={transferDate} onChangeText={setTransferDate} editable={!busy} /><Action title="Transferir entre caixinhas" disabled={busy || !transferTargetId || !transferAmount.trim()} onPress={onTransfer} /></>
        : <><Field label="Nova posição (R$)" value={updateAmount} onChangeText={setUpdateAmount} editable={!busy} keyboardType="decimal-pad" /><Field label="Data (AAAA-MM-DD)" value={updateDate} onChangeText={setUpdateDate} editable={!busy} /><Field label="Motivo" value={updateReason} onChangeText={setUpdateReason} editable={!busy} /><Text style={styles.small}>Diferença registrada: {delta === null ? '—' : valuesHidden ? '••••' : formatBRL(delta)}. Não é receita nem despesa.</Text><Action title="Atualizar posição" disabled={busy || !updateAmount.trim() || !updateReason.trim()} onPress={onUpdate} /></>}
    </View>}
  </View>;
}

function CommitmentRow({ commitment, busy, displayMoney, open, paymentAmount, setPaymentAmount, paymentDate, setPaymentDate, onToggle, onPay }: {
  commitment: FinanceCommitment; busy: boolean; displayMoney: (amountCents: number) => string; open: boolean; paymentAmount: string; setPaymentAmount: (value: string) => void;
  paymentDate: string; setPaymentDate: (value: string) => void; onToggle: () => void; onPay: () => void;
}) {
  return <View style={{ borderTopWidth: 1, borderColor: colors.line, paddingTop: 10, gap: 7 }}>
    <Text style={styles.label}>{commitment.description}</Text>
    <Text style={styles.small}>{commitment.cardName ? `${commitment.cardName} · fatura ${commitment.invoiceMonth ?? ''} · ` : ''}Vence {commitment.dueDate} · falta {displayMoney(commitment.remainingCents)}</Text>
    <Action secondary title={open ? 'Fechar pagamento' : 'Registrar pagamento'} disabled={busy || commitment.remainingCents <= 0} onPress={onToggle} />
    {open && <View style={{ gap: 8 }}><Field label="Valor pago (R$)" value={paymentAmount} onChangeText={setPaymentAmount} editable={!busy} keyboardType="decimal-pad" /><Field label="Data do pagamento (AAAA-MM-DD)" value={paymentDate} onChangeText={setPaymentDate} editable={!busy} /><Text style={styles.small}>Pode pagar uma parte ou o restante. Isso reduz a conta e a dívida, sem criar outra despesa.</Text><Action title="Confirmar pagamento" disabled={busy || !paymentAmount.trim()} onPress={onPay} /></View>}
  </View>;
}
