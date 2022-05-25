package ru.usedesk.chat_sdk.data.repository.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.JsonObject
import ru.usedesk.chat_sdk.data.repository._extra.retrofit.IHttpApi
import ru.usedesk.chat_sdk.data.repository.api.IApiRepository.EventListener
import ru.usedesk.chat_sdk.data.repository.api.entity.AdditionalFieldsRequest
import ru.usedesk.chat_sdk.data.repository.api.entity.FileResponse
import ru.usedesk.chat_sdk.data.repository.api.loader.InitChatResponseConverter
import ru.usedesk.chat_sdk.data.repository.api.loader.MessageResponseConverter
import ru.usedesk.chat_sdk.data.repository.api.loader.multipart.IMultipartConverter
import ru.usedesk.chat_sdk.data.repository.api.loader.socket.SocketApi
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.feedback.FeedbackRequest
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.initchat.InitChatRequest
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.initchat.InitChatResponse
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.message.MessageRequest
import ru.usedesk.chat_sdk.data.repository.api.loader.socket._entity.message.MessageResponse
import ru.usedesk.chat_sdk.entity.*
import ru.usedesk.common_sdk.api.IUsedeskApiFactory
import ru.usedesk.common_sdk.api.UsedeskApiRepository
import ru.usedesk.common_sdk.entity.exceptions.UsedeskHttpException
import ru.usedesk.common_sdk.entity.exceptions.UsedeskSocketException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*

