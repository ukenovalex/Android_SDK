package ru.usedesk.knowledgebase_gui.internal.screens.pages.article

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import ru.usedesk.common_gui.internal.argsGetLong
import ru.usedesk.knowledgebase_gui.R
import ru.usedesk.knowledgebase_gui.internal.screens.common.FragmentDataView
import ru.usedesk.knowledgebase_gui.internal.screens.entity.DataOrMessage
import ru.usedesk.knowledgebase_sdk.external.entity.UsedeskArticleBody

class ArticleFragment : FragmentDataView<UsedeskArticleBody>(
        R.layout.usedesk_fragment_article,
        R.style.Usedesk_Theme_KnowledgeBase
) {

    private lateinit var textViewTitle: TextView
    private lateinit var contentWebView: WebView

    private val viewModel: ArticleViewModel by viewModels()

    override fun onView(view: View) {
        super.onView(view)

        textViewTitle = view.findViewById(R.id.tv_title)
        contentWebView = view.findViewById(R.id.wv_content)

        contentWebView.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun init() {
        val articleId = argsGetLong(arguments, ARTICLE_ID_KEY)
        if (articleId != null) {
            viewModel.init(articleId)
        }
    }

    override fun getLiveData(): LiveData<DataOrMessage<UsedeskArticleBody>> = viewModel.liveData

    override fun setDataView(data: UsedeskArticleBody) {
        textViewTitle.text = data.title
        contentWebView.loadData(data.text, "text/html", null)
    }

    companion object {
        private const val ARTICLE_ID_KEY = "articleIdKey"

        @JvmOverloads
        fun newInstance(themeId: Int? = null, articleId: Long): ArticleFragment {
            return ArticleFragment().apply {
                arguments = Bundle().apply {
                    if (themeId != null) {
                        putInt(THEME_ID_KEY, themeId)
                    }
                    putLong(ARTICLE_ID_KEY, articleId)
                }
            }
        }
    }
}