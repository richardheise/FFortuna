import { spawnSync } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync, copyFileSync, chmodSync, readdirSync, statSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
// Enforce a bound on the ENTIRE build process tree, not only the Java heap.
if (process.env.FFORTUNA_BUILD_IN_SCOPE !== '1') {
  const limited = spawnSync('systemd-run', ['--user','--scope','--quiet',
    '--unit=ffortuna-native-build','--property=MemoryHigh=10G','--property=MemoryMax=12G',
    '--property=MemorySwapMax=0','--property=CPUQuota=1600%','--property=TasksMax=512',
    '/usr/bin/env','FFORTUNA_BUILD_IN_SCOPE=1',process.execPath,fileURLToPath(import.meta.url),...process.argv.slice(2)],
    {cwd:root,env:process.env,stdio:'inherit'});
  if(limited.error) throw new Error('Limite de recursos indisponível; build não será iniciado.', {cause:limited.error});
  process.exit(limited.status ?? 1);
}
const group = readFileSync('/proc/self/cgroup','utf8').trim().split('::')[1];
const memoryMax = readFileSync(join('/sys/fs/cgroup',group.trim(),'memory.max'),'utf8').trim();
if(memoryMax === 'max' || Number(memoryMax)>12*1024**3) throw new Error('Limite rígido de memória não confirmado; build bloqueado.');
console.log(`Build limitado pelo sistema a ${memoryMax} bytes e até 16 CPUs.`);
const local = join(root, '.local');
const java = process.env.FFORTUNA_JAVA_HOME ?? join(local, 'android-toolchain/jdk');
const sdk = process.env.ANDROID_HOME ?? join(local, 'android-toolchain/android-sdk');
if (!existsSync(join(java, 'bin/java')) || !existsSync(join(sdk, 'platform-tools/adb'))) {
  throw new Error('Toolchain ausente. Configure Tique JDK/SDK conforme docs/captura-nativa.md.');
}
const keys = join(local, 'keys');
mkdirSync(keys, { recursive: true, mode: 0o700 });
const keyFile = join(keys, 'ffortuna-poc.p12');
const properties = join(keys, 'poc-signing.properties');
if (existsSync(keyFile) !== existsSync(properties)) throw new Error('Assinatura incompleta: preserve os arquivos locais e recupere a chave; não será substituída.');
const env = {
  ...process.env, JAVA_HOME: java, ANDROID_HOME: sdk, ANDROID_SDK_ROOT: sdk,
  GRADLE_USER_HOME: join(local, 'gradle'),
  PATH: `${join(java,'bin')}:${join(sdk,'platform-tools')}:${process.env.PATH}`,
  FFORTUNA_MODE: 'capture-poc', EXPO_PUBLIC_FFORTUNA_MODE: 'capture-poc',
  EXPO_NO_TELEMETRY: '1', CI: '1', NODE_ENV: 'production',
  NODE_OPTIONS: '--max-old-space-size=768',
  CMAKE_BUILD_PARALLEL_LEVEL: '16',
  JAVA_TOOL_OPTIONS: '-XX:ActiveProcessorCount=16',
};
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
  if(result.error) throw result.error;
  if(result.status !== 0) process.exit(result.status ?? 1);
}
if (!existsSync(keyFile)) {
  const password = randomBytes(32).toString('hex');
  const passwordFile = join(keys, 'poc-signing.password');
  writeFileSync(passwordFile, password, { mode: 0o600 });
  run(join(java,'bin/keytool'), ['-genkeypair','-keystore',keyFile,'-storetype','PKCS12','-storepass:file',passwordFile,'-keyalg','RSA','-keysize','3072','-validity','3650','-alias','ffortuna-poc','-dname','CN=FFortuna Synthetic PoC']);
  writeFileSync(properties, `storePassword=${password}\n`, { mode: 0o600 });
  chmodSync(keyFile, 0o600);
}
const prebuildFingerprint = createHash('sha256');
for (const name of ['app.json','app.config.js','plugins/withCapturePoc.js','package.json']) prebuildFingerprint.update(readFileSync(join(root,name)));
const fingerprint = prebuildFingerprint.digest('hex');
const stamp = join(local,'capture-prebuild.sha256');
if(!existsSync(join(root,'android/settings.gradle')) || !existsSync(stamp) || readFileSync(stamp,'utf8') !== fingerprint) {
  run(process.execPath, ['node_modules/expo/bin/cli','prebuild','--platform','android','--no-install']);
  writeFileSync(stamp,fingerprint);
} else console.log('Projeto Android gerado preservado; configuração inalterada.');
writeFileSync(join(root,'android/local.properties'), `sdk.dir=${sdk}\n`);
const testOnly = process.argv.includes('--test-only');
const configureOnly = process.argv.includes('--configure-only');
const options = ['--no-daemon','--console=plain','--max-workers=1','--no-parallel','-Dorg.gradle.parallel=false',
  '-Dorg.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=16',
  '-Pkotlin.compiler.execution.strategy=in-process','-Dorg.gradle.priority=low',
  '-PreactNativeArchitectures=arm64-v8a'];
function gradle(tasks) { run('./gradlew', [...tasks,...options], join(root,'android')); }
if (!testOnly || configureOnly) {
  gradle([':app:configureCMakeRelWithDebInfo[arm64-v8a]']);
  const base = join(root,'android/app/.cxx/RelWithDebInfo');
  const candidates = readdirSync(base).map(dir=>join(base,dir,'arm64-v8a/build.ninja')).filter(existsSync)
    .sort((a,b)=>statSync(b).mtimeMs-statSync(a).mtimeMs);
  if(!candidates[0] || !readFileSync(candidates[0],'utf8').includes('pool = ff_compile'))
    throw new Error('Serialização do Ninja do app não confirmada; compilação bloqueada.');
  console.log('Ninja do app: pool de compilação limitado confirmado.');
}
if (!configureOnly) gradle([':ffortuna-capture:testReleaseUnitTest', ...(testOnly ? [] : [':app:assembleRelease']), ...(process.argv.includes('--compile-device-tests') ? [':ffortuna-capture:compileReleaseAndroidTestKotlin'] : [])]);
if (!testOnly && !configureOnly) {
  const destination = join(root,'artifacts');
  mkdirSync(destination,{recursive:true});
  const apk = join(destination,'tique-arm64.apk');
  const source = join(root,'android/app/build/outputs/apk/release/app-release.apk');
  run('python3', ['scripts/inspect-capture-apk.py',source,sdk]);
  copyFileSync(source,apk);
  const digest = createHash('sha256').update(readFileSync(apk)).digest('hex');
  writeFileSync(apk+'.sha256', `${digest}  tique-arm64.apk\n`);
  console.log(`APK local (exemplos sintéticos e diagnóstico real opcional): ${apk}\nSHA-256: ${digest}`);
}
