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

O banco SQLite de cada terminal é inicializado em `%LOCALAPPDATA%\LucraOne\PDV\data\lucraone-pdv.db`. As migrations são executadas automaticamente pelo Flyway antes de o aplicativo ficar disponível.

A resolução do diretório operacional é, hoje, específica de Windows. Em Linux e macOS o aplicativo abre, mas informa que não pôde preparar o banco local, em vez de escolher um diretório alternativo por conta própria. A resolução nativa por plataforma está registrada no roadmap como ajuste arquitetural próprio, com testes específicos, e não foi misturada à fase de API.

Os testes usam bancos temporários e não acessam o banco local do usuário. A política inicial de backup e recuperação está documentada em [Persistência local](docs/local-database.md).

## Conectividade com a API

O PDV consome o contrato `v1` do backend: health, pareamento de terminal e leitura autenticada do terminal. A URL base nunca está no código: vem de `api.base-url` na configuração bootstrap.

TLS não é enfraquecido em nenhuma hipótese, `X-Request-ID` correlaciona cada chamada, timeouts são explícitos, o retry é assimétrico (leituras podem repetir uma vez; o pareamento nunca repete) e a machine credential existe somente em memória nesta fase. Detalhes em [Contrato da API PDV](docs/api-contract.md).

## Identidade e configuração do terminal

Na inicialização, o PDV garante um `installation_id` local e estável, informa se o terminal está provisionado e lê a configuração bootstrap opcional em `%LOCALAPPDATA%\LucraOne\PDV\config\bootstrap.properties`, sem realizar chamadas ao backend. Detalhes em [Identidade e configuração do terminal](docs/terminal-configuration.md).

## Estrutura do projeto

```text
src/
  main/
    java/br/com/lucraone/pdv/
      domain/
      application/
        api/
        security/
        terminal/
      infrastructure/
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

`infrastructure/platform` e os adapters de secret store por plataforma estão com a direção aprovada e documentada; apenas o adapter Windows de segredos existe hoje.

## Desenvolvimento local

O aplicativo desktop é desenvolvido e testado localmente em Windows e em Linux. macOS é plataforma-alvo, porém ainda não validado. O backend LucraOne é um projeto separado; este repositório não inclui comandos ou código do backend Laravel.

## Estado atual

Fundação, Fase 2 — Persistência local, Fase 3 — Provisionamento e configuração do terminal e Fase 4 — Contrato e conectividade com a API concluídas.

O PDV **não** está operacional para venda. Autenticação de operador, catálogo, preços, venda, pagamentos, sincronização, periféricos, empacotamento e fiscal continuam pendentes.

Hoje o projeto possui:

- aplicação JavaFX executável;
- Maven Wrapper;
- arquitetura inicial em camadas;
- persistência local com SQLite em `%LOCALAPPDATA%`;
- migrations versionadas com Flyway, incluindo a V2 de identidade do terminal;
- acesso a dados com JDBC puro;
- transações JDBC com commit e rollback;
- `installation_id` local persistente;
- estado de provisionamento do terminal, ainda sem provisionamento remoto;
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
- persistência segura da machine credential;
- secret store para Linux e macOS;
- resolução do diretório operacional em Linux e macOS;
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
