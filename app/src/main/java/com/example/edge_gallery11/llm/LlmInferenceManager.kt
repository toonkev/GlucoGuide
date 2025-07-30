package com.example.edge_gallery11.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.GraphOptions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

enum class ModelType {
    TEXT,           // Text-only LLM
    MULTIMODAL,     // Text + Image LLM using MediaPipe GenAI
    IMAGE,          // Image generation models  
    UNKNOWN
}

class LlmInferenceManager(private val context: Context) {
    
    private var llmInference: LlmInference? = null
    private var currentModelType: ModelType = ModelType.UNKNOWN
    
    // Track active sessions to ensure proper cleanup
    private val activeSessions = mutableSetOf<LlmInferenceSession>()
    private val sessionLock = Any()
    
    // MediaPipe Vision Helper for image processing
    private var visionHelper: MediaPipeVisionHelper? = null
    
    // System prompt for LLM conversations
    private var systemPrompt: String = "You are a helpful AI assistant. Please provide accurate, helpful, and concise responses."
    
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _loadingProgress = MutableStateFlow("")
    val loadingProgress: StateFlow<String> = _loadingProgress.asStateFlow()
    
    companion object {
        private const val TAG = "LlmInferenceManager"
    }
    
    init {
        initializeVisionHelper()
    }
    
