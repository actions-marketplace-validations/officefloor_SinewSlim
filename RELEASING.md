# Releasing sinew-slim

Distribution is automated by `.github/workflows/release.yml`. Pushing a version tag builds,
tests and publishes everything; this note covers the tag scheme and the one-time setup.

## Version scheme

- **The tag is the version.** Release tags are `vMAJOR.MINOR.PATCH` (`v0.2.0`); the release
  builds everything with `-Pversion=0.2.0`, so the jar's `Implementation-Version` (and
  `sinew-slim --version`), the plugin's version on the Marketplace, the plugin zip's name and
  the Docker image all carry it. Any other tag form fails the release before anything happens.
- `gradle.properties` holds only the development version (`0.0.0-SNAPSHOT`) for local builds;
  it is never edited for a release.
- The workflow moves a floating major tag (`v0`) to the newest release on that major, so the
  Action can be pinned as `officefloor/SinewSlim@v0`.
- It also keeps a rolling GitHub Release named after the major tag (`v0`) holding the newest
  jar. The Action downloads `releases/download/<its own ref>/sinew-slim.jar`, so `@v0.1.0` gets
  that release's jar and `@v0` gets the rolling one. Any other ref (`@main`, a branch) builds
  the jar from source instead.

## Cutting a release

Before the first release of a change to the search or the facts, run the full parity check
(`ci/parity-full.sh`, about four hours: DESIGN.md, Testing) and keep its `build/parity/parity.txt`
with the release notes.

```bash
# 1. Add a <li><b>0.2.0</b>: …</li> entry to the change-notes in
#    intellij/src/main/resources/META-INF/plugin.xml (the Marketplace shows it; the release
#    fails fast without it), and commit it.
# 2. Tag that commit and push the tag.
git tag v0.2.0
git push origin v0.2.0
```

That one push first checks, publishing nothing until all pass:

- the eight publishing secrets are set (below), before anything else;
- the tag is `vMAJOR.MINOR.PATCH`, and `plugin.xml` has change notes for that version;
- the build and tests pass;
- the Plugin Verifier passes against every IDE of the plugin's range.

Then, in parallel, it:

- attaches `sinew-slim.jar` and the plugin zip to a GitHub Release `v0.1.0` with generated
  notes, creates the rolling `v0` release if it is not there, and replaces its jar;
- builds and pushes the image `ghcr.io/officefloor/sinew-slim` tagged `0.1.0`, `0.1`, `0` and
  `latest`;
- signs the plugin and uploads it to the JetBrains Marketplace (JetBrains then reviews it, so
  the version appears there a day or two later);
- signs the five libraries and uploads them to Maven Central as one deployment
  (`net.officefloor.sinewslim:sinewslim-*:0.2.0`, each with sources, javadoc and its POM);

and when all four succeed, moves the `v0` tag to this commit. A failure in one of those four
(an outage, say) does not undo the others: fix it and re-run the failed jobs.

