# Catálogo unificado de capítulos — estudo da rodada 3

**Observação:** 22 set. 2026 UTC. **Branch:** `tsuzuki/research-chapter-availability`, baseada em `tsuzuki/research-chapter-metadata`. **Escopo:** pesquisa somente; nenhuma implementação ou edição de contrato. Este documento distingue metadados de disponibilidade efetiva e não é parecer jurídico.

## Resumo executivo

- A arquitetura Tsuzuki já tem peças para preservar identidade canônica, coletar inventários de Add-ons instalados e resolver uma variante de leitura capítulo por capítulo, mantendo o progresso canônico. Isso demonstra capacidade estrutural, não disponibilidade de conteúdo neste ambiente.
- A rodada anterior observou em uma captura do app a lista de One-Punch Man começando em #138. A rodada atual não tinha `adb`, APK/dispositivo nem Add-ons instalados acessíveis; portanto esse ponto é uma evidência histórica do app, não uma leitura nova. Não foi possível verificar se MangaFire ainda começa em #138, se MangaBall ou outro Add-on possui #1–137, nem se qualquer capítulo abre no leitor.
- A API pública do MangaDex retornou 1.153 registros de upload para os quatro títulos e idiomas consultados. Registros e páginas são metadados de uploads comunitários, não um inventário editorial. A consulta não carregou páginas de leitura. Logo, nenhum capítulo MangaDex foi declarado legível nesta pesquisa.
- VIZ oferece catálogos editoriais oficiais em inglês para as quatro obras: checkpoints observados #1 e mais recente (One-Punch Man #237; Dandadan #246; Hunter × Hunter #420; Death Note #108), além de One-Punch Man #137/#138, Dandadan #231.1 e HxH #411–414. Isso permite verificar rótulos daquela edição, mas não licencia a ingestão automatizada/redistribuição nem prova que o leitor Mihon do Tsuzuki possa abrir os capítulos.
- **Resultado de produto:** catálogo conceitual unificado é compatível com os contratos existentes, mas não há evidência suficiente para declarar nenhuma das quatro obras contínua e realmente legível via Tsuzuki. A continuidade editorial pode ser apresentada com fonte e edição; disponibilidade para leitura só pode ser afirmada após inventário vivo e teste de abertura autorizado, por capítulo e idioma.

## Preparação e arquitetura examinada

Foram lidos os relatórios das rodadas 1 e 2 em `docs/research/`, `docs/TSUZUKI-SPEC.md` (especialmente §§ identidade, capítulos canônicos, variants, gaps e fallback), o plano ativo `docs/superpowers/plans/2026-09-22-tsuzuki-post-apk-smoke-round-2.md`, o relatório de validação do smoke e os modelos/interactors de identidade, capítulo, leitor, runtime de Add-ons e Mihon.

A spec estabelece: identidade Tsuzuki (`CanonicalTitle`) não é identidade do provedor; `CanonicalChapter` não é `Source Chapter`; número de exibição e ordenação são conceitos diferentes; labels como `12.5`, `12a`, `Extra 3`, `Prologue` não podem ser reduzidos a `float`; progresso pertence ao capítulo canônico; lacunas e incerteza não podem ser preenchidas com capítulos inventados. A primeira troca para fallback deve pedir confirmação, e fallback persistente exige escolha explícita do usuário.

Capacidades atuais relevantes, constatadas por inspeção de código:

