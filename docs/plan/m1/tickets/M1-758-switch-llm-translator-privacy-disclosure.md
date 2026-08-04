---
id: M1-758
title: "Correct every operator-facing LLM exposure disclosure"
status: done
created: 2026-08-03
last_updated: 2026-08-04
blocked_by:
  - M1-746
  - M1-756
files_budget: 30
files_scope:
  - prod/switch-llm.sh
  - prod/scripts/4-llm.sh
  - USER_GUIDE.md
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/TranslationProvider.java
  - docs/spec/verification.md
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/SwitchLlmWiringTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java
  - docs/spec/security.md
  - SETUP_GUIDE.md
  - README.md
  - OVERVIEW.md
  - CLAUDE.md
  - docs/SPEC.md
  - docs/spec/decisions.md
  - docs/design/05-llm-and-embeddings.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    RE-EDITING WHAT M1-746 ALREADY LANDED in `docs/spec/security.md`
    (§Rate limiting and §Secrets handling). That correction stands and is
    not revisited. NOTE this clause USED to forbid touching security.md
    outright, on the reasoning that editing it "would duplicate a landed
    change" — that is false for the gaps this ticket also closes, because
    those bullets do not exist yet. REVISED at the round-1 redteam refine:
    the clause previously capped security.md at "exactly those two
    additions and nothing else". That cap is what made the first attempt
    wrong — the script is corrected AGAINST this section, so a section
    that enumerates 4 of 7 legs propagates a 4-of-7 disclosure. The file
    is now in `files_scope` for the FULL leg enumeration named in
    `acceptance`, and nothing else.
  - >-
    THE AGGREGATE SYSTEM LLM BUDGET SENTENCE, `docs/spec/security.md`
    §Rate limiting: "Periodic digests do NOT count against user-initiated
    per-group LLM budget (they are system-initiated; the aggregate system
    LLM budget is the backstop for digest cost)." NO SUCH CONTROL EXISTS
    in code — only the per-user `LlmRateCap`, the D47 per-group bucket
    (both user-initiated) and per-task concurrency semaphores, which bound
    concurrency rather than volume. Two independent M1-756 audit rounds
    declined to attribute it to that diff; it is pre-existing. It is
    recorded HERE, rather than in a ticket, because this ticket is the one
    that edits that section and its author will be standing next to the
    false sentence. Resolving it needs a DECISION first — is the sentence
    aspirational, or is a real ceiling missing? — and the answer decides
    whether it is a one-line `spec:` commit or an implementation ticket.
    Do not fix it inline here, and do not delete this note when the
    section is edited.
  - >-
    Changing which backend any task routes to, the `LLM_TASKS` list, or the
    config-writing logic — a diff that alters routing has changed the
    deployment, not the warning about it. NOTE this clause used to open
    "This is a disclosure-text correction only". That is no longer true:
    the marker retirement below is a behavioural change to the display
    render, admitted into scope by user directive (see `overrides`). The
    ROUTING prohibition stands unchanged; it is the "only" that is retired.
  - >-
    Adding a locality constraint pinning `ModelTask.TRANSLATOR` local. That
    would be a real behavioural change needing its own decision (the D54
    embedding-locality precedent is a spec-level commitment, not a default to
    extend by analogy).
