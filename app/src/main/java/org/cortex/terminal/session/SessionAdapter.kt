package org.cortex.terminal.session

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.R
import org.cortex.terminal.databinding.ItemSessionBinding

class SessionAdapter(
    private val sessionManager: SessionManager,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit
) : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val isActive = position == sessionManager.currentSessionIndex
        val binding = holder.binding

        binding.sessionLabelText.text = "Session ${position + 1}"

        // The active session is marked by its highlighted background only.
        binding.sessionItemRoot.setBackgroundResource(
            if (isActive) R.drawable.session_item_active_bg else R.drawable.session_item_inactive_bg
        )

        binding.btnSessionClose.visibility = View.VISIBLE
        binding.btnSessionClose.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onClose(pos)
            }
        }

        binding.sessionItemRoot.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onSelect(pos)
            }
        }
    }

    override fun getItemCount(): Int = sessionManager.sessions.size
}
