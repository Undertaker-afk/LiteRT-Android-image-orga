# Image Orga

An Android app that sorts and searches your photos entirely on the device.

- **Categories you describe.** Give a category a topic and a description ("Receipts — paper
  receipts, invoices, bills"). Matching photos are sorted into it automatically.
- **Instant search.** Type what you are looking for, in any language, and results appear as
  you type. "Find similar" works from any photo.
- **Background indexing.** New photos are picked up while the app is closed, with a progress
  notification.
- **NPU, GPU or CPU.** On supported chips the app downloads the model build compiled for
  that chip's NPU.

Nothing leaves the phone. The only network use is the one-time model download.

## How it works

The app runs [EmbeddingGemma 2](https://huggingface.co/google/embeddinggemma-2) through
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). The model turns photos and text
into vectors in one shared space, so a photo of a cat lands near the text "cat".

- Each photo is embedded once and its vector stored in a local SQLite database.
- Search embeds the query and ranks all photo vectors by similarity, in memory.
- Each category is embedded as several texts: its topic, its full description, and every
  item listed in the description. A photo joins the category whose best text it matches
  most clearly, if that match is strong enough. Otherwise it stays unsorted.

Sorting is virtual: files are never moved or changed.

## Models

| Model | Download | Notes |
|---|---|---|
| EmbeddingGemma 2 · Text + Vision 440M | 388 MB | Default |
| EmbeddingGemma 2 · Full 740M | 485 MB | Same image results; carries an unused audio encoder |

Both are Apache 2.0 and downloaded from the
[litert-community](https://huggingface.co/litert-community) repositories on Hugging Face.

### NPU builds

When NPU is selected, the app reads the chip model from the system and downloads the
matching build instead of the generic one:

| Vendor | Chips with a published build |
|---|---|
| Qualcomm | SM8550, SM8650, SM8750, SM8850, QCS8275 |
| MediaTek | MT6991, MT6993 |
| Google | Tensor G5, Tensor G6 |

For any other Qualcomm, MediaTek or Tensor chip the app checks whether a build has been
published since and falls back to CPU if not. If the NPU fails to start, or crashes the app
while starting, the app switches to CPU on its own and says so in Settings.

NPU builds run only on the NPU, and their vectors are kept separate from CPU/GPU vectors,
so switching between NPU and CPU/GPU downloads the other file and indexes photos again.

## Settings

- Model, and hardware acceleration (NPU / GPU / CPU)
- Image detail (standard or high) and index size per photo (128–768 numbers)
- How strictly photos must match a category
- Automatic background indexing, optionally only while charging
- Wi-Fi-only model download
- Status and request buttons for every permission the app uses

## Permissions

| Permission | Why |
|---|---|
| Photos | Read, index and show your photos |
| Notifications | Progress while indexing or downloading |
| Unrestricted battery use (optional) | Prompt indexing of new photos while the app is closed |
| Internet | Model download only |

## Building

Requires JDK 17 or newer and the Android SDK (platform 36).

```
./gradlew :app:assembleDebug      # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest
```

Android 9 (API 28) or newer, arm64 devices and x86_64 emulators. The APK is about 125 MB,
most of it Qualcomm's NPU runtime, which has to be bundled with the app.

## Test status

- Unit tests cover the search and sorting maths and chip detection.
- Search and sorting were run on a desktop CPU against the real model with a small set of
  test photos, in English and German.
- The app itself, and NPU and GPU acceleration in particular, have not yet been run on a
  phone.
