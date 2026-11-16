# RequestHandoff

RequestHandoff is a free, open-source IntelliJ Platform tool that converts between [JetBrains HTTP Client](https://www.jetbrains.com/help/idea/http-client-in-product-code-editor.html) `.http` and `.rest` files and [Postman Collection v2.1](https://schema.getpostman.com/json/collection/v2.1.0/collection.json).

It exports request files, folders of those files, and `http-client.env.json` environments, and imports a collection plus an optional Postman environment back to `.http` files. It parses request files itself and does not use the HTTP Client plugin, so it runs in every JetBrains IDE, including Community editions.

Folder export and environment export are included at no cost.

Postman is a trademark of Postman, Inc. JetBrains and IntelliJ are trademarks of JetBrains s.r.o. This project is not affiliated with either.

## Install

Marketplace install is a later milestone. For v0.1, build the plugin and install the zip from disk.

Requirements: JDK 21 (Gradle can download it through the toolchain) and an IDE build `243` or newer (2024.3+).

```bash
./gradlew buildPlugin
```

The zip is written to `build/distributions/RequestHandoff-0.1.0.zip`.

In the IDE: **Settings | Plugins | gear icon | Install Plugin from Disk…**, then choose that zip and restart the IDE.

To try it from sources:

```bash
./gradlew runIde
```

## Usage

1. Right-click a `.http` or `.rest` file, or a folder that contains them, in the Project view. You can also right-click inside an open request file, or use **Tools | Export with RequestHandoff**.
2. Choose an output directory.
3. Leave **Include private environment values** unchecked unless you intend to write secrets from `http-client.private.env.json` into the environment files. That option is off every time you open the dialog.
4. Export writes:
   - `<name>.postman_collection.json`
   - one `<env>.postman_environment.json` for each named environment
   - `requestHandoff-report.md`
5. A notification reports how many requests were exported, how many were skipped, and how many warnings were recorded. **Open report** opens `requestHandoff-report.md`.

A single file becomes a collection whose requests sit at the top level. A folder becomes a collection of the same name: directories and request files become folders, and the requests inside each file stay in file order.

The closest `http-client.env.json` between the selection and the project root is used. `http-client.private.env.json` is detected the same way, but its contents are not read unless you enable private values. Nested environment objects are flattened with dotted names (`auth.user`). Values that come from the private file are marked `secret` in the Postman environment. Dynamic variables inside environment values are rewritten the same way as in requests. The report lists unmapped tokens and does not print environment values.

`{{var}}` placeholders are kept as Postman variables. File-level `@name = value` lines become collection variables. Dynamic variables are rewritten when Postman has an equivalent (`{{$uuid}}` becomes `{{$guid}}`). `{{$env.NAME}}`, `{{$processEnv NAME}}`, and `{{$dotenv NAME}}` become `{{NAME}}`. The value on this machine is not copied.

## Import

1. Choose **Tools | Import with RequestHandoff**. The action is also on the Project view popup menu, and on the editor popup for a `.json` file.
2. Choose a Postman Collection v2.1 JSON file. A Postman environment file is optional.
3. Choose an output directory.
4. Import writes:
   - `.http` files. A folder of requests becomes one file. A folder that also contains folders becomes a directory.
   - `http-client.env.json` for environment values that are not secrets. Dotted keys such as `auth.user` are nested, which is how HTTP Client reads `{{auth.user}}`.
   - `http-client.private.env.json` when a Postman value is marked `secret`.
   - `requestHandoff-import-report.md`
5. A notification reports how many requests were imported, how many were skipped, and how many warnings were recorded. **Open report** opens the import report.

Secret values are written only to `http-client.private.env.json`. They are not written to the public environment file, the import report, or logs. Disabled environment values are skipped. Keep the private file out of version control.

`{{var}}` placeholders are kept. Collection variables become `@name = value` lines. A collection that becomes one `.http` file keeps those lines in that file. A collection that becomes several files puts them in `variables.http`. The `raw` URL is used when Postman provides one.

## Mapping

| HTTP Client | Postman Collection v2.1 |
| --- | --- |
| `### Title` and `# @name id` | Request name. The `###` title is used when it is present; otherwise `# @name` is used. |
| Method and URL | `request.method` and `url`, with the query string in `url.query`. `GRAPHQL` is sent as `POST`. |
| Header lines | `request.header`. `Authorization` stays a header. |
| `{{var}}` | Left as a Postman `{{var}}`. |
| `{{$uuid}}`, `{{$random.uuid}}` | `{{$guid}}`. Postman also documents `{{$randomUUID}}`; both are UUID values, and the export uses `{{$guid}}` because that entry is specified as uuid-v4. |
| `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$randomInt}}` | The same names. |
| `{{$random.integer}}` | `{{$randomInt}}`. With `(from, to)`, the HTTP Client range is not applied. Postman `$randomInt` is always an integer from 0 to 1000, and the report says so. |
| `{{$random.email}}` and other `$random.*` helpers with a Postman equivalent | The matching `{{$random…}}` variable. Helpers with no equivalent, including `$random.alphabetic(length)`, `$random.alphanumeric(length)`, `$random.hexadecimal(length)`, `$random.float`, and `$exampleServer`, are left unchanged and listed in the report. |
| `{{$env.NAME}}`, `{{$processEnv NAME}}`, `{{$dotenv NAME}}` | `{{NAME}}`. The host or dotenv value is not read. |
| `@var = value` | Collection variable. Dynamic variables in the value are rewritten the same way. |
| JSON, XML, HTML, and other text bodies | `body.mode = raw` with a language hint. |
| `application/x-www-form-urlencoded` | `body.mode = urlencoded`. |
| Multipart text parts | `formdata` text fields. |
| Multipart file parts and `< ./file` bodies | File `src` (the path as written). File bytes are not embedded. |
| `X-REQUEST-TYPE: GraphQL` or the `GRAPHQL` method | `body.mode = graphql`. A JSON object after a blank line, or a JSON body with `query`, becomes GraphQL variables. |
| Directories and `.http` / `.rest` files | Collection folders. |

## Limitations

v0.1 does not translate everything. The export report states these limits for every run, and lists anything in the selection that was skipped or only partly converted.

- Response-handler scripts (`> {% %}` and `> ./script.js`) and pre-request scripts (`< {% %}` and `< ./script.js`) are **not** translated. Inline scripts are copied into the collection as commented-out script text. Script files are named in those comments and are not inlined.
- WebSocket and gRPC requests are skipped, with a warning in the report.
- `Authorization` headers are copied as headers. Postman auth objects are not created.
- Dynamic variables with no Postman equivalent are left unchanged. `{{$random.integer(from, to)}}` becomes `{{$randomInt}}` and loses its range. Host environment variables are not read.
- `run` imports, response redirects (`>>`), saved response examples, and request flags such as `@no-log` are listed in the report and are not converted.
- Private environment values are never written unless you explicitly enable them for that export. The report does not print those values.

Import uses that export surface in reverse. These extra limits apply:

- Scripts are copied into the `.http` file as comments. They are not turned into `< {% %}` or `> {% %}` blocks, and a later export does not recreate Postman events from those comments. `ImportMappings` in `core/.../ImportMappers.kt` is the hook for script translation.
- Postman auth objects are reported and skipped. `Authorization` headers are kept. Collection and folder auth are not copied onto child requests. `ImportMappings` is the hook for auth objects.
- Dynamic variables such as `{{$uuid}}` are left unchanged. `ImportMappings` is the hook for renaming them.
- WebSocket and gRPC requests, saved examples, proxy settings, and client certificates are skipped and listed in the import report.
- A multipart boundary is generated. The parts are preserved. `.rest` files come back as `.http`.
- A single-file export has no folders, so import writes one file named after the collection. Root-level requests that share a collection with folders are written to `requests.http`.
- GraphQL whose Postman method is POST is written with the `GRAPHQL` method. HTTP Client stores that as POST. A raw language hint becomes a `Content-Type` header when the request did not already have one.
- Header order and trailing whitespace may differ. The `raw` URL is kept when it is present, so a disabled query parameter that is still inside `raw` stays in the URL.

## Later milestones

Not in this version:

- Script translation from `client.*` to `pm.*`
- Postman auth objects
- Marketplace signing and publishing

## Build

The project follows the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) layout: Gradle Kotlin DSL, the IntelliJ Platform Gradle Plugin 2.20.1, and a Gradle wrapper. The plugin module depends only on `com.intellij.modules.platform`.

