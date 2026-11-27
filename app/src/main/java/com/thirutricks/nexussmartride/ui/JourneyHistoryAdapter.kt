package com.thirutricks.nexussmartride.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.thirutricks.nexussmartride.R
import com.thirutricks.nexussmartride.data.JourneyEntity
import java.util.Locale

class JourneyHistoryAdapter(
    private var journeys: List<JourneyEntity>,
    private val onItemClick: (JourneyEntity) -> Unit
) : RecyclerView.Adapter<JourneyHistoryAdapter.JourneyViewHolder>() {

    class JourneyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val layoutIconBg: FrameLayout = itemView.findViewById(R.id.layoutItemIconBg)
        val ivIcon: ImageView = itemView.findViewById(R.id.ivItemIcon)
        val tvTitle: TextView = itemView.findViewById(R.id.tvItemJourneyTitle)
        val tvTag: TextView = itemView.findViewById(R.id.tvItemJourneyTag)
        val tvSubtitle: TextView = itemView.findViewById(R.id.tvItemJourneySubtitle)
        val tvDistance: TextView = itemView.findViewById(R.id.tvItemJourneyDistance)
        val tvDurationSpeed: TextView = itemView.findViewById(R.id.tvItemDurationSpeed)
        val tvBatteryEff: TextView = itemView.findViewById(R.id.tvItemBatteryEff)
        val tvSavedRegen: TextView = itemView.findViewById(R.id.tvItemSavedRegen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JourneyViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_journey_history_card, parent, false)
        return JourneyViewHolder(view)
    }

    override fun getItemCount(): Int = journeys.size

    override fun onBindViewHolder(holder: JourneyViewHolder, position: Int) {
        val j = journeys[position]
        val context = holder.itemView.context

        holder.tvTitle.text = j.title
        holder.tvDistance.text = String.format(Locale.US, "%.1f", j.distanceKm)

        // Subtitle (Time & date)
        holder.tvSubtitle.text = if (j.routeSummary.isNotBlank()) {
            j.routeSummary
        } else if (j.timestampFormatted.isNotBlank()) {
            "${j.timestampFormatted} (${j.durationMins}m)"
        } else {
            "${j.durationMins} mins duration"
        }

        // Tag and badge styling
        val displayTag = if (j.aiTag.isNotBlank() && j.aiTag != "AI Auto") {
            j.aiTag
        } else {
            "${j.ridingMode} Mode"
        }
        holder.tvTag.text = displayTag

        val cyanBg = ContextCompat.getDrawable(context, R.drawable.bg_pill_cyan)
        val emeraldBg = ContextCompat.getDrawable(context, R.drawable.bg_pill_emerald)
        val amberBg = ContextCompat.getDrawable(context, R.drawable.bg_pill_amber)
        val cyanColor = ContextCompat.getColor(context, R.color.primary_cyan)
        val emeraldColor = ContextCompat.getColor(context, R.color.status_emerald)
        val amberColor = ContextCompat.getColor(context, R.color.status_amber)

        val upperTag = displayTag.uppercase(Locale.US)
        when {
            upperTag.contains("ECO") || upperTag.contains("CHAMP") -> {
                holder.tvTag.background = emeraldBg
                holder.tvTag.setTextColor(emeraldColor)
                holder.layoutIconBg.background = emeraldBg
                holder.ivIcon.setColorFilter(emeraldColor)
                holder.ivIcon.setImageResource(R.drawable.ic_leaf)
            }
            upperTag.contains("HIGHWAY") || upperTag.contains("RAPID") || upperTag.contains("POWER") || upperTag.contains("SPORT") -> {
                holder.tvTag.background = amberBg
                holder.tvTag.setTextColor(amberColor)
                holder.layoutIconBg.background = amberBg
                holder.ivIcon.setColorFilter(amberColor)
                holder.ivIcon.setImageResource(R.drawable.ic_bolt)
            }
            else -> {
                holder.tvTag.background = cyanBg
                holder.tvTag.setTextColor(cyanColor)
                holder.layoutIconBg.background = cyanBg
                holder.ivIcon.setColorFilter(cyanColor)
                holder.ivIcon.setImageResource(R.drawable.ic_location_pin)
            }
        }

        // Duration & Speed
        val avgSpeed = if (j.avgSpeedKmh > 0) String.format(Locale.US, "%.0f km/h", j.avgSpeedKmh) else "-- km/h"
        holder.tvDurationSpeed.text = "${j.durationMins} min • $avgSpeed"

        // Battery & Efficiency
        if (j.startSoc > 0 && j.endSoc > 0) {
            holder.tvBatteryEff.text = "🔋 ${j.startSoc}%➔${j.endSoc}%"
        } else if (j.efficiencyKmPerSoc > 0) {
            holder.tvBatteryEff.text = String.format(Locale.US, "%.2f km/%%", j.efficiencyKmPerSoc)
        } else {
            holder.tvBatteryEff.text = "${j.ridingMode} Mode"
        }

        // Savings & Regen
        val regenStr = if (j.regenKwh > 0) "+${String.format(Locale.US, "%.2f", j.regenKwh)}kWh" else "0.00kWh"
        val savedStr = if (j.moneySavedInr > 0) "₹${j.moneySavedInr}" else "₹--"
        holder.tvSavedRegen.text = "$savedStr • $regenStr"

        holder.itemView.setOnClickListener {
            onItemClick(j)
        }
    }

    fun submitList(newList: List<JourneyEntity>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = journeys.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = journeys[oldItemPosition]
                val new = newList[newItemPosition]
                return old.id == new.id || (old.startTime == new.startTime && old.distanceKm == new.distanceKm)
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return journeys[oldItemPosition] == newList[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        journeys = newList
        diffResult.dispatchUpdatesTo(this)
    }
}
