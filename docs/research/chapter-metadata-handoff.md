# Handoff — metadados de capítulos

**Estado:** pesquisa executada em `tsuzuki/research-chapter-metadata` (base `origin/tsuzuki/bootstrap`) em 2026-09-22; sem alteração de produto, sem PR/merge. Ver relatório e CSV adjacentes antes de decidir arquitetura. Esta pesquisa não autoriza integração automática de qualquer serviço.

## Evidência principal

- Piloto obrigatório (One-Punch Man, Dandadan, Hunter × Hunter, Death Note) executado antes de ampliar; bateria completa de 30 obras feita nas quatro APIs públicas documentadas. **30/30** identificadas em Kitsu, MangaBaka, MangaUpdates e MangaDex após resolução por IDs; **8/30** conferidas com último rótulo VIZ; **0/30** têm inventário editorial completo, tipado e independente demonstrado.
- Kitsu, baseline atual, tem `chapterCount` em **20/30**; falta nas três séries em curso/hiato do piloto. MangaBaka tem `total_chapters` 30/30, mas importa Kitsu/MangaUpdates, com licença CC BY-NC-SA 4.0 e termos upstream: não é segundo voto independente. MangaUpdates tem `latest_chapter` 30/30, porém é **último número**, não quantidade; `0` em quatro fichas não significa capítulo zero. MangaDex tem rótulos de uploads em 30/30, mas isso mede cobertura de uma fonte, não índice editorial.
- VIZ mostra One-Punch Man 237 em 2026-09-16; MangaDex inclui rótulo 311 na mesma obra. VIZ mostra Spy x Family 140.1, enquanto valores inteiros dos metadados param em 140. Extras, partes e redesenhos impedem converter o máximo em cardinalidade. A busca inicial MangaUpdates por *Look Back* trouxe homônimo errado; ID cruzado corrigiu. Prova de necessidade de checar identidade e edição.
- [VIZ proíbe scraping/indexação automatizada sem consentimento](https://www.viz.com/terms); [MANGA Plus](https://mangaplus.shueisha.co.jp/terms/eng/) não fornece licença geral de ingestão. [MangaDex exige atribuição e veda ads/serviço pago via API](https://gitlab.com/mangadex-pub/mangadex-api-docs/-/blob/main/index.md). [MangaBaka exige atribuição e separa direitos de terceiros](https://mangabaka.org/data/api). [MangaUpdates AUP](https://api.mangaupdates.com/openapi.yaml) exige crédito, cache e espaçamento. Permissões comerciais Kitsu/MangaUpdates não ficaram claras: revisão/contato necessário.

## Decisões para o orquestrador

1. Definir explicitamente se “catálogo” do MVP é **lista disponível na fonte Mihon**, **último rótulo editorial de uma edição**, ou **inventário editorial completo incluindo ausentes/especiais**. A pesquisa só demonstrou os dois primeiros parcialmente.
2. Aprovar política de proveniência/independência: MangaBaka não confirma Kitsu/MangaUpdates sem prova de origem do campo. Identidade canônica Tsuzuki continua UUID; IDs externos e capítulos da fonte são vínculos, nunca chaves canônicas.
3. Pedir revisão de licença/atribuição e, se necessário, contato formal com provedores/editoras antes de selecionar fontes para produto. Não usar API privada/scraping para tapar lacunas.
4. Definir UX de incerteza: mostrar “quantidade desconhecida” ou “último conhecido por [edição/fonte/data]” em vez de denominador exato, com principais, partes, extras, variantes e ausentes em eixos distintos.

## Pendências

Validação editorial independente das outras 22 obras; semântica e origem por campo `total_chapters` MangaBaka; regras de edição/redesenho (*One-Punch Man*, *Fullmetal Alchemist*, *Chainsaw Man*); divergências *Berserk*, *Blue Lock*, *Kingdom* e outras listadas no estudo; confirmação escrita de licença, limites e atribuição; teste longitudinal de frescor e cobertura regional/idioma. Não afirmar que a pesquisa provou um catálogo universal confiável.

## Consultas e artefatos

CSV: 120 linhas de provedores, URLs/HTTP/IDs/datas/valores brutos/rótulos; relatório: método, tabela das 30 obras, fontes oficiais, divergências e alternativas. No conjunto final: 30 buscas em cada API, 30 detalhes MangaBaka, 30 detalhes MangaUpdates, 30 aggregates MangaDex, além dos desempates por ID descritos no relatório. Os scripts HTTP foram temporários em `/tmp` e **não** entram no commit. Nenhum build Gradle é pertinente para documentos; nenhuma evidência de Fast CI é alegada sem push/execução.
