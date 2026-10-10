# LucraOne PDV Java — Roadmap

**Data inicial:** 2026-10-07
**Estado:** Fundação, Fase 2 — Persistência local, Fase 3 — Provisionamento e configuração do terminal e Fase 4 — Contrato e conectividade com a API concluídas.

**Plataformas-alvo:** Windows, Linux e macOS. O LucraOne PDV Java é um aplicativo Java multiplataforma, não um aplicativo Windows escrito em Java.
**Commit base:** `bc28f68fe0b05239df7e129ae8cb7f73113ab924`

Este documento registra a direção técnica inicial do PDV. Ele é versionável, mas não substitui decisões de produto, fiscais ou de operação. As fases futuras só começam após aprovação explícita.

## Visão de arquitetura

```text
LucraOne Backend / API (autoridade central)
              |
          HTTPS / REST
              |
LucraOne PDV Java (um terminal)
  +-- presentation: JavaFX
  +-- application: casos de uso, portas e orquestração — independente de SO
  +-- domain: regras e modelos de negócio — independente de SO
  +-- infrastructure: adapters concretos
        +-- persistence: SQLite / Flyway
        +-- api: HTTPS / REST
        +-- security: WindowsSecretStore | LinuxSecretStore | MacOsSecretStore
        +-- platform: Windows | Linux | macOS
```

A arquitetura em quatro camadas está aprovada e deve permanecer simples: `domain` não depende de JavaFX, HTTP ou SQLite; `application` declara portas necessárias; `infrastructure` as implementa; e `presentation` somente conduz a interação do operador. Não há necessidade de separar módulos Maven agora. Quando os adaptadores crescerem, subpacotes por capacidade (`persistence`, `api`, `sync`, `hardware`, `logging`) são suficientes.

### Independência de sistema operacional

`domain` e `application` são 100% independentes de sistema operacional. É proibido introduzir nessas camadas `System.getProperty("os.name")`, `LOCALAPPDATA`, `APPDATA`, variáveis `XDG_*`, DPAPI, `CryptProtectData`, libsecret ou Keychain. Esses detalhes pertencem exclusivamente a `infrastructure`, atrás de portas.

Um teste de fronteira (`ApiArchitectureBoundaryTest`) lê a árvore de fontes e falha se uma camada interna passar a depender de transporte, UI, adapter concreto ou detalhe de plataforma.

## Princípios arquiteturais aprovados

- Um banco local por terminal; nunca um arquivo SQLite compartilhado pela rede.
- Backend LucraOne como autoridade central dos cadastros e da consolidação operacional.
- Venda criada localmente como registro imutável, identificada por UUID e enviada com chave de idempotência.
- Cache local versionado para catálogo, preço e dados mínimos de cliente.
- Outbox local durável para alterações que ainda precisam ser entregues ao backend.
- Operação degradada explícita: o sistema informa quando usa dados locais e nunca finge estar sincronizado.
- Separação entre venda, pagamento, caixa, sincronização e fiscal.

## Fonte da verdade aprovada

| Dado | Autoridade | Regra local |
| --- | --- | --- |
| Produto | Backend | Cache somente leitura, com versão e data da última atualização. |
| Preço | Backend | Snapshot de tabela de preços publicada; a venda guarda preço, regra e versão usados. |
| Cliente | Backend | Cache parcial para atendimento; alterações dependem de política posterior. |
| Estoque | Backend | Snapshot indicativo, não saldo local autoritativo. |
| Venda | PDV na criação; backend na consolidação | Registro local imutável até entrega confirmada. |
| Pagamento | PDV na captura; backend na consolidação | Associado à venda, nunca substitui a venda. |
| Configuração de terminal | Mista | Backend define identidade e políticas; o PDV conserva estado operacional local. |

Venda e pagamento são criados localmente quando necessário. Enquanto não sincronizados, o PDV é a autoridade operacional daquela transação. Após a confirmação e consolidação, o backend é a referência central, sem que isso elimine o registro local, que poderá ser preservado conforme a estratégia futura de retenção e auditoria.

## Persistência local aprovada

