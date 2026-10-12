# Upstream sync

> **Last reviewed:** 2026-10-12 · **Last updated:** 2026-10-12 · **Change:** major (first version) · **Target:** Adobe Illustrator 2026 (30.x)

`AppThere/vecthere` is VectorCraft ([storytold/vectorcraft](https://github.com/storytold/vectorcraft)) plus a mobile
front end ([mobile.md](mobile.md)). Upstream changes many times a day, so this fork merges it automatically and
keeps its own changes to upstream's files as small as possible.

## How it works

`.github/workflows/upstream-sync.yml` runs every hour (and from the Actions tab, on any branch):

1. `.github/scripts/upstream-sync.sh` merges upstream's `main` into `upstream-sync/<branch>`, built on the
   branch the workflow runs from (`main` on the schedule). Nothing new upstream: the run stops there.
   A `Cargo.lock` conflict is resolved by taking upstream's file and letting cargo re-add our crates.
2. `cargo xtask ci` runs on the merge: fmt, clippy, every test, assets, brands, layers, wasm. That includes
   `crates/ffi`, which calls the engine directly and is what an upstream API change breaks.
3. The sync branch is pushed and a PR is opened or updated.
   - **Gate green:** the branch is fast-forwarded to the merge, and GitHub marks the PR merged.
   - **Gate red:** the PR stays open with the end of the log and the run fails. Push a fix to the sync branch
     and merge the PR. Later runs merge newer upstream commits into that same branch, never over your fix.
   - **Conflict:** nothing is pushed; an issue titled "Upstream sync needs a manual merge" lists the files
     and the commands. Syncing stops until it's resolved.

At most one sync runs per branch at a time, so with a busy upstream it effectively runs back to back: about one
full workspace build per sync.

### Setup (once, repository settings)

- **`UPSTREAM_SYNC_TOKEN` secret (needed):** a fine-grained token for this repository with Contents, Workflows,
  Pull requests and Issues set to read and write. The default `GITHUB_TOKEN` can't push commits that touch
  `.github/workflows/`, which upstream edits often, and pushes made with it don't start other workflows.
- **Settings › Actions › General:** allow GitHub Actions to create pull requests (only needed without the token).
- **`UPSTREAM_SYNC_LAND` variable (optional):** `false` leaves every sync as a PR for a person to merge.
- **Branch protection on `main`** (optional): the fast-forward is then refused and the workflow merges the PR
  instead, which the token must be allowed to do.
- Upstream's own workflows (FreeBSD, Windows on Arm, Affinity corpus) run on every push to `main` here too.
  Disable them in the Actions tab if they aren't wanted; don't edit their files, or every sync can conflict.

## Keeping the fork easy to merge

Conflicts come only from lines both sides change, so:

- **Put fork work in new files.** `crates/ffi`, `mobile/`, `docs/mobile.md`, this file and the sync workflow
  don't exist upstream and never conflict.
- **Don't edit upstream's progress docs** (`ROADMAP.md`, `README.md` status, `docs/gaps.md`, `docs/roadmap.md`,
  `docs/target-app-parity.md`, the `*-parity.md` checklists, `docs/architecture.md`). Upstream rewrites them in
  almost every PR. The mobile status lives in [mobile.md](mobile.md) instead; this overrides the progress-docs
  rule in `CLAUDE.md` for fork-only work.
- **Keep the edits to upstream's files tiny, and contribute them upstream when you can.** Today they are:

| File | Change | Why |
|---|---|---|
| `Cargo.toml` | `vectorcraft-ffi` path dependency; `uniffi = "=0.29.4"` | the workspace builds and tests the bridge |
| `Cargo.lock` | the entries for the above | resolved automatically on conflict |
| `xtask/src/layers.rs` | `("ffi", Class::Layer(6))` | the layering gate knows the crate |
| `crates/text/src/fontdb.rs` | Android and iOS system font folders | fonts on mobile (a good upstream candidate) |
| `ASSETS.md` | rows for the Android launcher icon | the assets gate; merged with `merge=union` (`.gitattributes`) |
| `crates/ui-egui/src/thumb_worker.rs` | `!(0.0..SETTLE_SECS).contains(&waited)` | newer clippy (`manual_range_contains`) fails upstream's line; drop when upstream fixes it |
| `CLAUDE.md`, `AGENTS.md` | the "Rust only" exception for `mobile/` | agents working here need it |

Add to this table whenever a fork change touches an upstream file.

The workflow builds with the current stable Rust, so a lint new in a Rust release can fail upstream's code here
before upstream notices; fix it the way clippy suggests (upstream usually lands the same line) and list it above.

## Resolving a conflict

```sh
git fetch origin main && git checkout -B upstream-sync/main origin/main
git fetch https://github.com/storytold/vectorcraft.git main && git merge --no-ff FETCH_HEAD
# fix the conflicted files; Cargo.lock: git checkout --theirs Cargo.lock && cargo metadata >/dev/null
git add -A && git commit --no-edit
cargo xtask ci
git push origin upstream-sync/main   # open a PR into main, or let the next run land it
```

Prefer upstream's side and re-apply the fork's change on top. If the change keeps conflicting, it is a sign it
belongs upstream or in a new file.

## Revision history

| Date | Change | Summary |
|---|---|---|
| 2026-10-12 | major | First version: hourly sync workflow, conflict handling, the list of fork edits to upstream files |
