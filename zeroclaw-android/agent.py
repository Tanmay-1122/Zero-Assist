import json
import sys
import asyncio
import os
from droidrun import DroidAgent
from droidrun.config_manager import ConfigLoader
from droidrun.agent.utils.llm_picker import load_llm

async def run_instruction(instruction_json: str):
    try:
        data = json.loads(instruction_json)
        instruction = data.get("instruction")
        config_data = data.get("config", {})
        
        if not instruction:
            print(json.dumps({"success": False, "error": "No instruction provided"}))
            return

        # Load configuration
        # We can either use a default config and override it, or build one
        config = ConfigLoader.load(None) 
        
        # Apply config overrides from Zero-Assist
        if "device_serial" in config_data and config_data["device_serial"]:
            config.device.serial = config_data["device_serial"]
        
        # LLM setup
        provider = config_data.get("llm_provider", "openai")
        api_key = config_data.get("llm_api_key")
        if api_key:
            if provider.lower() == "openai":
                os.environ["OPENAI_API_KEY"] = api_key
            elif provider.lower() == "anthropic":
                os.environ["ANTHROPIC_API_KEY"] = api_key
            elif provider.lower() == "google":
                os.environ["GOOGLE_API_KEY"] = api_key

        model = config_data.get("llm_model", "gpt-4o") # default
        
        # For simplicity in this bridge, we use the load_llm helper if provider/model are set
        llm = load_llm(provider, model=model)

        agent = DroidAgent(
            goal=instruction,
            llms=llm,
            config=config,
            timeout=1000
        )

        handler = agent.run()
        result = await handler
        
        print(json.dumps({
            "success": result.success,
            "result": str(result.data) if hasattr(result, 'data') else "Task completed",
            "summary": getattr(result, 'summary', '')
        }))

    except Exception as e:
        print(json.dumps({"success": False, "error": str(e)}))

async def main():
    # Read one instruction from stdin and exit
    # This allows the Rust manager to control the lifecycle
    line = sys.stdin.readline()
    if line:
        await run_instruction(line)

if __name__ == "__main__":
    asyncio.run(main())
