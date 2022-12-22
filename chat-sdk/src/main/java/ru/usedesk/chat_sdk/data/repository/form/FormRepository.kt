package ru.usedesk.chat_sdk.data.repository.form

import com.google.gson.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.usedesk.chat_sdk.data.repository._extra.ChatDatabase
import ru.usedesk.chat_sdk.data.repository.form.IFormRepository.LoadFormResponse
import ru.usedesk.chat_sdk.data.repository.form.IFormRepository.SendFormResponse
import ru.usedesk.chat_sdk.data.repository.form.entity.DbForm
import ru.usedesk.chat_sdk.data.repository.form.entity.LoadForm
import ru.usedesk.chat_sdk.data.repository.form.entity.SaveForm
import ru.usedesk.chat_sdk.entity.UsedeskChatConfiguration
import ru.usedesk.chat_sdk.entity.UsedeskForm
import ru.usedesk.chat_sdk.entity.UsedeskMessageAgentText.Field
import ru.usedesk.common_sdk.api.IUsedeskApiFactory
import ru.usedesk.common_sdk.api.UsedeskApiRepository
import ru.usedesk.common_sdk.api.multipart.IUsedeskMultipartConverter
import ru.usedesk.common_sdk.utils.UsedeskValidatorUtil
import javax.inject.Inject

