package ru.usedesk.chat_sdk.entity

interface IUsedeskActionListener {

    fun onConnectedState(connected: Boolean) {}

    fun onClientTokenReceived(clientToken: String) {}

    fun onMessageReceived(message: UsedeskMessage) {}

    fun onNewMessageReceived(message: UsedeskMessage) {}

    fun onMessagesReceived(messages: List<UsedeskMessage>) {}

    fun onMessageUpdated(message: UsedeskMessage) {}

    fun onMessageRemoved() {}

    fun onFeedbackReceived() {}

    fun onOfflineFormExpected(offlineFormSettings: UsedeskOfflineFormSettings) {}

    fun onException(usedeskException: Exception) {}
}