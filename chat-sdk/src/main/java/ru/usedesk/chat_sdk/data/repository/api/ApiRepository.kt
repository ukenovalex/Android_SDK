package ru.usedesk.chat_sdk.data.repository.api

import ru.usedesk.chat_sdk.data._entity.UsedeskFile
import ru.usedesk.chat_sdk.data.repository.api.loader.FileResponseConverter
import ru.usedesk.chat_sdk.data.repository.api.loader.InitChatResponseConverter
import ru.usedesk.chat_sdk.data.repository.api.loader.MessageResponseConverter
import ru.usedesk.chat_sdk.data.repository.api.loader.apifile.IFileApi
import ru.usedesk.chat_sdk.data.repository.api.loader.apiofflineform.IOfflineFormApi
import ru.usedesk.chat_sdk.data.repository.api.loader.apiofflineform.entity.OfflineFormRequest
import ru.usedesk.chat_sdk.data.repository.api.loader.file.IFileLoader
import ru.usedesk.chat_sdk.data.repository.api.loader.multipart.IMultipartConverter
import ru.usedesk.chat_sdk.data.repository.api.loader.socket.SocketApi
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.feedback.FeedbackRequest
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.initchat.InitChatRequest
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.initchat.InitChatResponse
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.message.MessageRequest
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.message.MessageResponse
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.setemail.SetEmailRequest
import ru.usedesk.chat_sdk.entity.*
import ru.usedesk.common_sdk.entity.exceptions.UsedeskException
import ru.usedesk.common_sdk.entity.exceptions.UsedeskHttpException
import ru.usedesk.common_sdk.entity.exceptions.UsedeskSocketException
import toothpick.InjectConstructor
import java.io.IOException
import java.net.URL
import java.util.*

@InjectConstructor
internal class ApiRepository(
        private val socketApi: SocketApi,
        private val offlineFormApi: IOfflineFormApi,
        private val fileApi: IFileApi,
        private val multipartConverter: IMultipartConverter,
        private val initChatResponseConverter: InitChatResponseConverter,
        private val messageResponseConverter: MessageResponseConverter,
        private val fileResponseConverter: FileResponseConverter,
        private val fileLoader: IFileLoader
) : IApiRepository {

    private var localId = 0L

    private fun isConnected() = socketApi.isConnected()

    private lateinit var eventListener: IApiRepository.EventListener

    private val socketEventListener = object : SocketApi.EventListener {
        override fun onConnected() {
            eventListener.onConnected()
        }

        override fun onDisconnected() {
            eventListener.onDisconnected()
        }

        override fun onTokenError() {
            eventListener.onTokenError()
        }

        override fun onFeedback() {
            eventListener.onFeedback()
        }

        override fun onException(exception: Exception) {
            eventListener.onException(exception)
        }

        override fun onInited(initChatResponse: InitChatResponse) {
            val chatInited = initChatResponseConverter.convert(initChatResponse)
            if (chatInited.noOperators) {
                eventListener.onOfflineForm()
            } else {
                eventListener.onChatInited(chatInited)
            }
        }

        override fun onNew(messageResponse: MessageResponse) {
            if (messageResponse.message?.payload?.noOperators == true) {
                eventListener.onOfflineForm()
            } else {
                val messages = messageResponseConverter.convert(messageResponse.message)
                eventListener.onMessagesReceived(messages)
            }
        }
    }

    @Throws(UsedeskException::class)
    override fun connect(url: String,
                         token: String?,
                         configuration: UsedeskChatConfiguration,
                         eventListener: IApiRepository.EventListener) {
        this.eventListener = eventListener
        socketApi.connect(url, token, configuration.companyId, socketEventListener)
    }

    @Throws(UsedeskException::class)
    override fun init(configuration: UsedeskChatConfiguration, token: String?) {
        socketApi.sendRequest(InitChatRequest(token, configuration.companyId,
                configuration.url))
    }

    @Throws(UsedeskException::class)
    override fun send(token: String, feedback: UsedeskFeedback) {
        checkConnection()
        socketApi.sendRequest(FeedbackRequest(token, feedback))
    }

    @Throws(UsedeskException::class)
    override fun send(token: String, text: String) {
        checkConnection()
        socketApi.sendRequest(MessageRequest(token, text))
    }

    @Throws(UsedeskException::class)
    override fun send(configuration: UsedeskChatConfiguration,
                      token: String,
                      usedeskFileInfo: UsedeskFileInfo) {
        checkConnection()
        localId--
        val calendar = Calendar.getInstance()
        val file = UsedeskFile(
                usedeskFileInfo.uri.toString(),
                usedeskFileInfo.type,
                "",
                usedeskFileInfo.name
        )
        val tempMessage = if (usedeskFileInfo.isImage()) {
            UsedeskMessageClientImage(localId, calendar, file)
        } else {
            UsedeskMessageClientFile(localId, calendar, file)
        }
        eventListener.onMessagesReceived(listOf(tempMessage))

        val url = URL(configuration.offlineFormUrl)
        val postUrl = String.format(HTTP_API_PATH, url.host)
        val loadedFile = fileLoader.load(usedeskFileInfo.uri)
        val parts = listOf(
                multipartConverter.convert("chat_token", token),
                multipartConverter.convert("file", loadedFile)
        )
        val fileResponse = fileApi.post(postUrl, parts).apply {
            id = localId.toString()
            type = loadedFile.type
            name = loadedFile.name
        }
        val message = fileResponseConverter.convert(fileResponse)
        eventListener.onMessageUpdated(message)
    }

    @Throws(UsedeskException::class)
    override fun send(token: String, email: String, name: String?, phone: Long?, additionalId: Long?) {
        socketApi.sendRequest(SetEmailRequest(token, email, name, phone, additionalId))
    }

    @Throws(UsedeskException::class)
    override fun send(configuration: UsedeskChatConfiguration,
                      companyId: String,
                      offlineForm: UsedeskOfflineForm) {
        try {
            val url = URL(configuration.offlineFormUrl)
            val postUrl = String.format(OFFLINE_FORM_PATH, url.host)
            offlineFormApi.post(postUrl, OfflineFormRequest(companyId, offlineForm))
        } catch (e: IOException) {
            throw UsedeskHttpException(UsedeskHttpException.Error.IO_ERROR, e.message)
        }
    }

    override fun disconnect() {
        socketApi.disconnect()
    }

    private fun checkConnection() {
        if (!isConnected()) {
            throw UsedeskSocketException(UsedeskSocketException.Error.DISCONNECTED)
        }
    }

    companion object {
        private const val OFFLINE_FORM_PATH = "https://%1s/widget.js/"
        private const val HTTP_API_PATH = "https://%1s/uapi/v1/"
    }
}