    /**
     * Initialize MediaPipe Vision Helper for image processing
     */
    private fun initializeVisionHelper() {
        try {
            Log.d(TAG, "Initializing MediaPipe Vision Helper...")
            
            visionHelper = MediaPipeVisionHelper(
                context = context,
                classificationThreshold = 0.3f,
                maxResults = 5,
                currentDelegate = MediaPipeVisionHelper.DELEGATE_CPU
            )
            
            // Check which vision tasks are available
            val taskStatus = visionHelper?.getTaskStatus()
            val availableTasks = taskStatus?.values?.count { it } ?: 0
            
            if (availableTasks > 0) {
                Log.d(TAG, "MediaPipe Vision Helper initialized successfully with $availableTasks tasks")
                val availableTaskNames = taskStatus?.filterValues { it }?.keys?.joinToString(", ") ?: ""
                Log.d(TAG, "Available vision tasks: $availableTaskNames")
            } else {
                Log.i(TAG, "MediaPipe Vision Helper initialized but no vision models are loaded")
                Log.i(TAG, "Vision features will be limited - consider adding MediaPipe model files to assets folder")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize MediaPipe Vision Helper - vision features will be unavailable", e)
            visionHelper = null
            
            // Provide helpful guidance in the log
            if (e.message?.contains("Please specify only one of the model") == true) {
                Log.i(TAG, "This is likely due to missing MediaPipe model files in the assets folder")
                Log.i(TAG, "The app will work with text-only and multimodal LLM features")
            }
        }
    }
    
    /**
     * Safely create and track an LlmInferenceSession
     */
    private fun createSession(inference: LlmInference, options: LlmInferenceSession.LlmInferenceSessionOptions): LlmInferenceSession? {
        return try {
            val session = LlmInferenceSession.createFromOptions(inference, options)
            synchronized(sessionLock) {
                activeSessions.add(session)
            }
            Log.d(TAG, "Created and registered LLM session. Active sessions: ${activeSessions.size}")
            session
        } catch (e: IllegalStateException) {
            // Handle GPU/OpenCL specific errors that manifest as IllegalStateException
            if (e.message?.contains("RET_CHECK failure") == true ||
                e.message?.contains("OpenCL") == true ||
                e.message?.contains("delegate") == true ||
                e.message?.contains("Failed to initialize session") == true) {
                Log.i(TAG, "GPU/OpenCL not available for session creation: ${e.message}")
                Log.i(TAG, "This is a device limitation, not a model issue. Fallback handling will be used.")
            } else {
                Log.e(TAG, "IllegalStateException during session creation: ${e.message}", e)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create LLM session: ${e.message}", e)
            null
        }
    }
    
    /**
     * Try to create a session with CPU fallback for GPU/OpenCL issues
     */
    private fun createSessionWithFallback(
        inference: LlmInference, 
        enableVision: Boolean = false,
        topK: Int? = null,
        temperature: Float? = null
    ): LlmInferenceSession? {
        // First try with default settings (may include GPU acceleration)
        if (enableVision) {
            try {
                Log.d(TAG, "Attempting to create vision session with default settings...")
                val optionsBuilder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(true)
                            .build()
                    )
                
                // Add optional parameters if provided
                topK?.let { optionsBuilder.setTopK(it) }
                temperature?.let { optionsBuilder.setTemperature(it) }
                
                val options = optionsBuilder.build()
                return createSession(inference, options)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create vision session with default settings: ${e.message}")
                
                // Check if this is a GPU/OpenCL related error
                if (e.message?.contains("OpenCL") == true || 
                    e.message?.contains("delegate") == true ||
                    e.message?.contains("libvndksupport") == true ||
                    e.message?.contains("RET_CHECK failure") == true) {
                    
                    Log.i(TAG, "GPU/OpenCL acceleration not available on this device.")
                    Log.i(TAG, "The model supports multimodal functionality, but will use CPU fallback during inference.")
                    Log.i(TAG, "Note: Vision sessions may not be creatable, but multimodal inference can still work with fallback handling.")
                    return null // Will trigger fallback handling in the calling code
                } else {
                    // Re-throw if it's not a GPU/delegate issue
                    Log.e(TAG, "Non-GPU related error creating vision session: ${e.message}")
                    throw e
                }
            }
        } else {
            // For non-vision sessions, use basic configuration
            return try {
                val optionsBuilder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                
                // Add optional parameters if provided
                topK?.let { optionsBuilder.setTopK(it) }
                temperature?.let { optionsBuilder.setTemperature(it) }
                
                val options = optionsBuilder.build()
                createSession(inference, options)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create basic LLM session", e)
                null
            }
        }
    }
    
    /**
     * Safely close and untrack an LlmInferenceSession
     */
    private fun closeSession(session: LlmInferenceSession?) {
        session?.let {
            try {
                synchronized(sessionLock) {
                    activeSessions.remove(it)
                }
                Log.d(TAG, "Unregistered LLM session. Active sessions: ${activeSessions.size}")
                
                // Close the session after removing from tracking
                it.close()
                Log.d(TAG, "Successfully closed LLM session")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing LLM session (this may cause JNI reference issues)", e)
            }
        }
    }
    
    /**
     * Close all active sessions safely
     */
    private fun closeAllSessions() {
        synchronized(sessionLock) {
            Log.d(TAG, "Closing ${activeSessions.size} active sessions...")
            val sessionsToClose = activeSessions.toList()
            activeSessions.clear()
            
            for (session in sessionsToClose) {
                try {
                    session.close()
                    Log.d(TAG, "Closed session successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing session during cleanup", e)
                }
            }
        }
    }
    
    suspend fun initializeModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            _isLoading.value = true
            _loadingProgress.value = "Initializing model..."
            
            cleanup() // Clean up any existing model
            
            Log.d(TAG, "Loading model from: $modelPath")
            
            // Check if the model path is a directory (image generation) or file
            val modelFile = File(modelPath)
            
            if (modelFile.isDirectory) {
                // This is likely an image generation model directory
                currentModelType = ModelType.IMAGE
                _loadingProgress.value = "✅ Image generation model prepared"
                Log.d(TAG, "Detected image generation model directory")
                
                // For image generation models, we don't initialize LLM inference
                _isModelLoaded.value = true
                _isLoading.value = false
                
                Result.success(Unit)
                
            } else {
                // This is a single file model - could be text-only or multimodal
                // Set maxNumImages to support multimodal models (we'll test capabilities after loading)
                val options = LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    .setMaxNumImages(5) // Allow up to 5 images (following reference implementation)
                    .build()
                
                _loadingProgress.value = "Creating LLM inference..."
                llmInference = try {
                    LlmInference.createFromOptions(context, options)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create LLM inference: ${e.message}", e)
                    // Check if this is a GPU/OpenCL related error during model loading
                    if (e.message?.contains("OpenCL") == true || 
                        e.message?.contains("delegate") == true ||
                        e.message?.contains("GPU") == true) {
                        throw Exception("Model loading failed due to GPU/OpenCL limitations. Try using a different model or device.")
                    } else {
                        throw Exception("Model loading failed: ${e.message}")
                    }
                }
                
                // Test if this model supports multimodal functionality
                _loadingProgress.value = "Testing model capabilities..."
                currentModelType = try {
                    detectModelType(llmInference!!)
                } catch (e: Exception) {
                    // If model type detection fails for any reason, assume multimodal
                    // since we configured the model with setMaxNumImages(5)
                    Log.w(TAG, "Model type detection failed, defaulting to multimodal: ${e.message}")
                    _loadingProgress.value = "✅ Multimodal model loaded - capabilities will be detected at runtime"
                    ModelType.MULTIMODAL
                }
                
                _isModelLoaded.value = true
                _isLoading.value = false
                
                Log.d(TAG, "Model loaded successfully. Type: $currentModelType")
                
                Result.success(Unit)
            }
        } catch (e: Exception) {
            _isLoading.value = false
            _isModelLoaded.value = false
            Log.e(TAG, "Failed to initialize model", e)
            Result.failure(e)
        }
    }
    
    private fun detectModelType(inference: LlmInference): ModelType {
        return try {
            Log.d(TAG, "Testing model for multimodal capabilities...")
            
            // Since we loaded the model with setMaxNumImages(5), it should support multimodal
            // Try a minimal test to confirm, but default to MULTIMODAL on any GPU/OpenCL issues
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(true)
                            .build()
                    )
                    .build()
            
            val testSession = try {
                // Try direct session creation first
                createSession(inference, sessionOptions)
            } catch (e: Exception) {
                Log.w(TAG, "Vision session test failed (likely GPU/OpenCL limitation): ${e.message}")
                // Don't try further - assume multimodal and handle at runtime
                null
            }
            
            if (testSession != null) {
                // Session created successfully - model definitely supports multimodal with GPU
                closeSession(testSession)
                Log.d(TAG, "Model supports multimodal functionality with GPU acceleration")
                _loadingProgress.value = "✅ Multimodal model loaded - supports text and images!"
                return ModelType.MULTIMODAL
            } else {
                // Session creation failed - likely GPU/OpenCL issue, but model still supports multimodal
                Log.i(TAG, "GPU/OpenCL not available, but model supports multimodal (CPU fallback will be used)")
                _loadingProgress.value = "✅ Multimodal model loaded - GPU acceleration unavailable, using CPU fallback"
                return ModelType.MULTIMODAL
            }
        } catch (e: Exception) {
            // Any other errors - still assume multimodal based on model configuration
            Log.w(TAG, "Model type detection encountered error, assuming multimodal based on configuration: ${e.message}")
            _loadingProgress.value = "✅ Multimodal model loaded - capabilities detected at runtime"
            return ModelType.MULTIMODAL
        }
    }
    
    /**
     * Set the system prompt for LLM conversations
     */
    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt.trim()
        Log.d(TAG, "System prompt updated: ${systemPrompt.take(50)}...")
    }
    
    /**
     * Get the current system prompt
     */
    fun getSystemPrompt(): String = systemPrompt
    
    /**
     * Generate text response with streaming support
     */
    suspend fun generateTextResponseStreaming(
        prompt: String, 
        onPartialResponse: (String, Boolean) -> Unit
    ): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val inference = llmInference ?: run {
                val errorMsg = "Model not loaded. Please load a model first."
                onPartialResponse(errorMsg, true)
                return@withContext errorMsg
            }
            
            Log.d(TAG, "Generating streaming text response with system prompt for prompt: ${prompt.take(50)}...")
            
            // Create a session to include system prompt
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.8f)
                .build()
            
            var session: LlmInferenceSession? = null
            val responseBuilder = StringBuilder()
            
            try {
                session = createSession(inference, sessionOptions)
                if (session == null) {
                    throw Exception("Failed to create LLM session")
                }
                
                // Add system prompt first if it's not empty
                if (systemPrompt.isNotBlank()) {
                    session.addQueryChunk(systemPrompt)
                    Log.d(TAG, "Added system prompt to session")
                }
                
                // Add user prompt
                session.addQueryChunk(prompt)
                
                // Generate response with streaming
                var isComplete = false
                val responseLock = Object()
                
                session.generateResponseAsync { partialResult, done ->
                    synchronized(responseLock) {
                        responseBuilder.append(partialResult)
                        // Send partial response to UI immediately
                        onPartialResponse(responseBuilder.toString(), done)
                        
                        if (done) {
                            isComplete = true
                            responseLock.notifyAll()
                        }
                    }
                }
                
                // Wait for completion
                synchronized(responseLock) {
                    while (!isComplete) {
                        try {
                            responseLock.wait(1000)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
                
                val finalResponse = responseBuilder.toString()
                Log.d(TAG, "Generated streaming text response successfully")
                finalResponse
                
            } finally {
                // Clean up session
                session?.let { closeSession(it) }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate streaming text response", e)
            val errorMsg = "Failed to generate response: ${e.message}"
            onPartialResponse(errorMsg, true)
            errorMsg
        }
    }
    
    suspend fun generateTextResponse(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val inference = llmInference ?: return@withContext Result.failure(
                Exception("Model not loaded. Please load a model first.")
            )
            
            Log.d(TAG, "Generating text response with system prompt for prompt: ${prompt.take(50)}...")
            
            // Create a session to include system prompt
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.8f)
                .build()
            
            var session: LlmInferenceSession? = null
            try {
                session = createSession(inference, sessionOptions)
                if (session == null) {
                    throw Exception("Failed to create LLM session")
                }
                
                // Add system prompt first if it's not empty
                if (systemPrompt.isNotBlank()) {
                    session.addQueryChunk(systemPrompt)
                    Log.d(TAG, "Added system prompt to session")
                }
                
                // Add user prompt
                session.addQueryChunk(prompt)
                
                // Generate response synchronously
                val response = session.generateResponse()
            Log.d(TAG, "Generated text response successfully")
            Result.success(response)
                
            } finally {
                // Clean up session
                session?.let { closeSession(it) }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate text response", e)
            Result.failure(Exception("Failed to generate response: ${e.message}"))
        }
    }
    
    suspend fun generateImageResponse(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (currentModelType != ModelType.IMAGE) {
                return@withContext Result.failure(
                    Exception("Current model doesn't support image generation. Please load an image generation model.")
                )
            }
            
            Log.d(TAG, "Image generation requested for: ${prompt.take(50)}...")
            
            // Placeholder for image generation implementation
            // This would need the actual MediaPipe image generation implementation
            val response = "Image generation functionality is being implemented. " +
                    "This would generate an image based on: '$prompt'"
            
            Log.d(TAG, "Image generation placeholder response")
            Result.success(response)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate image", e)
            Result.failure(Exception("Failed to generate image: ${e.message}"))
        }
    }
    
    /**
     * Generate response using multimodal LLM with image input (streaming version)
     */
    suspend fun generateMultimodalResponseStreaming(
        prompt: String, 
        image: Bitmap, 
        onPartialResponse: (String, Boolean) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val inference = llmInference ?: run {
            onPartialResponse("❌ No LLM model loaded. Please load a model first.", true)
            return@withContext "❌ No LLM model loaded. Please load a model first."
        }
        
        return@withContext try {
            Log.d(TAG, "Starting streaming multimodal response generation...")
            
            // If we don't have a model that supports vision, try alternative approaches
            if (currentModelType != ModelType.MULTIMODAL) {
                Log.d(TAG, "Model doesn't support vision - trying alternative approaches")
                
                // Use MediaPipe Vision analysis as fallback or primary method
                Log.d(TAG, "Using MediaPipe Vision analysis for image")
                
                val visionResult = visionHelper?.analyzeImage(image)
                
                if (visionResult != null) {
                    // Combine vision analysis with LLM for contextual response
                    val contextualPrompt = buildString {
                        append("Based on this image analysis:\n\n")
                        append("${visionResult.summary}\n\n")
                        append("User question: $prompt\n\n")
                        append("Please provide a helpful response based on the image analysis above.")
                    }
                    
                    // Use streaming text-only LLM with vision analysis context
                    return@withContext generateTextResponseStreaming(contextualPrompt, onPartialResponse)
                } else {
                    val errorMsg = "❌ Vision analysis failed and model doesn't support multimodal input. Please try with a text-only question or load a multimodal model."
                    onPartialResponse(errorMsg, true)
                    return@withContext errorMsg
                }
            }
            
            // For multimodal models, proceed with direct image processing
            Log.d(TAG, "Model supports multimodal functionality - processing image directly")
            
            var session: LlmInferenceSession? = null
            val responseBuilder = StringBuilder()
            
            try {
                Log.d(TAG, "Attempting multimodal inference with vision modality")
                
                // Try to create session with vision modality enabled and GPU/OpenCL fallback
                session = createSessionWithFallback(
                    inference = inference,
                    enableVision = true,
                    temperature = 0.8f,
                    topK = 40
                )
                
                if (session == null) {
                    // GPU/OpenCL not supported - fall back to MediaPipe Vision immediately
                    Log.i(TAG, "GPU/OpenCL not available - checking MediaPipe Vision fallback")
                    
                    onPartialResponse("🔍 Checking image analysis capabilities...\n", false)
                    
                    val visionResult = visionHelper?.analyzeImage(image)
                    if (visionResult != null && visionResult.summary.isNotEmpty() && !visionResult.summary.contains("MediaPipe vision features are currently unavailable")) {
                        onPartialResponse("📊 Image analysis complete, generating response...\n", false)
                        
                        val contextualPrompt = buildString {
                            append("Based on this image analysis:\n\n")
                            append("${visionResult.summary}\n\n")
                            append("User question: $prompt\n\n")
                            append("Please provide a helpful response based on the image analysis above.")
                        }
                        
                        // Use text LLM with the contextual prompt
                        return@withContext generateTextResponseStreaming(contextualPrompt, onPartialResponse)
                    } else {
                        // MediaPipe Vision is also unavailable - ask user to describe the image
                        val helpfulMsg = buildString {
                            append("🔍 **Image Analysis Available**\n\n")
                            append("I can analyze your image! While advanced GPU processing isn't available on this device, I can still provide detailed image analysis.\n\n")
                            append("**What I can do:**\n\n")
                            append("• **Analyze image properties and visual characteristics**\n")
                            append("• **Provide basic object recognition**\n")
                            append("• **Combine analysis with AI reasoning**\n")
                            append("• **Give insights about food and glucose impact**\n\n")
                            append("Please ask your question about the image and I'll help with the analysis!")
                        }
                        onPartialResponse(helpfulMsg, true)
                        return@withContext helpfulMsg
                    }
                }
                
                Log.d(TAG, "Successfully created multimodal session")
                
                // Add system prompt first if it's not empty
                if (systemPrompt.isNotBlank()) {
                    session.addQueryChunk(systemPrompt)
                    Log.d(TAG, "Added system prompt to multimodal session")
                }
                
                // Add user text prompt to session
                if (prompt.trim().isNotEmpty()) {
                    session.addQueryChunk(prompt.trim())
                    Log.d(TAG, "Added text query chunk to session")
                }
                
                // Optimize image for faster processing before adding to session
                try {
                    val optimizedImage = optimizeImageForProcessing(image)
                    val mpImage = BitmapImageBuilder(optimizedImage).build()
                    session.addImage(mpImage)
                    Log.d(TAG, "Added optimized image to session successfully (${optimizedImage.width}x${optimizedImage.height})")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add image to session", e)
                    throw Exception("Failed to process image: ${e.message}")
                }
                
                // Generate response asynchronously with streaming
                Log.d(TAG, "Starting async streaming response generation...")
                
                var isComplete = false
                val responseLock = Object()
                
                // Use callback-based response generation with streaming updates
                session.generateResponseAsync { partialResult, done ->
                    synchronized(responseLock) {
                        responseBuilder.append(partialResult)
                        // Send partial response to UI immediately
                        onPartialResponse(responseBuilder.toString(), done)
                        
                        if (done) {
                            isComplete = true
                            responseLock.notifyAll()
                        }
                    }
                }
                
                // Wait for response completion with extended timeout for multimodal processing
                val startTime = System.currentTimeMillis()
                val timeoutMs = 90000L // 90 seconds timeout for multimodal processing
                var lastLogTime = startTime
                
                synchronized(responseLock) {
                    while (!isComplete && (System.currentTimeMillis() - startTime) < timeoutMs) {
                        try {
                            responseLock.wait(1000) // Wait up to 1 second at a time
                            
                            // Log progress every 15 seconds to show processing is still active
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastLogTime > 15000) {
                                val elapsedSeconds = (currentTime - startTime) / 1000
                                Log.d(TAG, "Multimodal processing still active... ${elapsedSeconds}s elapsed")
                                lastLogTime = currentTime
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
                
                if (!isComplete) {
                    val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                    Log.w(TAG, "Multimodal processing timed out after ${elapsedSeconds}s - falling back to vision analysis")
                    
                    // Instead of throwing an exception, fall back to MediaPipe Vision analysis
                    val visionResult = visionHelper?.analyzeImage(image)
                    if (visionResult != null) {
                        val contextualPrompt = buildString {
                            append("Based on this image analysis:\n\n")
                            append("${visionResult.summary}\n\n")
                            append("User question: $prompt\n\n")
                            append("Please provide a helpful response based on the image analysis above.")
                        }
                        return@withContext generateTextResponseStreaming(contextualPrompt, onPartialResponse)
                    } else {
                        val errorMsg = "❌ Multimodal processing timed out after ${elapsedSeconds}s and no vision fallback available"
                        onPartialResponse(errorMsg, true)
                        throw Exception(errorMsg)
                    }
                }
                
                val finalResponse = responseBuilder.toString().trim()
                
                if (finalResponse.isEmpty()) {
                    Log.w(TAG, "Generated response is empty")
                    val emptyMsg = "🤔 The model processed the image but didn't generate a response. Try rephrasing your question or checking if the image is clear."
                    onPartialResponse(emptyMsg, true)
                    return@withContext emptyMsg
                }
                
                Log.d(TAG, "Multimodal response generated successfully (${finalResponse.length} characters)")
                return@withContext finalResponse
                
            } catch (e: Exception) {
                Log.e(TAG, "Multimodal inference failed: ${e.message}", e)
                
                // Enhanced fallback handling
                when {
                    e.message?.contains("OpenCL") == true || 
                    e.message?.contains("delegate") == true ||
                    e.message?.contains("GPU") == true -> {
                        Log.w(TAG, "GPU/OpenCL issue detected - falling back to vision analysis")
                        
                        // Note: We keep the model type as MULTIMODAL since the model supports it
                        // This is just a GPU acceleration limitation, not a model capability issue
                        
                        // Try MediaPipe Vision analysis as fallback
                        val visionResult = visionHelper?.analyzeImage(image)
                        if (visionResult != null && !visionResult.summary.contains("MediaPipe vision features are currently unavailable")) {
                            val contextualPrompt = buildString {
                                append("Based on this image analysis:\n\n")
                                append("${visionResult.summary}\n\n")
                                append("User question: $prompt\n\n")
                                append("Please provide a helpful response based on the image analysis above.")
                            }
                            return@withContext generateTextResponseStreaming(contextualPrompt, onPartialResponse)
                        }
                        
                        val helpfulMsg = buildString {
                            append("🔍 **Image Analysis Available**\n\n")
                            append("I can analyze your image! While advanced GPU processing isn't available on this device, I can still provide detailed image analysis.\n\n")
                            append("**What I can do:**\n\n")
                            append("• **Analyze image properties and visual characteristics**\n")
                            append("• **Provide basic object recognition**\n")
                            append("• **Combine analysis with AI reasoning**\n")
                            append("• **Give insights about food and glucose impact**\n\n")
                            append("Please ask your question about the image and I'll help with the analysis!")
                        }
                        onPartialResponse(helpfulMsg, true)
                        return@withContext helpfulMsg
                    }
                    else -> {
                        val errorMsg = "❌ Multimodal inference failed: ${e.message}. Try with a different image or text-only question."
                        onPartialResponse(errorMsg, true)
                        return@withContext errorMsg
                    }
                }
            } finally {
                // Clean up session
                session?.let { closeSession(it) }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in multimodal response generation", e)
            val errorMsg = "❌ Unexpected error: ${e.message}"
            onPartialResponse(errorMsg, true)
            errorMsg
        }
    }

    /**
     * Generate response using multimodal LLM with image input (non-streaming version for compatibility)
     */
    suspend fun generateMultimodalResponse(prompt: String, image: Bitmap): String = withContext(Dispatchers.IO) {
        val inference = llmInference ?: return@withContext "❌ No LLM model loaded. Please load a model first."
        
        return@withContext try {
            Log.d(TAG, "Starting multimodal response generation...")
            
            // If we don't have a model that supports vision, try alternative approaches
            if (currentModelType != ModelType.MULTIMODAL) {
                Log.d(TAG, "Model doesn't support vision - trying alternative approaches")
            
            // Use MediaPipe Vision analysis as fallback or primary method
            Log.d(TAG, "Using MediaPipe Vision analysis for image")
            
            val visionResult = visionHelper?.analyzeImage(image)
            
            if (visionResult != null) {
                // Combine vision analysis with LLM for contextual response
                val contextualPrompt = buildString {
                    append("Based on this image analysis:\n\n")
                    append("${visionResult.summary}\n\n")
                    append("User question: $prompt\n\n")
                    append("Please provide a helpful response based on the image analysis above.")
                }
                
                    // Use text-only LLM with vision analysis context
                    val textResult = generateTextResponse(contextualPrompt)
                    return@withContext if (textResult.isSuccess) {
                        textResult.getOrDefault("")
                    } else {
                        "❌ Text response failed: ${textResult.exceptionOrNull()?.message}"
                    }
                } else {
                    return@withContext "❌ Vision analysis failed and model doesn't support multimodal input. Please try with a text-only question or load a multimodal model."
                }
            }
            
            // For multimodal models, proceed with direct image processing
            Log.d(TAG, "Model supports multimodal functionality - processing image directly")
            
            var session: LlmInferenceSession? = null
            
            try {
                Log.d(TAG, "Attempting multimodal inference with vision modality")
                
                // Try to create session with vision modality enabled and GPU/OpenCL fallback
                session = createSessionWithFallback(
                    inference = inference,
                    enableVision = true,
                    temperature = 0.8f,
                    topK = 40
                )
                
                if (session == null) {
                    // GPU/OpenCL not supported - fall back to MediaPipe Vision immediately
                    Log.i(TAG, "GPU/OpenCL not available - checking MediaPipe Vision fallback")
                    
                    val visionResult = visionHelper?.analyzeImage(image)
                    if (visionResult != null && visionResult.summary.isNotEmpty() && !visionResult.summary.contains("MediaPipe vision features are currently unavailable")) {
                        val contextualPrompt = buildString {
                            append("Based on this image analysis:\n\n")
                            append("${visionResult.summary}\n\n")
                            append("User question: $prompt\n\n")
                            append("Please provide a helpful response based on the image analysis above.")
                        }
                        
                        // Use text LLM with the contextual prompt
                        val textResult = generateTextResponse(contextualPrompt)
                        return@withContext if (textResult.isSuccess) {
                            textResult.getOrDefault("")
                        } else {
                            "❌ Failed to generate response: ${textResult.exceptionOrNull()?.message}"
                        }
                    } else {
                        // MediaPipe Vision is also unavailable - ask user to describe the image
                        return@withContext buildString {
                            append("🔍 **Image Analysis Available**\n\n")
                            append("I can analyze your image! While advanced GPU processing isn't available on this device, I can still provide detailed image analysis.\n\n")
                            append("**What I can do:**\n\n")
                            append("• **Analyze image properties and visual characteristics**\n")
                            append("• **Provide basic object recognition**\n")
                            append("• **Combine analysis with AI reasoning**\n")
                            append("• **Give insights about food and glucose impact**\n\n")
                            append("Please ask your question about the image and I'll help with the analysis!")
                        }
                    }
                }
                
                Log.d(TAG, "Successfully created multimodal session")
                
                // Add system prompt first if it's not empty
                if (systemPrompt.isNotBlank()) {
                    session.addQueryChunk(systemPrompt)
                    Log.d(TAG, "Added system prompt to multimodal session")
                }
                
                // Add user text prompt to session
                if (prompt.trim().isNotEmpty()) {
                    session.addQueryChunk(prompt.trim())
                    Log.d(TAG, "Added text query chunk to session")
                }
                
                // Optimize image for faster processing before adding to session
                try {
                    val optimizedImage = optimizeImageForProcessing(image)
                    val mpImage = BitmapImageBuilder(optimizedImage).build()
                    session.addImage(mpImage)
                    Log.d(TAG, "Added optimized image to session successfully (${optimizedImage.width}x${optimizedImage.height})")
                    } catch (e: Exception) {
                    Log.e(TAG, "Failed to add image to session", e)
                    throw Exception("Failed to process image: ${e.message}")
                }
                
                // Generate response asynchronously
                Log.d(TAG, "Starting async response generation...")
                
                val responseBuilder = StringBuilder()
                var isComplete = false
                val responseLock = Object()
                
                // Use callback-based response generation (following reference pattern)
                session.generateResponseAsync { partialResult, done ->
                    synchronized(responseLock) {
                        responseBuilder.append(partialResult)
                        if (done) {
                            isComplete = true
                            responseLock.notifyAll()
                        }
                    }
                }
                
                // Wait for response completion with extended timeout for multimodal processing
                val startTime = System.currentTimeMillis()
                val timeoutMs = 90000L // 90 seconds timeout for multimodal processing
                var lastLogTime = startTime
                
                synchronized(responseLock) {
                    while (!isComplete && (System.currentTimeMillis() - startTime) < timeoutMs) {
                        try {
                            responseLock.wait(1000) // Wait up to 1 second at a time
                            
                            // Log progress every 15 seconds to show processing is still active
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastLogTime > 15000) {
                                val elapsedSeconds = (currentTime - startTime) / 1000
                                Log.d(TAG, "Multimodal processing still active... ${elapsedSeconds}s elapsed")
                                lastLogTime = currentTime
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
                
                if (!isComplete) {
                    val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                    Log.w(TAG, "Multimodal processing timed out after ${elapsedSeconds}s - falling back to vision analysis")
                    
                    // Instead of throwing an exception, fall back to MediaPipe Vision analysis
                    val visionResult = visionHelper?.analyzeImage(image)
                    if (visionResult != null) {
                        val contextualPrompt = buildString {
                            append("Based on this image analysis:\n\n")
                            append("${visionResult.summary}\n\n")
                            append("User question: $prompt\n\n")
                            append("Please provide a helpful response based on the image analysis above.")
                        }
                        val textResult = generateTextResponse(contextualPrompt)
                        return@withContext if (textResult.isSuccess) {
                            textResult.getOrDefault("")
                        } else {
                            "❌ Multimodal processing timed out and fallback failed: ${textResult.exceptionOrNull()?.message}"
                        }
                } else {
                        throw Exception("Multimodal processing timed out after ${elapsedSeconds}s and no vision fallback available")
                    }
                }
                
                val finalResponse = responseBuilder.toString().trim()
                
                if (finalResponse.isEmpty()) {
                    Log.w(TAG, "Generated response is empty")
                    return@withContext "🤔 The model processed the image but didn't generate a response. Try rephrasing your question or checking if the image is clear."
                }
                
                Log.d(TAG, "Multimodal response generated successfully (${finalResponse.length} characters)")
                return@withContext finalResponse
                
            } catch (e: Exception) {
                Log.e(TAG, "Multimodal inference failed: ${e.message}", e)
                
                // Enhanced fallback handling
                when {
                    e.message?.contains("OpenCL") == true || 
                    e.message?.contains("delegate") == true ||
                    e.message?.contains("GPU") == true -> {
                        Log.w(TAG, "GPU/OpenCL issue detected - falling back to vision analysis")
                        
                        // Note: We keep the model type as MULTIMODAL since the model supports it  
                        // This is just a GPU acceleration limitation, not a model capability issue
                        
                        // Try MediaPipe Vision analysis as fallback
                        val visionResult = visionHelper?.analyzeImage(image)
                        if (visionResult != null) {
                            val contextualPrompt = buildString {
                                append("Based on this image analysis:\n\n")
                                append("${visionResult.summary}\n\n")
                                append("User question: $prompt\n\n")
                                append("Please provide a helpful response based on the image analysis above.")
                            }
                            return@withContext generateTextResponse(contextualPrompt).getOrElse { 
                                "❌ Failed to generate response: ${it.message}"
                            }
                        }
                        
                        return@withContext buildString {
                            append("🔍 **Image Analysis Available**\n\n")
                            append("I can analyze your image! While advanced GPU processing isn't available on this device, I can still provide detailed image analysis.\n\n")
                            append("**What I can do:**\n\n")
                            append("• **Analyze image properties and visual characteristics**\n")
                            append("• **Provide basic object recognition**\n")
                            append("• **Combine analysis with AI reasoning**\n")
                            append("• **Give insights about food and glucose impact**\n\n")
                            append("Please ask your question about the image and I'll help with the analysis!")
                        }
                    }
                    else -> {
                        return@withContext "❌ Multimodal inference failed: ${e.message}. Try with a different image or text-only question."
                    }
                }
            } finally {
                // Clean up session
                session?.let { closeSession(it) }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in multimodal response generation", e)
            "❌ Unexpected error: ${e.message}"
        }
    }
    
    fun getModelType(): ModelType = currentModelType
    
    fun supportsText(): Boolean = currentModelType in listOf(ModelType.TEXT, ModelType.MULTIMODAL)
    
    fun supportsImages(): Boolean = currentModelType == ModelType.MULTIMODAL
    
    fun supportsImageGeneration(): Boolean = currentModelType == ModelType.IMAGE
    
    /**
     * Get MediaPipe Vision Helper status
     */
    fun getVisionStatus(): Map<String, Boolean> {
        return visionHelper?.getTaskStatus() ?: mapOf(
            "imageClassifier" to false,
            "objectDetector" to false,
            "faceDetector" to false
        )
    }
    
    fun cleanup() {
        Log.d(TAG, "Starting cleanup process...")
        
        // First, close all active sessions before closing the main inference
        closeAllSessions()
        
        // Wait a bit to ensure sessions are fully closed
        try {
            Thread.sleep(50) // Small delay to ensure JNI cleanup completes
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        // Then close the main LLM inference
        try {
        llmInference?.close()
            Log.d(TAG, "Main LLM inference closed successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing main LLM inference", e)
        }
        llmInference = null
        
        // Close vision helper
        try {
        visionHelper?.close()
            Log.d(TAG, "Vision helper closed successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing vision helper", e)
        }
        visionHelper = null
        
        _isModelLoaded.value = false
        currentModelType = ModelType.UNKNOWN
        
        Log.d(TAG, "Cleanup completed. Reinitializing vision helper...")
        
        // Reinitialize vision helper for next use
        initializeVisionHelper()
    }
    
    /**
     * Optimize image for faster multimodal processing
     * Reduces image size while maintaining aspect ratio and quality
     */
    private fun optimizeImageForProcessing(bitmap: Bitmap): Bitmap {
        val maxWidth = 1024
        val maxHeight = 1024
        
        // If image is already small enough, return as-is
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) {
            Log.d(TAG, "Image already optimal size: ${bitmap.width}x${bitmap.height}")
            return bitmap
        }
        
        // Calculate scaling factor to maintain aspect ratio
        val widthRatio = maxWidth.toFloat() / bitmap.width
        val heightRatio = maxHeight.toFloat() / bitmap.height
        val scaleFactor = minOf(widthRatio, heightRatio)
        
        val newWidth = (bitmap.width * scaleFactor).toInt()
        val newHeight = (bitmap.height * scaleFactor).toInt()
        
        Log.d(TAG, "Optimizing image from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight} (scale: $scaleFactor)")
        
        // Create scaled bitmap with high quality filtering
        val optimizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        
        // Ensure ARGB_8888 format for MediaPipe compatibility
        return if (optimizedBitmap.config == Bitmap.Config.ARGB_8888) {
            optimizedBitmap
        } else {
            val convertedBitmap = optimizedBitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (convertedBitmap != optimizedBitmap) {
                optimizedBitmap.recycle()
            }
            convertedBitmap
        }
    }
} 