# SinewSlim — design

> **Status (29 October 2026). DESIGN, NOT YET BUILT.** The build-out of
> `concentration-divergence-study/SPLIT-TOOL.md`. The algorithm is the one in that study's
> `SPLIT.md`, run today by `scripts/split.py` over the facts from `measurer/.../LiveWidth.java`.
> This document says how it becomes a product: a codebase report, a code-review gate and an
> IntelliJ plugin, all in Java, all driven by one configuration.

## The name

A sinew is the data that has to cross a seam when a method is cut in two (the study's
`SINEW.md`). A long method is a muscle grown too big. SinewSlim does not cut the sinew
through; it slims the method down to the cap by moving whole runs of statements out at the
seams where the least sinew crosses. Where no such seam exists, the sinew runs through the
whole method, and that is the method to redesign rather than split.

## What it does, in one paragraph

A method of 26 code lines or more is long; the study's reviewers call it a Long Method at that
length and nothing else beats it (step 13, MCC 0.777). SinewSlim does not re-argue that. For
every long method it finds the cheapest set of contiguous statement runs whose extraction
brings the method, and every extracted piece, to 25 lines or fewer, where the cost of a piece
is what crosses its boundary: its parameters and its return value. It says three things about
a method: **under the cap**; **slimmable**, with the pieces and their cost; or **not
slimmable**, with the reason, which is the warning list a developer should look into as a
design problem. The three surfaces below are three ways of showing those answers.

## Surfaces

| # | Surface | Scope | Output |
|---|---|---|---|
| 1 | **Scan** (`sinew-slim scan`) | the whole codebase | a report of every method: length, whether it is over the cap, the slim cost of the over-cap ones, the not-slimmable list, the locked share per class |
| 2 | **Review** (`sinew-slim review`) | the methods a change touches | the proposal for each touched method over the cap, the redesign warning for each that cannot be slimmed; as a GitHub Action, a GitLab job, a Jenkins step, a git hook, or console text for an IDE terminal |
| 3 | **IntelliJ plugin** | the method under the cursor | the pieces drawn over the method, each with a button that extracts it after asking only for a name |

All three are thin shells over one pure core. The same configuration file drives all three,
so the IDE, the hook and CI never disagree about a method.

## Language and build

Java throughout, no Python. The study's `split.py` is ported, not wrapped.

- **Java 17** as the language level (the IntelliJ platform's floor, and the measurer's).
- **Gradle** (Kotlin DSL) for every module, since the IntelliJ plugin needs the IntelliJ
  Platform Gradle plugin (2.x, as in `~/OfficeFloor-IntelliJ-Plugin`) and one build tool is
  better than two.
- **JavaParser** (pinned, 3.28.x as the measurer) parses the Java outside the IDE. No symbol
  solver: the pass reads syntax and lexical scope only, which is what makes it fast enough for
  a hook.

### What is a library, and what is ours

SinewSlim parses nothing itself. The parsing, the grammar across Java versions, and the syntax
tree are JavaParser's outside the IDE and IntelliJ's PSI inside it, and both are maintained
upstream.

What is ours is the layer between the tree and the search: walking the tree to record each
variable's reference, definition and mutation lines, the loop extents and the jumps. No
library offers this from source. The candidates were checked:

| Library | Why not |
|---|---|
| JavaParser symbol solver | resolves names and types; no def-use or data flow |
| Spoon | a richer tree, but its data-flow module is experimental and unmaintained |
| PMD 7 | dropped its data-flow analysis in version 7 |
| Soot / SootUp, WALA, Checker Framework dataflow | work on bytecode or need `javac` to compile the code first: unusable on a staged hook, on unsaved editor text, or on a repository that does not build |
| IntelliJ `ControlFlowUtil` (`getInputVariables`, `getOutputVariables`) | gives exactly a range's inputs and outputs, but only inside the IDE |

So the facts layer stays, but it is not new code. It is the study's `LiveWidth.java`, already
Java and already run over the study's corpora, taken over with the study-only output removed
(about 800 lines today, less once trimmed). What is ported from Python is only the search,
`split.py`.

