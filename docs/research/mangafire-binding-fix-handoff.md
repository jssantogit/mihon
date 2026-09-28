# MangaFire binding fix — handoff

## Resultado

O relatório sanitizado confirma que MangaFire falhou durante a resolução/materialização do binding, antes de produzir inventário para o probe:

```text
PROBE|outcome=EXTENSION_ERROR|addonId=eu.kanade.tachiyomi.extension.all.mangafire|received=0|accepted=0|provisional=0|discarded=0|reasons=BINDING_UNAVAILABLE:1
```

Ele **não identifica a causa de runtime específica**: não informa versão instalada da extensão, IDs/idiomas internos consultados, quais fontes internas estavam desativadas, resultado individual das buscas, classe/origem da exceção, CAPTCHA, nem falha ao materializar. Portanto não é evidência suficiente para atribuir o evento do usuário a idioma desativado, falta de correspondência, ambiguidade, rede ou extensão.

Foi confirmada, contudo, uma falha determinística no contrato do runtime que pode causar esse sintoma quando um pacote multi-fonte tem idiomas internos desativados:

1. `MihonAddonRepository.snapshot()` calculava `InstalledAddon.enabled` como verdadeiro se *qualquer* fonte interna estivesse habilitada, mas publicava em `mihonSourceIds` a lista de **todas** as fontes internas.
2. `ResolveContentBinding` usa `mihonSourceIds` para procurar e materializar candidatos de título.
3. `MihonReadingSourceGateway` rejeita uma busca para uma fonte interna desativada. `ResolveContentBinding` conserva falhas de busca e, se não há candidato selecionado, pode devolver `ContentBindingSourceSearchException` em vez de `ContentBindingNotFoundException`.
4. `RefreshChapterEvidence` normaliza uma exceção de binding não reconhecida como rede/timeout para `EXTENSION_ERROR / BINDING_UNAVAILABLE`.

Assim, estado misto (pacote habilitado, mas alguns idiomas desativados) pode contaminar a busca do binding com fonte que o usuário desativou. Isso é uma causa comprovada no código, reproduzida por teste de regressão. Ainda não está comprovado que esse era o estado do dispositivo na sessão `a0293c50-57b7-4e98-8e87-b4dd27c7b248`.

## Alteração mínima

`MihonAddonRepository.snapshot()` agora publica em `mihonSourceIds` somente IDs que não constam em `disabledSourceIds`. A flag `InstalledAddon.enabled` continua verdadeira quando pelo menos uma fonte está habilitada. `setEnabled()` continua ativando/desativando **todas** as fontes internas ao alternar o add-on inteiro. Não há mudança em identidade canônica, bindings persistidos, progresso, preferência de fonte, seleção automática de títulos, parser/rede da extensão ou tratamento de CAPTCHA; nenhuma migração é necessária.

## Teste de regressão

`MihonAddonRepositoryTest.partially disabled multi source addon exposes only enabled sources` cria fontes `en` e `pt-BR`, com `en` desativada. Confirma que o pacote continua habilitado e que só o ID habilitado é exposto ao resolvedor. O comportamento antigo retorna ambos os IDs e falha a expectativa.

## Diagnóstico versus causas alternativas

- **Fonte interna desativada incluída na busca:** defeito confirmado no código; teste comprova a regressão e a correção.
- **Correspondência ausente:** o relatório não mostra candidatos. O resolver diferencia ausência segura de erro de busca, mas o relatório atual colapsa certas falhas em `BINDING_UNAVAILABLE`.
- **Candidatos ambíguos:** o resolver usa confirmação explícita; o relatório não indica `BINDING_CONFIRMATION_REQUIRED`. Não foi observado caso ambíguo.
- **Rede/timeout/CAPTCHA:** não confirmado. A classificação existente identifica algumas falhas como rede/timeout quando a cadeia contém `IOException` ou timeout, mas a sessão não contém a exceção sanitizada necessária para afirmar isso. Não contornar nem desativar CAPTCHA.
- **Falha de materialização:** ocorre depois de selecionar candidato; não há indicação suficiente no relatório para separar essa possibilidade de outros erros encapsulados como `BINDING_UNAVAILABLE`.
- **Inventário vazio/ausência de capítulos:** não é o estágio reportado. A sonda da extensão MangaFire recebeu zero porque o binding não ficou disponível; isso não prova que a extensão ou o site não tenham capítulos.
- **Site público versus extensão instalada:** a listagem no site não prova que a extensão instalada encontrou o título, resolveu o binding ou consegue abrir capítulos. O relatório não registra versão da extensão nem disponibilidade de páginas.

## Verificação

- RED: teste novo incluído em `6b8c73566` e enviado à branch; CI v2 run `35874385719` executou os testes de App e falhou, enquanto formatação passou. O log final deverá ser consultado para guardar a mensagem exata da falha antes da correção.
- GREEN: a validação final depende do CI v2 acionado pelo commit de correção; incluir o resultado e SHA nesta seção ao concluir.
- Não executar Gradle localmente: o projeto define CI-first e zero tentativas pesadas locais.
- `adb` não está instalado neste ambiente. Nenhum diagnóstico de aparelho, APK, smoke test físico ou disponibilidade de leitura é alegado.

## Smoke test Android após CI

1. Instale uma build de diagnóstico aprovada externamente; esta tarefa não gera APK.
2. Abra Diagnóstico de capítulos e habilite a captura temporária.
3. Em Add-ons, habilite MangaFire e confirme que o(s) idioma(s) desejado(s) estão habilitados; não habilite idiomas que o usuário desativou.
4. Abra One-Punch Man, atualize o catálogo e copie o relatório sanitizado.
5. Confirme que MangaFire deixa de retornar `BINDING_UNAVAILABLE` causado por fonte interna desativada; se não houver correspondência, a UI/diagnóstico deve continuar distinguindo binding ausente/ambíguo de falha de rede/extensão. Não aceite uma correspondência de título por semelhança sem confirmação.
6. Se o seletor oferecer MangaFire, abra um capítulo deliberadamente escolhido. Registre idioma, rótulo e resultado, sem salvar conteúdo, URLs completas, tokens ou cookies.
7. Se aparecer CAPTCHA, use a interação humana oferecida pela extensão, quando disponível. Não contorne a proteção. Não altere a fonte preferida nem o progresso sem ação expressa.
8. Desative e apague o diagnóstico ao terminar. Registre se o capítulo realmente abriu; presença no seletor ou no site não é prova de leitura.

## Pendências

Para estabelecer se a causa observada no dispositivo foi exatamente o estado misto de fontes, o smoke test deve registrar versão do MangaFire e estado habilitado/desabilitado por idioma, além de um resultado sanitizado por fonte para a etapa de busca/materialização. Se o erro persistir com somente fontes habilitadas, a próxima investigação deve separar falta de correspondência, erro de rede/CAPTCHA e falha de materialização; não ampliar fallback nem modificar identidade canônica sem essa evidência.
