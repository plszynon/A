package com.aicoder.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(val isUser: Boolean, val text: String)

sealed class AppState {
    object CheckingModel : AppState()
    data class Downloading(val progress: Int) : AppState()
    object LoadingModel : AppState()
    object Ready : AppState()
    data class Error(val message: String) : AppState()
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private var llmInference: LlmInference? = null

    private val _appState = MutableStateFlow<AppState>(AppState.CheckingModel)
    val appState: StateFlow<AppState> = _appState

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    init {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            if (!ModelDownloader.isDownloaded(context)) {
                _appState.value = AppState.Downloading(0)
                try {
                    ModelDownloader.download(context).collect { pct ->
                        _appState.value = AppState.Downloading(pct)
                    }
                } catch (e: Exception) {
                    _appState.value = AppState.Error("Download failed: ${e.message}")
                    return@launch
                }
            }
            loadModel()
        }
    }

    private suspend fun loadModel() {
        _appState.value = AppState.LoadingModel
        try {
            withContext(Dispatchers.IO) {
                val context = getApplication<Application>().applicationContext
                val path = ModelDownloader.modelFile(context).absolutePath
                val options = LlmInferenceOptions.builder()
                    .setModelPath(path)
                    .setMaxTokens(1024)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
            }
            _appState.value = AppState.Ready
        } catch (e: Exception) {
            _appState.value = AppState.Error("Model load failed: ${e.message}")
        }
    }

    fun send(userText: String) {
        val engine = llmInference ?: return
        if (userText.isBlank() || _isGenerating.value) return

        _messages.update { it + ChatMessage(isUser = true, text = userText) }
        _messages.update { it + ChatMessage(isUser = false, text = "") }
        _isGenerating.value = true

        viewModelScope.launch {
            try {
                val tokens = callbackFlow {
                    engine.generateResponseAsync(userText) { partial, done ->
                        trySend(partial)
                        if (done) close()
                    }
                    awaitClose { }
                }
                tokens.collect { token ->
                    _messages.update { list ->
                        val last = list.last()
                        list.dropLast(1) + last.copy(text = last.text + token)
                    }
                }
            } catch (e: Exception) {
                _messages.update { list ->
                    list.dropLast(1) + ChatMessage(isUser = false, text = "[error: ${e.message}]")
                }
            } finally {
                _isGenerating.value = false
            }
        }
    }

    override fun onCleared() {
        llmInference?.close()
        super.onCleared()
    }
}
