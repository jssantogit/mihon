# Auditoria de inventário de capítulos — One-Punch Man

**Observação:** 2026-09-22 UTC. **Branch:** `tsuzuki/audit-chapter-inventory`, criada sobre `origin/tsuzuki/bootstrap`. **Escopo:** auditoria estática do app, páginas públicas e API documentada do MangaDex. Nenhuma implementação, APK, página/imagem protegida, cookie ou credencial foi armazenada.

## Conclusão direta

**Não é possível confirmar a causa exata da ausência de #1–137 no runtime sem observar a extensão Mihon instalada e seu inventário bruto. A primeira fronteira ainda não verificada é A→B: catálogo/página da fonte, no idioma e edição selecionados, versus a lista `SChapter` entregue pela extensão ao Mihon.** ADB, aparelho e Add-ons não estão disponíveis nesta sessão.

O que está sustentado:

1. O smoke do APK anterior registrou a lista começando em #138 como observação “provisional”; não registrou versão do Add-on, fonte Mihon interna, idioma, binding, inventário bruto, contagem, IDs ou URLs dos capítulos. `ChapterCoverageTest` usa fixture sintética `[138,139]` para esperar first-known=138; não reproduz MangaFire real.
2. Nesta branch, o gateway Mihon não filtra capítulos abaixo de 138, não pagina por conta própria e transforma diretamente `source.getMangaUpdate(..., fetchChapters=true).chapters` em snapshots. A reconciliação não apaga capítulos canônicos anteriores quando uma fonte omite rows. A UI renderiza a lista canônica ordenada, sem corte numérico em 138. Não foi localizado um limite 138 no core.
3. O core **pode** não criar/mapear um capítulo se a identidade/URL estiver vazia ou se a interpretação não atingir confiança; a tela pode ocultar um capítulo provisório sem mapeamento, progresso ou download. Dados raw reais são necessários para testar essa possibilidade.
4. `loadCachedFirst()` publica primeiro dados locais persistidos. O probe solicita inventário com `refresh=true`, mas evidence/capítulos canônicos persistidos não expiram nem são apagados por uma resposta incompleta. Além disso, `RefreshChapterEvidence` converte falhas de Add-on/binding em listas vazias; a UI pode não mostrar erro e preservar a lista antiga. Risco possível, não causa reproduzida.
5. MangaDex API atual retorna uploads PT-BR #1 e #2, mas não labels literais #137/#138/#139/#233/#234 nesse filtro; EN retorna total zero. Isso não prova que páginas abram ou que outra língua/fonte não as ofereça. MangaBall foi buscado como texto e declarou 0 chapters/0 translations; a sessão do usuário não foi reproduzida. MangaFire não apresentou lista textual no navegador de pesquisa.

**Explicação melhor apoiada para a apresentação:** a tela mostra o conjunto local de capítulos canônicos conhecidos/mapeados; se esse conjunto começa em 138, a tela também começa em 138. O teste sintético documenta essa semântica. **Ainda não sabemos se o Add-on já entregou só 138+ (perda A→B) ou se o dado se perdeu depois (B→C).** Nenhum fallback adicional é justificado antes dessa captura.

## Preparação

- Fetch de `origin/tsuzuki/bootstrap` feito antes da criação da branch. Base: `6f350d412` (`chore(release): integrate verified Round 3 chapter outline and build signed APK`). A branch de auditoria aponta para esse bootstrap.
- Lidos `docs/TSUZUKI-SPEC.md`, `docs/TSUZUKI-DEVELOPMENT.md`, `.agents/rules/tsuzuki-development.md`, `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md`, o plano e relatório do smoke round 2.
- Relatório/handoff da rodada 3 foram lidos sem checkout, via `git show origin/tsuzuki/research-chapter-availability:docs/research/unified-chapter-catalog-study.md` e `.../unified-chapter-catalog-handoff.md`.
- `adb devices -l` falhou com `/bin/bash: adb: command not found` (exit 127). Não é evidência de indisponibilidade dos capítulos. Não houve teste/build Gradle nem mudança de código; o projeto é CI-first e a observação Android está bloqueada.

## Identidade de fonte e limites observados

