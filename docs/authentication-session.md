# Fase 5 — Autenticação e sessão operacional

**Estado: EM ANDAMENTO / PARCIAL.** O lifecycle seguro da credencial de terminal foi implementado; operador e sessão operacional completa permanecem bloqueados. A Fase 4 continua concluída. A Fase 6 não foi iniciada.

## Contrato disponível e autoridade

Os únicos endpoints PDV aprovados são:

- `GET /api/v1/pdv/health`;
- `POST /api/v1/pdv/terminals/pair`;
- `GET /api/v1/pdv/terminal`.

**Operator authentication backend contract unavailable.** Não existe contrato documentado de login, sessão ou logout do operador. Não há senha persistida, usuário local paralelo, JWT inventado ou endpoint novo. A identidade autenticada do terminal não equivale a operador autenticado nem autorização comercial.

O backend precisa aprovar, para concluir a Fase 5:

1. Método e path oficiais de autenticação do operador, requisitos de transporte e vínculo com a machine credential, instalação, terminal e filial.
2. Schema de request: identificação do operador e prova de autenticação aceita, validação, limites e campos obrigatórios. Nenhum password flow é presumido.
3. Schema de resposta: identidade do operador, contexto autorizado, permissões necessárias, credencial de sessão, escopo, timestamps e expiração.
4. Semântica de erros, incluindo 401/403, rate limiting e correlação, sem exposição de credenciais.
5. Reautenticação e eventual renovação/refresh somente se aprovados, com regras de expiração/revogação.
6. Método/path e semântica oficiais de logout/revogação da sessão, incluindo comportamento quando offline.
7. Política explícita de sessão do operador offline, evidência exigida, duração e capacidades permitidas.

Este documento registra a dependência; não define rotas, payloads ou campos fictícios nem modifica o backend Laravel.

## Credencial protegida

A única fonte da machine credential é a resposta de pairing existente. `MachineCredentialRepository` é a porta da application, com save/load/delete e pré-verificação real de escrita. `SecretStoreMachineCredentialRepository` é o adapter, sobre `SecretStore`.

A chave não secreta e constante é `machine-credential-v1`. O envelope **dentro do store protegido** tem exatamente: `version` (1), `generation` (UUID aleatório), `token_type` (Bearer), `access_token` e `expires_at` (Instant). A geração não é token nem digest do token; vincula a prova local à rotação correspondente. Todos os campos são obrigatórios; versões desconhecidas, tipos inválidos, duplicatas, trailing tokens, UUID não canônico e JSON corrompido falham explicitamente.

Jackson já existente é usado somente na infraestrutura. Nenhuma nova dependência Maven foi adicionada. O envelope não é persistido em arquivo JSON, SQLite ou properties. Buffers intermediários byte[] são zerados; o modelo HTTP existente usa String, e a JVM pode manter cópias imutáveis. Não se promete eliminação absoluta em memória.

Windows utiliza o store DPAPI existente, com ciphertext em arquivo. Linux utiliza libsecret/Secret Service nativo, sem subprocessos ou segredo em argv. macOS continua fail-secure: Keychain não implementado, persistência bloqueada. O provider indisponível jamais é representado como chave ausente ou fallback em memória/plaintext.

## Pairing, rotação e compensação

`TerminalProvisioningService`, executado no worker, usa o installation_id persistido; não substitui instalação existente. A pré-verificação de armazenamento faz save/load/delete de um segredo sintético em chave única, confirma remoção e zera buffers. Se falhar, não chama o endpoint mutável. Essa operação não garante disponibilidade posterior.

Ordem:

1. Ler identidade local e eventual credencial anterior.
2. Verificar escrita protegida e invalidar evidência anterior no SQLite.
3. Fazer um único POST de pairing, sem retry automático.
4. Validar installation_id exato, referências não secretas coerentes, Bearer e expiração.
5. Persistir nova credencial no SecretStore; depois persistir referências no SQLite.
6. Se uma escrita falhar, tentar restaurar o registro protegido anterior (incluindo geração), ou remover a nova credencial se não havia anterior.
7. Se compensação também falhar, reportar `INCONSISTENT_STATE` explicitamente.

Se o store ficar indisponível após o POST e a compensação for bem-sucedida, o resultado continua `SECURE_STORE_UNAVAILABLE`, com request_id preservado. Não é convertido em falha genérica do backend. Falha de compensação tem precedência e continua inconsistente.

