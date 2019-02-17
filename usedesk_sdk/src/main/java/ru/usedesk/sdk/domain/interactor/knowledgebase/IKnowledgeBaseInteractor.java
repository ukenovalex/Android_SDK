package ru.usedesk.sdk.domain.interactor.knowledgebase;

import android.support.annotation.NonNull;

import java.util.List;

import io.reactivex.Single;
import ru.usedesk.sdk.domain.entity.knowledgebase.ArticleBody;
import ru.usedesk.sdk.domain.entity.knowledgebase.ArticleInfo;
import ru.usedesk.sdk.domain.entity.knowledgebase.Section;

public interface IKnowledgeBaseInteractor {

    @NonNull
    Single<List<Section>> getSectionsSingle();

    @NonNull
    Single<ArticleBody> getArticleSingle(@NonNull ArticleInfo articleInfo);
}
