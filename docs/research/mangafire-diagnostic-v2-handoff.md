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
| `ADDON_DISCOVERY` | Ausência do pacote, todas as fontes desativadas, quantidade e IDs internos habilitados. |
| `BINDING_SEARCH` | Por fonte e tentativa numérica: resultados encontrados, busca vazia, rede, timeout, CAPTCHA explícito ou erro da extensão. |
| `BINDING_MATCH` | Reutilização de vínculo, correspondência de alta confiança, abaixo do limite ou candidatos ambíguos. |
| `BINDING_MATERIALIZATION` | Vínculo criado, falha na materialização ou falha na persistência, sem divulgar URLs. |
| `INVENTORY` / `PROBE` | Capítulos por idioma quando conhecidos; falhas por binding individual e falhas agregadas. |
| `CONTENT_PROVIDER` | Ausência de binding, variante de capítulo não encontrada ou erro na obtenção do conteúdo. |
| `SELECTOR` | Add-on desativado/não registrado, sucesso com quantidade de opções, resultado vazio, cache ou falha do provedor. |

O novo relatório traz linhas `SUMMARY` **antes** dos eventos detalhados e mantém contadores
de falhas por add-on, etapa, categoria e razão, ainda que a lista de eventos antigos seja
descartada pelo limite. Limites: 200 eventos detalhados, 64 chaves de resumo, 32 KiB de texto.
Versão do cabeçalho: `Tsuzuki chapter inventory diagnostic v2`.

A classificação `CAPTCHA_REQUIRED` exige sinal explícito de desafio do provedor
(`captcha_required` ou `shape-selecting captcha` no erro original lido somente em memória).
Um `IOException` genérico continua sendo `NETWORK_ERROR`; não deduzimos CAPTCHA a partir
de um HTTP 403 inespecífico. O relatório exporta somente enums fechados, IDs técnicos
validados, idioma, contagens, tempos, índice da tentativa e rótulos normalizados de capítulo.
**Nunca** exporta busca textual, título, URL de fonte/capítulo, mensagem bruta de exceção,
cookies, tokens ou payload de binding.

IDs de fontes internas desativadas individualmente não são exportados pelo contrato atual:
a camada de add-ons fornece ao domínio somente os IDs habilitados. O diagnóstico distingue
pacote inteiramente desabilitado e registra os IDs habilitados sem alterar o contrato público.
A UI de extensão mantém o controle/visualização de idiomas desativados.

## Smoke test (depois de CI e APK)

1. Instalar APK diagnóstico **por cima do anterior**, sem limpar dados nem preferências.
2. Verificar estado e idiomas do MangaFire em Configurações → Add-ons.
3. Na obra One-Punch Man, Start diagnostic → Refresh e aguardar atualização.
4. **Sem parar a captura**, abrir o capítulo 1 e entrar no seletor de fontes (não selecionar
   automaticamente nem alterar fonte preferida). Se possível, repetir com capítulo que exista
   no MangaDex e eventualmente no MangaFire.
5. Copiar o relatório e verificar primeiro `SUMMARY|addonId=...mangafire`, depois os eventos
   `ADDON_DISCOVERY`, `BINDING_SEARCH`, `BINDING_MATCH`, `BINDING_MATERIALIZATION`,
   `PROBE`, `CONTENT_PROVIDER`, `SELECTOR`.
6. Se MangaFire aparecer, selecionar uma variante explicitamente e validar páginas carregadas.
   Se exigir CAPTCHA, usar somente a interação oferecida pela extensão.
7. Stop → Clear ao terminar; compartilhar apenas o relatório sanitizado.

## Critério de conclusão e limitações

Os testes JVM validam categorias de erro, buscas por tentativa, vinculação/materialização,
preservação de resumos mesmo após evicção, ausência de binding no provedor de conteúdo e
exclusão do seletor por falta de registro. A evidência física do aparelho é imprescindível
para atribuir a falha original do MangaFire a uma causa específica e para provar a leitura.
O diagnóstico não modifica mapeamento canônico, progresso/histórico, preferência de fonte,
mecanismos de CAPTCHA ou comportamento de seleção.
