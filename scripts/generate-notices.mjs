import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const lock = JSON.parse(readFileSync(join(root, 'package-lock.json'), 'utf8'));
const packages = lock.packages;
function lockPackageAt(key) {
  const entry = packages[key];
  if (entry?.link) {
    const target = packages[entry.resolved];
    if (!target) throw new Error(`Missing lockfile metadata for local package: ${entry.resolved}`);
    return { ...target, resolved: entry.resolved };
  }
  return entry;
}

const knownLicenseFiles = /^(?:licen[cs]e|copying|notice)(?:[._-].*)?$/i;

function packageDirectory(key) {
  return join(root, ...key.split('/'));
}

function resolveDependency(fromKey, name) {
  let cursor = fromKey;
  while (true) {
    const candidate = cursor ? `${cursor}/node_modules/${name}` : `node_modules/${name}`;
    if (packages[candidate]) return candidate;
    const split = cursor.lastIndexOf('/node_modules/');
    if (split < 0) {
      if (!cursor) break;
      cursor = '';
    } else cursor = cursor.slice(0, split);
  }
  return null;
}

const runtimeKeys = new Set();
const pending = [];
const rootPackage = packages[''];
for (const name of Object.keys(rootPackage.dependencies ?? {})) pending.push(['', name]);
for (const name of Object.keys(rootPackage.optionalDependencies ?? {})) pending.push(['', name]);
while (pending.length) {
  const [fromKey, name] = pending.pop();
  const key = resolveDependency(fromKey, name);
  if (!key || runtimeKeys.has(key)) continue;
  const lockPackage = lockPackageAt(key);
  if (lockPackage.dev === true) continue;
  runtimeKeys.add(key);
  for (const dependency of Object.keys(lockPackage.dependencies ?? {})) pending.push([key, dependency]);
  for (const dependency of Object.keys(lockPackage.optionalDependencies ?? {})) pending.push([key, dependency]);
  for (const dependency of Object.keys(lockPackage.peerDependencies ?? {})) {
    if (resolveDependency(key, dependency)) pending.push([key, dependency]);
  }
}

function packageName(key, pkg) {
  if (pkg.name) return pkg.name;
  const suffix = key.slice('node_modules/'.length).split('/node_modules/').at(-1);
  return suffix ?? key;
}

function licenseTextFiles(directory) {
  return readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isFile() && knownLicenseFiles.test(entry.name))
    .map((entry) => entry.name)
    .sort();
}

function findLicenseFiles(directory) {
  return licenseTextFiles(directory).map((file) => join(directory, file));
}

function origin(pkg, lockPackage = {}) {
  const repository = pkg.repository;
  if (typeof repository === 'string') return repository;
  if (repository && typeof repository.url === 'string') return repository.url.replace(/^git\+/, '').replace(/\.git$/, '');
  return pkg.homepage ?? lockPackage.resolved ?? '(origem não declarada no package.json/lockfile)';
}

function declaredLicense(lockPackage, pkg) {
  const value = lockPackage.license ?? pkg.license;
  if (typeof value === 'string') return value;
  if (value && typeof value.type === 'string') return value.type;
  return '(licença não declarada nos metadados)';
}

function chosenLicense(name, declared) {
  if (name === 'node-forge' && declared.includes('BSD-3-Clause') && declared.includes('GPL-2.0')) return 'BSD-3-Clause (alternativa selecionada entre BSD-3-Clause OR GPL-2.0)';
  return declared;
}

function licenseTextForFile(file, pkgName) {
  const raw = readFileSync(file, 'utf8');
  if (pkgName === 'node-forge' && file.endsWith('/LICENSE')) {
    const start = raw.indexOf('New BSD License (3-clause)');
    const end = raw.indexOf('GNU GENERAL PUBLIC LICENSE', start);
    if (start >= 0 && end > start) return { raw: raw.slice(start, end).replace(/-+\s*$/, ''), sourceNote: ' seção New BSD License (3-clause)' };
  }
  return { raw, sourceNote: '' };
}

const supplementsPath = join(root, 'legal/npm-supplements.json');
const supplements = existsSync(supplementsPath) ? JSON.parse(readFileSync(supplementsPath, 'utf8')) : [];
const inventory = [];
const licenseGroups = new Map();
const gaps = [];
const directRows = [];
for (const key of [...runtimeKeys].sort()) {
  const lockPackage = lockPackageAt(key);
  const directory = packageDirectory(key);
  const packageJsonPath = join(directory, 'package.json');
  const pkg = existsSync(packageJsonPath) ? JSON.parse(readFileSync(packageJsonPath, 'utf8')) : {};
  const name = packageName(key, pkg);
  const license = declaredLicense(lockPackage, pkg);
  const selectedLicense = chosenLicense(name, license);
  const upstream = origin(pkg, lockPackage);
  const localFiles = existsSync(directory) ? findLicenseFiles(directory) : [];
  const extra = supplements.filter((item) => item.name === name && item.version === lockPackage.version);
  const files = [...localFiles, ...extra.map((item) => join(root, item.textFile))];
  const sources = files.map((file) => relative(root, file).split('\\').join('/'));
  const upstreamSources = extra.map((item) => item.sourceUrl);
  const entry = { name, version: lockPackage.version, license, includedLicense: selectedLicense, origin: upstream, licenseSources: sources, upstreamSources };
  inventory.push(entry);
  if (!files.length) gaps.push(`${name}@${lockPackage.version}: ${license}; ${existsSync(directory) ? 'nenhum LICENSE/NOTICE na raiz do pacote instalado' : 'pacote opcional sem instalação local (texto não disponível)'}`);
  for (const file of files) {
    const content = licenseTextForFile(file, name);
    const digest = createHash('sha256').update(content.raw).digest('hex');
    const group = licenseGroups.get(digest) ?? { raw: content.raw, entries: [] };
    group.entries.push({ ...entry, source: `${relative(root, file).split('\\').join('/')}${content.sourceNote}` });
    licenseGroups.set(digest, group);
  }
}

