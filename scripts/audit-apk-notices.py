#!/usr/bin/env python3
"""Build an auditable notice inventory from the actual Android release inputs."""
import fnmatch
import hashlib
from html.parser import HTMLParser
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT/'.local/gradle/caches/modules-2/files-2.1'
NS={'m':'http://maven.apache.org/POM/4.0.0'}
LEGAL=re.compile(r'^(?:licen[cs]e|notice|copying)(?:[._-].*)?$',re.I)
def read_json(path): return json.loads((ROOT/path).read_text())
def sha(data): return hashlib.sha256(data).hexdigest()
def relative(path): return str(Path(path).resolve().relative_to(ROOT))
def own_texts(directory):
    return [relative(p) for p in sorted(directory.iterdir()) if p.is_file() and LEGAL.match(p.name)]
class PlainHTML(HTMLParser):
    def __init__(self): super().__init__(convert_charrefs=True);self.parts=[]
    def handle_data(self,data): self.parts.append(data)
    def handle_endtag(self,tag):
        if tag in ('p','div','br','h1','h2','h3','li','pre','tr'):self.parts.append('\n')
def plain(path):
    text=(ROOT/path).read_text()
    if Path(path).suffix=='.html':
        parser=PlainHTML();parser.feed(text);return ''.join(parser.parts)
    return text

supplements=read_json('legal/npm-supplements.json')
cpp=read_json('legal/native-cpp-supplements.json')
maven_supp=read_json('legal/maven-supplements.json')
archive_notices=[]
texts={};issues=[]
def include(path,component,source):
    raw=plain(path);digest=sha(raw.encode())
    entry=texts.setdefault(digest,{'text':raw,'components':set(),'sources':set(),'files':set()})
    entry['components'].add(component);entry['sources'].add(source);entry['files'].add(path)
    return {'textFile':path,'sha256':sha((ROOT/path).read_bytes()),'source':source}

source_map=ROOT/'android/app/build/generated/sourcemaps/react/release/index.android.bundle.map'
sources=json.loads(source_map.read_text())['sources']
js_keys=set()
for source in sources:
    if '/node_modules/' not in source:continue
    prefix,tail=source.rsplit('/node_modules/',1);parts=tail.split('/')
    name='/'.join(parts[:2]) if parts[0].startswith('@') else parts[0]
    js_keys.add((prefix+'/node_modules/'+name).lstrip('/'))
autolink=read_json('android/build/generated/autolinking/autolinking.json')
native_keys={relative(v['root']) for v in autolink['dependencies'].values()}
resolved=json.loads(subprocess.check_output(['node','node_modules/.bin/expo-modules-autolinking','resolve','--platform','android','--json'],cwd=ROOT,text=True))
publications={}
for module in resolved['modules']:
    for project in module.get('projects',[]):
        directory=Path(project['sourceDir']).parent
        if (directory/'package.json').exists():native_keys.add(relative(directory))
        pub=project.get('publication')
        if pub:publications[f"{pub['groupId']}:{pub['artifactId']}:{pub['version']}"]=relative(directory)
# The app's own Expo module is AGPL under the root LICENSE, not a third-party package.
packages=[];package_by_key={}
for key in sorted(js_keys|native_keys):
    directory=ROOT/key;pkg=json.loads((directory/'package.json').read_text())
    own=key.startswith('modules/') or key.startswith('vendor/')
    files=own_texts(directory)
    refs=[include(p,f"{pkg['name']}@{pkg['version']}",f"installed:{p}") for p in files]
    for record in supplements:
        if record['name']==pkg['name'] and record['version']==pkg['version']:
            refs.append(include(record['textFile'],f"{pkg['name']}@{pkg['version']}",record['sourceUrl']))
    if own and not refs:refs=[include('LICENSE',pkg['name'],'project root LICENSE')]
    if not refs:issues.append(f"No notice text for npm {pkg['name']}@{pkg['version']}")
    if pkg.get('license') not in {'MIT','ISC','MIT AND Apache-2.0','AGPL-3.0-only'}:
        issues.append(f"NPM license outside reviewed set: {pkg['name']}@{pkg['version']}: {pkg.get('license')}")
    entry={'key':key,'name':pkg['name'],'version':pkg['version'],'declaredLicense':pkg.get('license'),
           'scope':('own' if own else 'third-party'),'javascript':key in js_keys,'native':key in native_keys,'notices':refs}
    packages.append(entry);package_by_key[key]=entry

pom_cache=ROOT/'.local/license-poms';pom_cache.mkdir(parents=True,exist_ok=True)
def load_pom(g,a,v):
    paths=list((CACHE/g/a/v).glob('**/*.pom'))
    if paths: return ET.parse(paths[0]).getroot(),relative(paths[0])
    local=pom_cache/f'{g}-{a}-{v}.pom'
    url=f'https://repo.maven.apache.org/maven2/{g.replace(".","/")}/{a}/{v}/{a}-{v}.pom'
    if not local.exists():local.write_bytes(urllib.request.urlopen(url,timeout=20).read())
    return ET.parse(local).getroot(),url