Inside the IDE, the plugin cross-checks: for each proposed piece it asks IntelliJ's
`ControlFlowUtil` for the range's inputs and outputs and logs any disagreement with the facts
layer. Each disagreement is a test case for the facts layer, so the IDE keeps it honest
without SinewSlim depending on the IDE everywhere else.

```
SinewSlim/
  settings.gradle.kts
  facts/       sinewslim-facts: the facts model and the seam's roles (schema 3), the extra facts. No I/O.
  core/        sinewslim-core: the cost, the search, the result, over the facts. No I/O, no parser.
  javaparser/  sinewslim-javaparser: facts from JavaParser's syntax tree (the study's LiveWidth.java,
               taken over), and the extractors a consumer adds
  config/      sinewslim-config: .sinew-slim.yml: load, defaults, validation, the effective-config dump
  scan/        sinewslim-scan: a repository's files, include and exclude, read in parallel
  cli/         scan, review, hook, comment; git; report formats; providers (GitHub, GitLab)
  intellij/    the plugin: inspection, overlays, extraction through the IDE
  ci/          gitlab-ci.yml, Jenkinsfile (as ImpactGate)
  action.yml   the GitHub Action
  Dockerfile   JRE + git + the CLI jar
  doc/         the algorithm, the configuration, each surface
```

`facts` depends on nothing, and `core` only on `facts`. `javaparser` and the plugin each turn
source into the same facts; everything above that line is shared. That is the one rule that
keeps the three surfaces agreeing.

The five libraries publish to Maven Central as `net.officefloor.sinewslim:<project>`, and each
Gradle project carries its artifact name (`:sinewslim-core` in `core/`), so a project building on
them with a composite build substitutes them without a substitution block. Each has its own package
(`net.officefloor.sinewslim.facts`, `.core`, `.javaparser`, `.config`, `.scan`): no package is
split across two jars. `facts` and `scan` exist for consumers that price the same methods
another way: such a consumer reads the facts and the seams but not SinewSlim's cost, adds its own
facts through the extractors, and scans other files beside the Java.

## Configuration

SinewSlim reads every method one way: as methods extracted from it. The framework reading it had
until 0.2 (a method marked by `framework.annotations` priced as functions a framework injects) is
removed; pricing another architecture is not a health measure's job. A `framework:` key left in
a `.sinew-slim.yml`, or given with `--set`, is ignored with a warning, so no existing
configuration breaks.

One file at the repository root, `.sinew-slim.yml`, found by every surface (the plugin reads
it from the project root, and falls back to the IDE settings page when there is none). CLI
flags and Action inputs override it. Every value has a default; an empty file is valid.

```yaml
# the cap
trigger: 26              # a method with this many code lines or more is long
target: 25               # every piece and the remainder must end at this many or fewer

# a piece
min_piece: 3             # the smallest run worth extracting, in code lines
min_saving: 2            # lines a cut must remove (the run less its one call line)
max_outputs: 1           # values a piece may return; Java has one
trailing_return: true    # a run may end in a return; any other jump out of it is fatal

# parameters: a hard limit, and an optional soft limit with a premium above it
max_inputs: 5            # a piece needing more is not proposed
soft_inputs: null        # e.g. 3: each parameter beyond this costs extra
input_premium: 2         # the extra cost per parameter beyond soft_inputs

# cost weights
output_weight: 1         # a single return costs the same as a parameter
piece_cost: 1            # a name and a call site per piece
count_unpacking: true    # a piece returning a record leaves its call and one unpacking line per value

# variants, reported beside the default proposal
records: true            # when nothing is feasible with one return, try records (3 per value)
structural: true         # also solve with whole if/loop/try/switch statements only
prefer_structural_within: 1   # prefer the structural proposal when it costs at most this much more

# binding to the class
lock_on_any_field: false # default: a field write or an own-method call locks a piece
parameterise_field_reads: true

# what is scanned
include: ["**/*.java"]
exclude: ["**/generated/**", "**/target/**", "**/build/**"]

# gating (review only)
enforcement: warn        # off | warn | block
ratchet: true            # block only on growth (see Review)
```

### The defaults, and where they differ from the study

