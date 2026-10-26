import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Text, View } from 'react-native';
import { Action, Card, Field, styles } from '../ui/common';
import type {
  CaptureModule, DiagnosticFields, DiagnosticResult, DiagnosticSample,
  DiagnosticSampleInfo, DiagnosticStatus,
} from './native';

type Props = {
  native: CaptureModule;
  busy: boolean;
  run: (task: () => Promise<unknown>) => Promise<void>;
};
type PrivateReviewState = {
  sample: DiagnosticSample | null;
  fields: DiagnosticFields | null;
  password: string;
  confirmation: string;
};
const emptyPrivateState = (): PrivateReviewState => ({ sample: null, fields: null, password: '', confirmation: '' });
const PAGE_SIZE = 20;

const errorMessages: Record<string, string> = {
  SECURITY_CHECKS_REQUIRED: 'Execute as 40 verificações nativas isoladas nesta versão antes de iniciar uma sessão.',
  ACCESS_REQUIRED: 'Conceda acesso às notificações e aguarde a conexão do listener.',
  SOURCE_CHANGED: 'A origem instalada mudou ou ficou indisponível. Confira o aplicativo antes de iniciar outra sessão.',
  SAMPLE_LIMIT: 'O limite de 200 observações foi atingido. Pare e revise ou apague o diagnóstico.',
  REVIEW_LIMIT: 'O limite de 20 revisões por exportação foi atingido. Exclua uma revisão para selecionar outra.',
  REVIEW_SENSITIVE: 'Ainda há identificadores ou valores reais reconhecidos no texto. Revise os campos.',
  REVIEW_REQUIRED: 'A confirmação da revisão é necessária.',
  NO_REVIEWED_SAMPLES: 'Revise e confirme pelo menos uma amostra antes de exportar.',
  SAMPLE_MISSING: 'A amostra não está disponível; pode ter expirado pela retenção.',
  TEXT_LIMIT: 'Cada campo aceita até 4096 caracteres; no total, 16 mil.',
  DIAGNOSTIC_STORAGE: 'A caixa de diagnóstico está indisponível. Os arquivos foram preservados.',
  RETENTION_BLOCKED: 'A retenção da caixa sintética está bloqueada. Resolva o estado nas opções de caixas.',
  SOURCE_MISSING: 'O app de origem não está instalado.',
  SOURCE_DISABLED: 'Ative o recebimento de notificações no app de origem.',
  SIGNER_MISMATCH: 'A assinatura instalada mudou. Confira o app de origem antes de continuar.',
  CONSENT_REQUIRED: 'A confirmação de consentimento é necessária.',
  LIMIT_REACHED: 'O limite deste diagnóstico foi atingido.',
  EXPIRED: 'A janela de diagnóstico expirou. Inicie outra sessão se quiser continuar.',
  PASSWORD_LENGTH: 'Use uma senha de 12 a 128 caracteres.',
  INVALID_EXPORT: 'O arquivo não é uma exportação revisada de diagnóstico válida.',
  INVALID_REVIEW: 'A revisão contém texto não permitido. Confira os campos e revise novamente.',
  TOO_LARGE: 'O conteúdo excede o limite do diagnóstico.',
  STORAGE: 'O armazenamento local do diagnóstico está indisponível.',
  AUTH_FAILED: 'A senha está incorreta ou o arquivo foi alterado.',
  IO: 'Não foi possível concluir a operação de arquivo.',
};

function failureMessage(code?: string) {
  return code ? `${errorMessages[code] ?? 'Operação do diagnóstico não concluída.'} (${code})` : 'Operação do diagnóstico não concluída.';
}

function blank(value: string) { return value.length === 0 ? '—' : value; }