1. `MihonChapterInventoryGateway` chama a fronteira de fonte Mihon para obter uma lista de `SChapter`; preserva label/numero bruto, URL/ID de fonte, idioma, grupo/data e ordem observada. A operação é leitura de inventário, não de páginas.
2. `MihonChapterProbeProvider` transforma inventários em evidência provisória de Add-on. Essa evidência não tem autoridade editorial.
3. `ResolveChapterContent` pode resolver um `canonicalTitleId + canonicalChapterId` em opções de Add-ons/providers registrados. Consulta primeiro o provider preferido; quando não há opção, pode consultar alternativas com concorrência limitada. Sem fallback automático, o resultado pode pedir seleção; com ele, pode selecionar uma opção ranqueada.
4. O mapeamento título-provedor já existe e pode preservar `sourceTitleKey`, fonte e idioma. As variantes guardam identificadores específicos de fonte. `CanonicalChapter` e `ChapterVariant` permitem múltiplas leituras para uma identidade canônica e progresso canônico independente da variante.
5. `MihonInventorySnapshotCache` deduplica buscas simultâneas e mantém snapshots em memória por TTL (2 min, limite LRU 128); o resolver de conteúdo também tem cache. Probe com `refresh` pode ignorar inventário em cache. Fan-out de provedores tem limite de concorrência 4. `TsuzukiPerf` registra duração de rede/total, mas não houve medição de runtime nesta rodada.

Lacunas em relação à experiência pedida:

- Sem Add-on realmente instalado/dispositivo, não há evidência de inventário ou páginas legíveis de MangaFire/MangaBall nem de alternativa por capítulo.
- O capítulo editorial e sua disponibilidade operacional estão separados, como devem estar; falta uma base editorial validada/licenciada para determinar que #1–N existem numa edição, bem como uma correspondência revisada entre essa lista e IDs de upload.
- A edição não é uma identidade explícita em primeira classe no fluxo de leitura; idioma e Add-on são conhecidos, mas a estrutura de edições e renumerações pode ficar implícita no binding/payload. Igualdade de label não é suficiente para merge.
- Ordenação de lista por fonte não prova ordem editorial. Posição de resposta da API do MangaDex abaixo foi requisitada ascendente pelo label; não é a ordem original da UI e valores fracionários requerem semântica editorial.
- A opção de fallback pode escolher outra língua dependendo das preferências e configuração. Uma troca de idioma/edição silenciosa não atende ao requisito: seleção precisa preservar locale/edition ou obter autorização explícita.
- Leitura e progresso precisam continuar ligados a `CanonicalChapter`; fallback muda a variante, não a identidade nem o checkpoint do usuário. Uma variante de edição não equivalente deve criar fila/identidade separada, não ser forçada no mesmo capítulo.

## Método, consultas e limitações

### MangaDex

Foram usados os endpoints públicos documentados: `GET https://api.mangadex.org/manga/{uuid}` nos relatórios anteriores e, nesta rodada, paginação de `GET https://api.mangadex.org/chapter?manga={uuid}&translatedLanguage[]={en|pt-br}&limit=100&offset=...&order[chapter]=asc&includes[]=scanlation_group`. IDs de obra abaixo são aqueles adotados na rodada 1. Respostas mantiveram `id`, `chapter` literal, `title`, `volume`, idioma, grupos, `pages`, flags/URL externos, timestamps `publishAt`/`readableAt` e link do registro. A planilha contém cada registro retornado e os endpoints por página. `pages` é somente um campo de metadados; imagens/reader não foram requisitados.

- 31 GETs HTTP no ensaio anotado: 30 respostas HTTP 200 e uma consulta exploratória HTTP 400 por usar parâmetro `manga[]` incompatível com o endpoint. A resposta 400 foi descartada; não é evidência de cobertura. O total inclui oito consultas de contagem por obra/idioma, dezoito páginas na coleta paginada completa e cinco sondagens de parâmetros/endpoints; não houve retry para substituir o erro. Todas as oito consultas e todas as páginas foram HTTP 200.
- Consultas por idioma foram separadas. Um zero na filtragem indica ausência de registros retornados naquele filtro/instantâneo, não inexistência editorial ou impossibilidade de leitura.
- Não se clicou em URLs de capítulos nem foram baixadas imagens. Datas de `readableAt` no registro não constituem teste de abertura atual.
- O parâmetro de ordenação define apenas o retorno API pedido. A interface do MangaDex pode ordenar/filtrar por grupo, upload/data/volume; isso não foi validado como ordem da fonte.

