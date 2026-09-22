# Handoff — catálogo unificado de capítulos (rodada 3)

**Branch:** `tsuzuki/research-chapter-availability` (base `tsuzuki/research-chapter-metadata`). **Status:** pesquisa concluída dentro dos limites documentados; não houve alteração de produção, build, dispositivo, PR ou merge. Leia o [estudo completo](unified-chapter-catalog-study.md) e a [matriz CSV por upload/checkpoint](unified-chapter-coverage.csv).

## Decisões que a evidência suporta

- **MangaFire #138:** esse início aparece no screenshot/plano de smoke anterior; nesta sessão não houve runtime/device/Add-on para confirmar estado atual. Nenhuma lacuna de One-Punch Man foi realmente preenchida por fonte de leitura Mihon. A primeira lacuna operacional conhecida permanece **#1** (intervalo prévio apontado: #1–137).
- **Alternativa editorial para OPM:** VIZ EN cataloga #1, #137, #138 e #237; portanto a existência/ordem dos rótulos anteriores a #138 está verificada para aquela edição. VIZ não foi integrado ao Tsuzuki, os termos barram scraping/reprodução sem consentimento e não foi testado conteúdo autenticado; não conte #1–137 como chapters disponíveis via app.
- **MangaDex:** 1.153 metadados de upload em quatro títulos/EN e PT-BR. São observações de releases/grupos. Sem abrir páginas, não provam legibilidade, autorização de redistribuição, edição equivalente nem catálogo completo. O snapshot evidencia ausências expressivas: OPM PT-BR #94/#125–126/#137–203 e outras; Dandadan EN apenas 10 registros e PT-BR 140 labels inteiros ausentes entre extremos; HxH EN #411–414 e PT-BR #53 ausentes; Death Note somente #1–4/#108 EN.
- **Dandadan:** VIZ EN mostra #1, #231/#231.1 e #246. A sequência editorial daquela edição é verificável em catálogo, não a disponibilidade integral/contínua no Tsuzuki nem a edição PT-BR.
- **Hunter × Hunter:** VIZ começa em #1 e mostra #411–420; MangaDex tem labels PT-BR #0/#0.5, sem confirmação editorial. Não promover 0/.5 a início canônico; #1 é o primeiro capítulo oficial observado.
- **Death Note:** catálogo VIZ permite definir série principal #1–108; MangaDex não comprova 5–107. “108” é rótulo final/estrutura editorial, não 108 leituras acessíveis no Tsuzuki. Extras/one-shots são distintos.

## Respostas objetivas ao orquestrador

1. **Encontramos primeiro capítulo para leitura?** Encontramos #1 em catálogos oficiais VIZ e em alguns labels MangaDex; **não** confirmamos abertura pelo leitor Tsuzuki para nenhuma obra.
2. **Sequência até último conhecido?** Editorialmente, checkpoints VIZ: OPM #1–237, Dandadan #1–246, Hunter × Hunter #1–420 e Death Note #1–108. Isso não prova que cada item abre em Tsuzuki. Nenhuma sequência de Add-on foi testada capítulo por capítulo.
3. **Quantas lacunas restam?** Cobertura real de fontes Mihon não determinável sem runtime. No snapshot MangaDex upload, são 84 labels inteiros ausentes OPM PTBR (1–219), 236 Dandadan EN (1–246), 140 Dandadan PTBR (1–246), quatro HxH EN (1–420), um HxH PTBR (0–410) e 103 Death Note EN (1–108); esses números não representam gaps editoriais globais. OPM MangaFire pré-138 continua sem preenchimento operacional demonstrado.
4. **Quantas alternativas por capítulo?** Desconhecido como disponibilidade legível: nenhum teste live. CSV identifica upload records duplicados por label, não capítulos alternativos confirmados; VIZ é catálogo editorial/site separado, não ContentOption Mihon.
5. **Ordem editorial verificável?** Sim, para checkpoints/catálogos VIZ da edição inglesa, incluindo especiais decimais explicitamente listados. Não para equivalência automática entre MangaDex/MangaFire/MangaBall ou rótulos 0/.5.
6. **Evidência de abertura?** Nenhuma abertura via Tsuzuki nesta rodada. VIZ páginas mostram rotas/leitor oficial sujeitas a território e entitlement; MangaDex APIs retornaram metadados, páginas não acessadas.
7. **Bloqueios?** `adb` ausente; Add-ons dinâmicos/dispositivo inacessíveis; termos não autorizam ingestão comercial/redistribuição automaticamente; metadados e edições não casam sem mapping; fallback pode alterar idioma sem autorização se não houver guarda UX.

