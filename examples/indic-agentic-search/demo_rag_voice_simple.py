#!/usr/bin/env python3
"""
A demonstration of uploading files, creating vector stores, 
listening to user voice input, performing agentic search, and responding with TTS.
"""

import asyncio
import os
import tempfile
import json
from typing import Dict, Any

# Core dependencies
from openai import AsyncOpenAI, OpenAI

# Voice dependencies
import sounddevice as sd
import numpy as np
from scipy.io.wavfile import write
from sarvamai import SarvamAI
from sarvamai.play import save
from IPython.display import Audio
import pygame

# Configuration
BASE_URL = os.getenv("OPEN_RESPONSES_URL", "http://localhost:8080/v1")
API_KEY = os.getenv("OPENAI_API_KEY")
SARVAM_KEY = os.getenv("SARVAM_API_KEY")

if not API_KEY:
    raise ValueError("Please set the OPENAI_API_KEY environment variable.")
if not SARVAM_KEY:
    raise ValueError("Please set the SARVAM_API_KEY environment variable.")

MODEL_NAME = "openai@gpt-4.1-mini"

class VoiceRAGDemo:
    def __init__(self):
        self.setup_clients()
        self.setup_voice()
        self.vector_store_id = None
        self.previous_response_id = None
        
    def setup_clients(self):
        """Initialize OpenAI and Sarvam clients"""
        custom_headers = {"Authorization": f"Bearer {API_KEY}"}
        
        self.async_client = AsyncOpenAI(
            base_url=BASE_URL,
            api_key=API_KEY,
            default_headers=custom_headers
        )
        
        self.sync_client = OpenAI(
            base_url=BASE_URL,
            api_key=API_KEY,
            default_headers=custom_headers
        )
        
        # Initialize Sarvam client for voice
        self.sarvam_client = SarvamAI(api_subscription_key=SARVAM_KEY)
        
    def setup_voice(self):
        """Initialize pygame for audio playback"""
        pygame.mixer.init()  # Initialize pygame mixer for audio playback
        
    def upload_file(self, file_path: str) -> str:
        """Upload a file to the vector store"""
        print(f"Uploading file: {file_path}")
        with open(file_path, "rb") as file:
            response = self.sync_client.files.create(
                file=file,
                purpose="user_data"
            )
        print(f"File uploaded with ID: {response.id}")
        return response.id
        
    def create_vector_store(self, name: str) -> str:
        """Create a vector store"""
        print(f"Creating vector store: {name}")
        response = self.sync_client.vector_stores.create(name=name)
        print(f"Vector store created with ID: {response.id}")
        return response.id
        
    def add_file_to_vector_store(self, vector_store_id: str, file_id: str):
        """Add file to vector store with chunking strategy"""
        print(f"Adding file {file_id} to vector store {vector_store_id}")
        response = self.sync_client.vector_stores.files.create(
            vector_store_id=vector_store_id,
            file_id=file_id,
            chunking_strategy={
                "type": "static",
                "static": {
                    "max_chunk_size_tokens": 1000,
                    "chunk_overlap_tokens": 200
                }
            },
            attributes={
                "category": "documentation",
                "language": "en"
            }
        )
        print(f"File added to vector store successfully")
        
    def setup_rag_system(self, file_path: str, vector_store_name: str) -> str:
        """Complete setup of RAG system"""
        file_id = self.upload_file(file_path)
        vector_store_id = self.create_vector_store(vector_store_name)
        self.add_file_to_vector_store(vector_store_id, file_id)
        self.vector_store_id = vector_store_id
        return vector_store_id
        
    def record_audio(self, duration: int = 5, sample_rate: int = 44100) -> str:
        """Record audio from microphone"""
        print(f"Recording for {duration} seconds... Speak now!")
        
        audio_data = sd.rec(int(duration * sample_rate), 
                           samplerate=sample_rate, 
                           channels=1, 
                           dtype=np.int16)
        sd.wait()  # Wait until recording is finished
        
        # Save to temporary file
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp_file:
            write(tmp_file.name, sample_rate, audio_data)
            return tmp_file.name
            
    def speech_to_text(self, audio_file: str, lang_code: str = "hi-IN") -> str:
        """Convert speech to text using Sarvam STT"""
        try:
            response = self.sarvam_client.speech_to_text.transcribe(
                file=open(audio_file, "rb"),
                model="saarika:v2",
                language_code=lang_code
            )
            print(f"STT Response: {response}")
            text = response.transcript
            print(f"Recognized: {text}")
            return text
        except Exception as e:
            print(f"STT Error: {e}")
            return f"Error with speech recognition: {e}"
        finally:
            # Clean up temporary file
            if os.path.exists(audio_file):
                os.unlink(audio_file)
                
    def text_to_speech(self, text: str, speaker: str = "anushka", lang_code: str = "hi-IN"):
        """Convert text to speech using Sarvam TTS and play all audio segments"""
        try:
            print(f"Speaking: {text}")
            print(f"TTS text length: {len(text)} characters")
            
            # Generate audio using Sarvam TTS
            audio_response = self.sarvam_client.text_to_speech.convert(
                target_language_code=lang_code,
                text=text,
                model="bulbul:v2",
                speaker=speaker
            )
            
            # Check if we got multiple audio segments
            if hasattr(audio_response, 'audios') and isinstance(audio_response.audios, list):
                audio_segments = audio_response.audios
                print(f"TTS API returned {len(audio_segments)} audio segments")
                
                # Play each audio segment sequentially
                for i, audio_data in enumerate(audio_segments):
                    print(f"Playing audio segment {i + 1}/{len(audio_segments)}")
                    self._play_base64_audio(audio_data, i)
                    
                print("All audio segments played successfully")
            else:
                # Fallback for single audio response
                print("Playing single audio segment")
                self._play_audio_with_save(audio_response, 0)
                
        except Exception as e:
            print(f"TTS Error: {e}")
            print(f"Failed to convert text to speech: {text}")
    
    def _play_base64_audio(self, base64_audio: str, segment_index: int):
        """Play audio from base64 string"""
        try:
            import base64
            
            # Decode base64 audio data
            audio_bytes = base64.b64decode(base64_audio)
            
            # Write to temporary file
            with tempfile.NamedTemporaryFile(suffix=f"_segment_{segment_index}.wav", delete=False) as tmp_file:
                tmp_file.write(audio_bytes)
                temp_file_path = tmp_file.name
            
            # Play audio using pygame
            pygame.mixer.music.load(temp_file_path)
            pygame.mixer.music.play()
            
            # Wait for playback to complete
            while pygame.mixer.music.get_busy():
                pygame.time.wait(100)
            
            # Clean up
            os.unlink(temp_file_path)
                
        except Exception as e:
            print(f"Error playing base64 audio segment {segment_index}: {e}")
    
    def _play_audio_with_save(self, audio_response, segment_index: int):
        """Play audio using the save method (for single audio responses)"""
        try:
            # Save audio to temporary file using the save function
            with tempfile.NamedTemporaryFile(suffix=f"_segment_{segment_index}.wav", delete=False) as tmp_file:
                save(audio_response, tmp_file.name)
                temp_file_path = tmp_file.name
            
            # Play audio using pygame
            pygame.mixer.music.load(temp_file_path)
            pygame.mixer.music.play()
            
            # Wait for playback to complete
            while pygame.mixer.music.get_busy():
                pygame.time.wait(100)
            
            # Clean up
            os.unlink(temp_file_path)
                
        except Exception as e:
            print(f"Error playing audio with save method {segment_index}: {e}")
        
    async def process_query(self, user_input: str) -> str:
        """Process user query through agentic search"""
        if not self.vector_store_id:
            return "RAG system not initialized. Please upload a document first."
            
        print(f"Processing query: {user_input}")
        
        try:
            # Create agentic search tool definition
            agentic_search_tool = {
                "type": "agentic_search",
                "vector_store_ids": [self.vector_store_id],
                "max_num_results": 5,
                "max_iterations": 4,
                "seed_strategy": "hybrid",
                "alpha": 0.5,
                "initial_seed_multiplier": 3,
                "filters": {
                    "type": "and",
                    "filters": [
                        {"type": "eq", "key": "category", "value": "documentation"},
                        {"type": "eq", "key": "language", "value": "en"}
                    ]
                }
            }
            
            # Use the OpenAI responses endpoint directly
            response = self.sync_client.responses.create(
                model=MODEL_NAME,
                tools=[agentic_search_tool],
                input=user_input,
                previous_response_id=self.previous_response_id,
                store=True,
                instructions=(
                    "You are a helpful assistant that uses RAG-based search to answer questions. "
                    "When given a query, search the document vector store and provide a clear, "
                    "concise response based on the retrieved information. Keep responses brief "
                    "and conversational since they will be converted to speech."
                )
            )
            
            # Store the response ID for the next turn
            if hasattr(response, 'id'):
                self.previous_response_id = response.id
            
            # Extract text from the response
            if hasattr(response, 'output') and response.output:
                if isinstance(response.output, list) and len(response.output) > 0:
                    # Handle list of messages
                    if hasattr(response.output[0], 'content') and response.output[0].content:
                        if isinstance(response.output[0].content, list) and len(response.output[0].content) > 0:
                            return response.output[0].content[0].text
                        elif hasattr(response.output[0].content, 'text'):
                            return response.output[0].content.text
                        else:
                            return str(response.output[0].content)
                    else:
                        return str(response.output[0])
                else:
                    return str(response.output)
            else:
                return "Sorry, I couldn't generate a response."
            
        except Exception as e:
            return f"Error processing query: {str(e)}"
            
    async def conversation_loop(self):
        """Main conversation loop"""
        print("\nStarting voice conversation...")
        print("Press Ctrl+C to stop the conversation.")
        
        while True:
            try:
                # Record user input
                audio_file = self.record_audio()
                user_input = self.speech_to_text(audio_file)
                
                if not user_input:
                    continue
                    
                # Process through RAG
                response = await self.process_query(user_input)
                
                # Respond with voice
                self.text_to_speech(response)
                
            except KeyboardInterrupt:
                self.text_to_speech("Goodbye!")
                break
            except Exception as e:
                error_msg = f"An error occurred: {str(e)}"
                print(error_msg)
                self.text_to_speech("Sorry, I encountered an error.")

