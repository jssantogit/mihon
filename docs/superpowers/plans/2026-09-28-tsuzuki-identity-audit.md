# Tsuzuki — Auditoria de Identidade

Date: 2026-09-28
Branch: `tsuzuki/bootstrap`
Baseline: `73565985efaa2002a779e7c65be3168785d64258`
Status: auditoria inicial; nenhuma renomeação de código autorizada por este documento

## Objetivo

Mapear a identidade herdada do Mihon antes de qualquer rename e separar:

- identidade pública do produto;
- compatibilidade com Mihon/Tachiyomi;
- identificadores persistentes e contratos Android;
- nomes internos cujo rename teria custo sem benefício de produto;
- atribuição/licença/upstream.

A regra continua sendo: **não fazer search/replace global de Mihon/Tachiyomi para Tsuzuki**.

## Relação com upstream

O Tsuzuki deve ser tratado daqui em diante como produto e arquitetura independentes derivados do Mihon.

O Mihon continua útil como upstream técnico seletivo para correções e evolução reaproveitável, especialmente Android/Compose, Reader, compatibilidade de extensões, segurança e dependências.

Não assumir sincronização contínua por merge de `upstream/main`. Mudanças futuras do upstream devem ser avaliadas e portadas seletivamente por cherry-pick, port manual ou implementação equivalente.

A relação pública desejada é:

`Tsuzuki — independent project derived from Mihon; selectively consuming upstream`

A atribuição e a licença do upstream devem permanecer preservadas.

## Estado encontrado

### Repositório

- repositório atual: `jssantogit/mihon`;
- branch autoritativa: `tsuzuki/bootstrap`;
- o nome público do repositório ainda é Mihon;
- `README.md` ainda é essencialmente o README oficial do Mihon, com logo, badges, links, downloads, Discord, créditos e copy do Mihon;
- `CONTRIBUTING.md` ainda direciona contributors para infraestrutura/comunidade do Mihon;
- `.github/ISSUE_TEMPLATE/config.yml` ainda direciona usuários ao site/documentação do Mihon.

### Nome do app

No catálogo base de strings:

`i18n/src/commonMain/moko-resources/base/strings.xml`

o valor atual é:

`app_name = Mihon`

Variantes específicas de desenvolvimento já usam:

- `Tsuzuki Dev A`
- `Tsuzuki Dev B`
- `Tsuzuki Dev C`
- `Tsuzuki Generic Test`

Logo, a identidade Tsuzuki já existe nas lanes de desenvolvimento, mas a release/base ainda se apresenta como Mihon.

### Android application ID e namespace

Em `app/build.gradle.kts`:

- namespace: `eu.kanade.tachiyomi`
- applicationId: `app.mihon`

Build types/lane variants adicionam suffixes próprios, inclusive `.tsuzuki.*`.

O `applicationId` deve ser tratado como contrato de compatibilidade de alto risco. Alterá-lo cria outro pacote Android e pode interromper upgrade sobre APK existente e acesso ao sandbox de dados anterior.

O namespace Kotlin/Android `eu.kanade.tachiyomi` é predominantemente implementação interna. Não há benefício de produto suficiente para justificar rename em massa nesta frente.

### Pacotes internos herdados

A árvore contém grandes áreas sob:

- `eu.kanade.tachiyomi.*`
- `mihon.*`
- `tachiyomi.*`

Também existem:

- 85 paths sob `app/src/main/java/mihon` / AIDL relacionado;
- build logic sob `mihon.gradle.*`;
- plugin aliases/IDs `mihon.plugins.*`;
- version catalog `gradle/mihon.versions.toml`.

Esses nomes são majoritariamente internos. Renomeá-los agora produziria um diff enorme, elevaria o custo de absorver upstream e traria pouco ou nenhum benefício ao usuário.

### Compatibilidade com extensões

`eu.kanade.tachiyomi.AppInfo` declara explicitamente que é usado por extensions.

Isso confirma que partes do namespace herdado fazem parte da superfície de compatibilidade do ecossistema e não devem ser renomeadas por branding.

### Deep links e URI schemes

O manifest possui atualmente:

- legacy extension-store: `tachiyomi://add-repo`;
- extension-store: `mihon://extension-store`;
- OAuth/tracker callbacks via `mihon://...`;
- Supabase Tsuzuki: `tsuzuki://auth`.

Conclusão:

- `tachiyomi://` e `mihon://` não são apenas branding visual; podem ser contratos externos;
- o Tsuzuki já tem um scheme próprio para autenticação Supabase;
- a futura identidade pode adicionar schemes Tsuzuki onde fizer sentido, mas os aliases legados devem ser preservados até existir prova de que podem ser removidos;
- callbacks OAuth exigem coordenação com os providers registrados antes de qualquer mudança.

### Providers / authorities

O manifest deriva authorities de `${applicationId}`:

- `${applicationId}.provider`;
- `${applicationId}.shizuku`.

Logo, mudar `applicationId` também muda authorities e deve ser tratado junto da estratégia de migração do pacote.

### Backups

`BackupCreator` usa `BuildConfig.APPLICATION_ID` no nome do arquivo automático:

