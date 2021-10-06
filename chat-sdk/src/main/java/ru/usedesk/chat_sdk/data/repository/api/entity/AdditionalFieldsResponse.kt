package ru.usedesk.chat_sdk.data.repository.api.entity

import com.google.gson.annotations.SerializedName

internal class AdditionalFieldsResponse(
    @SerializedName("ticket_id")
    private val ticketId: Long,
    private val status: Int
)