SQLite por terminal é uma decisão aprovada. Cada caixa terá o seu próprio banco local e todos sincronizarão com o backend LucraOne. Ele é embarcado, transacional, maduro, funciona sem serviço adicional e é adequado para uma aplicação desktop com um processo principal e poucas conexões locais. Atende bem vendas locais, cache, outbox, configurações e contingência temporária.

Limitações a assumir desde o início:

- Escrita concorrente é serializada; não deve haver múltiplos PDVs escrevendo no mesmo arquivo.
- Não deve ser compartilhado por rede, SMB ou NAS, nem usado como um único arquivo para múltiplos terminais.
- Não resolve por si só conflitos de catálogo, estoque ou duplicidade de eventos.
- Backup, recuperação e migrações locais precisam ser planejados.
- Dados sensíveis exigem proteção adicional; SQLite puro não é uma solução de cofre de segredos.

SQLite deixa de ser adequado se houver necessidade de servidor local de loja, muitas escritas concorrentes independentes ou administração central de um banco local. Nesses casos, PostgreSQL local ou uma arquitetura de servidor de loja deverão ser reavaliados.

Alternativas consideradas:

- **PostgreSQL local:** excelente concorrência e recursos, mas adiciona serviço, instalação e suporte operacional; excessivo para um caixa único.
- **MySQL local:** mesma desvantagem de serviço local, sem benefício claro neste cenário.
- **H2:** prático para testes e protótipos, mas menos indicado como armazenamento operacional de longo prazo de um PDV Windows.
- **Firebird embedded:** alternativa válida no ecossistema desktop, mas SQLite tem ecossistema Java, operação e adoção mais simples para este caso.
- **SQL Server Express:** robusto, porém pesado e operacionalmente desnecessário para cada terminal.
- **Outros bancos embarcados:** só devem ser considerados se trouxerem uma necessidade concreta não atendida por SQLite.

A persistência local utiliza **Flyway** para migrations locais e **JDBC puro com repositórios pequenos** para acesso a dados. JDBC mantém comportamento e SQL previsíveis em SQLite; JDBI pode ser reavaliado se o mapeamento repetitivo se tornar um custo real. Hibernate/JPA não é a escolha inicial para esse núcleo transacional local.

### Classificação de dados no SQLite

| Classe | Exemplos | Observação |
| --- | --- | --- |
| Dados locais autoritativos | venda, item de venda, pagamento capturado, sessão de caixa, movimentos locais, outbox | Criados no terminal; não são descartados até reconciliação segura. |
| Cache do backend | produto, preço, cliente mínimo, tabelas auxiliares, snapshot de estoque | Possuem versão, origem e data de atualização. |
| Fila de sincronização | evento, payload, chave de idempotência, tentativas, próximo retry, erro | Durável e auditável; não é apenas uma fila em memória. |
| Configuração local | `installation_id`, terminal provisionado, checkpoints, preferências operacionais, diagnóstico | Segredos não devem ficar em texto puro. |

## Terminal e configuração

Cada instalação deve gerar e manter um `installation_id` local (UUID). O backend deve atribuir ou validar `terminal_id`, loja/filial, permissões e políticas. O PDV pode informar `device_name`, versão do aplicativo e características do ambiente como telemetria, mas não deve ser autoridade para filial ou identidade comercial.

A configuração seguirá uma combinação:

- arquivo de bootstrap não secreto para parâmetros iniciais, como URL do ambiente;
- SQLite para estado operacional, terminal provisionado e checkpoints;
- armazenamento protegido nativo da plataforma para tokens e material sensível, atrás da porta `SecretProtector`. O adapter Windows com DPAPI no escopo do usuário atual está disponível desde a Fase 3; Linux e macOS têm direção aprovada e implementação futura;
- `Preferences API` somente para preferências não críticas do usuário, se necessário.

### Diretório operacional local aprovado

O banco e os artefatos operacionais não ficarão no repositório, junto do código-fonte ou em diretório versionado. A estrutura interna é a mesma em todas as plataformas:

```text
<diretório de dados da aplicação>/LucraOne/PDV/
  data/
    lucraone-pdv.db
  logs/
  config/
```

