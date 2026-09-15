# Face recognition model

Character tagging identifies people with a TensorFlow Lite face recognition model, loaded from this
directory at startup.

## What to drop in here

A MobileFaceNet-style embedding model, named exactly:

```
app/src/main/assets/mobilefacenet.tflite
```

Requirements:

| Property    | Expected                                                        |
|-------------|-----------------------------------------------------------------|
| Input       | one square RGB image, normally 112 x 112, float32                |
| Input range | `(pixel - 127.5) / 128` - the ArcFace/MobileFaceNet convention   |
| Output      | one embedding vector, normally 128 or 192 floats                 |
| Size        | around 1 MB                                                     |

The input size and the embedding length are read from the model itself, so any export with that shape
works. A model trained on `0..1` inputs instead needs `MEAN` and `STD` changed in
`FaceEmbeddingModel.kt`; everything else adapts on its own.

`noCompress += "tflite"` in `app/build.gradle.kts` keeps the file uncompressed in the APK so it can be
memory-mapped rather than unpacked.

## Without the model

The app still builds and runs. `FaceEmbeddingModel.load` returns null, tagging falls back to the
landmark geometry in `FaceGeometry`, and the People tab shows a warning saying the groups will be
rough. Grouping by face proportions cannot reliably tell people apart - that is why the model matters.

## Changing model

Tags are stamped with a descriptor id built from the model's shape. Installing a different model makes
the stored vectors incomparable, so the app detects the mismatch on the next start, clears the old
tags and re-tags from scratch. Names given to characters are lost with them, so change models
deliberately.