### VIZ, MangaFire, MangaBall e dispositivo

A consulta ao catálogo e páginas do leitor VIZ foi manual/read-only, via páginas oficiais (links nas seções de resultados). Os rótulos foram conferidos sem coleta automatizada do sítio; o leitor completo, território e entitlement/assinatura variam. Os termos VIZ atuais proíbem scraping/reprodução sem consentimento prévio escrito e licenciam o uso das propriedades VIZ para uso pessoal não comercial; portanto os catálogos não autorizam integração, cache redistribuível ou leitura por um app terceiro.

Não acessamos endpoints não documentados, APIs privadas, mecanismos de proteção nem páginas de MangaFire. O site de MangaFire não publicou API oficial/documentação ou licença de dados identificável nesta investigação; o site tem página de DMCA, que não estabelece uma licença de reutilização. A issue pública de extensão é somente relato de bug sobre discrepância da lista carregada pelo Mihon; não prova cobertura atual. MangaBall não foi consultado para capítulos: suas regras públicas dizem que ferramentas automatizadas são desencorajadas sem aprovação e limitam uploads de scans oficiais. Não foi inferida autorização de uso.

`adb` não está instalado (`adb: command not found`), e os arquivos de produção não incluem Add-ons empacotados de MangaFire/MangaBall. Não havia aparelho/instância Android para consultar. Esse é o motivo documentado para ausência de teste de abertura. O relatório do smoke anterior registra visualmente OPM começando em #138 e ordena investigação; não é uma consulta repetida nem uma cobertura recém-observada nesta rodada.

## Resultados por fonte

**Convenções:** “registro” = metadado de upload; “label ausente” = ausente da resposta desse idioma/MangaDex, não ausência editorial. “Checkpoint VIZ” = rótulo observado no catálogo editorial da edição inglesa oficial. “Legível” = páginas abertas no leitor do Tsuzuki; não confirmado para nenhum upload.

| Obra | MangaDex EN: linhas / labels únicos | MangaDex PT-BR: linhas / labels únicos | Labels observados relevantes | Catálogo editorial inglês observado | Disponibilidade real via Tsuzuki |
|---|---:|---:|---|---|---|
| One-Punch Man | 0 / 0 | 169 / 163 | PT-BR integer labels 1–219 com 84 ausências internas; primeiras: 94, 125–126, 137–203 (inclusive); 28 labels fracionários; 6 labels duplicados em upload rows. | VIZ lista #1, #137, #138 e #237; há frações de edição como #132.1/#132.2. | Não testada. O MangaDex PT-BR não pode preencher uma leitura EN sem troca explícita de língua/edição. |
| Dandadan | 10 / 10 | 123 / 117 labels não vazios (118 incluindo um label vazio) | EN: 1–4 e 235, 242–246; todos com link externo; 236 labels inteiros ausentes no snapshot. PT-BR: rótulos 1–246, 140 inteiros ausentes (começa em 86), 11 fracionários, 5 duplicados e 1 label vazio; 9 registros apontam externamente. | VIZ #1–#246 observado, com #231 e #231.1 distintos. | Não testada; nenhum intervalo contínuo comprovado para leitura. |
| Hunter × Hunter | 426 / 418 | 420 / 413 | EN: #1–#420 exceto 411–414, 2 fracionários (340.5/.6), 8 labels duplicados; 11 externos. PT-BR: #0, #0.5 e #1–#410 exceto #53; 340.5/.6; 6 labels duplicados com 7 linhas extras. | VIZ #1, #411–414 e #420. Não foi localizada confirmação editorial VIZ de #0 ou #0.5. | Não testada; número #0/.5 não deve ser promovido a capítulo canônico com base só em upload. |
| Death Note | 5 / 5 | 0 / 0 | EN: #1–4 e #108; 103 labels ausentes entre 5–107; todos os cinco apontam externamente. | VIZ cataloga a série principal #1–#108 e a página #108 aponta #107 como anterior; VIZ também lista One-shot/Short Stories em obras separadas. | Não testada; a amostra MangaDex é altamente incompleta e o catálogo oficial não é uma integração autorizada Mihon. |