internal class ApiRepository(
    private val socketApi: SocketApi,
    private val multipartConverter: IMultipartConverter,
    private val initChatResponseConverter: InitChatResponseConverter,
    private val messageResponseConverter: MessageResponseConverter,
    apiFactory: IUsedeskApiFactory,
    gson: Gson
) : UsedeskApiRepository<IHttpApi>(apiFactory, gson, IHttpApi::class.java),
    IApiRepository {

    private fun isConnected() = socketApi.isConnected()

    private lateinit var eventListener: EventListener

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
            chatInited.offlineFormSettings.run {
                if ((workType == UsedeskOfflineFormSettings.WorkType.CHECK_WORKING_TIMES
                            && noOperators)
                    || (workType == UsedeskOfflineFormSettings.WorkType.ALWAYS_ENABLED_CALLBACK_WITH_CHAT
                            && initChatResponse.setup?.ticket?.statusId in STATUSES_FOR_FORM)
                    || workType == UsedeskOfflineFormSettings.WorkType.ALWAYS_ENABLED_CALLBACK_WITHOUT_CHAT
                ) {
                    eventListener.onOfflineForm(this, chatInited)
                } else {
                    eventListener.onChatInited(chatInited)
                }
            }
        }

        override fun onNew(messageResponse: MessageResponse) {
            val messages = messageResponseConverter.convert(messageResponse.message)
            messages.forEach {
                if (it is UsedeskMessageClient && it.id != it.localId) {
                    eventListener.onMessageUpdated(it)
                } else {
                    eventListener.onMessagesReceived(listOf(it))
                }
            }
        }

        override fun onSetEmailSuccess() {
            eventListener.onSetEmailSuccess()
        }
    }

    override fun connect(
        url: String,
        token: String?,
        configuration: UsedeskChatConfiguration,
        eventListener: EventListener
    ) {
        this.eventListener = eventListener
        socketApi.connect(url, token, configuration.getCompanyAndChannel(), socketEventListener)
    }

    override fun init(
        configuration: UsedeskChatConfiguration,
        token: String?
    ) {
        socketApi.sendRequest(
            InitChatRequest(
                token,
                configuration.getCompanyAndChannel(),
                configuration.urlChat
            )
        )
    }

    override fun send(
        messageId: Long,
        feedback: UsedeskFeedback
    ) {
        checkConnection()
        socketApi.sendRequest(FeedbackRequest(messageId, feedback))
    }

    override fun send(messageText: UsedeskMessageText) {
        checkConnection()
        val request = MessageRequest(messageText.text, messageText.id)
        socketApi.sendRequest(request)
    }

    override fun send(
        configuration: UsedeskChatConfiguration,
        token: String,
        fileInfo: UsedeskFileInfo,
        messageId: Long
    ) {
        checkConnection()

        val parts = mapOf(
            "chat_token" to token,
            "file" to fileInfo.uri,
            "message_id" to messageId
        ).mapNotNull(multipartConverter::convert)

        doRequest(configuration.urlOfflineForm, FileResponse::class.java) {
            it.postFile(parts)
        }
    }

    override fun setClient(
        configuration: UsedeskChatConfiguration
    ) {
        checkConnection()

        try {
            val avatarStream = try {
                val outputStream = ByteArrayOutputStream()
                BitmapFactory.decodeFile(configuration.avatar)
                    .compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            val parts = mapOf(
                "email" to configuration.clientEmail?.getCorrectStringValue(),
                "username" to configuration.clientName?.getCorrectStringValue(),
                "token" to configuration.clientToken,
                "note" to configuration.clientNote,
                "phone" to configuration.clientPhoneNumber,
                "additional_id" to configuration.clientAdditionalId,
                "company_id" to configuration.companyId,
                "avatar" to avatarStream
            ).mapNotNull(multipartConverter::convert)

            doRequest(configuration.urlOfflineForm, Array<Any>::class.java) {
                it.setClient(parts)
            }
        } catch (e: IOException) {
            throw UsedeskHttpException(UsedeskHttpException.Error.IO_ERROR, e.message)
        }
    }

    override fun send(
        configuration: UsedeskChatConfiguration,
        companyId: String,
        offlineForm: UsedeskOfflineForm
    ) {
        try {
            doRequest(configuration.urlOfflineForm, Array<Any>::class.java) {
                val params = mapOf(
                    "email" to offlineForm.clientEmail.getCorrectStringValue(),
                    "name" to offlineForm.clientName.getCorrectStringValue(),
                    "company_id" to companyId.getCorrectStringValue(),
                    "message" to offlineForm.message.getCorrectStringValue(),
                    "topic" to offlineForm.topic.getCorrectStringValue()
                )
                val customFields = offlineForm.fields.filter { field ->
                    field.value.isNotEmpty()
                }.map { field ->
                    field.key to field.value.getCorrectStringValue()
                }
                val json = JsonObject()
                (params + customFields).forEach { param ->
                    json.addProperty(param.key, param.value)
                }
                it.sendOfflineForm(json)
            }
        } catch (e: IOException) {
            throw UsedeskHttpException(UsedeskHttpException.Error.IO_ERROR, e.message)
        }
    }

    override fun send(
        token: String?,
        configuration: UsedeskChatConfiguration,
        additionalFields: Map<Long, String>,
        additionalNestedFields: List<Map<Long, String>>
    ) {
        val response = doRequest(configuration.urlOfflineForm, String::class.java) {
            if (token != null) {
                val totalFields =
                    (additionalFields.toList() + additionalNestedFields.flatMap { fields ->
                        fields.toList()
                    }).map { field ->
                        AdditionalFieldsRequest.AdditionalField(field.first, field.second)
                    }
                val request = AdditionalFieldsRequest(token, totalFields)
                it.postAdditionalFields(request)
            } else {
                throw UsedeskHttpException("Token is null")
            }
        }
    }

    private fun String.getCorrectStringValue() = this.replace("\"", "\\\"")

    override fun disconnect() {
        socketApi.disconnect()
    }

    private fun checkConnection() {
        if (!isConnected()) {
            throw UsedeskSocketException(UsedeskSocketException.Error.DISCONNECTED)
        }
    }

    companion object {
        private val STATUSES_FOR_FORM = listOf(null, 2, 3, 4, 7, 9, 10)
    }
}