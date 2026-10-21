# models

`efficientdet_lite0.tflite` — EfficientDet-Lite0 object detector, COCO 90-class, from
Google's public TensorFlow model download host. **Apache License 2.0.** 4.5 MB, 320×320
quantised input.

`labelmap.txt` — the 90 COCO labels, extracted from the model's own embedded metadata.
The model still carries them internally; reading them at runtime needed the TFLite Task
Library, whose last release predates the 16 KB page size requirement (see
`docs/DECISIONS.md` D8). Shipping them alongside makes the class list reviewable in the
repo instead of hidden inside a 4.5 MB binary, and costs 700 bytes.

The model is openly licensed and downloaded from upstream.

## Why this one

It fits the gate it sits behind. Detection only runs on frames that already passed cheap
frame differencing, at 2–5 fps on downscaled input, on a 2020 phone that must not cook
itself (`PLAN.md` §6, §9). Lite0 is the smallest of the family and quantised; anything
larger buys accuracy the gate mostly makes irrelevant.

## Class mapping

`PLAN.md` §6 wants `person / vehicle / pet / package`. COCO has the first three almost
directly and does not have the fourth:

| Ours | COCO classes |
|---|---|
| person | person |
| vehicle | bicycle, car, motorcycle, bus, truck, train, boat |
| pet | cat, dog, bird, horse, sheep, cow |
| package | suitcase, backpack, handbag |

**`package` is an approximation and should be read as one.** A cardboard box on a doorstep
is not a COCO class, so it is inferred from the luggage-shaped classes and will both miss
real deliveries and fire on a rucksack. Retraining is the honest fix; mapping is what is
available without a labelled dataset of this particular doorstep.

Both files are duplicated into `app/src/main/assets/` because that is where Android loads
them from; this directory is the source of record and the place the licence is recorded.
Re-extract the labels after any model change:

```sh
python3 -c "
import zipfile, io
d = open('models/efficientdet_lite0.tflite','rb').read()
z = zipfile.ZipFile(io.BytesIO(d[d.find(b'PK\x03\x04'):]))
open('models/labelmap.txt','w').write(z.read('labelmap.txt').decode())
"
```

## Sound (Phase 6)

`yamnet.tflite` — YAMNet audio event classifier, AudioSet 521-class, from Google's public
MediaPipe model host. **Apache License 2.0.** 4.1 MB, 16 kHz mono waveform input, one score
vector per ~0.975 s frame.

`yamnet_labels.txt` — the 521 AudioSet class names, extracted from the model's own embedded
metadata (the `yamnet_label_list.txt` zip entry), shipped alongside so the class list is
reviewable in the repo rather than hidden in the binary.

Both are duplicated into `app/src/main/assets/`. Re-extract the labels after any model change:

```sh
python3 -c "
import zipfile, io
d = open('models/yamnet.tflite','rb').read()
z = zipfile.ZipFile(io.BytesIO(d[d.find(b'PK\x03\x04'):]))
open('models/yamnet_labels.txt','w').write(z.read('yamnet_label_list.txt').decode())
"
```

### Class mapping

The 521 AudioSet classes are mapped onto a small user-facing set in
`core/.../sound/SoundClasses.kt`. Like `package` on the vision side, several are
approximations:

| Ours | AudioSet classes |
|---|---|
| dog | Dog, Bark, Yip, Howl, Bow-wow, Growling, Whimper (dog) |
| alarm | Alarm, Alarm clock, Siren, Civil defense siren, Buzzer, Smoke detector/smoke alarm, Fire alarm |
| doorbell | Doorbell, Ding-dong |
| glass | Glass, Chink/clink, Shatter, Breaking |
| shout | Shout, Bellow, Yell, Children shouting, Screaming |
| knock | Knock, Tap, Slam, Thump/thud |
| voice | Speech, Child speech, Conversation, Narration/monologue |

**`alarm` lumps sirens, smoke alarms and buzzers together**, and **`knock` will fire on any
sharp tap**, not only a door — these are the coarse-but-useful groupings the doorbell/box
distinction cannot be trusted past. The decibel trigger needs none of this and runs on its
own; classification only refines what a loud-enough sound is called.