const directDependencies = rootPackage.dependencies ?? {};
for (const name of Object.keys(directDependencies).sort()) {
  const key = resolveDependency('', name);
  if (!key) {
    directRows.push({ name, version: '(ausente do lockfile)', license: '(não encontrado)', origin: '(não encontrado)' });
    continue;
  }
  const lockPackage = lockPackageAt(key);
  const directory = packageDirectory(key);
  const pkgPath = join(directory, 'package.json');
  const pkg = existsSync(pkgPath) ? JSON.parse(readFileSync(pkgPath, 'utf8')) : {};
  directRows.push({ name, version: lockPackage.version, license: declaredLicense(lockPackage, pkg), origin: origin(pkg) });
}

const metadata = [
  'Runtime dependency inventory (from package-lock.json; optional and installed peer dependencies included).',
  'This inventory includes build and platform tooling and does not claim that every listed package is embedded in an APK.',
  ...inventory.map((item) => `${item.name}@${item.version} | declared license: ${item.license} | included license: ${item.includedLicense} | origin: ${item.origin} | text: ${item.licenseSources.join(', ') || '(no installed license text)'}`),
];
const textGroups = [...licenseGroups.values()].sort((a, b) => a.raw.localeCompare(b.raw));
const copiedTexts = textGroups.map((group, index) => {
  const packagesForText = [...new Map(group.entries.map((entry) => [`${entry.name}@${entry.version}`, entry])).values()]
    .sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version));
  const sources = [...new Set(group.entries.map((entry) => entry.source))].sort();
  return [
    `===== License text ${index + 1} (${createHash('sha256').update(group.raw).digest('hex')}) =====`,
    `Packages: ${packagesForText.map((entry) => `${entry.name}@${entry.version}`).join(', ')}`,
    `License metadata: ${[...new Set(packagesForText.map((entry) => `${entry.name}: ${entry.includedLicense}`))].join('; ')}`,
    `Origin text: ${sources.join(', ')}`,
    '', group.raw, '',
  ].join('\n');
});
const thirdPartyText = [...metadata, '', ...copiedTexts, ...(gaps.length ? ['', '===== Missing installed license text =====', ...gaps] : [])].join('\n');
const appLicense = readFileSync(join(root, 'LICENSE'), 'utf8');
const output = { appLicense, thirdPartyText };
const legalDirectory = join(root, 'src', 'legal');
mkdirSync(legalDirectory, { recursive: true });
writeFileSync(join(legalDirectory, 'notices.json'), `${JSON.stringify(output, null, 2)}\n`);

const markdown = [
  '# Avisos de dependências de terceiros',
  '',
  'O código-fonte do FFortuna está sob AGPL-3.0-only. O texto completo está em `LICENSE` e também no campo `appLicense` de [`src/legal/notices.json`](src/legal/notices.json).',
  '',
  'O inventário npm segue o fechamento de dependências de produção de `package-lock.json`, incluindo opcionais e peers. Também inclui ferramentas de build; a revisão do APK usa separadamente o source map e o grafo Android release, registrados em [legal/apk-components.json](legal/apk-components.json).',
  '',
  'O JSON reúne textos LICENSE/NOTICE do próprio pacote e suplementos upstream verificados e versionados em [legal/npm-supplements.json](legal/npm-supplements.json). Não há herança automática de licenças de pastas pais. Textos idênticos são deduplicados, preservando origem e avisos de copyright.',
  '',
  '| Dependência direta | Versão no lockfile | Metadados de licença | Origem upstream |',
  '| --- | --- | --- | --- |',
  ...directRows.map((item) => `| \`${item.name}\` | ${item.version} | ${item.license.replaceAll('|', '\\|')} | ${item.origin} |`),
  '',
  `O inventário npm contém ${inventory.length} entradas e ${gaps.length} lacunas de texto entre ferramentas/opcionais, detalhadas no JSON. Isso não é a contagem de pendências do APK. A cobertura do binário está no [relatório da revisão](docs/revisao-licencas-apk.md).`,
  '',
  'A prova Android também inclui [avisos nativos](src/legal/native-notices.json), exibidos na interface junto deste inventário. O inventário npm não é um inventário completo dos artefatos Maven/NDK do APK.',
  '',
  'Gere novamente o inventário após mudanças nas dependências:',
  '',
  '```sh',
  'node scripts/generate-notices.mjs',
  '```',
  '',
  '',
].join('\n');
writeFileSync(join(root, 'THIRD_PARTY_NOTICES.md'), `${markdown.trimEnd()}\n`);

console.log(`Generated src/legal/notices.json and THIRD_PARTY_NOTICES.md.`);
console.log(`Runtime packages: ${inventory.length}; deduplicated license texts: ${textGroups.length}; text gaps: ${gaps.length}.`);
