package com.example.edge_gallery11.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.edge_gallery11.viewmodel.ChatMessage
import com.example.edge_gallery11.viewmodel.EdgeGalleryViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatScreen(
    viewModel: EdgeGalleryViewModel,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val loadingProgress by viewModel.loadingProgress.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val selectedImage by viewModel.selectedImage.collectAsState()
    
    // Collect image processing state for animation
    val isProcessingImage by viewModel.isProcessingImage.collectAsState()
    
    var messageText by remember { mutableStateOf("") }
    var showImageBottomSheet by remember { mutableStateOf(false) }
    
    // Use the proper supportsImages property from ViewModel
    val supportsMultimodal by viewModel.supportsImages.collectAsState()
    
    // Image pickers
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.selectImageFromUri(uri)
        }
    }
    
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            viewModel.selectImage(it)
        }
    }
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            coroutineScope.launch {
                lazyListState.animateScrollToItem(chatMessages.size - 1)
            }
        }
    }
    
    // Auto-scroll during streaming (when content of the last message changes)
    LaunchedEffect(chatMessages.lastOrNull()?.content, isGenerating) {
        if (chatMessages.isNotEmpty() && isGenerating) {
            coroutineScope.launch {
                // Smooth scroll to bottom during streaming
                lazyListState.animateScrollToItem(chatMessages.size - 1)
            }
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header with model info
        TopAppBar(
            title = { 
                Column {
                    Text("AI Chat")
                    if (isModelLoaded) {
                        Text(
                            text = if (supportsMultimodal) "Text + Image" else "Text Only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            actions = {
                if (chatMessages.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear chat")
                    }
                }
            }
        )
        
        // Model status
        if (isLoading) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = loadingProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        
        // Model capability info
        if (isModelLoaded && !isLoading) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (supportsMultimodal) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (supportsMultimodal) Icons.Default.Image else Icons.Default.Chat,
                        contentDescription = null,
                        tint = if (supportsMultimodal) 
                            MaterialTheme.colorScheme.onPrimaryContainer 
                        else 
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (supportsMultimodal) 
                            "Multimodal model loaded - supports text, images, and MediaPipe Vision analysis" 
                        else 
                            "Text-only model loaded - basic image analysis available via MediaPipe Vision",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (supportsMultimodal) 
                            MaterialTheme.colorScheme.onPrimaryContainer 
                        else 
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        
        // Error message
        errorMessage?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
        }
        
        // Chat messages
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (chatMessages.isEmpty() && !isLoading) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Start a Conversation",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (supportsMultimodal) 
                                    "Send a message or attach an image to begin chatting with the AI assistant. You can have multi-turn conversations with full multimodal support including MediaPipe Vision analysis."
                                else
                                    "Send a message or attach an image to begin chatting with the AI assistant. You can have multi-turn conversations. Images will be analyzed using MediaPipe Vision and combined with text responses.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            if (!isModelLoaded) {
                                Text(
                                    text = "Model not loaded. Please check settings to download the model.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            
            items(chatMessages) { message ->
                ChatMessageItem(message = message, isGenerating = isGenerating)
            }
        }
        
        // Message input with image support
        ChatInput(
            messageText = messageText,
            onMessageTextChange = { messageText = it },
            selectedImage = selectedImage,
            onSendMessage = { message, image ->
                viewModel.sendChatMessage(message, image)
                messageText = ""
                // Clear selected image after sending
                if (image != null) {
                    viewModel.clearSelectedImage()
                }
            },
            onImageSelect = { showImageBottomSheet = true },
            onClearImage = { viewModel.clearSelectedImage() },
            isModelLoaded = isModelLoaded,
            supportsMultimodal = supportsMultimodal,
            isProcessingImage = isProcessingImage
        )
    }
    
    // Bottom sheet for image selection
    if (showImageBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImageBottomSheet = false }
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Select Image Source",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                if (!supportsMultimodal) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text(
                            text = "⚠️ This model doesn't support image analysis. You can attach images, but the AI won't be able to see or analyze them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                
                ListItem(
                    headlineContent = { Text("Camera") },
                    leadingContent = { Icon(Icons.Default.Camera, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            cameraLauncher.launch(null)
                            showImageBottomSheet = false
                        },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                )
                
                ListItem(
                    headlineContent = { Text("Gallery") },
                    leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            galleryLauncher.launch("image/*")
                            showImageBottomSheet = false
                        },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun ChatInput(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    selectedImage: Bitmap?,
    onSendMessage: (String, Bitmap?) -> Unit,
    onImageSelect: () -> Unit,
    onClearImage: () -> Unit,
    isModelLoaded: Boolean,
    supportsMultimodal: Boolean,
    isProcessingImage: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Image preview above the input (when image is selected)
        selectedImage?.let { image ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                Card(
                    modifier = Modifier.size(80.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "Selected image",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        
                        // Clear image button (smaller)
                        IconButton(
                            onClick = onClearImage,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Remove image",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        // Warning overlay if multimodal not supported
                        if (!supportsMultimodal) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                                        RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                                    )
                                    .padding(2.dp)
                            ) {
                                Text(
                                    text = "⚠️",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }
                
                // Show processing indicator next to image when analyzing
                if (isProcessingImage) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.height(80.dp)
                    ) {
                        ImageProcessingIndicator()
                    }
                }
            }
        }
        
        // Message input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Image attachment button
            IconButton(
                onClick = onImageSelect,
                enabled = isModelLoaded
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = "Attach image",
                    tint = if (selectedImage != null) 
                        MaterialTheme.colorScheme.primary 
                    else if (supportsMultimodal) 
                        MaterialTheme.colorScheme.onSurfaceVariant 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            
            OutlinedTextField(
                value = messageText,
                onValueChange = onMessageTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { 
                    Text(
                        if (isModelLoaded) {
                            when {
                                selectedImage != null && supportsMultimodal -> "Ask a question about the image..."
                                selectedImage != null && !supportsMultimodal -> "Type a message (image won't be analyzed)..."
                                else -> "Type a message..."
                            }
                        } else "Load a model in Settings to chat"
                    )
                },
                enabled = isModelLoaded,
                maxLines = 4
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            IconButton(
                onClick = {
                    if (messageText.isNotBlank() || selectedImage != null) {
                        onSendMessage(messageText, selectedImage)
                    }
                },
                enabled = (messageText.isNotBlank() || selectedImage != null) && isModelLoaded,
                modifier = Modifier
                    .background(
                        if ((messageText.isNotBlank() || selectedImage != null) && isModelLoaded) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Send message",
                    tint = if ((messageText.isNotBlank() || selectedImage != null) && isModelLoaded) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage, isGenerating: Boolean = false) {
    val isEmptyAiMessage = !message.isUser && message.content.isBlank()
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!message.isUser) {
            // AI avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Image if present
                message.image?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Message image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Message text or typing indicator
                if (message.content.isNotBlank()) {
                    MarkdownText(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.isUser) 
                            MaterialTheme.colorScheme.onPrimary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (isEmptyAiMessage && isGenerating) {
                    // Show typing indicator for empty AI messages during generation
                    TypingIndicator()
                }
                
                // Timestamp
                Text(
                    text = dateFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isUser) 
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        
        if (message.isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            // User avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        MaterialTheme.colorScheme.secondary,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "U",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Analyzing",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        
        // Animated dots
        val infiniteTransition = rememberInfiniteTransition(label = "typing")
        
        repeat(3) { index ->
            val delay = index * 100
            val dotAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_alpha_$index"
            )
            
            Text(
                text = "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dotAlpha)
            )
        }
    }
}

@Composable
fun ImageProcessingIndicator() {
    Card(
        modifier = Modifier.padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rotating camera icon
            val infiniteTransition = rememberInfiniteTransition(label = "image_processing")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "icon_rotation"
            )
            
            Icon(
                Icons.Default.Camera,
                contentDescription = "Processing",
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            Text(
                text = "Analyzing image",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            // Pulsing dots
            repeat(3) { index ->
                val delay = index * 150
                val dotScale by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = delay),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dot_scale_$index"
                )
                
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .scale(dotScale)
                        .background(
                            MaterialTheme.colorScheme.onPrimaryContainer,
                            CircleShape
                        )
                )
            }
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    val annotatedString = remember(text) {
        parseMarkdown(text)
    }
    
    Text(
        text = annotatedString,
        modifier = modifier,
        style = style,
        color = color
    )
}

fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Handle **bold** text
                i < text.length - 1 && text[i] == '*' && text[i + 1] == '*' -> {
                    val start = i + 2
                    val endIndex = text.indexOf("**", start)
                    if (endIndex != -1) {
                        // Found closing **
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(start, endIndex))
                        }
                        i = endIndex + 2
                    } else {
                        // No closing **, treat as regular text
                        append(text[i])
                        i++
                    }
                }
                // Handle bullet points (• or -) - make them slightly bolder
                i == 0 && (text[i] == '•' || text[i] == '-') -> {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Medium)) {
                        append(text[i])
                    }
                    i++
                }
                text[i] == '\n' && i + 1 < text.length && (text[i + 1] == '•' || text[i + 1] == '-') -> {
                    append('\n')
                    i++
                    if (i < text.length) {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Medium)) {
                            append(text[i])
                        }
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
} 