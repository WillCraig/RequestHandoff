# RequestHandoff

RequestHandoff is a free, open-source IntelliJ Platform tool that exports [JetBrains HTTP Client](https://www.jetbrains.com/help/idea/http-client-in-product-code-editor.html) `.http` and `.rest` files, folders of those files, and `http-client.env.json` environments.

It writes [Postman Collection v2.1](https://schema.getpostman.com/json/collection/v2.1.0/collection.json) JSON and one Postman environment file for each named environment. It parses request files itself and does not use the HTTP Client plugin, so it runs in every JetBrains IDE, including Community editions.

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

The closest `http-client.env.json` between the selection and the project root is used. `http-client.private.env.json` is detected the same way, but its contents are not read unless you enable private values. Nested environment objects are flattened with dotted names (`auth.user`). Values that come from the private file are marked `secret` in the Postman environment.

`{{var}}` placeholders are kept as Postman variables. File-level `@name = value` lines become collection variables.

## Mapping

| HTTP Client | Postman Collection v2.1 |
| --- | --- |
| `### Title` and `# @name id` | Request name. The `###` title is used when it is present; otherwise `# @name` is used. |
| Method and URL | `request.method` and `url`, with the query string in `url.query`. `GRAPHQL` is sent as `POST`. |
| Header lines | `request.header`. `Authorization` stays a header. |
| `{{var}}` | Left as a Postman `{{var}}`. |
| `@var = value` | Collection variable. |
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
- Dynamic variables such as `{{$uuid}}` are left unchanged.
- `run` imports, response redirects (`>>`), saved response examples, and request flags such as `@no-log` are listed in the report and are not converted.
- Private environment values are never written unless you explicitly enable them for that export. The report does not print those values.

## Later milestones

Not in this version:

- Script translation from `client.*` to `pm.*`
- Postman auth objects
- Dynamic variables (`$uuid` to `$guid`, and the rest)
- Marketplace signing and publishing
- Import-back / round-trip notes

## Build

The project follows the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) layout: Gradle Kotlin DSL, the IntelliJ Platform Gradle Plugin 2.x, and a Gradle wrapper. The plugin module depends only on `com.intellij.modules.platform`.

Parsing and export live in the `core` module. Those tests do not start an IDE. They include golden-style fixtures and validate generated collections against the vendored Postman Collection v2.1 schema.

The plugin compiles against IntelliJ IDEA Community 2024.3 (`sinceBuild` 243) with a Java 21 toolchain. Platform 2024.3 requires Java 21; the Gradle plugin's configuration check rejects an older bytecode target. `until-build` is omitted so newer IDE builds stay compatible.

```bash
./gradlew build test verifyPlugin
```

GitHub Actions runs that command on `ubuntu-latest` only.

## License

[MIT](LICENSE)
