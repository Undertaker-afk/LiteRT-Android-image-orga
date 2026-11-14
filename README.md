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

The app uses LiteRT-LM 0.18.1 on purpose: from 0.19.0 the runtime needs NPU connector
libraries with a newer interface than the ones published so far, so NPU cannot start there.

## Smart sorting and search (optional chat model)

Switching on "Use a local chat model" adds a Gemma model that runs on the phone:

- **Category phrases.** For each category the model suggests a few concrete things such
  photos show ("Pets" becomes dog playing, cat sleeping, …). These are matched in addition
  to your own text.
- **Photo checks.** Each photo that might fit a category is shown to the model with the
  question "does this photo belong in this category?". A yes places it, a no rules the
  category out. With checks on, weaker matches are also shown to the model, so it can both
  remove mistakes and rescue photos the vectors alone would have left unsorted. Answers are
  stored; a photo is only looked at again when the category text or the model changes.
- **Smart search.** After the instant results, the model adds concrete phrases for the
  search and the results are refined. The added phrases are shown under the search box.

| Chat model | Download | Notes |
|---|---|---|
| Gemma 4 E2B | 2.6 GB | Google's official build. NPU builds exist for Tensor G5/G6 and Qualcomm QCS8275 only |
| Gemma 4 E2B abliterated | 5.2 GB | Community build (huihui-ai, converted by edp1096) with refusals removed. CPU and GPU only |
| Your own model | any | Any LiteRT-LM chat model by download link; text-only models help with phrases and search but cannot check photos |

Photo checks take a few seconds per photo on a phone CPU, so a large library takes hours
the first time. GPU is usually several times faster. The chat model is unloaded shortly
after its last use because it needs gigabytes of memory.

## Settings

- Model, and hardware acceleration (NPU / GPU / CPU)
- Image detail (standard or high) and index size per photo (128–768 numbers)
- How strictly photos must match a category
- Optional chat model: which one, where it runs, photo checks, smart search
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
- The chat model's photo checks and phrase suggestions were run the same way, with the
  prompts the app uses.
- On a phone (Xiaomi, MediaTek MT6993) the app has been run with CPU indexing. NPU start-up
  failed there with LiteRT-LM 0.19.1 and has not yet been confirmed with 0.18.1. GPU
  acceleration and the chat model features have not been run on a phone.
