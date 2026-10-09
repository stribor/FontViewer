# Build and compatibility checks

The **Build and verify** GitHub Actions workflow runs on pushes to `master` or
`main`, `v*` tags, pull requests, and manual dispatch from the repository's
Actions page. It uses Java 21 and the checked-in Gradle wrapper.

The build job compiles against IntelliJ IDEA 2025.3, runs `check` (including
the native font and diff regression checks), validates the plugin package, and
uploads the installable ZIP as the **plugin-distribution** artifact. Build logs,
reports, and rendered regression previews are available as **build-reports**.

Four independent Plugin Verifier jobs check that exact ZIP against:

- IntelliJ IDEA 2025.3, the minimum supported release.
- The latest stable IntelliJ IDEA release.
- CLion 2025.3, the minimum supported release.
- The latest stable CLion release.

`tools/verification-matrix.py` resolves current stable releases from JetBrains'
release API each time the workflow runs. EAP and RC releases are excluded. Each
job uploads its logs and HTML/plain-text reports as **verifier-IU-minimum**,
**verifier-IU-latest**, **verifier-CL-minimum**, or **verifier-CL-latest**.

Compatibility problems, invalid packages, missing dependencies, and prohibited
internal, non-extendable, or override-only API usage fail verification. Other
warnings remain visible in the reports and should be reviewed before release.
These are binary compatibility checks; they do not replace interactive testing
in the target IDEs.

The workflow has read-only repository permissions and does not publish to
Marketplace or create GitHub releases. The ZIP is an unsigned build artifact;
release signing is a separate publishing step. GitHub Actions dependencies are
pinned to release commits, and the Gradle distribution is checksum-verified.

## Run locally

Use JDK 21. Building against the oldest supported SDK requires downloading it
on the first run:

```bash
./gradlew check verifyPluginStructure buildPlugin
```

Check the built plugin against both minimum releases:

```bash
./gradlew verifyPlugin
```

Check one specific product/version (`IU` for IDEA, `CL` for CLion):

```bash
./gradlew verifyPlugin -PverificationProduct=CL -PverificationVersion=2026.2.3.1
```

To verify an existing ZIP without rebuilding it:

```bash
./gradlew verifyPlugin \
  -PverificationArchive=build/distributions/font-preview-1.1.1.zip \
  -PverificationProduct=IU -PverificationVersion=2025.3
```

An installed IDE can be used as a verification target instead of downloading
one:

```bash
./gradlew verifyPlugin \
  -PverificationArchive=build/distributions/font-preview-1.1.1.zip \
  -PverificationLocalIde=/path/to/CLion.app
```

Reports are saved under `build/reports/pluginVerifier/`.
