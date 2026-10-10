# LucraOne PDV Java

Aplicativo desktop de Ponto de Venda do ecossistema LucraOne.

## Sobre o projeto

O LucraOne PDV Java será o aplicativo desktop responsável pela operação de ponto de venda do ecossistema LucraOne. Ele será executado localmente nos computadores dos caixas e, futuramente, se comunicará com o backend central do LucraOne.

O backend central é um projeto separado, baseado em Laravel e executado em servidor ou VPS. Esta aplicação não contém nem modifica o backend.

O uso de uma aplicação desktop oferece a base necessária para a operação no terminal de caixa e para futuras integrações com periféricos. A arquitetura será preparada para permitir operação local e sincronização posterior durante indisponibilidades temporárias de internet.

O PDV é um aplicativo Java **multiplataforma**, não um aplicativo Windows escrito em Java. As camadas `domain` e `application` são independentes de sistema operacional; detalhes de plataforma ficam restritos a adapters em `infrastructure`.

Plataformas-alvo: **Windows, Linux e macOS**.

Status de validação: Windows e Linux já participam da validação de desenvolvimento. macOS é plataforma-alvo, mas ainda não foi executado nem validado. O empacotamento de distribuição continua sendo trabalho futuro em todas as plataformas.

A infraestrutura de persistência local com SQLite está implementada, assim como o contrato e a conectividade com a API. Operação offline e sincronização continuam sendo etapas futuras.

## Arquitetura conceitual

```text
LucraOne Backend / API
        |
        | HTTPS / REST
        |
LucraOne PDV Java
        |
        +-- Persistência local
        +-- Operação offline
        +-- Sincronização
        +-- Periféricos
        +-- Interface de caixa
```

O desenho apresenta a direção arquitetural do produto. A persistência local já está implementada; operação offline, sincronização, periféricos e demais integrações continuam planejadas para fases futuras.

## Tecnologias

- Java 25 LTS
- JavaFX 25.0.3
- Maven
- Maven Wrapper
- JUnit 5
- SQLite JDBC
- Flyway

- Jackson Databind (JSON da API)
- `java.net.http.HttpClient` do JDK

Sincronização e recursos fiscais pertencem ao planejamento futuro e não fazem parte da implementação atual.

## Requisitos

- ambiente desktop Windows, Linux ou macOS;
- JDK 25;
- `JAVA_HOME` configurado para o JDK instalado;
- Git.

`JAVA_HOME` deve apontar para o diretório do JDK instalado. Os caminhos variam por plataforma e por forma de instalação; os exemplos abaixo não são requisitos universais:

- Windows: `C:\Program Files\Java\jdk-25.0.3`
- Linux: `/usr/lib/jvm/java-25-openjdk-amd64`
- macOS: saída de `/usr/libexec/java_home -v 25`

Não é necessário instalar Maven globalmente, pois o projeto utiliza o Maven Wrapper.

## Executando o projeto

Na raiz do repositório.

Linux e macOS:

```bash
./mvnw javafx:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd javafx:run
```

## Executando os testes

```bash
./mvnw test
```

```powershell
.\mvnw.cmd test
```

A suíte é integralmente independente da Internet: os testes de API usam um servidor HTTP local do próprio JDK.

## Gerando o build

```bash
./mvnw clean package
```

```powershell
.\mvnw.cmd clean package
```

O comando gera o artefato Maven de validação. Ainda não existe instalador para nenhuma plataforma.

## Persistência local

O banco SQLite de cada terminal fica em `data/lucraone-pdv.db`, sob a base operacional:

| Plataforma | Base |
| --- | --- |
| Windows | `%LOCALAPPDATA%\LucraOne\PDV` |
| Linux | `$XDG_DATA_HOME/LucraOne/PDV`, ou `~/.local/share/LucraOne/PDV` |
| macOS | `~/Library/Application Support/LucraOne/PDV` |

`PlatformPaths` centraliza a resolução. Windows preserva a árvore existente e falha se a variável necessária estiver ausente. Linux usa XDG absoluto; configuração fica em `$XDG_CONFIG_HOME/LucraOne/PDV`, ou `~/.config/LucraOne/PDV`. No macOS, configuração fica em `config` na mesma base. Migrations Flyway executam antes de o aplicativo ficar disponível.

SQLite, Flyway e estabilidade do `installation_id` foram testados em duas execuções JavaFX na sessão gráfica real do Ubuntu 24.04. Os paths macOS foram testados unitariamente, sem validação em host macOS. Detalhes em [Plataforma e armazenamento seguro](docs/platform.md).

Os testes usam bancos temporários e não acessam o banco local do usuário. A política inicial de backup e recuperação está documentada em [Persistência local](docs/local-database.md).

## Conectividade com a API

O PDV consome o contrato `v1` do backend: health, pareamento de terminal e leitura autenticada do terminal. A URL base nunca está no código: vem de `api.base-url` na configuração bootstrap.

TLS não é enfraquecido em nenhuma hipótese, `X-Request-ID` correlaciona cada chamada, timeouts são explícitos, o retry é assimétrico (leituras podem repetir uma vez; o pareamento nunca repete) e a machine credential possui lifecycle seguro via SecretStore na Fase 5. Detalhes em [Contrato da API PDV](docs/api-contract.md).

## Autenticação do terminal — Fase 5 parcial

O pairing presencial utiliza o `installation_id` existente. A credencial retornada é armazenada exclusivamente no SecretStore nativo: Windows/DPAPI e Linux/libsecret. O restore compara IDs e expiração antes de considerar o terminal autenticado. `401`/`403` removem a credencial local e exigem novo pairing; expiração usa `now >= expires_at`, sem margem adicional.

