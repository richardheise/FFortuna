import { useCallback, useEffect, useState } from 'react';
import { Alert, AppState, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Action, Card, ErrorBox, Field, ScreenScroll, styles } from '../ui/common';
import { captureModule, type CaptureSnapshot } from './native';
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
  const refresh = useCallback(async () => { if(native) setState(await native.snapshot()); }, []);
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
    catch { setError('Operação não concluída. Confira autorização, armazenamento e conexão abaixo. Nenhum reset foi feito.'); }
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
  const blockers = state ? [
    !state.storageReady && 'A caixa cifrada está indisponível. Confira a mensagem de erro acima.',
    !state.enabled && 'Toque em “Concordo: ativar captura somente sintética” na seção 1.',
    !state.authorized && 'Toque em “Abrir acesso às notificações no Android” e conceda o acesso ao FFortuna.',
    !state.notificationsAllowed && 'Toque em “Permitir notificações do emissor fictício” e autorize a publicação.',
  ].filter((reason): reason is string => typeof reason === 'string') : ['Aguardando o diagnóstico. Se não carregar, toque em “Atualizar estado” na seção 1.'];
  return <SafeAreaView style={styles.page}><ScreenScroll>
    <Text style={styles.title}>FFortuna · Captura nativa</Text>
    <Text style={styles.warning}>PROVA SINTÉTICA · Não monitora bancos. Não informe dados reais.</Text>
    <ErrorBox message={error ?? state?.lastError ?? null} />
    {!native ? <Card><Text style={styles.body}>Este diagnóstico exige o APK próprio. A demonstração financeira continua disponível no Expo Go.</Text></Card> : <>
      <Card><Text style={styles.heading}>1. Preparar e autorizar</Text>
        <Text style={styles.body}>O Android concede acesso amplo ao serviço, mas este APK filtra o próprio pacote antes de ler conteúdo e aceita só exemplos marcados. Fonte permitida: {state?.sourcePackage ?? 'carregando'}.</Text>
        <Text style={styles.body}>Caixa cifrada: {!state ? 'verificando' : state.storageReady ? `pronta · SQLCipher ${state.cipherVersion}` : 'indisponível'}</Text>
        <Text style={styles.body}>Consentimento local: {state?.enabled ? 'ativo' : 'desativado'} · acesso Android: {state?.authorized ? 'concedido' : 'não concedido'} · conexão: {state?.connected ? 'conectado' : 'desconectado'}</Text>
        <Action title={state?.enabled ? 'Desativar captura sintética' : 'Concordo: ativar captura somente sintética'} disabled={busy || (!state?.storageReady && !state?.enabled)} onPress={() => void run(() => native.setEnabled(!state?.enabled))} />
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
      <Card><Text style={styles.heading}>4. Backup e restauração</Text>
        <Text style={styles.body}>Salva registros e resultados de parsing, sem os payloads brutos. Escolha uma pasta neste aparelho. O arquivo é cifrado com sua senha, independente da chave local. A senha não é salva e não tem recuperação.</Text>
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
      {state?.observations?.map(item => <Card key={item.id}><Text style={styles.heading}>Revisão {item.revision} · {item.state === 'processed' ? 'interpretada' : item.payload === null ? 'sem bruto' : 'pendente'}</Text><Text selectable style={styles.small}>ID: {item.id}{'\n'}Fonte: {item.sourceKey}{'\n'}Publicada: {item.publishedAt}{'\n'}Capturada: {item.capturedAt}{'\n'}Parser: {item.parserVersion ?? 'sem resultado'}</Text><Text selectable style={styles.small}>{item.parsedJson}</Text></Card>)}
      <Action title="Apagar caixa sintética ativa" secondary disabled={busy} onPress={() => Alert.alert('Apagar a caixa ativa?', 'Apaga a caixa ativa e sua chave. A caixa anterior, se houver, e os backups externos permanecem. Sem um deles, os dados apagados não podem ser recuperados.', [{ text:'Cancelar',style:'cancel' },{ text:'Apagar',style:'destructive',onPress:() => void run(() => native.reset()) }])} />
    </>}
    <Action title="Sobre e licenças" secondary onPress={() => setLegalOpen(true)} />
  </ScreenScroll><LegalNotices visible={legalOpen} onClose={() => setLegalOpen(false)} /></SafeAreaView>;
}
