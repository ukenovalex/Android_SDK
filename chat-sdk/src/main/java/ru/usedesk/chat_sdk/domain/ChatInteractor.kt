package ru.usedesk.chat_sdk.domain

import android.net.Uri
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.usedesk.chat_sdk.data.repository.api.IApiRepository
import ru.usedesk.chat_sdk.data.repository.configuration.IUserInfoRepository
import ru.usedesk.chat_sdk.entity.*
import ru.usedesk.chat_sdk.entity.UsedeskOfflineFormSettings.WorkType
import ru.usedesk.common_sdk.entity.UsedeskEvent
import ru.usedesk.common_sdk.entity.UsedeskSingleLifeEvent
import ru.usedesk.common_sdk.entity.exceptions.UsedeskException
import ru.usedesk.common_sdk.utils.UsedeskRxUtil.safeCompletableIo
import ru.usedesk.common_sdk.utils.UsedeskRxUtil.safeSingleIo
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal class ChatInteractor @Inject constructor(
    private val configuration: UsedeskChatConfiguration,
    private val userInfoRepository: IUserInfoRepository,
    private val apiRepository: IApiRepository,
    private val cachedMessages: ICachedMessagesInteractor
) : IUsedeskChat {

    private val mainThread = AndroidSchedulers.mainThread()
    private val ioScheduler = Schedulers.io()
    private var token: String? = null
    private var initClientMessage: String? = configuration.clientInitMessage
    private var initClientOfflineForm: String? = null

    private var actionListeners = mutableSetOf<IUsedeskActionListener>()
    private var actionListenersRx = mutableSetOf<IUsedeskActionListenerRx>()

    private val connectionStateSubject =
        BehaviorSubject.createDefault(UsedeskConnectionState.CONNECTING)
    private val clientTokenSubject = BehaviorSubject.create<String>()
    private val messagesSubject = BehaviorSubject.create<List<UsedeskMessage>>()
    private val messageSubject = BehaviorSubject.create<UsedeskMessage>()
    private val newMessageSubject = PublishSubject.create<UsedeskMessage>()
    private val messageUpdateSubject = PublishSubject.create<UsedeskMessage>()
    private val messageRemovedSubject = PublishSubject.create<UsedeskMessage>()
    private val offlineFormExpectedSubject = BehaviorSubject.create<UsedeskOfflineFormSettings>()
    private val feedbackSubject = BehaviorSubject.create<UsedeskEvent<Any?>>()
    private val exceptionSubject = BehaviorSubject.create<Exception>()

    private var reconnectDisposable: Disposable? = null
    private val listenersDisposables = mutableListOf<Disposable>()
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val jobsScope = CoroutineScope(Dispatchers.IO)
    private val jobsMutex = Mutex()
    private val firstMessageMutex = Mutex()
    private var firstMessageLock: Mutex? = null

    private var lastMessages = listOf<UsedeskMessage>()

    private var chatInited: ChatInited? = null
    private var offlineFormToChat = false

    private var initedNotSentMessages = listOf<UsedeskMessage>()

    private var additionalFieldsNeeded: Boolean = true
    private val listenersMutex = Mutex()

    private val oldMutex = Mutex()
    private var oldMessagesLoadDeferred: Deferred<Boolean>? = null

    init {
        listenersDisposables.apply {
            add(connectionStateSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }.forEach { listener ->
                    listener.onConnectionState(it)
                }
            })

            add(clientTokenSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }.forEach { listener ->
                    listener.onClientTokenReceived(it)
                }
            })

            add(messagesSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }.forEach { listener ->
                    listener.onMessagesReceived(it)
                }
            })

            add(messageSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }.forEach { listener ->
                    listener.onMessageReceived(it)
                }
            })

            add(newMessageSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }.forEach { listener ->
                    listener.onNewMessageReceived(it)
                }
            })

            add(messageUpdateSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }.forEach { listener ->
                    listener.onMessageUpdated(it)
                }
            })

            add(messageRemovedSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }
                    .forEach(IUsedeskActionListener::onMessageRemoved)
            })

            add(offlineFormExpectedSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }
                    .forEach { listener -> listener.onOfflineFormExpected(it) }
            })

            add(feedbackSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }
                    .forEach(IUsedeskActionListener::onFeedbackReceived)
            })

            add(exceptionSubject.subscribe {
                runBlocking { listenersMutex.withLock { actionListeners } }
                    .forEach { listener -> listener.onException(it) }
            })
        }
    }

    private val eventListener = object : IApiRepository.EventListener {
        override fun onConnected() {
            connectionStateSubject.onNext(UsedeskConnectionState.CONNECTED)
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

            connectionStateSubject.onNext(UsedeskConnectionState.DISCONNECTED)
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

        @Synchronized
        override fun onChatInited(chatInited: ChatInited) {
            this@ChatInteractor.chatInited = chatInited
            this@ChatInteractor.onChatInited(chatInited)
        }

        @Synchronized
        override fun onMessagesOldReceived(oldMessages: List<UsedeskMessage>) {
            this@ChatInteractor.onMessagesNew(
                old = oldMessages,
                isInited = false
            )
        }

        @Synchronized
        override fun onMessagesNewReceived(newMessages: List<UsedeskMessage>) {
            this@ChatInteractor.onMessagesNew(
                new = newMessages,
                isInited = false
            )
        }

        @Synchronized
        override fun onMessageUpdated(message: UsedeskMessage) {
            this@ChatInteractor.onMessageUpdate(message)
        }

        override fun onOfflineForm(
            offlineFormSettings: UsedeskOfflineFormSettings,
            chatInited: ChatInited
        ) {
            this@ChatInteractor.chatInited = chatInited
            this@ChatInteractor.offlineFormToChat =
                offlineFormSettings.workType == WorkType.ALWAYS_ENABLED_CALLBACK_WITH_CHAT
            offlineFormExpectedSubject.onNext(offlineFormSettings)
        }

        override fun onSetEmailSuccess() {
            sendInitMessage()
        }
    }

    private fun sendInitMessage() {
        val initMessage = initClientOfflineForm ?: initClientMessage
        initMessage?.let {
            try {
                send(it)
                initClientMessage = null
                initClientOfflineForm = null
            } catch (e: Exception) {
                //nothing
            }
        }
    }

    override fun createChat(apiToken: String) = apiRepository.initChat(
        configuration,
        apiToken
    ).also { clientToken ->
        userInfoRepository.setConfiguration(configuration.copy(clientToken = clientToken))
    }

    override fun connect() {
        val curState = connectionStateSubject.value
        if (curState != UsedeskConnectionState.CONNECTED) {
            if (curState != UsedeskConnectionState.CONNECTING) {
                connectionStateSubject.onNext(UsedeskConnectionState.RECONNECTING)
            }
            reconnectDisposable?.dispose()
            reconnectDisposable = null
            token = (configuration.clientToken
                ?: userInfoRepository.getConfiguration(configuration)?.clientToken)
                ?.ifEmpty { null }

            unlockFirstMessage()
            resetFirstMessageLock()

            apiRepository.connect(
                configuration.urlChat,
                token,
                configuration,
                eventListener
            )
        }
    }

    private fun onMessageUpdate(message: UsedeskMessage) {
        runBlocking {
            jobsMutex.withLock {
                lastMessages = lastMessages.map {
                    when {
                        it is UsedeskMessageClient &&
                                message is UsedeskMessageClient &&
                                it.localId == message.localId || it is UsedeskMessageAgent &&
                                message is UsedeskMessageAgent &&
                                it.id == message.id -> message
                        else -> it
                    }
                }
                messagesSubject.onNext(lastMessages)
                messageUpdateSubject.onNext(message)
            }
        }
    }

    private fun onMessageRemove(message: UsedeskMessage) {
        lastMessages = lastMessages.filter { it.id != message.id }
        messagesSubject.onNext(lastMessages)
        messageRemovedSubject.onNext(message)
    }

    private fun onMessagesNew(
        old: List<UsedeskMessage> = listOf(),
        new: List<UsedeskMessage> = listOf(),
        isInited: Boolean
    ) {
        lastMessages = old + lastMessages + new
        (old.asSequence() + new.asSequence()).forEach { message ->
            messageSubject.onNext(message)
            if (!isInited) {
                newMessageSubject.onNext(message)
            }
        }
        messagesSubject.onNext(lastMessages)
    }

    override fun disconnect() {
        apiRepository.disconnect()
    }

    override fun addActionListener(listener: IUsedeskActionListener) {
        runBlocking {
            listenersMutex.withLock {
                connectionStateSubject.value?.let(listener::onConnectionState)

                clientTokenSubject.value?.let(listener::onClientTokenReceived)

                messagesSubject.value?.let(listener::onMessagesReceived)

                messageSubject.value?.let(listener::onMessageReceived)

                offlineFormExpectedSubject.value?.let(listener::onOfflineFormExpected)

                feedbackSubject.value?.let { listener.onFeedbackReceived() }

                exceptionSubject.value?.let(listener::onException)

                actionListeners.add(listener)
            }
        }
    }

    override fun removeActionListener(listener: IUsedeskActionListener) {
        runBlocking {
            listenersMutex.withLock {
                actionListeners.remove(listener)
            }
        }
    }

    override fun addActionListener(listener: IUsedeskActionListenerRx) {
        actionListenersRx.add(listener)
        listener.onObservables(
            connectionStateSubject.observeOn(mainThread),
            clientTokenSubject.observeOn(mainThread),
            messageSubject.observeOn(mainThread),
            newMessageSubject.observeOn(mainThread),
            messagesSubject.observeOn(mainThread),
            messageUpdateSubject.observeOn(mainThread),
            messageRemovedSubject.observeOn(mainThread),
            offlineFormExpectedSubject.observeOn(mainThread),
            feedbackSubject.observeOn(mainThread),
            exceptionSubject.observeOn(mainThread)
        )
    }

    override fun removeActionListener(listener: IUsedeskActionListenerRx) {
        actionListenersRx.remove(listener)
        listener.onDispose()
    }

    override fun isNoListeners(): Boolean = runBlocking {
        listenersMutex.withLock { actionListeners }
    }.isEmpty() && actionListenersRx.isEmpty()

    override fun send(textMessage: String) {
        val message = textMessage.trim()
        if (message.isNotEmpty()) {
            val sendingMessage = createSendingMessage(message)
            eventListener.onMessagesNewReceived(listOf(sendingMessage))

            runBlocking {
                jobsMutex.withLock {
                    jobsScope.launchSafe({ sendCached(sendingMessage) })
                }
            }
        }
    }

    private fun sendText(sendingMessage: UsedeskMessageClientText) {
        try {
            sendCached(sendingMessage)
        } catch (e: Exception) {
            onMessageSendingFailed(sendingMessage)
            throw e
        }
    }

    private fun sendAdditionalFieldsIfNeededAsync() {
        runBlocking {
            jobsMutex.withLock {
                if (additionalFieldsNeeded) {
                    additionalFieldsNeeded = false
                    if (configuration.additionalFields.isNotEmpty() ||
                        configuration.additionalNestedFields.isNotEmpty()
                    ) {
                        ioScope.launch {
                            try {
                                waitFirstMessage()
                                delay(3000)
                                apiRepository.send(
                                    token!!,
                                    configuration,
                                    configuration.additionalFields,
                                    configuration.additionalNestedFields
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                                additionalFieldsNeeded = true
                                exceptionSubject.onNext(e)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun send(usedeskFileInfoList: List<UsedeskFileInfo>) {
        val sendingMessages = usedeskFileInfoList.map(this@ChatInteractor::createSendingMessage)

        eventListener.onMessagesNewReceived(sendingMessages)

        runBlocking {
            jobsMutex.withLock {
                sendingMessages.forEach { msg ->
                    jobsScope.launchSafe({ sendCached(msg) })
                }
            }
        }
    }

    private fun sendFile(sendingMessage: UsedeskMessageFile) {
        sendingMessage as UsedeskMessageClient
        try {
            sendCached(sendingMessage)
        } catch (e: Exception) {
            onMessageSendingFailed(sendingMessage)
            throw e
        }
    }

    private fun sendCached(fileMessage: UsedeskMessageFile) {
        try {
            cachedMessages.addNotSentMessage(fileMessage as UsedeskMessageClient)
            val cachedUri = runBlocking {
                val uri = Uri.parse(fileMessage.file.content)
                val deferredCachedUri = cachedMessages.getCachedFileAsync(uri)
                deferredCachedUri.await()
            }
            val newFile = fileMessage.file.copy(content = cachedUri.toString())
            val cachedNotSentMessage = when (fileMessage) {
                is UsedeskMessageClientAudio -> UsedeskMessageClientAudio(
                    fileMessage.id,
                    fileMessage.createdAt,
                    newFile,
                    fileMessage.status,
                    fileMessage.localId
                )
                is UsedeskMessageClientVideo -> UsedeskMessageClientVideo(
                    fileMessage.id,
                    fileMessage.createdAt,
                    newFile,
                    fileMessage.status,
                    fileMessage.localId
                )
                is UsedeskMessageClientImage -> UsedeskMessageClientImage(
                    fileMessage.id,
                    fileMessage.createdAt,
                    newFile,
                    fileMessage.status,
                    fileMessage.localId
                )
                else -> UsedeskMessageClientFile(
                    fileMessage.id,
                    fileMessage.createdAt,
                    newFile,
                    fileMessage.status,
                    fileMessage.localId
                )
            }
            cachedMessages.updateNotSentMessage(cachedNotSentMessage)
            eventListener.onMessageUpdated(cachedNotSentMessage)
            waitFirstMessage()
            apiRepository.send(
                configuration,
                token!!,
                UsedeskFileInfo(
                    cachedUri,
                    cachedNotSentMessage.file.type,
                    cachedNotSentMessage.file.name
                ),
                cachedNotSentMessage.localId
            )
            unlockFirstMessage()
            cachedMessages.removeNotSentMessage(cachedNotSentMessage)
            runBlocking {
                cachedMessages.removeFileFromCache(Uri.parse(fileMessage.file.content))
            }
            sendAdditionalFieldsIfNeededAsync()
        } catch (e: Exception) {
            onMessageSendingFailed(fileMessage as UsedeskMessageClient)
            throw e
        }
    }

    private fun resetFirstMessageLock() {
        runBlocking {
            firstMessageMutex.withLock {
                firstMessageLock = Mutex()
            }
        }
    }

    private fun lockFirstMessage() {
        runBlocking {
            firstMessageMutex.withLock {
                firstMessageLock
            }?.apply {
                when {
                    !isLocked -> lock(this@ChatInteractor)
                    else -> waitFirstMessage()
                }
            }
        }
    }

    private fun unlockFirstMessage() {
        runBlocking {
            firstMessageMutex.withLock {
                firstMessageLock?.apply {
                    if (isLocked) {
                        delay(1000)
                        unlock(this@ChatInteractor)
                    }
                    firstMessageLock = null
                    println()
                }
            }
        }
    }

    private fun waitFirstMessage() {
        runBlocking {
            firstMessageLock?.withLock {}
        }
    }

    private fun sendCached(cachedMessage: UsedeskMessageClientText) {
        try {
            cachedMessages.addNotSentMessage(cachedMessage)
            lockFirstMessage()
            apiRepository.send(cachedMessage)
            cachedMessages.removeNotSentMessage(cachedMessage)
            sendAdditionalFieldsIfNeededAsync()
        } catch (e: Exception) {
            onMessageSendingFailed(cachedMessage)
            throw e
        } finally {
            unlockFirstMessage()
        }
    }

    private fun onMessageSendingFailed(sendingMessage: UsedeskMessageClient) {
        when (sendingMessage) {
            is UsedeskMessageClientText -> UsedeskMessageClientText(
                sendingMessage.id,
                sendingMessage.createdAt,
                sendingMessage.text,
                sendingMessage.convertedText,
                UsedeskMessageClient.Status.SEND_FAILED
            )
            is UsedeskMessageClientFile -> UsedeskMessageClientFile(
                sendingMessage.id,
                sendingMessage.createdAt,
                sendingMessage.file,
                UsedeskMessageClient.Status.SEND_FAILED
            )
            is UsedeskMessageClientImage -> UsedeskMessageClientImage(
                sendingMessage.id,
                sendingMessage.createdAt,
                sendingMessage.file,
                UsedeskMessageClient.Status.SEND_FAILED
            )
            is UsedeskMessageClientVideo -> UsedeskMessageClientVideo(
                sendingMessage.id,
                sendingMessage.createdAt,
                sendingMessage.file,
                UsedeskMessageClient.Status.SEND_FAILED
            )
            is UsedeskMessageClientAudio -> UsedeskMessageClientAudio(
                sendingMessage.id,
                sendingMessage.createdAt,
                sendingMessage.file,
                UsedeskMessageClient.Status.SEND_FAILED
            )
            else -> null
        }?.let(this@ChatInteractor::onMessageUpdate)
    }

    override fun send(agentMessage: UsedeskMessageAgentText, feedback: UsedeskFeedback) {
        apiRepository.send(agentMessage.id, feedback)

        onMessageUpdate(
            UsedeskMessageAgentText(
                agentMessage.id,
                agentMessage.createdAt,
                agentMessage.text,
                agentMessage.convertedText,
                agentMessage.buttons,
                false,
                feedback,
                agentMessage.name,
                agentMessage.avatar
            )
        )
    }

    override fun send(offlineForm: UsedeskOfflineForm) {
        when {
            offlineFormToChat -> offlineForm.run {
                val fields = offlineForm.fields
                    .map(UsedeskOfflineForm.Field::value)
                    .filter(String::isNotEmpty)
                val strings =
                    listOf(clientName, clientEmail, topic) + fields + offlineForm.message
                initClientOfflineForm = strings.joinToString(separator = "\n")
                chatInited?.let(this@ChatInteractor::onChatInited)
            }
            else -> apiRepository.send(
                configuration,
                configuration.getCompanyAndChannel(),
                offlineForm
            )
        }
    }

    override fun sendAgain(id: Long) {
        val message = lastMessages.firstOrNull { it.id == id }
        if (message is UsedeskMessageClient
            && message.status == UsedeskMessageClient.Status.SEND_FAILED
        ) {
            when (message) {
                is UsedeskMessageClientText -> {
                    val sendingMessage = UsedeskMessageClientText(
                        message.id,
                        message.createdAt,
                        message.text,
                        message.convertedText,
                        UsedeskMessageClient.Status.SENDING
                    )
                    onMessageUpdate(sendingMessage)
                    sendText(sendingMessage)
                }
                is UsedeskMessageClientImage -> {
                    val sendingMessage = UsedeskMessageClientImage(
                        message.id,
                        message.createdAt,
                        message.file,
                        UsedeskMessageClient.Status.SENDING
                    )
                    onMessageUpdate(sendingMessage)
                    sendFile(sendingMessage)
                }
                is UsedeskMessageClientVideo -> {
                    val sendingMessage = UsedeskMessageClientVideo(
                        message.id,
                        message.createdAt,
                        message.file,
                        UsedeskMessageClient.Status.SENDING
                    )
                    onMessageUpdate(sendingMessage)
                    sendFile(sendingMessage)
                }
                is UsedeskMessageClientAudio -> {
                    val sendingMessage = UsedeskMessageClientAudio(
                        message.id,
                        message.createdAt,
                        message.file,
                        UsedeskMessageClient.Status.SENDING
                    )
                    onMessageUpdate(sendingMessage)
                    sendFile(sendingMessage)
                }
                is UsedeskMessageClientFile -> {
                    val sendingMessage = UsedeskMessageClientFile(
                        message.id,
                        message.createdAt,
                        message.file,
                        UsedeskMessageClient.Status.SENDING
                    )
                    onMessageUpdate(sendingMessage)
                    sendFile(sendingMessage)
                }
            }
        }
    }

    override fun removeMessage(id: Long) {
        cachedMessages.getNotSentMessages().firstOrNull {
            it.localId == id
        }?.let {
            cachedMessages.removeNotSentMessage(it)
            onMessageRemove(it as UsedeskMessage)
        }
    }

    override fun removeMessageRx(id: Long) = safeCompletableIo(ioScheduler) { removeMessage(id) }

    @Synchronized
    override fun setMessageDraft(messageDraft: UsedeskMessageDraft) {
        runBlocking {
            cachedMessages.setMessageDraft(messageDraft, true)
        }
    }

    override fun setMessageDraftRx(messageDraft: UsedeskMessageDraft) =
        safeCompletableIo(ioScheduler) { setMessageDraft(messageDraft) }

    override fun getMessageDraft() = runBlocking { cachedMessages.getMessageDraft() }

    override fun getMessageDraftRx() =
        safeSingleIo(ioScheduler, this@ChatInteractor::getMessageDraft)

    override fun sendMessageDraft() {
        runBlocking {
            val messageDraft = cachedMessages.setMessageDraft(
                UsedeskMessageDraft(),
                false
            )

            send(messageDraft.text)
            send(messageDraft.files)
            //TODO: sync
        }
    }

    override fun sendMessageDraftRx() =
        safeCompletableIo(ioScheduler, this@ChatInteractor::sendMessageDraft)

    private fun createSendingMessage(text: String) = UsedeskMessageClientText(
        cachedMessages.getNextLocalId(),
        Calendar.getInstance(),
        text,
        apiRepository.convertText(text),
        UsedeskMessageClient.Status.SENDING
    )

    private fun createSendingMessage(fileInfo: UsedeskFileInfo): UsedeskMessageFile {
        val localId = cachedMessages.getNextLocalId()
        val calendar = Calendar.getInstance()
        val file = UsedeskFile.create(
            fileInfo.uri.toString(),
            fileInfo.type,
            "",
            fileInfo.name
        )
        return when {
            fileInfo.isImage() -> UsedeskMessageClientImage(
                localId,
                calendar,
                file,
                UsedeskMessageClient.Status.SENDING
            )
            fileInfo.isVideo() -> UsedeskMessageClientVideo(
                localId,
                calendar,
                file,
                UsedeskMessageClient.Status.SENDING
            )
            fileInfo.isAudio() -> UsedeskMessageClientAudio(
                localId,
                calendar,
                file,
                UsedeskMessageClient.Status.SENDING
            )
            else -> UsedeskMessageClientFile(
                localId,
                calendar,
                file,
                UsedeskMessageClient.Status.SENDING
            )
        }
    }

    override fun connectRx() = safeCompletableIo(ioScheduler, this@ChatInteractor::connect)

    override fun sendRx(
        agentMessage: UsedeskMessageAgentText,
        feedback: UsedeskFeedback
    ) = safeCompletableIo(ioScheduler) { send(agentMessage, feedback) }

    override fun sendRx(offlineForm: UsedeskOfflineForm) =
        safeCompletableIo(ioScheduler) { send(offlineForm) }

    override fun sendAgainRx(id: Long) = safeCompletableIo(ioScheduler) { sendAgain(id) }

    override fun disconnectRx() = safeCompletableIo(ioScheduler, this@ChatInteractor::disconnect)

    override fun loadPreviousMessagesPage() = runBlocking {
        oldMutex.withLock {
            oldMessagesLoadDeferred ?: createPreviousMessagesJobLockedAsync()
        }?.await()
    } ?: true

    private fun createPreviousMessagesJobLockedAsync(): Deferred<Boolean>? {
        val messages = messagesSubject.value
        val oldestMessageId = messages?.firstOrNull()?.id
        val token = token
        return when {
            oldestMessageId != null && token != null -> CoroutineScope(Dispatchers.IO).async {
                try {
                    val hasUnloadedMessages = apiRepository.loadPreviousMessages(
                        configuration,
                        token,
                        oldestMessageId
                    )
                    oldMutex.withLock {
                        oldMessagesLoadDeferred = when {
                            hasUnloadedMessages -> null
                            else -> CompletableDeferred(false)
                        }
                    }
                    hasUnloadedMessages
                } catch (e: Exception) {
                    e.printStackTrace()
                    oldMutex.withLock {
                        oldMessagesLoadDeferred = null
                    }
                    true
                }
            }.also {
                oldMessagesLoadDeferred = it
            }
            else -> null
        }
    }

    override fun release() {
        listenersDisposables.forEach(Disposable::dispose)
        runBlocking {
            jobsMutex.withLock {
                jobsScope.cancel()
            }
        }
        disconnect()
    }

    override fun releaseRx() = safeCompletableIo(ioScheduler, this@ChatInteractor::release)

    private fun sendUserEmail() {
        try {
            token?.let {
                apiRepository.setClient(
                    configuration.copy(
                        clientToken = it
                    )
                )
            }
        } catch (e: UsedeskException) {
            exceptionSubject.onNext(e)
        }
    }

    private fun onChatInited(chatInited: ChatInited) {
        this.token = chatInited.token
        if (configuration.clientToken != chatInited.token) {
            clientTokenSubject.onNext(chatInited.token)
        }

        if (chatInited.status in ACTIVE_STATUSES) {
            unlockFirstMessage()
        }

        val oldConfiguration = userInfoRepository.getConfiguration(configuration)

        if (initClientOfflineForm != null ||
            oldConfiguration?.clientInitMessage == initClientMessage
        ) {
            initClientMessage = null
        }

        userInfoRepository.setConfiguration(configuration.copy(clientToken = token))

        val ids = lastMessages.map(UsedeskMessage::id)
        val filteredMessages = chatInited.messages.filter { it.id !in ids }
        val notSentMessages = cachedMessages.getNotSentMessages()
            .mapNotNull { it as? UsedeskMessage }
        val filteredNotSentMessages = notSentMessages.filter { it.id !in ids }
        val needToResendMessages = lastMessages.isNotEmpty()
        onMessagesNew(
            new = filteredMessages + filteredNotSentMessages,
            isInited = true
        )

        when {
            chatInited.waitingEmail -> sendUserEmail()
            else -> eventListener.onSetEmailSuccess()
        }
        when {
            needToResendMessages -> {
                val initedNotSentIds = initedNotSentMessages.map(UsedeskMessage::id)
                notSentMessages.filter {
                    it.id !in initedNotSentIds
                }.forEach {
                    listenersDisposables.add(
                        sendAgainRx(it.id).subscribe({}, Throwable::printStackTrace)
                    )
                }
            }
            else -> initedNotSentMessages = notSentMessages
        }
    }

    private fun CoroutineScope.launchSafe(
        onRun: () -> Unit,
        onThrowable: (Throwable) -> Unit = Throwable::printStackTrace
    ) = launch {
        try {
            onRun()
        } catch (e: Throwable) {
            onThrowable(e)
        }
    }

    companion object {
        private val ACTIVE_STATUSES = listOf(1, 5, 6, 8)
    }
}