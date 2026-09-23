# One-Punch Man — recuperacao de inventario, setembro de 2026

## Evidencia de entrada

O diagnostico opt-in feito pelo usuario registrou MangaDex com 836 observacoes,
248 mapeadas e 588 com baixa confianca; a UI permaneceu com 121 capitulos
canonicos. MangaFire registrou `BINDING_UNAVAILABLE`, mas o motivo exato
da indisponibilidade nao foi exposto no relatorio anterior.

A implementacao publica da extensao MangaDex (`MangaDexHelper.createChapter`)
constroi os nomes dos capitulos como `Vol.1 Ch.1 - Title` (volume opcional),
sem garantir que `chapter_number` esteja preenchido. O parser Tsuzuki
anterior reconhecia capitulos quando o nome iniciava com numero ou marcador
de capitulo, mas nao reconhecia o prefixo `Vol.1`. Isso pode explicar
parte das evidencias com baixa confianca; a quantidade exata recuperada
so pode ser estabelecida por novo diagnostico no Android.

Fonte de implementacao:
https://github.com/keiyoushi/extensions-source/blob/main/src/all/mangadex/src/eu/kanade/tachiyomi/extension/all/mangadex/MangaDexHelper.kt

## Alteracoes

- O parser agora remove somente prefixos de volume seguidos de um
  marcador explicito de capitulo, aceitando `Vol.1 Ch.1`,
  `Vol.none Ch.137.5` e `Volume 12 - Chapter 137`.
  Labels ambiguos e dicas numericas isoladas continuam desconhecidos;
  nenhum capitulo ficticio e criado.
- A resolucao de vinculos testa ate tres grafias/pontuacoes do titulo
  quando nao ha candidato unico de alta confianca. A avaliacao de titulo
  existente permanece autoritativa para a busca automatica e candidatos
  empatados continuam exigindo confirmacao humana.
- Uma falha de busca de titulo deixa de ser silenciosamente tratada como
  busca genuinamente vazia. O diagnostico diferencia ausencia de vinculo
  e necessidade de confirmacao.
- Testes cobrem labels reais MangaDex e mapeamento de variantes, busca
  de titulos com hifens, ambiguidades, erros de rede e categorias
  sanitizadas do diagnostico.

## Limites

Nenhum log desta versao foi coletado do Android. Nao afirmar que todos
os 588 itens foram recuperados ou que MangaFire possui todos os capitulos
no addon instalado. Nao houve mudanca de fonte preferida, leitor,
progresso, politica de fallback ou migration SQLDelight.

## Smoke no APK seguinte (somente apos CI e autorizacao)

1. Na tela de One-Punch Man, iniciar diagnostico e tocar em Refresh.
2. Registrar `INVENTORY` PT-BR MangaDex, `PROBE`,
   `RECONCILIATION`, `PERSISTENCE` e `UI`. Confirmar se
   labels 1 e 2 chegam e quantas observacoes sao mapeadas.
3. Verificar se a UI passa a oferecer o capitulo 1, em ordem; abrir
   apenas a variante/idioma explicitamente escolhido pelo usuario.
4. Verificar MangaFire: se `NO_BINDING`, `NETWORK_ERROR`
   ou `BINDING_CONFIRMATION_REQUIRED` persistir, examinar
   a busca/binding da extensao instalada; nao inferir disponibilidade
   no aparelho a partir da pagina publica.
5. Comparar novo relatorio com a captura original e desativar/apagar
   o diagnostico ao terminar.