## Menor arquitetura conceitual que atende à experiência

Preservar o UUID/ID canônico de título e de capítulo Tsuzuki. Manter **inventário editorial por edição** separado de **availability/source variants**. Um capítulo só vira item canônico quando label, tipo e ordem são sustentados por uma edição de referência ou revisão; cada provedor oferece zero ou mais variants ligadas por mapping auditável. Mostrar gaps comprovados como indisponíveis; estruturar ausência de dado como desconhecida. Sem preencher intervalos numéricos por algoritmo.

Ao faltar fonte preferida, buscar somente variantes da mesma edition/language (ou equivalente explicitamente validado); pedir autorização para troca de source. Outra língua, edição, scanlator ou rótulo fracionário deve aparecer com aviso/opção, nunca troca silenciosa. Registrar avanço no `canonicalChapterId`. Snapshot cacheado com timestamp e revalidação sob demanda reduz fan-out ao abrir série; falhas, vazio e stale precisam estados distintos. A arquitetura existente oferece runtime inventory, resolver por `canonicalTitleId + canonicalChapterId`, options de fallback, cache curto e concorrência limitada, então a lacuna primária é evidência, licenças, edition mapping e abertura real — não autorização para mudar os contratos nesta tarefa.

## Consultas e referências da rodada

- MangaDex Chapter API pública: 31 GETs anotados, 30 HTTP 200, um HTTP 400 descartado; oito totais obra/idioma e 18 páginas completas de dados (1.153 linhas). Somente metadados. Os IDs e URLs exatos estão no CSV.
- Catálogos oficiais VIZ consultados manualmente: One-Punch Man; Dandadan; Hunter × Hunter; Death Note. Termos VIZ atuais e regra pública do MangaBall foram consultados.
- Repositório: spec, plano e smoke anterior, relatórios 1/2, modelos/interactors Mihon/Tsuzuki. `adb devices -l` não executável porque `adb` não está instalado; não substituído por uma inferência.
- Links: [MangaDex API docs](https://api.mangadex.org/docs/), [MangaDex AUP](https://gitlab.com/mangadex-pub/mangadex-api-docs/-/blob/main/index.md), [VIZ terms](https://www.viz.com/terms), [MangaBall site rules](https://mangaball.net/article/site-rule/), [MangaFire extension issue #18760](https://github.com/keiyoushi/extensions-source/issues/18760). URLs dos catálogos por obra estão no estudo.

## Pendências / próximo gate

1. Disponibilizar Android + Add-ons autorizados e reproduzir OPM MangaFire: inventário completo ordenado, idiomas/edições, e tentativas reais de abrir #1, #137/#138, lacunas e extremos. Repetir para os outros três títulos.
2. Identificar edição e política de licenciamento por source; solicitar por escrito permissão para index/cache/exibir e eventual monetização. Não tratar API pública ou ausência de resposta como licença.
3. Revisar correspondências capítulo↔edição por label especial, número fracionário, nova edição e ID, com amostra manual primária antes de habilitar fallback.
4. Só apresentar “sequência completa disponível” após verificação de todos os itens/intervalos/editorial gaps do scope e evidência de abertura por source/language; caso contrário exibir last-known/source-specific inventory com gaps e estado unknown.

**Arquivos da rodada:** `unified-chapter-catalog-study.md`, `unified-chapter-coverage.csv`, `unified-chapter-catalog-handoff.md`. Não há aprovação de integração ou licença presumida neste handoff.