Parsing and export live in the `core` module. Those tests do not start an IDE.

The plugin compiles against IntelliJ IDEA Community 2024.3 (`sinceBuild` 243) with a Java 21 toolchain. Platform 2024.3 requires Java 21; the Gradle plugin's configuration check rejects an older bytecode target. `until-build` is omitted so newer IDE builds stay compatible.

```bash
./gradlew buildPlugin
```

## Tests

Parser, exporter, and importer tests live in `:core`. They load `.http` / `.rest` fixtures (including a kitchen fixture with comments, variables, multi-line bodies, file references, headers, authorization, environments, GraphQL, and form/multipart data), compare the Postman Collection v2.1 JSON and environment JSON with golden files, and validate the collection against the vendored Postman schema. An in-test read-back checks that the exported JSON matches the parsed request model. The fidelity suite exports those samples and imports the result, then imports a representative collection (GraphQL, form and multipart, file refs, collection variables, WebSocket and gRPC) and exports it again. Comparisons allow header order, trailing whitespace, and a regenerated multipart boundary, and they fail on semantic drift. Secret Postman values are asserted to be absent from the public environment, the `.http` files, and the report.

Light tests in the plugin module use `BasePlatformTestCase`. They cover action enablement, the export dialog (output directory, private values off by default), the import dialog (collection file, optional environment, output directory), the written collection, environment, and report files, and the notification counts. They run headless inside Gradle and do not open a window. The IDE Starter smoke test covers export. Import is covered by the light tests rather than a second IDE boot.

