import { Text } from '../ui/Typography';
import { View } from 'react-native';
import { Action, Card, colors, styles } from '../ui/common';
import type { DailyState } from './native';

type Props = {
  state: DailyState | null;
  busy: boolean;
  onActivate: () => void;
  onPause: () => void;
  onPermission: () => void;
  onReconnect: () => void;
  onImport: () => void;
  onBank: () => void;
  onReceive: () => void;
  onDismissShared: () => void;
  onManual: () => void;
  onReview: () => void;
};

function displayDay(value: string): string {
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  if (dateOnly) return `${dateOnly[3]}/${dateOnly[2]}/${dateOnly[1]}`;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Data indisponível' : date.toLocaleDateString('pt-BR');
}

export function SourcesPanel({ state, busy, onActivate, onPause, onPermission, onReconnect, onImport, onBank, onReceive, onDismissShared, onManual, onReview }: Props) {
  const source = state?.source;
  const sourceName = source?.label?.trim() || 'Nubank';
  const enabled = !!state && state.active && state.mode === 'daily';
  const active = !!state && state.active && state.mode === 'daily' && state.authorized && state.connected;
  const missingSetup = !state || !source?.installed || !source.enabled || !source.signer || !state.authorized || !state.connected;
  const blocked = state?.status === 'blocked' || state?.status === 'error' || (!!state && (!source?.installed || !source.enabled || !source.signer || !state.authorized));
  const captureStatus = !state
    ? 'Carregando'
    : blocked
      ? 'Bloqueada'
      : active
        ? 'Ativa'
        : state.mode === 'diagnostic' && state.active
          ? 'Diagnóstico ativo'
          : state.mode === 'daily' && state.active && !state.connected
            ? 'Conectando'
            : 'Pausada';

  return <>
    <Card>
      <Text style={styles.heading}>Importar extrato</Text>
      <Text style={styles.small}>CSV ou OFX · só no aparelho</Text>
      <Text style={styles.body}>Exporte pelo Nubank e escolha o arquivo, ou compartilhe com Tique.</Text>
      <Action title="Importar extrato" disabled={busy} onPress={onImport} />
      {state?.source.installed && <Action title="Abrir Nubank" secondary disabled={busy} onPress={onBank} />}
    </Card>

    {!state?.configured && <Card>
      <Text style={styles.heading}>Comece manualmente</Text>
      <Text style={styles.small}>O extrato é opcional.</Text>
      <Action title="Começar manualmente" disabled={busy} onPress={onManual} />
    </Card>}

    <Card>
      <Text style={styles.heading}>Notificações</Text>
      <Text style={styles.body}>{sourceName} {source?.installed ? 'instalado' : 'não instalado'}</Text>
      <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}>
        <Text style={styles.small}>{state?.authorized ? 'Acesso às notificações concedido' : 'Permita o acesso às notificações'}</Text>
        {!state?.authorized && <Action secondary title="Permitir notificações" disabled={busy || !state} onPress={onPermission} />}
      </View>
      <View style={[styles.row, { justifyContent: 'space-between', flexWrap: 'wrap' }]}>
        <Text style={styles.label}>Notificações: <Text style={{ color: captureStatus === 'Ativa' ? colors.cyan : captureStatus === 'Bloqueada' ? colors.red : colors.muted }}>{captureStatus}</Text></Text>
        <Action title={enabled ? 'Pausar' : 'Ativar'} secondary={!active} disabled={busy || (!enabled && missingSetup)} onPress={enabled ? onPause : onActivate} />
      </View>
      {state?.authorized && !state.connected && <Action title="Reconectar" secondary disabled={busy} onPress={onReconnect} />}
      {state?.limitReached && <Text style={styles.warning}>A lista de notificações atingiu o limite. Seus registros foram preservados.</Text>}
    </Card>

    {state?.incomingFile && <Card>
      <Text style={styles.heading}>Arquivo compartilhado</Text>
      <Text style={styles.small}>Um arquivo compartilhado está pronto.</Text>
      <View style={styles.wrap}>
        <Action title="Receber arquivo" disabled={busy} onPress={onReceive} />
        <Action title="Dispensar" secondary disabled={busy} onPress={onDismissShared} />
      </View>
    </Card>}

    {!!state?.imports.length && <Card>
      <Text style={styles.heading}>Importações recentes</Text>
      {state.imports.slice(0, 3).map(item => <View key={item.id} style={{ borderTopWidth: 1, borderColor: colors.line, paddingTop: 10, gap: 3 }}>
        <Text style={styles.label}>{item.format.toUpperCase()} · {displayDay(item.capturedAt)}</Text>
        <Text style={styles.small}>{item.count} {item.count === 1 ? 'movimento' : 'movimentos'}</Text>
      </View>)}
      <Action title="Ver movimentos" secondary disabled={busy} onPress={onReview} />
    </Card>}
  </>;
}
