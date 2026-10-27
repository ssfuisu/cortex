package org.cortex.terminal.session

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.R
import org.cortex.terminal.databinding.ItemSessionBinding
import org.cortex.terminal.view.Chrome

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
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        val p = Chrome.palette

        binding.sessionLabelText.text = context.getString(R.string.session_label, position + 1)
        val session = synchronized(sessionManager.sessions) { sessionManager.sessions.getOrNull(position) }
        binding.sessionRootBadge.visibility = if (session?.isRoot == true) View.VISIBLE else View.GONE

        // The session on screen has the accent around it; the others sit flat on the drawer
        val radius = 14 * density
        binding.sessionItemRoot.background = Chrome.ripple(
            if (isActive) Chrome.rounded(p.accentSoft, radius, p.accent, (1.5f * density).toInt())
            else Chrome.rounded(p.raised, radius, p.outline, density.toInt().coerceAtLeast(1)),
            Chrome.withAlpha(p.accent, 60),
            radius
        )
        binding.sessionLabelText.setTextColor(p.text)
        binding.sessionRootBadge.setTextColor(p.danger)
        binding.sessionRootBadge.background = Chrome.rounded(p.dangerSoft, 6 * density, p.danger, density.toInt().coerceAtLeast(1))

        binding.btnSessionClose.visibility = View.VISIBLE
        binding.btnSessionClose.setTextColor(p.textDim)
        binding.btnSessionClose.background = Chrome.ripple(Chrome.rounded(0, 12 * density), Chrome.withAlpha(p.danger, 70), 12 * density)
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
