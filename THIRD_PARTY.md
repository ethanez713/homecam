# Third-party assets

Bundled in the app, alongside the libraries Gradle resolves.

| Asset | Where | Licence |
|---|---|---|
| Piazzolla (Huerta Tipográfica) | `app/src/main/res/font/piazzolla.ttf` | SIL Open Font License 1.1 |
| Instrument Sans (Instrument) | `app/src/main/res/font/instrument_sans.ttf` | SIL Open Font License 1.1 |
| IBM Plex Mono (IBM) | `app/src/main/res/font/ibm_plex_mono_*.ttf` | SIL Open Font License 1.1 |
| Lucide icons 0.469.0 | `app/src/main/res/drawable/lucide_*.xml`, converted from the SVGs | ISC |

The typefaces and the icon set are the ones the author's design system specifies. An icon is
added by converting its Lucide SVG to a vector drawable with a 2 px round-capped stroke and
no fill, named `lucide_<slug>.xml`.
