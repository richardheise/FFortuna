# Núcleo Kotlin responsável pelo banco no modo real

O usuário aceitou que o núcleo Kotlin seja responsável pelo único banco financeiro criptografado do modo real, suas migrações, evidências, processamento recuperável e confirmações atômicas; a interface Expo/TypeScript usa operações tipadas do módulo. Deixar o livro apenas em TypeScript facilitaria compartilhar código com a demonstração, mas faria o processamento financeiro depender do runtime JavaScript.

Consequências: usar SQLCipher Community no núcleo nativo e manter um adaptador de demonstração TypeScript/expo-sqlite exclusivamente sintético, sem sincronizar livros entre os modos. Os mesmos contratos e cenários de teste devem validar equivalência de comportamento; não se afirma essa equivalência antes dos testes. A captura persiste antes do processamento complexo, e migrações, serialização de escritas e unicidade ficam sob responsabilidade do núcleo nativo.
