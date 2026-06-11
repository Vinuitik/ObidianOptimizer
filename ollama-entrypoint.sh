#!/bin/sh
set -e

# Start Ollama server in the background
ollama serve &
OLLAMA_PID=$!

# Wait for server to be ready
echo "Waiting for Ollama to start..."
until ollama list > /dev/null 2>&1; do
  sleep 1
done

# Detect GPU — nvidia-smi is available in the container when GPU is passed through
if nvidia-smi > /dev/null 2>&1; then
  echo "INFO: GPU detected (nvidia-smi OK). Pulling mxbai-embed-large (1024-dim)."
  ollama pull mxbai-embed-large
else
  echo "WARN: No GPU detected — falling back to CPU. Pulling nomic-embed-text (768-dim)."
  echo "WARN: Embedding dimension will be 768 instead of 1024. Fix GPU passthrough to use the full model."
  echo "WARN: See architecture_plans/ML_ARCH.md -> Ollama GPU / CPU Fallback for instructions."
  ollama pull nomic-embed-text
fi

# Hand off to Ollama server process
wait $OLLAMA_PID
