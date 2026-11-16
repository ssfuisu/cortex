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
    private val onClose: (Int) -> Unit,
    private val onRename: ((Int) -> Unit)? = null
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
        val hairline = density.toInt().coerceAtLeast(1)
        val p = Chrome.palette

        val session = synchronized(sessionManager.sessions) { sessionManager.sessions.getOrNull(position) }
        binding.sessionLabelText.text = session?.customName ?: context.getString(R.string.session_label, position + 1)
        binding.sessionRootBadge.visibility = if (session?.isRoot == true) View.VISIBLE else View.GONE

        // Active session stands out with a left accent pill, highlighted terminal glyph, and border
        val radius = 15 * density
        binding.sessionItemRoot.background = Chrome.ripple(
            if (isActive) Chrome.rounded(p.accentSoft, radius, Chrome.withAlpha(p.accent, 140), (1.5f * density).toInt().coerceAtLeast(1))
            else Chrome.rounded(p.card, radius, p.outline, hairline),
            Chrome.withAlpha(p.accent, 60),
            radius
        )
        binding.sessionActiveIndicator.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
        binding.sessionActiveIndicator.background = Chrome.rounded(p.accent, 3 * density)
        binding.sessionIconContainer.background = Chrome.rounded(
            if (isActive) Chrome.withAlpha(p.accent, 36) else p.raised,
            10 * density,
            if (isActive) Chrome.withAlpha(p.accent, 110) else p.outline,
            hairline
        )
        binding.sessionIcon.setColorFilter(if (isActive) p.accent else p.textDim)
        binding.sessionLabelText.setTextColor(p.text)
        binding.sessionRootBadge.setTextColor(p.danger)
        binding.sessionRootBadge.background = Chrome.rounded(p.dangerSoft, 6 * density, p.danger, hairline)

        binding.btnSessionClose.visibility = View.VISIBLE
        binding.btnSessionClose.setTextColor(p.textDim)
        binding.btnSessionClose.background = Chrome.ripple(Chrome.rounded(0, 18 * density), Chrome.withAlpha(p.danger, 70), 18 * density)
        binding.btnSessionClose.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onClose(pos)
            }
        }

        binding.sessionItemRoot.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            val rename = onRename
            if (pos == RecyclerView.NO_POSITION || rename == null) return@setOnLongClickListener false
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            rename(pos)
            true
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