**Then publish the Central deployment by hand.** The upload waits in the
[Central Portal](https://central.sonatype.com/publishing/deployments), validated but not
public. Check it there (five components, signed) and press *Publish*. A Central release can never
be deleted or replaced, which is why this one step is not automatic. Dropping the deployment
instead leaves nothing behind, and the version can be released again.

## One-time setup

### JetBrains Marketplace (the IntelliJ plugin)

A release needs four secrets, all from the OfficeFloor organisation: `PUBLISH_TOKEN` and the
signing trio. If any is missing, the release fails at its first step, before
anything is published; `publishPlugin` also refuses to run without them, so the plugin is never
published unsigned.

1. **The first upload is by hand.** The Marketplace only accepts uploads from Gradle for a
   plugin that already exists there. Sign in at https://plugins.jetbrains.com with the
   JetBrains account that should own the listing, choose **Upload plugin**, and upload
   `sinew-slim-0.1.0.zip` from the v0.1.0 GitHub Release. Pick the Apache 2.0 license,
   category *Code tools*, and link the repository. Then, on the plugin's page, **Edit > Vendor**
   moves it to an *OfficeFloor* organisation (create one under your profile's
   Organizations), so others can be given access.
2. **The secrets** are shared by all OfficeFloor plugins: the organisation secrets
   `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD`, set by
   `setup-jetbrains-publishing.sh` in
   [OfficeFloorMaintenance](https://github.com/officefloor/OfficeFloorMaintenance#publishing-intellij-plugins-to-the-jetbrains-marketplace),
   whose `jetbrains-plugin-repositories.txt` lists this repository. Don't set them here as
   repository secrets: those would override the organisation's.

   To check signing locally with the identity's files (if a copy was kept): `CERTIFICATE_CHAIN="$(cat chain.crt)"
   PRIVATE_KEY="$(cat private_encrypted.pem)" PRIVATE_KEY_PASSWORD=… ./gradlew
   :intellij:signPlugin`, then `CERTIFICATE_CHAIN="$(cat chain.crt)" ./gradlew
   :intellij:verifyPluginSignature`.

Once the listing is live, add its link and the IDE's **Settings > Plugins > Marketplace**
install route to the README.

### Maven Central (the libraries)

The four secrets `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `GPG_PRIVATE_KEY` and
`GPG_PASSPHRASE` are the OfficeFloor organisation's, set by `setup-central-publishing.sh` in
[OfficeFloorMaintenance](https://github.com/officefloor/OfficeFloorMaintenance) for the
repositories in its `central-publishing-repositories.txt`, SinewSlim among them: the same Central
account and signing key as OfficeFloor's own releases, under the `net.officefloor` namespace. The
release workflow hands them to Gradle as `ORG_GRADLE_PROJECT_mavenCentralUsername`, `...Password`,
`signingInMemoryKey` and `signingInMemoryKeyPassword`. Do not set them as repository secrets here.

A dry run needs none of them: `./gradlew -Pversion=0.2.0 publishAllPublicationsToStagingRepository`
writes the unsigned bundle to `build/staging-deploy`.

### GHCR (the Docker image)

No secret: the `docker` job pushes with the built-in `GITHUB_TOKEN`. **The package must stay
public**: the GitLab and Jenkins templates and the pre-commit hook pull
`ghcr.io/officefloor/sinew-slim:0` anonymously.

Nothing needs doing for that: the package is created by this public repository's workflow, so
it took the repository's public visibility on the first release (v0.1.0). Check it after a
release, or after any change to the organisation's package policy, with an anonymous pull:

```bash
docker logout ghcr.io
docker pull ghcr.io/officefloor/sinew-slim:0
```

If that fails with `unauthorized`, the package has become private: make it public at
https://github.com/orgs/officefloor/packages/container/sinew-slim/settings (Danger Zone >
Change visibility; public packages must be allowed at the organisation level first).

### The repository

The repository is public, which the Action and the release assets need to be reachable by
others; keep it so.

### GitHub Marketplace (the Action)

The Action is listed as [sinew-slim](https://github.com/marketplace/actions/sinew-slim). A
listing is a flag on a GitHub Release, and only the web UI can set it: the `gh release create`
in `release.yml` makes the Release but never lists it. So each release is one click more:

1. **Once, for the organisation:** an owner of `officefloor` accepts the GitHub Marketplace
   Developer Agreement (the Publish checkbox below stays disabled until then). Publishing needs
   two-factor authentication on the account doing it.
2. **Per release**, after the workflow has finished: open the versioned Release (`v0.2.0`, not
   the rolling `v0`), **Edit**, tick **Publish this Action to the GitHub Marketplace**, check it
   reports "Everything looks good!", and **Update release**. The first time, also pick the
   categories: *Code quality* primary, *Code review* secondary.

GitHub checks the `action.yml` at the Release's tag: one `action.yml` at the root, a `name`
no other Marketplace action, user, organisation or category has, `branding`, and a
`description` under 125 characters. Keep those so when editing it.

Skipping step 2 for a release breaks nothing: `@v0` and `@vX.Y.Z` work whether or not the
listing shows the newest version; the listing just stays on the last one ticked.
