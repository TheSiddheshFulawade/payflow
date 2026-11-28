# 2026-11-28 · Session 7 · PF-11 merged, PF-12 released (v1.0.0)

- **Merged:** #17 PF-11 README + 3 animated SVGs + Mermaid + docs/DECISIONS.md (`8a4eccf`, 6/6 green, main CI
  46160492062 green). Both fresh reviewers (technical, finance) passed it; README renders on github.com.
- **PF-12:** chief lifted the 23:30 rule. Local targeted test run was stopped for low memory (not re-run; CI is the
  gate). security-auditor READY (no must-fix; 3 should-fixes listed as known issues). Fresh finance reviewer: 3
  MISLEADING fixed (time columns, recon "no breaks", SPEC C1-C5), then OK TO RELEASE.
- **State:** release PR: badge "v1.0.0", README release link, SPEC PF-12 tick, `docs/releases/v1.0.0.md`.
- **Next:** none in SPEC. Optional: SASL/ACLs for Kafka, image digests, version bump from 0.1.0-SNAPSHOT, TODO-MEs.
- **Waiting on user:** nothing. PayFlow live: v1.0.0.
- **Quiz PF-11:** why conditions with every number; checked vs proven; why an SVG in `<img>` runs no script and
  what moves it instead; what reduced motion and dark mode do, and why a frozen frame must read; why a docs guard test.
- **Quiz PF-12:** annotated vs lightweight tag; why tag the merge commit on green main, not the branch; why the
  badge said "planned" before the tag existed; what release notes must state as not proven; why not rerun CI to green.
