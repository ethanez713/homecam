# Third-party notices

Assets bundled in the app. Libraries Gradle resolves are not copied into the tree; their checksums are locked in [`gradle/verification-metadata.xml`](gradle/verification-metadata.xml).

License texts are in [`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/).

## Fonts

SIL Open Font License 1.1. Each file below is the upstream copyright statement followed by the license text.

| Font | Files | Notice |
|---|---|---|
| Piazzolla (The Piazzolla Project Authors, 2018) | `app/src/main/res/font/piazzolla.ttf` | [ofl_piazzolla.txt](THIRD_PARTY_LICENSES/ofl_piazzolla.txt) |
| Instrument Sans (The Instrument Sans Project Authors, 2022) | `app/src/main/res/font/instrument_sans.ttf` | [ofl_instrument_sans.txt](THIRD_PARTY_LICENSES/ofl_instrument_sans.txt) |
| IBM Plex Mono (IBM Corp., 2017). Reserved Font Name "Plex" | `app/src/main/res/font/ibm_plex_mono_regular.ttf`, `ibm_plex_mono_medium.ttf`, `ibm_plex_mono_semibold.ttf` | [ofl_ibm_plex_mono.txt](THIRD_PARTY_LICENSES/ofl_ibm_plex_mono.txt) |

## Icons

Lucide 0.469.0, ISC. The drawables in `app/src/main/res/drawable/lucide_*.xml` are converted from the Lucide SVGs: a 2 px round-capped stroke, no fill, named `lucide_<slug>.xml`.

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2022.

The license text is [ISC-Lucide.txt](THIRD_PARTY_LICENSES/ISC-Lucide.txt).

## Models

Apache License 2.0. The license text is included once, in [Apache-2.0.txt](THIRD_PARTY_LICENSES/Apache-2.0.txt).

`models/` is the source of record. The same files are copied into `app/src/main/assets/` because that is where Android loads them. What the class lists can and cannot say is in [models/README.md](models/README.md).

| Asset | Copies | Source | License |
|---|---|---|---|
| EfficientDet-Lite0, `efficientdet_lite0.tflite` | `models/efficientdet_lite0.tflite`, `app/src/main/assets/efficientdet_lite0.tflite` | Google's public TensorFlow model download host. COCO 90-class object detector, 320×320 quantised input. | Apache-2.0 |
| COCO labels, `labelmap.txt` | `models/labelmap.txt`, `app/src/main/assets/labelmap.txt` | The 90 COCO labels extracted from the EfficientDet-Lite0 model's embedded metadata. | Apache-2.0 |
| YAMNet, `yamnet.tflite` | `models/yamnet.tflite`, `app/src/main/assets/yamnet.tflite` | Google's public MediaPipe model host. AudioSet 521-class audio classifier, 16 kHz mono. | Apache-2.0 |
| AudioSet labels, `yamnet_labels.txt` | `models/yamnet_labels.txt`, `app/src/main/assets/yamnet_labels.txt` | The 521 AudioSet class names extracted from the YAMNet model's `yamnet_label_list.txt` entry. | Apache-2.0 |

## Sample image (debug build only)

`app/src/debug/assets/gallery/front-door.jpg` is the scene the debug `GalleryActivity` shows in
place of a camera feed. It is not in release builds. Cropped from
["View from front door"](https://www.flickr.com/photos/27532236@N00/6841655600) by humbert15,
[CC BY 2.0](https://creativecommons.org/licenses/by/2.0/).
