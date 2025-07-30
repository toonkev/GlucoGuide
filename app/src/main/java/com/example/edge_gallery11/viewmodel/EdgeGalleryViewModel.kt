package com.example.edge_gallery11.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.edge_gallery11.llm.LlmInferenceManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.OpenableColumns
import java.util.zip.ZipInputStream

data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val image: Bitmap? = null,
    val id: String = System.currentTimeMillis().toString() + "_" + kotlin.random.Random.nextInt(1000)
)

data class PromptTemplate(
    val name: String,
    val template: String,
    val placeholder: String = "Enter your text here..."
)

class EdgeGalleryViewModel(application: Application) : AndroidViewModel(application) {
    
    private val llmManager = LlmInferenceManager(application)
    private val sharedPreferences = application.getSharedPreferences("edge_gallery_prefs", Context.MODE_PRIVATE)

    // Model state
    val isModelLoaded = llmManager.isModelLoaded
    val isLoading = llmManager.isLoading
    val loadingProgress = llmManager.loadingProgress
    
    // Multimodal support - derived from the actual model capabilities
    val supportsImages: StateFlow<Boolean> = isModelLoaded.map { 
        if (it) llmManager.supportsImages() else false 
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    
    private val _modelPath = MutableStateFlow<String?>(sharedPreferences.getString("model_path", null))
    val modelPath: StateFlow<String?> = _modelPath.asStateFlow()

    // System prompt state
    private val defaultSystemPrompt = """You are GlucoGuide's glucose analyzer. Give VERY brief responses (3-4 short lines max).

Format:
**Impact:** [HIGH/MODERATE/LOW] glucose spike likely
**Why:** [Main carb sources + estimated grams]
**Balancers:** [Protein/fat/fiber present]
**Tip:** [One quick management suggestion]

Keep responses short and focused only on glucose impact."""
    private val _systemPrompt = MutableStateFlow(sharedPreferences.getString("system_prompt", defaultSystemPrompt) ?: defaultSystemPrompt)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _isCopying = MutableStateFlow(false)
    val isCopying: StateFlow<Boolean> = _isCopying.asStateFlow()

    // Chat state
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()
    
    // Ask Image state
    private val _selectedImage = MutableStateFlow<Bitmap?>(null)
    val selectedImage: StateFlow<Bitmap?> = _selectedImage.asStateFlow()
    
    private val _imageAnalysisResult = MutableStateFlow<Bitmap?>(null)
    val imageAnalysisResult: StateFlow<Bitmap?> = _imageAnalysisResult.asStateFlow()
    
    private val _imageAnalysisText = MutableStateFlow<String?>(null)
    val imageAnalysisText: StateFlow<String?> = _imageAnalysisText.asStateFlow()
    
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    
    // Separate state for image processing animation
    private val _isProcessingImage = MutableStateFlow(false)
    val isProcessingImage: StateFlow<Boolean> = _isProcessingImage.asStateFlow()
    
    // Error state
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // Prompt templates
    val promptTemplates = listOf(
        PromptTemplate(
            "Summarize",
            "Please summarize the following text in a concise manner:\n\n{input}",
            "Enter text to summarize..."
        ),
        PromptTemplate(
            "Rewrite",
            "Please rewrite the following text to be more professional and clear:\n\n{input}",
            "Enter text to rewrite..."
        ),
        PromptTemplate(
            "Generate Code",
            "Please generate {language} code for the following requirement:\n\n{input}",
            "Describe what code you need..."
        ),
        PromptTemplate(
            "Explain",
            "Please explain the following concept in simple terms:\n\n{input}",
            "Enter concept to explain..."
        ),
        PromptTemplate(
            "Translate",
            "Please translate the following text to {language}:\n\n{input}",
            "Enter text to translate..."
        )
    )
    
    init {
        // We are removing the automatic model initialization on startup to prevent crashes
        // if the stored model path is invalid.
        // modelPath.value?.let { initializeModel(it) }
    }
    
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if(displayNameIndex != -1) {
                        result = cursor.getString(displayNameIndex)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                if (cut != null) {
                    result = result.substring(cut + 1)
                }
            }
        }
        return result
    }
    
    fun selectModelFile(uri: Uri) {
        viewModelScope.launch {
            _isCopying.value = true
            _errorMessage.value = null // Clear previous errors
            val result = withContext(Dispatchers.IO) {
                try {
                    val contentResolver = getApplication<Application>().contentResolver
                    val mimeType = contentResolver.getType(uri)
                    val fileName = getFileName(uri)

                    if (mimeType == "application/zip" || fileName?.endsWith(".zip", ignoreCase = true) == true) {
                        val modelDir = File(getApplication<Application>().cacheDir, "unzipped_model")
                        if (modelDir.exists()) {
                            modelDir.deleteRecursively()
                        }
                        modelDir.mkdirs()

                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            ZipInputStream(inputStream).use { zipInputStream ->
                                var entry = zipInputStream.nextEntry
                                while (entry != null) {
                                    val file = File(modelDir, entry.name)
                                    if (entry.isDirectory) {
                                        file.mkdirs()
                                    } else {
                                        file.outputStream().use { outputStream ->
                                            zipInputStream.copyTo(outputStream)
                                        }
                                    }
                                    entry = zipInputStream.nextEntry
                                }
                            }
                        }
                        // The ImageGenerator needs the path to the directory containing the model files
                        Result.success(modelDir.absolutePath)
                    } else {
                        // Assume it's a .task file or similar single file model
                        val tempFile = File(getApplication<Application>().cacheDir, "selected_model.task")
                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            tempFile.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        Result.success(tempFile.absolutePath)
                    }
                } catch (e: Exception) {
                    Result.failure<String>(e)
                }
            }
            _isCopying.value = false

            result.onSuccess { path ->
                _modelPath.value = path
                sharedPreferences.edit().putString("model_path", path).apply()
                initializeModel(path)
            }.onFailure { e ->
                _errorMessage.value = "Failed to process model file: ${e.message}"
            }
        }
    }

    fun initializeModel(path: String? = _modelPath.value) {
        if (path.isNullOrEmpty()) {
            _errorMessage.value = "Model path is not set. Please select a model in Settings."
            return
        }
        viewModelScope.launch {
            val result = llmManager.initializeModel(path)
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.message
            } else {
                // Set the system prompt when model is successfully loaded
                llmManager.setSystemPrompt(_systemPrompt.value)
            }
        }
    }
    
    fun clearError() {
        _errorMessage.value = null
    }
    
    // System prompt functions
    fun updateSystemPrompt(newPrompt: String) {
        _systemPrompt.value = newPrompt.trim()
        sharedPreferences.edit().putString("system_prompt", newPrompt.trim()).apply()
        // Update the LLM manager with the new system prompt
        llmManager.setSystemPrompt(newPrompt.trim())
    }
    
    fun resetSystemPrompt() {
        updateSystemPrompt(defaultSystemPrompt)
    }
    
    // Ask Image functions
    fun selectImage(bitmap: Bitmap) {
        // Ensure bitmap is in ARGB_8888 format for MediaPipe
        _selectedImage.value = ensureARGB8888(bitmap)
        _imageAnalysisResult.value = null
    }
    
    fun clearSelectedImage() {
        _selectedImage.value = null
    }
    
    private fun ensureARGB8888(bitmap: Bitmap): Bitmap {
        return if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            // Convert to ARGB_8888 format
            val convertedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            // Only recycle the original if it's not the same instance
            if (convertedBitmap != bitmap) {
                bitmap.recycle()
            }
            convertedBitmap
        }
    }
    
    fun selectImageFromUri(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val contentResolver = getApplication<Application>().contentResolver
                    val originalBitmap = if (Build.VERSION.SDK_INT < 28) {
                        @Suppress("DEPRECATION")
                        android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    } else {
                        val source = ImageDecoder.createSource(contentResolver, uri)
                        ImageDecoder.decodeBitmap(source)
                    }
                    
                    // Convert to ARGB_8888 format required by MediaPipe
                    ensureARGB8888(originalBitmap)
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to load image: ${e.message}"
                    null
                }
            }
            bitmap?.let { selectImage(it) }
        }
    }
    
    fun analyzeImage(prompt: String) {
        val selectedImageBitmap = _selectedImage.value
        viewModelScope.launch {
            _isGenerating.value = true
            _isProcessingImage.value = true
            _imageAnalysisResult.value = null
            _imageAnalysisText.value = ""  // Start with empty string for streaming
            
            if (selectedImageBitmap != null) {
                try {
                    // Use streaming multimodal LLM to analyze the image with the prompt
                    llmManager.generateMultimodalResponseStreaming(prompt, selectedImageBitmap) { partialResponse, done ->
                        // Update the analysis text with streaming content
                        _imageAnalysisText.value = partialResponse
                        
                        if (done) {
                            _isGenerating.value = false
                            _isProcessingImage.value = false
                        }
                    }
                } catch (e: Exception) {
                    _errorMessage.value = e.message
                    _isGenerating.value = false
                    _isProcessingImage.value = false
                }
            } else {
                _errorMessage.value = "Please select an image first"
                _isGenerating.value = false
                _isProcessingImage.value = false
            }
        }
    }
    
    // Chat functions
    fun sendChatMessage(message: String, image: Bitmap? = null) {
        if (message.isBlank() && image == null) return

        val userMessage = ChatMessage(content = message, isUser = true, image = image)
        _chatMessages.value = _chatMessages.value + userMessage
        
        // Create an initial empty AI response message that will be updated as streaming progresses
        val aiMessageId = "ai_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000)}"
        val initialAiMessage = ChatMessage(content = "", isUser = false, id = aiMessageId)
        _chatMessages.value = _chatMessages.value + initialAiMessage
        
        viewModelScope.launch {
            _isGenerating.value = true
            
            // Set image processing state if image is present
            if (image != null) {
                _isProcessingImage.value = true
            }
            
            try {
                if (image != null) {
                    // Use streaming multimodal response when image is present
                    llmManager.generateMultimodalResponseStreaming(message, image) { partialResponse, done ->
                        // Update the AI message with the latest partial response
                        updateChatMessage(aiMessageId, partialResponse)
                        
                        if (done) {
                            _isGenerating.value = false
                            _isProcessingImage.value = false
                        }
                    }
                } else {
                    // Use streaming text-only response when no image
                    llmManager.generateTextResponseStreaming(message) { partialResponse, done ->
                        // Update the AI message with the latest partial response
                        updateChatMessage(aiMessageId, partialResponse)
                        
                        if (done) {
                            _isGenerating.value = false
                        }
                    }
                }
            } catch (e: Exception) {
                // Update the AI message with error content
                updateChatMessage(aiMessageId, "Error: ${e.message}")
                _isGenerating.value = false
                _isProcessingImage.value = false
            }
        }
    }
    
    /**
     * Update a specific chat message by ID
     */
    private fun updateChatMessage(messageId: String, newContent: String) {
        _chatMessages.value = _chatMessages.value.map { message ->
            if (message.id == messageId) {
                message.copy(content = newContent)
            } else {
                message
            }
        }
    }
    
    fun clearChat() {
        _chatMessages.value = emptyList()
    }
    
    override fun onCleared() {
        super.onCleared()
        llmManager.cleanup()
    }
} 