Contagem de “únicos” na tabela considera labels não vazios. Os detalhes linha a linha estão em [`unified-chapter-coverage.csv`](unified-chapter-coverage.csv), inclusive IDs exatos de MangaDex, grupos, timestamps e páginas por label. Duplicatas são uploads distintos com o mesmo label (não sinônimos/editoriais já reconciliados).

### One-Punch Man — #1 a #137 e a lacuna

- A VIZ confirma a edição digital inglesa com os capítulos #137 e #138 adjacentes; seus checkpoints incluem #1 e #237 (rótulo de publicação datado 2026-09-16). Assim, a pergunta editorial “existe uma sequência anterior ao 138?” tem resposta **sim dentro da edição VIZ em inglês**: pelo menos os labels #1–137 são parte do seu catálogo exibido. A listagem VIZ também contém labels adicionais como #124.1 e #132.1/#132.2, logo o número inteiro não descreve todos os itens editoriais.
- Isso **não preenche MangaFire no Tsuzuki**: não há conexão/licença/API VIZ ou runtime observado que entregue essas páginas pelo Mihon. O relato anterior MangaFire começando #138 sugere que, naquela captura, o primeiro intervalo descoberto era #1–137, mas não se verificou que outra fonte Mihon forneça o prefixo. Portanto a **primeira lacuna operacional não resolvida segue sendo #1** (e, de forma agregada, #1–137 na fonte/locale do screenshot), não um número posterior inferido de maiores labels.
- MangaDex tem PT-BR upload label #1, mas sua resposta PT-BR não contém #94, #125/#126 e #137–203 entre vários outros; não existe EN record nessa consulta. A sequência PT-BR combina por identidade de obra provável, mas sua identidade de edição e equivalência página-a-página não foi confirmada. Não é substituto silencioso de MangaFire inglês.
- As discrepâncias oficiais 237 VIZ vs 311 MangaDex anterior não são contradição editorial resolvida: #237 é rótulo VIZ inglês em 2026; 311 é máximo de label upload MangaDex do snapshot da rodada anterior, não o capítulo editorial da mesma edição. Nesta rodada MangaDex PT-BR chega a #219, mas possui gaps. A diferença é edição/tempo/fonte de upload plausível, causa completa não confirmada. A página japonesa Tonari #257 investigada anteriormente é publicação/edição distinta.
- **Conclusão:** lacunas MangaFire efetivamente preenchidas nesta rodada: **zero**. Evidência editorial oficial independente confirma que VIZ lista #1–137 e pode ser consultado como rota licenciada fora da integração atual. Primeiro capítulo operacional pelo Tsuzuki: desconhecido.

### Dandadan — idioma e rótulos fracionários

VIZ cataloga inglês #1 e #246 (2026-09-14), com #231 e #231.1 como entradas separadas; a regra de ordenação/semântica de #231.1 precisa seguir a própria edição, não decimal genérico. MangaDex EN lista só 10 registros (1–4, 235, 242–246), e todos são links externos; PT-BR tem os rótulos acima com ausências grandes. Assim existe evidência de língua EN editorial e upload EN parcial e de uploads PT-BR parciais, não um inventário de leitura sem cortes em ambos. Não foi verificada a existência de fonte brasileira licenciada/API para essa série nesta rodada. Não mesclar 231.1 nem rótulos `.5` de upload PT-BR com capítulos oficiais sem identificação editorial.

### Hunter × Hunter — capítulos 0 e 0.5