def get_document_path(filename: str = "chapter.pdf") -> str:
    """Get the path to the document file"""
    # Look for the file in current directory first
    if os.path.exists(filename):
        return filename
    
    # Look in the python directory
    python_dir_path = os.path.join(os.path.dirname(__file__), filename)
    if os.path.exists(python_dir_path):
        return python_dir_path
    
    # Look in parent directory
    parent_dir_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), filename)
    if os.path.exists(parent_dir_path):
        return parent_dir_path
    
    raise FileNotFoundError(f"Could not find {filename}. Please ensure the file exists in the current directory, python directory, or parent directory.")

async def main():
    """Main demo function"""
    print("Simple Voice RAG Demo Starting...")
    
    # Create demo instance
    demo = VoiceRAGDemo()
    
    try:
        # Get document path
        document_file = get_document_path("chapter.pdf")
        print(f"Using document: {document_file}")
        
        # Setup RAG system
        vector_store_id = demo.setup_rag_system(document_file, "voice-demo-docs")
        print(f"RAG system ready with vector store: {vector_store_id}")
        
        # Start voice conversation
        await demo.conversation_loop()
        
    except FileNotFoundError as e:
        print(f"Error: {e}")
        print("Please ensure chapter.pdf is available in one of the following locations:")
        print("- Current directory")
        print("- python/ directory") 
        print("- Parent directory")
        return
        
    except Exception as e:
        print(f"Error: {e}")
        
    finally:
        print("Demo completed.")

if __name__ == "__main__":
    asyncio.run(main()) 
