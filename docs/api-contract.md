# Contrato da API PDV consumido pelo aplicativo

Este documento descreve apenas o contrato que o PDV Java consome. Ele não duplica a documentação interna
do backend Laravel.

## Versão e endereço

O contrato consumido é o `v1`. O prefixo de transporte é escrito em um único lugar,
`infrastructure/api/PdvApiEndpoints`, e o segmento de versão é derivado de
`application/api/PdvContract.SUPPORTED_VERSION`.

```text
/api/v1/pdv
```

A URL base **não** existe no código-fonte. Ela vem da configuração bootstrap da Fase 3:

```properties
api.base-url=https://host-do-ambiente
```

`PdvBackendGatewayFactory.fromBootstrap(...)` constrói o adapter a partir dessa chave. A ausência da chave
é um estado válido, não um defeito: o resultado é `Optional.empty()` e o terminal pode iniciar sem backend
configurado.

`PdvApiBaseUrl` normaliza o endereço antes do uso: remove barra final, preserva um eventual caminho de
contexto e a porta explícita, e resolve cada endpoint pelo construtor multiargumento de `URI`, nunca por
concatenação de strings.

## Transporte e segurança

- TLS obrigatório. `http` é aceito somente para host de loopback literal (`localhost`, `127.0.0.1`,
  `[::1]`), exatamente a mesma regra já aplicada ao `api.base-url` na Fase 3. Os testes locais usam
  loopback e por isso não exigem nenhum relaxamento da regra de produção.
- Nenhum `SSLContext`, `TrustManager` ou verificador de hostname é substituído. A validação de certificado
  e de hostname permanece integralmente com o padrão da plataforma.
- Redirecionamentos nunca são seguidos, o que também impede que um header `Authorization` seja reenviado a
  outro host.
- Credenciais, query strings e fragmentos são rejeitados na URL base.

## Headers

Enviados em toda chamada:

| Header | Valor |
| --- | --- |
| `Accept` | `application/json` |
| `User-Agent` | `LucraOne-PDV/<versão>`, derivado de `ApplicationMetadata` |
| `X-Request-ID` | `UUID.randomUUID().toString()` |

`Content-Type: application/json` é enviado apenas no `POST` de pareamento.

`Authorization: Bearer <machine credential>` é enviado apenas na leitura autenticada do terminal.

## Timeouts

Centralizados em `infrastructure/api/PdvApiSettings`:

| Parâmetro | Valor |
| --- | --- |
| connect timeout | 5 segundos |
| request timeout | 10 segundos |

Os valores são curtos de propósito: um ponto de venda precisa informar indisponibilidade rapidamente, em
vez de deixar o operador esperando.

## Endpoints

### `GET /api/v1/pdv/health`

Sem autenticação. Resposta esperada:

```json
{ "data": { "status": "ok", "api": "pdv", "version": "v1" } }
```

Mapeado para `BackendHealth`. Uma versão diferente de `v1` **não** falha a chamada: `versionSupported()`
retorna `false` e a informação fica disponível para decisão futura. Não há negociação de versão nesta fase.

### `POST /api/v1/pdv/terminals/pair`

Sem autenticação. Corpo enviado:

```json
{ "pairing_code": "...", "installation_id": "UUID-v4" }
```

O `installation_id` é a identidade local criada na Fase 3. O formato do `pairing_code` é definido e
validado pelo backend; o cliente apenas rejeita valor vazio, para não duplicar uma regra de servidor que
poderia recusar um código válido.

Resposta de sucesso mapeada para `PairedTerminal`, com `terminal`, `tenant`, `company`, `branch` e
`credential` (`token_type`, `access_token`, `expires_at`).

### `GET /api/v1/pdv/terminal`

Requer `Authorization: Bearer`. Resposta mapeada para `CurrentTerminal`. O backend deliberadamente **não**
devolve `access_token` aqui, apenas `credential.expires_at`.

## Modelo de erro

Envelope do backend:

```json
{ "error": { "code": "...", "message": "...", "request_id": "..." } }
```

`error.code` é o sinal estável. Nenhuma decisão do cliente depende de `error.message`, que é tratado como
detalhe de diagnóstico e nunca é registrado em log.

Em `validation_error` o campo `error.errors` é preservado por campo em `PdvApiFailure.fieldErrors()`.

### Classificação

O projeto sinaliza problemas de infraestrutura com exceções tipadas (`LocalDatabaseException`,
`SecretProtectionException`, `InvalidBootstrapConfigurationException`). O cliente da API segue o mesmo
estilo: a chamada falha com `PdvApiException`, que carrega um `PdvApiFailure`. Nenhum tipo de resultado
paralelo foi introduzido.