A raiz é resolvida de forma nativa por plataforma. Isso **não** é uma regra universal baseada em `%LOCALAPPDATA%`:

| Plataforma | Raiz | Situação |
| --- | --- | --- |
| Windows | `%LOCALAPPDATA%` | implementado |
| Linux | XDG Base Directory: `$XDG_DATA_HOME`, com fallback `~/.local/share` | a implementar |
| macOS | `~/Library/Application Support` | a implementar |

Hoje `infrastructure.persistence.LocalDataDirectory` resolve `LOCALAPPDATA` diretamente e falha de forma explícita quando a variável não existe, sem escolher diretório alternativo. Em Linux e macOS a aplicação abre, informa que não pôde preparar o banco local e não cria arquivo nenhum.

A resolução nativa pertence a `infrastructure/platform`, atrás de uma porta como `PlatformPaths`, e é um ajuste arquitetural próprio, com testes específicos por plataforma. Ele foi deliberadamente mantido fora da fase de API e deve ser concluído antes da operação completa em Linux ou macOS.

## Offline-first e sincronização

A operação offline deve ser construída por etapas, não presumida apenas porque há SQLite. O modelo aprovado é local-first para operações autorizadas, com sincronização assíncrona e estado visível ao operador.

Comportamento alvo:

- **Internet cai antes da venda:** usar catálogo e políticas locais ainda válidos, indicando modo degradado.
- **Cai durante a venda:** finalizar a transação local de forma atômica; a entrega ao backend fica pendente.
- **Internet volta:** executar pull incremental e push da outbox com retentativas controladas.
- **Backend indisponível:** manter dados pendentes localmente e apresentar diagnóstico, sem repetir envios cegamente.
- **Sync falha:** registrar motivo, aumentar o intervalo de retry e permitir suporte operacional consultar o erro.
- **Venda enviada sem resposta:** reenviar a mesma chave de idempotência; o backend deve retornar o resultado da operação original, não duplicar a venda.

Cada evento sincronizável deve ter UUID estável, `idempotency_key`, versão do payload e timestamps. Uma máquina de estados inicial pode usar `PENDING`, `SYNCING`, `SYNCED`, `RETRY_SCHEDULED` e `ERROR_REQUIRES_ACTION`. `SYNCING` não é estado final e deve ser recuperável após queda do processo.

O sincronizador deve ter push, pull incremental, checkpoint por recurso, retry com backoff e limite de tentativas automáticas. Um full sync controlado é mecanismo de recuperação, não o caminho normal. A outbox local deve ser gravada na mesma transação da venda ou do pagamento que ela representa.

### Conflitos e políticas futuras

- Catálogo e preço novos do backend valem para novas operações depois da atualização local.
- Venda offline preserva o preço publicado que estava no terminal no momento da conclusão; a venda deve carregar versão/tabela de preço aplicada e não ser alterada retroativamente pelo sync.
- Produto desativado depois de estar em cache exige política comercial: bloquear novas vendas offline ou permitir apenas em janela de validade definida. Esta decisão ainda é pendente.
- Cliente é atualizado preferencialmente pelo backend; mudanças locais futuras precisam de versão e regra de resolução.
- Estoque divergente é reconciliado centralmente. O PDV não deve tentar resolver conflito de saldo sozinho.

Preço deve ser cacheado com versão, período de vigência e data de atualização. Para venda offline, a regra aprovada é aplicar o preço publicado disponível localmente no momento da conclusão e registrar essa evidência. A validade máxima do cache e a política de bloqueio após expiração continuam pendentes de decisão de negócio.

Estoque local deve ser somente snapshot/cache. Em múltiplos caixas, nenhum terminal conhece o saldo global em tempo real durante queda de internet. A regra de permitir, limitar ou bloquear venda offline por risco de estoque precisa ser definida pela operação; qualquer permissão deve gerar reconciliação posterior.

Clientes não devem ser sincronizados integralmente sem necessidade. Recomenda-se busca online quando disponível e cache parcial de clientes recentes ou vinculados a operações locais, com retenção mínima de dados pessoais.

## Domínio futuro (conceitual)

Os conceitos abaixo orientam conversas futuras; não representam classes existentes nem contrato definitivo:

