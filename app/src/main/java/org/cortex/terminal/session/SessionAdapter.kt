package org.cortex.terminal.session

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.R

class SessionAdapter(
    private val sessionManager: SessionManager,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit
) : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.sessionItemRoot)
        val indexText: TextView = view.findViewById(R.id.sessionIndexText)
        val titleText: TextView = view.findViewById(R.id.sessionTitleText)
        val statusText: TextView = view.findViewById(R.id.sessionStatusText)
        val closeBtn: TextView = view.findViewById(R.id.btnSessionClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessionManager.sessions[position]
        val isActive = position == sessionManager.currentSessionIndex

        holder.indexText.text = (position + 1).toString()
        holder.titleText.text = if (session.title.isNotBlank()) session.title else "Session ${position + 1}"

        if (isActive) {
            holder.statusText.text = "Active"
            holder.statusText.setTextColor(Color.parseColor("#a6e3a1"))
            holder.root.setBackgroundResource(R.drawable.key_button_bg)
            holder.indexText.setBackgroundResource(R.drawable.key_button_active)
            holder.indexText.setTextColor(Color.parseColor("#181825"))
        } else {
            holder.statusText.text = "Background"
            holder.statusText.setTextColor(Color.parseColor("#a6adc8"))
            holder.root.setBackgroundColor(Color.parseColor("#11111b"))
            holder.indexText.setBackgroundColor(Color.parseColor("#313244"))
            holder.indexText.setTextColor(Color.parseColor("#cdd6f4"))
        }

        holder.closeBtn.visibility = if (sessionManager.sessions.size > 1) View.VISIBLE else View.GONE
        holder.closeBtn.setOnClickListener {
            onClose(position)
        }

        holder.root.setOnClickListener {
            onSelect(position)
        }
    }

    override fun getItemCount(): Int = sessionManager.sessions.size
}
