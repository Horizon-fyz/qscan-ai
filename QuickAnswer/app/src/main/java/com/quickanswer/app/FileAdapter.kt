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

    /** 由 Activity 注入的选中判定，用于高亮多选项。 */
    var isSelected: (File) -> Boolean = { false }

    fun submit(files: List<File>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    fun fileAt(position: Int): File = items[position]

    /** 返回文件在当前显示列表中的位置，不在列表中返回 -1。 */
    fun positionOf(file: File): Int = items.indexOf(file)

    fun files(): List<File> = items.toList()

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_file, parent, false)
        val file = items[position]
        val selected = isSelected(file)
        view.isActivated = selected

        val icon = view.findViewById<ImageView>(R.id.ivIcon)
        val name = view.findViewById<TextView>(R.id.tvName)
        val meta = view.findViewById<TextView>(R.id.tvMeta)
        val check = view.findViewById<TextView>(R.id.tvCheck)
        check.visibility = if (selected) View.VISIBLE else View.GONE

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
