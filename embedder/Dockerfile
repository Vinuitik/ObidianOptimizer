# CUDA 12 base — onnxruntime-gpu uses CUDAExecutionProvider when CUDA is available.
# If the container starts without --gpus / no nvidia-container-toolkit, CUDA provider
# is absent at runtime and the service falls back to CPU with a loud warning.
FROM nvidia/cuda:12.2.2-cudnn8-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y \
        python3.11 python3.11-venv python3-pip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip3 install --no-cache-dir -r requirements.txt

COPY . .

# /models is a Docker volume — model is downloaded here on first start and reused.
VOLUME ["/models"]

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
