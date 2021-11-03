package ru.usedesk.chat_gui.chat.messages.adapters

import android.content.res.Resources
import android.graphics.Rect
import android.support.v4.media.session.PlaybackStateCompat
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.view.updatePadding
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import com.makeramen.roundedimageview.RoundedImageView
import ru.usedesk.chat_gui.R
import ru.usedesk.chat_gui.chat.messages.MessagesViewModel
import ru.usedesk.chat_sdk.entity.*
import ru.usedesk.common_gui.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*


internal class MessagesAdapter(
    private val recyclerView: RecyclerView,
    private val viewModel: MessagesViewModel,
    lifecycleOwner: LifecycleOwner,
    private val customAgentName: String?,
    private val rejectedFileExtensions: Array<String>,
    private val onFileClick: (UsedeskFile) -> Unit,
    private val onUrlClick: (String) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.BaseViewHolder>() {

    private var items: List<UsedeskMessage> = listOf()
    private val viewHolders: MutableList<BaseViewHolder> = mutableListOf()
    private val layoutManager = LinearLayoutManager(recyclerView.context)

    private val mediaHolders = mutableListOf<MessageVideoViewHolder>()
    private val videoPlayer = SimpleExoPlayer.Builder(recyclerView.context)
        .setUseLazyPreparation(true)
        .build()

    init {
        recyclerView.apply {
            layoutManager = this@MessagesAdapter.layoutManager
            adapter = this@MessagesAdapter
            setHasFixedSize(false)
            addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
                val difBottom = oldBottom - bottom
                if (difBottom > 0) {
                    scrollBy(0, difBottom)
                }
            }
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lastItemIndex = layoutManager.findLastVisibleItemPosition()
                viewModel.showToBottomButton(lastItemIndex < items.size - 1)
            }
        })
        videoPlayer.addListener(object : Player.Listener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playWhenReady && playbackState == PlaybackStateCompat.STATE_PLAYING) {
                    if (mediaHolders.all {
                            !it.hasPlayer() || !it.isVisibleGlobal()
                        }) {
                        videoPlayer.stop()
                    }
                }
            }
        })
        viewModel.messagesLiveData.observe(lifecycleOwner) {
            it?.let {
                onMessages(it)
            }
        }
    }

    private fun onMessages(messages: List<UsedeskMessage>) {
        val oldItems = items
        items = messages

        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size

            override fun getNewListSize() = items.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldItems[oldItemPosition]
                val new = items[newItemPosition]
                return old.id == new.id ||
                        (old is UsedeskMessageClient &&
                                new is UsedeskMessageClient &&
                                old.localId == new.localId)
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldItems[oldItemPosition]
                val new = items[newItemPosition]
                if ((oldItemPosition == oldListSize - 1) != (newItemPosition == newListSize - 1)) {
                    return false
                }
                if (new is UsedeskMessageText &&
                    old is UsedeskMessageText &&
                    new.text != old.text
                ) {
                    return false
                }
                if (new is UsedeskMessageFile &&
                    old is UsedeskMessageFile &&
                    new.file.content != old.file.content
                ) {
                    return false
                }
                if (new is UsedeskMessageClient &&
                    old is UsedeskMessageClient &&
                    new.status != old.status
                ) {
                    return false
                }
                return true
            }
        })
        diffResult.dispatchUpdatesTo(this)
        if (oldItems.isEmpty()) {
            recyclerView.scrollToPosition(items.size - 1)
        } else {
            val visibleBottom = recyclerView.computeVerticalScrollOffset() + recyclerView.height
            val contentHeight = recyclerView.computeVerticalScrollRange()
            if (visibleBottom >= contentHeight) {//Если чат был внизу
                recyclerView.scrollToPosition(items.size - 1)
            }
        }
    }

    private fun getFormattedTime(calendar: Calendar): String {
        val dateFormat: DateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return dateFormat.format(calendar.time)
    }

    fun clear() {
        viewHolders.forEach {
            it.clear()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            UsedeskMessage.Type.TYPE_AGENT_TEXT.value -> {
                MessageTextAgentViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_text_agent,
                    R.style.Usedesk_Chat_Message_Text_Agent
                ) { rootView, defaultStyleId ->
                    MessageTextAgentBinding(rootView, defaultStyleId)
                })
            }
            UsedeskMessage.Type.TYPE_AGENT_FILE.value -> {
                MessageFileAgentViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_file_agent,
                    R.style.Usedesk_Chat_Message_File_Agent
                ) { rootView, defaultStyleId ->
                    MessageFileAgentBinding(rootView, defaultStyleId)
                })
            }
            UsedeskMessage.Type.TYPE_AGENT_IMAGE.value -> {
                MessageImageAgentViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_image_agent,
                    R.style.Usedesk_Chat_Message_Image_Agent
                ) { rootView, defaultStyleId ->
                    MessageImageAgentBinding(rootView, defaultStyleId)
                })
            }
            UsedeskMessage.Type.TYPE_AGENT_VIDEO.value -> {
                MessageVideoAgentViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_video_agent,
                    R.style.Usedesk_Chat_Message_Video_Agent
                ) { rootView, defaultStyleId ->
                    MessageVideoAgentBinding(rootView, defaultStyleId)
                })
            }
            UsedeskMessage.Type.TYPE_AGENT_AUDIO.value -> {
                MessageAudioAgentViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_audio_agent,
                    R.style.Usedesk_Chat_Message_Audio_Agent
                ) { rootView, defaultStyleId ->
                    MessageAudioAgentBinding(rootView, defaultStyleId)
                })
            }
            UsedeskMessage.Type.TYPE_CLIENT_TEXT.value -> {
                MessageTextClientViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_text_client,
                    R.style.Usedesk_Chat_Message_Text_Client
                ) { rootView, defaultStyleId ->
                    MessageTextClientBinding(rootView, defaultStyleId)
                })
            }
            UsedeskMessage.Type.TYPE_CLIENT_FILE.value -> {
                MessageFileClientViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_file_client,
                    R.style.Usedesk_Chat_Message_File_Client
                ) { rootView, defaultStyleId ->
                    MessageFileClientBinding(rootView, defaultStyleId)
                })
            }
            UsedeskMessage.Type.TYPE_CLIENT_IMAGE.value -> {
                MessageImageClientViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_image_client,
                    R.style.Usedesk_Chat_Message_Image_Client
                ) { rootView, defaultStyleId ->
                    MessageImageClientBinding(rootView, defaultStyleId)
                })
            }
            UsedeskMessage.Type.TYPE_CLIENT_VIDEO.value -> {
                MessageVideoClientViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_video_client,
                    R.style.Usedesk_Chat_Message_Video_Client
                ) { rootView, defaultStyleId ->
                    MessageVideoClientBinding(rootView, defaultStyleId)
                })
            }
            UsedeskMessage.Type.TYPE_CLIENT_AUDIO.value -> {
                MessageAudioClientViewHolder(inflateItem(
                    parent,
                    R.layout.usedesk_item_chat_message_audio_client,
                    R.style.Usedesk_Chat_Message_Audio_Client
                ) { rootView, defaultStyleId ->
                    MessageAudioClientBinding(rootView, defaultStyleId)
                })
            }
            else -> {
                throw RuntimeException("Unknown view type:$viewType")
            }
        }.apply {
            viewHolders.add(this)
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = items[position].type.value

    fun scrollToBottom() {
        recyclerView.smoothScrollToPosition(items.size - 1)
    }

    internal abstract class BaseViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        abstract fun bind(position: Int)

        open fun clear() {}
    }

    internal abstract inner class MessageViewHolder(
        itemView: View,
        private val bindingDate: DateBinding,
        private val tvTime: TextView,
        private val styleValues: UsedeskResourceManager.StyleValues
    ) : BaseViewHolder(itemView) {

        private val dateStyleValues = bindingDate.styleValues
            .getStyleValues(R.attr.usedesk_chat_message_date_text)

        override fun bind(position: Int) {
            val message = items[position]
            val formatted = getFormattedTime(message.createdAt)
            tvTime.text = formatted

            val previousMessage = items.getOrNull(position - 1)
            bindingDate.tvDate.visibility =
                if (isSameDay(previousMessage?.createdAt, message.createdAt)) {
                    View.GONE
                } else {
                    bindingDate.tvDate.text = when {
                        isToday(message.createdAt) -> {
                            dateStyleValues.getString(R.attr.usedesk_text_1)
                        }
                        isYesterday(message.createdAt) -> {
                            dateStyleValues.getString(R.attr.usedesk_text_2)
                        }
                        else -> {
                            val dateFormat: DateFormat =
                                SimpleDateFormat("dd MMMM", Locale.getDefault())
                            dateFormat.format(message.createdAt.time)
                        }
                    }
                    View.VISIBLE
                }
        }

        private fun isSameDay(calendarA: Calendar?, calendarB: Calendar): Boolean {
            return calendarA?.get(Calendar.YEAR) == calendarB.get(Calendar.YEAR) &&
                    calendarA.get(Calendar.DAY_OF_YEAR) == calendarB.get(Calendar.DAY_OF_YEAR)
        }

        fun bindAgent(
            position: Int,
            agentBinding: AgentBinding
        ) {
            val messageAgent = items[position] as UsedeskMessageAgent

            agentBinding.tvName.text = customAgentName ?: messageAgent.name
            agentBinding.tvName.visibility = visibleGone(!isSameAgent(messageAgent, position - 1))

            val avatarImageId: Int
            val visibleState: Int
            val invisibleState: Int

            agentBinding.styleValues.getStyleValues(R.attr.usedesk_chat_message_avatar_image).run {
                avatarImageId = getId(R.attr.usedesk_drawable_1)
                val visibility = listOf(View.VISIBLE, View.INVISIBLE, View.GONE)
                    .getOrNull(getInt(android.R.attr.visibility))
                when (visibility) {
                    View.INVISIBLE -> {
                        visibleState = View.INVISIBLE
                        invisibleState = View.INVISIBLE
                    }
                    View.GONE -> {
                        visibleState = View.GONE
                        invisibleState = View.GONE
                    }
                    else -> {
                        visibleState = View.VISIBLE
                        invisibleState = View.INVISIBLE
                        setImage(agentBinding.ivAvatar, messageAgent.avatar, avatarImageId)
                    }
                }
            }

            agentBinding.ivAvatar.visibility = if (!isSameAgent(messageAgent, position + 1)) {
                visibleState
            } else {
                invisibleState
            }

            val lastOfGroup = position == items.size - 1 ||
                    items.getOrNull(position + 1) is UsedeskMessageClient
            agentBinding.vEmpty.visibility = visibleGone(lastOfGroup)
        }

        fun bindClient(
            position: Int,
            clientBinding: ClientBinding
        ) {
            val lastOfGroup = position == items.size - 1 ||
                    items.getOrNull(position + 1) is UsedeskMessageAgent
            val clientMessage = items[position] as UsedeskMessageClient
            val timeStyleValues = styleValues.getStyleValues(R.attr.usedesk_chat_message_time_text)
            val statusDrawableId =
                if (clientMessage.status == UsedeskMessageClient.Status.SUCCESSFULLY_SENT) {
                    timeStyleValues.getId(R.attr.usedesk_drawable_2)
                } else {
                    timeStyleValues.getId(R.attr.usedesk_drawable_1)
                }

            tvTime.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                statusDrawableId,
                0
            )

            clientBinding.apply {
                ivSentFailed.apply {
                    visibility =
                        visibleInvisible(clientMessage.status == UsedeskMessageClient.Status.SEND_FAILED)
                    setOnClickListener {
                        PopupMenu(it.context, it).apply {
                            inflate(R.menu.usedesk_messages_error_popup)
                            setOnMenuItemClickListener { item ->
                                when (item.itemId) {
                                    R.id.send_again -> {
                                        viewModel.sendAgain(clientMessage.localId)
                                    }
                                    R.id.remove_message -> {
                                        viewModel.removeMessage(clientMessage.localId)
                                    }
                                }
                                true
                            }
                            show()
                        }
                    }
                }
                vEmpty.visibility = visibleGone(lastOfGroup)
            }
        }

        private fun <T : Any> bindBottomMargin(
            vEmpty: View,
            isClient: Boolean
        ) {
            val last = if (isClient) {
                items.getOrNull(adapterPosition + 1) is UsedeskMessageAgent
            } else {
                items.getOrNull(adapterPosition + 1) is UsedeskMessageClient
            }
            vEmpty.visibility = visibleGone(last)
        }

        private fun isSameAgent(messageAgent: UsedeskMessageAgent, anotherPosition: Int): Boolean {
            val anotherMessage = items.getOrNull(anotherPosition)
            return anotherMessage is UsedeskMessageAgent
                    && anotherMessage.avatar == messageAgent.avatar
                    && anotherMessage.name == messageAgent.name
        }
    }

    internal abstract inner class MessageTextViewHolder(
        itemView: View,
        private val binding: MessageTextBinding,
        bindingDate: DateBinding
    ) : MessageViewHolder(itemView, bindingDate, binding.tvTime, binding.styleValues) {

        override fun bind(position: Int) {
            super.bind(position)

            binding.lFeedback.visibility = View.GONE

            val messageText = items[position] as UsedeskMessageText

            binding.tvText.text = Html.fromHtml(messageText.text)
            binding.tvText.visibility = View.VISIBLE
        }
    }

    internal abstract inner class MessageFileViewHolder(
        itemView: View,
        private val binding: MessageFileBinding,
        bindingDate: DateBinding
    ) : MessageViewHolder(itemView, bindingDate, binding.tvTime, binding.styleValues) {

        private val textSizeStyleValues =
            binding.styleValues.getStyleValues(R.attr.usedesk_chat_message_file_size_text)

        override fun bind(position: Int) {
            super.bind(position)

            val messageFile = items[position] as UsedeskMessageFile

            val name = messageFile.file.name
            binding.tvFileName.text = name
            binding.tvExtension.text = name.substringAfterLast('.')
            if (rejectedFileExtensions.any { name.endsWith(it) }) {
                val textColor = textSizeStyleValues.getColor(R.attr.usedesk_text_color_2)
                binding.tvFileSize.text = textSizeStyleValues.getString(R.attr.usedesk_text_1)
                binding.tvFileSize.setTextColor(textColor)
            } else {
                val textColor = textSizeStyleValues.getColor(R.attr.usedesk_text_color_1)
                binding.tvFileSize.text = messageFile.file.size
                binding.tvFileSize.setTextColor(textColor)
            }
            binding.rootView.setOnClickListener {
                onFileClick(messageFile.file)
            }
        }
    }

    internal abstract inner class MessageImageViewHolder(
        itemView: View,
        private val binding: MessageImageBinding,
        bindingDate: DateBinding
    ) : MessageViewHolder(itemView, bindingDate, binding.tvTime, binding.styleValues) {

        private val loadingImageId = binding.styleValues
            .getStyleValues(R.attr.usedesk_chat_message_image_preview_image)
            .getId(R.attr.usedesk_drawable_1)

        override fun bind(position: Int) {
            super.bind(position)
            bindImage(position)
        }

        private fun bindImage(position: Int) {
            val messageFile = items[position] as UsedeskMessageFile

            binding.ivPreview.setOnClickListener(null)
            binding.ivError.setOnClickListener(null)

            showImage(binding.ivPreview,
                loadingImageId,
                messageFile.file.content,
                binding.pbLoading,
                binding.ivError, {
                    binding.ivPreview.setOnClickListener {
                        onFileClick(messageFile.file)
                    }
                }, {
                    binding.ivError.setOnClickListener {
                        bindImage(position)
                    }
                })
        }

        override fun clear() {
            clearImage(binding.ivPreview)
        }
    }

    internal abstract inner class MessageVideoViewHolder(
        itemView: View,
        private val binding: MessageVideoBinding,
        bindingDate: DateBinding
    ) : MessageViewHolder(itemView, bindingDate, binding.tvTime, binding.styleValues) {

        private val fullscreen: View = binding.pvVideo.findViewById(R.id.exo_fullscreen_icon)
        private lateinit var commentFile: UsedeskFile
        private var lastVisible = false

        init {
            binding.pvVideo.init { visible ->
                val paddingBottom = if (visible) {
                    32f
                } else {
                    0f
                }
                binding.lTimeContainer.updatePadding(
                    0,
                    0,
                    0,
                    dpToPixels(recyclerView.resources, paddingBottom).toInt()
                )
            }

            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val visible = isVisible()
                    if (!visible && lastVisible) {
                        reset()
                    }
                    lastVisible = visible
                }
            })

            fullscreen.setOnClickListener {
                Toast.makeText(recyclerView.context, "FULLSCREEN", Toast.LENGTH_SHORT).show()
                //activity.fullscreenVideo(binding.pvVideo)
            }
        }

        override fun bind(position: Int) {
            super.bind(position)
            val messageFile = items[position] as UsedeskMessageFile
            this.commentFile = messageFile.file
            reset()
        }

        override fun clear() {
            //clearImage(binding.ivPreview)
        }

        private fun isVisible(): Boolean {
            val rectParent = Rect()
            recyclerView.getGlobalVisibleRect(rectParent)
            val rectItem = Rect()
            binding.rootView.getGlobalVisibleRect(rectItem)
            return rectParent.contains(rectItem)
        }

        fun hasPlayer(): Boolean = binding.pvVideo.player != null

        fun isVisibleGlobal(): Boolean = lastVisible

        private fun changeElements(
            showStub: Boolean = false,
            showPreview: Boolean = false,
            showPlay: Boolean = false,
            showVideo: Boolean = false
        ) {
            binding.lStub.visibility = visibleInvisible(showStub)
            binding.ivPreview.visibility = visibleInvisible(showPreview)
            binding.ivPlay.visibility = visibleInvisible(showPlay)
            binding.pvVideo.visibility = visibleInvisible(showVideo)
        }

        fun reset() {//TODO: вместе с AudioViewHolder сделать интерфейс MediaHolder чтобы ресетить сразу всё
            binding.pvVideo.player?.stop()
            binding.pvVideo.player = null

            changeElements(
                showStub = true,
                showPlay = true,
            )

            showThumbnail(binding.ivPreview,
                commentFile.content,
                onSuccess = {
                    if (binding.pvVideo.visibility != View.VISIBLE) {
                        changeElements(
                            showPreview = true,
                            showPlay = true
                        )
                    }
                }
            )

            binding.ivPlay.setOnClickListener {
                changeElements(showVideo = true)

                videoPlayer.stop()
                binding.pvVideo.player = null
                (mediaHolders - this).forEach {
                    it.reset()
                }
                videoPlayer.setMediaItem(MediaItem.fromUri(commentFile.content))
                binding.pvVideo.player = videoPlayer
                videoPlayer.prepare()
                videoPlayer.play()
                binding.pvVideo.hideController()
            }
        }
    }

    fun dpToPixels(resources: Resources, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }

    fun PlayerView.init(
        onControlsVisibilityChanged: (Boolean) -> Unit = {}
    ) {
        onControlsVisibilityChanged(false)
        setControllerVisibilityListener { visibility ->
            val visible = visibility == View.VISIBLE
            findViewById<View>(R.id.exo_controller).startAnimation(
                AnimationUtils.loadAnimation(
                    context,
                    if (visible) {
                        R.anim.fade_in
                    } else {
                        R.anim.fade_out
                    }
                )
            )
            onControlsVisibilityChanged(visible)
        }
    }

    internal abstract inner class MessageAudioViewHolder(
        itemView: View,
        private val binding: MessageAudioBinding,
        bindingDate: DateBinding
    ) : MessageViewHolder(itemView, bindingDate, binding.tvTime, binding.styleValues) {

        override fun bind(position: Int) {
            super.bind(position)
            bindImage(position)
        }

        private fun bindImage(position: Int) {
            /*val messageFile = items[position] as UsedeskMessageFile

            binding.ivPreview.setOnClickListener(null)
            binding.ivError.setOnClickListener(null)

            showImage(binding.ivPreview,
                loadingImageId,
                messageFile.file.content,
                binding.pbLoading,
                binding.ivError, {
                    binding.ivPreview.setOnClickListener {
                        onFileClick(messageFile.file)
                    }
                }, {
                    binding.ivError.setOnClickListener {
                        bindImage(position)
                    }
                })*/
        }

        override fun clear() {
            //clearImage(binding.ivPreview)
        }
    }

    internal inner class MessageTextClientViewHolder(
        private val binding: MessageTextClientBinding
    ) : MessageTextViewHolder(binding.rootView, binding.content, binding.date) {
        override fun bind(position: Int) {
            super.bind(position)
            bindClient(position, binding.client)
        }
    }

    internal inner class MessageFileClientViewHolder(
        private val binding: MessageFileClientBinding
    ) : MessageFileViewHolder(binding.rootView, binding.content, binding.date) {
        override fun bind(position: Int) {
            super.bind(position)
            bindClient(position, binding.client)
        }
    }

    internal inner class MessageImageClientViewHolder(
        private val binding: MessageImageClientBinding
    ) : MessageImageViewHolder(binding.rootView, binding.content, binding.date) {
        override fun bind(position: Int) {
            super.bind(position)
            bindClient(position, binding.client)
        }
    }

    internal inner class MessageVideoClientViewHolder(
        private val binding: MessageVideoClientBinding
    ) : MessageVideoViewHolder(binding.rootView, binding.content, binding.date) {
        override fun bind(position: Int) {
            super.bind(position)
            bindClient(position, binding.client)
        }
    }

    internal inner class MessageAudioClientViewHolder(
        private val binding: MessageAudioClientBinding
    ) : MessageAudioViewHolder(binding.rootView, binding.content, binding.date) {
        override fun bind(position: Int) {
            super.bind(position)
            bindClient(position, binding.client)
        }
    }

    internal inner class MessageTextAgentViewHolder(
        private val binding: MessageTextAgentBinding
    ) : MessageTextViewHolder(binding.rootView, binding.content, binding.date) {

        private val goodStyleValues = binding.styleValues
            .getStyleValues(R.attr.usedesk_chat_message_feedback_good_image)

        private val badStyleValues = binding.styleValues
            .getStyleValues(R.attr.usedesk_chat_message_feedback_bad_image)

        private val thanksText = binding.styleValues
            .getStyleValues(R.attr.usedesk_chat_message_text_message_text)
            .getString(R.attr.usedesk_text_1)

        private val buttonsAdapter = ButtonsAdapter(binding.content.rvButtons) {
            if (it.url.isNotEmpty()) {
                onUrlClick(it.url)
            } else {
                viewModel.onSendButton(it.text)
            }
        }

        private val goodAtStart = binding.styleValues
            .getStyleValues(R.attr.usedesk_chat_message_feedback_good_image)
            .getInt(android.R.attr.layout_gravity) in arrayOf(Gravity.START, Gravity.LEFT)

        override fun bind(position: Int) {
            super.bind(position)
            bindAgent(position, binding.agent)

            val messageAgentText = items[position] as UsedeskMessageAgentText
            buttonsAdapter.update(messageAgentText.buttons)

            binding.content.rootView.layoutParams.apply {
                width = if (messageAgentText.buttons.isEmpty()
                    && !messageAgentText.feedbackNeeded
                    && messageAgentText.feedback == null
                ) {
                    FrameLayout.LayoutParams.WRAP_CONTENT
                } else {
                    FrameLayout.LayoutParams.MATCH_PARENT
                }
            }

            val ivLike = binding.content.ivLike
            val ivDislike = binding.content.ivDislike

            val goodImage = goodStyleValues.getId(R.attr.usedesk_drawable_1)
            val goodColoredImage = goodStyleValues.getId(R.attr.usedesk_drawable_2)
            val badImage = badStyleValues.getId(R.attr.usedesk_drawable_1)
            val badColoredImage = badStyleValues.getId(R.attr.usedesk_drawable_2)
            when {
                messageAgentText.feedback != null -> {
                    binding.content.lFeedback.visibility = View.VISIBLE

                    aloneSmile(
                        ivLike,
                        binding.content.lFeedback,
                        goodColoredImage,
                        messageAgentText.feedback == UsedeskFeedback.LIKE
                    )

                    aloneSmile(
                        ivDislike,
                        binding.content.lFeedback,
                        badColoredImage,
                        messageAgentText.feedback == UsedeskFeedback.DISLIKE
                    )

                    binding.content.tvText.text = thanksText
                }
                messageAgentText.feedbackNeeded -> {
                    binding.content.lFeedback.visibility = View.VISIBLE

                    enableSmile(
                        ivLike,
                        ivDislike,
                        binding.content.lFeedback,
                        goodAtStart,
                        goodImage,
                        goodColoredImage
                    ) {
                        viewModel.sendFeedback(messageAgentText, UsedeskFeedback.LIKE)
                        binding.content.tvText.text = thanksText
                    }

                    enableSmile(
                        ivDislike,
                        ivLike,
                        binding.content.lFeedback,
                        !goodAtStart,
                        badImage,
                        badColoredImage
                    ) {
                        viewModel.sendFeedback(messageAgentText, UsedeskFeedback.DISLIKE)
                        binding.content.tvText.text = thanksText
                    }
                }
                else -> {
                    binding.content.lFeedback.visibility = View.GONE
                }
            }
        }

        private fun aloneSmile(
            imageView: ImageView,
            container: ViewGroup,
            imageId: Int,
            visible: Boolean
        ) {
            imageView.apply {
                setImageResource(imageId)
                post {
                    x = container.width / 4.0f
                }
                alpha = if (visible) {
                    1.0f
                } else {
                    0.0f
                }
                isEnabled = false
                isClickable = false
                setOnClickListener(null)
            }
        }

        private fun enableSmile(
            imageViewMain: ImageView,
            imageViewSub: ImageView,
            container: ViewGroup,
            initStart: Boolean,
            initImageId: Int,
            activeImageId: Int,
            onClick: () -> Unit
        ) {
            imageViewMain.apply {
                post {
                    x = if (initStart) {
                        0.0f
                    } else {
                        container.width / 2.0f
                    }
                }
                alpha = 1.0f
                scaleX = 1.0f
                scaleY = 1.0f
                isEnabled = true
                setImageResource(initImageId)
                setOnClickListener {
                    isEnabled = false
                    isClickable = false
                    setOnClickListener(null)
                    imageViewSub.apply {
                        isEnabled = false
                        isClickable = false
                        setOnClickListener(null)
                    }

                    setImageResource(activeImageId)

                    onClick()

                    animate().setDuration(500)
                        .x(container.width / 4.0f)

                    imageViewSub.animate()
                        .setDuration(500)
                        .alpha(0.0f)
                        .scaleX(0.5f)
                        .scaleY(0.5f)
                }
            }
        }
    }

    internal inner class MessageFileAgentViewHolder(
        private val binding: MessageFileAgentBinding
    ) : MessageFileViewHolder(binding.rootView, binding.content, binding.date) {

        override fun bind(position: Int) {
            super.bind(position)
            bindAgent(position, binding.agent)
        }
    }

    internal inner class MessageImageAgentViewHolder(
        private val binding: MessageImageAgentBinding
    ) : MessageImageViewHolder(binding.rootView, binding.content, binding.date) {

        override fun bind(position: Int) {
            super.bind(position)
            bindAgent(position, binding.agent)
        }
    }

    internal inner class MessageVideoAgentViewHolder(
        private val binding: MessageVideoAgentBinding
    ) : MessageVideoViewHolder(binding.rootView, binding.content, binding.date) {

        override fun bind(position: Int) {
            super.bind(position)
            bindAgent(position, binding.agent)
        }
    }

    internal inner class MessageAudioAgentViewHolder(
        private val binding: MessageAudioAgentBinding
    ) : MessageAudioViewHolder(binding.rootView, binding.content, binding.date) {

        override fun bind(position: Int) {
            super.bind(position)
            bindAgent(position, binding.agent)
        }
    }

    companion object {
        private fun isToday(calendar: Calendar): Boolean {
            val today = Calendar.getInstance()
            return (today[Calendar.YEAR] == calendar[Calendar.YEAR]
                    && today[Calendar.DAY_OF_YEAR] == calendar[Calendar.DAY_OF_YEAR])
        }

        private fun isYesterday(calendar: Calendar): Boolean {
            val yesterday = Calendar.getInstance()
            yesterday.add(Calendar.DAY_OF_YEAR, -1)
            return (yesterday[Calendar.YEAR] == calendar[Calendar.YEAR]
                    && yesterday[Calendar.DAY_OF_YEAR] == calendar[Calendar.DAY_OF_YEAR])
        }
    }

    internal class DateBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val tvDate: TextView = rootView.findViewById(R.id.tv_date)
    }

    internal class MessageTextBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val tvTime: TextView = rootView.findViewById(R.id.tv_time)
        val rvButtons: RecyclerView = rootView.findViewById(R.id.rv_buttons)
        val lFeedback: ViewGroup = rootView.findViewById(R.id.l_feedback)
        val tvText: TextView = rootView.findViewById(R.id.tv_text)
        val ivLike: ImageView = rootView.findViewById(R.id.iv_like)
        val ivDislike: ImageView = rootView.findViewById(R.id.iv_dislike)
    }

    internal class MessageTextClientBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageTextBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val client = ClientBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class MessageTextAgentBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageTextBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val agent = AgentBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class MessageFileBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val tvTime: TextView = rootView.findViewById(R.id.tv_time)
        val tvFileName: TextView = rootView.findViewById(R.id.tv_file_name)
        val tvFileSize: TextView = rootView.findViewById(R.id.tv_file_size)
        val tvExtension: TextView = rootView.findViewById(R.id.tv_extension)
    }

    internal class MessageFileClientBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageFileBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val client = ClientBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class MessageFileAgentBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageFileBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val agent = AgentBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class MessageImageBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val tvTime: TextView = rootView.findViewById(R.id.tv_time)
        val ivPreview: RoundedImageView = rootView.findViewById(R.id.iv_preview)
        val ivError: ImageView = rootView.findViewById(R.id.iv_error)
        val pbLoading: ProgressBar = rootView.findViewById(R.id.pb_loading)
    }

    internal class MessageVideoBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val tvTime: TextView = rootView.findViewById(R.id.tv_time)
        val lTimeContainer: ViewGroup = rootView.findViewById(R.id.l_time_container)
        val pvVideo: PlayerView = rootView.findViewById(R.id.pv_video)
        val ivPreview: ImageView = rootView.findViewById(R.id.iv_preview)
        val ivPlay: ImageView = rootView.findViewById(R.id.iv_play)
        val lStub: ViewGroup = rootView.findViewById(R.id.l_stub)
    }

    internal class MessageAudioBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val tvTime: TextView = rootView.findViewById(R.id.tv_time)
        val pvVideo: PlayerView = rootView.findViewById(R.id.pv_video)
        val ivPlay: ImageView = rootView.findViewById(R.id.iv_play)
    }

    internal class MessageImageClientBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageImageBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val client = ClientBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class MessageVideoClientBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageVideoBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val client = ClientBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class MessageAudioClientBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageAudioBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val client = ClientBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class MessageImageAgentBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageImageBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val agent = AgentBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class MessageVideoAgentBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageVideoBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val agent = AgentBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class MessageAudioAgentBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val content = MessageAudioBinding(rootView.findViewById(R.id.content), defaultStyleId)
        val agent = AgentBinding(rootView, defaultStyleId)
        val date = DateBinding(rootView, defaultStyleId)
    }

    internal class AgentBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val ivAvatar: ImageView = rootView.findViewById(R.id.iv_avatar)
        val tvName: TextView = rootView.findViewById(R.id.tv_name)
        val vEmpty: View = rootView.findViewById(R.id.v_empty)
    }

    internal class ClientBinding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val vEmpty: View = rootView.findViewById(R.id.v_empty)
        val ivSentFailed: ImageView = rootView.findViewById(R.id.iv_sent_failed)
    }
}