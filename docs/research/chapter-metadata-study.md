# Estudo de metadados de capítulos — Tsuzuki

**Data da observação:** 2026-09-22 UTC. **Branch:** `tsuzuki/research-chapter-metadata`, criada de `origin/tsuzuki/bootstrap`. **Natureza:** pesquisa; nenhuma integração ou código de produção. O CSV adjacente contém 120 linhas (30 obras × 4 APIs), IDs, valores brutos, URLs, datas e rótulos de capítulos disponíveis. Respostas de APIs podem mudar após esta data.

## Conclusão principal

Nenhuma das quatro APIs testadas fornece, isoladamente, um catálogo editorial completo e confiável para todas as obras. O Kitsu continua útil como ponto de descoberta/identidade, mas `chapterCount` estava ausente em 10/30 obras. MangaBaka preencheu `total_chapters` em 30/30, porém é agregador que importa Kitsu e MangaUpdates e não constitui confirmação independente. MangaUpdates retornou `latest_chapter` em 30/30, mas esse campo não é uma contagem: vale `0` para os dois one-shots da amostra e para outras obras; valores como *Vagabond* `2` e *Bleach* `250` não descrevem seu total editorial. MangaDex forneceu rótulos por volume de uploads em 30/30, porém sua cobertura de leitura é fragmentária, multilíngue, sujeita a edições, disponibilidade e controles; seus rótulos não são um índice editorial completo. Referências de editoras são excelentes para conferência pontual, não uma API reutilizável autorizada.

**Recomendação conceitual:** manter Kitsu na descoberta; usar metadados complementares somente após análise de licença/atribuição e checagem de identidade; separar o eixo de *estrutura editorial* do eixo de *disponibilidade de leitura*. Exibir contagem exata somente quando houver definição de edição e tipos de capítulo, inventário justificável e evidência suficiente. Caso contrário, apresentar “último capítulo conhecido”, “capítulos encontrados na fonte” ou “contagem desconhecida”, jamais converter um desses valores em outro silenciosamente.

## 1. Contexto do Tsuzuki

Leitura de `docs/TSUZUKI-SPEC.md` §§5, 6 e 9, `docs/TSUZUKI-DEVELOPMENT.md`, `.agents/rules/tsuzuki-development.md`, planos de catálogo Kitsu e capítulos canônicos de 2026-09-18/19, e dos modelos/DTOs atuais. A arquitetura já distingue `CatalogItem` efêmero de `CanonicalTitle` persistido com UUID Tsuzuki. `ExternalIdentity` guarda IDs de provedores; `SourceTitleMapping` guarda correspondência com obras das fontes Mihon. O cliente atual lê `https://kitsu.io/api/edge/manga`, mapeia `chapterCount` para `CatalogItem.chapterCount` e status, mas não obtém lista editorial de capítulos. O motor existente modela `CanonicalChapter` com `displayNumber`, `baseNumber`, `part`, `alphaSuffix`, `type` (`REGULAR`, `PROLOGUE`, `EPILOGUE`, `EXTRA`, `SPECIAL`, `ONESHOT`, `UNKNOWN`) e confiança. `ChapterVariant`/`SourceChapterSnapshot` preservam registros Mihon de fontes de leitura. O número `Double` do capítulo Mihon não é identidade canônica. A especificação já exige reconciliação conservadora, lacunas e incerteza explícita.

**Lacuna de informação:** não existe, nos quatro retornos testados, um inventário universal, autorizado e tipado de capítulos editoriais por edição. Faltam equivalência confiável entre edições/partes/redesenhos, classificação de extras e prólogos, prova de completude do inventário, data de publicação por capítulo em todas as obras e autorização para ingestão editorial massiva. `updatedAt` da ficha não demonstra que o capítulo mais recente foi atualizado. A existência de um capítulo numa fonte Mihon/MangaDex não demonstra que é o capítulo editorial correspondente, nem que todos os capítulos anteriores existem nela.

## 2. Método e consultas reais

