# Chave utilizável pelo núcleo com a tela bloqueada

O usuário aceitou que o núcleo possa usar a chave sem biometria por operação, para capturar e processar em segundo plano com a tela bloqueada após o primeiro desbloqueio desde o reinício. A alternativa de exigir autenticação para uso da chave reduziria essa disponibilidade e pausaria o acesso ao banco quando não autorizado.

Consequências: proteger a chave aleatória do SQLCipher com chave de encapsulamento não exportável no Keystore e tratar o bloqueio biométrico da interface separadamente; ele não impede o serviço de acessar o banco. A chave do banco precisa estar disponível à implementação nativa na memória. Não prometer captura antes do primeiro desbloqueio; testar o comportamento no aparelho e oferecer backup portátil cifrado independente da chave local antes de aceitar dados reais.
