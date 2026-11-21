# Bundled on-device vision runtime

The Android ARM64 debug build bundles Cactus runtime 4.0.0 and Mirage weights so scene inference can run without uploading the image. Both repositories identify their license as Apache 2.0. The runtime files came from `Cactus-Compute/cactus` revision `1a028f5a3972b67dd8d8752f882113d475e7ec4c`; the model came from `Cactus-Compute/mirage` revision `3d03cee88c0acbe4e71b94b567ab14e6e55f40c9`.

| Bundled file | SHA-256 |
| --- | --- |
| `android/app/src/main/cpp/cactus.h` | `facefca17a0d5e715bd92a8b0ea272c1ac34a20a798003b1e691c8fba5ace6e2` |
| `android/app/src/main/cpp/libcactus.a` | `4f75e975f6a859979a2715a89fdbc0fc32742cde001f8674598cf89017d39fdf` |
| `android/app/src/main/assets/mirage.cact` | `4249a0f7515272ff76b9414bec17d7e7daf84b15b66c7fb4f17fce1e66f9f237` |

Sources: [Cactus runtime release](https://huggingface.co/Cactus-Compute/cactus/tree/1a028f5a3972b67dd8d8752f882113d475e7ec4c/releases/4.0.0/android-arm64), [Mirage model](https://huggingface.co/Cactus-Compute/mirage/tree/3d03cee88c0acbe4e71b94b567ab14e6e55f40c9). The runtime header and static library hashes matched the published release manifest. The model hash records the downloaded file. Mirage's scene and OCR output is model inference; Malayalam text still uses the separate consented Indic OCR path.
