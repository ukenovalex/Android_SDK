package ru.usedesk.chat_gui.chat.offlineformselector

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import ru.usedesk.chat_gui.R
import ru.usedesk.common_gui.UsedeskBinding
import ru.usedesk.common_gui.UsedeskFragment
import ru.usedesk.common_gui.inflateItem

internal class OfflineFormSelectorPage : UsedeskFragment() {

    private val viewModel: OfflineFormSelectorViewModel by viewModels()

    private lateinit var binding: Binding
    private lateinit var adapter: OfflineFormSelectorAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = inflateItem(
            inflater,
            container,
            R.layout.usedesk_page_offline_form_selector,
            R.style.Usedesk_Chat_Screen
        ) { rootView, defaultStyleId ->
            Binding(rootView, defaultStyleId)
        }

        init()

        return binding.rootView
    }

    private fun init() {
        val items = argsGetStringArray(KEY_ITEMS, arrayOf())
        val selectedIndex = argsGetInt(KEY_SELECTED_INDEX, 0)

        adapter = OfflineFormSelectorAdapter(
            binding.rvItems,
            binding,
            viewModel,
            viewLifecycleOwner,
            items.asList(),
            selectedIndex
        )

        viewModel.selectedIndexLiveData.observe(viewLifecycleOwner) {
            it?.let {
                getParentListener<IItemSelectChangeListener>()?.onItemSelectChange(it)
            }
        }
    }

    companion object {
        const val KEY_ITEMS = "keyItems"
        const val KEY_SELECTED_INDEX = "keySelectedIndex"

        fun newInstance(items: Array<String>, selectedIndex: Int): OfflineFormSelectorPage {
            return OfflineFormSelectorPage().apply {
                arguments = Bundle().apply {
                    putStringArray(KEY_ITEMS, items)
                    putInt(KEY_SELECTED_INDEX, selectedIndex)
                }
            }
        }
    }

    internal class Binding(rootView: View, defaultStyleId: Int) :
        UsedeskBinding(rootView, defaultStyleId) {
        val rvItems: RecyclerView = rootView.findViewById(R.id.rv_items)
    }
}