| Serviço/representação | Identificador observado | Idioma/edição | Versão |
|---|---|---|---|
| MangaDex | UUID `d8a959f7-648e-4c8d-8f23-f1f3f8e129f3` | API consultada EN e PT-BR; release records comunitários, edição editorial não indicada | API atual, não pinada |
| MangaFire | slug `729pj-one-punch-man` | língua/edição do usuário não conhecida | Add-on instalado desconhecido |
| MangaBall | `one-punch-man-68515501702284f83417844d` | página mostra filtro “Chapter Languages All”; chapters não vieram no snapshot textual | site identifica `v2025.6.3`; Add-on desconhecido |
| Tsuzuki no smoke | UUID canônico e `ContentBinding` ausentes do handoff | Add-on MangaFire nomeado, mas sem língua/Source interna | versão de app/Add-on não registrada no relatório |

Não se inferiram `CanonicalTitleId`, source IDs Mihon, binding, URL de fonte ou edição dos slugs do site. Tais valores são do aparelho/DB do usuário.

## A. Catálogos/páginas originais

### MangaDex

O link de título MangaDex fornecido não pôde ser aberto pelo navegador de pesquisa (erro não recuperável); não houve retry/contorno. Em seu lugar, foram feitas consultas públicas de metadados pela API documentada, sem reader/páginas:

- `GET /manga/d8a959f7-648e-4c8d-8f23-f1f3f8e129f3` — HTTP 200, `status=ongoing`, `lastChapter` vazio, `updatedAt=2026-06-05T11:29:17Z`.
- `GET /chapter?manga={id}&limit=1&order[chapter]=asc` — HTTP 200, `total=1918` para releases de todos os idiomas/grupos. Isso não são 1.918 capítulos editoriais distintos.
- Com `translatedLanguage[]=en`: HTTP 200, total=0. Com `translatedLanguage[]=pt-br`: HTTP 200, total=169. Coleta paginada PT-BR: offset 0 = 100, offset 100 = 69, ambos HTTP 200; duas requisições, ordem ascendente pelo label solicitada.
- PT-BR: 169 upload rows, 163 labels distintos, 6 labels duplicados (`94.1`, `94.2`, `94.3`, `118`, `123`, `130`); inteiros entre 1–219 com 84 ausentes. Primeiros ausentes: 94, 125–126, 137–153; maior label integer observado é #219. `externalUrl` foi nulo em todos os 169 rows: o registro não tem link externo, mas isso **não** prova que o reader carregue.
- #1: ID `d7ed204c-e2d0-422d-afab-63309a846545`, grupo `I Wish Scans`, título `Um Soco`, pages metadata 20. #2: `41eab5e1-8a6b-4af9-a589-d6c8b259e6b1`, mesmo grupo, `Caranguejo e Desempregado`, pages metadata 18. Nenhuma página foi aberta.

Filtro EN zero é somente ausência de releases sob esse filtro e snapshot; não significa que a obra não tenha capítulos noutras línguas/edições.

### MangaBall

O fetch textual da URL fornecida retornou o título certo, versão do site `v2025.6.3`, status `Ongoing`, `Published: 2012 0 chapters`, e `0 Chapters with 0 Translations`; o próprio texto indica `updated 2d ago`. A nota da página afirma que, após um takedown notice dos detentores enviado ao MangaDex, fan translations de One-Punch Man estão indisponíveis para upload e aponta a leitura integral ao Shonen Jump. Isso documenta o snapshot textual retornado, não prova o que aparece numa sessão/browser interativo do usuário. Não abriu chapters nem se consultou API privada.

### MangaFire

O navegador de pesquisa identificou a URL do título, mas trouxe zero linhas de conteúdo textual e nenhum inventário/status HTTP utilizável. Não se tentou API não documentada, WebView, CAPTCHA, token, proxy ou outro contorno.

