
![glucoguide logo2](https://github.com/user-attachments/assets/330ef197-8acf-4ed0-9974-8200754e418f)


GlucoGuide - AI-Powered Image Processing App

A modern Android application built with **MediaPipe** for on-device AI image processing and multimodal LLM inference. This app demonstrates state-of-the-art computer vision and generative AI capabilities running entirely on your device.

## 🚀 Features

### **MediaPipe-Powered Image Processing**
- **Image Classification**: Identifies objects and concepts in images using EfficientNet
- **Object Detection**: Detects and localizes objects with bounding boxes using COCO-SSD
- **Face Detection**: Detects faces with confidence scores
- **Real-time Analysis**: Fast, on-device processing optimized for mobile devices

### **Multimodal AI Capabilities**
- **Ask Image**: Upload images and ask questions about them
- **AI Chat**: Multi-turn conversations with LLM models
- **Vision + LLM**: Combines MediaPipe vision analysis with language model responses

### **Model Support**
- **Multimodal Models**: PaliGemma, Gemma-3n E2B/E4B for vision-language tasks
- **Text-Only Models**: Gemma, LLaMA, and other LiteRT-compatible models
- **Image Generation**: MediaPipe image generation model support
- **Custom Models**: Load your own .task files

## 🛠️ Technology Stack

### **Core Technologies**
- **MediaPipe Tasks**: Google's on-device ML framework
  - `com.google.mediapipe:tasks-vision:0.10.14` - Computer vision tasks
  - `com.google.mediapipe:tasks-genai:0.10.25` - LLM inference
- **Android Jetpack Compose**: Modern declarative UI
- **Kotlin Coroutines**: Asynchronous programming
- **MVVM Architecture**: Clean separation of concerns

### **MediaPipe Vision Tasks**
- **ImageClassifier**: Pre-trained EfficientNet models for image classification
- **ObjectDetector**: COCO-SSD MobileNet for object detection
- **FaceDetector**: Lightweight face detection with confidence scoring
- **Optimized Processing**: CPU/GPU delegate support for performance

### **LLM Integration**
- **LlmInference**: MediaPipe's on-device LLM framework
- **Session Management**: Efficient context handling for conversations
- **Vision Modality**: Multimodal processing for image + text inputs
- **Streaming Responses**: Real-time token generation

## 📱 App Architecture

### **MediaPipe Vision Pipeline**
```kotlin
// MediaPipeVisionHelper - Handles all computer vision tasks
class MediaPipeVisionHelper {
    private var imageClassifier: ImageClassifier?
    private var objectDetector: ObjectDetector?
    private var faceDetector: FaceDetector?
    
    fun analyzeImage(bitmap: Bitmap): VisionAnalysisResult
}
```

### **LLM Inference Manager**
```kotlin
// LlmInferenceManager - Manages AI model loading and inference
class LlmInferenceManager {
    private var llmInference: LlmInference?
    private var visionHelper: MediaPipeVisionHelper?
    
    suspend fun generateMultimodalResponse(prompt: String, image: Bitmap): Result<String>
}
```

### **UI Components**
- **AIChatScreen**: Multi-turn conversations with image support
- **AskImageScreen**: Single-turn image analysis (coming soon)
- **SettingsScreen**: Model management and configuration

## 🔧 Setup and Installation

### **Prerequisites**
- Android Studio 2024.1.1+
- Minimum SDK 24 (Android 7.0)
- Target SDK 36 (Android 14)
- 4GB+ device storage for models

### **Building the App**
```bash
git clone <repository-url>
cd edge_gallery11
./gradlew assembleDebug
```

### **Loading Models**
1. **Automatic Download**: Use the Settings screen to browse and download models
2. **Manual Installation**: Place .task files in `/storage/emulated/0/Download/`
3. **Custom Models**: Select any LiteRT-compatible .task file via file picker

## 🧠 Supported Models

### **Multimodal Models** (Vision + Language)
- **PaliGemma 3B**: Google's vision-language model
- **Gemma-3n E2B/E4B**: Efficient multimodal models
- **Custom Vision Models**: Any MediaPipe-compatible multimodal .task file

### **Text-Only Models**
- **Gemma 2B/7B**: Google's lightweight LLM
- **LLaMA-2**: Meta's language model (converted to LiteRT)
- **Custom LLMs**: Any text-generation .task model

### **Vision Models**
- **EfficientNet-Lite**: Image classification (built-in)
- **COCO-SSD MobileNet**: Object detection (built-in)
- **MediaPipe Face Detection**: Face detection (built-in)

## 💡 Usage Examples

### **Image Analysis Workflow**
1. **Select Image**: Camera or gallery picker
2. **MediaPipe Processing**: Automatic vision analysis
3. **LLM Enhancement**: Optional language model response
4. **Results Display**: Classifications, objects, faces, and AI commentary

### **Chat with Images**
1. **Start Conversation**: Navigate to AI Chat
2. **Attach Image**: Tap image icon, select photo
3. **Ask Question**: Type your question about the image
4. **Get Response**: Receive multimodal AI analysis

### **Model Performance**
- **Vision Tasks**: ~50-200ms on modern devices
- **LLM Inference**: ~2-5 seconds for short responses
- **Memory Usage**: ~1-3GB depending on model size
- **Battery Impact**: Optimized for on-device efficiency

## 🔍 Technical Details

### **Image Processing Pipeline**
```kotlin
fun analyzeImage(bitmap: Bitmap): VisionAnalysisResult {
    // 1. Format conversion (ARGB_8888)
    val processedBitmap = ensureCorrectBitmapFormat(bitmap)
    
    // 2. MediaPipe conversion
    val mpImage = BitmapImageBuilder(processedBitmap).build()
    
    // 3. Parallel vision tasks
    val classifications = runImageClassification(mpImage)
    val detections = runObjectDetection(mpImage)
    val faces = runFaceDetection(mpImage)
    
    // 4. Result aggregation
    return VisionAnalysisResult(classifications, detections, faces, summary)
}
```

### **Multimodal Integration**
```kotlin
suspend fun generateMultimodalResponse(prompt: String, image: Bitmap): Result<String> {
    // 1. Try native multimodal model first
    if (currentModelType == ModelType.MULTIMODAL) {
        val session = LlmInferenceSession.createFromOptions(
            llmInference,
            sessionOptions.setGraphOptions(
                GraphOptions.builder().setEnableVisionModality(true).build()
            )
        )
        session.addQueryChunk(prompt)
        session.addImage(BitmapImageBuilder(image).build())
        return session.generateResponse()
    }
    
    // 2. Fallback: MediaPipe vision + text LLM
    val visionAnalysis = visionHelper.analyzeImage(image)
    val contextualPrompt = "Based on this image analysis: ${visionAnalysis.summary}\n\nUser question: $prompt"
    return llmInference.generateResponse(contextualPrompt)
}
```

## 🚧 Development Status

- ✅ **MediaPipe Vision Processing**: Fully implemented
- ✅ **LLM Text Generation**: Working with all model types
- ✅ **Multimodal Chat**: Vision + language integration complete
- ✅ **UI Framework**: Complete with modern Material Design 3
- 🔄 **Ask Image Screen**: Dedicated image analysis UI (in progress)
- 🔄 **Image Generation**: MediaPipe image generation models
- 🔄 **Performance Optimization**: GPU delegate improvements

## 🤝 Contributing

This project demonstrates MediaPipe's capabilities for on-device AI. Contributions welcome for:
- Additional vision tasks (pose detection, segmentation)
- New model integrations
- Performance optimizations
- UI/UX improvements

## 📄 License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

## 🔗 Resources

- [MediaPipe Documentation](https://developers.google.com/mediapipe)
- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)
- [LiteRT Model Hub](https://ai.google.dev/edge/litert)
- [Gemma 3n Model Download](https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/tree/main)  
- [MediaPipe Tasks Guide](https://developers.google.com/mediapipe/solutions/tasks)

---

Built with ❤️ using Google's MediaPipe and modern Android development tools. 
