# Design

## Current production paths

- Detail and Library call `RefreshChapterEvidence`, which probes integrations and enabled Add-on bindings, then calls `ReconcileChapterEvidence`.
- The selector and progressive discovery call `ResolveChapterContent` and targeted `RefreshChapterEvidence.executeForBinding`. Binding search stays in `ResolveContentBinding`.
- Legacy Reader entry and source switch call `RefreshCanonicalChapters`, which uses `MihonChapterInventoryGateway` and `ReconcileChapterInventory` to write canonical chapters and variants. `ResolveReadingSource` serves that legacy path.
- `MihonContentProvider` bridges mapped evidence and legacy variants into Mihon reading options. `MihonChapterProbeProvider` supplies Add-on evidence.

## Decision

`ReconcileChapterEvidence` becomes the authoritative semantic mapping operation. Legacy source inventory remains an adapter and must not independently choose a different canonical identity for the same source chapter. Reconciliation preloads title state, indexes it by canonical ID, stable producer key, and structured identity, then builds a write set. The data layer commits canonical rows and evidence together. Network calls finish before the transaction begins. A title-scoped mutation gate prevents two local reconciliations from assigning different IDs, while database uniqueness and transactions protect persistence.

The Reader constructs and validates a replacement session before publishing it. Previous history and canonical progress remain intact on preparation failure. Post-success page restoration clamps to the available page range. The Mihon projection is recoverable without losing canonical progress.

## Compatibility and migration

No table is dropped or recreated. New SQL queries and indexes, if needed, are additive and tested against existing schema. Existing IDs remain stable; conflicting old mappings are retained for explicit repair rather than silently merged. A new canonicalization rule requires regression fixtures before enabling it for persisted data.

## Evidence limits

Synthetic inventories prove deterministic contracts, not provider availability. One-Punch Man's historic 169/163 observation must be diagnosed at raw inventory, probe, reconciliation, persistence, and UI boundaries on the current build. Dandadan rendering requires original image/device evidence and remains a separate acceptance item.