`${APPLICATION_ID}_YYYY-MM-DD_HH-mm.tachibk`

O formato/extension `.tachibk` permanece compatível com a herança Tachiyomi/Mihon.

O nome de arquivo pode eventualmente ganhar branding Tsuzuki, mas leitura/restauração de backups anteriores não deve ser quebrada.

### Assinatura

O workflow `.github/workflows/apk.yml` já utiliza secrets persistentes `TSUZUKI_KEYSTORE_*` e produz artifacts `tsuzuki-...`.

Isso já é identidade Tsuzuki e deve ser preservado.

O build ainda ativa a assinatura via variável interna `MIHON_GITHUB_RELEASE`. Apesar do nome, ela é atualmente um detalhe interno de build; renomeá-la não é necessário para a identidade pública e pode ser feito apenas se houver motivo técnico.

### Release workflow herdado

`.github/workflows/release.yml` ainda é o workflow upstream:

- só executa quando `github.repository == 'mihonapp/mihon'`;
- nomeia APKs como `mihon-...`;
- cria releases `Mihon ...`;
- usa links e copy de suporte do Mihon;
- usa `MIHON_BOT_TOKEN`.

No fork atual, esse workflow está essencialmente desativado pelos guards de repository. Ele deve ser substituído/reescrito para releases Tsuzuki quando o canal de distribuição for formalizado, e não simplesmente reaproveitado por rename cego.

### Website workflow herdado

`.github/workflows/update_website.yml` dispara `mihonapp/website` usando `MIHON_BOT_TOKEN`.

Não representa infraestrutura Tsuzuki e deve ser removido/desativado ou mantido apenas como histórico upstream, não como workflow operacional do projeto.

### Branding visual

Ainda existem assets e referências diretamente Mihon:

- `.github/assets/logo.png`;
- `app/src/main/res/drawable/ic_mihon.xml`;
- `app/src/main/res/drawable/ic_mihon_splash.xml`;
- launcher assets herdados;
- `LogoHeader.kt` renderiza `R.drawable.ic_mihon`.

Esses itens devem ser substituídos quando o logo/ícone Tsuzuki básico estiver definido.

### Themes e nomes históricos

O manifest e resources ainda usam nomes como:

- `Theme.Tachiyomi`;
- `TachiyomiTheme`;
- `TachiyomiColorScheme`.

São identificadores internos. Não são prioridade de branding e devem ficar fora do rename inicial.

### Strings de suporte ao Mihon

A base de strings ainda contém textos de campanha/doação do Mihon e referências a Discord/comunidade Mihon.

Esses textos não devem ser apresentados como se fossem suporte financeiro/comunidade do Tsuzuki. Devem ser removidos das superfícies públicas do Tsuzuki ou reestruturados.

Isso é diferente de créditos/licença: atribuição ao Mihon deve continuar presente em About/Credits/licença.

### Licença e créditos

`LICENSE` e o bloco de licença/créditos do README mantêm copyright/autoria do upstream.

Esses créditos não devem ser apagados.

Quando houver documentação Tsuzuki definitiva, ela deve distinguir claramente:

- autoria/identidade atual do Tsuzuki;
- derivação do Mihon;
- copyrights/licença herdados;
- componentes e projetos de terceiros.

## Matriz de identidade

