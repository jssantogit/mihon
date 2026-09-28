# Chapter integrity requirements

## ADDED Requirements

### Requirement: Stable canonical identity

The system SHALL preserve a canonical chapter ID when the same verified chapter is observed across sources. It SHALL retain separate variants or evidence records for each source. It SHALL NOT merge ambiguous observations using text similarity or number alone when volume, chapter type, part, edition, or established evidence conflicts.

#### Scenario: Shared verified chapter

- **WHEN** two enabled sources report the same unambiguous chapter identity
- **THEN** one canonical chapter remains and both source observations remain addressable

#### Scenario: Ambiguous or conflicting identity

- **WHEN** source observations are ambiguous or contradict an existing mapping
- **THEN** the system preserves observations and exposes an internal conflict without silently remapping user progress

### Requirement: Atomic, idempotent reconciliation

The system SHALL reconcile chapter rows, variants, and evidence in an atomic title-scoped write when they form one operation. A failed or canceled write SHALL roll back. Repeated refreshes SHALL retain IDs and progress and SHALL NOT delete another source's evidence. Repository lookups SHALL use stable keys or preloaded indexes rather than scan the title inventory per observation.

#### Scenario: Interrupted inventory write

- **WHEN** persistence fails after some rows in an inventory are written
- **THEN** no rows from that inventory become visible and earlier chapter IDs, evidence, and progress remain unchanged

### Requirement: Truthful availability and order

Metadata counts SHALL produce provisional outline slots only. A reading option SHALL require evidence for the requested canonical chapter and a compatible enabled source. Numeric chapter order SHALL place 0 before 0.5 before 1 before 1.5 before 2; special and unknown chapters SHALL follow their explicit type rules. Provider failure SHALL remain distinct from an empty inventory.

#### Scenario: Metadata slot has no pages

- **WHEN** a reported count creates an outline slot without a mapped source observation
- **THEN** the slot remains provisional and is not presented as a readable option

### Requirement: Safe source replacement

The Reader SHALL retain its current session, history, and canonical progress if a replacement returns no pages, incompatible content, or an error. A successful switch SHALL clamp restored page position to the new page count and publish the new session only after preparation succeeds. Preference changes SHALL follow the existing post-preparation confirmation flow.

#### Scenario: Replacement fails after a valid session

- **WHEN** the selected source throws or returns an empty page list during preparation
- **THEN** the Reader keeps the prior session, history, and canonical progress

### Requirement: Bounded discovery

Discovery SHALL preserve the Fast branch's targeted binding refresh, language priorities, source eligibility, bounded visible wait, and explicit ambiguity confirmation. Delayed results SHALL NOT replace a newer chapter selection. Repeated searches SHALL have bounded in-flight work.

#### Scenario: Old search completes after chapter selection changes

- **WHEN** a pending discovery for chapter A completes after the user selects chapter B
- **THEN** its result does not replace chapter B's options or change the user's preferences
