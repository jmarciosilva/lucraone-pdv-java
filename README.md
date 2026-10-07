# LucraOne PDV Java

Aplicativo desktop de Ponto de Venda do ecossistema LucraOne.

## Sobre o projeto

O LucraOne PDV Java será o aplicativo desktop responsável pela operação de ponto de venda do ecossistema LucraOne. Ele será executado localmente nos computadores dos caixas e, futuramente, se comunicará com o backend central do LucraOne.

O backend central é um projeto separado, baseado em Laravel e executado em servidor ou VPS. Esta aplicação não contém nem modifica o backend.

O uso de uma aplicação desktop oferece a base necessária para a operação no terminal de caixa e para futuras integrações com periféricos. A arquitetura será preparada para permitir operação local e sincronização posterior durante indisponibilidades temporárias de internet.

A infraestrutura de persistência local com SQLite está implementada. Operação offline e sincronização continuam sendo etapas futuras.

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

Integração com API, sincronização e recursos fiscais pertencem ao planejamento futuro e não fazem parte da implementação atual.

## Requisitos

- Windows 11 ou ambiente desktop compatível;
- JDK 25;
- `JAVA_HOME` configurado para o JDK instalado;
- Git.

`JAVA_HOME` deve apontar para o diretório do JDK instalado. No ambiente de desenvolvimento atual, por exemplo, o caminho é `C:\Program Files\Java\jdk-25.0.3`; trata-se apenas de um exemplo, não de um requisito universal.

Não é necessário instalar Maven globalmente, pois o projeto utiliza o Maven Wrapper.

## Executando o projeto

No Windows PowerShell, na raiz do repositório:

```powershell
.\mvnw.cmd javafx:run
```

## Executando os testes

```powershell
.\mvnw.cmd test
```

## Gerando o build

```powershell
.\mvnw.cmd clean package
```

O comando gera o artefato Maven de validação. Ainda não existe instalador para Windows.

## Persistência local

O banco SQLite de cada terminal é inicializado em `%LOCALAPPDATA%\LucraOne\PDV\data\lucraone-pdv.db`. As migrations são executadas automaticamente pelo Flyway antes de o aplicativo ficar disponível.

Os testes usam bancos temporários e não acessam o banco local do usuário. A política inicial de backup e recuperação está documentada em [Persistência local](docs/local-database.md).

## Identidade e configuração do terminal

Na inicialização, o PDV garante um `installation_id` local e estável, informa se o terminal está provisionado e lê a configuração bootstrap opcional em `%LOCALAPPDATA%\LucraOne\PDV\config\bootstrap.properties`, sem realizar chamadas ao backend. Detalhes em [Identidade e configuração do terminal](docs/terminal-configuration.md).

## Estrutura do projeto

```text
src/
  main/
    java/br/com/lucraone/pdv/
      application/
      domain/
      infrastructure/
      presentation/
        view/
    resources/
  test/
    java/
```

- `domain`: regras de negócio puras.
- `application`: casos de uso e coordenação.
- `infrastructure`: persistência e integrações externas futuras.
- `presentation`: JavaFX e interação com o usuário.

## Desenvolvimento local

O aplicativo desktop é desenvolvido e testado localmente, principalmente em Windows. O backend LucraOne é um projeto separado; este repositório não inclui comandos ou código do backend Laravel.

## Estado atual

Fundação e Fase 2 — Persistência local concluídas.

Hoje o projeto possui:

- aplicação JavaFX executável;
- Maven Wrapper;
- arquitetura inicial em camadas;
- persistência local com SQLite em `%LOCALAPPDATA%`;
- migrations versionadas com Flyway;
- acesso a dados com JDBC puro;
- transações JDBC com commit e rollback;
- testes automatizados de persistência com bancos temporários;
- build local funcional.

## Ainda não implementado

- autenticação;
- integração com backend LucraOne;
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
- instalador Windows.

## Próximas etapas

Este é um roadmap inicial e pode evoluir:

1. Provisionamento e configuração do terminal
2. Integração com API LucraOne
3. Autenticação
4. Sincronização de catálogo
5. Núcleo de venda local
6. Operação offline
7. Periféricos
8. Empacotamento desktop
9. Integração fiscal futura