- `Terminal`, `TerminalConfiguration`, `Installation` e `Branch`;
- `Operator`, credencial e sessão operacional;
- `Product`, `Price`, tabela/regra de preço e snapshot de estoque;
- `Customer` com dados mínimos necessários ao atendimento;
- `Sale`, `SaleItem`, descontos autorizados e cancelamentos;
- `Payment`, alocação de pagamentos, troco e múltiplas formas;
- `CashSession`, abertura, suprimento, sangria e fechamento;
- `SyncEvent`, `OutboxEntry`, checkpoint e diagnóstico de sincronização.

Venda e pagamento devem permanecer distintos: uma venda pode ter diversos pagamentos, dinheiro pode gerar troco e estados de autorização de cartão não são o mesmo estado comercial da venda.

## Segurança, observabilidade e testes

O mínimo seguro inclui não armazenar senhas em SQLite, proteger tokens no armazenamento nativo do sistema operacional, restringir permissões do diretório da aplicação, evitar dados sensíveis em logs e manter retenção controlada de clientes em cache. O adapter Windows com DPAPI no escopo do usuário atual está disponível desde a Fase 3; os adapters de Linux e macOS têm direção aprovada e implementação futura, e enquanto não existirem nenhuma credencial é persistida. Não há fallback inseguro: sem store nativo, a política é falhar de forma segura. Criptografia do banco deve ser decidida conforme a classificação de dados e a política operacional; se adotada, a gestão da chave é tão importante quanto a cifra.

Em transporte, TLS nunca é enfraquecido: nenhum `SSLContext`, `TrustManager` ou verificador de hostname é substituído, e redirecionamentos não são seguidos. O logging da API é sanitizado — registra operação, método, caminho lógico, status, duração e `request_id`, e nunca corpo, `Authorization`, access token, pairing code ou host do backend.

Logs locais rotativos devem registrar nível, versão do aplicativo, correlação de sync, falhas de hardware e erros sem credenciais ou payloads sensíveis. Deve existir material de diagnóstico para suporte, sem telemetria automática não aprovada.

Testes devem começar cedo com unidade para domínio e aplicação, integração SQLite para migrations/repositórios e testes de idempotência/outbox. Os testes de contrato da API já existem, contra servidor HTTP local do próprio JDK, e a suíte é integralmente independente da Internet; smoke contra ambiente real é opt-in por variável de ambiente. Depois entram cenários offline/sync e testes de UI JavaFX focados nos fluxos críticos. GitHub Actions é recomendado futuramente para `test` e `package`; empacotamento pode ser adicionado quando houver distribuição.

## Fases

### Fase 1 — Fundação ✅

- **Objetivo:** estabelecer um projeto desktop Java compilável e documentado.
- **Entregas:** Java 25, JavaFX, Maven Wrapper, JUnit, camadas iniciais, README, build local e primeiro commit.
- **Fora de escopo:** dados, API, autenticação e fluxos de PDV.
- **Aceite:** `test` e `clean package` com sucesso; aplicação JavaFX abre.
- **Dependências:** nenhuma.
- **Base:** `ad78522759eba6a5e9c67c9bb8524a98a236345b`.

### Fase 2 — Persistência local ✅

**Concluída ✅**

- **Objetivo:** introduzir armazenamento local versionado, sem regra de venda ou integração remota.
- **Entregas:** SQLite, driver JDBC SQLite, Flyway, localização em `%LOCALAPPDATA%`, criação/abertura de banco, migrations, transações, repositórios base somente quando necessários, testes de integração e definição de estratégia básica de recuperação/backup.
- **Fora de escopo:** catálogo, login, sync, vendas, pagamentos, API e fiscal.
- **Aceite:** banco novo e banco atualizado chegam ao schema esperado de forma repetível; testes isolados passam.
- **Dependências:** decisões aprovadas de SQLite, Flyway, JDBC e diretório operacional; definição da estratégia de backup e recuperação.
- **Base:** `769dc71c37a9f4c69b896c2e01dcf4830ad81981`.

### Fase 3 — Provisionamento e configuração do terminal ✅

**Concluída ✅**

