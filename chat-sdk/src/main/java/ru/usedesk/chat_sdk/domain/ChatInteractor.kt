package ru.usedesk.chat_sdk.domain

import io.reactivex.Completable
import io.reactivex.CompletableEmitter
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import ru.usedesk.chat_sdk.data.repository.api.IApiRepository
import ru.usedesk.chat_sdk.data.repository.api.entity.ChatInited
import ru.usedesk.chat_sdk.data.repository.configuration.IUserInfoRepository
import ru.usedesk.chat_sdk.entity.*
import ru.usedesk.common_sdk.entity.UsedeskEvent
import ru.usedesk.common_sdk.entity.UsedeskSingleLifeEvent
import ru.usedesk.common_sdk.entity.exceptions.UsedeskException
import toothpick.InjectConstructor
import java.util.*
import java.util.concurrent.TimeUnit

@InjectConstructor
internal class ChatInteractor(
        private val configuration: UsedeskChatConfiguration,
        private val userInfoRepository: IUserInfoRepository,
        private val apiRepository: IApiRepository
) : IUsedeskChat {

    private var token: String? = null
    private var signature: String? = null
    private var initClientMessage: String? = null

    private var actionListeners = mutableSetOf<IUsedeskActionListener>()
    private var actionListenersRx = mutableSetOf<IUsedeskActionListenerRx>()

    private val connectedStateSubject = BehaviorSubject.createDefault(false)
    private val messagesSubject = BehaviorSubject.create<List<UsedeskMessage>>()
    private val messageSubject = BehaviorSubject.create<UsedeskMessage>()
    private val newMessageSubject = PublishSubject.create<UsedeskMessage>()
    private val messageUpdateSubject = PublishSubject.create<UsedeskMessage>()
    private val offlineFormExpectedSubject = BehaviorSubject.create<UsedeskChatConfiguration>()
    private val feedbackSubject = BehaviorSubject.create<UsedeskEvent<Any?>>()
    private val exceptionSubject = BehaviorSubject.create<Exception>()

    private var reconnectDisposable: Disposable? = null
    private val listenersDisposables = CompositeDisposable()

    private var lastMessages = listOf<UsedeskMessage>()

    private var localId = -1000L

    init {
        listenersDisposables.apply {
            connectedStateSubject.subscribe {
                actionListeners.forEach { listener ->
                    listener.onConnectedState(it)
                }
            }

            messagesSubject.subscribe {
                actionListeners.forEach { listener ->
                    listener.onMessagesReceived(it)
                }
            }

            messageSubject.subscribe {
                actionListeners.forEach { listener ->
                    listener.onMessageReceived(it)
                }
            }

            newMessageSubject.subscribe {
                actionListeners.forEach { listener ->
                    listener.onNewMessageReceived(it)
                }
            }

            messageUpdateSubject.subscribe {
                actionListeners.forEach { listener ->
                    listener.onMessageUpdated(it)
                }
            }

            offlineFormExpectedSubject.subscribe {
                actionListeners.forEach { listener ->
                    listener.onOfflineFormExpected(it)
                }
            }

            feedbackSubject.subscribe {
                actionListeners.forEach { listener ->
                    listener.onFeedbackReceived()
                }
            }

            exceptionSubject.subscribe {
                actionListeners.forEach { listener ->
                    listener.onException(it)
                }
            }
        }
    }

    private val eventListener = object : IApiRepository.EventListener {
        override fun onConnected() {
            connectedStateSubject.onNext(true)
        }

        override fun onDisconnected() {
            if (reconnectDisposable?.isDisposed != false) {
                reconnectDisposable = Completable.timer(5, TimeUnit.SECONDS).subscribe {
                    try {
                        connect()
                    } catch (e: Exception) {
                        //nothing
                    }
                }
            }

            connectedStateSubject.onNext(false)
        }

        override fun onTokenError() {
            try {
                apiRepository.init(configuration, token)
            } catch (e: UsedeskException) {
                onException(e)
            }
        }

        override fun onFeedback() {
            feedbackSubject.onNext(UsedeskSingleLifeEvent(null))
        }

        override fun onException(exception: Exception) {
            exceptionSubject.onNext(exception)
        }

        override fun onChatInited(chatInited: ChatInited) {
            this@ChatInteractor.onChatInited(chatInited)
        }

        override fun onMessagesReceived(newMessages: List<UsedeskMessage>) {
            this@ChatInteractor.onMessagesNew(newMessages, false)
        }

        override fun onMessageUpdated(message: UsedeskMessage) {
            this@ChatInteractor.onMessageUpdate(message)
        }

        override fun onOfflineForm() {
            offlineFormExpectedSubject.onNext(configuration)
        }

        override fun onSetEmailSuccess() {
            initClientMessage?.also {
                if (it.isNotEmpty()) {
                    try {
                        send(it)
                        initClientMessage = ""
                    } catch (e: Exception) {
                        //nothing
                    }
                }
            }
        }
    }

    override fun connect() {
        reconnectDisposable?.dispose()
        reconnectDisposable = null
        val configuration = userInfoRepository.getConfigurationNullable()
        if (!isStringEmpty(this.configuration.clientSignature)) {
            if (this.configuration.clientSignature == configuration?.clientSignature) {
                token = this.configuration.clientSignature
            } else {
                token = this.configuration.clientSignature
                signature = this.configuration.clientSignature
            }
        } else if (configuration != null
                && this.configuration.companyId == configuration.companyId
                && (isFieldEquals(this.configuration.clientEmail, configuration.clientEmail)
                        || isFieldEquals(this.configuration.clientPhoneNumber, configuration.clientPhoneNumber)
                        || isFieldEquals(this.configuration.clientAdditionalId, configuration.clientAdditionalId)
                        || (this.configuration.clientEmail == configuration.clientEmail
                        && this.configuration.clientPhoneNumber == configuration.clientPhoneNumber
                        && this.configuration.clientAdditionalId == configuration.clientAdditionalId))) {
            token = userInfoRepository.getToken()
        }
        apiRepository.connect(
                this.configuration.urlChat,
                token,
                this.configuration,
                eventListener
        )
    }

    private fun isStringEmpty(text: String?): Boolean {
        return text?.isEmpty() != false
    }

    private fun isFieldEquals(field1: String?, field2: String?): Boolean {
        return if (isStringEmpty(field1)) {
            isStringEmpty(field2)
        } else {
            field1 == field2
        }
    }

    private fun isFieldEquals(field1: Long?, field2: Long?): Boolean {
        return field1 != null && field1 == field2
    }

    private fun onMessageUpdate(message: UsedeskMessage) {
        lastMessages = lastMessages.map {
            if (it.id == message.id) {
                message
            } else {
                it
            }
        }
        messagesSubject.onNext(lastMessages)
        messageUpdateSubject.onNext(message)
    }

    private val sendingMessagesTimeout = CompositeDisposable()

    private fun onMessagesNew(messages: List<UsedeskMessage>,
                              isInited: Boolean) {
        lastMessages = lastMessages + messages
        messages.forEach { message ->
            messageSubject.onNext(message)
            if (!isInited) {
                newMessageSubject.onNext(message)
            }

            if (message.id < 0) {
                runTimeout(message)
            }
            messagesSubject.onNext(lastMessages)
        }
    }

    private fun runTimeout(message: UsedeskMessage) {
        sendingMessagesTimeout.add(
                Completable.timer(SENDING_TIMEOUT_SECONDS, TimeUnit.SECONDS).subscribe {
                    val failedMessage = lastMessages.firstOrNull {
                        it.id == message.id
                    }
                    if (failedMessage is UsedeskMessageClient &&
                            failedMessage.status != UsedeskMessageClient.Status.SUCCESSFULLY_SENT) {
                        when (failedMessage) {
                            is UsedeskMessageClientText -> {
                                UsedeskMessageClientText(
                                        failedMessage.id,
                                        failedMessage.createdAt,
                                        failedMessage.text,
                                        UsedeskMessageClient.Status.SEND_FAILED
                                )
                            }
                            is UsedeskMessageClientFile -> {
                                UsedeskMessageClientFile(
                                        failedMessage.id,
                                        failedMessage.createdAt,
                                        failedMessage.file,
                                        UsedeskMessageClient.Status.SEND_FAILED
                                )
                            }
                            is UsedeskMessageClientImage -> {
                                UsedeskMessageClientImage(
                                        failedMessage.id,
                                        failedMessage.createdAt,
                                        failedMessage.file,
                                        UsedeskMessageClient.Status.SEND_FAILED
                                )
                            }
                            else -> null
                        }?.run {
                            messageUpdateSubject.onNext(this)
                        }
                    }
                }
        )
    }

    override fun disconnect() {
        apiRepository.disconnect()
    }

    override fun addActionListener(listener: IUsedeskActionListener) {
        actionListeners.add(listener)
    }

    override fun removeActionListener(listener: IUsedeskActionListener) {
        actionListeners.remove(listener)
    }

    override fun addActionListener(listener: IUsedeskActionListenerRx) {
        actionListenersRx.add(listener)
        listener.onObservables(
                connectedStateSubject,
                messageSubject,
                newMessageSubject,
                messagesSubject,
                messageUpdateSubject,
                offlineFormExpectedSubject,
                feedbackSubject,
                exceptionSubject
        )
    }

    override fun removeActionListener(listener: IUsedeskActionListenerRx) {
        actionListenersRx.remove(listener)
        listener.onDispose()
    }

    override fun isNoListeners(): Boolean {
        return actionListeners.isEmpty() && actionListenersRx.isEmpty()
    }

    override fun send(textMessage: String) {
        if (textMessage.isNotEmpty()) {
            token?.also {
                val sendingMessage = createSendingMessage(textMessage)
                eventListener.onMessagesReceived(listOf(sendingMessage))

                apiRepository.send(it, sendingMessage)
            }
        }
    }

    override fun send(usedeskFileInfoList: List<UsedeskFileInfo>) {
        token?.also { token ->
            usedeskFileInfoList.forEach { usedeskFileInfo ->
                val sendingMessage = createSendingMessage(usedeskFileInfo)
                eventListener.onMessagesReceived(listOf(sendingMessage))

                apiRepository.send(configuration, token, sendingMessage)
            }
        }
    }

    override fun send(agentMessage: UsedeskMessageAgentText, feedback: UsedeskFeedback) {
        token?.also {
            apiRepository.send(it, agentMessage.id, feedback)

            onMessageUpdate(UsedeskMessageAgentText(
                    agentMessage.id,
                    agentMessage.createdAt,
                    agentMessage.text,
                    agentMessage.buttons,
                    false,
                    feedback,
                    agentMessage.name,
                    agentMessage.avatar
            ))
        }
    }

    override fun send(offlineForm: UsedeskOfflineForm) {
        apiRepository.send(configuration, configuration.companyId, offlineForm)
    }

    override fun sendAgain(id: Long) {
        token?.let { token ->
            val message = lastMessages.firstOrNull {
                it.id == id
            }
            if (message is UsedeskMessageClient
                    && message.status == UsedeskMessageClient.Status.SEND_FAILED) {
                when (message) {
                    is UsedeskMessageClientText -> {
                        val sendingMessage = UsedeskMessageClientText(
                                message.id,
                                message.createdAt,
                                message.text,
                                UsedeskMessageClient.Status.SENDING
                        )
                        onMessageUpdate(sendingMessage)
                        runTimeout(sendingMessage)
                        apiRepository.send(token, sendingMessage)
                    }
                    is UsedeskMessageClientImage -> {
                        val sendingMessage = UsedeskMessageClientImage(
                                message.id,
                                message.createdAt,
                                message.file,
                                UsedeskMessageClient.Status.SENDING
                        )
                        onMessageUpdate(sendingMessage)
                        runTimeout(sendingMessage)
                        apiRepository.send(configuration, token, sendingMessage)
                    }
                    is UsedeskMessageClientFile -> {
                        val sendingMessage = UsedeskMessageClientFile(
                                message.id,
                                message.createdAt,
                                message.file,
                                UsedeskMessageClient.Status.SENDING
                        )
                        onMessageUpdate(sendingMessage)
                        runTimeout(sendingMessage)
                        apiRepository.send(configuration, token, sendingMessage)
                    }
                }
            }
        }
    }

    private fun createSendingMessage(text: String): UsedeskMessageText {
        localId--
        val calendar = Calendar.getInstance()
        return UsedeskMessageClientText(localId, calendar, text, UsedeskMessageClient.Status.SENDING)
    }

    private fun createSendingMessage(fileInfo: UsedeskFileInfo): UsedeskMessageFile {
        localId--
        val calendar = Calendar.getInstance()
        val file = UsedeskFile.create(
                fileInfo.uri.toString(),
                fileInfo.type,
                "",
                fileInfo.name
        )
        return if (fileInfo.isImage()) {
            UsedeskMessageClientImage(localId, calendar, file, UsedeskMessageClient.Status.SENDING)
        } else {
            UsedeskMessageClientFile(localId, calendar, file, UsedeskMessageClient.Status.SENDING)
        }
    }

    override fun connectRx(): Completable {
        return Completable.create { emitter: CompletableEmitter ->
            connect()
            emitter.onComplete()
        }
    }

    override fun sendRx(textMessage: String): Completable {
        return Completable.create { emitter: CompletableEmitter ->
            send(textMessage)
            emitter.onComplete()
        }
    }

    override fun sendRx(usedeskFileInfoList: List<UsedeskFileInfo>): Completable {
        return Completable.create { emitter: CompletableEmitter ->
            send(usedeskFileInfoList)
            emitter.onComplete()
        }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
    }

    override fun sendRx(agentMessage: UsedeskMessageAgentText, feedback: UsedeskFeedback): Completable {
        return Completable.create { emitter: CompletableEmitter ->
            send(agentMessage, feedback)
            emitter.onComplete()
        }
    }

    override fun sendRx(offlineForm: UsedeskOfflineForm): Completable {
        return Completable.create { emitter: CompletableEmitter ->
            send(offlineForm)
            emitter.onComplete()
        }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
    }

    override fun sendAgainRx(id: Long): Completable {
        return Completable.create { emitter: CompletableEmitter ->
            sendAgain(id)
            emitter.onComplete()
        }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
    }

    override fun disconnectRx(): Completable {
        return Completable.create { emitter: CompletableEmitter ->
            disconnect()
            emitter.onComplete()
        }
    }

    private fun sendUserEmail() {
        try {
            apiRepository.send(token,
                    signature,
                    configuration.clientEmail,
                    configuration.clientName,
                    configuration.clientNote,
                    configuration.clientPhoneNumber,
                    configuration.clientAdditionalId)
        } catch (e: UsedeskException) {
            exceptionSubject.onNext(e)
        }
    }

    private fun onChatInited(chatInited: ChatInited) {
        this.token = chatInited.token

        userInfoRepository.setToken(token)

        val ids = lastMessages.map {
            it.id
        }
        val filteredMessages = chatInited.messages.filter {
            it.id !in ids
        }
        onMessagesNew(filteredMessages, true)

        val initClientMessage = try {
            userInfoRepository.getConfiguration().clientInitMessage
        } catch (ignore: UsedeskException) {
            null
        }
        if (initClientMessage?.equals(configuration.clientInitMessage) == false) {
            send(initClientMessage)
        }
        userInfoRepository.setConfiguration(configuration)

        if (chatInited.waitingEmail) {
            sendUserEmail()
        } else {
            eventListener.onSetEmailSuccess()
        }
    }

    companion object {
        private const val SENDING_TIMEOUT_SECONDS = 60L
    }
}