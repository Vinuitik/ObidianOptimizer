# GPU Memory Arbitration — FLOWS
Files: gpu_slot.py, model_runtime.py, ingest/extract_av.py, ingest/clip_onnx.py, ingest/jobs.py

> The embedder container runs three GPU models — the text embedder (onnxruntime),
> faster-whisper (ctranslate2), and CLIP (onnxruntime). On a 4GB card each fits
> **alone** but not **together**. Policy: a single-occupant GPU "slot" — one heavy
> model in VRAM at a time. No VRAM probing, no co-residency, no pynvml.

## The rule

```
gpu_slot: one occupant ∈ {embedder, whisper, clip} | None
  exclusive(name)      ingest models BLOCK for the slot, EVICT the occupant, claim it
  embedder_session()   the embedder NEVER blocks/evicts: slot free → GPU, else CPU floor
```

Whisper-on-CPU is painfully slow; embeddings-on-CPU are fine — so **ingest wins the
GPU and the embedder bends to CPU**. That's the whole priority order.

## Embed flow (`model_runtime.embed_texts`)

```
embed_texts(texts)
  → gpu_slot.embedder_session(_ensure_gpu_session):
       lock free AND occupant ∈ {None, embedder} → load/return GPU session (occupant=embedder)
       ingest holds the slot                      → None
  → session = GPU session or state["cpu_session"]  (the always-built CPU floor)
  → sub-batch by EMBED_BATCH_SIZE → session.run → mean-pool → L2-normalise
```
- GPU session is **capped** (`EMBED_GPU_MEM_LIMIT_MB`, arena_extend=kSameAsRequested)
  and inputs **sub-batched** (`EMBED_BATCH_SIZE`) so one multi-chunk note can't grow
  VRAM past the cap. To change either: env vars (model_runtime top).
- Default weights are **fp16** (`EMBED_ONNX_FILE=onnx/model_fp16.onnx`, ~220MB for
  gte-base) — the headroom that lets embedder-or-whisper fit on 4GB. fp32 = `onnx/model.onnx`.
- Graph optimisation is pinned to **BASIC** (`EMBED_ORT_OPT`, default basic) because
  onnxruntime 1.19's EXTENDED `SimplifiedLayerNormFusion` asserts on some fp16 exports
  (it crashed the old mxbai fp16 model at load). Set `EMBED_ORT_OPT=all` to chase speed
  once a model is verified to load clean.
- When whisper/CLIP evict the embedder, `_unload_gpu_session` frees the GPU session
  (CPU floor stays); the next embed rebuilds it via `_ensure_gpu_session`.

## Whisper flow (`ingest/extract_av._whisper_transcribe`)

```
for device in [cuda, cpu]:               # _device_plan
  with gpu_slot.exclusive("whisper"):    # evicts embedder, claims GPU (LOAD only)
      model = _load_whisper(device)      # cached across jobs (no per-file reload)
  _run_transcribe(model, wav)            # runs WITHOUT the lock; embedder → CPU meanwhile
  on CUDA OOM (load or transcribe) → unload_whisper(), retry on CPU   # safety net
```
Whisper stays loaded between jobs (avoids thrash during a burst); freed on idle.

## CLIP flow (`ingest/clip_onnx._load`)

```
resolve files (HF/CPU) OUTSIDE the slot
  → with gpu_slot.exclusive("clip"): build the two CUDA sessions   # evicts embedder/whisper
cached until idle-evict
```

## Idle eviction (`ingest/jobs._evict_models`)

```
ingest queue empty → gpu_slot.release_ingest()
  → evicts whichever ingest model (whisper|clip) holds the slot → occupant=None
  → next embed reclaims the GPU. Never evicts the embedder.
```

## Technology Notes (constraints / failure modes)

- **Why the embedder "grows" even though weights are fixed.** onnxruntime's CUDA
  BFC arena allocates activation scratch sized to the largest batch×seq it's seen,
  and **never returns it to the driver**. So resident VRAM = fixed weights + a
  high-water arena. `EMBED_GPU_MEM_LIMIT_MB` caps the arena; `EMBED_BATCH_SIZE`
  stops a single call exceeding it. Without BOTH, a big note OOMs internally.
- **The lock is short-held.** `exclusive()` holds it only across the model LOAD;
  the minutes-long transcription runs after the `with` exits (occupant flag still
  set). So a transcription never blocks embedding — embedding just goes to CPU.
- **Two CUDA contexts.** onnxruntime and ctranslate2 each carry their own CUDA
  context (~hundreds of MB). That's why only one heavy model at a time — co-residency
  was rejected, not just unimplemented.
- **Two CUDA *versions* coexist.** onnxruntime uses the base image's CUDA-13 libs
  (`libcublas.so.13`); ctranslate2 is a CUDA-12 build needing `libcublas.so.12`, shipped
  via the `nvidia-cublas-cu12` wheel and found through `LD_LIBRARY_PATH` (Dockerfile).
  Different soname (`.so.12` vs `.so.13`) = no collision; the one host driver (CUDA-13
  capable) runs both via backward compat. ~500MB on DISK, not VRAM/RAM. If a
  `libcudnn.so.9` error appears, add `nvidia-cudnn-cu12` the same way.
- **OOM→CPU is a safety net, not the plan.** With the embedder evicted, whisper has
  the card to itself and shouldn't OOM; the CPU retry covers fragmentation / a
  bigger `WHISPER_MODEL`.
- **No pynvml.** Single-occupancy means there's never a "does X fit alongside Y?"
  question to probe. Dependency deliberately avoided.
- **Interactive embeds (search query, flashcard open-answer judge) ride the same
  policy** — GPU if free, else CPU. They're small (1–3 texts), so a CPU run is
  ~100–300ms; they never block on a transcription.
- **GTX 1650 / 4GB sizing:** fp16 embedder ~1.4GB · whisper distil-large int8 ~1.6GB
  · CLIP ~0.6GB — each alone leaves margin; together they would not.

## Change Index

| Thing to change | Where |
|---|---|
| Embedder weights (fp16/fp32/int8) | `EMBED_ONNX_FILE` env (`model_runtime`) |
| GPU arena cap | `EMBED_GPU_MEM_LIMIT_MB` env |
| Embed sub-batch size | `EMBED_BATCH_SIZE` env |
| Disable the slot (force no-GPU behavior) | `GPU_SLOT=off` (`gpu_slot.ENABLED`) |
| Whisper model / size | `WHISPER_MODEL` env (`extract_av`) |
| Whisper device order / OOM fallback | `extract_av._device_plan` / `_whisper_transcribe` |
| Who can be evicted | `gpu_slot.set_evictor(name, fn)` at each model's module load |
| Embedder GPU (re)build / unload | `model_runtime._build_gpu_session` / `_unload_gpu_session` |
| Idle eviction trigger | `ingest/jobs._evict_models` → `gpu_slot.release_ingest` |
| GPU vs CPU priority | by design: ingest `exclusive()` evicts; embedder `embedder_session()` yields |