acceptance:
  - >-
    The `translator` line in the Phase 4 privacy disclosure states that for a
    scope on a non-English `/lang`, the user's RAW chat message is sent to the
    remote provider — not only "translation of the bot's replies to you". After
    M1-746 the query-anchoring leg passes the D28 pre-fetch's query, which IS
    the user's message (truncated, not redacted), to `ModelTask.TRANSLATOR`.
    The current text understates this to bot-reply echo.
  - >-
    `translator` is grouped with `chat` in the loud/private tier rather than
    reading as topic-interest exposure. The script's own Phase 4 comment says
    "A wrong claim here is a security defect"; an operator deciding whether to
    switch backends must see both tasks that carry private messages.
  - >-
    The Phase 4 block comment asserting "chat carries PRIVATE user DMs" as
    the sole private-data task is corrected to match, so the next reader
    does not restore the old grouping from the comment. NOTE, corrected at
    the refine: this item also named "the header comment above
    `LLM_TASKS`", implying it carries the same claim. It does not — that
    comment says only which config families exist and what the list drives,
    and asserts nothing about private data. A pointer there is optional and
    must not restate Phase 4's rationale (two copies of a security claim is
    a drift hazard, not redundancy).
  - >-
    THE DISCLOSURE NAMES EVERY TRANSLATOR LEG. **The count is NOT four.**
    The round-1 attempt wrote "FOUR separate legs" and the round-1 redteam
    falsified it; the enumeration below is the corrected one, taken from
    every production call site that reaches `ModelTask.TRANSLATOR`, and it
    has TWO TIERS that differ in what gates them. Getting the tiering right
    is the whole point — a single flat list is what produced the false
    claim the first time.
  - >-
    TIER A — legs gated on the SCOPE's `/lang` (Provider, via
    `TranslationPipeline`; an `en` scope is a genuine no-op for these):
    (A1) the bot's chat REPLY prose, `ChatAgent:558` — pre-existing, and
    the ONLY leg the pre-M1-758 script line disclosed ("can echo your
    queries"); (A2) query anchoring, `QueryAnchorTranslator` (M1-746) —
    the user's RAW chat message on the D28 pre-fetch path, truncated, not
    redacted; (A3) display-hit headlines — `SavedCommandHandler:453,457`
    (`/saved`, M1-755), `ClusterBlockRenderer:132` (`/summary`, M1-747),
    `DigestRenderer:886` (periodic digest, M1-756, the only budgeted one);
    (A4) cluster prose + section roll-ups — `ClusterBlockRenderer:166`,
    `DigestRenderer:552,825`, `CategoryRollupGenerator:213`, UNBUDGETED,
    and reached by BOTH the scheduled digest and `/summary`, so attributing
    them to the digest alone (as round 1 did) is wrong.
  - >-
    TIER B — the leg gated on `source.language`, NOT on any scope's
    `/lang`: the Collector's `IngestTranslationWorker:274,318`. Its only
    skip arm is `"en".equals(row.language())`, the SOURCE's declared
    language (V74, the D29 operator correction path via
    `BootstrapLoader:173`); it runs `@Scheduled` on
    `infochat.llm.translator.poll-interval` with no enable flag, unattended
    and forever, and `renderPrompt:358-366` sends `{{title}}` PLUS the full
    UNTRUNCATED `{{body}}` — not a headline, the whole post. **The
    disclosure must NOT state or imply that an `/lang en` deployment sends
    nothing through `translator`.** That is the round-1 medium finding: a
    deployment whose scopes are all `en` but which configures one
    non-English source ships whole post bodies to the remote provider, and
    the round-1 text told the operator it cost them nothing. A hard
    negative that is false is worse than silence, because the operator
    acts on it.
  - >-
    NO REGRESSION-PINNING OF A DELETED DISCLOSURE. The round-1 test
    asserted `assertFalse(output.contains("translation of the bot's
    replies to you"))`, which pinned the REMOVAL of the A1 leg's only
    disclosure — the suite would have blocked restoring it. Whatever
    wording replaces that line, A1's exposure must still be named, and no
    assertion may require the absence of a still-true exposure claim.
  - >-
    SYNC THE SECTION BEFORE SYNCING THE SCRIPT. The script is corrected
    against `docs/spec/security.md` §Secrets handling, so an incomplete
    section propagates into the operator-facing text rather than being
    caught by it. Both spec gaps below are therefore fixed in THIS
    ticket, ahead of the script edit, not left for a later one.
  - >-
    SPEC GAP 1 — §Secrets handling omits the `/summary` display-hit leg
    (M1-747). It is the same data class the section already discloses for
    the digest (a `DisplayHeadline` output reaching
    `ModelTask.TRANSLATOR`), differing only in being user-initiated
    rather than scheduled. Verified absent on main at `2698edbf`: the
    section carries exactly three `translator` bullets, none of them
    `/summary`. Add the missing bullet in the established shape, then
    name the leg in the script.
  - >-
    SPEC GAP 2 — `infochat.digest.translation-max-per-render` has no
    §Rate limiting entry, unlike the M1-746 and M1-755 translator legs
    which each got one. M1-756 shipped the control
    (`application.properties:631`, default 5) and documented it only
    under §Secrets handling (`security.md:1905`). Code is therefore
    STRICTER than spec, which is the dangerous direction: removing or
    widening the budget later would need no spec amendment and would
    trip no review. Add the §Rate limiting entry matching the shape the
    `/saved` display-hit entry already uses. Deferred from M1-756 rather
    than found independently — it was raised at that ticket's round-3
    audit and left rather than reopen a CLEAN gate for one paragraph.
  - >-
    SPEC GAP 3 (added at the redteam refine) — §Secrets handling must
    enumerate the A1 reply-prose leg and the TIER B ingest leg. Today the
    section closes with "The enumeration in this section is still
    incomplete for the unattended legs named above — they predate this
    entry and are not M1-756's to document." That sentence is the standing
    admission of the gap, and this ticket is the one that inherits it: the
    script is corrected AGAINST this section, so leaving the section at 4
    of 7 guarantees the script lands at 4 of 7 too. Enumerate both, then
    delete the now-false incompleteness sentence — but do NOT let deleting
    it become a claim of completeness the enumeration does not support.
  - >-
    SPEC GAP 4 (added at the redteam refine) — `SETUP_GUIDE.md`
    §"Switching your AI backend later" (:563-567) still reads "loudest for
    **chat** (it sends your private messages), versus the ingest tasks
    (`security`/`tagger`/`entity`/`classifier`)", with `translator` in
    NEITHER tier. `docs/spec/security.md` §Secrets handling explicitly
    designates that section as the operator-facing description of this
    disclosure ("see `SETUP_GUIDE.md` §'Switching your AI backend
    later'"), and it is what an operator reads BEFORE running the
    switcher — i.e. before Phase 3 rewrites `application.properties`. It
    is therefore the earlier of the two consent surfaces, and correcting
    only the later one leaves the decision being made against stale text.
    Bring its tiering into line with the script. Pre-existing, not caused
    by this diff; folded in here (files_budget 3 -> 4) rather than
    deferred, because shipping a corrected script beside an uncorrected
    pre-read is the same "names some, not all" defect one level up.
  - >-
    THE UNIT OF WORK IS THE CENSUS IN `## Census`, NOT THE SWITCHER.
    Added at the round-2 refine, and it is the reason for the third pass.
    Round 1 found one uncovered surface, round 2 found two more plus a
    third as out-of-model — each pass returning a DIFFERENT file set,
    which is the documented signal that the ticket was scoped by artifact
    ("the switcher's text") when the real unit is a CLASS ("every place
    that tells an operator what leaves their machine"). Every row in
    `## Census` must be dispositioned; a row left FIXED-elsewhere or
    deferred must say so IN the table, not silently.
  - >-
    THE INSTALL-TIME DISCLOSURE (`prod/scripts/4-llm.sh:485-500`) IS
    CORRECTED, and it takes priority over the switcher's. It prints
    BEFORE the operator types the remote URL and key, by its own design
    comment; the switcher's prints only to operators who later re-route.
    An operator who picks `remote` during `prod/setup.sh` and never runs
    `prod/switch-llm.sh` sees THIS text and no other. Today it carries
    the exact pre-M1-746 line this ticket deleted from the switcher —
    "translator — translation of the bot's replies; exposes the
    bot-reply text (can echo your queries)" — in the tier the same block
    defines as "not private user data", with `chat` as the sole `!!`
    entry. Bring it to the same two-fact shape as the switcher's, with
    the same prohibition on implying an `en`-only deployment is exempt.
  - >-
    THE SWITCHER-IS-PER-TASK CLAIM IS CORRECTED WHEREVER IT APPEARS.
    `SETUP_GUIDE.md:556-558` says the switcher "asks, for each AI task,
    which backend to use ... Press Enter to keep a task as-is", and
    `prod/scripts/4-llm.sh:482` says it "can later move generative tasks
    back to local one at a time". Both are false since M1-603/D56:
    `prod/switch-llm.sh:162` prompts ONCE ("LLM backend for ALL
    generative tasks") and Phase 3 writes the shared default every task
    inherits, sweeping per-task pins behind the M1-605 typed-consent
    gate. This matters beyond tidiness — an operator who believes it
    will run the switcher intending to leave `chat` and `translator`
    local and will route BOTH remote, which is precisely the outcome
    this ticket exists to prevent. NOTE the round-2 diff ADDED a
    contradicting sentence at `SETUP_GUIDE.md:603-605` relying on the
    true behaviour; that contradiction is self-inflicted and is fixed
    here, and any surviving mitigation advice must also say that a
    hand-pinned per-task route is SWEPT by a later switch unless the
    operator declines the consent prompt.
  - >-
    THE INSTALL-TIME DOC SURFACES ARE CORRECTED: `SETUP_GUIDE.md:287`
    ("Costs money; your prompts go to that provider") and `:315-319`
    ("Only the generative tasks — chat, summaries, tagging — use the
    remote provider"), which names three task families where seven
    exist and omits `translator` and the unattended ingest leg entirely;
    and `README.md:215-221`, which enumerates "public post bodies for
    the ingest tasks and the request-time `summarizer`, and your private
    chat messages if you route chat" — omitting `translator` — and then
    asserts "the setup wizard spells out exactly what each task exposes",
    which is a claim ABOUT the text in row 1 and is false until row 1
    is fixed. Minimal edits: these are orientation prose, not the
    disclosure itself; they must stop being WRONG without growing into
    a fourth copy of the leg list.
  - >-
    ONE AUTHORITY, N RENDERINGS. `docs/spec/security.md` §Secrets
    handling is the single source; the two runtime disclosures and the
    three doc surfaces are renderings of it at different lengths. No
    rendering may state a fact absent from the section, and none may
    carry its own leg enumeration except `SETUP_GUIDE.md` §"Switching
    your AI backend later", which is the designated long form the short
    renderings point at. This is what stops the next reader from
    "improving" one copy into a sixth divergent claim.
  - >-
    THE PIN-SURVIVAL CLAIM IS TRUE OR ABSENT. Added at the round-3 refine
    because the round-3 diff INTRODUCED it and it is false. Both surfaces
    said a later switch sweeps hand-pinned routes "unless you decline its
    consent prompt". There is no such prompt: `prod/switch-llm.sh` has five
    `read` prompts (:162 backend, :188 dialect, :196/201/204 base-url, :225
    per-task model, :330 api-key), none about pins. The M1-605 gate (:301)
    fires ONLY on a bare-Enter answer over a pinned config and then `exit 0`
    — so "declining" means not switching at all. On a typed backend (which
    the script itself calls the consent, :299) Phase 3 (:352-355) deletes
    every per-task base-url/api-key unconditionally and merely ANNOUNCES the
    swept list after the fact. The operator CANNOT both switch and keep a
    pin. Say that: every switch that proceeds removes the pins, the only
    guard is that a bare-Enter run over a pinned config refuses and writes
    nothing, so re-apply the pin after any switch. Inventing a safeguard is
    worse than omitting one — the operator relies on it.
  - >-
    NO assertFalse MAY TARGET A TRUE EXPOSURE CLAIM — RESTATED because the
    round-3 diff violated the round-1 item that already said it, in the
    sibling test file. `RemoteLlmWiringTest` asserted the absence of
    "translation of the bot's replies", which is a fragment of the A1
    reply-prose leg's natural phrasing — a live, spec-enumerated exposure
    (`ChatAgent:558`). A follow-up trying to SPELL OUT that leg would fail
    the build. Retarget onto what is actually false: the pre-M1-746 tier
    placement (`-  translator`, the public/topic-interest marker) or the
    phrase "exposes the bot-reply text (can echo your queries)".
  - >-
    PER-TASK PARITY BETWEEN THE TWO RUNTIME DISCLOSURES. The round-3 diff
    added a comment at `prod/scripts/4-llm.sh` asserting the install block
    "must not be the weaker of the two", while its `summarizer` line stayed
    weaker than `prod/switch-llm.sh:429`: install says only "summaries of
    the posts you query", the switcher says "ingest-time abstracts of EVERY
    long fetched PUBLIC post (BodySummaryWorker) plus summaries of the posts
    you query". The omitted leg is unattended and whole-body
    (`BodySummaryWorker:202` `@Scheduled`, `:231`/`:276`
    `ModelTask.SUMMARIZER`) — the same shape this ticket surfaces for
    translator. Every task's line must be at least as strong at install time
    as at switch time; a comment claiming parity that the block does not
    hold is worse than no comment.
  - >-
    CENSUS ROW 8 — `OVERVIEW.md:288-293` §7 "Privacy note" still reads
    "public post bodies for ingest tasks; private chat messages if chat is
    routed remotely": `translator` absent, private-data exposure gated on
    `chat` alone. Identical to the defect fixed at `README.md` (row 7) and
    `SETUP_GUIDE.md:287` (row 6). It is reader-facing (linked from
    `README.md:178` as the architecture entry point) and is NOT covered by
    the census carve-out, which excludes only `docs/design/**`,
    `docs/plan/**` and `security.md` itself.
  - >-
    THE CENSUS ENUMERATION RULE IS FIXED, NOT JUST ITS TABLE. Row 8 was
    invisible BY CONSTRUCTION: the recorded second grep is path-restricted
    (`prod/ SETUP_GUIDE.md README.md`) and phrase-based, while the section
    header claims "by invocation, not by phrase". Fixing only the row would
    leave the next reader re-running a grep that cannot find what it missed.
    Replace it with a capability-first rule: select every maintained
    reader-facing `*.md` (repo root + `docs/`, excluding `docs/design/`,
    `docs/plan/`, `docs/process/`, `.claude/`) that mentions remote/cloud
    LLM routing, THEN filter to those making an exposure claim. Record that
    this corrected rule was run and returned exactly one newly-wrong surface
    (row 8) — `SECURITY.md` and `DEVELOPER.md` mention remote routing but
    assert no exposure; `SETUP_GUIDE.md:975` ("your prompts and post content
    go to that provider") is vague but not false.
  - >-
    `docs/spec/security.md:1904` — the sentence describing what the switcher
    prints still reads "`chat` (private user messages) flagged loudest, the
    ingest tasks ... as topic-interest exposure", placing `translator` in
    neither tier. Judged illustrative at the round-1 self-check and left
    alone; that judgement no longer holds now that BOTH runtime disclosures
    put `translator` in the `!!` tier, so the sentence describes an output
    that no longer exists. In `files_scope` already.
  - >-
    THE FABRICATED SAFEGUARD IS GONE FROM COMMENTS TOO, not only from the
    printed text. The round-3 fix corrected the echo lines in
    `prod/scripts/4-llm.sh` but left the block comment 40 lines above still
    asserting a switch "sweeps such pins unless the operator declines the
    consent prompt", so the file contradicted itself and the round-3
    regression guard could not see it — every assertion reads captured
    stdout, and a comment never reaches stdout. A claim about what a
    control does is wrong in a comment for the same reason it is wrong in
    output: the next editor restores it from there.
  - >-
    THE "NEVER TRANSLATED" INVARIANT IS CORRECTED WHEREVER ASSERTED. This
    is a DIFFERENT defect class from the disclosure understatement the rest
    of this ticket fixes — not "the warning is too weak" but "a stated
    privacy guarantee is false" — and it is the upstream source the
    disclosures were drifting toward. D29 is precise: a non-English source
    post IS translated at ingest into an ADDITIONAL derived field, and what
    is guaranteed is that the stored body is never *rewritten*.
    `docs/spec/verification.md:466` already records that distinction
    explicitly ("the storage guarantee is what 'never rewritten' means").
    Three copies state the stronger, false form and are corrected to the
    D29 wording: `CLAUDE.md:50` and `docs/SPEC.md:258` ("Source post bodies
    are never translated"), and `README.md:214` ("source post bodies are
    never sent to a translator" — under a **Private by construction**
    heading, i.e. asserted as a guarantee, in a file already in
    `files_scope` and already surfaced by the census; missed at round 3
    because the census filter was a phrase grep rather than a read).
  - >-
    THE SIX-TASK POST-BODY ENUMERATION IS CORRECTED IN BOTH PLACES IT
    APPEARS. `docs/spec/decisions.md` D57 states that "the post body of a
    `security` / `tagger` / `entity` / `classifier` / `summarizer` / `chat`
    call is sent to the remote provider" — six tasks, omitting `translator`,
    the seventh and the one that sends whole untruncated bodies
    (`IngestTranslationWorker:274/318`, `renderPrompt:358-366`). The same
    row's own opening correctly lists all seven, so the parenthetical
    contradicts its own decision. D57 is REPORTING
    `docs/design/05-llm-and-embeddings.md:1102`, which carries the identical
    six-task list, so correcting only D57 would leave it citing a source
    that says something else — a fresh contradiction of exactly the kind
    this ticket keeps removing. Both are corrected. This is the one place
    the ticket enters `docs/design/**`, and only because a spec row cites
    that exact sentence; the census's design-tier exclusion otherwise
    stands. AMENDED at round 5: this is no longer the ONLY place the
    ticket enters `docs/design/**`. The all-file-types invariant sweep
    (population (c)) also returns
    `docs/design/05-llm-and-embeddings.md:932-934`, whose "Never
    translated: post bodies / post titles" list is a third copy of the
    row-10 false invariant — in the same file, and reachable by neither
    the census nor the auditor. The exclusion stands for the EXPOSURE
    class only; the invariant class is enumerated over every tracked
    file type without path exclusions, which is the whole point of
    population (c).
  - >-
    THE CENSUS FILTER IS A READ, NOT A GREP — restated because the rule has
    now failed the SAME way twice. Round 2's rule was path-restricted and
    phrase-based; round 3 replaced it with "select by capability, then
    filter to those making an exposure claim", and the filter was AGAIN run
    as a phrase grep, which is why `docs/spec/decisions.md` D57 and
    `README.md:214` were both missed (D57 says "post body" singular where
    the grep had "post bodies"; README's line is phrased as a negative
    guarantee, which no positive-exposure phrase matches). A phrase grep
    cannot enumerate a class whose members are defined by MEANING. Step 2
    is: open every file step 1 selected and read its remote-routing and
    translation claims. Record that the read-based run was performed and
    what it returned.
  - >-
    THE INVARIANT CLASS IS ENUMERATED OVER ALL FILE TYPES, NOT `*.md`.
    Added at the round-5 refine, and it is the reason rounds 4 and 5 both
    found members of the same class. The census's population (b) is
    `*.md` and excludes `docs/design/**`; both round-5 surfaces sit
    exactly in those blind spots, and neither is reachable by any
    rendering of the existing rule. `TranslationProvider.java` is Java —
    unreachable by an `*.md` population — and it is the UPSTREAM text the
    three copies row 10 corrected are verbatim paraphrases of, so the
    census fixed the copies and left the original they were copied from.
    `docs/design/05-llm-and-embeddings.md:932` is excluded by the census
    AND unreadable by the auditor (whose role bars design notes), so no
    participant in the loop could see it. The two classes need different
    enumeration methods and the ticket had only one: an EXPOSURE claim
    ("what does routing task X remote send?") is defined by meaning and
    needs a read, which is why a phrase grep failed it four times; the
    INVARIANT claim ("post text is never translated") is a phrase family
    and greps reliably — across every tracked file type, javadoc and
    design notes included. Record the all-file-types run and its result.
  - >-
    CENSUS ROW 12 — `USER_GUIDE.md:124` and `:298-299` assert the false
    strong form twice ("original posts aren't translated"; "The original
    posts themselves are never translated — only the bot's own wording").
    This is the only end-user-facing document in the repo and the only
    surface a non-operator ever reads, so it is the one place the
    corrected operator disclosures cannot reach. It was not missed but
    MIS-DISPOSITIONED: the round-4 read-based run named it by hand and
    cleared it as asserting "no exposure or invariant". The `/lang` row
    additionally still says "English and Czech in v1", stale since
    `es`/`ru`/`tr` shipped — corrected in the same line rather than left
    as a known-false neighbour.
  - >-
    CENSUS ROW 13 — `TranslationProvider.java:7-18`, the SPI javadoc,
    states BOTH false claims: "Translates <strong>only</strong> the
    LLM-authored strings the bot itself produces" and "<strong>Source
    post bodies are NEVER translated.</strong> ... Provider surfaces them
    as-is". Both are false about this SPI's own live call path —
    `TranslationPipeline.runForDisplayHit` hands
    `translationProvider.translate` a `DisplayHeadline`, i.e. a
    source-authored title or a bounded body prefix. Correcting the
    renderings while the authority they paraphrase keeps the retired form
    is the "ONE AUTHORITY, N RENDERINGS" item applied backwards.
  - >-
    `docs/spec/verification.md:452-457` — the D29 presentation-path spy
    requirement's first clause ("no call argument equals or contains the
    body of any `post` row; only presentation strings ... reach it") is
    false of the shipped display leg, and the same bullet's next four
    sentences state the corrected doctrine, so the file contradicts
    itself. NO TEST implements the retired clause (the D29 storage
    property is pinned on the ingest path instead,
    `IngestTranslationWorkerIT:219-221`, byte-identical title and body),
    so this is a spec-text correction with no suite impact — verified
    before editing rather than assumed. Restate the requirement as what
    the path must actually prove: arguments are bot-authored prose or a
    `DisplayHeadline`, nothing writes back to the post row, and no
    UNBOUNDED body reaches the translator. Raised as round-5 out-of-model
    and fixed here rather than deferred, because a spec-tier requirement
    the shipped code violates is the same false-guarantee class as rows
    10, 12 and 13.
  - >-
    THE MACHINE-TRANSLATION MARKER IS RETIRED, NOT RE-WORDED. Admitted into
    scope by user directive after round 8 (see `overrides`); rounds 5-9 each
    found a different false or overclaimed sentence about the marker, and the
    marker itself is why. It was a per-language CONSTANT with no nonce
    (`reply.translation.hit_marker`, one value per bundle) appended to a
    field that also carries publisher-authored text, so any publisher could
    put the literal in a title and have it render byte-identically to a
    genuine one — `LlmOutputSanitizer`'s closed list covers privileged
    commands and markdown links, not bracketed prose. A label that asserts
    "the bot wrote this" and is trivially forgeable by the untrusted party
    is worse than no label: it is a provenance claim the mechanism cannot
    back. Delete `BundleKeys.REPLY_TRANSLATION_HIT_MARKER` and its value from
    ALL FIVE bundles (en/cs/es/ru/tr — bundle parity is a build-enforced
    invariant, so a key removed from one must go from every one).
  - >-
    THE DISPLAY-HIT RENDER CARRIES THE ORIGINAL, NOT A LABEL.
    `TranslationPipeline.finishDisplayHit` returns the bounded translation
    followed by the ORIGINAL headline on a bracketed line beneath it. The
    original is the leg's own input — already bounded, flattened and
    sanitized by `DisplayHeadline` — so no new transform and no bundle
    lookup is involved, and the bracketed line makes no claim about WHO
    produced the translation: the reader compares the two lines themselves.
    A translation byte-identical to its input is still delivered unchanged
    and unbracketed — there is nothing to attribute. The implication runs
    one way only and no surface may state the converse: a bracket means the
    line above is this leg's translation, but an UNbracketed line does not
    mean the text is in the reader's language (the entry guard returns the
    input untouched on an absent or malformed source language, and a caller
    whose per-render budget is spent skips the leg entirely).
  - >-
    THE CALLERS ABSORB THE TWO-LINE SHAPE. A translated headline is now two
    lines, so `DigestRenderer` puts the URL on its own line beneath them
    (`headline.contains("\n") ? "\n" : "  "`) while an untranslated headline
    keeps the inline `headline  url` form. Every comment and javadoc that
    said a skipped or budget-exhausted row renders "unmarked" says
    "unbracketed" instead — including `application.properties`'
    `infochat.digest.translation-max-per-render` note, which is the operator's
    copy of that behaviour.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/SwitchLlmWiringTest.java
      — asserts the remote-backend disclosure names the translator task's
      private-message exposure and BOTH tiers: at minimum one assertion per
      Tier A leg class (reply prose, raw query, display-hit headlines,
      unbudgeted prose/roll-ups) plus one pinning the Tier B ingest leg as
      NOT scope-gated and as carrying full post bodies. The Tier B
      assertion is the regression guard that matters most — it is the claim
      round 1 got backwards.
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java
      — the install-time disclosure (census row 1) gets the same regression
      guard the switcher's has: it already drives 4-llm.sh's `remote` branch,
      which is what prints the block, so the assertions attach there rather
      than in a new harness. Pin both facts and the en-not-exempt claim.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/translation/DisplayHitTranslationTest.java
      — the display-hit leg's own tests move from asserting the appended
      marker to asserting the two-line `translation\n[original]` shape,
      including the byte-identical-translation case (delivered unchanged and
      unbracketed) and the no-op legs (en scope, same-language, absent or
      malformed source language) which stay single-line.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
      — pins the URL placement fork: a translated headline puts the URL on
      its own line, an untranslated one keeps the inline two-space form, and
      rows past `translation-max-per-render` stay unbracketed.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
      and .../SavedCommandHandlerTest.java — the `/summary` and `/saved`
      display-hit sites assert the same bracketed shape; the degraded-cluster
      and budget-exhausted arms assert the headline renders untranslated and
      unbracketed.
  preserves:
    - >-
      NO assertion may pin the ABSENCE of a still-true exposure claim.
      Round 1's `assertFalse(output.contains("translation of the bot's
      replies to you"))` made the suite enforce the A1 gap; an assertFalse
      here is legitimate only against wording that is actually false (e.g.
      a blanket "sends nothing" claim), never against a leg that still
      exists.
    - >-
      Every existing SwitchLlmWiringTest assertion, including the positional
      stdin sequence (adding a task to `LLM_TASKS` shifts it by one slot —
      this ticket adds no task, so the sequence is unchanged).
    - >-
      The per-task shape of the disclosure. The text must stay per-task and
      must never become a blanket "privacy sacrificed" line.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
decision_refs:
  - D58
  - D28
  - D56
reviews:
  - round: 1
    date: 2026-08-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 41
      added: 3498
      removed: 221
overrides:
  - date: 2026-08-04
    kind: scope-widening
    authority: user directive, in-session, after the round-8 audit
    what: |
      `files_scope` 15 -> 30 and `files_budget` 15 -> 30, admitting the
      Provider display-hit render surface: TranslationPipeline, BundleKeys,
      ClusterBlockRenderer, SavedCommandHandler, DigestRenderer,
      application.properties, all five bundles, and the four provider tests
      that pin those renders. `out_of_scope` clause 3 amended: its ROUTING
      prohibition stands, its "disclosure-text correction only" opening is
      retired.
    why: |
      Rounds 5-9 of the red-team each found a different false or overclaimed
      sentence about the machine-translation marker — false invariant, false
      render detail, unreachable literal, overclaimed trust property, stale
      promise — and every round fixed the sentence rather than the marker.
      The user's decision is that the marker itself is the defect: a
      per-language CONSTANT asserting bot authorship, forgeable by any
      publisher because the sanitizer's closed list does not cover bracketed
      prose. Retiring it removes the claim the text kept getting wrong.
      Recorded here rather than routed through `escalate -> refine` at the
      user's explicit instruction; the entry exists so the widening is not
      silent.
    note: |
      COLLISION, flagged and not resolved by this ticket: M1-759
      ("Anchor-first headline display: reader-language line, bracketed
      original beneath") is `pending` with a locked worktree one commit
      ahead of main, and its `files_scope` covers the same Provider files
      with its own divergent implementation of this render. M1-759 needs
      rescoping or abandoning before it is started, or whichever branch
      merges second conflicts.
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-04
    category: INFO-LEAK
    severity: medium
    promise: |
      docs/spec/security.md §Secrets handling — the switcher "prints a per-task
      privacy disclosure naming exactly which generative tasks now call a remote
      provider and what each exposes". The same section names the missing
      consumer explicitly: "the Collector's ingest translation worker is a
      separate continuously-scheduled consumer of the same task."
    gap: |
      The added disclosure gates the ENTIRE translator exposure on a scope's
      /lang and asserts a hard negative — "an /lang en scope is a strict no-op
      and sends nothing. Where it is set, FOUR separate legs reach the remote
      provider". False: IngestTranslationWorker is gated on `source.language`
      (a per-SOURCE column, V74), never on any scope's /lang; its only skip arm
      is `if ("en".equals(row.language()))`, and its prompt interpolates
      {{title}} AND the full untruncated {{body}}, on a @Scheduled tick. The
      diff also deletes the old `translator)` case arm, so the loop now prints
      nothing for translator and the ingest leg is named on no line at all. The
      added test pins the false wording (asserts "strict no-op" present).
    repro: |
      All scopes on default /lang en, at least one non-English source
      configured. Operator runs prod/switch-llm.sh, picks remote, reads the
      disclosure: translator "sends nothing" for en scopes. Operator accepts
      the switch. From the next Collector tick on, the full title and body of
      every non-English post ships to the remote endpoint, continuously,
      undisclosed.
    suggested_fix_class: other
  - date: 2026-08-04
    category: INFO-LEAK
    severity: low
    promise: |
      docs/spec/security.md §Secrets handling — the disclosure names "what each
      exposes"; §Rate limiting treats the presentation-prose legs as part of
      this task's surface.
    gap: |
      The diff asserts completeness at FOUR legs while removing the only
      disclosure of a fifth: the deleted line read "translator — translation of
      the bot's replies to you; exposes the bot-reply text (which can echo your
      queries)". That leg is still live — ChatAgent:558
      `translationPipeline.run(sanitized, scopeLanguage)` translates chat-mode
      REPLY prose, which quotes and paraphrases the user's private message.
      DigestRenderer:825 / CategoryRollupGenerator:213 run the same call for
      cluster prose and roll-ups, and /summary renders through
      DigestRenderer.forSummaryRendering, so the diff's attribution of those to
      the DIGEST alone is also incomplete. The added test pins the removal
      (assertFalse on the old wording).
    repro: |
      Operator wants private conversation local and cheap tasks remote. They
      read the loud tier, see translator's four legs (query, saved headlines,
      /summary headlines, digest headlines), and route chat local / translator
      remote. Every reply the bot composes in a cs/ru/es/tr scope is then sent
      verbatim to the remote provider, disclosing the conversation the operator
      explicitly kept local. The pre-diff text warned about exactly this.
    suggested_fix_class: other
  - date: 2026-08-04
    category: INFO-LEAK
    severity: low
    promise: |
      docs/spec/security.md §Secrets handling designates SETUP_GUIDE.md
      §"Switching your AI backend later" as the operator-facing description of
      this disclosure, as amended by the M1-746 bullet ("routing `translator`
      to a remote provider therefore exposes private user messages, exactly as
      `chat` does").
    gap: |
      SETUP_GUIDE.md:563-567 still describes the pre-M1-746 world — "loudest
      for chat (it sends your private messages), versus the ingest tasks
      (security/tagger/entity/classifier)". translator appears in neither tier
      and chat is still presented as the sole private-message task. No
      SETUP_GUIDE.md hunk exists in the diff; the file is outside files_scope.
    repro: |
      An operator evaluating a cloud move reads SETUP_GUIDE.md §"Switching your
      AI backend later" BEFORE running the switcher — that is what the section
      is for. It tells them exactly one task carries private data. They accept
      and proceed. The corrected statement appears only in the script's Phase 4
      output, which prints AFTER Phase 3 has already rewritten
      application.properties.
    suggested_fix_class: other
  - date: 2026-08-04
    round: 9
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §Threat model — "**LLMs** (local or remote) are black
      boxes that can be coaxed into emitting attacker-chosen output"; and
      §Rate limiting as this diff writes it, which names the BRACKET as the
      shipped render-side signal ("rows beyond the budget render untranslated,
      unbracketed", security.md:1670 / :1696).
    gap: |
      The round-8 rework retired the machine-translation marker entirely
      (TranslationPipeline:346-354 now returns `bounded + "\n[" + original +
      "]"`; REPLY_TRANSLATION_HIT_MARKER and `reply.translation.hit_marker`
      deleted from BundleKeys and all five bundles — a repo-wide grep over
      **/*.java and **/*.properties returns nothing). USER_GUIDE.md:124, an
      ADDED line in this diff, still tells end users "translated headlines are
      usually tagged, in the language you picked". Nothing is tagged, and the
      one line that does appear (the bracketed original) is in the SOURCE
      language. USER_GUIDE.md:298-311, added by the same diff, states the
      correct rule; the two are mutually exclusive and :124 — in the command
      reference — is the one a reader reaches first.
    repro: |
      Scope on /lang cs. An attacker publishes to a subscribed feed a title
      that instructs the translator rather than reading as prose; per §Threat
      model the model can be coaxed into emitting attacker-chosen output, so
      the primary line becomes attacker-chosen Czech. The digest broadcasts
      `<attacker Czech>` / `[innocuous original]` / URL. A member who learned
      /lang from USER_GUIDE.md:124 looks for a tag in their language, finds
      none anywhere (none exists), and reads the Czech line as the publisher's
      own headline. Nothing is malformed, so no sanitizer, audit row or WARN
      fires.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-08-04
    verdict: FINDINGS
    round: 9
    base: 29aa59afbd80e13df6265e6934c77f1a4a06ffe6
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-758-2026-08-04-r9.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Round 9, re-audit of the post-r8 rework. That rework retired the
      machine-translation marker at its root, which closes r8's forgeable-tag
      finding. No prior finding re-opened. The one low is the stale half of
      that removal: USER_GUIDE.md:124 (added by this diff) still promises a
      tag that no longer exists anywhere in code or bundles, and contradicts
      USER_GUIDE.md:298-311 in the same file. Verified in-tree before
      accepting (grep for hit_marker/HIT_MARKER returns nothing;
      finishDisplayHit reads back as the bracketed-original form).

      Three out-of-model items, one of which needs a user decision: the
      bracketed render now delivers the publisher's original and its
      translation as TWO separately-sanitized strings joined by a bare "\n"
      (DisplayHeadline.of for the original, sanitizer-2 for the translation),
      where before only one string reached the reader on this leg — so a
      closed-list command word in one and its flag in the other is neither
      redacted nor audited, while `split("\\s+")` still dispatches the pasted
      pair. Argued down to advisory (dispatch needs is_admin=true; the only
      flag-bearing payoff, `/list-sources --all`, is already admin-visible),
      but security.md's stated justification for the split-unit residual —
      that merging would let a co-clustered attacker delete a third party's
      post — does not apply here, since both strings are the same author's
      field and its translation. The other two: a code comment
      (TranslationPipeline:337-338) whose "an unbracketed line ALWAYS means
      the text is already in the reader's language" is false on four
      degradation arms the user-facing text enumerates correctly, and
      README.md:216 still calling the summarizer request-time.
  - date: 2026-08-04
    verdict: FINDINGS
    round: 8
    base: 29aa59afbd80e13df6265e6934c77f1a4a06ffe6
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-758-2026-08-04-r8.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      No prior finding re-opened. One low, and a DIFFERENT class from rounds
      5-7: not "this claim is false" but "this claim asserts a trust property
      the mechanism does not have". The r7 text told users the tag meant the
      bot wrote the line; the marker is a per-language CONSTANT with no nonce
      (unlike `[REDACTED:<id>]`, whose randomization the spec justifies as
      anti-forgery) and the sanitizer does not strip it from publisher text,
      so an untrusted title can carry it verbatim. The positional half
      ("at the end of the line") was also wrong on 2 of 3 render sites —
      `/saved` puts the tag mid-template, the digest appends the URL after it.

      Fixed as text: the tag is now a hint rather than a guarantee in BOTH
      directions, and design 05 records the constant/no-nonce/not-stripped
      property so a provenance guarantee cannot be re-derived from it.

      NOT FIXED, deliberately: giving the marker a nonce is a behavioural
      change to code M1-747/755/756 own, which this ticket's out_of_scope
      bars. Needs its own ticket if the user wants the property to actually
      hold rather than merely to be described accurately.
  - date: 2026-08-04
    verdict: FINDINGS
    round: 7
    base: 29aa59afbd80e13df6265e6934c77f1a4a06ffe6
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-758-2026-08-04-r7.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      No prior finding re-opened; severity down to a single low. But it is
      the THIRD consecutive round where the previous round's fix introduced
      a new false claim: r6's remediation named `[machine translated]` as
      the tag readers look for, and that is the one marker value this leg
      can never emit — `finishDisplayHit` resolves the marker in the SCOPE's
      language and the leg short-circuits for `en`, so the reachable values
      are the cs/es/ru/tr bundle strings. It appeared in a worked example
      whose own command is `/lang cs`.

      THE PATTERN, which is the real deliverable of rounds 5-7: every
      remediation has been MORE SPECIFIC than the text it replaced, and
      specificity is where the falsity keeps landing — a vague-and-false
      invariant was replaced by a specific render claim (false), which was
      replaced by a specific marker literal (false). The round-7 fix moves
      deliberately back DOWN the specificity ladder: name the bundle key
      and the resolution rule, not a literal that a bundle edit or a new
      language would falsify. Restated as a rule the next author can apply:
      prefer the claim that stays true under foreseeable change, and never
      quote a user-visible literal from a localized resource.
  - date: 2026-08-04
    verdict: FINDINGS
    round: 6
    base: 29aa59afbd80e13df6265e6934c77f1a4a06ffe6
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-758-2026-08-04-r6.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      All THIRTEEN prior findings closed; the ModelTask.TRANSLATOR call-site
      enumeration re-derived and VERIFIED TRUE a third time. Both new
      findings were introduced by the round-5 remediation and share ONE
      cause: text rendered from `docs/spec/llm.md` §D29 display-leg
      amendment without checking that section against the code. Verified
      in-branch before accepting — `finishDisplayHit` returns
      `bounded + " " + marker` (translation REPLACES the headline; the only
      bracket is the marker, same line), and `runForDisplayHit` passes
      `Locale.of(sourceLanguage)` with zero Provider reads of
      `title_en`/`body_en`. Both remediated to the SHIPPED render.

      The recurrence lesson is now explicit and is the one this ticket kept
      re-learning at a different altitude each round: rendering from an
      authority does not transfer the authority's correctness. Rounds 1-5
      were "which surfaces did the census miss"; round 6 is "the source I
      rendered from was itself unverified". §"ONE AUTHORITY, N RENDERINGS"
      needs the complement — a rendering must be checked against CODE, not
      only against its authority.

      OUT-OF-MODEL 1 re-dispositioned: llm.md:303-317 is a TARGET-state
      spec, not a defect — M1-759 (pending) implements exactly that render.
      Its files_scope carries no docs, so the USER_GUIDE.md and design-05
      text corrected here goes stale when it lands; flagged to the user
      rather than edited, since M1-759 has an active worktree.
  - date: 2026-08-04
    verdict: FINDINGS
    round: 5
    base: 29aa59afbd80e13df6265e6934c77f1a4a06ffe6
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-758-2026-08-04-r5.md
    findings_count: 2
    out_of_model_count: 6
    note: |
      All ELEVEN prior findings re-verified closed, and structural claim 2
      — that security.md §Secrets handling enumerates every production
      ModelTask.TRANSLATOR call site — independently RE-DERIVED from the
      call graph and VERIFIED TRUE for the second time by a second
      auditor. Structural claim 1 (the census) FALSIFIED: its round-4
      read-based run named USER_GUIDE.md by hand and cleared it, while the
      file asserts the false strong form twice. Both findings are in the
      false-INVARIANT class row 10 opened, not the weak-disclosure class
      the ticket started as.

      ROOT CAUSE, and the reason this round refines rather than
      decomposes: the ticket carries TWO defect classes and had ONE
      enumeration method. An exposure claim is defined by meaning and
      needs a read (settled at round 4, cost rounds 2-4). An invariant
      claim is a phrase family that greps reliably — but population (b)
      is `*.md`, so BOTH round-5 surfaces were unreachable by
      construction: TranslationProvider.java is Java (and is the upstream
      text row 10's three copies paraphrase, so the census fixed the
      copies and left the original), and design 05:932 is excluded by the
      census AND unreadable by the auditor. Population (c) added — all
      tracked file types, no path exclusions — and run twice with
      differently-shaped patterns, returning the same closed set.

      files_budget 12 -> 15. The verdict file existed only in `target/`
      (gitignored, wiped by `mvn clean`) and was copied into
      docs/plan/m1/redteam/ before it could be lost.
  - date: 2026-08-04
    verdict: FINDINGS
    round: 4
    base: 29aa59afbd80e13df6265e6934c77f1a4a06ffe6
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-758-2026-08-04-r4.md
    findings_count: 2
    out_of_model_count: 6
    note: |
      All NINE prior findings re-verified closed. Two independent
      spot-checks PASSED: spec completeness (the diff's own new claim that
      security.md enumerates every production ModelTask.TRANSLATOR call
      site verified TRUE against the call graph) and census population (a)
      (PRIVACY DISCLOSURE appears in exactly two shell scripts). Both new
      findings are low and both real: the fabricated pin-survival safeguard
      survived in a block comment the stdout-only test cannot see, and the
      census missed docs/spec/decisions.md D57's six-task post-body
      enumeration. Falsifying the second surfaced a third defect the
      developer had walked past in a file already in scope — README.md:214
      asserting "source post bodies are never sent to a translator" under a
      "Private by construction" heading. That opened a distinct defect
      class (a FALSE INVARIANT, not a weak disclosure) with copies in
      CLAUDE.md:50 and docs/SPEC.md:258. files_budget 8 -> 12; the census
      filter is restated as a READ because running it as a phrase grep is
      what hid rows 10 and 11 for two consecutive rounds.
  - date: 2026-08-04
    verdict: FINDINGS
    round: 3
    base: 29aa59afbd80e13df6265e6934c77f1a4a06ffe6
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-758-2026-08-04-r3.md
    findings_count: 4
    out_of_model_count: 2
    note: |
      All FIVE prior findings re-verified closed. Spec-completeness and
      pointer-reachability spot-checks PASSED. Of the four new findings,
      THREE are regressions the round-3 edit itself introduced (a fabricated
      "decline the consent prompt" safeguard that does not exist; an
      assertFalse targeting a TRUE exposure, reinstating the round-1
      anti-pattern in the sibling test file; a parity comment the block does
      not hold for summarizer). The fourth is a census gap, OVERVIEW.md,
      invisible to the round-2 enumeration rule by construction. Refined
      rather than escalated further: files_budget 7 -> 8, the census
      enumeration RULE replaced with a capability-first selection, and a
      corrected run of it returns exactly one newly-wrong surface.
  - date: 2026-08-04
    verdict: FINDINGS
    round: 2
    base: 29aa59afbd80e13df6265e6934c77f1a4a06ffe6
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-758-2026-08-04-r2.md
    findings_count: 2
    out_of_model_count: 1
    note: |
      Re-audit of the rewritten disclosure. All THREE round-1 findings are
      closed and were not re-reported. Two new ones, both surviving
      falsification: a SECOND runtime disclosure at install time
      (prod/scripts/4-llm.sh:485-500) still carrying the deleted pre-M1-746
      translator line, and a self-inflicted contradiction in SETUP_GUIDE.md
      (:556-558 says the switcher is per-task; this diff added :603-605
      saying it is one-backend-for-all — the latter is the true one).
      A census run at this round found 7 disclosure surfaces across 4 files;
      this diff corrects 2. Two audit passes returning DIFFERENT file sets is
      the documented decompose trigger, so escalated rather than refined a
      third time.
  - date: 2026-08-04
    verdict: FINDINGS
    base: 29aa59afbd80e13df6265e6934c77f1a4a06ffe6
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-758-2026-08-04.md
    findings_count: 3
    out_of_model_count: 2
    note: |
      Round 1 at the /m1-tick run gate, ahead of review. Two of the three
      findings are defects the diff INTRODUCED (a false hard negative about
      /lang en, and the deletion of the still-live bot-reply-prose leg while
      claiming the enumeration is complete) — both regression-pinned by the
      test the diff adds. The third is pre-existing and outside files_scope
      (SETUP_GUIDE.md). Halted to escalation rather than self-fixing, because
      the honest fix changes what the ticket claims ("FOUR legs" is wrong) and
      the SETUP_GUIDE.md leg needs a files_scope refine.

      FALSIFICATION PASS (user-requested, before escalating). All three
      survived. F1: worker has no enable flag (@Scheduled on
      infochat.llm.translator.poll-interval), routes to the same ModelTask
      .TRANSLATOR family switch-llm.sh controls, its only skip arm is
      `"en".equals(row.language())` (the SOURCE's language), and renderPrompt
      :358-366 interpolates {{title}} + full untruncated {{body}}. `language`
      is operator-settable per source via BootstrapLoader:173 (the D29
      correction path), though the shipped prod/config/bootstrap-sources.json
      has 7 sources with language unset — so the leak is LATENT in a default
      deployment but the false claim is live regardless, because it is the
      operator's decision input. F2: ChatAgent:558 IS /lang-gated
      (`!"en".equals(scopeLanguage)`), so it sits inside the stated
      precondition — what breaks is the explicit "FOUR legs" completeness
      claim, which excludes it, plus the deletion of its only disclosure and
      the assertFalse pinning that deletion. One auditor imprecision corrected:
      the /summary prose route is NOT DigestRenderer.forSummaryRendering (a
      test-only seam, no main callers) but SummaryCommandHandler.digestRenderer
      .renderSummarySections/renderShortBody -> DigestRenderer:825
      translationPipeline.run; the conclusion holds. F3: SETUP_GUIDE.md:563-567
      verified verbatim, and security.md does designate that section.
clarity_check:
  date: 2026-08-04
  verdict: WARN
  warnings:
    - >-
      lint clean (0 blockers, 0 warnings). Self-check corrected one mechanical
      defect inline: `files_scope`/`test_plan.adds` named
      `infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/SwitchLlmWiringTest.java`,
      which does not exist. The only `SwitchLlmWiringTest.java` in the repo is
      `infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/SwitchLlmWiringTest.java`
      — same file, wrong module prefix (`test_plan.preserves` describing "every
      existing SwitchLlmWiringTest assertion, including the positional stdin
      sequence" confirms the ticket means that existing file). Path corrected;
      file count unchanged at 3.
    - >-
      Ticket-vs-code truth spot-checks all PASS: `prod/switch-llm.sh:403` carries
      the bot-reply-only translator line and `:387` the "chat carries PRIVATE
      user DMs" block comment; `docs/spec/security.md` §Secrets handling carries
      exactly three `translator` bullets (M1-746 at :1881, M1-755 at :1890,
      M1-756 at :1901) and none for `/summary` (SPEC GAP 1 real); §Rate limiting
      carries the `/saved` `infochat.save.translation-max-per-page` entry at
      :1661 but no `infochat.digest.translation-max-per-render` entry, whose only
      two occurrences repo-wide are `application.properties:631` (default 5) and
      `security.md:1905` (SPEC GAP 2 real). The `/summary` display-hit leg exists
      (`ClusterBlockRenderer:118`, M1-747).
    - >-
      Control preservation N/A — the diff reroutes no code path; it is disclosure
      text plus test assertions.
escalation_reason:
---

# M1-758: switch-llm.sh — translator now carries private user messages

## Context

M1-746 added the query-anchoring translation leg: for a scope on a non-English
`/lang`, `SemanticSearchTool` sends the search query to `ModelTask.TRANSLATOR`
before embedding it. On the D28 pre-fetch path that query is the user's raw
chat message (`ChatAgent.buildSemanticRetrievalBlock`, truncated to
`SEMANTIC_QUERY_MAX_CHARS`, not redacted).

`prod/switch-llm.sh`'s Phase 4 disclosure still describes the pre-M1-746 world:

```
translator — translation of the bot's replies to you; exposes the bot-reply
text (which can echo your queries).
```

That was accurate when the translator only rendered bot prose. It now
understates the exposure: the operator is told `chat` is the one task carrying
private messages, and switches backends on that basis, while `translator`
carries them too.

Found by the M1-746 r6 red-team (claude, INFO-LEAK medium),
`docs/plan/m1/redteam-multi/M1-746-2026-08-03-r6/`. M1-746 corrected the
spec-side disclosure; the script's runtime text was outside its `files_scope`,
which is why it is a separate ticket rather than a silent scope widening.

## Census

**Enumeration rule.** Two populations, each selected by CAPABILITY first,
then filtered:

```bash
# (a) runtime print sites — a script that prints a disclosure at a decision point
grep -rn 'PRIVACY DISCLOSURE' --include=*.sh .

# (b) maintained reader-facing docs that mention remote/cloud LLM routing ...
for f in $(git ls-files '*.md' | grep -v '^docs/design/\|^docs/plan/\|^docs/process/\|^\.claude/'); do
  grep -qiE 'remote-llm|cloud API|remote provider|remote LLM' "$f" && echo "$f"
done
# ... then read each hit and keep the ones making an EXPOSURE claim (what is sent).
```

**The round-2 version of this rule was broken and row 8 is the proof.** It
claimed "by invocation, not by phrase" while its second grep was BOTH
path-restricted (`prod/ SETUP_GUIDE.md README.md`) and phrase-based, so
`OVERVIEW.md` could not be found by re-running it — none of `exposes`,
`private messages`, `goes to that provider`, `leaves the machine`, `never
leave` matches OVERVIEW's "private chat messages … to that external
provider". Selecting by capability (does this file discuss remote routing at
all?) and filtering by reading is what makes the second population
enumerable rather than guessed.

**Step 2 is a READ, not a grep — the rule has now failed the same way
twice.** Round 2's filter was path-restricted and phrase-based. Round 3
rewrote the rule to "select by capability, then filter to those making an
exposure claim" — and the filter was AGAIN run as a phrase grep, which is
why rows 10 and 11 survived to round 4: D57 says "post body" where the
grep had "post bodies", and `README.md:214` is phrased as a NEGATIVE
guarantee ("never sent to a translator"), which no positive-exposure
phrase can match. A phrase grep cannot enumerate a class whose members are
defined by meaning. Open each selected file and read its remote-routing
and translation claims.

Read-based run performed at the round-4 refine over every maintained
reader-facing and spec-tier `*.md` (adding `translat` to the capability
selector, which is what surfaces the negative-guarantee family). It
returned rows 10 and 11, and **mis-dispositioned `USER_GUIDE.md`** —
see below. `SECURITY.md`, `DEVELOPER.md` and `docs/spec/messaging.md`
mention remote routing or translation but assert no exposure or
invariant; `docs/spec/llm.md:295` states the D29 guarantee in its correct
"never rewritten" form; `SETUP_GUIDE.md:975` ("your prompts and post
content go to that provider") is vague but not false.

**Population (b) is `*.md` and that is a THIRD structural failure of the
rule, not a third missed file.** The two defect classes this ticket
handles need different enumeration methods, and the census had only one:

- An **exposure** claim ("what does routing task X remote send?") is
  defined by MEANING. No phrase grep enumerates it; step 2 is a read.
  That is settled above and cost rounds 2–4.
- An **invariant** claim ("post text is never translated") is a PHRASE
  FAMILY. It greps reliably — and must be grepped over **every tracked
  file type**, because the class is asserted in javadoc and design notes,
  not only in reader-facing Markdown.

Round 5's two surfaces sit precisely where the `*.md` population cannot
reach: `TranslationProvider.java` is Java, and it is the UPSTREAM text
rows 10's three copies paraphrase — so the census corrected the copies
and left the original. `docs/design/05-llm-and-embeddings.md:932` is
excluded by the census AND unreadable by the auditor, whose role bars
design notes; no participant in the loop could see it.

```bash
# (c) the invariant class — ALL tracked file types, no path exclusions
git grep -nIiE "never (be )?(translated|sent to a translator)|aren'?t translated\
|are not translated|only the (bot|LLM)-?(authored|'s own)|surfaces them as-?is\
|translates? <?strong>?only|untranslated|don'?t translate|do not translate\
|remain in (the )?source language|left in (the )?(source|original)"
```

All-file-types run performed at the round-5 refine, twice with
differently-shaped patterns; both returned the same set. Rows 12, 13 and
`docs/design/05-llm-and-embeddings.md:932-934` (folded into row 10, same
file already in `files_scope`), and nothing else. Two hits verified TRUE
and left alone: `DegradedDigestRenderer:42` ("these headlines are never
translated") is correct — the degraded path is spec-pinned to no LLM
calls, and its own javadoc contrasts itself with the normal-mode leg that
does translate; `docs/spec/llm.md:338` ("a digest sent to ten group
members is not translated" ten times) is about cache reuse, not the
invariant.

Re-run both at implementation; a row appearing that is not in this table
means the census is stale and the table is corrected, not ignored. Rows 3
and 4 were already fixed by the round-1/round-2 work on this branch.

| # | Site | Defect | Disposition |
|---|---|---|---|
| 1 | `prod/scripts/4-llm.sh:485-500` | Install-time runtime disclosure: `chat` is the sole `!!` tier; `translator` sits in the "not private user data" tier carrying the exact deleted pre-M1-746 line; none of the other six legs named | FIX — highest priority; the only disclosure an install-time-remote operator ever sees |
| 2 | `prod/scripts/4-llm.sh:482` | Comment: switch-llm.sh "can later move generative tasks back to local one at a time" — false since M1-603/D56 | FIX |
| 3 | `prod/switch-llm.sh:405-421` | Switch-time runtime disclosure understated translator to bot-reply echo | DONE on this branch (two-fact shape + SETUP_GUIDE pointer) |
| 4 | `SETUP_GUIDE.md` §"Switching your AI backend later" tier line | `chat` named as the sole private-text task | DONE on this branch (+ the leg-by-leg long form added) |
| 5 | `SETUP_GUIDE.md:556-558` | "asks, for each AI task, which backend to use ... Press Enter to keep a task as-is" — false; and the round-2 diff added a contradicting sentence 47 lines below | FIX — self-inflicted contradiction, in scope already |
| 6 | `SETUP_GUIDE.md:287`, `:315-319` | Install-time remote choice: "your prompts go to that provider"; "chat, summaries, tagging" = 3 of 7 task families; `translator` and the ingest leg absent | FIX (minimal) |
| 7 | `README.md:215-221` | Omits `translator` from the exposure list; asserts "the setup wizard spells out exactly what each task exposes", which is false until row 1 lands | FIX (minimal) |
| 8 | `OVERVIEW.md:288-293` §7 "Privacy note" | "public post bodies for ingest tasks; private chat messages if chat is routed remotely" — `translator` absent, private data gated on `chat` alone | FIX (minimal) — found at round 3; the round-2 enumeration rule could not see it |
| 9 | `prod/scripts/4-llm.sh` block comment | Repeats the fabricated "sweeps such pins unless the operator declines the consent prompt" safeguard the printed text now correctly denies | FIX — found at round 4; invisible to the stdout-only regression guard |
| 10 | `README.md:214`, `CLAUDE.md:50`, `docs/SPEC.md:258` | State the FALSE strong form of the D29 invariant — "source post bodies are never translated" / "never sent to a translator" — under privacy-guarantee headings | FIX — found at round 4; a different defect class (false guarantee, not weak disclosure) and the upstream source the disclosures drifted toward |
| 11 | `docs/spec/decisions.md` D57, `docs/design/05-llm-and-embeddings.md:1102` | Both enumerate six tasks whose post bodies reach the remote provider, omitting `translator` — the seventh, and the one sending whole untruncated bodies | FIX both — D57 quotes the design line verbatim, so correcting one alone creates a fresh contradiction |
| 12 | `USER_GUIDE.md:124`, `:298-299` | The false strong form, asserted twice, in the ONLY end-user-facing document — the one surface the corrected operator disclosures never reach. Not missed: the round-4 read cleared it by hand. `/lang` row also stale at "English and Czech in v1" | FIX — found at round 5 (medium); the mis-disposition is the defect, not the file |
| 13 | `infochat-messaging-adapter/.../TranslationProvider.java:7-18` | The SPI javadoc — the UPSTREAM text row 10's three copies paraphrase — asserts both "translates **only** LLM-authored strings" and "source post bodies are NEVER translated ... surfaces them as-is", while its own `runForDisplayHit` path is handed a source-authored `DisplayHeadline` | FIX — found at round 5; invisible to an `*.md` population by construction, and to both regression guards, which read shell stdout |
| 14 | `docs/spec/verification.md:452-457` | D29 presentation-path spy requirement whose first clause the shipped display leg violates, contradicted by its own next four sentences. No test implements the retired clause — the storage property is pinned on the ingest path (`IngestTranslationWorkerIT:219-221`) | FIX — round-5 out-of-model; same false-guarantee class as 10/12/13, so fixed here rather than deferred |

**Deliberately NOT in the census.** `docs/design/07-deployment.md` (per-task
config reference, not an exposure claim); `docs/spec/security.md` itself (the
authority the census renders, already in `files_scope` for the leg
enumeration); anything under `docs/plan/`.

## Approach

Text-only, across four surfaces: `docs/spec/security.md` (the authority),
`prod/switch-llm.sh` (the switch-time disclosure), `SETUP_GUIDE.md` (the
pre-read an operator consults BEFORE switching), and the wiring test that
pins the script's output. Spec first, then the two operator-facing texts
against it — an incomplete section propagates into both.

The script's own comment states the standard the change is held to — "A wrong
claim here is a security defect, so the text is per-task, never a blanket
'privacy sacrificed' line" — so the fix keeps the per-task shape and moves
`translator` into the loud tier beside `chat`.

**The round-1 attempt failed that standard in a new way, which is why the
tiering above is spelled out.** It moved `translator` into the loud tier
correctly, then gated the whole task on the scope's `/lang` and asserted a
hard negative: "an `/lang en` scope is a strict no-op and sends nothing.
Where it is set, FOUR separate legs reach the remote provider." Both halves
were wrong — the ingest leg is gated on `source.language` and ignores every
scope's `/lang`, and the count omitted the reply-prose leg whose only
disclosure the same diff deleted. Replacing a vague-but-true line with a
specific-and-false one is a regression even though it reads as an
improvement, and the round-1 test locked it in. Enumerate by call site
(the list in `acceptance`), not by the ticket's title.

## Out-of-scope

Routing changes. Any locality constraint on `ModelTask.TRANSLATOR` — pinning
the translator local would genuinely close the exposure rather than disclose
it, but that is a behavioural decision with its own cost (no local translator
is configured on the remote-llm profile) and needs its own ticket. Re-editing
what M1-746 landed in `security.md`. The false aggregate-system-LLM-budget
sentence recorded in `out_of_scope` (needs a decision first, not an inline
fix).

## Notes

- The exposure is conditional on a non-English scope. `en` scopes are a strict
  no-op in the translator leg, so today's `en`-only deployments send nothing
  new. The disclosure must still be accurate for the `cs` scopes the
  `LanguageRegistry` already enables.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-758-switch-llm-translator-privacy-disclosure.md`