SecretStore e SQLite não compartilham transação. Não existe atomicidade distribuída nem revogação remota inventada. Se o backend tiver consumido o código sem resposta, novo pairing/intervenção presencial pode ser necessário. Compensação local não desfaz o pairing remoto e a credencial antiga restaurada pode já ter sido revogada pelo backend. Sua evidência offline anterior não é restaurada; exige nova validação online.

O código de pairing é transitório, limpo do campo após cada tentativa, nunca armazenado ou logado. Em rotação bem-sucedida, a nova credencial substitui a antiga. Divergência ou expiração da resposta impede persistência. Falhas ou queda entre stores resultam em estado detectável na restauração, sem aceitação silenciosa de token.

## Restore, expiração e validação

`TerminalAuthenticationService` fornece futures sobre executor serial dedicado. O composition root fica em `infrastructure/auth`. SQLite, SecretStore e esperas HTTP nunca são executados na JavaFX Application Thread; atualizações visuais usam Platform.runLater. O fechamento de recursos também é feito no worker.

O restore lê instalação, referências e credencial; avalia expiração via Clock injetado antes de HTTP, depois da resposta e após gravar a evidência local. Regra estrita: **now >= expires_at exige reautenticação**, sem clock skew ou sessão infinita. Token expirado não é enviado nem habilita offline. A expiração protegida é a autoridade local; GET deve devolver expiração idêntica, sem extensão silenciosa.

Com API configurada e credencial não expirada, GET current terminal valida a identidade. `200` somente autentica quando installation_id, terminal_id, branch_id e expires_at correspondem. Divergência gera CONTEXT_MISMATCH, sem sobrescrever referências. Não há monitor de rede/polling; validação deriva de startup, pairing ou ação do usuário. A UI agenda apenas uma reavaliação local ao expirar um estado autenticado/offline.

## Estados explícitos

| Estado | Significado |
| --- | --- |
| NOT_PROVISIONED | Não há referências nem credencial; instalação é preservada. |
| PROVISIONED_NO_CREDENTIAL | Referências existem, mas credencial não; novo pairing necessário. |
| CREDENTIAL_PRESENT_UNVERIFIED | Token local não expirado, mas ainda sem validação online/evidência suficiente. |
| AUTHENTICATED | GET coerente aceito e evidência não secreta persistida. |
| OFFLINE_LIMITED | Identidade validada anteriormente, token não expirado, rede/timeout impeditivo. |
| REAUTHENTICATION_REQUIRED | Expiração local ou rejeição definitiva 401/403; novo pairing. |
| SECURE_STORE_UNAVAILABLE | Armazenamento protegido indisponível; operação bloqueada. |
| CONTEXT_MISMATCH | IDs ou expiração divergem da instalação/credencial conhecida. |
| LOCAL_STATE_UNAVAILABLE | Não foi possível ler/persistir estado local confiável. |
| VALIDATION_FAILED | Falha de API/protocolo fora da política offline; não autenticado. |
| INCONSISTENT_STATE | Credencial sem referências, envelope inválido ou compensação/delete que falhou. |
| BACKEND_NOT_CONFIGURED | Pairing solicitado sem URL configurada; nenhuma chamada. |

Sem token, referências locais não provam autenticação. Token sem provisionamento não é recuperado automaticamente por GET: exige intervenção/re-pairing. Credencial encontrada sem configuração da API permanece não verificada, nunca offline por conveniência.

## Rejeição, correlação e política offline

401 significa somente UNAUTHENTICATED: não se infere bloqueio, revogação, expiração remota ou situação de tenant/company/branch. 403 significa autorização/contexto negado. Ambos invalidam a evidência e tentam remover a credencial local; sucesso exige novo pairing. Falha de remoção é inconsistente, sem reaproveitar evidência para offline. Correlation request_id é preservado quando disponível; mensagens/payloads do backend não entram no snapshot ou erro do novo lifecycle.

V3 `terminal_validation` guarda geração, instalação, terminal, filial, identidade normalizada do backend e instante de validação. Nenhum segredo ou hash do token é armazenado. Antes de todo GET autenticado, essa evidência é invalidada duravelmente. Uma queda, rejeição ou falha de delete não permite reutilizar prova antiga após restart.

Somente NETWORK_UNAVAILABLE/TIMEOUT podem restaurar a evidência anterior e produzir OFFLINE_LIMITED, quando geração e todas as referências/backend correspondem, validação não está no futuro e credencial não expirou. Uma resposta 500, inválida, 401/403 ou erro de armazenamento nunca faz isso. Para segurança, uma queda do processo ou erro não elegível descarta a prova anterior e exige nova validação online antes de offline limitado.

