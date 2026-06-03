package com.wf11.safealert.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.databinding.ItemWalkerBinding

data class WalkerItem(val walkerId: String, val rssi: Int, val alertLevel: Int)

class WalkerListAdapter : RecyclerView.Adapter<WalkerListAdapter.VH>() {

    private val items = mutableListOf<WalkerItem>()

    fun update(list: List<WalkerItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    inner class VH(private val b: ItemWalkerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(w: WalkerItem) {
            b.tvWalkerId.text = w.walkerId
            b.tvRssi.text = "RSSI: ${w.rssi} dBm"
            b.tvDistance.text = estimateDistance(w.rssi)
            val color = when (w.alertLevel) {
                BleConstants.LEVEL_DANGER  -> android.graphics.Color.parseColor("#F44336")
                BleConstants.LEVEL_WARNING -> android.graphics.Color.parseColor("#FFC107")
                else                       -> android.graphics.Color.WHITE
            }
            b.root.setBackgroundColor(color)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemWalkerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    private fun estimateDistance(rssi: Int): String {
        val dist = Math.pow(10.0, (-69 - rssi) / 20.0)
        return "약 %.1fm".format(dist)
    }
}
