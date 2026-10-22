# Expo com integração Kotlin para captura Android

Decisão aceita pelo usuário: usar Expo/React Native/TypeScript para acelerar a validação da interface e integrar a captura Android por Kotlin. A alternativa Kotlin integral simplificaria a integração e os ciclos de vida, mas perderia o fluxo inicial solicitado de demonstração no Expo Go.

Consequências: manter duas linguagens e testar a integração; recursos nativos próprios exigem build próprio, enquanto o Expo Go fica restrito à demonstração sintética. O [ADR 0004](0004-nucleo-nativo-dono-do-banco.md) define Kotlin como dono do banco real, migrações e processamento com a interface fechada. A demonstração usa um serviço TypeScript separado exclusivamente sintético; essa duplicação exige testes de equivalência antes de declarar o modo real compatível.
