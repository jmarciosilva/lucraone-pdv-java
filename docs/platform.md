# Plataforma e armazenamento seguro

Pré-requisito técnico multiplataforma antes da Fase 5, sem nova fase numerada.
Fase 4: CONCLUÍDA. Fase 5: PRÓXIMA, NÃO INICIADA.

## OperatingSystem e PlatformPaths

`OperatingSystem` centraliza a única leitura de `os.name`: WINDOWS, LINUX, MACOS,
UNSUPPORTED. `fromName` permite testes sem depender do host; `description` fornece
diagnóstico à composição. Domain/application não consultam o SO.

`PlatformPathsFactory` seleciona adapters que expõem applicationDirectory,
dataDirectory, logsDirectory, configDirectory e secretsDirectory. Paths usam
Path/resolve; LocalDataDirectory prepara data/logs/config com Files.createDirectories.

| Plataforma | applicationDirectory | configDirectory | Validação |
| --- | --- | --- | --- |
| Windows | `%LOCALAPPDATA%\LucraOne\PDV` | `config` sob a base | TESTADO UNITARIAMENTE |
| Linux | `$XDG_DATA_HOME/LucraOne/PDV`, ou `$HOME/.local/share/LucraOne/PDV` | `$XDG_CONFIG_HOME/LucraOne/PDV`, ou `$HOME/.config/LucraOne/PDV` | TESTADO NESTE HOST |
| macOS | `$HOME/Library/Application Support/LucraOne/PDV` | `config` sob a base | TESTADO UNITARIAMENTE; NÃO VALIDADO EM HOST REAL |

Todos os paths estão IMPLEMENTADOS. Data/logs são subdiretórios da base; SQLite fica
em `data/lucraone-pdv.db`. Windows preserva a resolução anterior e falha se LOCALAPPDATA
estiver ausente, sem fallback cwd/temp/home. Linux ignora XDG relativo/inválido e exige
HOME absoluto para fallback, conforme XDG. Configuração usa sua raiz XDG própria;
não foram criadas outras raízes operacionais. macOS mantém configuração na mesma árvore.
SO desconhecido falha explicitamente, nunca é presumido Linux.

SecretsDirectory é usado somente por Windows, como `secrets` sob a base. Linux usa
keyring nativo e macOS precisará de Keychain; nenhum diretório de segredos é criado
nessas plataformas. Bootstrap permanece opcional e não secreto.

## SecretStore e fail-secure

`application/security/SecretProtector` continua primitive protect/unprotect de bytes.
`SecretStore` declara save(key, byte[]), load(key): Optional<byte[]> e delete(key).
Chaves são identificadores não secretos, ASCII limitado, sem separadores de caminho.
Save sobrescreve; delete ausente é idempotente. Input pertence ao chamador; o array
retornado por load também pertence ao chamador e deve ser zerado após uso. Os adapters
limpam cópias temporárias. Não se promete eliminação absoluta de cópias na JVM/provider.

Optional.empty significa apenas ausência confirmada. SecretStoreException.Kind distingue
UNAVAILABLE, READ_FAILED, WRITE_FAILED e DELETE_FAILED. Mensagens são fixas e sem causas
que possam carregar conteúdo; toString não contém chave nem segredo. Store indisponível,
item bloqueado, falha ou SO desconhecido causam erro. Nenhum fallback plaintext, base64,
chave hardcoded, senha fixa, properties, SQLite ou chmod isolado é permitido.

A factory única seleciona WindowsSecretStore, LinuxSecretStore ou o limite MacOsSecretStore.
A apresentação não conhece stores concretos. MachineCredential não foi conectada ao store.
Nenhum pairing/backend real, token real, autenticação, sessão ou migration nova foi criado/usado.

## WindowsSecretStore

**IMPLEMENTADO / TESTADO UNITARIAMENTE / NÃO VALIDADO EM HOST REAL nesta rodada.**

Reutiliza WindowsDpapiSecretProtector no escopo do usuário. Criptografia e binding DPAPI
não foram reescritos; a verificação do SO passou a usar a detecção central.
Arquivos binários contêm somente ciphertext; nomes usam SHA-256 do identificador.
Temporário também é cifrado e substituição é atômica; se não suportada, falha.
Leituras recusam symlink e tamanho inválido. Perfil Windows é necessário para recuperar
material protegido. Os quatro testes reais DPAPI seguem skipped no Linux; wrapper foi
testado com SecretProtector sintético, sem opção de produção equivalente.

## LinuxSecretStore

**IMPLEMENTADO / TESTADO UNITARIAMENTE / TESTADO NESTE HOST.**

Usa libsecret via Foreign Function API do JDK 25. Protocolo e sessão Secret Service ficam
na biblioteca nativa mantida pelo GNOME, sem reimplementar criptografia D-Bus e sem framework
Java adicional. API binária evita transformar segredo em String/argv/ambiente/arquivo.
Não usa ProcessBuilder ou secret-tool.

Atributos: application=br.com.lucraone.pdv e key=identificador não secreto; label constante.
Coleção persistente default, nunca session. Busca todos os itens sem carregar segredos
implicitamente; item locked causa UNAVAILABLE. Desbloqueie o keyring na sessão nativa.
Ausência/falha do serviço não é missing. Duplicidade causa READ_FAILED. Delete verifica
remoção e não acessa itens de outras aplicações.