def declarations(g,a,v,seen=None):
    seen=set() if seen is None else seen
    if (g,a,v) in seen:raise ValueError('POM parent cycle')
    seen.add((g,a,v));root,origin=load_pom(g,a,v)
    licenses=[{'name':e.findtext('m:name',namespaces=NS),'url':e.findtext('m:url',namespaces=NS)} for e in root.findall('m:licenses/m:license',NS)]
    if licenses:return licenses,origin
    parent=root.find('m:parent',NS)
    if parent is not None:
        return declarations(*(parent.findtext('m:'+part,namespaces=NS) for part in ('groupId','artifactId','version')),seen)
    return [],origin

sdk_file=ROOT/'android/app/build/outputs/sdk-dependencies/release/sdkDependencies.txt'
coordinates=re.findall(r'maven_library\s*\{\s*groupId: "([^"]+)"\s*artifactId: "([^"]+)"\s*version: "([^"]+)"',sdk_file.read_text())
# Inspect LICENSE/NOTICE shipped inside the selected runtime archives on every audit.
for g,a,v in coordinates:
    for artifact in sorted(list((CACHE/g/a/v).glob('**/*.jar'))+list((CACHE/g/a/v).glob('**/*.aar'))):
        if '-debug' in artifact.name or artifact.name.endswith(('-sources.jar','-javadoc.jar')):continue
        with zipfile.ZipFile(artifact) as outer:
            archives=[('',outer)]
            if 'classes.jar' in outer.namelist():archives.append(('classes.jar!',zipfile.ZipFile(io.BytesIO(outer.read('classes.jar')))))
            for prefix,archive in archives:
                for name in archive.namelist():
                    if archive.getinfo(name).is_dir() or not LEGAL.match(Path(name).name):continue
                    raw=archive.read(name)
                    try:raw.decode('utf8')
                    except UnicodeDecodeError:continue
                    path='legal/native-license-texts/archive-'+sha(raw)[:16]+'.txt'
                    if '--check' not in sys.argv:(ROOT/path).write_bytes(raw)
                    archive_notices.append({'coordinate':f'{g}:{a}:{v}','artifact':artifact.name,'archivePath':prefix+name,'textFile':path,'sha256':sha(raw)})
maven=[];so_owners={}
for g,a,v in coordinates:
    coord=f'{g}:{a}:{v}';refs=[];license_name=None
    if coord in publications:
        entry=package_by_key[publications[coord]];refs=list(entry['notices']);licenses=[{'name':entry['declaredLicense'],'url':'NPM source project'}];origin=entry['key'];license_name=entry['declaredLicense']
    else:
        licenses,origin=declarations(g,a,v)
        for record in maven_supp:
            if fnmatch.fnmatchcase(coord,record['pattern']):
                refs.append(include(record['textFile'],coord,record['sourceUrl']));license_name=record['license']
        if not refs and any('apache' in (item['name'] or '').lower() for item in licenses):
            refs=[include('legal/native-license-texts/apache-2.0.txt',coord,'https://www.apache.org/licenses/LICENSE-2.0.txt')];license_name='Apache-2.0'
    for record in archive_notices:
        if record['coordinate']==coord:refs.append(include(record['textFile'],coord,f"{coord}!{record['archivePath']}"))
    if not refs or not license_name:issues.append(f'Unresolved Maven license: {coord} {licenses}')
    artifacts=[]
    folder=CACHE/g/a/v
    paths=sorted(list(folder.glob('**/*.jar'))+list(folder.glob('**/*.aar')))
    # Release variant only; never infer that debug/test artifacts belong to this APK.
    paths=[p for p in paths if '-debug' not in p.name and not p.name.endswith(('-sources.jar','-javadoc.jar'))]
    for path in paths:
        artifacts.append({'file':path.name,'sha256':sha(path.read_bytes())})
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if name.startswith('jni/arm64-v8a/') and name.endswith('.so'):so_owners.setdefault(Path(name).name,set()).add(coord)
    maven.append({'coordinate':coord,'license':license_name,'declaredLicenses':licenses,'licenseEvidence':origin,
                  'artifacts':artifacts,'notices':refs,'scope':'release dependency graph (includes BOM/metadata; conservative)'} )

for record in cpp: include(record['textFile'],record['component']+' '+record['version'],record['sourceUrl'])
for path,label in [('legal/native-license-texts/sqlcipher-core-c4b275a.txt','SQLCipher core c4b275a'),('legal/native-license-texts/libtomcrypt-476a957.txt','LibTomCrypt 476a957'),('legal/native-license-texts/ndk27-libcxx.txt','NDK 27.1 libc++'),('legal/native-license-texts/mozilla-2.0.txt','Public Suffix List MPL-2.0'),('legal/native-license-texts/okhttp-publicsuffix-NOTICE.txt','OkHttp 4.9.2 Public Suffix List')]:include(path,label,'upstream provenance recorded in file / exact APK resource')