1. **Piloto obrigatório:** One-Punch Man, Dandadan, Hunter × Hunter e Death Note, antes de expandir. Primeiro teste identificou `406` do Kitsu por `Accept: application/json`; a chamada aceita `application/vnd.api+json`. Um GET em `/v1/series/search` do MangaUpdates retornou `405`; a operação documentada é `POST`. Ambos foram corrigidos; não foram contados como dados ausentes. O piloto corrigido constatou Kitsu `chapterCount=null` nos três primeiros, e `108` em Death Note; MangaBaka `237/246/420/108`; MangaUpdates `latest_chapter=237/246/420/108`; MangaDex máximo de rótulo inteiro `311/246/420/108`. O `311` do MangaDex em One-Punch Man **não** corresponde ao último `237` da edição VIZ; não foi arbitrariamente corrigido.
2. **Amostra ampliada:** 30 obras pré-definidas, com concluídas, em curso, hiatos, one-shots, extras, números `0`/`0.5`, edições e capítulos fracionários. Por obra: busca pública Kitsu `GET /api/edge/manga?filter[text]=…`, MangaBaka `GET /v1/series/search?q=…`, MangaUpdates `POST /v1/series/search` (JSON `search`), MangaDex `GET /manga?title=…`; depois detalhes públicos `GET /v1/series/{id}` (MangaBaka e MangaUpdates) e `GET /manga/{id}/aggregate` (MangaDex). A coleta não usou credenciais, API privada, páginas raspadas ou conteúdo de capítulos. Busca MangaBaka espaçada por 2,1 s, dentro do limite documentado de 30 buscas não cacheadas/minuto; requisições MangaDex serializadas, muito abaixo do limite global historicamente documentado. Scripts temporários ficaram em `/tmp`, fora do repositório. Endpoints exatos e HTTP de cada observação estão no CSV.
3. **Resolução de identidade:** título exato/alias não bastou. Foram usados tipo de obra e os IDs cruzados declarados por MangaBaka (`source.kitsu.id`, `source.manga_updates.id`) e, para casos ambíguos, links de MangaDex `kt`/`mu`, ano e edição. Nos 30 registros MangaBaka escolhidos, os IDs cruzados de Kitsu e MangaUpdates coincidiram com os registros escolhidos. Isso valida o mapeamento, **não** a independência nem o número. Exemplo de falso positivo evitado: busca MangaUpdates por *Look Back* encontrou primeiro outra obra de 2013, 5 volumes; o registro correto, `Look Back (FUJIMOTO Tatsuki)`, foi buscado pelo ID cruzado `62512335978`. A busca por *Fullmetal Alchemist* mostrou duas fichas MangaDex de mesmo nome; foi escolhida `dd8a907a-…` por ano 2001, status concluído e links `kt=66`, `mu=8q91xb4`, em vez de ficha de reedição digital em andamento.
4. **Referência editorial:** checagem manual pontual de páginas públicas VIZ via busca/página, sem extração automatizada em massa, por restrição expressa dos [termos VIZ](https://www.viz.com/terms). Oito obras possuem último rótulo VIZ anotado no CSV. [Kodansha](https://kodansha.us/series/attack-on-titan/) foi consultada como exemplo de catálogo de edições/volumes, não como denominador universal de capítulos. As demais 22 não receberam validação editorial independente: a ausência é explícita.

### Vocabulário de medida

- **`chapterCount` Kitsu / `total_chapters` MangaBaka:** números publicados pelo provedor; não há lista tipada que prove, nesta pesquisa, se incluem extras, versão alternativa ou apenas capítulos principais. Não renomeá-los como contagem canônica.
- **`latest_chapter` MangaUpdates:** último número registrado segundo o provedor, não cardinalidade. Valor `0` é preservado como bruto e não interpretado como “capítulo zero”.
- **MangaDex `/aggregate`:** mapa de rótulos por volume e uploads. `unique_chapter_labels` no CSV é cardinalidade de *rótulos textuais deduplicados* na resposta, não capítulos editoriais nem capítulos efetivamente legíveis em Mihon. O “último” CSV é só o maior rótulo inteiro observado; decimais, `none`, volumes e versões são preservados separadamente. Uma lista de rótulos não prova que cada item tenha páginas disponíveis ao usuário, nem que cobriu todos os idiomas.
- **VIZ:** último rótulo de sua edição/serviço na data, não verdade universal da edição japonesa, webcomic ou volume redesenhado.

## 3. APIs, uso e viabilidade

| Serviço | API e resultado real | Autenticação, limites, atualização | Uso/atribuição/viabilidade |
|---|---|---|---|
| **Kitsu** | [Documentação JSON:API oficial](https://hummingbird-me.github.io/api-docs/), `GET /api/edge/manga` retornou 200 para 30/30 buscas; `chapterCount` 20/30, lista editorial não encontrada. O código Tsuzuki já o usa. | GET de catálogo sem token nos testes; `Accept: application/vnd.api+json` exigido. Limite numérico não localizado na documentação consultada. `updatedAt` da ficha não indica atualização do capítulo. | A documentação menciona licença Apache 2.0 da **especificação**, não uma licença inequívoca para redistribuir dados. [Termos Kitsu](https://kitsu.io/terms) dependeram de JavaScript no acesso; permissões comerciais/redistribuição precisam confirmação. Baseline de descoberta, não denominador de progresso. |
| **MangaBaka** | [API oficial](https://mangabaka.org/data/api), `GET /v1/series/search` e `/v1/series/{id}`: 30/30 fichas e `total_chapters`, nenhum inventário de capítulos. | GET público sem token. Busca: 30/min, demais GET: 180/min, para misses de cache; 429 se exceder. Base global atualizada semanalmente, ficha entre 1–7 dias conforme upstream, segundo fornecedor. Schema ainda sem estabilidade v1.0. | Dados originais CC BY-NC-SA 4.0 com atribuição; dados terceiros retêm termos originais. [Termos](https://mangabaka.org/about/terms), [licença](https://mangabaka.org/about/data-license), [uso não comercial](https://mangabaka.org/about/data-license-noncommercial). Proibido scraping do site; API permitida dentro da licença. Uso comercial/monetização exige decisão/licença; atribuir MangaBaka **e** upstream. Corroboração não independente. |
| **MangaUpdates** | [OpenAPI oficial](https://api.mangaupdates.com/openapi.yaml) e [documentação](https://api.mangaupdates.com/): `POST /v1/series/search`, `GET /v1/series/{id}` funcionaram sem autenticação, 30/30 registros. Campo `latest_chapter`, sem lista editorial. | A documentação diz funções majoritariamente públicas; recursos de usuário requerem conta. AUP exige espaçar requisições e cache, mas não publica teto numérico no documento consultado. `last_updated` da ficha não garante atualização do último capítulo. | AUP exige crédito e proíbe fraude, spam, ilegalidade e dano à base; não foi encontrada licença explícita de redistribuição/comercial no documento examinado. Contatar mantenedores antes de depender em produto. `status` textual mistura volumes e hiato; `latest_chapter` pode ser de releases/catalogação, não total. |
| **MangaDex** | [Docs oficiais](https://api.mangadex.org/docs/) e [AUP oficial em GitLab](https://gitlab.com/mangadex-pub/mangadex-api-docs/-/blob/main/index.md). `GET /manga`, `GET /manga/{id}`, `GET /manga/{id}/aggregate` retornaram 200 na amostra. Aggregate trouxe rótulos em 30/30, não lista editorial. | GET públicos sem login no teste; limite global de 5 req/s/IP descrito em [post técnico MangaDex](https://mangadex.dev/an-api-to-rule-them-all/amp/) (histórico; conferir docs vigentes antes de produto). Disponibilidade e controles podem variar. Metadata `updatedAt` da ficha e upload não são atualização editorial. | AUP: crédito MangaDex e grupos, respeito a remoções; sem anúncios/serviço pago apoiado na API, salvo exceção aprovada. É fonte de uploads/variantes, não autoridade editorial. Viabilidade jurídica e cobertura variam; nunca substituir Mihon como mecanismo operacional de leitura. |
| **VIZ, MANGA Plus, editoras japonesas** | [VIZ capítulos](https://www.viz.com/shonenjump), [MANGA Plus](https://mangaplus.shueisha.co.jp/), [Shueisha](https://shonenjumpplus.com/), [Shogakukan](https://www.shogakukan.co.jp/), [Kodansha](https://kodansha.us/) fornecem referências públicas por edição, nem todas uma API de metadados licenciada. Não foram usados endpoints privados. | Acesso/território/assinatura/retirada de capítulos variam. [FAQ MANGA Plus](https://mangaplus.shueisha.co.jp/faq/eng/) diz que alguns capítulos finais de obras concluídas são removidos após 30 dias; presença para leitura não equivale a estrutura. | [VIZ veda scraping/indexação automatizada sem consentimento](https://www.viz.com/terms). [Termos MANGA Plus](https://mangaplus.shueisha.co.jp/terms/eng/) restringem usos fora do privado e direitos de conteúdo. Referências para conferência manual/negociação, **não** fonte automatizada presumida. |
| **AniList / MAL (alternativas)** | [AniList Media.chapters](https://anilist.gitbook.io/anilist-apiv2-docs/docs/reference/object/media) expressa quantidade quando completa, sem lista. [MAL API v2](https://myanimelist.net/apiconfig/references/api/v2) requer cadastro/credenciais; não foi amostrada nesta bateria, nem a antiga branch MAL foi tocada. | [AniList limite](https://anilist.gitbook.io/anilist-apiv2-docs/docs/guide/rate-limiting) documenta 90/min normal e aviso de redução temporária a 30/min. MAL exige fluxo autorizado; nenhum token de terceiros foi reutilizado. | [Termos AniList](https://anilist.gitbook.io/anilist-apiv2-docs/docs/guide/terms-of-use) restringem trackers concorrentes sem autorização e uso comercial. Não é fallback automático. Para MAL, verificar acordo e escopo antes de integrar. |

**Atenção:** API publicamente acessível não significa licença irrestrita. Este estudo não é parecer jurídico; termos e limites precisam de verificação na data de eventual implantação.

## 4. Resultados da amostra

Tabela: K = Kitsu `chapterCount`; B = MangaBaka `total_chapters`; MU = MangaUpdates `latest_chapter`; MD = maior **rótulo inteiro** do aggregate MangaDex; `n` = número de rótulos textuais distintos presentes no aggregate, não total editorial. `—` é campo nulo/sem rótulo inteiro, não zero. Valores de métricas diferentes são deliberadamente apresentados lado a lado, **não** como medidas equivalentes.

| Obra | K | B | MU | MD | n |
|---|---:|---:|---:|---:|---:|
| One-Punch Man | — | 237 | 237 | 311 | 445 |
| Dandadan | — | 246 | 246 | 246 | 136 |
| Hunter x Hunter | — | 420 | 420 | 420 | 428 |
| Death Note | 108 | 108 | 108 | 108 | 16 |
| One Piece | — | 1193 | 1193 | 1193 | 671 |
| Berserk | — | 401 | 386 | 386 | 406 |
| Vagabond | 327 | 327 | 2 | 327 | 327 |
| Naruto | 700 | 700 | 700 | 700 | 12 |
| Bleach | 706 | 706 | 250 | 686 | 14 |
| Dragon Ball | 520 | 520 | 149 | 519 | 521 |
| Fullmetal Alchemist | 116 | 150 | 150 | 108 | 125 |
| Attack on Titan | 141 | 141 | 139 | 127 | 2 |
| Chainsaw Man | 97 | 232 | 232 | 232 | 247 |
| Jujutsu Kaisen | 272 | 272 | 271 | 271 | 24 |
| My Hero Academia | 432 | 432 | 430 | 431 | 6 |
| Frieren: Beyond Journey’s End | — | 147 | 147 | 145 | 31 |
| Spy x Family | — | 140 | 140 | 140 | 181 |
| Oshi no Ko | 168 | 171 | 166 | 166 | 181 |
| Vinland Saga | 220 | 224 | 220 | 220 | 3 |
| Steel Ball Run | 95 | 96 | 30 | 95 | 98 |
| Yotsuba&! | — | 114 | 114 | 114 | 132 |
| Blue Lock | — | 342 | 272 | 362 | 137 |
| Kingdom | — | 877 | 832 | 885 | 106 |
| Akira | 120 | 120 | 0 | 10 | 10 |
| Monster | 162 | 162 | 0 | 162 | 162 |
| Pluto | 65 | 65 | 65 | 7 | 7 |
| Bakuman | 176 | 176 | 176 | 176 | 11 |
| Solanin | 29 | 30 | 29 | 29 | 29 |
| Goodbye Eri | 1 | 1 | 0 | — | 1 |
| Look Back | 1 | 1 | 0 | — | 1 |

### Referências editoriais conferidas (edição VIZ, 2026-09-22)

| Obra | Último rótulo VIZ | Observação |
|---|---:|---|
| [One-Punch Man](https://www.viz.com/shonenjump/chapters/one-punch-man) | 237 | A própria listagem tem `132.1`, `132.2` e numerosos `.1/.2`; `311` de MangaDex não deve ser adotado como editorial desta edição. |
| [Dandadan](https://www.viz.com/shonenjump/chapters/dandadan) | 246 | Também há `231.1`; 246 não é necessariamente quantidade de itens da lista. |
| [Hunter x Hunter](https://www.viz.com/shonenjump/chapters/hunter-x-hunter) | 420 | O intervalo após 410 evidencia pausa/recomeço; status MangaDex “ongoing” e MangaBaka “hiatus” divergem. |
| [Death Note](https://www.viz.com/shonenjump/death-note-chapter-108/chapter/13999) | 108 | VIZ confirma o rótulo 108; MangaUpdates descreve ainda volume extra/bunkoban. |
| [One Piece](https://www.viz.com/shonenjump/chapters/one-piece) | 1193 | B e MU têm o mesmo número, mas B agrega MU; não são duas provas independentes. |
| [Chainsaw Man](https://www.viz.com/shonenjump/chapters/chainsaw-man) | 232 | Kitsu `97` retrata parte/estado diferente; MangaDex marca “completed” e MangaBaka “releasing”. |
| [Jujutsu Kaisen](https://www.viz.com/shonenjump/chapters/jujutsu-kaisen) | 271 | B/Kitsu `272` versus MU/MD `271`; VIZ também mostra `262.2`. Sem definição do que inclui 272, não declarar erro absoluto. |
| [Spy x Family](https://www.viz.com/shonenjump/chapters/spy-x-family) | 140.1 | B/MU/MD mostram `140` inteiro; VIZ tem partes `.1/.2` em outros números também. “Último inteiro 140” não expressa o último item. |

A comparação é **de rótulos e edições**, não valida uma quantidade de capítulos principais. A página [Kodansha de Attack on Titan](https://kodansha.us/series/attack-on-titan/) mostra múltiplas edições (omnibus, colossal, box, color), ilustração concreta de que volume/edição não define a mesma cardinalidade de capítulos. Não foi feita extração automatizada de VIZ nem comparação editorial para as demais 22 obras.

### Cobertura, concordância e falhas observadas

- **Identidade:** 30/30 obras receberam registro nos quatro serviços após buscas, detalhes e desempate por IDs. A taxa de *retorno de ficha* é 30/30 por serviço nesta amostra escolhida; não estima cobertura do universo de mangás. Houve pelo menos **um falso positivo demonstrável** de busca por homônimo (*Look Back* no MangaUpdates) antes da correção; portanto “primeiro resultado” é inseguro. Duas fichas *Fullmetal Alchemist* do MangaDex exigiram distinguir edição. Persistem dúvidas de equivalência estrutural entre edições apesar da identidade de obra.
- **Campos:** Kitsu `chapterCount` 20/30, ausente 10/30 (inclui One-Punch Man, Dandadan e Hunter × Hunter); MangaBaka `total_chapters` 30/30; MangaUpdates `latest_chapter` 30/30, mas `0` em 4/30 (Akira, Monster, Goodbye Eri, Look Back), sem utilidade como total; MangaDex retornou aggregate com rótulos 30/30, mas rótulo inteiro máximo indisponível em 2 one-shots. **Inventário editorial tipado e demonstravelmente completo: 0/30** nessas APIs. Não se deve chamar aggregate de catálogo editorial.
- **Concordância numérica, não semântica:** B e MU têm número bruto igual em 13/30; B e K coincidem em 14/20 registros K com valor. Essa igualdade não indica confirmação independente nem igualdade de definição. Nas oito páginas VIZ conferidas, B tem número bruto igual ao último rótulo em 6; MU em 7; MD máximo inteiro em 6; K só informou valor em 3 e igualou em 1. Essa “igualdade” **não mede acurácia da contagem** porque compara campos semanticamente diferentes. Nenhum denominador estatístico de “capítulos principais corretos” pôde ser calculado.
- **Atualização em andamento:** One-Punch Man `237`, Dandadan `246` e One Piece `1193` em B/MU coincidem numericamente com VIZ no dia; Kitsu não fornece número nas três. Spy x Family já tem rótulo VIZ `140.1`, enquanto os agregados numéricos mostram `140`. Blue Lock varia B `342`, MU `272`, MD `362`; Kingdom B `877`, MU `832`, MD `885`, sem referência editorial auditada nesta pesquisa. Não se atribuiu causa ou “vencedor” a essas divergências.
- **Listas especiais/irregulares:** aggregate MangaDex traz `0` e `0.5` em Hunter × Hunter; `0.01` etc. em Berserk; muitos rótulos decimais em One-Punch Man (155 rótulos únicos decimais), Spy x Family e outros; `none` em Dandadan e nos one-shots. Rótulo `none` não é um capítulo canônico chamado “none”. Parte `.1` não é automaticamente EXTRA: pode ser divisão de capítulo principal, versão ou bônus. Sem título/data/conteúdo editorial, classificação permanece incerta.
- **Disponibilidade ≠ total:** *Death Note* aggregate MangaDex tem 16 rótulos únicos apesar de VIZ mostrar último 108; *Attack on Titan* apenas 2 nesta coleta; *My Hero Academia* 6. Contagem do aggregate é de inventário disponível/representado no serviço, sob condições de idioma e upload, não denominador de progresso. Nem mesmo a contagem “capítulos legíveis agora” foi validada em todos os idiomas/territórios, pois não se abriu cada item nem se usou conta.

### Dependência MangaBaka/Kitsu/MangaUpdates

[MangaBaka declara explicitamente](https://mangabaka.org/data/api) importar Kitsu, MangaUpdates e outros provedores; atualiza sua base semanalmente e solicita correção na fonte original. Todos os 30 registros escolhidos expuseram `source.kitsu.id` e `source.manga_updates.id` correspondentes aos IDs testados diretamente. O campo `source` observado traz IDs/ratings; não revela inequivocamente, **por campo**, se `total_chapters` veio de Kitsu, MangaUpdates, AniList, MAL ou curadoria. Portanto coincidência B–K ou B–MU foi registrada, mas **não computada como voto independente**. O B pode ser útil como índice de ligações e sugestão, nunca como prova autônoma do mesmo número que agrega.

### Divergências não resolvidas

1. *One-Punch Man*: VIZ/B/MU `237`, MD rótulo inteiro `311`, com 445 rótulos únicos e 155 decimais. Pode envolver redesenho/numeração de uploads; não foi demonstrada uma regra de conversão. Exige reconciliação por edição e capítulo editorial.
2. *Berserk*: B `401`, MU/MD máximo `386`; além de rótulos pré-numeração `0.01+`. Não foi obtida referência editorial auditada para determinar semântica.
3. *Vagabond* MU `2` contra K/B/MD `327`; *Bleach* MU `250` contra K/B `706` e MD `686`; não se deve tomar `latest_chapter` como máximo editorial.
4. *Fullmetal Alchemist* K `116`, B/MU `150`, MD último rótulo `108`/125 rótulos; *Oshi no Ko* K `168`, B `171`, MU/MD `166`; *Vinland Saga* K/MU/MD `220`, B `224`; *Solanin* B `30`, K/MU/MD `29`. A definição de especiais/edição não foi comprovada.
5. *Attack on Titan* K/B `141`, MU `139`, MD `127` e apenas dois rótulos; a página Kodansha consultada é por volume, não resolve esse total. *Pluto* K/B/MU `65` e MD `7`: disponibilidade desigual, não prova de erro editorial.
6. *Chainsaw Man* K `97` contra B/MU/MD `232`, possivelmente segmentação por partes, mas **não confirmada**. *Jujutsu Kaisen* B/K `272` contra referência VIZ/MU/MD `271`; edição/extras exigem investigação. *Blue Lock* e *Kingdom* têm divergências grandes não verificadas editorialmente.
7. Termos comerciais/redistribuição do Kitsu e MangaUpdates, permissões das editoras, política corrente de MangaDex e atribuição upstream para uso em Tsuzuki ainda requerem confirmação formal. Acessibilidade HTTP não resolve o direito de usar dados no produto.

## 5. Estratégias conceituais (sem implementação)

**A. Conservadora, recomendada para decisão inicial.** Kitsu continua descoberta e identidade sugerida. Persistir apenas identidade Tsuzuki e vínculos de provedores validados. Separar na apresentação “último capítulo editorial conhecido” (com edição, data e fonte) de “capítulos disponíveis nas fontes Mihon”. Quando só houver B/MU ou apenas `chapterCount`, apresentar **estimativa/último conhecido**, não um total canônico exato. Sem inventário editorial autorizado/validado, denominador de progresso é “indefinido”; ainda é possível mostrar a lista real da fonte Mihon e lacunas *nela*, rotuladas como tal.

**B. Catálogo editorial por licenciamento/parceria.** Negociar acesso a feed de editoras ou provedores que autorize redistribuição de número/lista, atualização e atribuição. Mapear série → edição → capítulos (rótulo, ordem, tipo, data, revisão/renumeração) com proveniência por item. Só então calcular quantidade de principais e ausentes; não confundir com disponibilidade de leitura. Maior confiabilidade, custo e cobertura limitada.

**C. Reconciliação híbrida com incerteza.** Usar Kitsu/B/MU para candidatos e IDs, inventários de leitura para variantes, e referências editoriais permitidas para confirmar subconjuntos. Dedupe condicionado a edição/tipo/base/parte; manter capítulo 0, 0.5, EXTRA e SPECIAL explicitamente. Rótulo refeito/renumerado não muda automaticamente a identidade canônica; preservar alias de edição e proveniência. Se dois campos dependem da mesma fonte upstream, contar como **uma** evidência. Quando fontes independentes divergem ou inventário é incompleto, não exibir contagem exata e não gerar “capítulos ausentes” globais a partir do máximo numérico. Essa alternativa pode coexistir com A, mas exige política editorial e licença antes de produto.

**Próxima decisão necessária:** qual definição de “catálogo confiável” será contratualizada para o MVP: (i) lista dos capítulos disponíveis numa fonte escolhida, (ii) último capítulo publicado por uma edição, ou (iii) estrutura editorial completa com extras e ausentes? As quatro medidas são diferentes. A pesquisa demonstrou (i) parcialmente nas fontes e (ii) para oito páginas VIZ; **não** demonstrou (iii) para 30 obras. Não transformar esta proposta em implementação antes dessa decisão e da revisão de termos.

## 6. Auditoria das consultas e limites da evidência

**Retidas no CSV:** 30 buscas Kitsu, 30 buscas MangaBaka, 30 buscas MangaUpdates, 30 buscas MangaDex; 30 detalhes MangaBaka, 30 detalhes MangaUpdates e 30 aggregates MangaDex. Duas consultas Kitsu por ID resolveram os one-shots; sete detalhes MangaBaka e seis detalhes MangaUpdates foram consultas direcionadas de resolução dentro das 30 fichas finais. Houve ainda duas consultas MangaDex por ID/aggregate direcionadas para Fullmetal Alchemist e My Hero Academia. IDs cruzados e páginas oficiais foram conferidos pontualmente. URLs por linha permitem reproduzir GETs; o corpo do POST MangaUpdates é `{"search":"<obra>","perpage":25}`. A pesquisa preparatória incluiu os erros `406` Kitsu e `405` MangaUpdates acima e consultas de inspeção de schema; esses **não** são respostas de obra no CSV. A quantidade de HTTP preparatório não foi preservada em log e não deve ser inventada como contagem exata.

**Efetivamente verificadas:** 30/30 obras com fichas identificadas nas quatro APIs; 8/30 com referência VIZ de último rótulo; 0/30 com inventário editorial completo e independente validado. Os números são uma amostra intencional, não medida de população. Não foi realizado monitoramento longitudinal nem revisão jurídica. Respostas oficiais de VIZ/MANGA Plus não foram usadas como API automatizada, em respeito aos termos. Esta investigação **conclui a bateria de 30 consultas**, mas **não estabelece que algum serviço sozinho satisfaça a promessa de catálogo confiável**.