| Setting | SinewSlim default | Study (`SPLIT.md`) | Why |
|---|---|---|---|
| trigger / target | 26 / 25 | 26 / 25 | the step 13 cutoff, unchanged |
| min_piece | 3 | 5 | small helpers are proposed; the study's 5 came from Alon and Feitelson's distribution, not a rule |
| min_saving | 2 | 3 | follows from min_piece: a 3-line run replaced by a call saves 2 |
| max_inputs | 5 | 3 | a hard ceiling only; the soft limit carries the preference instead |
| max_outputs | 1 | 1 | Java's single return |
| count_unpacking | true | false (split.py counts the call line alone) | a record piece leaves `int a = totals.a();` per value in the method; counting it keeps the remainder honest |

`sinew-slim config --profile study` prints and applies the study's values, so a run can be set
beside the paper's numbers.

### The soft parameter limit

The hard limit says which pieces may be proposed at all. The soft limit says which are
preferred: a piece with `k` inputs costs

```
inputs_cost(k) = k + input_premium * max(0, k - soft_inputs)      (soft_inputs set)
inputs_cost(k) = k                                                 (soft_inputs null)
```

With `soft_inputs: 3, input_premium: 2`, a 4-parameter piece costs 6 and a 5-parameter piece
costs 9, so the search takes a 4-parameter cut only when every 3-parameter route costs more.
The premium enters the seam cost below, so it changes both the ranking and the worst seam.

## The core

### Facts

Per method, from either parser:

- the **statement tree**: every node with its kind (block, if, loop, try, switch entry,
  lambda body, return, break, continue, throw, yield, other) and its line span;
