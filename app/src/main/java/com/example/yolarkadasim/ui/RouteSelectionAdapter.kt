package com.example.yolarkadasim.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.yolarkadasim.R

/**
 * Data class representing a single option in the route/stop selection list.
 */
data class SelectionOption(
    val id: String, // Original ID for identifying the item (routeId or stopId)
    val badge: String,
    val title: String,
    val subtitle: String,
    val showAudioIcon: Boolean = false,
    val isFavorite: Boolean = false
)

/**
 * RecyclerView adapter for the route/stop selection dialog.
 */
class RouteSelectionAdapter(
    private var items: List<SelectionOption> = emptyList(),
    private val onItemClick: (position: Int) -> Unit,
    private val onAudioClick: ((position: Int) -> Unit)? = null,
    private val onFavoriteClick: ((position: Int) -> Unit)? = null
) : RecyclerView.Adapter<RouteSelectionAdapter.OptionViewHolder>() {

    inner class OptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textBadge: TextView = itemView.findViewById(R.id.textOptionBadge)
        val textTitle: TextView = itemView.findViewById(R.id.textOptionTitle)
        val textSubtitle: TextView = itemView.findViewById(R.id.textOptionSubtitle)
        val btnAudio: ImageButton = itemView.findViewById(R.id.btnAudioPreview)
        val btnFav: ImageButton = itemView.findViewById(R.id.btnFavorite)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(pos)
            }
            btnAudio.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onAudioClick?.invoke(pos)
            }
            btnFav.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onFavoriteClick?.invoke(pos)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_option, parent, false)
        return OptionViewHolder(view)
    }

    override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
        val item = items[position]
        holder.textBadge.text = item.badge
        holder.textTitle.text = item.title
        holder.textSubtitle.text = item.subtitle
        holder.textSubtitle.visibility = if (item.subtitle.isNotEmpty()) View.VISIBLE else View.GONE
            
        holder.btnAudio.visibility = if (item.showAudioIcon) View.VISIBLE else View.GONE
        
        // Favori durumu ilk bakışta ayrışsın: dolu sarı yıldız / şeffaf içli gri kontur
        val favIcon = if (item.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        holder.btnFav.setImageResource(favIcon)
        holder.btnFav.contentDescription =
            if (item.isFavorite) "Favorilerden çıkar" else "Favoriye ekle"

        // Set combined contentDescription for TalkBack
        val description = buildString {
            append("Hat ${item.badge}")
            append(", ${item.title}")
            if (item.subtitle.isNotEmpty()) {
                append(", ${item.subtitle}")
            }
        }
        holder.itemView.contentDescription = description
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<SelectionOption>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    fun getItem(position: Int): SelectionOption = items[position]
}