O catálogo oficial VIZ inicia no #1, lista o intervalo recente #411–#420 e identifica o #420 como publicação datada 2026-09-06. O API MangaDex PT-BR contém labels #0 e #0.5; isso prova apenas que uploads foram rotulados assim. As fontes primárias consultadas não confirmaram que sejam capítulos regulares da serialização oficial. São candidatos a prólogo, extra, conteúdo promocional ou convenção de grupo, mas classificação é **não resolvida**, não fato. Para exibir o início verificável da edição VIZ, #1 vem antes de qualquer label de upload não mapeado; para apresentar 0/.5 como extras, é preciso referência editorial específica. MangaDex EN #411–414 ausentes no snapshot; a VIZ os cataloga, demonstrando que upload coverage não é editorial completeness.

### Death Note — série principal 108

A página oficial da série VIZ enumera capítulo #1 e série até #107 e #108; a página específica #108 aponta #107 como anterior. Isso dá base para a definição estrita “sequência de capítulos principais no catálogo VIZ, labels 1–108”, não para incluir extras/one-shots: VIZ expõe `Death Note: Special One-Shot`/`Death Note Short Stories` como títulos distintos. O MangaDex retornou apenas #1–4 e #108, não sequência completa, e todos com link externo. Não foi provado que capítulos 5–107 podem ser abertos dentro do Tsuzuki. Assim “108 capítulos conhecidos” não equivale a “108 capítulos disponíveis para leitura”.

## Catálogo experimental conceitual

Uma lista unificada pode ser construída sem fundir identidades de fonte: cada entrada editorial elegível deve ter `(canonicalTitleId, editionId, canonicalChapterId)`, label literal, sort key editorial validada, type, parte/volume/data quando conhecida, origem/URL de evidência, revisão, confiança e status (`confirmed`, `disputed`, `unknown`). Disponibilidade vive em arestas separadas: `(canonicalChapterId, sourceMappingId, sourceChapterId, language, edition, group/version, observedAt, status)`. As linhas de MangaDex desta rodada só preencheriam as arestas candidatas `observed-upload`, nunca estado “confirmed-readable”.

Correspondência automática mínima deve exigir a mesma obra canônica verificada, edição compatível, língua explícita e sinal editorial adicional (ID externo mapeado/volume/parte/release/date ou revisão humana). Label igual é insuficiente. Uma edição refeita/reordenada permanece separada até prova de compatibilidade. Labels como `0.5`, `231.1`, `340.5` ficam como strings e precisam de classificação manual/fonte primária; ordenar como número float perderia tipos e colisões. Duplicatas do mesmo label dentro do grupo não são duplicatas canônicas sem vínculo verificado.

Com esse modelo, a tela pode apresentar a ordem editorial uma vez e chips/estado de disponibilidade por idioma. Ao ler #n, usar a fonte preferida se houver variante compatível; caso falte, oferecer “Ler uma vez nesta fonte alternativa” e mostrar origem, idioma e edição. Trocar definitivamente exige escolha do usuário. Se só houver outra língua/edição, não trocar automaticamente: informar e pedir aceitação explícita. Ao iniciar a leitura pela alternativa compatível, registrar progresso no `canonicalChapterId`; voltar à preferência depois não deve criar um capítulo/progresso paralelo. Sem variant compatível, capítulo editorial confirmado fica “indisponível nas fontes atuais”; capítulo editorial não comprovado não é sintetizado.

## Licenças, legalidade e custo

