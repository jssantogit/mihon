# Tsuzuki runtime integration tests — handoff

## Resumo

Branch: `tsuzuki/runtime-integration-tests`  
Base solicitada: `tsuzuki/fix-mangafire-binding` @ `2e76abcfa9dd155178cec06783abc043120141ea`  
Base SHA (`BASE_SHA`): `2e76abcfa9dd155178cec06783abc043120141ea`  
HEAD de código validado (`CODE_HEAD_SHA`): `34d11b2d9268e7588776b06c956c0d4ce2d05772`. O SHA do commit documental será informado no resumo final.

Esta rodada acrescenta testes determinísticos locais no limite real de `HttpSource` do Mihon,
um cenário que leva a resposta HTTP da extensão fictícia até a resolução e persistência de um
binding canônico, taxonomia estruturada de falha na busca, e roteamento de CI que exige testes
Domain e App para alterações nas fronteiras Tsuzuki relevantes.

Isso **não comprova que MangaFire 1.6.34 funciona** nem explica o erro observado no dispositivo.
Nenhum APK de extensão autorizado, imutável e com digest conhecido foi fornecido ou encontrado
no repositório; também não há `app/src/androidTest` ou emulador com a extensão instalada nessa
execução. O resultado real do MangaFire permanece pendente de diagnóstico da extensão em ambiente
autorizado.

## Auditoria de cobertura anterior

O inventário da cobertura antes de implementar está em
[`runtime-integration-test-audit.md`](runtime-integration-test-audit.md). Resumo:

- Domain possuía bons testes isolados para seleção/ambiguidade, materialização de binding,
  inventário, reconciliação e alternativas de conteúdo, em geral com gateways/repos fakes.
- Testes do adapter `MihonReadingSourceGateway` usavam `CatalogueSource`, `SourceManager` e
  respostas/falhas de busca sintéticas; não atravessavam request/response/parser de `HttpSource`.
- Não havia cenário conjunto que ligasse a resposta HTTP de busca à seleção/materialização de um
  binding canônico, nem uma extensão binária real em testes automatizados.
- `RuntimeV2SmokeReadinessTest` já conectava resolver, refresh de evidência, reconciliação e
  opções de conteúdo com providers/repos fakes. Não provava o comportamento de uma extensão real.
- A CI anterior roteava por módulo. Uma mudança Domain poderia deixar de executar App tests e uma
  mudança de gateway App poderia deixar de executar Domain tests.
- Não foi encontrado artefato versionado MangaFire 1.6.34 + hash; `app/src/androidTest` não existe.

## Infraestrutura e contrato de falha

### Harness HTTP local

`LocalMihonSourceHarness` inicia `MockWebServer` em loopback e expõe um `HttpSource` de teste,
um `SourceManager` fake e o `MihonReadingSourceGateway` real. Os resultados são respostas
controladas por teste; a suíte não consulta MangaFire, MangaDex ou qualquer host externo e não
guarda conteúdo de páginas de mangá.

`MihonBindingHttpIntegrationTest` envia uma busca HTTP bem-sucedida pelo `HttpSource` e valida o
fluxo subsequente do `ResolveContentBinding`: candidato retornado, correspondência pelo título
canônico, materialização Mihon e persistência do binding sem modificar a identidade canônica.
`MihonReadingSourceHttpIntegrationTest` cobre diretamente parser + gateway e suas falhas.
Junto aos testes de `RuntimeV2SmokeReadinessTest`, `MihonContentProviderTest` e testes Domain,
isso dá cobertura automatizada às fronteiras principais sem depender de serviços públicos.

### Classificação estruturada da busca

O adapter expõe `ReadingSourceSearchFailure`, preservando o throwable original como `cause` e
exportando apenas categoria fechada e, quando conhecido, status HTTP inteiro validado. Categorias:

| Categoria | Regra de classificação | O que demonstra |
| --- | --- | --- |
| `SOURCE_DISABLED` | Source ID presente em preferências desabilitadas | A busca não foi tentada |
| `SOURCE_UNAVAILABLE` | ID não resolve para `CatalogueSource` instalado | Não prova falha do site |
| `HTTP_RESPONSE` | Exceção HTTP Mihon com status 100–599 | Status observado; corpo e headers não são exportados |
| `CAPTCHA_REQUIRED` | Marcador explícito reconhecido na cadeia de causa | Não há bypass; requer ação humana/ambiente adequado |
| `TIMEOUT` | `SocketTimeoutException` observada | Categoria comprovada; o teste injeta exceção sintética e não valida timing de socket real |
| `NETWORK_FAILURE` | Exceções de socket/conexão/DNS específicas | Falha de transporte observada |
| `MALFORMED_RESPONSE` | Parser estrutural conhecido (JSON/Kotlin serialization) | Resposta não foi interpretável pelo parser |
| `EXTENSION_FAILURE` | Erro não classificado da extensão/parser | Falha interna observada, causa ainda indeterminada |
| `INDETERMINATE` | `IOException` genérica sem sinal específico | Não permite concluir rede, anti-bot ou timeout |