| Item | Atual | Desejado | Categoria | Risco | Estratégia |
| --- | --- | --- | --- | --- | --- |
| Nome do repositório | `jssantogit/mihon` | Tsuzuki | A — renomear | médio | coordenar rename no GitHub depois de atualizar referências próprias |
| README público | Mihon | Tsuzuki + créditos upstream | A | baixo | reescrever; não copiar apresentação do upstream como identidade atual |
| App label base | Mihon | Tsuzuki | A | baixo | troca direta do resource base |
| Dev app labels | Tsuzuki Dev A/B/C/Generic | manter | A | baixo | já correto |
| Logo/ícone/splash | Mihon | Tsuzuki | A | baixo/médio | substituir após definição dos assets básicos |
| `LogoHeader` | `ic_mihon` | asset Tsuzuki | A | baixo | trocar referência junto dos assets |
| `rootProject.name` | Mihon | Tsuzuki | A | baixo | rename controlado de identidade de build |
| `applicationId` | `app.mihon` | decisão separada | C — compatibilidade | alto | **não alterar nesta primeira onda**; definir estratégia de upgrade/dados |
| Android namespace | `eu.kanade.tachiyomi` | manter por ora | D — não tocar | alto/diff grande | nenhum rename nesta frente |
| Pacotes `eu.kanade.tachiyomi.*` | herdados | manter por ora | D/B | alto | preservar compat/upstream |
| Pacotes `mihon.*` | herdados | manter por ora | D | médio/alto | não renomear por estética |
| Build logic `mihon.gradle.*` | herdado | manter por ora | D | médio | baixo valor de produto para rename |
| Plugin IDs `mihon.plugins.*` | herdados | manter por ora | D | médio | não tocar |
| `AppInfo` de extensions | Tachiyomi namespace | manter | B — compatibilidade | alto | preservar ABI/API esperada |
| `tachiyomi://add-repo` | legado | alias compatível | B/C | alto | manter |
| `mihon://extension-store` | atual | manter alias; opcional Tsuzuki adicional | B/C | alto | migração aditiva, nunca remoção abrupta |
| Tracker OAuth `mihon://...` | atual | TBD | C | alto | mapear registrations antes de tocar |
| Supabase callback | `tsuzuki://auth` | manter | A | baixo | já correto |
| FileProvider authority | `${applicationId}.provider` | depende do app ID | C | alto | tratar junto do applicationId |
| Shizuku authority | `${applicationId}.shizuku` | depende do app ID | C | alto | tratar junto do applicationId |
| Backup filename | applicationId + `.tachibk` | Tsuzuki sem quebrar import legado | C/B | médio | preservar leitura; branding do nome pode ser posterior |
| Assinatura APK | secrets Tsuzuki persistentes | manter | C | crítico | não quebrar keystore/upgrade path |
| Artifact do APK workflow | `tsuzuki-...` | manter | A | baixo | já correto |
| `MIHON_GITHUB_RELEASE` | nome interno | manter por ora | D | baixo | rename opcional apenas por limpeza futura |
| Release workflow | Mihon oficial/gated | Tsuzuki | A | médio | substituir por workflow próprio quando distribuição for definida |
| Website workflow | mihonapp/website | remover/substituir | A | baixo | não deve agir como infraestrutura Tsuzuki |
| CONTRIBUTING | Mihon links/comunidade | Tsuzuki | A | baixo | reescrever preservando atribuição quando relevante |
| Issue template links | mihon.app | Tsuzuki ou remover | A | baixo | atualizar superfícies públicas |
| Themes `Theme.Tachiyomi` | herdado | manter por ora | D | baixo benefício | não tocar |
| Strings de doação Mihon | campanha Mihon | não expor como Tsuzuki | A/B | médio | remover da UI Tsuzuki; manter créditos separadamente |
| LICENSE/upstream credits | Mihon/Tachiyomi | preservar | B — manter | crítico jurídico | não apagar; adicionar créditos Tsuzuki apenas onde apropriado |

## Categorias adotadas

### A — Renomear para Tsuzuki

Identidade pública e de produto:

- repo;
- README;
- app label;
- logo/ícone/splash;
- headers visuais;
- documentação própria;
- issue/contribution surfaces;
- artifacts e release copy próprios;
- workflows especificamente Tsuzuki.

### B — Manter Mihon/Tachiyomi

Quando o nome descreve de fato origem, compatibilidade ou atribuição:

- licença/copyright upstream;
- créditos;
- APIs/contratos usados por extensions;
- deep links legados enquanto necessários;
- documentação que fala explicitamente do upstream.

### C — Migrar com compatibilidade

Não alterar sem plano específico:

- `applicationId`;
- assinatura;
- OAuth callbacks;
- URI schemes usados externamente;
- authorities;
- backups/storage;
- qualquer identificador persistido;
- update path.

### D — Não tocar nesta frente

Internals sem benefício de produto proporcional ao risco/diff:

- namespace `eu.kanade.tachiyomi`;
- grande parte de `mihon.*`;
- `tachiyomi.*`;
- build logic `mihon.gradle.*`;
- plugin IDs internos;
- nomes de Theme/classes internas herdadas.

## Contrato preliminar para a implementação

A primeira onda de implementação de identidade deve ser **branding-only e compatibility-preserving**.

Ela pode alterar:

- app label;
- README/documentação pública;
- logo/ícone/splash;
- branding em telas;
- nome do root project;
- nomes/copy de artifacts e workflows próprios;
- superfícies de GitHub que hoje apontam para Mihon como se o fork fosse o upstream oficial.

Ela não deve alterar:

- `applicationId`;
- assinatura;
- namespaces/pacotes internos;
- schemes legados;
- tracker OAuth callbacks;
- authorities;
- formato de backup;
- APIs de extensions.

## Próximas decisões antes da spec de implementação

1. Definir logo/ícone/wordmark Tsuzuki básicos.
2. Decidir o nome final do repositório no GitHub (`Tsuzuki` vs `tsuzuki`, respeitando convenção desejada).
3. Decidir se a primeira release pública deve continuar com `applicationId = app.mihon` para preservar o upgrade path atual.
4. Mapear os callbacks OAuth registrados externamente antes de qualquer proposta de URI Tsuzuki adicional.
5. Definir a seção About/Credits que explicará a relação com Mihon.
6. Reescrever o release workflow somente quando o canal de distribuição Tsuzuki estiver definido.

## Conclusão

A maior parte da identidade pública pode ser migrada para Tsuzuki sem tocar na fundação técnica herdada.

A estratégia recomendada é deliberadamente assimétrica:

- **forte rename na superfície pública**;
- **forte preservação nos contratos técnicos e internals**.

Isso reduz risco, preserva compatibilidade com o ecossistema Mihon/Tachiyomi e mantém o Tsuzuki apto a consumir upstream seletivamente no futuro.
