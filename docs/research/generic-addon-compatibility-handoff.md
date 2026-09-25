# Generic Mihon Add-on compatibility — integration handoff

Date: 2026-09-25. Branch: `tsuzuki/generic-addon-compatibility`, based on `tsuzuki/runtime-e2e-tests` at `6ae00eb4e3cb9348cbdcaacef189671d2f912738`.

## Decision and implemented path

The Keiyoushi `index.pb` is an extension **catalog**, not proof that a source can read a chapter on a device. The existing Mihon `ExtensionStoreRepository`/`ExtensionApi`, `ExtensionManager`, `ExtensionLoader`, and `SourceManager` retain catalog, installation, trust, and loaded-source authority. This branch adds no second extension manager or site-specific adapter. `MihonAddonRepository` exposes one installed Add-on per extension package and only its loaded, enabled internal `CatalogueSource` IDs. The relevant adapter tests now cover mixed enabled/disabled IDs, removal, missing loaded extension, and rejection of non-catalogue sources.

`ResolveContentBinding.searchProgress` adds a cold, bounded search to the existing canonical binding resolver. INITIAL searches up to three eligible internal sources, ordered by title/global language preference; BROADEN explicitly searches only unqueried eligible IDs. Concurrency is capped at four. Events distinguish prior binding count, safe bound result, confirmation candidates, genuinely empty/no-match, classified failure, and completion. Existing `execute`/`executeAll`, scoring, thresholds, `ConfirmContentBinding`, and persistence rules remain the binding authority. Prior bindings and candidates are **not** chapter options. No canonical title is merged by title similarity.

Change Source consumes these events progressively for one selected Add-on package. It retains good results when a peer source fails, requires explicit confirmation for ambiguous editions, and offers “Search more” rather than searching every installed source automatically. The chapter selector only displays actual `ResolveChapterContent` options. Empty/error states offer an explicit discovery action. Title detail opens the existing binding sheet; Reader uses a component-targeted internal MainActivity action carrying the same nonblank canonical title ID. There is no public deep-link filter. A new preference is not written by discovery or binding; existing Reader preparation/preference timing is unchanged.

The V2 chapter path maps `PersistedChapterEvidence` to `CanonicalChapter`. It need not create legacy `ChapterVariant` rows. `MihonContentProvider` accepts mapped evidence identities (and existing variants) when resolving actual chapter options; an unobserved chapter is not fabricated.

## Verification

