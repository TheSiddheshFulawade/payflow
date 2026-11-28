# PF-11 README and diagrams: say what was checked, and show it safely

Status: branch `feat/pf-11-readme`, not committed yet. Line numbers are from the working tree and may move. Files:
`README.md`, `docs/DECISIONS.md`, `docs/diagrams/*.svg`, `docs/badges/*.svg`,
`system-tests/src/test/java/com/siddheshfulawade/payflow/system/ReadmeAssetsTest.java`.

## What the change does, in plain words

There is no new service code. PF-11 is the front door of the project.

1. **The README is rewritten** around what a reader needs: a pitch, three badges, "What is checked" (six invariants),
   "Results" (a table plus the conditions), "Run it with one command", three animated diagrams, a module diagram
   (Mermaid, which GitHub draws itself) and "Key decisions".
2. **The long decision list moved** to `docs/DECISIONS.md`, grouped by task. The README keeps one line per big
   decision and links to the full entry by anchor (for example `docs/DECISIONS.md#pf-05-payment-api-and-saga`).
3. **Three diagrams are SVG files written by hand**: the happy path, compensation, and outbox plus inbox. They
   animate with CSS only (`saga-happy-path.svg:20-25`: `@keyframes on`, `@keyframes go`, and one
   `prefers-reduced-motion` rule that turns every animation off). A dark-mode block at line 6 swaps the colours.
4. **A guard test** (`ReadmeAssetsTest`) reads these files as plain text and fails the build if a diagram gets a
   script, an outside link, no title or no reduced-motion rule, if the README loses a heading, uses a hype word
   (`:119-126`), or links a file that does not exist (`:128-150`).

## The core concepts

**A number without its conditions is a claim, not a result.** The Results table says "C1: 49 s" and then, right
under it, which machine, which date, how many runs ("one run of each scenario on each machine; not a benchmark"),
what the time measures (seeding to invariant check) and what was not run (C6 was cut by SPEC section 8). A reader can
then judge the number, and nobody can accuse you of selling it. Same for reconciliation: the rows were seeded, not sent
through the saga, and the README says so.

**"Checked" is not "proven".** The README says "Passing tests show that no violation was found in those runs. They are
not a formal proof." A test can only show that the bugs it looked for are absent in the runs it did. Banks care about
this wording, because overclaiming is how an audit goes wrong. `readmeHasNoHype` bans "production-ready",
"battle-tested" and "bulletproof" so the claim cannot creep back in.

**Why an SVG in an `<img>` cannot run scripts.** On GitHub the README shows `![...](x.svg)` as an image. Browsers
treat an SVG loaded as an image as a picture only: no script runs, no outside file is fetched, no links work.
That is a safety feature, not a limit to fight. So we do not rely on JavaScript: motion is CSS `@keyframes`, which
images do allow. The test still forbids `<script`, `on...=` event attributes, `@import`, `@font-face`,
`<foreignObject>`, `javascript:` and external `href`/`url()` (`ReadmeAssetsTest:30-34, 55-63`), because the same
file opened directly in a tab is no longer sandboxed, and because a diagram that loads nothing cannot break or leak.

**Reduced motion.** Some people get dizzy or distracted by moving screens, and their system says so
(`prefers-reduced-motion: reduce`). Our rule `*{animation:none!important}` honours it. Accessibility is also the
`<title>` and `<desc>` inside each SVG, and the long alt text in the README; the test requires the first two.

**Readable when frozen.** With motion off (or in a screenshot, a PDF, a phone preview) the animation is gone, so
the picture must still tell the story. The highlight groups (`.hl`) are only an extra layer; the arrows and labels are
always drawn. `display:none` on `.hl` under reduced motion removes the highlight, and nothing else is lost.

**Dark and light.** `prefers-color-scheme: dark` lets the SVG pick its own colours through CSS variables
(`saga-happy-path.svg:6`). GitHub users switch themes, and a diagram with a fixed white background looks like a hole
in dark mode.

**Why test documentation.** Docs rot silently: a renamed file leaves a dead link, a copied diagram gains a script, a
heading is deleted. A small test turns each of those into a red build, the same way a unit test guards code. It costs
a few text checks and no browser.

## Flow: how a diagram reaches the reader

```
Reader opens README.md on GitHub
  |-- Markdown renderer sees ![alt](docs/diagrams/x.svg)
  |-- Browser fetches x.svg from the same repo (no other host)
  |-- <img> mode: scripts and outside loads are blocked
  |-- CSS inside the SVG runs:
  |     prefers-color-scheme -> picks colours
  |     prefers-reduced-motion? yes -> no animation, still picture
  |                              no  -> @keyframes highlight each step
  `-- Reader sees the diagram

Build:
  mvnw test -> ReadmeAssetsTest reads the files as text
            -> size, well-formed XML, no script, title/desc, links resolve
```

## Interview questions

1. **Why does the README put conditions next to every number?**
   A number only means something with its setting: machine, number of runs, what is timed, what is excluded. "49 s"
   from one laptop run is evidence of one thing, not a benchmark. Stating that protects you from over-claiming and
   lets the reader repeat it.

2. **What is the difference between "checked" and "proven" for a payment system?**
   Tests and chaos runs show that the invariants held in the runs we made; they cannot show every interleaving. A proof
   (or a model checker) covers all of them. So we say "no violation was found in those runs", and we name what was not
   run (C6). In a bank, saying less and being exact beats saying more.

3. **Why can't an SVG in an `<img>` run JavaScript, and what do you use instead for motion?**
   Image mode is sandboxed: no scripts, no outside requests, no interaction. CSS animation is still allowed, so we use
   `@keyframes`. The diagram then has no attack surface in the README, and the guard test keeps scripts out even for
   someone who opens the file directly.

4. **What do `prefers-reduced-motion` and `prefers-color-scheme` do, and why include both?**
   They are CSS media queries that read the user's system settings. Reduced motion stops animation for people who are
   bothered by it (an accessibility need); colour scheme adapts to dark or light. Both cost a few lines, and without
   them the diagram is unreadable in dark mode or uncomfortable for some readers.

5. **Why write a test for documentation, and what does yours check?**
   Because docs break without anyone noticing, and a reader's first impression is the README. `ReadmeAssetsTest` checks
   that each SVG is small, well-formed (parsed with DOCTYPE and external entities disabled), has a title and
   description, animates, honours reduced motion and loads nothing external; that the README has its headings in
   order, a Mermaid block and no hype; and that relative links resolve. It runs with the normal build, so a broken doc
   fails the build.

## What breaks if...

Suppose someone "improves" `outbox-inbox.svg` by adding `<style>@import url(https://fonts.example/x.css);</style>` for
a nicer font. Work out, before you run anything: (a) what GitHub does with it in `<img>` mode, (b) what happens for a
reader who opens the file directly or is offline, (c) which test fails and on which line of `ReadmeAssetsTest`, and
(d) what you would do instead for the font. Then try it on a scratch copy and compare with the test message.

## TODO-ME

`ReadmeAssetsTest` has a disabled test `everyDecisionsAnchorInReadmeExists`. The existing link test only checks that
the file exists, so a typo in `#pf-05-payment-api-and-saga` would pass. Fill it in, remove `@Disabled`, and check it by
breaking one anchor in a scratch copy of the README (not the real one) and seeing the test fail.
