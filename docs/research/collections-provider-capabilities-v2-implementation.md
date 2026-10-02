# Collections Provider Capabilities V2 — implementation status

This document records the implementation outcome of the approved Collections Provider Capabilities V2 plan.

## Runtime contract

Collections now uses one provider-capability contract for UI exposure and execution:

- registered `CollectionQueryProvider` instances are the source of selectable providers;
- `CollectionProviderDescriptor` controls visible Quick/Advanced filters, sort choices, paging, residual scan budgets, request budgets, and value lookup sources;
- provider-specific concepts stay namespaced as `QueryField.Custom`;
- unsupported imported expressions remain preserved and fail closed;
- provider switching preserves only semantically compatible filters/sorts and requires confirmation before removing incompatible state;
- persisted Lists and unsaved Preview share the same planner, paging, cache, residual evaluator, scan budget, and provider registry;
- dynamic value lookups are provider-owned and debounced by the List Builder runtime.

## Global providers

| Provider | Collections execution | Paging | Dynamic values | Notes |
| --- | --- | --- | --- | --- |
| Kitsu | Registered | Native offset | Genres | Reference descriptor. Only exact remote/residual capabilities are exposed. |
| MangaUpdates | Registered | Page API normalized to raw offset | Genres, categories | Rich manga-native filters and provider sorts. Unproven remote year syntax is not fabricated. |
| Hikka | Registered | One-based page API normalized to raw offset | Genres | Independent MAL/Hikka score ranges and native sorts. Multi-genre behavior remains conservative until exact semantics are proven. |
| Shikimori | Registered | Page/limit normalized to raw offset | Genres, publishers | Include/exclude semantics and the shared 5 req/s + 90 req/min request gate are preserved. |
| MyAnimeList | Registered | Ranked offset streams | — | Top/popularity/favorites candidate streams plus bounded exact residual filtering. |
| Bangumi | Registered | Raw Book stream filtered through eligible-offset normalization | — | Manga eligibility is applied without losing raw paging position, avoiding the old pre-pagination discard bug. |

## UI behavior

The List Builder no longer carries a hard-coded common provider/filter list.

- Source choices come from registered descriptors.
- Quick and Advanced controls are generated from the selected descriptor.
- Static, boolean, numeric/date range, free-text, and remote-lookup value sources use the same descriptor contract.
- `INCLUDE_EXCLUDE` controls keep positive and negative predicates independently.
- Remote lookup state is threaded through root and nested Collection/Folder/List editors.
- Preview is real execution, debounced, and stale requests are cancelled.
- A residual scan budget stop returns partial results with a continuation cursor instead of pretending provider exhaustion or spinning indefinitely.

## Persistence and paging

- Collections-specific extensible sorts are persisted with stable keys and optional direction.
- Collection schema/portable format v2 accepts legacy v1 sort strings.
- Cache keys contain provider, normalized pushdown, sort key/direction, raw offset, and page size.
- Page-based providers normalize arbitrary raw offsets without skipping or duplicating candidates.
- Filtered candidate streams use eligible-offset normalization where filtering changes cardinality.

## Policy

Shikimori and Hikka public Search/Metadata/Artwork capabilities are `ALLOWED` according to the project decision. Ratings remain allowed. Authenticated tracker/account operations still use user-owned credentials.

Policy does not fabricate account-library runtime behavior: complete-list projection remains gated on verified provider semantics and a real `UserListProvider`.

## Diagnostics

Collections execution emits only bounded structured diagnostics:

- query planned;
- execution completed;
- provider id;
- result item count;
- closed outcome codes including partial bounded-scan completion.

Query text, List names, manga titles, lookup text, URLs, credentials, provider bodies, and exception messages are not recorded.

## Intentional fail-closed gates

The V2 rule is correctness over parity. A provider can expose fewer controls than another provider.

Capabilities whose exact semantics are not proven remain hidden rather than approximated. Current examples include provider-specific multi-value/range behavior explicitly called out by the implementation waves. These gates do not block the six providers from being registered and executable for their verified capabilities.

## Validation

Final integration PR: #89.

The final Wave 8 branch contains Waves 1–7 plus the final dynamic lookup, descriptor-only UI, include/exclude, diagnostics, and global integration work.

Required merge gate:

1. full CI: Format, Domain, Data, App, SQLDelight migration verification, Release Compile, Supabase lane where applicable, and CI Gate;
2. merge the stacked implementation only after the final tree is green;
3. generate an acceptance APK only after the complete stack is consolidated into `main`.
