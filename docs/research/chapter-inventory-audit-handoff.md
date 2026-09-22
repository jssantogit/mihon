# Handoff — auditoria do inventário OPM

**Status:** auditoria estática e consultas públicas concluídas; teste real de extensão/Android pendente. **Branch:** `tsuzuki/audit-chapter-inventory` sobre `tsuzuki/bootstrap`. Veja [relatório](chapter-inventory-audit.md) e [CSV](chapter-inventory-audit-results.csv).

## Resposta direta

O Tsuzuki mostrou a lista começando em #138 porque a tela renderiza as chapters canônicas observadas/mapeadas disponíveis no estado local; quando o conjunto conhecido começa em 138, a UI também começa ali. Isso é coerente com o smoke anterior e com fixture unitária sintética `[138,139]`. O gateway não filtra em 138, e UI não impõe esse limite.

**A causa raiz da falta de #1–137 não foi determinada.** A primeira fronteira ainda não verificada é A→B: página/lista pública em edição/idioma específico versus `SChapter` bruto do Add-on Mihon instalado. Não se pode dizer se a extensão retorna apenas 138+ ou se capítulos se perdem em B→C sem o snapshot bruto do aparelho. Não adicionar fallback ainda.

## Evidência principal e limites

- `adb` não instalado (exit 127); nenhum aparelho, Add-on, runtime, página de leitura ou APK foi usado nesta sessão. Isto não prova indisponibilidade.
- MangaDex API: filtro EN=0, PT-BR=169, all-languages total=1.918 rows. PT-BR tem #1/#2, mas não labels exatos #137/#138/#139/#233/#234; 84 gaps inteiros entre 1–219. Metadata não prova leitura.
- MangaBall texto público retornou `0 chapters`, `0 translations`, com note de takedown; a tela dinâmica/sessão do usuário não foi reproduzida.
- MangaFire page fornecida não retornou conteúdo textual utilizável. Add-on e versão não acessíveis.
- Static gateway encaminha rows do `getMangaUpdate` sem pagination/filter. Reconcile não remove ausência. UI filtra rows sem confirmação/mapping/progresso/download e ordena o restante. `RefreshChapterEvidence` pode esconder failures como vazio; estado local carrega antes do refresh.
- Report público de bugs MangaFire em outros titles é fator de risco, não prova da falha específica.

## Próximo gate

Com Android/Add-ons acessíveis, registrar versão/IDs de fonte/binding e seguir cada label #1/#2/#137–139/#233–234 por A site → B raw extension → C snapshots/evidence/canonical DB → D UI. Identificar a primeira fronteira desigual e só então propor patch e teste de regressão. Testar abertura real somente se a fonte listar a chapter e sem armazenar conteúdo/cookies/tokens. Não mudar idioma/edição automaticamente.

## Consultas e artefatos

MangaDex API: 1 title detail + 1 total sem filtro + 1 count EN + 1 count PT-BR + 2 pages PT-BR (6 GETs); todos HTTP 200, sem endpoint de reader. Abertura do site MangaDex no navegador de pesquisa falhou sem retry. MangaBall página textual acessível; MangaFire URL sem conteúdo textual. VIZ #138/#234 consultadas para labels, páginas gated `Join to read`, sem acesso a imagens.

Arquivos: `chapter-inventory-audit.md`, `chapter-inventory-audit-results.csv`, `chapter-inventory-audit-handoff.md`. Nenhuma mudança de produção, build, APK, PR ou merge.
