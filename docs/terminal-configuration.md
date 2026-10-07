# Identidade e configuração do terminal

## Identidade da instalação

Na primeira inicialização o PDV gera um `installation_id` local com `UUID.randomUUID()` (UUID versão 4) e o grava na tabela `installation` do SQLite local. A tabela admite uma única linha; inicializações seguintes, inclusive simultâneas, reutilizam o valor existente e nunca o regeneram.

O `installation_id` identifica a instalação do aplicativo. Ele não é derivado de MAC address, hostname, usuário do Windows ou serial de hardware e não é o `terminal_id`, que é uma identidade comercial atribuída ou validada pelo backend. Timestamps técnicos são gravados em UTC no formato ISO-8601.

## Provisionamento

O estado de provisionamento fica na tabela `terminal_provisioning`:

- sem linha: **não provisionado**, estado válido enquanto não houver provisionamento pelo backend;
- com linha: **provisionado**, com `terminal_id` e `branch_id` gravados juntos.

`terminal_id` e `branch_id` são referências externas opacas (texto, até 64 caracteres) porque o contrato da API ainda não foi definido. O PDV não gera esses valores. A gravação ocorre em uma única transação e o schema rejeita provisionamento parcial. Nesta fase não existe fluxo remoto de provisionamento.

## Configuração bootstrap

Arquivo opcional, não secreto, em UTF-8 no formato Java Properties:

```text
%LOCALAPPDATA%\LucraOne\PDV\config\bootstrap.properties
```

```properties
environment=homologation
api.base-url=https://api.exemplo.com.br
```

- Sem o arquivo, o PDV inicializa normalmente e o diagnóstico informa bootstrap ausente.
- Valores em branco equivalem a ausentes; espaços nas extremidades são ignorados.
- Somente `environment` e `api.base-url` são aceitas; outras chaves invalidam o arquivo.
- `environment` aceita de 1 a 32 caracteres: letras, números, ponto, hífen ou sublinhado.
- `api.base-url` precisa ser URL absoluta `https`, com host e sem credenciais, query string ou fragmento. `http` só é aceito para loopback local (`localhost`, `127.0.0.1` ou `[::1]`). A validação é apenas sintática: nenhuma conexão é feita.
- Arquivo inválido impede a inicialização com mensagem clara, sem repetir os valores configurados e sem alterar o banco.

O bootstrap nunca deve conter senhas, tokens ou outras credenciais.

## Proteção de segredos

A porta `SecretProtector` protege segredos futuros, como tokens. A implementação `WindowsDpapiSecretProtector` usa DPAPI (`CryptProtectData`/`CryptUnprotectData`) no escopo do usuário Windows atual, chamado pela Foreign Function API do JDK, sem dependência externa e sem chave gerenciada pela aplicação. Requer `--enable-native-access=ALL-UNNAMED`, já configurado para testes e para `javafx:run`.

Ainda não há segredo real nem tabela de segredos. Material protegido só pode ser recuperado pelo mesmo usuário Windows no mesmo perfil; se o perfil for perdido, o segredo deve ser obtido novamente pelo fluxo de autenticação futuro, nunca reconstruído.

## Diagnóstico

`TerminalService.initialize()` garante a identidade, valida o bootstrap e devolve `TerminalDiagnostics` com: `installation_id`, status de provisionamento, `terminal_id`/`branch_id` quando existirem, caminho do banco, estado e caminho do bootstrap, ambiente, URL da API configurada, versão do aplicativo e sistema operacional. O diagnóstico é local, não é enviado a nenhum serviço e não contém segredos.
