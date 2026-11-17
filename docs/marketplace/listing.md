# JetBrains Marketplace listing

Draft for the first upload of RequestHandoff 0.1.0. Paste the description and change notes into the Marketplace form. Add real screenshots in the plugin admin after that upload. This repo does not ship a picture of the IDE.

Postman is a trademark of Postman, Inc. JetBrains and IntelliJ are trademarks of JetBrains s.r.o. This project is not affiliated with either.

## Identity

| Field | Value |
| --- | --- |
| Name | RequestHandoff |
| Plugin id | `com.willcraig.requesthandoff` |
| Vendor | Will Craig |
| Vendor email | 24501963+WillCraig@users.noreply.github.com |
| Vendor URL | https://github.com/WillCraig/RequestHandoff |
| License | MIT. Link the [LICENSE](../../LICENSE) file. Source code is this repository. |
| Pricing | Free. No ads. |
| Logo | `src/main/resources/META-INF/pluginIcon.svg` (light) and `pluginIcon_dark.svg` (dark). 40×40 SVG, packaged in `META-INF`. |

The logo is a request card and an arrow. It is not the IntelliJ Platform Plugin Template mark and not a JetBrains product logo.

## Compatibility

`since-build` is `243` (2024.3+). `until-build` is omitted.

JetBrains documents that an omitted `until-build` means every future IDE build, and Marketplace accepts that. A fixed `until-build` is recommended, not required. [Build number ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html) call this open-end compatibility. [Common errors](https://plugins.jetbrains.com/docs/marketplace/list-of-common-errors-and-warnings.html) say `SuspiciousUntilBuild` can be cleared by leaving the field empty when the plugin should keep working on future IDEs.

The plugin depends only on `com.intellij.modules.platform`. It does not depend on the HTTP Client plugin, so Community editions stay compatible.

## Description

The first line is the Marketplace card summary. HTML matches `plugin.xml`.

```html
<p>RequestHandoff exports JetBrains HTTP Client files (<code>.http</code>, <code>.rest</code>) and folders of them to Postman Collection v2.1, and exports <code>http-client.env.json</code> environments to Postman environment files.</p>
<p>Folder export and environment export are free. Request files are parsed directly, with no dependency on the HTTP Client plugin, so this works in every JetBrains IDE, including Community editions.</p>
<p>Postman is a trademark of Postman, Inc. JetBrains and IntelliJ are trademarks of JetBrains s.r.o. This project is not affiliated with either.</p>
```

## Change notes

```html
<ul>
  <li>0.1.0 — Export <code>.http</code> and <code>.rest</code> files and folders, plus public HTTP Client environments. Private environment values stay opt-in.</li>
</ul>
```

## Getting started

1. Install RequestHandoff from JetBrains Marketplace, or install the signed zip from disk.
2. Right-click a `.http` or `.rest` file, or a folder of them. You can also use **Tools | Export with RequestHandoff**.
3. Choose an output directory. Leave **Include private environment values** unchecked unless you mean to write secrets from `http-client.private.env.json`.
4. The export writes `<name>.postman_collection.json`, one Postman environment file per named environment, and `requestHandoff-report.md`.

## Tags

Pick tags on the upload form. Choose tags for an editor tool that reads HTTP request files. Do not select categories the plugin does not fit.

## Screenshots

After the plugin exists on Marketplace, add screenshots under Media in the plugin admin. The [listing guide](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html) asks for at least 1200×760 pixels, one aspect ratio, the default IDE theme, and no desktop or browser chrome.

Useful frames:

- The export dialog, with private values unchecked.
- The Project view action on a folder of `.http` files.
- The written collection file and `requestHandoff-report.md`.

## Still done in the Marketplace UI

- Create the vendor profile on the first upload, and accept the Marketplace developer agreement.
- Confirm this listing text.
- Add the screenshots.
- Set the license to MIT and the source link to this repository.
