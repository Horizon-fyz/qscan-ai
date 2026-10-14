package com.quickanswer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.quickanswer.app.databinding.ActivityFileManagerBinding
import java.io.File

/**
 * 文件管理：类似 MT 管理器的全设备文件浏览器。
 * 打开 / 上级 / 新建文件夹 / 重命名 / 删除 / 复制剪切粘贴 / 分享 / 排序 / 属性，多选批量操作。
 * 零新增依赖：ListView + CHOICE_MODE_MULTIPLE_MODAL + AbsListView.MultiChoiceModeListener。
 */
class FileManagerActivity : AppCompatActivity(), AbsListView.MultiChoiceModeListener {

    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var adapter: FileAdapter

    private val root: File = Environment.getExternalStorageDirectory()
    private var currentDir: File = root
    private var sortMode = SortMode.NAME
    private var opened = false

    private enum class SortMode { NAME, SIZE, DATE }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = FileAdapter(this)
        binding.list.adapter = adapter
        binding.list.emptyView = binding.tvEmpty
        binding.list.choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL
        binding.list.setMultiChoiceModeListener(this)
        binding.list.setOnItemClickListener { _, _, position, _ ->
            val file = adapter.fileAt(position)
            if (file.isDirectory) open(file) else openSingle(file)
        }

        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnUp.setOnClickListener { goUp() }
        binding.btnRefresh.setOnClickListener {
            if (hasStoragePermission()) refresh() else requestStoragePermission()
        }
        binding.btnNewFolder.setOnClickListener { createFolder() }
        binding.btnSort.setOnClickListener { chooseSort() }
        binding.btnPaste.setOnClickListener { paste() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goUp()) finish()
            }
        })

        if (hasStoragePermission()) open(root) else requestStoragePermission()
        updatePasteButton()
    }

    override fun onResume() {
        super.onResume()
        if (!opened && hasStoragePermission()) open(root)
    }

    // ===== 权限 =====

    private fun hasStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle(R.string.fm_permission_title)
                .setMessage(R.string.fm_permission_msg)
                .setPositiveButton(R.string.fm_permission_grant) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    runCatching { startActivity(intent) }.onFailure {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton(R.string.fm_cancel, null)
                .setCancelable(false)
                .show()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    // ===== 导航 =====

    private fun open(dir: File) {
        currentDir = dir
        opened = true
        binding.tvPath.text = displayPath(dir)
        refresh()
    }

    private fun displayPath(dir: File): String {
        val absRoot = root.absolutePath
        val abs = dir.absolutePath
        return if (abs == absRoot) getString(R.string.fm_root)
        else getString(R.string.fm_root) + abs.removePrefix(absRoot)
    }

    private fun refresh() {
        val files = currentDir.listFiles()?.toMutableList() ?: mutableListOf()
        files.sortWith { a, b ->
            if (a.isDirectory != b.isDirectory) return@sortWith if (a.isDirectory) -1 else 1
            when (sortMode) {
                SortMode.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortMode.SIZE -> if (a.isDirectory) a.name.compareTo(b.name, true)
                else b.length().compareTo(a.length())
                SortMode.DATE -> b.lastModified().compareTo(a.lastModified())
            }
        }
        adapter.submit(files)
        binding.btnUp.alpha = if (currentDir.absolutePath == root.absolutePath) 0.4f else 1f
    }

    private fun goUp(): Boolean {
        if (currentDir.absolutePath == root.absolutePath) return false
        val parent = currentDir.parentFile ?: return false
        if (!currentDir.absolutePath.startsWith(root.absolutePath)) return false
        open(parent)
        return true
    }

    private fun handleBack() {
        if (!goUp()) finish()
    }

    // ===== 打开 / 分享 =====

    private fun openSingle(file: File) {
        if (!FileUtils.openFile(this, file)) toast(getString(R.string.fm_open_failed))
    }

    private fun shareFiles(files: List<File>) {
        if (files.isEmpty()) return
        if (files.size == 1) {
            FileUtils.shareFile(this, files.first())
            return
        }
        val uris = ArrayList(files.map { FileUtils.uriFor(this, it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.fm_share)))
        }
    }

    // ===== 新建 / 重命名 / 删除 / 属性 =====

    private fun createFolder() {
        showInputDialog(R.string.fm_new_folder, "", R.string.fm_new_folder_hint) { name ->
            if (name.isEmpty()) return@showInputDialog
            val target = File(currentDir, name)
            if (target.exists()) {
                toast(getString(R.string.fm_name_exists))
            } else if (target.mkdirs()) {
                toast(getString(R.string.fm_created, name))
                refresh()
            } else {
                toast(getString(R.string.fm_create_failed))
            }
        }
    }

    private fun rename(file: File) {
        showInputDialog(R.string.fm_rename, file.name, R.string.fm_rename_hint) { name ->
            if (name.isEmpty() || name == file.name) return@showInputDialog
            val target = File(file.parentFile, name)
            if (target.exists()) {
                toast(getString(R.string.fm_name_exists))
            } else if (file.renameTo(target)) {
                toast(getString(R.string.fm_renamed))
                refresh()
            } else {
                toast(getString(R.string.fm_op_failed))
            }
        }
    }

    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty()) return
        val msg = if (files.size == 1) {
            getString(R.string.fm_delete_msg, files.first().name)
        } else {
            getString(R.string.fm_delete_msg_multi, files.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.fm_delete_title)
            .setMessage(msg)
            .setPositiveButton(R.string.fm_delete) { _, _ ->
                var count = 0
                for (file in files) if (FileUtils.delete(file)) count++
                if (count > 0) {
                    toast(getString(R.string.fm_deleted, count))
                    refresh()
                } else {
                    toast(getString(R.string.fm_op_failed))
                }
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    private fun showProperties(file: File) {
        // 目录大小可能较大，放到后台线程统计，避免阻塞主线程。
        Thread {
            val size = if (file.isDirectory) FileUtils.dirSize(file) else file.length()
            runOnUiThread { showPropertiesDialog(file, size) }
        }.start()
    }

    private fun showPropertiesDialog(file: File, size: Long) {
        val type = getString(if (file.isDirectory) R.string.fm_type_folder else R.string.fm_type_file)
        val message = buildString {
            append(getString(R.string.fm_prop_name)).append("：").append(file.name).append('\n')
            append(getString(R.string.fm_prop_path)).append("：").append(file.parent ?: "-").append('\n')
            append(getString(R.string.fm_prop_type)).append("：").append(type).append('\n')
            append(getString(R.string.fm_prop_size)).append("：").append(FileUtils.formatSize(size)).append('\n')
            append(getString(R.string.fm_prop_modified)).append("：")
                .append(FileUtils.formatDate(file.lastModified()))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.fm_prop_title)
            .setMessage(message)
            .setPositiveButton(R.string.fm_ok, null)
            .show()
    }

    // ===== 排序 =====

    private fun chooseSort() {
        val labels = arrayOf(
            getString(R.string.fm_sort_name),
            getString(R.string.fm_sort_size),
            getString(R.string.fm_sort_date)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.fm_sort)
            .setItems(labels) { _, which ->
                sortMode = SortMode.entries[which]
                refresh()
            }
            .show()
    }

    // ===== 复制 / 剪切 / 粘贴 =====

    private fun setClip(files: List<File>, cut: Boolean) {
        if (files.isEmpty()) return
        clipFiles = files.toMutableList()
        clipCut = cut
        toast(getString(if (cut) R.string.fm_moved else R.string.fm_copied, files.size))
        updatePasteButton()
    }

    private fun updatePasteButton() {
        binding.btnPaste.visibility = if (clipFiles.isEmpty()) android.view.View.GONE
        else android.view.View.VISIBLE
    }

    private fun paste() {
        if (clipFiles.isEmpty()) return
        var count = 0
        for (src in clipFiles) {
            // 目标位于被复制/移动的目录自身或子目录内时，拒绝粘贴。
            if (src.isDirectory && (currentDir.absolutePath == src.absolutePath ||
                        currentDir.absolutePath.startsWith(src.absolutePath + File.separator))
            ) {
                toast(getString(R.string.fm_paste_into_self))
                return
            }
            val target = File(currentDir, src.name)
            val dst = if (target.exists()) FileUtils.uniqueChild(currentDir, src.name) else target
            val ok = if (clipCut) FileUtils.move(src, dst) else FileUtils.copy(src, dst)
            if (ok) count++
        }
        if (count > 0) {
            toast(getString(R.string.fm_pasted, count))
            if (clipCut) {
                clipFiles = mutableListOf()
                updatePasteButton()
            }
            refresh()
        } else {
            toast(getString(R.string.fm_paste_failed))
        }
    }

    // ===== 多选（Contextual Action Mode） =====

    private fun selectedFiles(): List<File> {
        val checked = binding.list.checkedItemPositions
        val result = mutableListOf<File>()
        for (i in 0 until binding.list.count) {
            if (checked.get(i)) result.add(adapter.fileAt(i))
        }
        return result
    }

    override fun onItemCheckedStateChanged(
        mode: ActionMode,
        position: Int,
        id: Long,
        checked: Boolean
    ) {
        mode.invalidate()
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menuInflater.inflate(R.menu.file_actions, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = binding.list.checkedItemCount
        mode.title = getString(R.string.fm_n_selected, count)
        val single = count == 1
        menu.findItem(R.id.action_open).isVisible = single
        menu.findItem(R.id.action_rename).isVisible = single
        menu.findItem(R.id.action_properties).isVisible = single
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val files = selectedFiles()
        if (files.isEmpty()) return false
        when (item.itemId) {
            R.id.action_open -> {
                mode.finish()
                val file = files.first()
                if (file.isDirectory) open(file) else openSingle(file)
            }
            R.id.action_share -> {
                mode.finish()
                shareFiles(files)
            }
            R.id.action_rename -> {
                mode.finish()
                rename(files.first())
            }
            R.id.action_copy -> {
                mode.finish()
                setClip(files, false)
            }
            R.id.action_cut -> {
                mode.finish()
                setClip(files, true)
            }
            R.id.action_delete -> {
                mode.finish()
                confirmDelete(files)
            }
            R.id.action_select_all -> {
                for (i in 0 until binding.list.count) binding.list.setItemChecked(i, true)
            }
            R.id.action_properties -> {
                mode.finish()
                showProperties(files.first())
            }
            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {}

    // ===== 工具 =====

    private fun showInputDialog(
        titleRes: Int,
        initial: String,
        hintRes: Int,
        onOk: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = getString(hintRes)
            setSingleLine()
            setText(initial)
            if (initial.isNotEmpty()) setSelection(initial.length)
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.fm_ok) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 跨页面保留的剪贴板：复制/剪切后切目录再粘贴。 */
        private var clipFiles: MutableList<File> = mutableListOf()
        private var clipCut = false
    }
}