- **Objetivo:** definir a identidade local e a configuração operacional inicial do terminal.
- **Entregas:** modelo de `installation_id`, configuração bootstrap, armazenamento seguro de segredos e diagnóstico básico.
- **Fora de escopo:** login completo, catálogo e venda.
- **Aceite:** terminal pode ser identificado, reaberto e diagnosticado sem expor segredos.
- **Dependências:** Fase 2 e definição de provisionamento pelo backend.
- **Base:** `bc28f68fe0b05239df7e129ae8cb7f73113ab924`.

### Fase 4 — Contrato e conectividade com a API ✅

**Concluída ✅**

- **Objetivo:** validar o contrato mínimo entre PDV e backend antes de fluxos comerciais.
- **Entregas:** especificação versionada em [docs/api-contract.md](docs/api-contract.md), porta de aplicação `PdvBackendGateway`, adapter HTTPS com o `HttpClient` do JDK, contratos `v1` de health/pareamento/terminal autenticado, correlação `X-Request-ID`, timeouts explícitos, retry assimétrico, erros tipados e testes de contrato contra servidor HTTP local.
- **Fora de escopo:** sincronização completa, venda, login de operador e persistência da machine credential.
- **Aceite:** ambiente de teste comprova chamadas autenticáveis, versionadas e observáveis.
- **Dependências:** Fase 3 e auditoria dos requisitos do Laravel.
- **Nota:** a machine credential existe somente em memória. A persistência segura depende de secret store por plataforma e pertence à Fase 5.

### Fase 5 — Autenticação e sessão operacional

**Planejada · próxima prioridade**

- **Objetivo:** estabelecer operador, terminal e regras de sessão.
- **Entregas:** fluxo de autenticação aprovado, token protegido, expiração, logout e política offline limitada.
- **Fora de escopo:** autorização TEF, fiscal e venda completa.
- **Aceite:** não há senha persistida localmente; expiração e reautenticação têm comportamento definido.
- **Dependências:** Fase 4 e política de segurança/produto.
- **Pré-requisito obrigatório:** antes de persistir a machine credential é necessário reavaliar e decidir
  (1) o `SecretStore` multiplataforma — Windows/DPAPI, Linux/Secret Service ou equivalente seguro,
  macOS/Keychain — e (2) o `PlatformPaths` multiplataforma — Windows/`LOCALAPPDATA`, Linux/XDG,
  macOS/Application Support. Sem store nativo a política é falhar de forma segura: nunca gravar segredo em
  texto puro, em `properties`, em arquivo ou no SQLite sem proteção.

### Fase 6 — Catálogo e preços locais

- **Objetivo:** disponibilizar dados de venda consistentes no terminal.
- **Entregas:** cache de produtos, preço versionado, pull incremental, busca e indicadores de atualização.
- **Fora de escopo:** baixa de estoque e fechamento comercial.
- **Aceite:** catálogo pode ser atualizado, consultado localmente e sua validade é visível.
- **Dependências:** Fases 2, 4 e 5; contrato de delta sync.

### Fase 7 — Núcleo transacional de vendas

- **Objetivo:** registrar venda local com consistência e auditoria.
- **Entregas:** venda, itens, validações aprovadas, UUID, preço aplicado, outbox e testes de atomicidade.
- **Fora de escopo:** fiscal, TEF e periféricos.
- **Aceite:** queda do processo não produz venda parcialmente gravada; reenvio não duplica evento.
- **Dependências:** Fases 2, 5 e 6; regras comerciais aprovadas.

### Fase 8 — Pagamentos e caixa

- **Objetivo:** tratar pagamentos e a operação de caixa sem misturá-los à venda.
- **Entregas:** dinheiro, PIX, múltiplas formas, troco, sessão de caixa, abertura, sangria e fechamento.
- **Fora de escopo:** integração TEF certificada e fiscal.
- **Aceite:** totais e estados de venda/pagamento/caixa são reconciliáveis.
- **Dependências:** Fase 7 e regras financeiras da operação.

### Fase 9 — Sincronização e resiliência offline — endurecimento

