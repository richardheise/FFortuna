# Avisos de dependências de terceiros

O código-fonte do FFortuna está sob AGPL-3.0-only. O texto completo está em `LICENSE` e também no campo `appLicense` de [`src/legal/notices.json`](src/legal/notices.json).

O inventário segue o fechamento de dependências de produção de `package-lock.json`, incluindo dependências opcionais e peers instalados. Ele também inclui ferramentas de build e plataforma; isso não significa que todos os pacotes estarão no APK. O app ainda é distribuído como código-fonte e não como APK.

O JSON reúne textos LICENSE/NOTICE disponíveis, agrupados quando são idênticos, além de versões, metadados de licença e origens. Cada texto precisa estar na raiz do próprio pacote. O script não herda licenças de pacotes pais nem reconstrói textos ausentes.

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

O inventário contém 570 pacotes de produção. Há 53 lacunas de texto LICENSE/NOTICE, detalhadas ao final do JSON. Feche essas lacunas e confira as licenças antes de distribuir um APK.

Gere novamente o inventário após mudanças nas dependências:

```sh
node scripts/generate-notices.mjs
```
