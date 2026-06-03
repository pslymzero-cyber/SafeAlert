package com.wf11.safealert.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wf11.safealert.model.Device
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.databinding.ItemDeviceBinding

class DeviceListAdapter : RecyclerView.Adapter<DeviceListAdapter.VH>() {

    private val items = mutableListOf<Device>()

    fun update(list: List<Device>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    inner class VH(private val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(d: Device) {
            b.tvDeviceId.text = d.deviceId
            b.tvRssi.text = "RSSI: ${d.rssi} dBm"
            b.tvDistance.text = estimateDistance(d.rssi)
            val color = when (d.alertLevel) {
                BleConstants.LEVEL_DANGER  -> android.graphics.Color.parseColor("#F44336")
                BleConstants.LEVEL_WARNING -> android.graphics.Color.parseColor("#FFC107")
                else                       -> android.graphics.Color.WHITE
            }
            b.root.setBackgroundColor(color)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    private fun estimateDistance(rssi: Int): String {
        val dist = Math.pow(10.0, (-69 - rssi) / 20.0)
        return "약 %.1fm".format(dist)
    }
}