- **Objetivo:** endurecer o mecanismo que começa gradualmente nas fases anteriores, tornando o fluxo local resiliente a falhas de conectividade.
- **Entregas:** retry/backoff, recovery, tratamento de resposta perdida, conflitos, full sync controlado, observabilidade de sync, testes de falha e reconciliação. A Fase 4 define contrato/API; a Fase 6 já faz pull de catálogo/preços; a Fase 7 cria a outbox de venda; e a Fase 8 gera eventos de pagamento/caixa quando aplicável.
- **Fora de escopo:** multi-terminal compartilhando SQLite.
- **Aceite:** cenários de queda, reenvio, resposta perdida e reconciliação passam em testes automatizados.
- **Dependências:** Fases 4, 6, 7 e 8; idempotência suportada pelo backend.

### Fase 10 — Periféricos

- **Objetivo:** integrar dispositivos sem acoplar o núcleo a fornecedores específicos.
- **Entregas:** portas/adaptadores para scanner, impressora térmica, gaveta, balança e avaliação TEF.
- **Fora de escopo:** fiscal.
- **Aceite:** dispositivos suportados possuem diagnóstico, fallback e testes com simuladores quando possível.
- **Dependências:** fluxos de venda e caixa estáveis.

### Fase 11 — Empacotamento e operação multiplataforma

- **Objetivo:** distribuir e atualizar o aplicativo de forma controlada nas plataformas-alvo.
- **Entregas:** avaliação de `jpackage`, diretórios operacionais nativos por plataforma, upgrade e rollback
  documentados, e um artefato por plataforma:
  - Windows: MSI/EXE;
  - Linux: formato a decidir (`.deb`, AppImage, Flatpak ou outro);
  - macOS: DMG/PKG conforme estratégia futura, incluindo assinatura e notarização.
- **Fora de escopo:** auto-update sem política de segurança e suporte.
- **Aceite:** instalação limpa, atualização e desinstalação testadas em cada plataforma que o produto decidir suportar na distribuição.
- **Dependências:** aplicação funcional, `PlatformPaths` multiplataforma concluído e decisão de distribuição.

### Fase 12 — Fiscal futura

- **Objetivo:** integrar requisitos fiscais sem contaminar o núcleo comercial.
- **Entregas:** desenho separado para NFC-e/NF-e, certificados, contingência, SEFAZ e auditoria.
- **Fora de escopo:** antecipar implementação fiscal nas fases iniciais.
- **Aceite:** escopo, responsabilidade e homologação fiscal aprovados antes do desenvolvimento.
- **Dependências:** venda, pagamentos, operação e requisitos legais definidos.

## Decisões arquiteturais