Um HTTP 403 genérico permanece HTTP, não CAPTCHA. Uma `IOException` genérica agora é
`INDETERMINATE`; somente classes de transporte concretas indicam falha de rede. Cancelamento
continua propagando como `CancellationException`. A mensagem sanitizada da falha não incorpora
URL, payload, headers ou texto arbitrário da causa.

No diagnóstico Domain, essas categorias são convertidas em estágios/resultados/reasons fechados.
O status só atravessa o evento como inteiro sanitizado. A reconciliação, resolução de título,
preferências de fonte, progresso e fallback operacional permanecem inalterados.

## Cenários automatizados

| Fronteira | Cobertura nova ou existente relevante |
| --- | --- |
| HTTP `200` com título válido | `MihonReadingSourceHttpIntegrationTest` |
| HTTP `200` com resultado vazio | Mantém sucesso vazio; distinto de erro |
| HTTP `403` genérico | Categoria/status preservados; não CAPTCHA; corpo não aparece na mensagem |
| HTTP `429` e `503` | Status classificados como HTTP observado |
| CAPTCHA | Só sinal textual explicitamente reconhecido no fixture |
| Falha de parser | `JSONException` sintética classificada como malformada |
| Erro interno da extensão | Categoria distinta, texto de erro não exportado |
| `IOException` genérica | `INDETERMINATE`, não inferida como rede |
| Falha de conexão sintética | `ConnectException` injetada no cliente local classificada como rede; não demonstra conectividade física nem causa do MangaFire |
| Timeout sintético | `SocketTimeoutException` injetada no interceptor local; não simula espera de socket |
| Cancelamento | `CancellationException` do parser continua sendo lançada |
| Source desabilitada/ausente | Categoria explícita testada em `MihonReadingSourceGatewayTest` |
| Busca → binding | HTTP local atravessa source parser, gateway real, scoring, materialização e persistência |
| Ambiguidade/reuso/refresh/inventário/seleção | Mantidos nos testes existentes Domain/App; planner agora pareia os shards para paths de runtime |

O harness é pequeno e reutilizável: novos providers locais podem fornecer parser/source fixture e
respostas `MockWebServer` sem copiar a montagem de rede. Não há caso determinístico novo que
instale/carregue um APK de extensão Mihon real nem caso que execute a tela Android do seletor;
essas fronteiras continuam cobertas por composição de testes com fakes, não por runtime Android.

## Change Planner e CI

O Change Planner seleciona Domain Tsuzuki + App Tsuzuki para contratos/gateways/binding/
inventário/seleção tocados nos diretórios relevantes. Alterações de UI não relacionadas e docs
continuam sem acionar esses shards pesados. Os testes de roteamento passaram de 29 para 33 casos;
as alterações incluem verificação explícita de gateway, binding e seletor nos dois shards, e
garantem que UI fora do escopo não force Domain.

Validações executadas:

