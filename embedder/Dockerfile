# CUDA 13 + cuDNN base — matches host driver 580.x which exposes CUDA 13.0.
# onnxruntime-gpu >=1.21 requires libcudart.so.13; CUDA 12 images don't ship it.
FROM nvidia/cuda:13.0.0-cudnn-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive
# --no-install-recommends keeps this to the packages we actually use. Without it,
# ffmpeg drags in ~230 X11/mesa/SDL video-display libs (including libxcb-present0,
# which we never need for audio transcription and which has 404'd on flaky mirrors).
# tesseract-ocr-eng and ca-certificates are Recommends we DO need, so list them.
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3.11 python3.11-venv curl ca-certificates \
        ffmpeg tesseract-ocr tesseract-ocr-eng \
    && curl -sS https://bootstrap.pypa.io/get-pip.py | python3.11 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN python3.11 -m pip install --no-cache-dir -r requirements.txt
# faster-whisper drags in CPU onnxruntime, whose files clobber onnxruntime-gpu's
# in the shared namespace — reinstall the GPU wheel last so CUDA EP survives.
RUN python3.11 -m pip install --no-cache-dir --force-reinstall --no-deps "onnxruntime-gpu>=1.19.0"

# CUDA-12 cuBLAS (from the nvidia-cublas-cu12 wheel) lands in site-packages, not a
# standard linker dir — prepend it so ctranslate2 (a CUDA-12 build) finds libcublas.so.12.
# Prepended, not replaced, so the base image's CUDA-13 paths (used by onnxruntime) survive.
ENV LD_LIBRARY_PATH=/usr/local/lib/python3.11/dist-packages/nvidia/cublas/lib:${LD_LIBRARY_PATH}

COPY . .

# /models is a Docker volume — model is downloaded here on first start and reused.
VOLUME ["/models"]

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
