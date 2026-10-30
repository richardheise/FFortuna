import { useEffect, useRef, useState } from 'react';
import { AppState, Text, View } from 'react-native';
import { Action, Card, Chip, ErrorBox, Field, styles } from '../ui/common';
import type { CaptureModule, ClassificationItem, ClassificationResult } from './native';

type Selection = { categoryId: string | null; category: string; tagIds: string[] };
type Props = {
  native: CaptureModule; busy: boolean; run: (task: () => Promise<unknown>) => Promise<void>;
  selection?: Selection; onChange?: (value: Selection) => void;
};
const errors: Record<string, string> = {
  CLASSIFICATION_DUPLICATE_NAME: 'Esse nome já existe, inclusive entre as opções arquivadas.',
  CLASSIFICATION_INVALID_NAME: 'Use um nome de 1 a 80 caracteres, sem caracteres de controle.',
  CLASSIFICATION_ITEM_LIMIT: 'O limite é de 200 categorias e tags no total.',
  CLASSIFICATION_ITEM_MISSING: 'A opção não está disponível. Reabra o gerenciamento.',
};

export function ClassificationPanel({ native, busy, run, selection, onChange }: Props) {
  const [items, setItems] = useState<ClassificationItem[]>([]);
  const [ready, setReady] = useState(false);
  const [manage, setManage] = useState(!selection);
  const [kind, setKind] = useState<ClassificationItem['kind']>('category');
  const [name, setName] = useState('');
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const alive = useRef(false);
  const selectionRef = useRef(selection); selectionRef.current = selection;
  const onChangeRef = useRef(onChange); onChangeRef.current = onChange;

  function apply(result: ClassificationResult) {
    if (result.status !== 'ok' || !result.items) {
      setError(errors[result.code ?? ''] ?? 'Não foi possível acessar as classificações. Os dados foram preservados.');
      return false;
    }
    setItems(result.items); setReady(true); setError(null);
    const selected = selectionRef.current;
    const category = result.items.find(item => item.id === selected?.categoryId);
    if (selected && category && category.name !== selected.category) onChangeRef.current?.({ ...selected, category: category.name });
    return true;
  }
  useEffect(() => {
    alive.current = true;
    async function load() {
      const request = ++generation.current;
      try {
        const result = await native.classifications();
        if (alive.current && generation.current === request) apply(result);
      } catch { if (alive.current && generation.current === request) setError('Não foi possível carregar as classificações.'); }
    }
    void load();
    const listener = AppState.addEventListener('change', state => {
      generation.current += 1;
      setItems([]);setName('');setEditing(null);setReady(false);setError(null);
      if (state === 'active') void load();
    });
    return () => { alive.current = false; generation.current += 1; listener.remove(); };
  }, [native]);

  function mutate(task: () => Promise<ClassificationResult>) {
    const request = generation.current;
    void run(async () => {
      try {
        const result = await task();
        if (alive.current && generation.current === request && apply(result)) { setName('');setEditing(null); }
      } catch { if (alive.current && generation.current === request) setError('Não foi possível salvar a classificação.'); }
    });
  }
  const disabled = busy || !ready;
  function toggleTag(id: string) {
    if (disabled || !selection || !onChange) return;
    const tagIds = selection.tagIds.includes(id) ? selection.tagIds.filter(value => value !== id) : [...selection.tagIds, id];
    if (tagIds.length > 20) { setError('Selecione até 20 tags por revisão.'); return; }
    onChange({ ...selection, tagIds });
  }
  return <Card>
    <Text style={styles.heading}>Categorias e tags</Text>
    <ErrorBox message={error} />
    {!ready && !error && <Text style={styles.small}>Carregando opções…</Text>}
    {selection && <>
      <Text style={styles.label}>Categoria principal</Text>
      {!selection.categoryId && selection.category && selection.category !== 'A classificar' && <Text style={styles.small}>Texto da revisão anterior: {selection.category}. Escolha uma opção para vinculá-la ao catálogo.</Text>}
      <View style={styles.wrap}>
        <Chip title="A classificar" active={!selection.categoryId} onPress={() => { if (!disabled) onChange?.({ ...selection, categoryId: null, category: 'A classificar' }); }} />
        {items.filter(item => item.kind === 'category' && (!item.archived || item.id === selection.categoryId)).map(item =>
          <Chip key={item.id} title={`${item.name}${item.archived ? ' (arquivada)' : ''}`} active={selection.categoryId === item.id} onPress={() => { if (!disabled && !item.archived) onChange?.({ ...selection, categoryId: item.id, category: item.name }); }} />)}
      </View>
      <Text style={styles.label}>Tags opcionais · várias por revisão</Text>
      <View style={styles.wrap}>{items.filter(item => item.kind === 'tag' && (!item.archived || selection.tagIds.includes(item.id))).map(item =>
        <Chip key={item.id} title={`${item.name}${item.archived ? ' (arquivada)' : ''}`} active={selection.tagIds.includes(item.id)} onPress={() => { if (!item.archived || selection.tagIds.includes(item.id)) toggleTag(item.id); }} />)}</View>
      {!items.some(item => item.kind === 'tag' && !item.archived) && <Text style={styles.small}>Crie tags como Viagem ou Trabalho no gerenciamento abaixo.</Text>}
      <Action secondary title={manage ? 'Fechar gerenciamento' : 'Gerenciar categorias e tags'} disabled={busy} onPress={() => setManage(value => !value)} />
    </>}
    {manage && <>
      <Text style={styles.small}>Opções cifradas neste aparelho. Arquivar preserva os vínculos e impede novas seleções. Apagar o diagnóstico não apaga o catálogo. Ainda não há backup deste catálogo; desinstalar o app o remove.</Text>
      <View style={styles.wrap}>{(['category', 'tag'] as const).map(value => <Chip key={value} title={value === 'category' ? 'Categorias' : 'Tags'} active={kind === value} onPress={() => { if (!busy) { setKind(value);setEditing(null);setName(''); } }} />)}</View>
      <Field label={editing ? 'Novo nome' : kind === 'category' ? 'Nome da nova categoria' : 'Nome da nova tag'} value={name} onChangeText={setName} editable={!disabled} maxLength={80} autoCorrect={false} autoComplete="off" importantForAutofill="no" />
      <Action title={editing ? 'Salvar nome' : kind === 'category' ? 'Criar categoria' : 'Criar tag'} disabled={disabled || !name.trim()} onPress={() => mutate(() => editing ? native.renameClassification(editing, name) : native.createClassification(kind, name))} />
      {editing && <Action secondary title="Cancelar renomeação" disabled={busy} onPress={() => { setEditing(null);setName(''); }} />}
      {items.filter(item => item.kind === kind).map(item => <View key={item.id} style={{ gap: 6 }}>
        <Text style={styles.label}>{item.name}{item.archived ? ' · arquivada' : ''}</Text>
        <View style={styles.wrap}>
          <Action secondary title="Renomear" disabled={disabled} onPress={() => { setEditing(item.id);setName(item.name); }} />
          <Action secondary title={item.archived ? 'Reativar' : 'Arquivar'} disabled={disabled} onPress={() => mutate(() => native.archiveClassification(item.id, !item.archived))} />
        </View>
      </View>)}
    </>}
  </Card>;
}
