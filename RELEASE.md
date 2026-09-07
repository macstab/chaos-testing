# Release Process

Release pipeline for `macstab-chaos-testing` - GitHub Packages (automated),
Maven Central (manual), and Javadoc on GitHub Pages (automated).

## First-time Maven Central setup

Only needed **once** per namespace. The namespace `com.macstab.chaos` is a
sub-namespace of `com.macstab` - if `com.macstab` is already verified for other
projects, Central Portal inherits that verification automatically.

1. **Sonatype Central Portal account** - https://central.sonatype.com/
2. **Verify namespace** (only if not already verified at `com.macstab`):
   - Namespaces -> Add Namespace -> `com.macstab.chaos`
   - Add the `sonatype-verify=...` TXT record to `macstab.com`
   - Wait for DNS propagation, click Verify
3. **Generate user token** (Account -> Generate User Token), put into
   `~/.gradle/gradle.properties`:
   ```properties
   ossrhUsername=<token-username>
   ossrhPassword=<token-password>
   ```
4. **GPG signing key**:
   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --export-secret-keys <KEY_ID> > ~/.ssh/mavencentral_gpg_keyring.gpg
   ```
5. **Gradle signing config** in `~/.gradle/gradle.properties`:
   ```properties
   signing.keyId=<last-8-chars-of-key-id>
   signing.password=<gpg-passphrase>
   signing.secretKeyRingFile=/Users/you/.ssh/mavencentral_gpg_keyring.gpg
   ```

## Required GitHub secrets

Configure at `Settings -> Secrets and variables -> Actions`:

| Secret | Used by | Purpose |
|--------|---------|---------|
| `BOT_GITHUB_TOKEN` | semantic-release, publish-snapshot | Push tags, create releases, manage GitHub Packages |
| `BOT_USER_NAME` | semantic-release (main->develop merge) | Git config for bot commits |
| `BOT_USER_EMAIL` | semantic-release (main->develop merge) | Git config for bot commits |
| `NPM_TOKEN` | semantic-release | Optional, only if publishing to npm |

## Required GitHub Pages setting

`Settings -> Pages -> Build and deployment -> Source: GitHub Actions`.
The `deploy-javadoc` workflow pushes the aggregated Javadoc on every release.

## Publishing overview

| Target          | Trigger                        | Version          | Flow      |
|-----------------|--------------------------------|------------------|-----------|
| GitHub Packages | Build on `develop` succeeds    | `X.Y.Z-SNAPSHOT` | Automatic |
| GitHub Packages | Push tag `vX.Y.Z`              | `X.Y.Z`          | Automatic |
| GitHub Pages    | Push tag `vX.Y.Z`              | `X.Y.Z` Javadoc  | Automatic |
| Maven Central   | Manual checkout of tag + push  | `X.Y.Z`          | Manual    |

## Branching model

- `develop` - ongoing work. Every push builds, tests, and publishes a SNAPSHOT.
- `main` - release branch. Every merge triggers semantic-release, which
  analyses conventional-commit messages since the last tag, bumps the version,
  creates the tag `vX.Y.Z`, generates `CHANGELOG.md`, and cuts a GitHub
  release. The tag push then triggers `publish-release.yml` (GitHub Packages)
  and `deploy-javadoc.yml` (GitHub Pages).

## Code coverage (Jacoco)

Every test task is finalized by `jacocoTestReport` (XML + HTML). The
root-level `jacocoAggregatedReport` task combines coverage from all subprojects
into `build/reports/jacoco/aggregated/{html,jacoco.xml}`. CI uploads these as
a workflow artifact named `jacoco-coverage`.

## Workflows

- `build.yml` - spotless + `./gradlew build jacocoAggregatedReport` on PRs and pushes to main/develop/feat/**
- `publish-snapshot.yml` - publishes `X.Y.Z-SNAPSHOT` to GitHub Packages
  after a successful build on develop
- `semantic-release.yml` - tags the release and cherry-picks back to develop
  after a successful build on main
- `publish-release.yml` - publishes `X.Y.Z` to GitHub Packages on tag push
- `deploy-javadoc.yml` - builds the aggregated Javadoc and deploys it to
  GitHub Pages on tag push

## Commit message format (Conventional Commits)

Semantic-release reads commit messages to pick the version bump:

| Type     | Version Bump | Example                         |
|----------|--------------|---------------------------------|
| `feat:`  | MINOR        | `feat: add JMX reset hook`      |
| `fix:`   | PATCH        | `fix: bootstrap resource lookup`|
| `perf:`  | PATCH        | `perf: reduce advice overhead`  |
| `chore:` | PATCH        | `chore: bump deps`              |
| `docs:`  | none         | `docs: fix README recipe`       |
| `feat!:` | MAJOR        | `feat!: remove legacy API`      |

## Publishing a release to Maven Central (manual)

```bash
# After the vX.Y.Z tag is on main:
git fetch --tags
git checkout vX.Y.Z

# Build, sign, and stage signed artifacts into the Central Portal staging repo.
# Requires ossrhUsername, ossrhPassword, and signing.* in ~/.gradle/gradle.properties.
./gradlew clean publish -x test --no-daemon --stacktrace

git checkout main
```

Then at https://central.sonatype.com/ -> Deployments, review the staged
artifacts and click **Publish**. Artifacts sync to Maven Central in 10-30 minutes.

## Troubleshooting

**SNAPSHOT not publishing** - check Actions logs for Build & Test; the
snapshot workflow only runs after a successful build on `develop`.

**Release tag exists but no GitHub Release** - semantic-release runs on main,
not on tag. Check the `Semantic Release` workflow run for failures.

**Javadoc deploy fails with "pages not enabled"** - enable
`Settings -> Pages -> Source: GitHub Actions` once; subsequent deploys work.

**`./gradlew publish` fails locally** - expected if OSSRH credentials are
not in `~/.gradle/gradle.properties`. The build script skips the OSSRH
repo and signing tasks when credentials are absent.

**Maven Central HTTP 401** - regenerate the token at
https://central.sonatype.com/ -> Account -> Generate User Token.

**Maven Central HTTP 402/405** - namespace not verified. Verify
`com.macstab.chaos` (or the parent `com.macstab`) in Central Portal.

## Version bump

After a release, bump the next development version on develop:

```bash
git checkout develop
# Edit gradle.properties: version=X.Y+1.0-SNAPSHOT
git commit -m "chore: bump to X.Y+1.0-SNAPSHOT"
git push
```

Semantic-release maintains the release-line versions itself on main.
