# Loader icon attribution

Icons in this directory identify the loader or server platform a CurseForge or Modrinth project reports in the Add
Mods dialog. They are bundled unmodified (byte-identical to the source) and are never fetched at runtime. These
licenses cover copyright only; they grant no trademark rights, and the icons are used solely to name the projects
they belong to. No endorsement by those projects is implied.

## neoforge.png

- Source: https://github.com/neoforged/.github/blob/756a0a0ccc19fe2fc09d0ced18c4ed6876700b36/art/neoforged_icon_16x16.png
  (added in neoforged/.github#47)
- Copyright: the NeoForged team; branding art created by Ridanisaurus
  (https://github.com/neoforged/.github/blob/main/art/README.md)
- License: Creative Commons Attribution 4.0 International (CC BY 4.0),
  https://creativecommons.org/licenses/by/4.0/ ; license text:
  https://github.com/neoforged/.github/blob/7d4e38307737cfb7de594a44bae5a48c3208d078/art/LICENSE
- Modifications: none (renamed from `neoforged_icon_16x16.png`)
- SHA-256: 05ac21262ebf28fb3304b7be9ff48040f57972d71bd128fb6fa041e3eeea0238

## quilt.png

- Source: https://github.com/QuiltMC/art/blob/0ddcb7046d3835f884b05ff169083029b793d02f/brand/16png/quilt_mini_icon_transparent.png
- Copyright: QuiltMC; official branding primarily by Prospector (https://github.com/QuiltMC/art)
- License: CC0 1.0 Universal, as stated for the `brand` directory in
  https://github.com/QuiltMC/art/blob/5a656285dae6357828d2f072ffdcf1f10ca35956/brand/LICENSE
- Modifications: none (renamed from `quilt_mini_icon_transparent.png`)
- SHA-256: ebdf60e0eb8eda5c319fddcc05834f6cf05be138105b10354a49b01071b25f88

## Loaders without a bundled icon

Fabric, Forge, Legacy Fabric, Paper and Purpur use a generic letter tile drawn in code (see
`com.atlauncher.gui.card.LoaderIndicatorStrip`). It is not, and does not imitate, any project's logo. Their official
artwork is not bundled because permission to redistribute it in this launcher has not been established:

- Fabric: the loader repository's Apache-2.0 license has no notice covering its icon, and the website logo is under
  different (CC BY-NC-SA 4.0) terms.
- Forge: its maintainer has asked third parties not to use the logo.
- Paper: the published brand rules forbid changing the logomark's dimensions, and no copyright license is named.
- Purpur: no brand or asset terms were found beyond the website repository's general MIT license.
- Legacy Fabric: not reviewed.

Only add an icon here after verifying the license for that exact file, and record it in this file.