| Evidence | Result and limit |
| --- | --- |
| [Full CI 36080952197](https://github.com/jssantogit/mihon/actions/runs/36080952197) at code HEAD `efac6d86e` | **PASS**: Change Planner, Format, Core/Data/Domain/App tests, SQLDelight migrations, Supabase backend, Release Compile, CI Gate. Native Package Gate was skipped; no APK was published. |
| [App/Format CI 36079178929](https://github.com/jssantogit/mihon/actions/runs/36079178929) | **PASS**: deterministic progressive binding → explicit confirmation → HTTP inventory → mapped canonical evidence → two real options → Reader preparation → fixture `getPageList`, on one canonical title/store. |
| [App/Format CI 36080352470](https://github.com/jssantogit/mihon/actions/runs/36080352470) | **PASS**: Reader discovery route, invalid ID no-op, one-shot binding-sheet launch, plus affected App suite. Actual cross-activity navigation was not instrumented on Android. |
| [Earlier real MangaFire E2E 36050812440](https://github.com/jssantogit/mihon/actions/runs/36050812440) | Prior-branch, opt-in, one title/one English source, 14 stages passed. **Not rerun** for this branch; not universal-provider proof. |
| `python3 .github/scripts/test_ci_v2_plan.py` | 34 routing tests passed. Affected-mode checks must not be confused with skipped jobs. |

The deterministic tests use a synthetic Mihon `HttpSource` and MockWebServer; they do not query external providers. Existing domain and adapter tests separately cover disabled/removal, empty/partial inventory, identity mismatch, fallback enabled/disabled, and canonical progress. The new shared-state journey covers multiple internal sources and languages, ambiguity, HTTP 503 isolation, and only observed chapter options. Neither suite proves that every extension APK or website works.

## Known limits and review points

1. Third-party `CatalogueSource` may execute blocking Java/HTTP code. The 25-second coroutine timeout is cooperative, not a hard kill; four hung calls can occupy the four permits. Per-source progress prevents an ordinary slow/failing peer from suppressing completed results, but cannot guarantee responsiveness against four non-cooperative calls.
2. The Reader → MainActivity discovery transition is unit-tested and compiles, but still needs a focused Android smoke test for activity flags, process restoration, and reopening the correct title/sheet. No user data, external source, or provider was used for that test here.
3. No live matrix of 222 sources, no new physical-device test, no CAPTCHA/429 bypass, and no guarantee of site availability. The Keiyoushi index is for distribution metadata only.
4. Some temporary UI copy is English-only. Localization and a physical UX check are follow-up work, not grounds to weaken identity gates.
5. Existing binding counts are informational. A stale binding is never presented as a readable chapter solely because it is stored; chapter availability still requires compatible mapped evidence/inventory and `ContentDelivery` resolution.

## Integration review checklist

- Confirm the internal Reader navigation intent opens the expected canonical title and binding sheet once after activity recreation.
- Exercise one installed multi-source extension with one internal source disabled and verify Change Source never queries that ID.
- Confirm an ambiguous real edition requires user selection and that an unselected candidate does not persist.
- Open a chapter from an alternate verified source; confirm canonical progress and current per-title preference behavior remain unchanged until Reader preparation succeeds.
- Treat HTTP/network/CAPTCHA failures as provider/integration observations, not as empty chapter inventories.

No merge, PR, provider mass query, local Gradle, or distribution APK was performed for this branch.

## Pré-merge: checkpoint Android posterior

Naquele checkpoint, uma cópia local das instruções foi interpretada como proibição de emuladores; a política atual permite emuladores isolados e ADB na CI opt-in, mantendo o aceite final de UX manual no telefone. Os resultados abaixo são históricos daquele checkpoint. Compilar `androidTest` não equivale a executar seus testes.

| Frente | Evidência observada | Estado |
| --- | --- | --- |
| MangaFire 1.6.34, One-Punch Man, fonte inglesa | [Execução real 36084563918](https://github.com/jssantogit/mihon/actions/runs/36084563918), SHA `491c2bbe`: um `PASS` para cada uma das 14 etapas, de `EXTENSION_INSTALL` a `GET_PAGE_LIST` (23 páginas); JUnit 1 teste, 0 falhas/erros/ignorados; isolamento e limpeza do banco `PASS`. | **PASS histórico**, não reexecutado no HEAD atual. Não prova disponibilidade permanente do provedor. |
| Navegação Reader → título canônico → binding sheet | A primeira instrumentação adicionada falhou na compilação do [run 36088072415](https://github.com/jssantogit/mihon/actions/runs/36088072415): helper de instância inacessível à fixture aninhada. O commit `19076688f` moveu helpers sem estado para o companion object e corrigiu a formatação. [CI v2 36090521925](https://github.com/jssantogit/mihon/actions/runs/36090521925) passou App, Format e CI Gate. [Execução 36090964655](https://github.com/jssantogit/mihon/actions/runs/36090964655) no commit `ffd1256df` passou fixture, compilação de `androidTest` e verificação de assinaturas JUnit; o job de emulador foi **ignorado**. | **Compila; comportamento Android não comprovado.** |
| MangaBall 1.6.1 multifuente, pt-BR | Fixture conhecido com SHA-256 `c2212a46d201034c15717873183b1b1261a00fbc5db52ab5eae6976f386d6739`; fonte pt-BR `35546023386335815`. A matriz histórica comprovou candidatos de busca, não edição, binding, inventário ou leitura. | **INCONCLUSIVE** no fluxo E2E. Nenhuma nova consulta externa. |

O commit `ffd1256df` incluiu a branch de compatibilidade no gatilho de **compilação** do workflow Android. Um push sem `[android-fixture]`/`[android-live]` mantém o job de emulador ignorado. [CI v2 36090964648](https://github.com/jssantogit/mihon/actions/runs/36090964648) passou Change Planner e CI Gate; os demais jobs foram ignorados pela política de roteamento, logo não são evidência de testes executados.

**Aceite pendente no telefone, sem automação:** em um APK de teste aprovado e identificado por SHA, abrir One-Punch Man com a extensão instalada; acionar “Find/Add Reading Source” a partir do Reader tanto com MainActivity aberta quanto após inicialização fria; confirmar o título canônico correto e uma única abertura do binding sheet; fechar e repetir após recriação do app; confirmar que progresso e preferência não mudam sem preparar leitura. Para MangaBall, habilitar apenas a fonte pt-BR desejada e desabilitar outra fonte interna; verificar que a desabilitada não é pesquisada, escolher explicitamente a edição correta entre candidatos, confirmar persistência do binding, observar capítulos e uma alternativa correspondente, e abrir o Reader. Registrar `PASS`, `FAIL` ou `NOT_RUN` por etapa, aparelho/Android, versão do APK, SHA e data. Não inferir identidade por título parecido nem disponibilidade pela mera presença de candidatos.

**Decisão de integração:** a branch ainda não está pronta para declarar o checkpoint Android completo. A compilação foi recuperada e a regressão histórica do MangaFire é positiva; navegação física e MangaBall real permanecem sem validação atual. Nenhum merge ou APK de distribuição foi realizado.

## Correção do contrato de navegação — 2026-09-25

A decisão de produto mais recente substitui a limpeza da rota do Reader. Find/Add Reading Source iniciado durante a leitura é temporário: o Reader conserva capítulo e task, abre o `ContentBindingLinkSheet`/`ContentBindingLinkScreenModel` existente dentro da própria Activity, e o fechamento seguido de **um Back** retorna à mesma instância quando Android a preserva. Uma recriação posterior restaura a identidade do capítulo sem reabrir o sheet. A navegação normal para detalhes a partir da biblioteca não foi alterada. Pesquisa/binding não escreve preferência nem progresso canônico.

O primeiro teste Android offline no commit `4a4805fbd`, [run 36123207314](https://github.com/jssantogit/mihon/actions/runs/36123207314), confirmou no frio e no quente a mesma task/instância, um sheet e capítulo restaurado após recriação, mas falhou antes de Back: o teste exigia que o sheet permanecesse aberto **durante** a recriação, requisito não aprovado. O caso inválido usava o texto da obra como proxy de tela de detalhes, embora esse texto também pudesse aparecer na biblioteca. A [CI v2.1 36123203830](https://github.com/jssantogit/mihon/actions/runs/36123203830) passou App e falhou apenas em dois pontos mecânicos do Format. Essas falhas não foram tratadas como aprovação.

No commit de código `6ce320052`, os testes fecharam o sheet e verificaram o Back **antes** da recriação, passaram a identificar a tela de detalhes pelo cabeçalho próprio, e aplicaram as duas correções de formato. A [CI v2.1 36124853985](https://github.com/jssantogit/mihon/actions/runs/36124853985) passou Change Planner, App, Format e CI Gate; compilação/release e demais jobs ignorados não são alegados como testes executados. O [workflow Android offline 36124854455](https://github.com/jssantogit/mihon/actions/runs/36124854455), `workflow_dispatch` com `live_probe=false`, executou no emulador isolado: cold Reader **PASS**, warm Reader **PASS**, intent inválido **PASS**, cada um com 1 JUnit/0 falhas, fixture removida e app data limpa. Nos dois primeiros, a evidência sanitizada registra `sheet=OPENED_ONCE`, `readerActivity=SAME_INSTANCE`, `readerChapter=CANONICAL`, `task=UNCHANGED`, `progress=UNCHANGED` e `preferences=UNCHANGED`. O fixture não carregou páginas; `position=POSITION_NOT_OBSERVABLE` impede afirmar preservação de posição real. O resumo genérico `readerContinuity=PASS` do caso de intent inválido não é evidência de Reader naquele cenário; a evidência pertinente ali é `invalidIntent=REJECTED`.

**Restam:** aceite manual de UX em telefone real (inclusive posição em página carregada); regressão MangaFire no código atual e MangaBall multifuente continuam para checkpoints separados, sem execução nesta tarefa. Nenhuma solicitação externa ao MangaFire/MangaBall, merge, PR, APK de distribuição ou Gradle local foi feita nesta correção.
