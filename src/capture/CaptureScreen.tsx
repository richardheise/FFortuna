import { useCallback, useEffect, useState } from 'react';
import { Alert, AppState, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Action, Card, ErrorBox, Field, ScreenScroll, styles } from '../ui/common';
import { captureModule, type CaptureSnapshot, type LocalRecoveryEntry, type SecurityReport } from './native';
import { DiagnosticPanel } from './DiagnosticPanel';
import { FinancePanel } from './FinancePanel';
import { LegalNotices } from '../legal/LegalNotices';

export function CaptureScreen() {
  const [native] = useState(captureModule);
  const [state, setState] = useState<CaptureSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [legalOpen, setLegalOpen] = useState(false);
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [localEntries, setLocalEntries] = useState<LocalRecoveryEntry[]>([]);
  const refresh = useCallback(async () => {
    if(!native) return;
    setState(await native.snapshot());
    if(advancedOpen) {
      try { setLocalEntries(await native.localRecoveryEntries()); }
      catch { setLocalEntries([]); }
    }
  }, [native, advancedOpen]);
  useEffect(() => {
    void refresh().catch(() => setError('Não foi possível abrir o diagnóstico nativo.'));
    const subscription = AppState.addEventListener('change', s => {
      if(s === 'active') void refresh().catch(() => setError('Falha ao atualizar o estado nativo.'));
      else { setPassword('');setConfirmation(''); }
    });
    return () => subscription.remove();
  }, [refresh]);
  async function run(task: () => Promise<unknown> | unknown) {
    if(busy) return; setBusy(true); setError(null); setMessage(null);
    try { await task(); await refresh(); }
    catch {
      setError('Operação não concluída. Confira autorização e armazenamento abaixo.');
      try { await refresh(); } catch { /* Keep the last available state. */ }
    }
    finally { setBusy(false); }
  }
  function backup(mode: 'export' | 'restore') {
    const secret = password;
    setPassword('');setConfirmation('');
    void run(async () => {
      const result = mode === 'export' ? await native!.exportBackup(secret) : await native!.restoreBackup(secret);
      if(result.status === 'error') {
        const errors: Record<string, string> = {
          PASSWORD_LENGTH: 'Use uma senha de 12 a 128 caracteres, sem caracteres inválidos.',
          INVALID_FILE: 'Arquivo incompleto ou formato de backup não suportado.',
          AUTH_FAILED: 'Senha incorreta ou arquivo alterado. Nenhuma caixa foi substituída.',
          TOO_LARGE: 'Backup excede os limites desta prova: 8 MiB ou 10 mil observações.',
          INVALID_DATA: 'Os registros do backup são inválidos ou pertencem a outro formato.',
          PREVIOUS_EXISTS: 'Já existe uma caixa anterior preservada. Volte a ela ou descarte-a antes de outra restauração.',
          IO: 'Falha ao acessar o arquivo. Confira o armazenamento local; um arquivo incompleto pode ter ficado no destino.',
          BUSY: 'Já existe uma operação de backup em andamento.',
          STORAGE: 'Não foi possível preparar a caixa cifrada. Confira o diagnóstico; não apague os dados como tentativa de reparo.',
        };
        setError(errors[result.code ?? ''] ?? 'Não foi possível concluir o backup/restauração.');
      } else if(result.status === 'saved') setMessage('Backup cifrado salvo. Guarde a senha separadamente: ela é necessária mesmo em outro aparelho.');
      else if(result.status === 'restored') setMessage(`Restauradas ${result.count ?? 0} observações em uma nova caixa. ${result.unavailable ?? 0} ficaram sem bruto e não podem ser reprocessadas. A caixa anterior foi preservada. A captura está desativada; ative-a novamente quando quiser.`);
      else setMessage('Seleção cancelada. Nenhuma caixa foi alterada.');
    });
  }
  const retentionBlocked = state?.retention?.blocked ?? true;
  const blockers = state ? [
    !state.storageReady && 'A caixa cifrada está indisponível. Confira a mensagem de erro acima.',
    retentionBlocked && 'A retenção de dados brutos está bloqueada. A captura permanece pausada até a limpeza ser verificada.',
    !state.enabled && 'Toque em “Concordo: ativar captura somente sintética” na seção 1.',
    !state.authorized && 'Toque em “Abrir acesso às notificações no Android” e conceda o acesso ao Tique.',
    !state.notificationsAllowed && 'Toque em “Permitir notificações do emissor fictício” e autorize a publicação.',
  ].filter((reason): reason is string => typeof reason === 'string') : ['Aguardando o diagnóstico. Se não carregar, toque em “Atualizar estado” na seção 1.'];
  return <SafeAreaView style={styles.page}><ScreenScroll>
    <Text style={styles.title}>Tique · {toolsOpen ? 'Ferramentas de captura' : 'Finanças'}</Text>
    <Action secondary title={toolsOpen ? 'Voltar ao livro financeiro' : 'Ferramentas de captura e verificações'} disabled={busy} onPress={() => { setPassword('');setConfirmation('');setToolsOpen(value => !value); }} />
    {native && !toolsOpen && <FinancePanel native={native} busy={busy} run={run} />}
    {toolsOpen && <Text style={styles.warning}>Exemplos abaixo são sintéticos. Diagnóstico real somente mediante ativação explícita nas configurações avançadas.</Text>}
    {state?.diagnostic?.active && <Text style={styles.warning}>Diagnóstico real ativo até {state.diagnostic.expiresAt ? new Date(state.diagnostic.expiresAt).toLocaleString() : "expirar"}. Fonte: Nubank. Pare a coleta nas configurações avançadas.</Text>}
    <ErrorBox message={error ?? state?.lastError ?? null} />
    {!native ? <Card><Text style={styles.body}>Este modo exige o APK próprio. A demonstração financeira continua disponível no Expo Go.</Text></Card> : toolsOpen && <>
      <Card><Text style={styles.heading}>1. Preparar e autorizar</Text>
        <Text style={styles.body}>O Android concede acesso amplo ao serviço. Para os exemplos abaixo, o app filtra o próprio pacote antes de ler conteúdo e aceita só fixtures marcadas. O diagnóstico real tem consentimento separado nas configurações avançadas. Fonte dos exemplos: {state?.sourcePackage ?? 'carregando'}.</Text>
        <Text style={styles.body}>Caixa cifrada: {!state ? 'verificando' : state.storageReady ? `pronta · SQLCipher ${state.cipherVersion}` : 'indisponível'}</Text>
        <Text style={styles.body}>Consentimento local: {state?.enabled ? 'ativo' : 'desativado'} · acesso Android: {state?.authorized ? 'concedido' : 'não concedido'} · conexão: {state?.connected ? 'conectado' : 'desconectado'}</Text>
        <Action title={state?.enabled ? 'Desativar captura sintética' : 'Concordo: ativar captura somente sintética'} disabled={busy || (!state?.storageReady && !state?.enabled) || (!state?.enabled && retentionBlocked)} onPress={() => void run(() => native.setEnabled(!state?.enabled))} />
        {state?.retention && <Text style={state.retention.blocked ? styles.warning : styles.small}>
          Retenção do bruto: ativa {state.retention.active?.status ?? 'indisponível'}{state.retention.active?.remaining === undefined ? '' : ` · ${state.retention.active.remaining} com bruto`}; anterior {state.retention.previous?.status ?? 'indisponível'}{state.retention.previous?.remaining === undefined ? '' : ` · ${state.retention.previous.remaining} com bruto`}{state.retention.blocked ? ' · captura bloqueada até a limpeza ser verificada.' : ''}
        </Text>}
        {state && !state.retention && <Text style={styles.warning}>Estado da retenção indisponível. A ativação da captura está bloqueada até a verificação carregar.</Text>}
        <Action title="Abrir acesso às notificações no Android" secondary disabled={busy} onPress={() => void run(() => native.openSettings())} />
        <Action title="Permitir notificações do emissor fictício" secondary disabled={busy} onPress={() => void run(() => native.requestPostPermission())} />
        <Text style={styles.small}>Emissor: {state?.notificationsAllowed ? 'autorizado' : 'sem permissão'}. A permissão de publicar exemplos e o acesso para ler notificações são autorizações diferentes. O consentimento local também precisa estar ativo.</Text>
        <Action title="Atualizar estado" secondary disabled={busy} onPress={() => void run(refresh)} />
        <Text style={styles.small}>Ao voltar das configurações, o estado é atualizado. Se necessário, toque Atualizar estado.</Text>
      </Card>
      <Card><Text style={styles.heading}>2. Publicar exemplos</Text>
        <Text style={styles.body}>Cada publicação cria uma identidade própria. Atualizar reutiliza a última identidade; o resumo de grupo é ignorado. Nenhum evento altera o saldo financeiro.</Text>
        {blockers.length > 0 && <Text accessibilityRole="alert" style={styles.warning}>Para liberar os exemplos:{'\n'}{blockers.map(reason => `• ${reason}`).join('\n')}</Text>}
        {busy && <Text style={styles.small}>Operação em andamento. Aguarde para publicar outro exemplo.</Text>}
        <View style={styles.wrap}>{([
          ['single','Publicar exemplo'],['same','Duas compras iguais'],['update','Atualizar último exemplo'],['group','Dois filhos + resumo'],['delayed','Agendar exemplo com app fechado'],
        ] as const).map(([scenario, title]) => <Action key={scenario} title={title} secondary disabled={busy || blockers.length > 0} onPress={() => void run(async () => { await native.emit(scenario); if(scenario === 'delayed') setMessage('Agendado a partir de 15 segundos; pode atrasar por política do Android. Vá à tela inicial, bloqueie a tela e aguarde. Não use force-stop.'); })} />)}</View>
        <Text style={styles.small}>Após publicar, aguarde e atualize. A gravação ocorre em Kotlin, independentemente desta tela e do JavaScript. A prova não promete captura sem perdas.</Text>
      </Card>
      <Card><Text style={styles.heading}>3. Recuperar e conferir</Text>
        <View style={styles.wrap}>
          <Action title="Reler notificações ativas" secondary disabled={busy} onPress={() => void run(() => native.replay())} />
          <Action title="Reprocessar pendências" secondary disabled={busy} onPress={() => void run(() => native.recover())} />
          <Action title="Verificar cifra e integridade" secondary disabled={busy} onPress={() => void run(async () => { await native.verify(); setMessage('SQLCipher e integridade verificados; o arquivo principal não tem cabeçalho SQLite em claro. Isso não valida sozinho todos os arquivos nem o backup.'); })} />
        </View>
        <Text style={styles.body}>Observações persistidas: {state?.total ?? 0} · pendentes: {state?.pending ?? 0} · esquema: {state?.schemaVersion ?? '—'}</Text>
        {(state?.unavailablePending ?? 0) > 0 && <Text style={styles.warning}>{state?.unavailablePending} observações estão sem bruto por retenção ou restauração de backup e não podem ser reprocessadas. Os registros foram preservados e não entram na contagem de pendentes processáveis.</Text>}
        <Text style={styles.small}>Publicação, captura e data efetiva são campos separados. Exibindo as últimas 30 observações; dispensar uma notificação não apaga seu registro.</Text>
      </Card>
      {message && <Card><Text style={styles.body}>{message}</Text></Card>}
      <Card><Text style={styles.heading}>Recuperação local automática</Text>
        <Text style={styles.body}>O app mantém até 3 cópias cifradas dos registros sintéticos, no máximo uma a cada 24 horas quando os dados mudam. Não inclui conteúdo bruto. As cópias usam uma chave deste aparelho e são perdidas se o app for desinstalado.</Text>
        <Text style={state?.localRecovery?.status === 'blocked' ? styles.warning : styles.small}>
          Estado: {state?.localRecovery?.status ?? 'verificando'} · cópias: {state?.localRecovery?.count ?? 0}{state?.localRecovery?.latestAt ? ` · mais recente ${new Date(state.localRecovery.latestAt).toLocaleString()}` : ''}{state?.localRecovery?.failureCode ? ` · código ${state.localRecovery.failureCode}` : ''}
        </Text>
      </Card>
      <Action title={advancedOpen ? 'Fechar configurações avançadas' : 'Configurações avançadas'} secondary disabled={busy} onPress={() => { setPassword('');setConfirmation('');setAdvancedOpen(value => !value); }} />
      {advancedOpen && <>
      <DiagnosticPanel native={native} busy={busy} run={run} />
      <Card><Text style={styles.heading}>Backup portátil e caixas</Text>
        <Text style={styles.body}>Salva registros e resultados de parsing, sem os payloads brutos. Escolha uma pasta neste aparelho. O arquivo é cifrado com sua senha, independente da chave local. A senha não é salva e não tem recuperação. Salvo fora do app, este backup pode ser restaurado após desinstalação ou em outro aparelho.</Text>
        <Field label="Senha do backup (12 a 128 caracteres)" value={password} onChangeText={setPassword} secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={128} />
        <Field label="Repita a senha para criar um backup" value={confirmation} onChangeText={setConfirmation} secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="off" importantForAutofill="no" maxLength={128} />
        <Action title="Criar backup cifrado" disabled={busy || !state?.storageReady || password.length < 12 || password !== confirmation} onPress={() => backup('export')} />
        <Action title="Restaurar backup em nova caixa" secondary disabled={busy || password.length < 12 || !!state?.previousBoxAvailable} onPress={() => Alert.alert('Restaurar backup?', 'O arquivo será validado antes de ativar uma nova caixa. A caixa atual será preservada, sem mesclar registros, e a captura será desativada. A senha do primeiro campo será usada.', [{ text: 'Cancelar', style: 'cancel' }, { text: 'Escolher arquivo', onPress: () => backup('restore') }])} />
        {state?.previousBoxAvailable && <>
          <Text style={styles.warning}>Há uma caixa anterior preservada. Descarte-a explicitamente antes de restaurar outro backup. Voltar a ela troca a caixa ativa e desativa a captura.</Text>
          <Action title="Voltar à caixa anterior" secondary disabled={busy} onPress={() => Alert.alert('Trocar de caixa?', 'A caixa anterior será verificada antes da troca. A atual ficará preservada e a captura será desativada.', [{ text:'Cancelar',style:'cancel' }, { text:'Trocar',onPress:() => void run(async () => { await native.switchPreviousBox();setMessage('Caixa anterior ativada; a captura está desativada.'); }) }])} />
          <Action title="Descartar caixa anterior" secondary disabled={busy} onPress={() => Alert.alert('Descartar caixa anterior?', 'Apaga definitivamente apenas a caixa anterior e sua chave. A caixa ativa e os backups externos permanecem.', [{ text:'Cancelar',style:'cancel' },{ text:'Descartar',style:'destructive',onPress:() => void run(() => native.discardPreviousBox()) }])} />
        </>}
      </Card>
      <Card><Text style={styles.heading}>Gerenciar recuperação local</Text>
        <Text style={styles.body}>Ativar ou desativar a criação automática não apaga as cópias já existentes.</Text>
        <Action title={state?.localRecovery?.enabled ? 'Desativar cópias automáticas' : 'Ativar cópias automáticas'} secondary disabled={busy || !state?.localRecovery || (state.localRecovery.status === 'blocked' && !state.localRecovery.enabled)} onPress={() => void run(() => native.setLocalRecoveryEnabled(!state?.localRecovery?.enabled))} />
        {localEntries.length === 0 ? <Text style={state?.localRecovery?.status === 'blocked' ? styles.warning : styles.small}>{state?.localRecovery?.status === 'blocked' ? 'Lista indisponível enquanto a recuperação estiver bloqueada.' : 'Nenhuma cópia local disponível.'}</Text> : localEntries.map(entry => <View key={entry.id}>
          <Text style={styles.small}>{new Date(entry.createdAt).toLocaleString()} · {entry.count} observações</Text>
          <Action title="Restaurar esta cópia local" secondary disabled={busy || !!state?.previousBoxAvailable} onPress={() => Alert.alert('Restaurar cópia local?', 'A cópia será verificada antes de ativar uma nova caixa. A caixa atual será preservada e a captura será desativada.', [{ text: 'Cancelar', style: 'cancel' }, { text: 'Restaurar', onPress: () => void run(async () => {
            const result = await native.restoreLocalRecovery(entry.id);
            if(result.status === 'restored') setMessage(`Cópia restaurada: ${result.count ?? entry.count} observações. A captura está desativada; ative-a quando quiser.`);
            else if(result.status === 'error') setError('A cópia local não pôde ser restaurada. A caixa ativa foi preservada.');
          }) }])} />
        </View>)}
        <Action title="Apagar cópias locais e desativar" secondary disabled={busy || ((state?.localRecovery?.count ?? 0) === 0 && state?.localRecovery?.status !== 'blocked')} onPress={() => Alert.alert('Apagar cópias locais?', 'Apaga somente as cópias cifradas desta instalação e desativa a recuperação automática. As caixas ativa/anterior e os backups portáteis não serão apagados.', [{ text: 'Cancelar', style: 'cancel' }, { text: 'Apagar cópias', style: 'destructive', onPress: () => void run(() => native.clearLocalRecovery()) }])} />
      </Card>
      <Card><Text style={styles.heading}>Retenção e diagnóstico de segurança</Text>
        <Text style={state?.retention?.blocked ? styles.warning : styles.body}>
          Caixa ativa: {state?.retention?.active?.status ?? 'indisponível'} · anterior: {state?.retention?.previous?.status ?? 'indisponível'}{state?.retention?.active?.failureCode ? ` · ativa: ${state.retention.active.failureCode}` : ''}{state?.retention?.previous?.failureCode ? ` · anterior: ${state.retention.previous.failureCode}` : ''}{state?.retention?.blocked ? ' · limpeza bloqueada; captura pausada. Se a chave anterior estiver indisponível, o descarte explícito fica acima.' : ''}
        </Text>
        <Action title="Verificar retenção agora" secondary disabled={busy} onPress={() => void run(() => native.maintainRetention())} />
        <Text style={styles.small}>A bateria usa dados isolados. A checagem de logs cobre apenas uma janela do próprio processo; indisponibilidade fica como não verificado. Não habilita dados reais.</Text>
        <Action title="Executar verificações nativas isoladas" secondary disabled={busy} onPress={() => Alert.alert('Executar diagnóstico?', 'A captura será pausada. As verificações usam somente dados sintéticos isolados e não alteram a caixa ativa.', [{ text: 'Cancelar', style: 'cancel' }, { text: 'Executar', onPress: () => void run(async () => {
          const report: SecurityReport = await native.runSecurityChecks();
          setMessage(`Diagnóstico concluído: ${report.passed} aprovadas, ${report.failed} falhas e ${report.notVerified} não verificadas. A captura permanece pausada.`);
        }) }])} />
        {state?.securityReport && <>
          <Text style={styles.body}>Última execução: {new Date(state.securityReport.ranAt).toLocaleString()} · Android API {state.securityReport.sdk} · {state.securityReport.passed} aprovadas · {state.securityReport.failed} falhas · {state.securityReport.notVerified} não verificadas.</Text>
          {state.securityReport.cases.map(testCase => <Text key={testCase.id} style={testCase.status === 'failed' ? styles.warning : styles.small}>
            {testCase.status === 'passed' ? 'Aprovado' : testCase.status === 'failed' ? 'Falhou' : 'Não verificado'} · {testCase.title} ({testCase.id}){testCase.code ? ` · ${testCase.code}` : ''}
          </Text>)}
        </>}
      </Card>
      <Card><Text style={styles.heading}>Apagar caixa sintética ativa</Text>
        <Text style={styles.body}>Apaga a caixa ativa e sua chave. A caixa anterior, as cópias locais e os backups portáteis permanecem.</Text>
        <Action title="Apagar caixa ativa" secondary disabled={busy} onPress={() => Alert.alert('Apagar a caixa ativa?', 'Apaga a caixa ativa e sua chave. A caixa anterior, as cópias locais e os backups portáteis permanecem. Sem uma cópia disponível, os dados apagados não podem ser recuperados.', [{ text:'Cancelar',style:'cancel' },{ text:'Apagar',style:'destructive',onPress:() => void run(() => native.reset()) }])} />
      </Card>
      </>}
      {state?.observations?.map(item => <Card key={item.id}><Text style={styles.heading}>Revisão {item.revision} · {item.state === 'processed' ? 'interpretada' : item.payload === null ? 'sem bruto' : 'pendente'}</Text><Text selectable style={styles.small}>ID: {item.id}{'\n'}Fonte: {item.sourceKey}{'\n'}Publicada: {item.publishedAt}{'\n'}Capturada: {item.capturedAt}{'\n'}Parser: {item.parserVersion ?? 'sem resultado'}</Text><Text selectable style={styles.small}>{item.parsedJson}</Text></Card>)}
    </>}
    <Action title="Sobre e licenças" secondary onPress={() => setLegalOpen(true)} />
  </ScreenScroll><LegalNotices visible={legalOpen} onClose={() => setLegalOpen(false)} /></SafeAreaView>;
}
