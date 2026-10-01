# Tsuzuki Rating — implementation plan

**Date:** 2026-10-01  
**Status:** Wave 1 implementation

## Product definition

Tsuzuki Rating is an aggregate summary of provider-native community ratings. It never replaces provider ratings or provider branding.

### V1 formula

- normalize every usable provider score to a 0–10 scale:
  `normalized = value / maxValue * 10`;
- every provider contributes exactly one equal-weight signal;
- raw provider vote counts are preserved but **do not weight** the V1 aggregate;
- at least **two distinct providers** are required;
- one provider alone produces no Tsuzuki Rating;
- invalid/out-of-range scores fail closed;
- calculation uses full precision; UI rounds only for presentation.

### Identity evidence

Every contributing source carries one of:

- `VERIFIED` — provider ID already known or proven through provider-published mapping;
- `CORROBORATED_RATING_ONLY` — the existing ephemeral strict match (exact normalized title/alias plus publication year or creator corroboration, unique result only).

Corroborated rating-only evidence may contribute to Tsuzuki Rating but never becomes canonical identity.

## Wave 1 — core

- preserve provider vote counts across exact and supplemental rating paths;
- preserve rating identity evidence into resolved models;
- add `TsuzukiRatingSource` and `TsuzukiRating`;
- add pure deterministic `ComputeTsuzukiRating`;
- expose the aggregate on resolved metadata;
- unit-test normalization, minimum source count, equal weighting, duplicate providers, invalid data and corroborated evidence.

## Wave 2 — product surfaces

- compute Tsuzuki Rating for Search/Discovery catalog items;
- show the approved Tsuzuki Rating identity in canonical Detail;
- show a compact Tsuzuki Rating on catalog cards while retaining provider provenance;
- use the approved RGB asset without changing its design.

## Wave 3 — diagnostics and acceptance

- record bounded aggregate composition diagnostics: source count, verified/corroborated counts and whether an aggregate was produced;
- never export raw titles or arbitrary provider payloads;
- full CI on every wave;
- final signed APK only after all waves merge;
- physical smoke with works exposing 1 through 6 ratings.

## Invariants

- native provider scales remain unchanged in provider badges;
- Tsuzuki Rating is always 0–10;
- vote-count dominance is intentionally rejected in V1;
- source identity matching rules are not weakened;
- no canonical identity is created from a rating-only match;
- formula changes require an explicit future product decision.