- the **code lines**: lines inside the method that hold code, not blank and not only
  comments; this is the length the cap is read on (the oracle's nloc);
- per **local and parameter**: its reference lines, definition lines, mutation lines (a
  write into the object: an array store, a field set through it), the loop extents it sits
  in, and whether its declaration has no value or a literal one;
- per **field**: the lines it is read on and written on;
- per **call**: its line, and whether it has no receiver or `this` as receiver (an own-class
  call).

The facts are the study's live-width facts, schema 3, as a Java record set rather than JSON.
`MethodFacts.SCHEMA` carries the number, continuing the study's; it changes whenever a field's
meaning does, so a consumer can tell which facts it was given.

**Extra facts.** A consumer reads more than the schema without changing it. A
`JavaFacts.Extractor` reads a callable's syntax tree and returns one fact under its own
`FactKey`; the parse runs every extractor it is given, and each method's `MethodUnit.extras()`
holds what they read. Keys compare by identity, so two consumers never collide, and SinewSlim's
search reads none of them: an extractor cannot move a slim cost, nor parity with `split.py`.
Files other than Java (configuration, wiring) are read through `scan` with the consumer's own
reader.

**The seam's roles and its cost are apart.** `Seam` (in `facts`) says what crosses a run's
boundary; `Params` (in `core`) prices it as an extracted method: `inputCost`, `outputCost`,
`width`, `cost`, `feasible` and `binding`.

### The seam

For a run of statements with line set L, each local or parameter takes one role:

| Role | Rule | Becomes |
|---|---|---|
| input | referenced in L and referenced before L | a parameter |
| output | written in L and referenced after L, or referenced earlier in an enclosing loop | the return value |
| moved | declared before L with no value or a literal one, every other reference in L | nothing: the declaration moves into the new method |
| passed | a method parameter used unchanged | an input, labelled |
| internal / untouched | — | nothing |

A run is **feasible** when it has at most `max_outputs` outputs, at most `max_inputs`
inputs, no jump that leaves it (a trailing `return` aside), at least `min_piece` lines, and
at most `target` lines once its own inner pieces are out.

```
seam(p)  = inputs_cost(inputs_p) + output_cost(outputs_p)
output_cost(0) = 0,  output_cost(1) = output_weight,  output_cost(k ≥ 2) = 3k   (records variant)
cost(S)  = Σ_p ( seam(p) + piece_cost )
```

### The search

The bottom-up dynamic program of `split.py`, ported:

1. For each sequence (block, switch entry, lambda body), walk its statements in order. At
   each statement either keep it in the remainder (with any reduction its inner sequences
   offer) or close an extracted run ending there.
2. Keep a Pareto front per state: remainder length so far, worst seam, pieces, total cost.
3. A statement longer than the target cannot move whole; its inner sequences are solved
   first, and the parent sees the lengths it can be reduced to and what each costs.
4. Of the segmentations where the remainder and every piece end at or under the target, take
   the one with the smallest **worst seam**, then the **fewest pieces**, then the smallest
   **total cost**.

Then the variants: **structural** (whole `if`, loop, `try`, `switch` statements only, preferred
when within `prefer_structural_within` of the best, since that is where developers cut), and
**records** (only when the default is infeasible).

### The result

```
MethodResult
  location        file, class, method signature, line span
  length          code lines
  status          UNDER_CAP | SLIMMABLE | SLIMMABLE_WITH_RECORD | NOT_SLIMMABLE
  slimCost        the worst seam of the best segmentation
  totalCost       cost(S)
  interface       the sum of seams: the sinews the slimming creates
  pieces[]        line range, length, inputs (passed marked), output, moved declarations,
                  binding (free | bound by reads: fields | locked: reason), lines saved
  remainder       length after, binding
  reason          for NOT_SLIMMABLE: the obstacle (below)
  structural      the structural-only result beside it
```

**The reason for NOT_SLIMMABLE** is found by lifting the limits one at a time and seeing which
lift makes a segmentation feasible, in this order:

- *every cut carries two or more results* (outputs): lifting the single return makes it
  feasible. With `records: true` this is `SLIMMABLE_WITH_RECORD` instead, with the records
  proposal;
- *every cut needs more parameters than the limit* (inputs);
- *every cut carries two or more results or needs more parameters than the limit* (both);
- *every run that would bring it under the cap has a return, break or continue leaving it*
  (jumps): feasible only once jumps are ignored. Early-return guard clauses and a `switch` of
  `return`s are the usual cause;
- *a single statement is longer than the cap and nothing inside it can move*, naming it: *the
  switch statement at lines 397–529 (129 lines)*. Usually a `switch` of many short `case`s, none
  long enough to be a piece;
- *too long to slim in one pass* (length): no one statement blocks, but the method is so long
  that even one call line per piece leaves it over the cap. It needs slimming in two levels, or
  a redesign.

On OfficeFloor (October 2026), jumps account for 79 of the 106 not-slimmable methods, so the
jump rule is the limit that most often decides between a fix and a redesign notice.

A method where every piece and the remainder are locked to the class (each writes fields or
calls the class's own methods) is a status of its own, `SLIMMABLE_BUT_LOCKED`: reported as a
god-method warning even though a split exists, because splitting it moves nothing out of the
class, and the fix is the class, not the method.

### Performance

The search is per method and independent, so the scan runs files in parallel on a fixed pool
whose threads have a deep stack (the parser and the search recurse with the code's nesting).
Parsing dominates: OfficeFloor (3,872 files, 21,994 methods) scans in about 6 seconds,
mssql-jdbc (299 files) in about 3.5. A per-file cache keyed by the file's blob hash
(`.sinew-slim/cache/`) would let repeated scans and hooks skip unchanged files; not built,
since nothing yet needs it.

**Many runs of one sequence** (`Assessor.runs`, `RunSlimmer`, since 0.2.1). A caller that asks
how each run of a sequence of statements would slim as a method of its own (every candidate
function body, say) asks of about n²/2 runs; slimmed one by one from scratch, a flat generated
method of a few hundred statements takes hours. The search walks a sequence left to right, and
its states after statements `from..i-1` do not depend on what follows, so one walk from each
first statement serves every run starting there: about n walks, minutes becoming seconds. The
walk reads the whole sequence as a method of its own, which indexes every run inside it as the
run's own facts do (the same code lines and seams). The results are the same, run for run:
`RunSlimmerTest` compares every run of every sequence of the committed parity subset (141,111
runs, 30,590 of them slimmable) against slimming each from scratch. `solveSeq` uses the same walk,
and the full parity check is unchanged by it.

**Walks that end (since 0.2.3).** A walk to the sequence's end still costs time in its length,
and with n walks a generated table of thousands of statements took hours again. An end's runs
are slimmed only by an option that leaves at most the target plus its covered lines less its
length. A later statement adds its own option's lines left, and a cut its call (at least one).
A statement's option can leave fewer than no lines: statements sharing a line are each counted
against it, so a loop or if of them slimmed from inside can save more than it counts. So after
statement q the lines left can still fall by at most the sum of the later statements' most
negative options, and a walk keeps only the states that, allowing for that fall, could still
reach the largest limit of its ends. Every state built from a dropped one is dropped too, and
none could have hidden a kept one from the Pareto front at an end. Once no state is left that a
run within the target could start from, the walk stops: the runs after it cannot be slimmed. A
flat table has no negative option, so a walk over one covers at most a few hundred statements (a
flat run holds about target² lines, as each piece leaves its call in what is left, which must
itself fit). `RunSlimmer.cannot` answers "cannot be slimmed" from the walk alone, without building
the run's facts or index (a run's facts index each of its statements as the walk does: code
lines come from the statement tree, and no sibling has a line inside a statement's span but on
its first or last line). A method's own search prunes the same way, against its one end.

0.2.2 pruned at the limit itself, as if lines left could not fall, and so slimmed differently two
methods of a study's sample whose loops and ifs hold several statements to a line.
`RunSlimmerSharedLinesTest` generates such methods (nearly all with an option leaving fewer than
no lines) and checks every run against slimming from scratch, under the defaults and the
structural variant, and each method's own search against the whole search, under every variant
the assessment uses; both fail on 0.2.2. `RunSlimmerTest` checks every run of the parity
subset, and every run it answers "cannot" for, the same way.

## Surface 1 — Scan

```bash
sinew-slim scan [--repo .] [--format text|markdown|html|json|csv|sarif] [--out report]
```

The report has four parts:

1. **Summary.** Methods scanned; how many under the cap; how many over it, split into
   slimmable, slimmable only with a record, slimmable but locked, not slimmable; the
   distribution of slim cost among the slimmable; the total lines over the cap (the excess,
   `Σ max(0, length − target)`).
2. **Under the cap.** Every method with its length and headroom (`target − length`), so the
   methods close to the cap show before they cross it. Collapsed in text formats, full in
   JSON and CSV.
3. **Over the cap, slimmable.** Ranked by slim cost, then length: each method with its cost
   and its proposal (pieces, inputs, output, binding, lines saved).
4. **The warning list: not slimmable.** Each method with its reason, length and class. These
   are the methods a developer should look into for redesign; the report says so in words:
   *this method is bound to the object's state; the next change here is a design decision,
   not an edit.*

Plus a **class table**: per class, methods over the cap, pieces proposed, the **locked share**
(locked pieces over all pieces), and the not-slimmable count. A high locked share is a god
class rather than a set of long methods, and the report sorts by it.

The JSON is the contract (versioned schema, every parameter written into it) that the other
formats and later tools read. `sarif` lets GitHub code scanning show the not-slimmable list as
alerts.

`sinew-slim baseline` writes the scan's per-method lengths and statuses to
`.sinew-slim-baseline.json`, which the review's ratchet reads when there is no base commit to
compare with.

## Surface 2 — Review

```bash
sinew-slim review --mode staged|worktree|range [--base origin/main]
                  [--format console|markdown|json|sarif|github|codequality]
                  [--enforcement off|warn|block] [--no-ratchet]
```

The modes and the plumbing follow ImpactGate (`~/ImpactGate`): `staged` (index against
`HEAD`, the hook default), `worktree` (unstaged edits), `range` (merge-base of `--base` to
`HEAD`, for CI). Git is driven as a subprocess, so the Docker image needs only a JRE and git.

**What it looks at.** Each changed `.java` file is parsed at the head side, and at the base
side when the file existed. A method is **touched** when a changed hunk overlaps its span.
Methods are matched across the two sides by class and signature, falling back to class and name
when the signature changed, and to the name alone when the class was renamed with its file (a
fallback is used only when the name is unique on both sides). Nothing is said about a method the
change did not touch. With no base commit (a repository's first commit), the lengths come from
`.sinew-slim-baseline.json` when there is one; otherwise every touched method is new.

**What it says**, per touched method:

| Before → after | Message | Gates (ratchet on) |
|---|---|---|
| under → under | nothing | — |
| under → over | *crossed the cap*, with the proposal | yes |
| over → longer | *grew from 31 to 38*, with the proposal | yes |
| over → same or shorter | *still over the cap*, with the proposal, as information | no |
| new method over the cap | *new method over the cap*, with the proposal | yes |
| over → under | *now under the cap*, as information | no |

A method that cannot be slimmed carries the redesign notice in place of a proposal: *cannot be
slimmed: {reason}. Investigate for redesign*. It gates as any other method does, by its row
above: when it crossed the cap, grew, or is new. (The first draft gated on every touched
method that cannot be slimmed; that breaks the ratchet, since a one-line fix inside an old
300-line method would fail the build. Under the ratchet it is information until it grows.)

With `ratchet: false`, every touched method over the cap gates. The ratchet is what makes the
gate survivable on a codebase that already has long methods: nothing that was long before
fails, only making it longer does.

Exit codes as ImpactGate: `0` ok or warn, `2` blocked, `1` usage or environment error.

### Console output (hook and IDE terminals)

Lines start with `path:line:` so IntelliJ, VS Code and most terminals turn them into links:

```
src/main/java/acme/OrderService.java:48: placeOrder grew 31 -> 38 lines (cap 25); slim cost 2
  lines 52-63   12 lines   in: request, config   out: session   static            saves 11
  lines 70-78    9 lines   in: session           out: -         instance: cache   saves 8
  remainder     21 lines
src/main/java/acme/Ledger.java:112: reconcile is 44 lines and cannot be slimmed:
  every cut carries two results (total, adjustments). Investigate for redesign.
sinew-slim: 2 methods over the cap, 1 not slimmable (warn)
```

### Distribution

As ImpactGate, with the Java toolchain in place of pip:

- **GitHub Action** (`action.yml`, composite): `actions/setup-java` (Temurin 17), download the
  released jar for the action's own ref (a release asset of the same tag; the release workflow
  also keeps a rolling `v0` release holding the newest 0.x jar, so `@v0` resolves), or build
  from source when there is no such release (`@main`, a branch). Then `review --mode range`
  against the PR base, the markdown to the job summary and a **sticky PR comment**,
  **annotations** on the touched methods' lines through workflow commands (`--format github`:
  a warning when it gates, a notice when it does not; no code scanning needed), and SARIF to
  code scanning when `code-scanning: true`. The `verdict` output is ok, warn or block.
  `fetch-depth: 0` is required. The repository's own CI runs the action on its PRs (`uses: ./`).
- **Docker image** `ghcr.io/officefloor/sinew-slim` (Temurin 17 JRE, git,
  `safe.directory '*'`, non-root): the base for GitLab and Jenkins and for anyone without Java.
- **GitLab** (`ci/gitlab-ci.yml`): merge-request rule, `GIT_DEPTH: 0`, `review --base
  $CI_MERGE_REQUEST_DIFF_BASE_SHA`, sticky MR note when `GITLAB_TOKEN` is set, the markdown as an
  artifact, and the code-quality report (`--format codequality`) so findings show in the MR
  widget.
- **Jenkins** (`ci/Jenkinsfile`): `docker run` the image against
  `origin/${CHANGE_TARGET:-main}`, the report written from standard output on the agent (the
  container runs unprivileged), archived.
- **git hook**: `sinew-slim install-hook` writes `.git/hooks/pre-commit` running `review
  --mode staged --format console`. For the pre-commit framework, a `.pre-commit-hooks.yaml`
  entry using `language: docker_image` (pre-commit has no Java language), and a `system`
  entry for teams with the CLI installed.
- **The CLI itself**: a single jar on GitHub Releases, plus the `bin/sinew-slim` launcher
  (Maven Central later, if a build plugin wants it). JVM start-up is the cost a hook feels (a
  fifth of a second with nothing staged); a GraalVM native image is the later option if it
  matters, and the core's lack of reflection keeps that open.

`sinew-slim comment --provider github|gitlab --body-file report.md` posts the sticky comment,
as ImpactGate's command does.

## Surface 3 — IntelliJ plugin

### What the developer sees

- **Inspection**: a method at or over the trigger is marked on its name with its verdict:
  *SinewSlim: 38 lines; slimmable at cost 2 (2 pieces, remainder 21 lines)*. A weak warning
  when it can be slimmed; a warning when it cannot, or when every piece stays bound to the
  class, since those are the redesign notices. The quick fix shows the overlay.
- **Gutter mark** on long methods (a different icon for the redesign cases); clicking it shows
  or hides the overlay, as does the intention *Slim this method* (Alt+Enter anywhere in it).
- **Overlay**: each proposed piece is a coloured band over its lines (two alternating colours,
  light and dark variants), with a block inlay above it holding the one-line summary and two
  buttons:

  ```
  ┃ piece 1 · lines 52-63 · 12 lines · in: config (passed: request) · out: session · static · saves 11   [Extract…] [Skip]
  ```

  Above the method, a header with the verdict and the remainder, *SinewSlim · 38 lines ·
  slimmable at cost 2 (2 pieces, remainder 21 lines) · remainder 21 lines (was 38)*, and
  *Close* (and *Reset* once a piece has been skipped).
- **Extract…** asks for one thing, the name, with a suggestion (`computeSession` from a single
  output, or empty). OK performs the extraction; nothing else is asked.
- **Skip** drops the piece and solves again without it, since the cheapest segmentation
  without that run may differ. The overlay redraws.
- Any edit redraws the overlay from the new text, so after an extraction it shows what is left
  to do, and closes once the method is under the cap.
- **Records**: a piece with two or more results has *Extract with record…*, which asks for
  two names, the method's and the record's (suggested as `computeA` and `AAndB`).

### How it works

- **Facts from the editor's own text.** The plugin runs the same `javaparser` module and the
  same configuration over the document text, not PSI, so the IDE and CI compute the identical
  proposal for the identical text. The analysis is cached per document version (its
  modification stamp); a file that does not parse mid-edit simply has no marks until it does.
  PSI is used for what JavaParser cannot do: applying the edit.
- **Applying a piece**, one command each, so each undoes on its own (`SlimExtractor`, the one
  class that touches the refactoring):
  1. find the piece's statements in PSI: the topmost statements wholly on its lines, in one
     sequence;
  2. ask the IDE first, before anything changes: `ExtractMethodProcessor.prepare()` on those
     statements, with its error dialogs off. A refusal leaves the code as it was;
  3. move the piece's literal declarations down into it (removing them from a multiple
     declaration when they share one), so the IDE does not turn them into parameters;
  4. `prepare()`, `testPrepare()`, `setMethodName(name)`, `setStatic(true)` when the piece
     touches no field and no own method and the IDE agrees it can be static, then
     `doRefactoring()`. The IDE decides the parameter types, the return, the `throws` clause
     and the generics; it is the authority on everything the syntactic facts cannot see.
     Duplicate detection is not run, so no second dialog appears.

  `ExtractMethodProcessor` is an implementation class, not stable API, which is why it is
  confined to one class; the Plugin Verifier checks it against each newer platform
  before a publish.
