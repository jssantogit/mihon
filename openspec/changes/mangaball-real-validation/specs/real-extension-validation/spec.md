# Real multi-source Mihon extension validation

## ADDED Requirements

### Requirement: R1. Immutable, isolated, opt-in fixture

The Android workflow SHALL verify the pinned MangaBall APK bytes and signature, install it only in a disposable emulator, and require explicit dispatch for any live provider call. It SHALL never query all 42 internal sources or user data.

#### Scenario: Fixture and grouping

Given the verified MangaBall 1.6.1 APK, when Mihon loads and trusts it in the test emulator, Tsuzuki observes exactly one installed Add-on representing the package and 42 internal source IDs. The pt-BR source ID is `35546023386335815`.

#### Scenario: Disabled peer

Given another internal source disabled in the emulator, when the progressive binding search targets the pt-BR source, no search event or gateway call targets the disabled source. The test restores the prior disabled-source preference during cleanup.

### Requirement: R2. Verified identity before binding

A candidate SHALL NOT be accepted solely because its title resembles the canonical title. A test may explicitly confirm a candidate only when its stable source identity matches the predeclared reference work and edition. Zero or multiple reference matches yield `INCONCLUSIVE`, not an invented binding.

#### Scenario: One-Punch Man reference

Given a directed pt-BR search for One-Punch Man, the test compares candidate source identity with the predeclared MangaBall reference path `title-detail/one-punch-man-68515501702284f83417844d`. A unique match may enter explicit confirmation; otherwise subsequent steps are `NOT_RUN`.

### Requirement: R3. Real shared-state journey

With a unique verified candidate, the test SHALL use production resolver/gateway/repository boundaries and one disposable canonical title/database across progressive search, materialization, confirmed binding, persisted binding readback, chapter inventory, evidence reconciliation, content resolution, reader preparation, and `getPageList`. A chapter is selectable only if its exact source/chapter identity is evidenced. The test SHALL not fabricate missing chapters or equate list positions.

#### Scenario: External absence or restriction

An empty search/inventory, HTTP failure, network failure, CAPTCHA, unavailable option, or empty page list yields a stage-specific `INCONCLUSIVE` or `FAIL` with later stages `NOT_RUN`. The workflow never counts compilation, a skipped job, or a one-test JUnit run with incomplete stages as an E2E PASS.

### Requirement: R4. Sanitized and bounded evidence

The report SHALL contain allowlisted stage, outcome, category, source ID, language, counts, and elapsed duration only. It SHALL not export URLs, titles, cookies, tokens, headers, exception messages, response bodies, page URLs, or images. Provider calls have explicit limits; CAPTCHA/429 are not bypassed. A fail-closed verifier validates exactly one JUnit method and the stage sequence before calling the journey complete.

#### Scenario: Incomplete stage report

Given one passing AndroidJUnitRunner method with a missing or contradictory journey stage, when the verifier processes its sanitized events, it rejects full E2E acceptance rather than converting absent evidence into PASS.