ABI Linux LP64 (ponteiros 64 bits); ABI de 32 bits falha explicitamente. Requer native-access
já configurado no Maven. Chamadas são síncronas: não executar na thread JavaFX.
GCancellable é acionado após dez segundos por contexto; cancelamento nativo é cooperativo,
sem promessa de interrupção instantânea. Delete com verificação usa dois contextos.
Timeout de escrita pode deixar resultado indeterminado no provider; não há retry automático.
GError é liberado sem copiar mensagens; SecretValue é liberado por libsecret e buffers de
entrada são zerados.

Nenhuma dependência Maven/POM nova. Bibliotecas existentes neste host foram reutilizadas:

| Dependência nativa | Versão neste host | Licença | Motivo |
| --- | --- | --- | --- |
| libsecret-1 | 0.21.4-1build3; API binária >= 0.19 | LGPL-2.1-or-later | API mantida Secret Service |
| GLib/GIO/GObject | 2.80.0-6ubuntu3.9 | LGPL-2.1-or-later | Objetos, atributos, cancelamento |
| GNOME Keyring | 46.1-2ubuntu0.2 | Licenças do pacote, GPL/LGPL conforme componente | Provider Secret Service nesta sessão |

Host auditado: sessão D-Bus e org.freedesktop.secrets disponíveis, libsecret instalado;
secret-tool ausente. Escolha evita dependência Java extra e mantém segurança na API nativa.
Referências oficiais: [gravação binária](https://gnome.pages.gitlab.gnome.org/libsecret/func.password_storev_binary_sync.html),
[busca e locked](https://gnome.pages.gitlab.gnome.org/libsecret/method.Service.search_sync.html),
[leitura explícita](https://gnome.pages.gitlab.gnome.org/libsecret/method.Item.load_secret_sync.html),
[SecretValue](https://gnome.pages.gitlab.gnome.org/libsecret/method.Value.get.html).

## MacOsSecretStore

**Keychain NÃO IMPLEMENTADO / NÃO VALIDADO EM HOST REAL.**

MacOsSecretStore é limite explicitamente indisponível, não adapter Keychain fake.
Valida argumentos e lança UNAVAILABLE em save/load/delete; factory seleciona esse limite
sem degradação silenciosa. TESTADO UNITARIAMENTE quanto à indisponibilidade/validação.
Não usa comando security com senha em argv. Implementação nativa e validação em macOS
são obrigatórias antes de persistir credenciais nessa plataforma. Limitação admitida
nos critérios deste pré-requisito não significa autenticação macOS pronta.

## Arquitetura e offline

Testes guardam independência de domain/application, variáveis por adapter, consulta de
SO centralizada, apresentação sem stores concretos e cliente HTTP portátil. API da Fase 4
permanece intacta. Sem novos módulos, framework ou migration de autenticação.

Offline é requisito oficial: internet temporariamente indisponível não deve impedir
vendas elegíveis com dados e meios locais. SQLite fundamenta continuidade; catálogo/preço
local, venda local, outbox e sincronização após reconexão ficam nas fases futuras.
Operador deverá ver Online / Offline / Sincronização pendente. Sync evoluirá gradualmente,
com endurecimento na Fase 9. Nada desses fluxos foi implementado nesta rodada.

## Evidências Ubuntu 24.04 — 2026-10-10

- Baseline: 138 testes, 134 PASS, 4 SKIPPED, zero FAIL/ERROR; clean package verde.
- Regressão final: 179 testes, 175 PASS, 4 SKIPPED, zero FAIL/ERROR; test e clean package verdes.
- Novos testes padrão: PlatformPathsTest (16), SecretStoreContractTest (21), fronteiras adicionais (4). Dois ITs opt-in passaram separadamente.
- JavaFX real em DISPLAY=:0, sem Xvfb; janela 1000x700 confirmada em duas execuções.
- Base: ~/.local/share/LucraOne/PDV; config: ~/.config/LucraOne/PDV; XDG não definido.
- Primeira execução criou SQLite e aplicou V1/V2. Segunda validou v2 sem reaplicar migrations.
- Installation_id consultado read-only idêntico nas duas execuções; banco preservado.
- Janelas encerradas normalmente via WM_DELETE_WINDOW.
- LinuxSecretStoreIT passou save/load/overwrite binário/delete/missing com UUID sintético,
  try/finally e confirmação de ausência final; buffers limpos.
- LinuxSecretStoreUnavailableIT com endereço D-Bus isolado inválido retornou UNAVAILABLE.

```bash
./mvnw test
./mvnw clean package
# Opt-in, provider real; grava/remove somente sintético:
./mvnw test -Dtest=LinuxSecretStoreIT
# Sessão isolada indisponível, sem alterar a sessão principal:
env DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/lucraone-no-such-bus \
  LUCRAONE_TEST_UNAVAILABLE_BUS=1 ./mvnw test -Dtest=LinuxSecretStoreUnavailableIT
```

ITs são opt-in, fora da suíte padrão, sem backend/Internet/segredos reais.
Windows e macOS precisam de validação real nos respectivos hosts.