- `python3 .github/scripts/test_ci_v2_plan.py` — **33/33 passaram** nesta sessão.
- `git diff --check` — passou antes dos commits.
- Fast CI v2.1: run [`35907643108`](https://github.com/jssantogit/mihon/actions/runs/35907643108), SHA `34d11b2d`; Change Planner, App Tsuzuki, Domain Tsuzuki, Format e CI Gate passaram.
- Não executado localmente: Gradle/format/build, conforme policy `CI_FIRST` (`localHeavyAttempts=0`).
- Nenhum APK foi gerado; o workflow APK apareceu como skipped, fora do objetivo desta tarefa.
- Compile/Release Compile, Native Package Gate, SQLDelight e Supabase Backend foram skipped pelo planner; isso não é alegado como validação desses alvos.

Houve ciclos iniciais de CI vermelho enquanto a implementação era test-first. A execução
`35906511585` no commit `3c7a7e707` passou Change Planner e Domain, mas falhou no teste que
tentava obter uma porta local fechada; a fixture ainda tinha uma disputa de bind e não comprovava
uma falha de rede determinística. Em `35907114764`, o JUnit identificou que o cenário vermelho
era o timeout com resposta atrasada. A fixture não exercitou esse timeout como esperado. O teste
foi simplificado para injetar `ConnectException` e `SocketTimeoutException` no interceptor local,
verificando classificação do gateway sem alegar simulação de timeout de socket real.

A primeira execução da regressão falhou na compilação porque o novo tipo ainda não existia; a rodada seguinte revelou
um import de JSON indisponível no app, uma expectativa antiga que tratava `IOException` genérica
como rede e um fechamento de parênteses do formatter. Essas causas foram corrigidas em commits
subsequentes. O run `35907643108` passou nos shards planejados, incluindo os testes de regressão
de classificação e os testes de integração HTTP local.

## Android e MangaFire real

Cobertura Android realmente demonstrada nesta rodada: **nenhuma**. A execução não instalou um APK,
não abriu a extensão 1.6.34 e não inspecionou tráfego real. O harness confirma apenas o
comportamento do adapter Mihon com `HttpSource` sintético e endpoint loopback.

Para habilitar uma pista instrumentada confiável posteriormente, primeiro obter de uma origem
autorizada o APK exato MangaFire 1.6.34 e seu hash verificado, fixar os termos/condições e gerar
um teste instrumentado que carregue `ExtensionManager`/`AndroidSourceManager` com respostas
controladas. Se o add-on não aceitar um cliente HTTP configurável/proxy de teste sem modificar
seu comportamento, não afirmar determinismo: manter o teste de extensão real como execução sob
demanda separada. Nunca contornar CAPTCHA ou controles de acesso. Logs/artefatos não devem conter
cookies, tokens, URL completa, páginas, imagens ou dados pessoais.

## Verificador separado de provedores externos

Não foi adicionado à CI principal um teste que consulte serviços públicos. Esses testes seriam
flaky por disponibilidade, geografia, mudanças editoriais e anti-bot. Proposta: uma execução
manual/agendada isolada, não bloqueante para Fast CI, com opt-in e artefatos sanitizados, usando
somente APIs/documentação e acesso autorizados. Saídas devem distinguir `SUCCESS`, falha de
integração observada, indisponibilidade externa observada, CAPTCHA/challenge explícito e
`INDETERMINATE`. Sem telemetria explícita, não rotular erros de rede genéricos como anti-bot.
Não foi criada credencial, crawler ou bypass nesta tarefa.

## Riscos e próximos passos

1. A causa de `EXTENSION_ERROR / EXTENSION_FAILURE` no MangaFire 1.6.34 ainda não foi reproduzida
   com o APK real; continua sem diagnóstico causal.
2. Os testes locais validam o limite Mihon `HttpSource` com resposta controlada; não provam que a
   extensão use a mesma implementação/parser nem que a fonte esteja autorizada/legível no aparelho.
3. O novo binding HTTP usa repositório Mihon falso para atribuir ID local; não valida SQLite ou
   persistência instalada real. Repositórios de domínio e seletor têm suas próprias regressões.
4. A suíte final de CI precisa estar verde para declarar validação completa; etapas puladas não são
   evidência de aprovação.
5. Próxima investigação sem novo APK: usar o aparelho do usuário que já tem MangaFire 1.6.34 e
   exportar somente o relatório v2 sanitizado. Interpretar `stage/outcome/reason`, duração e fonte
   interna para localizar busca, materialização ou seletor; não exportar conteúdo nem segredos.
   Acrescentar ao harness local um fixture para a categoria observada. Se o resultado continuar
   `EXTENSION_FAILURE`, o limite público ainda não expõe a causa interna; só então considerar
   reprodução com APK autorizado e hash verificado em harness Android.

## Commits desta branch

- `0c31806a3` — auditoria pré-implementação.
- `b3f2255f9` — parear shards CI e testar roteamento.
- `218993868` — testes HTTP iniciais (RED intencional; não é CI de aceitação).
- `3932fbeec` — classificação estruturada e regressões (CI revelou correções necessárias).
- `892870ba0` — não inferir rede a partir de I/O genérico.
- `1bdb68717` — integrar busca HTTP local com resolução/persistência de binding.
- `c59cfdf29` — corrigir a asserção nullable apontada pela CI.
- `45b4cc6a2` — usar construtor público do resolver e ajustar regressões.
- `47488672b` — ajustes de teste/format; CI expôs a disputa de porta.
- `3c7a7e707` — tentativa de fixture com porta recusada reservada; substituída por fixture sintética.
- `a20991a2e` — tornar a classificação de falha de conexão determinística via interceptor.
- `34d11b2d9` — tornar também a classificação de timeout sintética e explicitar limites.
- Handoff SHA: consta no commit posterior a `CODE_HEAD_SHA`; esse commit contém apenas esta documentação.
