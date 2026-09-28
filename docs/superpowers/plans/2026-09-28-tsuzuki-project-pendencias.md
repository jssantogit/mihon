# Tsuzuki — Pendências do Projeto

Date: 2026-09-28
Branch: `tsuzuki/bootstrap`
Status: backlog pós-estabilização do runtime/leitor

## Contexto

A etapa de estabilização do runtime, descoberta de fontes, identidade de capítulos e integração do Reader foi concluída e consolidada em `tsuzuki/bootstrap`.

Este documento registra as próximas frentes de produto. Ele é um backlog de alto nível, não uma autorização para iniciar todas as implementações em paralelo. Cada frente deve ganhar sua própria spec/plano antes de alterações arquiteturais relevantes.

## 1. Identidade própria do projeto

Objetivo: reduzir a identidade pública herdada do Mihon e estabelecer o Tsuzuki como produto próprio.

Pendências:
- revisar o nome do repositório e a apresentação pública do projeto;
- substituir referências visíveis ao Mihon por Tsuzuki quando forem identidade de produto;
- manter referências a Mihon quando forem necessárias por atribuição, upstream, compatibilidade ou contexto técnico;
- criar logo/ícone próprios;
- revisar nome do app, workflows, artifacts, documentação e demais superfícies visíveis;
- auditar identificadores técnicos antes de renomeá-los, especialmente package/application ID, assinatura, deep links, storage e dados persistidos.

Regra: não fazer renomeação global cega. Classificar cada ocorrência em manter, renomear, migrar com compatibilidade ou não tocar.

## 2. Reorganização funcional da UI

Objetivo: tornar a interface mais coerente e fácil de usar sem ainda executar o redesign visual completo.

Escopo inicial:
- reorganizar a disposição das funções existentes;
- remover conteúdos, ações e superfícies que não devem fazer parte do happy path;
- revisar Home, Search, Library, Collections, Add-ons/Integrações e Settings/Profile;
- expor ações importantes que hoje estão escondidas;
- reduzir duplicidade e heranças de navegação do Mihon;
- preservar o comportamento funcional já aceito enquanto a arquitetura de informação é reorganizada.

Esta etapa é principalmente estrutural. O polish visual definitivo fica para uma frente posterior.

## 3. Torrents, Debrid e HTTPS — novo runtime de extensões

Objetivo: criar uma nova arquitetura de conteúdo inspirada no modelo de extensões locais do CloudStream.

Direção preliminar:
- extensões executadas localmente no dispositivo;
- suporte a fontes HTTPS;
- suporte a descoberta/streaming via torrent;
- suporte a serviços de Debrid;
- evitar depender do modelo convencional de Add-ons hospedados do Stremio/Nuvio para todo o runtime;
- manter separação clara entre metadata provider, reading/content provider e transporte;
- projetar segurança, sandbox/isolamento, atualizações de extensões, permissões, cache e compatibilidade.

Detalhes arquiteturais serão definidos em uma spec própria antes da implementação.

## 4. DNA visual definitivo do Tsuzuki

Objetivo: dar identidade visual própria ao aplicativo depois que a estrutura da UI estiver estabilizada.

Pendências:
- design system;
- paleta;
- tipografia;
- espaçamento;
- cards e superfícies;
- componentes de navegação;
- estados vazios, loading, erro e progresso;
- motion/animações;
- tratamento visual do Reader e das telas de detalhe;
- consistência entre Home, Search, Library, Collections e Settings;
- integração do logo/branding definidos na frente de identidade.

Regra: não fazer o grande redesign antes da reorganização funcional, para evitar polir componentes que serão removidos ou reposicionados.

## 5. Collections e catálogos v2

Objetivo: aproximar Collections do modelo de uso do Nuvio e transformá-las em uma ferramenta real de organização e descoberta.

Principais pendências:
- filtros muito mais fortes e combináveis;
- melhorar ordenação, agrupamento e critérios de consulta;
- permitir ao usuário criar catálogos/listas reutilizáveis;
- permitir catálogos alimentados por fontes de metadados além de Kitsu e MAL;
- manter separação entre metadata provider e reading source;
- permitir que listas/collections relevantes sejam expostas na Home;
- definir melhor a relação entre Collections, catálogos, filtros persistidos e descoberta.

Essa frente afeta Home, Search e Collections e deve ser considerada antes do redesign visual final.

## Ordem sugerida

1. Identidade própria do projeto.
2. Reorganização funcional da UI.
3. Collections e catálogos v2.
4. Torrents, Debrid e HTTPS / novo runtime de extensões.
5. DNA visual definitivo.

A frente 4 pode avançar conceitualmente em paralelo porque é relativamente independente da reorganização da UI, mas a implementação não deve misturar mudanças arquiteturais do runtime com uma grande reforma visual na mesma rodada.

## Estado atual

Concluído antes deste backlog:
- runtime modular consolidado;
- descoberta automática de fontes;
- Reader integrado ao modelo canônico;
- reconciliação/identidade de capítulos estabilizada;
- regressões físicas críticas de One Piece, Hunter × Hunter, Kimetsu no Yaiba e Tokyo Ghoul aceitas;
- integração final consolidada em `tsuzuki/bootstrap`.

Próxima ação recomendada: abrir uma spec/plano específico para a frente 1 antes de iniciar alterações de branding ou identificadores técnicos.
