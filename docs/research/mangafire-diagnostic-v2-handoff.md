# Tsuzuki MangaFire — diagnóstico de binding e seletor v2

## Objetivo

O relatório v1 em One-Punch Man informa `PROBE / EXTENSION_ERROR / BINDING_UNAVAILABLE` quando
o vínculo falha, mas não mostra em qual fonte interna, busca, decisão ou operação ocorreu o erro.
Este incremento **não presume que a correção de fontes internas desativadas resolve o incidente
no dispositivo**. A v2 é observacional, opt-in, local em memória e temporária (remover antes
do lançamento, após concluir o diagnóstico).

## Contrato de diagnóstico

O mesmo botão Start / Refresh / Copy / Stop / Clear na tela da obra captura:

| Etapa | O que fica observável |
| --- | --- |
| `ADDON_DISCOVERY` | Ausência do pacote ou do add-on habilitado. |
| `SOURCE_ELIGIBILITY` | Fontes Mihon internas habilitadas e desabilitadas, IDs, idiomas e contadores; fontes desabilitadas não são consultadas. |
| `BINDING_SEARCH` | Por fonte e tentativa numérica: resultados encontrados, busca vazia, rede, timeout, CAPTCHA explícito ou erro da extensão. |
| `BINDING_MATCH` | Reutilização de vínculo, correspondência de alta confiança, abaixo do limite ou candidatos ambíguos. |
| `BINDING_MATERIALIZATION` | Vínculo criado, falha na materialização ou falha na persistência, sem divulgar URLs. |
| `CHAPTER_INVENTORY` / `CHAPTER_PROBE` | Capítulos por idioma quando conhecidos; falhas por binding individual e falhas agregadas. |
| `CONTENT_PROVIDER` | Ausência de binding, variante de capítulo não encontrada ou erro na obtenção do conteúdo. |
| `CONTENT_SELECTOR` | Add-on desativado/não registrado, opções recebidas/aceitas/filtradas, resultado vazio, cache ou falha do provedor. |

O relatório traz linhas `SUMMARY` **antes** dos eventos detalhados, inclusive o primeiro estágio
explicitamente marcado pelo código como bloqueador por add-on e a contagem de fontes afetadas.
Esse resumo não é inferido de um erro genérico e não é expulso por eventos bem-sucedidos de outros
add-ons. Cada linha detalhada carrega `correlationId` opaco da captura e ordinal do evento, para
reconstituir a ordem do fluxo Start → Refresh → seletor; a captura não separa múltiplos Refresh
concorrentes em IDs de operação individuais. Limites explícitos: 200 eventos detalhados, 64
chaves de resumo de falhas, 32 primeiros bloqueadores e 32 KiB de texto. Versão do cabeçalho:
`Tsuzuki chapter inventory diagnostic v2`.

A classificação `CAPTCHA_REQUIRED` exige sinal explícito de desafio do provedor
(`captcha_required` ou `shape-selecting captcha` no erro original lido somente em memória).
Um `IOException` genérico continua sendo `NETWORK_ERROR`; não deduzimos CAPTCHA a partir
de um HTTP 403 inespecífico. O relatório exporta somente enums fechados, IDs técnicos
validados, idioma, contagens, tempos, índice da tentativa e rótulos normalizados de capítulo.
**Nunca** exporta busca textual, título, URL de fonte/capítulo, mensagem bruta de exceção,
cookies, tokens ou payload de binding. Quando uma fronteira app recebe o `HttpException` Mihon,
somente seu código numérico HTTP válido (100–599) pode ser exportado. Busca de título via
`ReadingSourceGateway` não expõe esse código ao domínio e continuará registrada por categoria
sanitizada sem status.

`NO_BINDING` e `BINDING_NOT_MATERIALIZED` substituem o uso operacional genérico de
`BINDING_UNAVAILABLE`. Fontes internas desativadas são observáveis somente durante uma captura;
o modelo operacional continua fornecendo ao resolvedor exclusivamente os IDs habilitados. A
instrumentação não ativa, registra para busca, nem muda preferências de uma fonte desativada.

## Exemplo sanitizado

```text
Tsuzuki chapter inventory diagnostic v2
sessionId=opaque-capture-id
titleHash=4fa1c2d9e073abcd
SUMMARY|addonId=eu.kanade.tachiyomi.extension.all.mangafire|firstBlockingStage=BINDING_SEARCH|outcome=NETWORK_ERROR|affectedSources=3|reason=NETWORK_FAILURE
BINDING_SEARCH|outcome=NETWORK_ERROR|correlationId=opaque-capture-id|event=17|addonId=eu.kanade.tachiyomi.extension.all.mangafire|sourceId=1234|language=en|attempt=1|reasons=NETWORK_FAILURE:1
SELECTOR|outcome=NO_BINDING|correlationId=opaque-capture-id|event=854|addonId=eu.kanade.tachiyomi.extension.all.mangafire|received=0|accepted=0|reasons=PROVIDER_NOT_REGISTERED:1
```

O exemplo é apenas ilustrativo; não representa uma nova execução no aparelho.

## Smoke test Android (não executado nesta sessão)

1. Instalar o APK diagnóstico aprovado **por cima do anterior**, sem limpar dados nem preferências.
2. Verificar estado e idiomas do MangaFire em Configurações → Add-ons.
3. Na obra One-Punch Man, Start diagnostic → Refresh e aguardar atualização.
4. **Sem parar a captura**, abrir o capítulo 1 e entrar no seletor de fontes (não selecionar
   automaticamente nem alterar fonte preferida). Se possível, repetir com capítulo que exista
   no MangaDex e eventualmente no MangaFire.
5. Copiar o relatório; verificar o `SUMMARY|addonId=...mangafire` inicial e seguir `correlationId`
   e `event` em ordem, depois conferir os eventos
   `ADDON_DISCOVERY`, `BINDING_SEARCH`, `BINDING_MATCH`, `BINDING_MATERIALIZATION`,
   `CHAPTER_PROBE`, `CONTENT_PROVIDER`, `CONTENT_SELECTOR`.
6. Se MangaFire aparecer, selecionar uma variante explicitamente e validar páginas carregadas.
   Se exigir CAPTCHA, usar somente a interação oferecida pela extensão.
7. Stop → Clear ao terminar; compartilhar apenas o relatório sanitizado.

## Critério de conclusão e limitações

Os testes JVM cobrem categorias de erro, buscas por tentativa, vinculação/materialização,
fontes internas mistas sem consulta às desativadas, resumos mesmo após evicção por 836 sucessos
de inventário, opções filtradas no seletor, ausência de binding e add-on sem registro. A evidência física do aparelho é imprescindível
para atribuir a falha original do MangaFire a uma causa específica e para provar a leitura.
O diagnóstico não modifica mapeamento canônico, progresso/histórico, preferência de fonte,
mecanismos de CAPTCHA ou comportamento de seleção.