# Tie every native library in the actual APK to its published artifact or built source project.
built_owners={'libappmodules.so':'FFortuna generated app + React Native/linked codegen (root AGPL and upstream notices)',
 'libc++_shared.so':'Android NDK 27.1 libc++ (Apache-2.0 WITH LLVM-exception)',
 'libexpo-modules-core.so':'expo-modules-core','libexpo-sqlite.so':'expo-sqlite',
 'libgesturehandler.so':'react-native-gesture-handler','libreact_codegen_rnscreens.so':'react-native-screens',
 'libreact_codegen_safeareacontext.so':'react-native-safe-area-context','libreanimated.so':'react-native-reanimated',
 'librnscreens.so':'react-native-screens','libworklets.so':'react-native-worklets'}
apk=Path(sys.argv[sys.argv.index('--apk')+1]) if '--apk' in sys.argv else ROOT/'artifacts/ffortuna-capture-poc-arm64.apk'
native=[]
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if not name.startswith('lib/') or not name.endswith('.so'):continue
        owners=sorted(so_owners.get(Path(name).name,[]))
        if Path(name).name in built_owners:owners.append(built_owners[Path(name).name])
        if not owners:issues.append('Unmapped native library: '+name)
        native.append({'path':name,'owners':owners})
    psl=z.read('okhttp3/internal/publicsuffix/publicsuffixes.gz')
    for name in z.namelist():
        if LEGAL.match(Path(name).name) and not name.endswith('/') and not name.startswith('assets/ffortuna-legal/'):
            raw=z.read(name)
            try:raw.decode('utf8')
            except UnicodeDecodeError:continue
            path='legal/native-license-texts/apk-'+sha(raw)[:16]+'.txt'
            if '--check' not in sys.argv:(ROOT/path).write_bytes(raw)
            include(path,'Original APK notice '+name,'APK:'+name)

summary={'javascriptPackages':len(js_keys),'nativeNpmPackages':len(native_keys),'npmPackagesTotal':len(packages),
         'mavenCoordinates':len(maven),'nativeLibraries':len(native),'cppSupplements':len(cpp),'unresolved':len(issues)}
report={'schemaVersion':1,'variant':'capture-poc release arm64-v8a','summary':summary,
 'npm':packages,'maven':maven,'nativeLibraries':native,'cppSupplements':cpp,
 'publicSuffix':{'license':'MPL-2.0','resourceSha256':sha(psl),'sourceFile':'legal/source/okhttp-4.9.2-public_suffix_list.dat','sourceUrl':'https://github.com/square/okhttp/blob/parent-4.9.2/okhttp/src/test/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat'},
 'publicationRequirements':['Publish the matching AGPL source revision/archive with the APK.','Keep the third-party notice assets and the MPL source file with the distribution.'],
 'unresolved':issues}
if '--check' not in sys.argv:
    (ROOT/'legal/apk-components.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    (ROOT/'legal/archive-notices.json').write_text(json.dumps(archive_notices,ensure_ascii=False,indent=2)+'\n')
if issues:
    print(json.dumps(summary));print('\n'.join(issues));raise SystemExit(1)
parts=['Tique — avisos de bibliotecas do APK Android',
       'Código original: AGPL-3.0-only. Fonte: https://github.com/richardheise/Tique',
       'Distribua o código-fonte da mesma revisão junto da release; a tag e o manifesto da release vinculam o APK ao fonte.',
       'This software is based in part on the work of the Independent JPEG Group.',
       f'Inventário: {len(js_keys)} pacotes JavaScript, {len(packages)} pacotes NPM no total com módulos nativos, {len(maven)} coordenadas Maven no grafo release e {len(native)} bibliotecas nativas.',
       'O grafo Maven é conservador e também contém BOMs/metadados; não significa que cada artefato inteiro esteja no APK.',
       'Public Suffix List: MPL-2.0. Fonte correspondente em legal/source/okhttp-4.9.2-public_suffix_list.dat na distribuição do fonte.', '']
for digest,entry in sorted(texts.items()):
    parts.extend(['===== '+', '.join(sorted(entry['components']))+' =====','Fontes: '+'; '.join(sorted(entry['sources'])),entry['text'].rstrip(),''])
text='\n'.join(parts)
asset_dir=ROOT/'modules/ffortuna-capture/android/src/main/assets/ffortuna-legal'
if '--check' in sys.argv:
    assert read_json('legal/apk-components.json') == report, 'Inventory differs from actual build inputs'
    assert read_json('src/legal/native-notices.json')['text'] == text, 'UI notices are stale'
    assert (asset_dir/'THIRD_PARTY_NOTICES.txt').read_text() == text, 'APK notice asset is stale'
    assert (asset_dir/'LICENSE.txt').read_bytes() == (ROOT/'LICENSE').read_bytes()
else:
    (ROOT/'src/legal/native-notices.json').write_text(json.dumps({'text':text},ensure_ascii=False,indent=2)+'\n')
    asset_dir.mkdir(parents=True,exist_ok=True)
    (asset_dir/'THIRD_PARTY_NOTICES.txt').write_text(text)
    (asset_dir/'LICENSE.txt').write_text((ROOT/'LICENSE').read_text())
print(json.dumps(summary));print('Notice text bytes:',len(text.encode()),'deduplicated texts:',len(texts))