- **MangaDex API:** API pública e gratuita condicionada à AUP: creditar MangaDex; se permitir leitura, creditar grupos de scanlation e respeitar pedidos de remoção; não executar anúncios nem serviços pagos no app/site que use a API. Isso é condição de acesso à API, não licença geral sobre imagens, traduções ou capítulos dos grupos/editoras. Aplicabilidade a Tsuzuki monetizado, cache offline, redistribuição de metadados e fontes de leitura exige revisão jurídica/consentimento. Não se devem servir ou baixar scans a partir desta pesquisa.
- **VIZ:** termos vigentes de 2026-02-01 proíbem scraping/automação e reprodução sem autorização escrita; uso dos sites é pessoal/não comercial, não transfere direitos sobre conteúdo. Catálogo é referência editorial manual nesta pesquisa, não fonte técnica de ingestão ou content provider autorizado para Tsuzuki.
- **MangaBall:** regras públicas desencorajam ferramentas automáticas sem aprovação e limitam scans oficiais (salvo hipóteses condicionadas como autorização editorial, prova, obra fora de catálogo). A permissão para consultar/reutilizar dados em Tsuzuki é não esclarecida; nenhuma consulta a capítulo feita.
- **MangaFire:** nenhuma documentação oficial de API/uso de dados ou autorização de redistribuição foi localizada nesta rodada. Isso não equivale a proibição confirmada nem a autorização. Sem resposta do operador, não fazer ingestão automatizada nem pressupor permissão comercial.
- **Custo/latência:** lista/capítulo na abertura multiplicando por 4+ fontes pode amplificar round trips e falhas. A arquitetura atual limita concorrência a 4 e possui cache/in-flight dedup, mas probe `refresh` pode gerar novo tráfego. Metadados de disponibilidade podem ser atualizados em background/TTL curto e apresentação inicial usar snapshot com timestamp; revalidar somente fonte selecionada/alternativas sob demanda. Não existe benchmark real nesta rodada, então não quantificar segundos nem propor polling agressivo. Política final precisa respeitar rate limits e condições de cada serviço, com timeouts, cancelamento, backoff e resultados `unknown/error` separados de vazio.

## Respostas aos critérios de sucesso

| Obra | Primeiro capítulo para ler? | Sequência até último conhecido? | Lacunas após combinação | Alternativas em >1 fonte? | Ordem editorial verificável? | Evidência de abertura real? | Impedimentos centrais |
|---|---|---|---|---|---|---|---|
| One-Punch Man | Editorialmente #1 na VIZ; no leitor Tsuzuki **não confirmado**. | Catálogo VIZ dá checkpoints #1–237 e adjacências, mas leitor integral pelo Tsuzuki não verificado. | Operacionais: #1–137 ainda sem fonte Mihon constatada; no upload MangaDex PT-BR, 84 labels inteiros faltam de 1–219, incluindo #94/#125–126/#137–203. | Não é possível contar opções legíveis; MangaDex tem duplicatas de uploads com labels iguais, VIZ é site separado. | Sim, para os rótulos observados da VIZ EN; não para equivalência de MangaDex ou edição japonesa. | Não em Tsuzuki. Página VIZ oficial existe mas autenticação/território e integração não testados. | falta integração/licença de inventário/editorial e teste Add-on; mudança EN/PTBR não silenciosa. |
| Dandadan | VIZ #1 é checkpoint editorial; Tsuzuki aberto **não confirmado**. | VIZ lista #1–246, mas cobertura de leitura Tsuzuki contínua não provada. | Não calculável para fontes reais; MangaDex EN faltam 236 integer labels, PT-BR 140 integer labels entre min e max. | Não confirmar duplicata legível; 5 labels PT-BR duplicados são upload records. | VIZ labels #231/#231.1; demais upload order apenas API ascendente. | Não em Tsuzuki. MangaDex EN 10 external links, PTBR 9 external; não abriram. | gaps uploads e classificação de fracionários, idioma, source/legal. |
| Hunter × Hunter | Editorialmente #1; Tsuzuki aberto **não confirmado**. | VIZ oferece #1–420 na edição consultada; catálogo de leitura completo via Tsuzuki não confirmado. | Não calcular como gaps editoriais; MangaDex EN faltam #411–414, PT-BR #53; .5 e #0 sem classificação. | Duplicatas como uploads no MD; nenhuma alternativa real testada capítulo por capítulo. | VIZ verifica labels #1 e #411–420; #0/.5 não verificados editorialmente. | Não em Tsuzuki. | #0/.5 sem proveniência editorial; ausência de acesso runtime; entitlement VIZ. |
| Death Note | VIZ cataloga #1; Tsuzuki aberto **não confirmado**. | VIZ principal #1–108; capítulo a capítulo não provado via Tsuzuki. | MangaDex não retorna 5–107 (103 labels); sem outra fonte de leitura testada. | Nenhuma alternativa utilizável foi verificada. | VIZ lista série principal e #108→#107 anterior; extras são catálogo separado. | Não em Tsuzuki; nenhum capítulo MangaDex foi aberto. | não confundir metadata count com acesso; completar evidência autorizada. |