Essa política **não autoriza vendas** nem constitui sessão do operador. Capacidades comerciais offline, catálogo, preços, outbox e sync pertencem às próximas fases aprovadas. O requisito de produto de continuidade offline permanece intacto.

## Remoção local e re-pairing

Forget invalida a evidência primeiro, remove a credencial e mantém installation_id e provisioning não secreto. Sem credencial, o estado é PROVISIONED_NO_CREDENTIAL (ou NOT_PROVISIONED se nunca houve referências). Delete é idempotente quando o provider está funcional; indisponibilidade é erro, não sucesso fictício.

Esta operação é local, não logout de operador nem revogação remota do terminal. Credencial perdida, expirada ou rejeitada requer novo código presencial do pairing existente. Nenhuma recuperação secreta alternativa foi criada. Envelope corrompido exige remoção local explícita antes de novo pairing; não é sobrescrito silenciosamente.

## Validação e limitações reais

- Baseline anterior à Fase 5: 179 testes padrão, 175 PASS, 4 SKIPPED DPAPI; 0 FAIL/ERROR.
- Trabalho preservado após a queda: 278 testes, 274 PASS, 4 SKIPPED; 0 FAIL/ERROR, revalidado nesta continuação.
- Continuação: 3 testes adicionais para indisponibilidade após pairing/compensação, inicialização concorrente do LazySecretStore e evidência SQLite corrompida no restore. Total final: 281, 277 PASS, 4 SKIPPED DPAPI Windows-only em Linux; 0 FAIL/ERROR.
- Integração opt-in reexecutada nesta continuação: LinuxSecretStoreIT, LinuxMachineCredentialRepositoryIT e LinuxSecretStoreUnavailableIT, 3 PASS. Chaves sintéticas isoladas, remoção confirmada em finally e D-Bus indisponível isolado sem alterar a sessão principal.
- Backend controlado PdvTestBackend, SQLite temporário e Clock.fixed; suíte padrão sem Internet.
- Linux: libsecret e JavaFX na sessão gráfica real; V3 aplicada após backup SQLite online seguro, instalação preservada. Nenhum pairing real ou credencial real usado.
- Windows: testes sintéticos/unitários e DPAPI existente preservado; runtime Windows não validado neste host Linux.
- macOS: fail-secure testado unitariamente; Keychain não implementado nem validado em host macOS.
- Banco operacional de desenvolvimento permanece preservado fora do repositório. Não há tabela de operador/senha/token nem venda/sync.

## Continuação após a queda de energia — 2026-10-10

Os 38 arquivos preservados (9 modificados e 29 novos) foram revisados integralmente a partir de `74459cf36dad8b987923a882647ca3b5771c526a`, sem descarte ou recriação da implementação. A correção funcional desta continuação preserva a classificação de indisponibilidade do store após pairing remoto e compensação bem-sucedida; um teste reproduziu a falha antes da correção. Os outros dois testes adicionais verificam inicialização concorrente do provider e falha segura com evidência corrompida.

`./mvnw test` e `./mvnw clean package` passaram com 281 testes; `git diff --check` passou. Os três ITs Linux passaram separadamente, incluindo cleanup sintético confirmado. O sandbox bloqueou sockets do backend HTTP local na primeira tentativa da suíte; a execução autorizada fora dele passou, sem relaxamento de segurança no código.

`./mvnw javafx:run` abriu a janela real de 1000×700 em DISPLAY=:0, sem Xvfb. O estado inicial foi NOT_PROVISIONED, com API não configurada e operador indisponível. A ação Verificar conexão respondeu; o dump de threads mostrou a JavaFX Application Thread no loop gráfico e o worker ocioso após restore. Encerramento normal por WM_DELETE_WINDOW e BUILD SUCCESS. Não houve pairing real, erro LOCALAPPDATA, indisponibilidade indevida do store ou segredo exibido.

Banco atual e backup existente passaram novamente em integrity_check após o runtime. O atual manteve V1/V2/V3, o backup manteve V1/V2, ambos com `installation_id` `fd40d440-b972-4ee1-a182-7ad62ad3ec52`. V3 não foi alterada nesta continuação, não houve V4, rollback ou backup adicional. Autenticação e sessão do operador continuam pendentes do contrato backend; Fase 5 parcial e Fase 6 não iniciada.
