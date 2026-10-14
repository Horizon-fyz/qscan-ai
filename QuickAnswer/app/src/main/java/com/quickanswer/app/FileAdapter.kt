package com.quickanswer.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/** 文件列表适配器：文件夹用文件夹图标并显示子项数，文件显示大小与修改时间。 */
class FileAdapter(private val context: Context) : BaseAdapter() {

    private val items = mutableListOf<File>()

    fun submit(files: List<File>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    fun fileAt(position: Int): File = items[position]

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_file, parent, false)
        val file = items[position]

        val icon = view.findViewById<ImageView>(R.id.ivIcon)
        val name = view.findViewById<TextView>(R.id.tvName)
        val meta = view.findViewById<TextView>(R.id.tvMeta)

        name.text = file.name
        if (file.isDirectory) {
            icon.setImageResource(R.drawable.ic_folder)
            val count = file.listFiles()?.size ?: 0
            meta.text = context.getString(R.string.fm_n_items, count)
        } else {
            icon.setImageResource(R.drawable.ic_file)
            meta.text = "${FileUtils.formatSize(file.length())} · ${FileUtils.formatDate(file.lastModified())}"
        }
        return view
    }
}
