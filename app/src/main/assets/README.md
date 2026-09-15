# Face recognition model

Character tagging identifies people with a TensorFlow Lite face recognition model. Without one the app
falls back to landmark geometry, which cannot reliably tell people apart, and the People tab says so.

## What ships here

`mobilefacenet.tflite` is committed to this repository and packed into every build, so the app
recognises faces out of the box.

| | |
|---|---|
| Source | [syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing](https://github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing) (`app/src/main/assets/MobileFaceNet.tflite`) |
| License | MIT - see `licenses/MobileFaceNet-MIT-LICENSE.txt` |
| Size | 5,233,396 bytes |
| SHA-256 | `d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b` |
| Architecture | MobileFaceNet, trained with InsightFace/ArcFace loss |
| Input | 112 x 112 RGB, `(pixel - 127.5) / 128` |
| Output | 192-value embedding |

Those input and output details match `FaceEmbeddingModel.kt` exactly, which is why this particular
export needs no code changes.

**On the weights.** The MIT license covers the repository as published by its author. The weights
themselves come from InsightFace-style training, whose datasets (MS-Celeb-1M and its derivatives)
carry their own terms and were in some cases withdrawn. That is normal for personal and internal
apps; before distributing SCRTVWR commercially, check whether you need weights with a cleaner
provenance - [sirius-ai/MobileFaceNet_TF](https://github.com/sirius-ai/MobileFaceNet_TF) (Apache-2.0)
is the training and conversion code for producing your own.

## Replacing it

### From the app, with no rebuild

People tab → **Replace model** → pick a `.tflite` from storage. It is validated before it replaces
anything, copied into the app's private storage, and used ahead of this bundled copy. Best for trying
models out, since swapping takes seconds.

### In this folder

Overwrite `mobilefacenet.tflite`. It is then packed into every build.

### From the build

Set these in `gradle.properties` (or as `SCRTVWR_FACE_MODEL_URL` / `SCRTVWR_FACE_MODEL_SHA256`):

```properties
faceModelUrl=https://example.com/mobilefacenet.tflite
faceModelSha256=<sha-256 of that file>
```

`preBuild` then depends on `downloadFaceModel`, which fetches the file into this folder, verifies the
checksum and refuses to use a mismatch. Nothing is downloaded while `faceModelUrl` is unset, and an
already-correct file is left alone. Best for CI, where the model should not live in version control.

## What a replacement has to look like

| Property    | Expected                                                       |
|-------------|----------------------------------------------------------------|
| Input       | one square RGB image, normally 112 x 112, float32               |
| Input range | `(pixel - 127.5) / 128` - the ArcFace/MobileFaceNet convention  |
| Output      | one embedding vector, normally 128 to 512 floats                |
| Size        | 1 - 6 MB                                                       |

Input size and embedding length are read from the model, so any export with that shape works. A model
trained on `0..1` inputs, or on per-image standardisation as FaceNet uses, needs `MEAN` and `STD`
changed in `FaceEmbeddingModel.kt`. Anything that is not a square-input, single-vector-output TFLite
model is rejected with a reason when installed.

`noCompress += "tflite"` in `app/build.gradle.kts` keeps the file uncompressed in the APK so it can be
memory-mapped rather than unpacked.

## Changing model

Tags are stamped with a descriptor id built from the model's shape and a checksum of the file itself,
so two different models are never confused even if their shapes match. Installing a different model
makes the stored vectors incomparable: the app notices, clears the old tags and re-tags from scratch.
Names given to characters go with them, so change models deliberately.