**Leitura da matriz:** “lacuna após combinação” é quantificada para os instantâneos de upload observados, não como inexistência editorial nem como ausência universal em qualquer fonte. Número de alternativas *legíveis* é “desconhecido” para as quatro, não zero demonstrado.

## Perguntas para provedores e próximos passos de pesquisa

Não foi enviado contato. Perguntas prontas, sem interpretar silêncio como consentimento:

1. **MangaFire/MangaBall:** existe API pública documentada? Quais limites e condições específicas permitem indexar somente labels/IDs? O Tsuzuki pode persistir/cachar, exibir e consultar esses metadados, inclusive em app potencialmente monetizado? Pode abrir conteúdo por Add-on Mihon e sob quais termos territoriais? Existe identificador estável e indicação de edition, language, specials/revisions?
2. **VIZ/editoras:** existe feed/licença para catálogo estruturado de capítulos (ID, label, sort index, edition, tipo, volume, publicação/retificação) e permissão de exibição/cache no app? Existe deep link oficial compatível com lançamento em app de terceiro, e limites geográficos/subscription?
3. **MangaDex:** confirmar se a política permite uso somente de identificadores/labels em catálogo sem renderizar capítulos e num app free/ad-free; obrigações de cache, remoções de grupos e consequências de fontes de leitura externas com direitos não verificados.

Etapa experimental seguinte só deve coletar inventários por Mihon depois de disponibilizar Android/dispositivo e Add-ons permitidos, respeitando termos. Medir um source-first load e um fallback por título/idioma; testar leitura real dos checkpoints extremos e das lacunas, capturar IDs, URLs, grupo, idioma, ordem, erros e timestamps. Classificar cada mapping com edição; não declarar continuidade até inspeção de todos os labels necessários contra edição editorial de referência.

## Fontes primárias e referências

- Tsuzuki: [spec de produto](../TSUZUKI-SPEC.md), [plano de smoke anterior](../superpowers/plans/2026-09-22-tsuzuki-post-apk-smoke-round-2.md), [relatório de validação anterior](../superpowers/reports/2026-09-22-post-apk-smoke-round-2-validation.md), relatórios das rodadas [1](chapter-metadata-study.md) e [2](chapter-metadata-round2.md).
- MangaDex: [documentação da API](https://api.mangadex.org/docs/), [AUP e condições oficiais](https://gitlab.com/mangadex-pub/mangadex-api-docs/-/blob/main/index.md). IDs consultados e URLs por página estão na planilha.
- Regras públicas: [MangaBall Site Rule](https://mangaball.net/article/site-rule/), [MangaFire DMCA notice](https://www.mangafire.fr/fr/dmca) (não é licença nem API docs).
- VIZ: [One-Punch Man chapter catalog](https://www.viz.com/shonenjump/chapters/one-punch-man), [Dandadan chapter catalog](https://www.viz.com/shonenjump/chapters/dandadan), [Hunter × Hunter chapter catalog](https://www.viz.com/shonenjump/chapters/hunter-x-hunter), [Death Note chapter catalog](https://www.viz.com/shonenjump/chapters/death-note), [VIZ Terms of Use](https://www.viz.com/terms). As páginas variam por data, região e login; data desta observação consta acima.
- Extensão Mihon: issue pública sobre divergência do inventário MangaFire [#18760](https://github.com/keiyoushi/extensions-source/issues/18760); é relato de problema, não prova de cobertura geral.
