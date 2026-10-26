FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .

# Container posture (SELF_HOST_BUNDLE_PLAN D7): the `claude-cli` provider is OFF.
# The router only tries providers listed in LLM_*_PRIORITY, and the claude binary is
# not installed here, so no personal Claude subscription can be used. API-key
# providers are configured by the bundle .env. bundle/compose.yml re-pins these two
# vars so a stray .env value can't re-enable it.
ENV LLM_TEXT_PRIORITY=groq,github,mistral,deepseek,gemini,anthropic \
    LLM_VISION_PRIORITY=gemini,github,mistral,anthropic \
    PORT=5001 \
    PYTHONUNBUFFERED=1
# main.py requires a vault path at import; the vault is mounted at /vault, which the
# backend also uses in its image paths (/vault/... maps to itself).
ENV VAULT_HOST_PATH=/vault

EXPOSE 5001
CMD ["python", "main.py"]