export function DiagnosticPanel({ native, busy, run }: Props) {
  const [status, setStatus] = useState<DiagnosticStatus | null>(null);
  const [samples, setSamples] = useState<DiagnosticSampleInfo[]>([]);
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [privateRevision, setPrivateRevision] = useState(0);
  const privateState = useRef<PrivateReviewState>(emptyPrivateState());
  const mounted = useRef(false);
  const privateGeneration = useRef(0);

  const clearPrivateState = useCallback((rerender = true) => {
    privateGeneration.current += 1;
    privateState.current = emptyPrivateState();
    if (rerender && mounted.current) setPrivateRevision(value => value + 1);
  }, []);

  const refresh = useCallback(async () => {
    try {
      const current = await native.diagnosticSnapshot();
      if (!mounted.current) return;
      setStatus(current);
      setError(current.status === 'blocked' && current.failureCode ? failureMessage(current.failureCode) : null);
      const result = await native.diagnosticSamples();
      if (!mounted.current) return;
      if (result.status === 'error') {
        setSamples([]);
        setError(failureMessage(result.code));
      } else {
        setSamples(result.samples ?? []);
        setVisibleCount(PAGE_SIZE);
      }
    } catch {
      if (mounted.current) setError('Não foi possível atualizar o diagnóstico. Tente novamente.');
    }
  }, [native]);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    const subscription = AppState.addEventListener('change', next => {
      if (next === 'active') void refresh();
      else clearPrivateState();
    });
    return () => {
      subscription.remove();
      mounted.current = false;
      clearPrivateState(false);
    };
  }, [clearPrivateState, refresh]);

  async function perform(task: () => Promise<DiagnosticResult>, onSuccess?: (result: DiagnosticResult) => void) {
    setError(null);
    setMessage(null);
    await run(async () => {
      try {
        const result = await task();
        if (result.status === 'error') setError(failureMessage(result.code));
        else {
          setError(null);
          onSuccess?.(result);
        }
      } catch {
        setError('Não foi possível concluir a operação do diagnóstico.');
      }
      await refresh();
    });
  }

  function confirmStart() {
    const signer = status?.source.signer;
    if (!signer) {
      setError('A assinatura instalada não pôde ser conferida localmente; o diagnóstico não foi iniciado.');
      return;
    }
    Alert.alert(
      'Iniciar diagnóstico temporário?',
      'O app de origem poderá fornecer notificações por até 24 horas ou 200 observações, com retenção local de até 7 dias. O conteúdo não é convertido em lançamentos nem usado para calcular saldo. O Android pode ocultar parte do texto; a captura não é garantida. Você pode parar antes, sem apagar os registros já coletados.',
      [
        { text: 'Cancelar', style: 'cancel' },
        { text: 'Consentir e iniciar', onPress: () => void perform(() => native.startDiagnostic(signer)) },
      ]
    );
  }

  function confirmStop() {
    Alert.alert('Parar o diagnóstico?', 'Interrompe novas observações. Os registros já coletados permanecem disponíveis até a retenção ou remoção explícita.', [
      { text: 'Continuar', style: 'cancel' },
      { text: 'Parar', style: 'destructive', onPress: () => void perform(() => native.stopDiagnostic()) },
    ]);
  }

  function confirmClear() {
    Alert.alert('Apagar diagnóstico?', 'Remove somente os registros temporários deste diagnóstico. Isso não apaga caixas sintéticas nem backups portáteis.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Apagar diagnóstico', style: 'destructive', onPress: () => void perform(() => native.clearDiagnostic(), () => {
        clearPrivateState();
        setMessage('Registros temporários do diagnóstico apagados.');
      }) },
    ]);
  }

  async function openSample(info: DiagnosticSampleInfo) {
    setError(null);
    setMessage(null);
    const generation = privateGeneration.current;
    await run(async () => {
      try {
        const result = await native.diagnosticSample(info.id);
        if(!mounted.current || AppState.currentState !== 'active' || generation !== privateGeneration.current) return;
        if (result.status === 'error' || !result.sample) {
          setError(failureMessage(result.code));
          return;
        }
        privateState.current = {
          sample: result.sample,
          fields: { ...result.sample.suggestion, textLines: [...result.sample.suggestion.textLines] },
          password: '', confirmation: '',
        };
        setPrivateRevision(value => value + 1);
      } catch {
        setError('Não foi possível abrir esta observação.');
      }
      await refresh();
    });
  }

  function editField(field: keyof DiagnosticFields, value: string) {
    const fields = privateState.current.fields;
    if (!fields) return;
    privateState.current.fields = field === 'textLines'
      ? { ...fields, textLines: value.split('\n') }
      : { ...fields, [field]: value };
    setPrivateRevision(revision => revision + 1);
  }

  function submitReview() {
    const opened = privateState.current.sample;
    const fields = privateState.current.fields;
    if (!opened || !fields) return;
    Alert.alert(
      'Confirmar revisão manual?',
      'Confirme que removeu nomes, estabelecimentos, contas e outros dados que possam identificar alguém. As sugestões automáticas não garantem anonimização.',
      [
        { text: 'Voltar e revisar', style: 'cancel' },
        { text: 'Confirmo: revisar', onPress: () => {
          let fieldsJson = JSON.stringify(fields);
          void perform(() => native.reviewDiagnostic(opened.id, fieldsJson, true), result => {
            if (result.status === 'ok' || result.status === 'saved') {
              clearPrivateState();
              setMessage('Revisão salva somente no armazenamento local do diagnóstico.');
            }
          }).finally(() => { fieldsJson = ''; });
        } },
      ]
    );
  }

  function confirmExclude(info: DiagnosticSampleInfo) {
    Alert.alert('Excluir esta revisão?', 'Remove somente o texto revisado. A observação original do diagnóstico permanece até a retenção ou remoção explícita.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Excluir revisão', style: 'destructive', onPress: () => void perform(() => native.excludeDiagnosticReview(info.id), () => {
        if (privateState.current.sample?.id === info.id) clearPrivateState();
        setMessage('Texto revisado excluído; a observação original permanece.');
      }) },
    ]);
  }

  function exportReviewed() {
    const password = privateState.current.password;
    const confirmation = privateState.current.confirmation;
    if (password.length < 12 || password.length > 128 || password !== confirmation) {
      setError('Use uma senha de 12 a 128 caracteres e confirme-a antes de exportar.');
      return;
    }
    clearPrivateState();
    void perform(() => native.exportDiagnostic(password), result => {
      if (result.status === 'saved') setMessage('Arquivo local cifrado salvo. Inclui apenas revisões aprovadas; não foi compartilhado nem enviado.');
      else if (result.status === 'cancelled') setMessage('Seleção cancelada; nenhum arquivo foi gravado pelo app.');
    }).finally(() => { /* the password state was cleared before the native call */ });
  }

  const opened = privateState.current.sample;
  const fields = privateState.current.fields;
  const visibleSamples = samples.slice(0, visibleCount);
  const source = status?.source;
  void privateRevision;

  return <>
    <Card>
      <Text style={styles.heading}>Diagnóstico temporário de notificações</Text>
      <Text style={styles.body}>Captura texto de notificações do app selecionado apenas para revisão local. Não interpreta transações, não cria lançamentos e não altera saldo. Nomes, lojas, locais e frases devem ser revisados manualmente; sugestões automáticas não garantem anonimização.</Text>
      <Text style={styles.small}>Android pode ocultar campos ou fornecer notificações incompletas. Não há garantia de captura integral.</Text>
      {source && <View style={{ gap: 4 }}>
        <Text style={styles.small}>Origem: {source.installed ? 'instalada' : 'não instalada'} · aplicativo {source.enabled ? 'ativado' : 'desativado'} · {source.label || source.packageName}</Text>
        <Text style={styles.small}>Pacote: {source.packageName} · versão: {source.versionName ?? 'indisponível'}</Text>
        <Text style={styles.small}>Assinatura local: {source.signer ?? 'indisponível'}. Conferida no aparelho; não representa validação de certificado oficial.</Text>
      </View>}
      <Text style={status?.status === 'blocked' ? styles.warning : styles.small}>
        Estado: {status?.status ?? 'verificando'} · sessão: {status?.active ? 'ativa' : 'parada'} · autorização: {status?.authorized ? 'concedida' : 'não concedida'} · listener: {status?.connected ? 'conectado' : 'desconectado'}
        {status?.startedAt ? ` · iniciou ${new Date(status.startedAt).toLocaleString()}` : ''}{status?.expiresAt ? ` · expira ${new Date(status.expiresAt).toLocaleString()}` : ''}
      </Text>
      {status?.failureCode && <Text style={styles.warning}>{failureMessage(status.failureCode)}</Text>}
      {error && <Text accessibilityRole="alert" style={styles.error}>{error}</Text>}
      {message && <Text style={styles.small}>{message}</Text>}
      <View style={styles.wrap}>
        <Action title="Atualizar diagnóstico" secondary disabled={busy} onPress={() => void refresh()} />
        {status?.active
          ? <Action title="Parar coleta (manter registros)" secondary disabled={busy} onPress={confirmStop} />
          : <Action title="Consentir e iniciar até 24 horas" disabled={busy || !status || !status.securityChecksPassed || !status.source.installed || !status.source.enabled || !status.source.signer || !status.authorized || status.status === 'blocked' || status.limitReached} onPress={confirmStart} />}
        <Action title="Apagar registros temporários" secondary disabled={busy || !status || (status.count === 0 && status.status !== 'blocked')} onPress={confirmClear} />
      </View>
      {!status?.securityChecksPassed && <Text style={styles.warning}>Antes da coleta real, execute “Executar verificações nativas isoladas” abaixo e confira as 40 aprovadas. Para iniciar outra sessão após encerrar o processo, execute a bateria novamente.</Text>}
      {!status?.authorized && <Text style={styles.small}>Conceda o acesso ao listener nas permissões do Android antes de iniciar a coleta.</Text>}
      <Text style={styles.small}>Limites: até 24 horas, 200 observações e retenção de 7 dias. Parar não apaga. A origem não é tratada como confiável só por estar instalada.</Text>
    </Card>

    <Card>
      <Text style={styles.heading}>Observações para revisão</Text>
      <Text style={styles.small}>{status?.count ?? samples.length} coletadas · {status?.reviewedCount ?? samples.filter(sample => sample.hasReview).length} revisadas · exibindo {visibleSamples.length} de {samples.length} metadados.</Text>
      {visibleSamples.length === 0 ? <Text style={styles.small}>Nenhuma observação disponível.</Text> : visibleSamples.map(info => <View key={info.id} style={{ gap: 6, borderTopWidth: 1, borderColor: '#DCE3D9', paddingTop: 10 }}>
        <Text style={styles.small}>Revisão {info.revision} · capturada {new Date(info.capturedAt).toLocaleString()} · publicada {new Date(info.publishedAt).toLocaleString()}</Text>
        <Text style={styles.small}>{info.isSummary ? 'Resumo de grupo' : 'Notificação individual'} · {info.truncated ? 'texto truncado' : 'sem truncamento pelo FFortuna'} · {info.hasText ? 'contém texto' : 'sem texto'} · {info.hasReview ? 'revisada' : 'não revisada'}</Text>
        <View style={styles.wrap}>
          <Action title="Abrir e revisar" secondary disabled={busy} onPress={() => void openSample(info)} />
          {info.hasReview && <Action title="Excluir revisão" secondary disabled={busy} onPress={() => confirmExclude(info)} />}
        </View>
      </View>)}
      {samples.length > visibleCount && <Action title="Mostrar mais" secondary disabled={busy} onPress={() => setVisibleCount(count => count + PAGE_SIZE)} />}
    </Card>

    {opened && fields && <Card>
      <Text style={styles.heading}>Original da observação · exibido após solicitação</Text>
      <Text style={styles.label}>Título</Text><Text selectable={false} style={styles.body}>{blank(opened.original.title)}</Text>
      <Text style={styles.label}>Texto</Text><Text selectable={false} style={styles.body}>{blank(opened.original.text)}</Text>
      <Text style={styles.label}>Texto expandido</Text><Text selectable={false} style={styles.body}>{blank(opened.original.bigText)}</Text>
      <Text style={styles.label}>Subtexto</Text><Text selectable={false} style={styles.body}>{blank(opened.original.subText)}</Text>
      <Text style={styles.label}>Linhas</Text><Text selectable={false} style={styles.body}>{blank(opened.original.textLines.join('\n'))}</Text>
      <Text style={styles.heading}>Sugestão editável</Text>
      <Text style={styles.small}>Revise todos os campos manualmente. A sugestão é apenas um auxílio e pode deixar identificadores no texto.</Text>
      <Field label="Título" value={fields.title} onChangeText={value => editField('title', value)} editable={!busy} autoCorrect={false} autoComplete="off" importantForAutofill="no" autoCapitalize="sentences" maxLength={4096} />
      <Field label="Texto" value={fields.text} onChangeText={value => editField('text', value)} editable={!busy} multiline autoCorrect={false} autoComplete="off" importantForAutofill="no" autoCapitalize="sentences" maxLength={4096} />
      <Field label="Texto expandido" value={fields.bigText} onChangeText={value => editField('bigText', value)} editable={!busy} multiline autoCorrect={false} autoComplete="off" importantForAutofill="no" autoCapitalize="sentences" maxLength={4096} />
      <Field label="Subtexto" value={fields.subText} onChangeText={value => editField('subText', value)} editable={!busy} autoCorrect={false} autoComplete="off" importantForAutofill="no" autoCapitalize="sentences" maxLength={4096} />
      <Field label="Linhas de texto (uma por linha)" value={fields.textLines.join('\n')} onChangeText={value => editField('textLines', value)} editable={!busy} multiline autoCorrect={false} autoComplete="off" importantForAutofill="no" autoCapitalize="sentences" maxLength={4096} />
      <View style={styles.wrap}>
        <Action title="Salvar revisão após confirmação" disabled={busy} onPress={submitReview} />
        <Action title="Fechar e limpar texto" secondary disabled={busy} onPress={() => clearPrivateState()} />
      </View>
    </Card>}

    <Card>
      <Text style={styles.heading}>Exportar somente revisões</Text>
      <Text style={styles.body}>Gera um arquivo .ffortuna-samples cifrado localmente. Inclui apenas campos revisados e confirmados. Não compartilha nem envia o arquivo. Este formato é independente dos backups da caixa sintética.</Text>
      <Field label="Senha (12 a 128 caracteres)" value={privateState.current.password} onChangeText={value => { privateState.current.password = value; setPrivateRevision(revision => revision + 1); }} editable={!busy} secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={128} />
      <Field label="Confirmar senha" value={privateState.current.confirmation} onChangeText={value => { privateState.current.confirmation = value; setPrivateRevision(revision => revision + 1); }} editable={!busy} secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={128} />
      <Action title="Conferir arquivo exportado" secondary disabled={busy || !!opened || privateState.current.password.length < 12} onPress={() => {
        const secret=privateState.current.password;clearPrivateState();
        void perform(() => native.verifyDiagnosticExport(secret), result => {
          if(result.status==='verified') setMessage(`Arquivo autenticado: ${result.count ?? 0} amostras revisadas. Nenhum registro foi importado ou alterado.`);
          else if(result.status==='cancelled') setMessage('Seleção cancelada.');
        });
      }} />
      <Action title="Exportar revisões cifradas" disabled={busy || !!opened || privateState.current.password.length < 12 || privateState.current.password.length > 128 || privateState.current.password !== privateState.current.confirmation || (status?.reviewedCount ?? 0) === 0} onPress={exportReviewed} />
    </Card>
  </>;
}
