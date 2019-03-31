package ru.usedesk.sdk.ui.knowledgebase.pages.categories;

import android.support.annotation.NonNull;

import java.util.List;

import ru.usedesk.sdk.appsdk.KnowledgeBase;
import ru.usedesk.sdk.domain.entity.knowledgebase.Category;
import ru.usedesk.sdk.ui.knowledgebase.common.DataViewModel;
import ru.usedesk.sdk.ui.knowledgebase.common.ViewModelFactory;

class CategoriesViewModel extends DataViewModel<List<Category>> {


    private CategoriesViewModel(@NonNull KnowledgeBase knowledgeBase, long sectionId) {
        loadData(knowledgeBase.getCategoriesSingle(sectionId));
    }

    static class Factory extends ViewModelFactory<CategoriesViewModel> {

        private KnowledgeBase knowledgeBase;
        private long sectionId;

        public Factory(@NonNull KnowledgeBase knowledgeBase, long sectionId) {
            this.knowledgeBase = knowledgeBase;
            this.sectionId = sectionId;
        }

        @NonNull
        @Override
        protected CategoriesViewModel create() {
            return new CategoriesViewModel(knowledgeBase, sectionId);
        }

        @NonNull
        @Override
        protected Class<CategoriesViewModel> getClassType() {
            return CategoriesViewModel.class;
        }
    }
}