`integrationTest` is separate and is not part of `build` or `test`. It uses the IDE Starter and Driver to boot IntelliJ IDEA Community 2024.3 with the built plugin, open a sample `.http` file, run **Export with RequestHandoff**, and assert the collection JSON. The Driver confirms the dialog with the OK button's `doClick`, because a Robot mouse click does not land when the session has no display. On Linux, Starter starts Xvfb when `DISPLAY` is unset. The first run downloads the IDE.

```bash
./gradlew :core:test
./gradlew :test
./gradlew integrationTest
```

Plugin Verifier runs are split so an EAP failure can be reported without failing the stable check. The default run is the sinceBuild IDE (`current()`, IDEA Community 2024.3) plus `latest()` RELEASE builds of IDEA Community, IDEA Ultimate, PyCharm, and WebStorm. `recommended()` is not used: it would also queue every minor release and EAP since 243, and those EAP results would fail the stable check.

```bash
./gradlew verifyPlugin -PrequestHandoff.verifierChannel=current
./gradlew verifyPlugin
./gradlew verifyPlugin -PrequestHandoff.verifierChannel=eap
```

`current` is only the 2024.3 IDE. The default `stable` channel is `current` plus the latest stable builds. `eap` is the latest EAP of IDEA Community and IDEA Ultimate.

## Continuous integration

GitHub Actions runs on `ubuntu-latest` only. The Build workflow runs on every push and pull request:

- unit tests and light fixture tests
- the Starter smoke test, with IDE logs uploaded on failure
- Plugin Verifier against the 2024.3 baseline and the latest stable IDEA Community, IDEA Ultimate, PyCharm, and WebStorm
- Plugin Verifier against the latest IDEA Community and Ultimate EAP builds

A stable verifier failure fails the workflow. An EAP failure does not. The EAP job opens or updates one issue titled `RequestHandoff IDE compatibility needs attention`, labeled `needs-attention` and assigned to WillCraig. A later failure updates that issue instead of opening another one.

The IDE release watch workflow runs every Monday and can be started with `workflow_dispatch`. It records the newest stable and EAP builds from the JetBrains releases API, runs the unit and light tests, and runs the stable and EAP verifier. Any failure opens or updates the same `needs-attention` issue. The workflow has `contents: read` and `issues: write`. Tokens are not written into the issue body.

Dependabot checks Gradle (including the IntelliJ Platform Gradle Plugin and Kotlin) and GitHub Actions weekly.

## License

[MIT](LICENSE)
