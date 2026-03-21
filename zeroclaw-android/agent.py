import asyncio
import json
import logging
import sys
from typing import Any, Dict, Optional

from droidrun import DroidAgent
from droidrun.agent.utils.llm_picker import load_llm
from droidrun.config_manager import ConfigLoader

# Configure logging without exposing credentials
logging.basicConfig(level=logging.WARNING)
logger = logging.getLogger(__name__)

OPENAI_LIKE_BASE_URLS: Dict[str, Optional[str]] = {
    "openrouter": "https://openrouter.ai/api/v1",
    "groq": "https://api.groq.com/openai/v1",
    "mistral": "https://api.mistral.ai/v1",
    "deepseek": "https://api.deepseek.com/v1",
    "huggingface": "https://router.huggingface.co/v1",
    "custom-openai": None,
    "lmstudio": "http://localhost:1234/v1",
    "vllm": "http://localhost:8000/v1",
    "localai": "http://localhost:8080/v1",
}

DEFAULT_MODELS: Dict[str, str] = {
    "openai": "gpt-4o",
    "anthropic": "claude-3-5-sonnet-latest",
    "google-gemini": "gemini-2.0-flash",
    "openrouter": "google/gemini-2.0-flash-exp:free",
    "groq": "llama-3.1-8b-instant",
    "mistral": "mistral-small-latest",
    "deepseek": "deepseek-chat",
    "huggingface": "meta-llama/Llama-3.2-3B-Instruct",
    "ollama": "llama3.2",
    "custom-openai": "gpt-4o-mini",
    "lmstudio": "local-model",
    "vllm": "local-model",
    "localai": "local-model",
}


def _normalize_provider(provider: str) -> str:
    aliases = {
        "google": "google-gemini",
        "gemini": "google-gemini",
        "hf": "huggingface",
        "custom": "custom-openai",
    }
    canonical = provider.strip().lower()
    return aliases.get(canonical, canonical)


def _trimmed(value: Any) -> Optional[str]:
    if not isinstance(value, str):
        return None
    text = value.strip()
    return text or None


async def run_instruction(instruction_json: str) -> None:
    """
    Execute a single instruction from JSON input.
    Properly handles async operations and credential security.
    """
    try:
        data = json.loads(instruction_json)
        instruction = _trimmed(data.get("instruction")) or _trimmed(data.get("prompt"))
        config_data = data.get("config", {})
        if not isinstance(config_data, dict):
            config_data = {}

        if not instruction:
            print(json.dumps({"success": False, "error": "No instruction provided"}))
            return

        # Load configuration
        try:
            config = ConfigLoader.load(None)
        except Exception as exc:
            print(
                json.dumps(
                    {
                        "success": False,
                        "error": f"Failed to load configuration: {str(exc)}",
                    }
                )
            )
            return

        # Apply config overrides from Zero-Assist
        if config_data.get("device_serial"):
            config.device.serial = config_data["device_serial"]

        provider = _normalize_provider(config_data.get("llm_provider", "openai"))
        model = _trimmed(config_data.get("llm_model")) or DEFAULT_MODELS.get(provider, "gpt-4o")
        api_key = _trimmed(config_data.get("llm_api_key"))
        base_url = _trimmed(config_data.get("llm_base_url"))

        # Initialize LLM with explicit credentials (not environment variables for security)
        try:
            llm = _initialize_llm(provider, model, api_key, base_url)
        except ValueError as exc:
            print(
                json.dumps(
                    {"success": False, "error": f"LLM initialization failed: {str(exc)}"}
                )
            )
            return

        # Create and run the agent with proper error handling
        try:
            agent = DroidAgent(goal=instruction, llms=llm, config=config, timeout=1000)
            run_result = agent.run()
            result = await run_result if asyncio.iscoroutine(run_result) else run_result

            success = getattr(result, "success", False)
            result_data = str(getattr(result, "data", "Task completed"))
            summary = getattr(result, "summary", "")

            print(
                json.dumps(
                    {
                        "success": success,
                        "result": result_data,
                        "summary": summary,
                    }
                )
            )

        except asyncio.TimeoutError:
            print(
                json.dumps(
                    {
                        "success": False,
                        "error": "Agent execution timed out after 1000ms",
                    }
                )
            )
        except Exception as exc:
            logger.error(f"Agent execution failed: {type(exc).__name__}")
            print(
                json.dumps(
                    {
                        "success": False,
                        "error": f"Agent execution failed: {str(exc)}",
                    }
                )
            )

    except json.JSONDecodeError as exc:
        print(json.dumps({"success": False, "error": f"Invalid JSON input: {str(exc)}"}))
    except Exception as exc:
        logger.error(f"Unexpected error: {type(exc).__name__}")
        print(json.dumps({"success": False, "error": f"Unexpected error: {str(exc)}"}))


def _initialize_llm(
    provider: str,
    model: str,
    api_key: Optional[str],
    base_url: Optional[str],
):
    """
    Initialize LLM with secure credential handling.

    Passes credentials directly to the LLM instead of using environment variables
    to prevent credential exposure in process listings, logs, or memory dumps.
    """
    if not provider:
        raise ValueError("LLM provider not specified")

    if provider == "openai":
        if not api_key:
            raise ValueError("OpenAI API key required but not provided")
        return load_llm("OpenAI", model=model, api_key=api_key)

    if provider == "anthropic":
        if not api_key:
            raise ValueError("Anthropic API key required but not provided")
        return load_llm("Anthropic", model=model, api_key=api_key)

    if provider == "google-gemini":
        if not api_key:
            raise ValueError("Google Gemini API key required but not provided")
        return load_llm("GoogleGenAI", model=model, api_key=api_key)

    if provider == "ollama":
        return load_llm(
            "Ollama",
            model=model,
            base_url=base_url or "http://localhost:11434",
        )

    if provider in OPENAI_LIKE_BASE_URLS:
        resolved_base_url = base_url or OPENAI_LIKE_BASE_URLS[provider]
        if not resolved_base_url:
            raise ValueError(f"Provider '{provider}' requires a base URL")

        if provider not in {"custom-openai", "lmstudio", "vllm", "localai"} and not api_key:
            raise ValueError(f"{provider} API key required but not provided")

        return load_llm(
            "OpenAILike",
            model=model,
            api_key=api_key,
            base_url=resolved_base_url,
        )

    raise ValueError(
        "Unsupported LLM provider: "
        f"{provider}. Supported providers: openai, anthropic, google-gemini, "
        "openrouter, groq, mistral, deepseek, huggingface, ollama, "
        "custom-openai, lmstudio, vllm, localai"
    )


async def main():
    # Read one instruction from stdin and exit.
    line = sys.stdin.readline()
    if line:
        await run_instruction(line)


if __name__ == "__main__":
    asyncio.run(main())
