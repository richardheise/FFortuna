# Avisos de dependências de terceiros

O código-fonte do FFortuna está sob AGPL-3.0-only. O texto completo está em `LICENSE` e também no campo `appLicense` de [`src/legal/notices.json`](src/legal/notices.json).

O inventário npm segue o fechamento de dependências de produção de `package-lock.json`, incluindo opcionais e peers. Também inclui ferramentas de build; a revisão do APK usa separadamente o source map e o grafo Android release, registrados em [legal/apk-components.json](legal/apk-components.json).

O JSON reúne textos LICENSE/NOTICE do próprio pacote e suplementos upstream verificados e versionados em [legal/npm-supplements.json](legal/npm-supplements.json). Não há herança automática de licenças de pastas pais. Textos idênticos são deduplicados, preservando origem e avisos de copyright.

| Dependência direta | Versão no lockfile | Metadados de licença | Origem upstream |
| --- | --- | --- | --- |
| `decode-uri-component` | 1.0.0 | AGPL-3.0-only | (origem não declarada no package.json/lockfile) |
| `decode-uri-component-fixed` | 0.5.0 | MIT | SamVerschueren/decode-uri-component |
| `expo` | 57.0.31 | MIT | https://github.com/expo/expo |
| `expo-constants` | 57.0.22 | MIT | https://github.com/expo/expo |
| `expo-crypto` | 57.0.4 | MIT | https://github.com/expo/expo |
| `expo-linking` | 57.0.13 | MIT | https://github.com/expo/expo |
| `expo-router` | 57.0.29 | MIT | https://github.com/expo/expo |
| `expo-sqlite` | 57.0.4 | MIT | https://github.com/expo/expo |
| `expo-status-bar` | 57.0.1 | MIT | https://github.com/expo/expo |
| `react` | 19.2.3 | MIT | https://github.com/facebook/react |
| `react-dom` | 19.2.3 | MIT | https://github.com/facebook/react |
| `react-native` | 0.86.3 | MIT | https://github.com/react/react-native |
| `react-native-gesture-handler` | 2.32.0 | MIT | https://github.com/software-mansion/react-native-gesture-handler |
| `react-native-reanimated` | 4.5.1 | MIT | https://github.com/software-mansion/react-native-reanimated |
| `react-native-safe-area-context` | 5.7.0 | MIT | https://github.com/AppAndFlow/react-native-safe-area-context |
| `react-native-screens` | 4.26.2 | MIT | https://github.com/software-mansion/react-native-screens |
| `react-native-worklets` | 0.10.1 | MIT | https://github.com/software-mansion/react-native-reanimated |

O inventário npm contém 570 entradas e 46 lacunas de texto entre ferramentas/opcionais, detalhadas no JSON. Isso não é a contagem de pendências do APK. A cobertura do binário está no [relatório da revisão](docs/revisao-licencas-apk.md).

A prova Android também inclui [avisos nativos](src/legal/native-notices.json), exibidos na interface junto deste inventário. O inventário npm não é um inventário completo dos artefatos Maven/NDK do APK.

Gere novamente o inventário após mudanças nas dependências:

```sh
node scripts/generate-notices.mjs
```
