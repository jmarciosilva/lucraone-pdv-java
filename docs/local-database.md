# Persistência local

## Localização

Cada terminal utiliza um banco SQLite independente. Em Windows, a convenção operacional é:

```text
%LOCALAPPDATA%\LucraOne\PDV\
  data\
    lucraone-pdv.db
  logs\
  config\
```

O banco nunca é criado no repositório, junto do JAR, em `src/` ou em compartilhamento de rede. Se `LOCALAPPDATA` não estiver disponível, a aplicação falha de forma explícita; ela não usa diretório atual, temporário ou home como alternativa de produção.

## Inicialização e migrations

A inicialização cria os diretórios operacionais necessários, abre o banco SQLite e aplica as migrations em `src/main/resources/db/migration` por meio do Flyway. O Flyway mantém a tabela técnica `flyway_schema_history`; não há tabelas de negócio nesta fase.

As conexões da aplicação configuram `foreign_keys = ON`, `busy_timeout = 5000` e `journal_mode = WAL`. O timeout reduz falhas transitórias por contenção local. WAL permite leitores durante uma escrita, mantendo o modelo de um banco por terminal; ele não torna o banco apropriado para compartilhamento SMB/NAS.

Flyway clean permanece desabilitado. Inicialização repetida valida o histórico e não reaplica migrations já registradas. Um arquivo SQLite existente com tabelas, mas sem `flyway_schema_history`, é recusado sem alteração: não há baseline automático.

## Testes

Os testes de infraestrutura recebem um diretório base temporário por `@TempDir`. Eles nunca leem ou escrevem o banco em `%LOCALAPPDATA%` e verificam resolução de caminho, migrations, segunda inicialização, preservação de banco desconhecido, PRAGMAs e commit/rollback JDBC.

## Estratégia inicial de backup e recuperação

Ainda não há backup automático. Antes de uma migration de risco, o processo futuro deve criar uma cópia recuperável somente com o banco fechado ou usando mecanismo SQLite seguro, como o backup online ou `VACUUM INTO` quando aplicável.

Não é seguro copiar apenas `lucraone-pdv.db` enquanto houver escrita em modo WAL. Uma estratégia de cópia por arquivo precisa considerar os arquivos `-wal` e `-shm`; uma estratégia baseada na API de backup do SQLite é preferível.

Se uma migration falhar, a aplicação não executa clean, não exclui o arquivo e não recria automaticamente o banco. O arquivo e o histórico de migration devem ser preservados para diagnóstico e recuperação. Um banco suspeito de corrupção também não deve ser descartado automaticamente, especialmente quando no futuro puder conter transações ainda não sincronizadas. Restauração, quarentena e suporte operacional serão definidos antes de haver dados de negócio locais.
