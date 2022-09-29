package ru.usedesk.chat_sdk.di

import android.content.Context
import dagger.BindsInstance
import dagger.Component
import ru.usedesk.chat_sdk.data.repository.messages.IUsedeskMessagesRepository
import ru.usedesk.chat_sdk.domain.IUsedeskChat
import ru.usedesk.chat_sdk.entity.UsedeskChatConfiguration
import ru.usedesk.common_sdk.di.UsedeskCommonModule
import ru.usedesk.common_sdk.di.UsedeskCustom

@ChatScope
@Component(modules = [UsedeskCommonModule::class, ChatModule::class])
internal interface ChatComponent {

    val chatInteractor: IUsedeskChat

    @Component.Builder
    interface Builder {

        @BindsInstance
        fun appContext(context: Context): Builder

        @BindsInstance
        fun configuration(chatConfiguration: UsedeskChatConfiguration): Builder

        @BindsInstance
        fun customMessagesRepository(repository: UsedeskCustom<IUsedeskMessagesRepository>): Builder

        fun build(): ChatComponent
    }
}