internal class FormRepository @Inject constructor(
    database: ChatDatabase,
    initConfiguration: UsedeskChatConfiguration,
    multipartConverter: IUsedeskMultipartConverter,
    apiFactory: IUsedeskApiFactory,
    gson: Gson
) : UsedeskApiRepository<FormApi>(
    apiFactory,
    multipartConverter,
    gson,
    FormApi::class.java
), IFormRepository {
    private val userKey = initConfiguration.userKey()
    private val formDao = database.formDao()
    private val mutex = Mutex()
    private val dbGson = Gson()

    override suspend fun saveForm(form: UsedeskForm) {
        formDao.save(form.toDb())
    }

    private fun UsedeskForm.toDb(): DbForm {
        val fieldMap = fields.associate {
            it.id to when (it) {
                is Field.CheckBox -> it.checked.toString()
                is Field.List -> it.selected?.id?.toString()
                is Field.Text -> it.text.trim()
            }
        }
        val rawFields = dbGson.toJson(fieldMap)
        return DbForm(
            id,
            userKey,
            rawFields,
            state == UsedeskForm.State.SENT
        )
    }

    private suspend fun getDbForm(formId: Long) =
        mutex.withLock { valueOrNull { formDao.get(formId) } }

    override suspend fun loadForm(
        urlChatApi: String,
        clientToken: String,
        form: UsedeskForm
    ): LoadFormResponse {
        val listsId = form.fields
            .asSequence()
            .filter { it !is Field.Text }
            .joinToString(",") { it.id }
        val request = LoadForm.Request(
            clientToken,
            listsId
        )
        val response = doRequestJson(
            urlChatApi,
            request,
            LoadForm.Response::class.java,
            FormApi::loadForm
        )
        return when (response?.fields) {
            null -> LoadFormResponse.Error(response?.code)
            else -> {
                val fieldMap = form.fields.associateBy(Field::id)
                val loadedFields = form.fields.mapNotNull {
                    when (it) {
                        is Field.Text -> listOf(it)
                        else -> {
                            val field = response.fields[it.id]
                            when (field?.get("list")) {
                                null -> field?.convert(it)
                                else -> field.convertToList(fieldMap)
                            }
                        }
                    }
                }.flatten()
                val loadedForm = form.copy(
                    fields = loadedFields,
                    state = UsedeskForm.State.LOADED
                )
                val savedForm = when (val dbForm = getDbForm(form.id)) {
                    null -> loadedForm
                    else -> {
                        val savedFields = dbGson.fromJson(dbForm.fields, JsonObject::class.java)
                        loadedForm.copy(
                            fields = loadedForm.fields.map { field ->
                                when (val savedValue = savedFields[field.id]?.asString) {
                                    null -> field
                                    else -> when (field) {
                                        is Field.CheckBox -> field.copy(checked = savedValue == "true")
                                        is Field.List -> {
                                            val itemId = savedValue.toLongOrNull()
                                            field.copy(selected = field.items.firstOrNull { it.id == itemId })
                                        }
                                        is Field.Text -> field.copy(text = savedValue)
                                    }
                                }
                            },
                            state = when {
                                dbForm.sent -> UsedeskForm.State.SENT
                                else -> UsedeskForm.State.LOADED
                            }
                        )
                    }
                }
                LoadFormResponse.Done(savedForm)
            }
        }
    }

    override fun validateForm(form: UsedeskForm): UsedeskForm = form.copy(
        fields = form.fields.map { field ->
            when (field) {
                is Field.CheckBox -> field.copy(hasError = field.required && !field.checked)
                is Field.List -> {
                    val isValid = !field.required || field.selected != null
                    field.copy(hasError = !isValid)
                }
                is Field.Text -> {
                    val text = field.text
                    val isValid = when (field.type) {
                        Field.Text.Type.EMAIL -> when {
                            field.required -> UsedeskValidatorUtil.isValidEmailNecessary(text)
                            else -> UsedeskValidatorUtil.isValidEmail(text)
                        }
                        Field.Text.Type.PHONE -> when {
                            field.required -> UsedeskValidatorUtil.isValidPhoneNecessary(text)
                            else -> UsedeskValidatorUtil.isValidPhone(text)
                        }
                        else -> !field.required || text.any { it.isLetterOrDigit() }
                    }
                    field.copy(hasError = !isValid)
                }
            }
        }
    )

    override suspend fun sendForm(
        urlChatApi: String,
        clientToken: String,
        form: UsedeskForm
    ): SendFormResponse {
        val request = SaveForm.Request(
            clientToken,
            form.fields.mapNotNull { field ->
                val value = when (field) {
                    is Field.CheckBox -> JsonPrimitive(field.checked.toString())
                    is Field.List -> {
                        when (field.parentId) {
                            null -> {
                                val lists = form.fields.filterIsInstance<Field.List>()
                                val tree = mutableListOf(field)
                                var lastChild: Field.List? = field
                                while (lastChild != null) {
                                    lastChild = lists.firstOrNull { list ->
                                        list.parentId == lastChild?.id
                                    }
                                    if (lastChild != null) {
                                        tree.add(lastChild)
                                    }
                                }
                                when (tree.size) {
                                    1 -> when (val selectedId = field.selected?.id?.toString()) {
                                        null -> null
                                        else -> JsonPrimitive(selectedId)
                                    }
                                    else -> JsonArray().apply {
                                        tree.forEach { list ->
                                            add(JsonObject().apply {
                                                add("id", JsonPrimitive(list.id))
                                                add(
                                                    "value",
                                                    JsonPrimitive(
                                                        list.selected?.id?.toString() ?: ""
                                                    )
                                                )
                                            })
                                        }
                                    }
                                }
                            }
                            else -> null
                        }
                    }
                    is Field.Text -> JsonPrimitive(field.text)
                }
                when (value) {
                    null -> null
                    else -> SaveForm.Request.Field(
                        field.id,
                        field.required,
                        value
                    )
                }
            }
        )
        val response = doRequestJson(
            urlChatApi,
            request,
            SaveForm.Response::class.java,
            FormApi::saveForm
        )
        return when (response?.status) {
            1 -> {
                val newForm = form.copy(state = UsedeskForm.State.SENT)
                saveForm(newForm)
                SendFormResponse.Done(newForm)
            }
            else -> SendFormResponse.Error(response?.code)
        }
    }

    private fun JsonObject.getOrNull(key: String) = when (val value = get(key)) {
        is JsonNull -> null
        else -> value
    }

    private fun JsonObject.convert(field: Field): List<Field> = //TODO: конвертер бы сюда
        when (getOrNull("ticket_field_type_id")?.asInt) {
            3 -> listOf(
                Field.CheckBox(
                    field.id,
                    field.name,
                    field.required
                )
            )
            2 -> listOf(field)
            1 -> listOf(
                Field.Text(
                    id = field.id,
                    name = field.name,
                    required = field.required,
                    type = Field.Text.Type.NONE
                )
            )
            else -> listOf()
        }

    private fun JsonObject.convertToList(lists: Map<String, Field>): List<Field.List>? =
        valueOrNull {
            when (val list = getAsJsonObject("list")) {
                null -> {
                    val fieldLoaded =
                        gson.fromJson(this, LoadForm.Response.FieldLoadedList::class.java)
                    when {
                        fieldLoaded.children.isEmpty() -> null
                        else -> listOfNotNull(
                            (lists[fieldLoaded.id] as? Field.List)?.copy(
                                items = fieldLoaded.children.map {
                                    Field.List.Item(
                                        it.id,
                                        it.value,
                                        it.parentOptionId?.toList() ?: listOf()
                                    )
                                },
                                parentId = getOrNull("parent_field_id")?.asString
                            )
                        )
                    }
                }
                else -> {
                    list.entrySet().mapNotNull {
                        (it.value as JsonObject).convertToList(lists)
                    }.flatten()
                }
            }
        }

}