Relatos públicos de extensão indicam riscos gerais, não causa OPM: issue [#17403](https://github.com/keiyoushi/extensions-source/issues/17403) relata Browse/Search vazios após alteração do site; issue [#18760](https://github.com/keiyoushi/extensions-source/issues/18760), em MangaFire 1.6.32 e outro título, relata WebView com chapters entre os retornados no Mihon. São reports de terceiros, sem reprodução aqui.

### Edição editorial de comparação

A VIZ oficial EN confirma #137/#138/#139 consecutivos; a página de #138 aponta 137 anterior e 139 seguinte. A página oficial #234 aponta #233 anterior e #235 seguinte. As páginas mostram `Join to read`; são referências de label/edição, não disponibilidade no Mihon, nem equivalência automática com MangaFire ou MangaDex PT-BR.

## B. Inventário bruto da extensão Mihon

**Não observado para MangaFire, MangaBall ou MangaDex Add-on.** Não há Add-ons/dispositivo/ADB, pacote instalado, version, source IDs, `SChapter` exportado ou logs. Primeiro/último, contagem, paginação, duplicatas, gaps, locale e leitura por extensão são desconhecidos. O usuário reporta MangaFire até #234, mas essa observação não foi verificada nesta sessão.

O repositório não contém a implementação das extensões MangaFire/MangaBall/MangaDex: Add-ons são carregados em runtime. Outra versão pública da extensão não substitui a que está instalada no aparelho.

## C. Inventário Tsuzuki e reconciliação

- `MihonChapterInventoryGateway.fetchLive()` resolve Manga/Source associados e chama `source.getMangaUpdate(fetchDetails=false, fetchChapters=true)`. Mapeia cada `update.chapters` em snapshot e informa `chapters=${snapshots.size}` em log; não há corte de 138, paginação própria, nem transformação de lista em páginas (`app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonChapterInventoryGateway.kt:67-120`). Se o Source devolver 1–234, o gateway percorre essas linhas; se devolver 138–234, ele vê somente isso. Site parsing/paging está fora do repo.
- `MihonAddonProviderFactory` chama o probe com `refresh=true` (`app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonAddonProviderFactory.kt:37-42`). Cache de inventário é memory-only TTL 2 min/LRU 128; refresh pula a entrada normal, embora possa coalescer com fetch in-flight igual.
- `MihonChapterProbeProvider` usa apenas bindings `AVAILABLE` para o Add-on; omite snapshot sem chapter ID e URL; transforma nome/número em evidence `ADDON_PROVISIONAL` (`app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonChapterProbeProvider.kt:30-95`).
- `ResolveContentBinding.executeAll` reutiliza bindings AVAILABLE existentes sem buscar o título novamente; se não há binding utilizável, pesquisa cada sourceId Mihon e materializa candidatos acima de limiares (`domain/src/main/java/tachiyomi/domain/tsuzuki/content/interactor/ResolveContentBinding.kt:68-160`). Binding errado/antigo pode persistir; não há binding OPM do aparelho a examinar.
- `ReconcileChapterEvidence` exige confidence de evidence e parser ≥0.95 e identidade específica para mapear Add-on em `CanonicalChapter`; evidence não confiável pode persistir com canonical ID nulo (`domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/ReconcileChapterEvidence.kt:72-96`). `ReconcileChapterInventory` reconcilia por identidade estrutural e não apaga chapters omitidos; o modelo `SourceChapterInventory` diz que lista vazia não implica deletion.
- `RefreshChapterEvidence.collectAddonEvidence` converte falha de binding/probe em `emptyList()` (`domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/RefreshChapterEvidence.kt:109-155`). Pode confundir falha com ausência de novas observações e manter DB anterior. Risco de observabilidade confirmado, uso real desconhecido.

Nenhum snapshot real, parse/confidence, evidencia mapeada, canonical row ou contagem C foi observada neste dispositivo (inexistente).

## D. Lista na interface

- `CanonicalTitleScreenModel.loadCachedFirst()` publica estado local antes do refresh remoto em background (`app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModel.kt:283-380`).
- `loadLocalState` inclui capítulo confirmado, ou mapeado por evidence, ou com progresso/download. Evidence Add-on sem mapping pode ficar fora (`.../CanonicalTitleScreenModel.kt:383-476`).
- `withMetadataSlots` combina contagens positivas de Kitsu/MAL e rows reais; `buildCanonicalChapterOutline` gera slots `1..count` e ordena pela identidade estruturada (`.../CanonicalTitleScreenModel.kt:479-494`; `domain/.../BuildCanonicalChapterOutline.kt:29-79`). Kitsu OPM `chapterCount=null` foi observado no estudo anterior em 2026-09-22, mas identidade MAL/contagens armazenadas no aparelho são desconhecidas.
- UI lista `state.chapters` na ordem recebida sem filtro numérico `>=138` (`app/src/main/java/eu/kanade/presentation/tsuzuki/detail/CanonicalTitleDetailScreen.kt`, bloco Loaded/LazyColumn). Campo `addonCoverage` existe no model/helper, mas não foi localizado rendering na tela; o usuário talvez não veja que lista é partial.
- `ChapterCoverageTest`: fixture `[138,139]` → first discovered=138 e coverage count=2, first=138,last=139. Sintético; não é inventário real de MangaFire.

## Rótulos específicos solicitados

| Label | MangaDex EN API | MangaDex PT-BR API | MangaFire | MangaBall | VIZ EN editorial | Tsuzuki no aparelho |
|---:|---|---|---|---|---|---|
| 1 | filtro total 0 | returned; ID no CSV | não observado | fetch retornou zero chapter rows | catálogo editorial | nenhum snapshot |
| 2 | filtro total 0 | returned; ID no CSV | não observado | fetch retornou zero chapter rows | catálogo editorial | nenhum snapshot |
| 137 | filtro total 0 | label literal ausente | não observado | sem chapter rows no fetch | VIZ prev de #138 | unknown |
| 138 | filtro total 0 | label literal ausente | Add-on não testado | sem chapter rows no fetch | VIZ #138; adjacente 137/139 | usuário viu primeira #138 em smoke; ID/variant/página não capturados |
| 139 | filtro total 0 | label literal ausente | não observado | sem chapter rows no fetch | adjacente a #138 | test fixture synthetic only |
| 233 | filtro total 0 | label literal ausente | usuário relata, não reproduzido | sem chapter rows no fetch | prev de #234 VIZ | unknown |
| 234 | filtro total 0 | label literal ausente | usuário relata, não reproduzido | sem chapter rows no fetch | VIZ #234; next #235 | unknown |

CSV registra por obra/source/language/label estados de fronteira, IDs e URLs verificáveis. “Ausente na resposta filtrada” não equivale a capítulo editorial inexistente; “listed” não equivale a páginas legíveis.

## Causas: conclusão e status

| Possível causa | Evidência | Conclusão |
|---|---|---|
| binding de obra/edição errado | Código reaproveita binding AVAILABLE; ID/URL real do usuário indisponíveis | não resolvido |
| idioma filtrado | Gateway não filtra locale; só consulta bindings disponíveis e guarda language do binding. MD EN=0/PT-BR=169 | gateway sem filtro explícito, mas binding/config desconhecidos |
| paginação na extensão | Gateway não pagina; recebe resultado final do Mihon Source | possível em A→B, não provado |
| parser de site/extensão | issues MangaFire em outros títulos mostram risco | possível, não reproduzido OPM |
| cache | probe força refresh; state local persistido carrega primeiro e não expira | cache LRU não é causa direta comprovada; dados persistidos parciais são hipótese |
| normalização/reconciliação | sem cut-off 138 e sem deletion; URL vazio/parse <0.95 pode impedir mapping | não resolvido sem raw rows |
| dedupe/ordem | identity estruturada, variant por source ID+chapter ID; UI sem corte | não há evidência de truncamento em 138; merge entre edições não inspecionado |
| erro tratado como vazio | aggregator apaga falha de binding/provider e retorna lista vazia | risco de observabilidade confirmado, uso real desconhecido |
| link externo/página real | MD PT-BR #1/#2 com `externalUrl=null`, pages counts são metadata; nenhuma page abriu | leitura real não confirmada |

## Correção mínima condicionada à fronteira

Não adicionar fallback ainda.

1. **Site A lista 1–137, raw Mihon B não:** corrigir extensão/site selector, paginação, idioma ou parser com a versão instalada; não inventar chapters no Tsuzuki.
2. **B tem 1–137, gateway C não:** testar mapping `mangaId/sourceId/sourceUrl`, empty source update, erro/timeout e snapshot mapping. Corrigir runtime somente se prova de perda no gateway existir.
3. **C contém rows mas evidence não mapeia:** regressão com labels/números reais 1,2,137–139,233–234; verificar URL, parse confidence, canonical ID e variants. Não unir edições só por número.
4. **Canonical DB tem #1–137 mas D não:** corrigir filtro/cache UI demonstrado; regressão garante visibilidade e ordem sem apagar progresso.
5. **Binding/network falha:** manter failure distinto de vazio/unknown, com teste de erro sem remover observações históricas.
6. **Só se outra fonte instalada tiver mesma edição/língua e chapter variant mapeada/abrível** aplicar resolução por capítulo existente; a necessidade de fallback novo não foi provada.

## Procedimento curto para reproduzir no APK (pendente)

1. Registrar APK SHA, versão Mihon/Tsuzuki, Add-on ID e versão instalada, sourceId interno/idioma, binding ID, canonicalTitleId, source manga ID/URL e confidence/verification. Não exportar banco, cookies ou tokens.
2. Em browser normal, para cada site registrar edição/locale/filtro, contagem/paginação, first/last e labels 1,2,137,138,139,233,234. MangaDex EN/PT-BR separados; diferenciar grupos/external URL. Sem login/bypass ou download de images.
3. No app, preservar estado original; abrir OPM, executar um refresh, gravar a cada boundary só contagem, labels, ID/URL boolean ou mascarado, idioma, source mapping ID, confidence, evidence mapped/unmapped, canonical number IDs, lista final UI. Separar falha de rede de resultado vazio.
4. Seguir as mesmas 7 labels de A site → B `getMangaUpdate().chapters` → snapshots → parsed evidence/mapping → DB canonical → `state.chapters`/UI. A primeira divergência localiza a perda.
5. Para chapters que a própria fonte listar, testar abertura/continuação e registrar apenas sucesso/falha e page-list count. Não salvar bytes, imagens, page URLs, cookies, credenciais ou tokens.
6. Repetir um Add-on/source por vez; não trocar idioma/edição silenciosamente. Parar diante de bloqueio/takedown e registrar erro.

Logs atuais `TsuzukiPerf` registram contagem/duração do gateway, não a passagem de cada label pelas quatro fronteiras. Instrumentação redigida ou breakpoint exigiria APK/teste posterior aprovado; não gerar APK nesta rodada.

## Fontes

- Repositório: [Tsuzuki spec](../TSUZUKI-SPEC.md), [runtime modular spec](../superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md), [plano smoke](../superpowers/plans/2026-09-22-tsuzuki-post-apk-smoke-round-2.md), [relatório smoke](../superpowers/reports/2026-09-22-post-apk-smoke-round-2-validation.md), [estudo R3](https://github.com/jssantogit/mihon/blob/tsuzuki/research-chapter-availability/docs/research/unified-chapter-catalog-study.md), [handoff R3](https://github.com/jssantogit/mihon/blob/tsuzuki/research-chapter-availability/docs/research/unified-chapter-catalog-handoff.md).
- URLs dadas pelo usuário: [MangaDex](https://mangadex.org/title/d8a959f7-648e-4c8d-8f23-f1f3f8e129f3?tab=chapters&order=asc), [MangaFire](https://mangafire.to/title/729pj-one-punch-man), [MangaBall](https://mangaball.net/title-detail/one-punch-man-68515501702284f83417844d/). [MangaDex API docs](https://api.mangadex.org/docs/), [MangaDex AUP](https://gitlab.com/mangadex-pub/mangadex-api-docs/-/blob/main/index.md).
- Edição de referência: [VIZ OPM #138](https://www.viz.com/shonenjump/one-punch-man-chapter-138/chapter/22212), [VIZ OPM #234](https://www.viz.com/shonenjump/one-punch-man-chapter-234/chapter/50927), [catálogo](https://www.viz.com/shonenjump/chapters/one-punch-man), [Terms](https://www.viz.com/terms). Somente labels editoriais; sem permissão de scraping/redistribuição inferida.
- Extensão risk reports: [issue #17403](https://github.com/keiyoushi/extensions-source/issues/17403), [issue #18760](https://github.com/keiyoushi/extensions-source/issues/18760). Relatos terceiros, não evidência de causa específica OPM.