| Decisão | Estado | Registro |
| --- | --- | --- |
| Java 25, JavaFX e Maven Wrapper | Aprovada | Já fazem parte da fundação. |
| Arquitetura em camadas | Aprovada | `domain`, `application`, `infrastructure` e `presentation`, sem módulos Maven separados nesta fase. |
| SQLite por terminal | Aprovada | Banco isolado por caixa; nunca compartilhado por rede, SMB ou NAS. |
| Backend como autoridade central | Aprovada | Autoridade de produto, preço, cliente, estoque, configuração comercial e consolidação de vendas/pagamentos. |
| Offline-first gradual | Aprovada | Evolução em fases, com operação degradada explícita e limites operacionais ainda a definir. |
| UUID e idempotência | Aprovada | Eventos relevantes terão UUID, chave de idempotência, payload versionado e timestamps; o backend deve reconhecer reenvios. |
| Outbox local | Aprovada | Durável no SQLite e gravada na mesma transação da operação de origem quando aplicável. |
| Flyway | Aprovada | Migrations locais versionadas. |
| JDBC puro | Aprovada | Repositórios pequenos, SQL explícito e previsível; JDBI só será reavaliado se houver custo real de repetição. |
| Estoque local não autoritativo | Aprovada | Apenas snapshot/cache; nenhum terminal garante saldo global em tempo real durante queda de internet. |
| Diretório operacional fora do repositório | Aprovada | Estrutura `LucraOne/PDV/{data,logs,config}` fora do repositório e de diretórios versionados, com raiz nativa por plataforma. |
| Aplicação multiplataforma | Aprovada | Plataformas-alvo Windows, Linux e macOS. O PDV não é um aplicativo Windows escrito em Java. |
| `domain` e `application` independentes de SO | Aprovada | Nenhum detalhe de plataforma nas camadas internas; verificado por teste de fronteira de arquitetura. |
| Adapters específicos em `infrastructure` | Aprovada | Todo detalhe de SO vive atrás de porta, em `infrastructure/security` e `infrastructure/platform`. |
| `SecretStore` por plataforma | Direção aprovada · implementação futura | Windows/DPAPI disponível; Linux/Secret Service e macOS/Keychain pendentes. Sem fallback inseguro: a política é falhar de forma segura. |
| `PlatformPaths` por plataforma | Direção aprovada · implementação futura | Windows/`LOCALAPPDATA`, Linux/XDG, macOS/Application Support. Pré-requisito para operação completa fora do Windows. |
| Cliente HTTP do JDK | Aprovada | `java.net.http.HttpClient` com `sendAsync`; nenhuma biblioteca HTTP de terceiros foi adicionada. |
| Jackson para JSON | Aprovada | `jackson-databind`, restrito aos DTOs de transporte em `infrastructure/api/dto`. |
| Erros de API como exceção tipada | Aprovada | `PdvApiException` com `PdvApiFailure`, consistente com o estilo já usado em persistência e segurança. |
| Retry assimétrico | Aprovada | Leituras idempotentes podem repetir uma vez; o `POST` de pareamento nunca repete, pois o código é de uso único. |
| Machine credential somente em memória | Aprovada até a Fase 5 | Nenhuma persistência de token enquanto não houver secret store multiplataforma. |

## Decisões que continuam pendentes

- formato de distribuição em Linux (`.deb`, AppImage, Flatpak ou outro);
- estratégia de assinatura e notarização em macOS;
- mecanismo definitivo de secret store em Linux e em macOS;
- prazo máximo de validade do cache de preço;
- política para produto desativado durante operação offline;
- política de estoque offline: permitir, bloquear, limitar ou alertar em risco de saldo insuficiente;
- tamanho e retenção do cache local de clientes;
- login offline e expiração de sessão offline;
- TEF e fornecedores/homologações associados;
- fiscal, incluindo NFC-e, NF-e, certificado, contingência e SEFAZ;
- auto-update e processo de distribuição;
- necessidade futura de servidor local de loja.

## Riscos principais

- Duplicidade de venda quando a resposta do backend se perde.
- Conflito de preço, produto desativado e estoque durante operação offline.
- Definição insuficiente do contrato de sincronização e de idempotência no backend.
- Exposição de dados de cliente, token ou log no terminal.
- Falha de migration ou corrupção local sem estratégia de recuperação.
- Backup local, cópia segura antes de migration, recuperação após falha e tratamento de banco corrompido ainda precisam de definição operacional.
- Dependências de hardware, drivers e fornecedores de TEF/impressão.
- Escopo fiscal e requisitos regulatórios introduzidos antes da maturidade do núcleo.
- Suporte operacional a muitos terminais sem telemetria e diagnóstico adequados.

## Fora do escopo inicial

- Fiscal: NFC-e, NF-e, contingência, certificado e SEFAZ.
- TEF e homologações específicas de adquirentes.
- Auto-update sem processo de distribuição aprovado.
- Servidor local de loja ou banco compartilhado por múltiplos terminais.
- Arquitetura multi-terminal com SQLite em rede.
- Integrações não necessárias ao núcleo inicial de PDV.

## Pontos a verificar no backend antes da integração

- autenticação de operador e vínculo com terminal/filial;
- endpoints e permissões para provisionamento, catálogo, preço, cliente, venda e pagamento;
- idempotência persistente por chave e retorno de resultado prévio;
- versionamento de API, DTOs, paginação, cursor/delta sync e timestamps em UTC;
- `deleted_at` ou outro mecanismo de remoção propagável;
- versão de recurso/ETag para conflitos;
- política de estoque, preço offline e reconciliação;
- limites de payload, rate limit, observabilidade e ambiente de homologação.
