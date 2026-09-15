# Face recognition model

Character tagging identifies people with a TensorFlow Lite face recognition model. Without one the app
falls back to landmark geometry, which cannot reliably tell people apart, and the People tab says so.

No model is committed to this repository: which one ships inside the app is a licensing decision, and
Google publishes face *detection* models (ML Kit, bundled already) but not face *recognition* ones.
There are three ways to supply it.

## 1. Install it from the app (no rebuild)

People tab → **Install model** → pick a `.tflite` file from storage. It is validated before it
replaces anything, copied into the app's private storage, and used ahead of any bundled copy. Best for
trying models out, since swapping takes seconds.

## 2. Drop it in this folder

```
app/src/main/assets/mobilefacenet.tflite
```

It is then packed into every build. Best once you have settled on a model.

## 3. Let the build fetch it

Set these in `gradle.properties` (or as `SCRTVWR_FACE_MODEL_URL` / `SCRTVWR_FACE_MODEL_SHA256`):

```properties
faceModelUrl=https://example.com/mobilefacenet.tflite
faceModelSha256=<sha-256 of that file>
```

`preBuild` then depends on `downloadFaceModel`, which fetches the file into this folder, verifies the
checksum and refuses to use a mismatch. Nothing is downloaded while `faceModelUrl` is unset. Best for
CI, where the model should not live in version control. Supply the checksum: it is what stops a
changed file at that URL from quietly ending up in your app.

## What the model has to look like

| Property    | Expected                                                       |
|-------------|----------------------------------------------------------------|
| Input       | one square RGB image, normally 112 x 112, float32               |
| Input range | `(pixel - 127.5) / 128` - the ArcFace/MobileFaceNet convention  |
| Output      | one embedding vector, normally 128 or 192 floats                |
| Size        | around 1 MB                                                    |

Input size and embedding length are read from the model, so any export with that shape works. A model
trained on `0..1` inputs needs `MEAN` and `STD` changed in `FaceEmbeddingModel.kt`. Anything that is
not a square-input, single-vector-output TFLite model is rejected with a reason when installed.

`noCompress += "tflite"` in `app/build.gradle.kts` keeps the file uncompressed in the APK so it can be
memory-mapped rather than unpacked.

## Changing model

Tags are stamped with a descriptor id built from the model's shape and a checksum of the file itself,
so two different models are never confused even if their shapes match. Installing a different model
makes the stored vectors incomparable: the app notices, clears the old tags and re-tags from scratch.
Names given to characters go with them, so change models deliberately.