- **If the IDE refuses** the region (it finds two outputs the facts missed, or a resolution the
  lexical match got wrong), the plugin says why, leaves the run out, solves again, and logs the
  refusal: a refusal is a defect in the facts, and the log is how they get fixed.
- **Records** (`RecordExtractor`): the piece is reshaped to have one result, then extracted
  by the IDE as any piece is. In one command: a result declared inside the piece has its
  declaration lifted out in front of it, the piece keeping the assignment; the piece ends by
  packing its results, `Totals totals = new Totals(a, b);`, and each is unpacked after it,
  `a = totals.a();`, so the record is the piece's one output; `private record Totals(int a,
  int b) {}` is declared in the class (types read from PSI before anything changes); the IDE
  extracts; and each lifted declaration is folded into its unpacking, `int a = totals.a();`.
  A module below Java 16 is refused before anything changes, and a refusal after the reshaping
  is undone, so either way the code is as it was.
- **Settings**: read from `.sinew-slim.yml` at the project root, reread when it changes. A file
  that does not load raises one notification naming the key, and the defaults apply. A settings
  page for projects without the file is not built.
- **Not built yet**: the cross-check against IntelliJ's `ControlFlowUtil` (each disagreement
  logged as a test case for the facts), and Esc to close the overlay.
- **Platform**: IntelliJ Platform Gradle plugin 2.19, IntelliJ IDEA Community 2024.2.4 as the
  build target (the last line on JDK 17), `sinceBuild` 242 with no upper bound, the
  `com.intellij.java` bundled plugin; the Plugin Verifier before every publish; signed and
  published to the JetBrains Marketplace as `~/OfficeFloor-IntelliJ-Plugin` is. The release
  attaches the plugin zip to the GitHub Release.

## What the tool is not

- **Not a smell detector.** Length over the cap is the trigger. The tool acts on it.
- **Not an automatic refactorer.** In the plugin every edit goes through the IDE's
  refactoring with the developer's name on it. The CLI never edits code.
- **Not a judge of cohesion.** It costs interface, not meaning. A cheap piece can still be the
  wrong thing to name; the developer skipping it is the answer.

## Testing

- **Parity with the study.** The Java core is accepted when it reproduces `split.py` exactly
  (status, slim cost, pieces, cuts) on the study's facts, under each of its named parameter
  sets. A committed subset (1,200 methods) is replayed on every build, so this repository needs
  no Python for it. The full check, every long method of the study's seed-3 open-source sample
  (about 187,000), is `ci/parity-full.sh`: the study exports `split.py`'s answers, the parity test
  replays them, and the record goes to `build/parity/parity.txt`. It is run before a change to
  the search or the facts is merged, and before a release to Maven Central.
- **Unit tests** in `core` from hand-written fact sets: each seam role, each jump rule, the
  soft-limit premium, the loop back edge, recursion into a long loop, every not-slimmable
  reason.
- **Fixture sources** in `javaparser` and `intellij`: small Java files with the expected
  proposal in a comment, run through both fact producers, which must agree.
- **IntelliJ tests** on the platform's light fixtures: overlay ranges, extraction of each
  piece in fixture files, refusal handling, undo.
- **CLI tests** against throwaway git repositories: each mode, the ratchet table above, exit
  codes, each output format.

## Build order

| Phase | Delivers | Done when |
|---|---|---|
| 1 | `core` + `javaparser`: the facts, the search, the result | parity with `split.py` on the study's sets |
| 2 | `config` + `cli scan` in every format | a scan of OfficeFloor and of a MLCQ repository in reasonable time; the not-slimmable list read by hand |
| 3 | `cli review`, hook, Docker, Action, GitLab, Jenkins | a PR in a test repository gets the sticky comment, annotations and the right exit code in each CI |
| 4 | IntelliJ: inspection, overlay, extract with name only, skip and re-solve | a long method slimmed under the cap in the IDE with only names typed |
| 5 | records extraction, framework reading, native image | records and the framework reading built (the IDE extracts a record piece with no errors left); the native image deferred: a hook run starts in a fifth of a second, and nothing yet needs it |

## Open questions

- **Do developers accept the proposals?** Haas and Hummel's 86% is the bar. The plugin's
  skip and refusal logs, with the user's consent, are how it would be measured.
- **Does the soft limit beat the hard one?** With the study's `max_inputs: 3` as the soft limit
  under a hard 5, are the extra proposals (4 and 5 parameters) accepted as often?
- **Line counting outside Java.** Everything here is Java. Kotlin is the obvious next language
  for an IntelliJ-centred tool, and needs its own fact producer (no JavaParser), so it is a
  separate piece of work rather than a setting.
- **Licence and coordinates.** Apache-2.0 as ImpactGate; Maven group `net.officefloor.sinewslim`;
  CLI name `sinew-slim`; image `ghcr.io/officefloor/sinew-slim`; Action
  `officefloor/SinewSlim@v0`.

## Sources

- `concentration-divergence-study/SPLIT.md`: the algorithm, the parameters and the prior work.
- `concentration-divergence-study/SPLIT-TOOL.md`: the three signals and the surfaces this
  builds out.
- `concentration-divergence-study/scripts/split.py` and `measurer/.../LiveWidth.java`: the
  implementation to port.
- `~/ImpactGate`: the CLI modes, the Action, the GitLab and Jenkins templates, the hook, the
  Docker image and the release flow to follow.
- `~/OfficeFloor-IntelliJ-Plugin`: the IntelliJ Platform Gradle setup, signing and publishing.
