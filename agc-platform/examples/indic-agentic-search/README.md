# Voice RAG Demo

A complete demonstration of a voice-interactive RAG (Retrieval-Augmented Generation) system that:

1. Uploads a document to a vector store
2. Listens to user voice input
3. Performs agentic search on the document
4. Responds with text-to-speech

## Setup

### macOS Setup

1. Install system dependencies first:
```bash
# Install PortAudio for audio processing
brew install portaudio

# If you don't have Homebrew, install it first:
# /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

2. Install Python dependencies:
```bash
pip install -r requirements_demo.txt
```
3. Start OpenResponses:
```bash
docker run -p 6644:6644 -e OPEN_RESPONSES_EMBEDDINGS_HTTP_ENABLED=false masaicai/open-responses-onnx:latest
```

4. Set environment variables:
```bash
export OPENAI_API_KEY="your-openai-api-key"
export SARVAM_API_KEY="your-sarvam-api-key"
export OPEN_RESPONSES_URL="http://localhost:6644/v1"  # Optional, defaults to localhost:8080/v1
```

## Usage

1. Place the `chapter.pdf` file in one of these locations:
   - Current directory (where you run the script)
   - `python/` directory 
   - Parent directory

2. Run the demo:
```bash
python demo_rag_voice_simple.py
```

The demo will:
1. Load the `chapter.pdf` document
2. Upload it to a vector store with chunking strategy (1000 tokens per chunk, 200 token overlap)
3. Start a voice conversation loop
4. Record your voice for 5 seconds when prompted
5. Convert speech to text using Sarvam STT (supports Hindi by default)
6. Process your question through the agentic search system
7. Respond with speech using Sarvam TTS

## Voice Commands

- Ask any question about the document content
- Press Ctrl+C to quit the conversation

## Features

- **Multilingual Support**: Default setup uses Hindi (hi-IN) for both STT and TTS, but can be configured
- **Agentic Search**: Uses hybrid search strategy with up to 4 iterations and 5 results per iteration
- **Audio Playback**: Handles multiple audio segments from TTS responses
- **Conversation Memory**: Maintains context across conversation turns

## Example Questions

You can ask questions about the content in the `chapter.pdf` document. For example:
- Ask about specific topics covered in the document
- Request explanations of concepts mentioned
- Inquire about details from particular sections

## Configuration

The demo uses these default settings:
- **STT Model**: saarika:v2 (Sarvam)
- **TTS Model**: bulbul:v2 (Sarvam)
- **TTS Speaker**: anushka
- **Language**: Hindi (hi-IN)
- **LLM Model**: openai@gpt-4.1-mini
- **Recording Duration**: 5 seconds
- **Sample Rate**: 44.1kHz

## Requirements

- Microphone for voice input
- Speakers/headphones for voice output
- Internet connection for Sarvam Speech services
- OpenAI API access for the RAG backend
- Sarvam API key for speech-to-text and text-to-speech

## API Keys Required

1. **OpenAI API Key**: For the RAG backend and agentic search
2. **Sarvam API Key**: For speech-to-text and text-to-speech services

You can get a Sarvam API key from: https://www.sarvam.ai/

## Troubleshooting

### Audio Issues
If you encounter audio-related issues on macOS:
```bash
# Ensure PortAudio is properly installed
brew reinstall portaudio

# If pygame audio issues occur
pip uninstall pygame
pip install pygame
```

### File Not Found
Make sure `chapter.pdf` exists in one of the supported locations. The demo will automatically search:
1. Current working directory
2. `python/` subdirectory
3. Parent directory 
