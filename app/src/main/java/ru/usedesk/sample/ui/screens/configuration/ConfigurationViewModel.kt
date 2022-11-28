package ru.usedesk.sample.ui.screens.configuration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.usedesk.chat_sdk.UsedeskChatSdk
import ru.usedesk.chat_sdk.domain.IUsedeskChat.CreateChatResult
import ru.usedesk.chat_sdk.entity.UsedeskChatConfiguration
import ru.usedesk.common_gui.UsedeskViewModel
import ru.usedesk.common_sdk.entity.UsedeskSingleLifeEvent
import ru.usedesk.knowledgebase_sdk.entity.UsedeskKnowledgeBaseConfiguration
import ru.usedesk.sample.ServiceLocator
import ru.usedesk.sample.model.configuration.entity.Configuration
import ru.usedesk.sample.model.configuration.entity.ConfigurationValidation
import ru.usedesk.sample.ui.screens.configuration.ConfigurationViewModel.Model

class ConfigurationViewModel : UsedeskViewModel<Model>(Model()) {
    private val configurationRepository = ServiceLocator.configurationRepository

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val ioScope = CoroutineScope(Dispatchers.IO)

    data class Model(
        val configuration: Configuration = Configuration(),
        val validation: ConfigurationValidation? = null,
        val avatar: String? = null,
        val clientToken: ClientToken = ClientToken()
    )

    init {
        mainScope.launch {
            configurationRepository.getConfigurationFlow().collect {
                setModel { copy(configuration = it) }
            }
        }
    }

    fun onGoSdkClick(configuration: Configuration): Boolean {
        val configurationValidation = validate(configuration)
        setModel { copy(validation = configurationValidation) }
        return if (configurationValidation.chatConfigurationValidation.isAllValid()
            && configurationValidation.knowledgeBaseConfiguration.isAllValid()
        ) {
            configurationRepository.setConfiguration(configuration)
            true
        } else {
            false
        }
    }

    fun onCreateChat(configuration: Configuration): Boolean {
        val configurationValidation = validate(configuration)
        setModel { copy(validation = configurationValidation) }
        return if (configurationValidation.chatConfigurationValidation.isAllValid()
            && configurationValidation.knowledgeBaseConfiguration.isAllValid()
        ) {
            configurationRepository.setConfiguration(configuration)
            true
        } else {
            false
        }
    }

    fun setTempConfiguration(configuration: Configuration) {
        setModel { copy(configuration = configuration) }
    }

    fun setAvatar(avatar: String?) {
        setModel { copy(avatar = avatar) }
    }

    private fun validate(configuration: Configuration): ConfigurationValidation {
        val chatValidation = UsedeskChatConfiguration(
            configuration.urlChat,
            configuration.urlChatApi,
            configuration.companyId,
            configuration.channelId,
            configuration.messagesPageSize,
            configuration.clientToken,
            configuration.clientEmail,
            configuration.clientName,
            configuration.clientNote,
            configuration.clientPhoneNumber,
            configuration.clientAdditionalId,
            configuration.clientInitMessage,
            null,
            configuration.cacheFiles
        ).validate()
        val knowledgeBaseValidation = UsedeskKnowledgeBaseConfiguration(
            configuration.urlApi,
            configuration.accountId,
            configuration.token,
            configuration.clientEmail,
            configuration.clientName
        ).validate()
        return ConfigurationValidation(
            chatValidation,
            knowledgeBaseValidation
        )
    }

    fun isMaterialComponentsSwitched(configuration: Configuration): Boolean =
        when (configuration.materialComponents) {
            modelFlow.value.configuration.materialComponents -> false
            else -> {
                configurationRepository.setConfiguration(configuration)
                true
            }
        }

    fun createChat(apiToken: String) {
        setModel {
            when {
                clientToken.loading -> this
                else -> {
                    UsedeskChatSdk.requireInstance().createChat(apiToken) { result ->
                        setModel {
                            copy(
                                clientToken = when (result) {
                                    is CreateChatResult.Done -> clientToken.copy(
                                        loading = false,
                                        completed = UsedeskSingleLifeEvent(result.clientToken)
                                    )
                                    CreateChatResult.Error -> clientToken.copy(
                                        loading = false,
                                        error = UsedeskSingleLifeEvent(Unit)
                                    )
                                }
                            )
                        }

                        UsedeskChatSdk.release(false)
                    }
                    copy(clientToken = clientToken.copy(loading = true))
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()

        mainScope.cancel()
        ioScope.cancel()
    }

    data class ClientToken(
        val loading: Boolean = false,
        val completed: UsedeskSingleLifeEvent<String?>? = null,
        val error: UsedeskSingleLifeEvent<Unit>? = null
    )
}