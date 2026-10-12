# Fase 6.2B — recebimento local de catálogo

O cliente Java implementa o contrato de distribuição versionada do backend e grava cada publicação em tabelas próprias do SQLite. A pesquisa por descrição/SKU e a leitura de código de barras da Fase 6.3 consultam somente a versão ativa local.

## Fluxo implementado

1. Consulta entregas destinadas à credencial de máquina.
2. Lê e valida o manifesto e baixa partes NDJSON em sequência.
3. Confere SHA-256 da parte antes de persistir os bytes e confirmar o checkpoint.
4. Confere o SHA-256 global e as quantidades de produtos, preços e embalagens antes de aceitar a publicação.
5. Persiste uma versão completa e move o ponteiro ativo em uma transação SQLite. Uma falha mantém a versão ativa anterior.
6. Registra recebimento e aplicação separadamente. Se a resposta de confirmação se perder, o estado local permite repetir a confirmação idempotente.

Tokens de máquina seguem no armazenamento seguro do sistema operacional. As rotas de distribuição não enviam sessão de operador. O serviço fica disponível no `TerminalRuntime`; a sincronização automática da aplicação é opt-in com `-Dlucraone.pdv.catalog-distribution.enabled=true`. A validação manual requer provisionar um terminal de teste com `pdv:catalog:distribution`. Não usar o `pdv-001` para validar esse fluxo.

## Persistência

A migration Flyway `V6__versioned_catalog_delivery.sql` adiciona staging de partes, snapshots completos imutáveis e um ponteiro para a versão ativa. O cache parcial V5 não é apagado nem usado como staging. Produtos, preços de venda e embalagens conservam os códigos de barras como texto, inclusive zeros à esquerda.

## Validação local

Os testes usam um servidor HTTP em loopback e banco SQLite temporário. Eles exercitam os endpoints com credencial sintética; não fazem chamadas à VPS nem ao terminal real. Executar na raiz do repositório:

```sh
./mvnw test
```

Antes do teste manual integrado, provisionar um terminal separado para desenvolvimento, emitir credencial com a ability de distribuição e confirmar que a filial usa dados de teste. Só então iniciar o PDV com a propriedade opt-in acima e testar publicação, retomada e substituição de versão. A pesquisa local pode ser verificada com qualquer snapshot ativo armazenado no SQLite do terminal.