`OFFLINE_LIMITED` exige validação online anterior persistida e credencial ainda válida. Representa somente identidade previamente conhecida; não autoriza vendas nem autentica operador. A remoção local da credencial preserva instalação e referências do provisionamento, sem revogação remota inventada. Operações SQLite, nativas e HTTP ficam fora da thread JavaFX.

**Operator authentication backend contract unavailable.** Autenticação e sessão do operador permanecem pendentes. macOS continua bloqueado para credencial persistente até implementação e validação real do Keychain. Veja [Autenticação e sessão](docs/authentication-session.md).

Integração Linux opt-in, somente com dados sintéticos e cleanup confirmado:

```bash
./mvnw test -Dtest=LinuxSecretStoreIT,LinuxMachineCredentialRepositoryIT
```

## Identidade e configuração do terminal

Na inicialização, o PDV garante um `installation_id` local e estável, informa se o terminal está provisionado e lê a configuração bootstrap opcional em `PlatformPaths.configDirectory()/bootstrap.properties`, com restore assíncrono da autenticação do terminal. Se houver credencial válida e API configurada, valida a identidade via `GET /api/v1/pdv/terminal`. Sem credencial, não chama o backend. Detalhes em [Identidade e configuração do terminal](docs/terminal-configuration.md).

## Estrutura do projeto

```text
src/
  main/
    java/br/com/lucraone/pdv/
      domain/
      application/
        auth/
        api/
        security/
        terminal/
      infrastructure/
        auth/
        api/
          dto/
          error/
        configuration/
        persistence/
        platform/
        security/
      presentation/
        view/
    resources/
  test/
    java/
```

- `domain`: regras de negócio puras, independentes de sistema operacional, HTTP, JSON e UI.
- `application`: casos de uso, portas e coordenação. Também independente de sistema operacional.
- `infrastructure`: adapters concretos — persistência, API HTTPS, segredos por plataforma e caminhos por plataforma.
- `presentation`: JavaFX e interação com o usuário. Nunca fala HTTP diretamente.

`infrastructure/platform` implementa paths por plataforma. `SecretStore` declara save/load/delete: Windows reutiliza DPAPI e Linux usa libsecret/Secret Service. O limite macOS falha explicitamente; integração Keychain real permanece pendente. A porta `MachineCredentialRepository` compõe o SecretStore com um envelope protegido e versionado, sem token no SQLite.

## Desenvolvimento local

O aplicativo desktop é desenvolvido e testado localmente em Windows e em Linux. macOS é plataforma-alvo, porém ainda não validado. O backend LucraOne é um projeto separado; este repositório não inclui comandos ou código do backend Laravel.

## Estado atual

Fundação e Fases 2–4 concluídas. **Fase 5 — EM ANDAMENTO / PARCIAL**: lifecycle da machine credential, restore, expiração, validação online, offline limitado e remoção local implementados. Autenticação e sessão do operador dependem de contrato oficial do backend; nenhum endpoint foi inventado. Fase 6 não iniciada.

O PDV **não** está operacional para venda. Autenticação de operador, catálogo, preços, venda, pagamentos, sincronização, periféricos, empacotamento e fiscal continuam pendentes.

Hoje o projeto possui:

- aplicação JavaFX executável;
- Maven Wrapper;
- arquitetura inicial em camadas;
- persistência local com SQLite em paths nativos por plataforma;
- migrations versionadas com Flyway, incluindo V2 de identidade e V3 de evidência não secreta de validação;
- acesso a dados com JDBC puro;
- transações JDBC com commit e rollback;
- `installation_id` local persistente;
- provisionamento remoto coordenado, com persistência segura e compensação explícita;
- configuração bootstrap não secreta em `bootstrap.properties`;
- proteção de segredos com DPAPI no Windows;
- diagnóstico local do terminal;
- porta de aplicação para o backend e adapter HTTPS com o `HttpClient` do JDK;
- contratos `v1` de health, pareamento e terminal autenticado;
- correlação por `X-Request-ID`, timeouts explícitos, retry seguro e erros tipados;
- testes automatizados com bancos temporários;
- build local funcional.

## Ainda não implementado

- autenticação de operador e sessão operacional;
- integração Keychain real em macOS;
- sincronização;
- produtos;
- carrinho;
- vendas;
- pagamentos;
- leitores;
- impressora térmica;
- gaveta;
- operação offline;
- NFC-e/NF-e;
- instalador para Windows, Linux e macOS.

## Armazenamento seguro

Linux requer `libsecret-1.so.0` (API binária >= 0.19), GLib/GIO/GObject e um Secret Service funcional na sessão do usuário. A integração usa FFM do JDK, sem `secret-tool`, subprocesso ou nova dependência Maven. Sem provider, com item bloqueado ou com falha, não existe fallback plaintext. macOS permanece plataforma-alvo: paths testados unitariamente; SecretStore depende de implementação e validação real em macOS.

## Requisito de operação offline

A indisponibilidade temporária da internet não deve impedir vendas elegíveis com dados e meios de pagamento disponíveis localmente. SQLite é fundamento dessa continuidade. Catálogo/preço local, venda local, outbox e sincronização após reconexão serão construídos nas fases futuras; o operador deverá visualizar **Online / Offline / Sincronização pendente**. Nada desses fluxos foi implementado neste pré-requisito.

## Próximas etapas

Este é um roadmap inicial e pode evoluir:

1. Autenticação e sessão operacional
2. Catálogo e preços locais
3. Núcleo transacional de vendas
4. Pagamentos e caixa
5. Sincronização e resiliência offline
6. Periféricos
7. Empacotamento e operação multiplataforma
8. Fiscal futura