| Situação | `PdvFailureKind` |
| --- | --- |
| backend inalcançável, DNS, conexão recusada, falha TLS | `NETWORK_UNAVAILABLE` |
| prazo de conexão ou de request excedido | `TIMEOUT` |
| status inesperado, incluindo 4xx não previstos | `PROTOCOL_ERROR` |
| 401 | `UNAUTHENTICATED` |
| 403 | `FORBIDDEN` |
| 422 com `validation_error` | `VALIDATION` |
| 422 com `pairing_failed` | `PAIRING_FAILED` |
| 429 | `RATE_LIMITED` |
| 500 e demais 5xx | `SERVER_ERROR` |
| 2xx com JSON inválido, campo obrigatório ausente ou `Content-Type` inesperado | `INVALID_RESPONSE` |

Em 401 o cliente não infere a causa: o backend esconde deliberadamente se foi token expirado, terminal
bloqueado ou tenant suspenso. Em 403 não há tentativa de contornar com header alternativo.

Resposta `2xx` cujo corpo não seja JSON é tratada como `INVALID_RESPONSE`: uma página HTML de proxy nunca
é interpretada como payload de sucesso.

## Rate limiting

Em 429 o header `Retry-After` é capturado e exposto de forma tipada em `PdvApiFailure.retryAfter()`, nas
duas formas da RFC 9110: atraso em segundos ou data HTTP. O cliente **não** dorme nem bloqueia por conta
própria; a decisão de espera pertence a quem chama.

## Política de retry

Assimétrica de propósito.

| Operação | Tentativas | Motivo |
| --- | --- | --- |
| `GET health` | 1 retry | leitura idempotente |
| `GET terminal` | 1 retry | leitura idempotente |
| `POST terminals/pair` | nenhuma | o código é de uso único e o backend pode ter concluído o pareamento mesmo que a resposta tenha se perdido |

Retry acontece somente em falha realmente transitória: `TIMEOUT`, `502`, `503` e `504`. Nunca em `400`,
`401`, `403`, `404`, `422`, `429` ou `500` — um `500` pode ser determinístico, e repeti-lo não ajuda.

## Correlação

`X-Request-ID` é gerado antes de a chamada sair. `UUID.toString()` já satisfaz a regra do backend (1–128
caracteres ASCII, alfanuméricos mais `.` `_` `:` `-`), então nenhum gerador adicional foi introduzido.

Um retry reutiliza o mesmo identificador, para que o suporte veja uma única operação lógica.

Precedência do identificador devolvido ao chamador, em sucesso e em falha:

1. header `X-Request-ID` da resposta;
2. `error.request_id` do corpo de erro;
3. identificador enviado pelo cliente.

O header vence porque o contrato garante sua presença em toda resposta, enquanto `error.request_id` existe
apenas em corpos de erro. Isso mantém uma regra única para sucessos e falhas.

## Observabilidade

Logging sanitizado via `java.util.logging`, no logger `br.com.lucraone.pdv.infrastructure.api`. O projeto
não possuía logging estruturado e nenhum framework foi adicionado.

Registrado por chamada: `operation`, `method`, `path` lógico, `status`, `duration_ms` e `request_id`.

Nunca registrado: corpo de request ou response, header `Authorization`, access token, pairing code e o
host do backend — uma URL configurada poderia, ela própria, embutir dado sensível.

## Sensibilidade da credencial

A machine credential devolvida pelo pareamento é secreta. Nesta fase ela existe **somente em memória**,
pelo tempo do fluxo que a usa.

Ela não é gravada no SQLite, em `properties`, em arquivo ou em qualquer outro meio, e nenhuma migration
local foi criada para token. `MachineCredential`, `PairedTerminal`, `PairTerminalCommand`,
`CredentialDto` e `PairTerminalRequestDto` têm `toString()` redigido, para que o segredo não chegue a um
log pela representação gerada de um record.

A persistência segura depende de um secret store por plataforma e pertence à fase de autenticação. Não há
fallback: sem store nativo, a política é falhar de forma segura, nunca gravar em texto puro.

## Assincronismo

Todos os métodos da porta devolvem `CompletableFuture`, implementados sobre `HttpClient.sendAsync`. Nenhum
chamador bloqueia a thread da aplicação JavaFX. A camada de apresentação conversa com a porta de
aplicação e nunca com `HttpClient`.

## Testes

Os testes de contrato usam um backend HTTP local baseado em `com.sun.net.httpserver.HttpServer`, do
próprio JDK, em porta efêmera de loopback. Nenhuma dependência de mock server foi adicionada e a suíte
Maven é integralmente independente da Internet.

O smoke contra ambiente real (`PdvHealthSmokeIT`) é opt-in: só executa com `LUCRAONE_PDV_SMOKE=1` e lê o
endereço de `LUCRAONE_PDV_BASE_URL`, de forma que nenhuma URL de ambiente entra no código. Ele toca apenas
o endpoint de health, não autenticado: não cria terminal, não consome código de pareamento e não solicita
credencial.
