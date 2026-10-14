package com.quickanswer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.quickanswer.app.databinding.ActivityFileManagerBinding
import com.quickanswer.app.databinding.ItemDrawerEntryBinding
import com.quickanswer.app.databinding.ViewFilePaneBinding
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * 文件管理：完全按 MT 管理器逻辑——常驻左右两个文件窗口，复制/移动是「当前窗口 → 另一窗口」，
 * 没有独立的粘贴步骤；配同步、后退/前进/上级三分离、底部工具栏、多选（全选/反选/类选/连选）、
 * 侧拉栏（存储 + 书签）、关键字过滤、路径跳转。
 */
class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var paneA: Pane
    private lateinit var paneB: Pane
    private var active: Pane? = null
    private var initialized = false
    private var sortMode = SortMode.NAME

    private val prefs by lazy { getSharedPreferences("fm_prefs", MODE_PRIVATE) }
    private val bookmarks = mutableListOf<File>()
    private val storageRoots: List<Pair<String, File>> by lazy { buildStorageRoots() }

    private enum class SortMode { NAME, SIZE, DATE }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { ensureLoaded() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paneA = Pane(binding.paneA)
        paneB = Pane(binding.paneB)
        paneA.bind()
        paneB.bind()
        loadBookmarks()

        binding.btnSidebar.setOnClickListener { openDrawer() }
        binding.drawerScrim.setOnClickListener { closeDrawer() }
        binding.btnAddBookmark.setOnClickListener { addBookmark(active?.dir) }

        // 底部常态工具栏
        binding.btnBack.setOnClickListener { active?.back(); updateBars() }
        binding.btnForward.setOnClickListener { active?.forward(); updateBars() }
        binding.btnUp.setOnClickListener { active?.up(); updateBars() }
        binding.btnSync.setOnClickListener { syncPanes() }
        binding.btnNewFolder.setOnClickListener { createFolder() }
        binding.btnSort.setOnClickListener { chooseSort() }
        binding.btnFilter.setOnClickListener { editFilter() }

        // 多选工具栏
        binding.btnSelCancel.setOnClickListener { active?.clearSelection() }
        binding.btnSelAll.setOnClickListener { selectAll() }
        binding.btnSelInvert.setOnClickListener { invertSelection() }
        binding.btnSelClass.setOnClickListener { classSelect() }
        binding.btnSelRange.setOnClickListener { rangeSelect() }
        binding.btnSelCopy.setOnClickListener { transfer(cut = false) }
        binding.btnSelMove.setOnClickListener { transfer(cut = true) }
        binding.btnSelDelete.setOnClickListener { confirmDelete() }
        binding.btnSelShare.setOnClickListener { shareSelected() }
        binding.btnSelRename.setOnClickListener { renameSelected() }
        binding.btnSelProps.setOnClickListener { propsSelected() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val pane = active
                when {
                    pane != null && pane.selection.isNotEmpty() -> pane.clearSelection()
                    binding.drawer.visibility == View.VISIBLE -> closeDrawer()
                    pane != null && pane.back() -> updateBars()
                    else -> finish()
                }
            }
        })

        if (hasStoragePermission()) ensureLoaded() else requestStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        ensureLoaded()
    }

    // ===== 权限与初始化 =====

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

    /** 首次拿到权限后把两个窗口都定位到内部存储。 */
    private fun ensureLoaded() {
        if (initialized || !hasStoragePermission()) return
        initialized = true
        val home = Environment.getExternalStorageDirectory()
        paneA.navigateTo(home, record = false)
        paneB.navigateTo(home, record = false)
        setActive(paneA)
    }

    // ===== 窗口激活 / 工具栏状态 =====

    private fun setActive(pane: Pane) {
        if (active === pane) return
        active = pane
        paneA.updateActiveUi()
        paneB.updateActiveUi()
        updateBars()
    }

    private fun otherOf(pane: Pane): Pane = if (pane === paneA) paneB else paneA

    private fun updateBars() {
        val pane = active ?: return
        val count = pane.selection.size
        if (count == 0) {
            binding.fileBar.visibility = View.VISIBLE
            binding.selectBar.visibility = View.GONE
        } else {
            binding.fileBar.visibility = View.GONE
            binding.selectBar.visibility = View.VISIBLE
            binding.tvSelCount.text = getString(R.string.fm_n_selected, count)
            val single = count == 1
            binding.btnSelRename.visibility = if (single) View.VISIBLE else View.GONE
            binding.btnSelProps.visibility = if (single) View.VISIBLE else View.GONE
        }
        binding.btnBack.alpha = if (pane.canBack()) 1f else 0.4f
        binding.btnForward.alpha = if (pane.canForward()) 1f else 0.4f
        binding.btnUp.alpha = if (pane.dir.parentFile != null) 1f else 0.4f
    }

    // ===== 排序 / 过滤 =====

    private fun comparator(): Comparator<File> = Comparator { a, b ->
        if (a.isDirectory != b.isDirectory) return@Comparator if (a.isDirectory) -1 else 1
        when (sortMode) {
            SortMode.NAME -> a.name.compareTo(b.name, ignoreCase = true)
            SortMode.SIZE -> if (a.isDirectory) a.name.compareTo(b.name, ignoreCase = true)
            else b.length().compareTo(a.length())
            SortMode.DATE -> b.lastModified().compareTo(a.lastModified())
        }
    }

    private fun matchFilter(file: File, key: String): Boolean = when {
        key.startsWith("/") -> runCatching {
            Regex(key.substring(1), RegexOption.IGNORE_CASE).containsMatchIn(file.name)
        }.getOrDefault(false)
        key.startsWith("!") -> !file.name.contains(key.substring(1), ignoreCase = true)
        else -> file.name.contains(key, ignoreCase = true)
    }

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
                paneA.refresh()
                paneB.refresh()
            }
            .show()
    }

    private fun editFilter() {
        val pane = active ?: return
        showInputDialog(R.string.fm_filter, pane.filter, R.string.fm_filter_hint) { key ->
            pane.filter = key
            pane.refresh()
        }
    }

    // ===== MT 招牌操作 =====

    private fun syncPanes() {
        val from = active ?: return
        val to = otherOf(from)
        if (to.dir.absolutePath == from.dir.absolutePath) {
            toast(getString(R.string.fm_same_dir))
            return
        }
        to.navigateTo(from.dir)
        toast(getString(R.string.fm_synced))
    }

    /** 复制 / 移动：当前窗口选中项 → 另一窗口当前目录，无粘贴步骤。 */
    private fun transfer(cut: Boolean) {
        val src = active ?: return
        val dst = otherOf(src)
        val files = src.selection.toList()
        if (files.isEmpty()) return
        if (src.dir.absolutePath == dst.dir.absolutePath) {
            toast(getString(R.string.fm_same_dir))
            return
        }
        for (file in files) {
            if (file.isDirectory && (dst.dir.absolutePath == file.absolutePath ||
                        dst.dir.absolutePath.startsWith(file.absolutePath + File.separator))
            ) {
                toast(getString(R.string.fm_into_self))
                return
            }
        }
        var ok = 0
        for (file in files) {
            val target = File(dst.dir, file.name)
            val dest = if (target.exists()) FileUtils.uniqueChild(dst.dir, file.name) else target
            val success = if (cut) FileUtils.move(file, dest) else FileUtils.copy(file, dest)
            if (success) ok++
        }
        src.clearSelection()
        src.refresh()
        dst.refresh()
        toast(getString(if (cut) R.string.fm_move_done else R.string.fm_copy_done, ok))
    }

    private fun selectAll() {
        val pane = active ?: return
        pane.selection.clear()
        pane.selection.addAll(pane.adapter.files())
        pane.updateSelectionUi()
    }

    private fun invertSelection() {
        val pane = active ?: return
        val inverted = pane.adapter.files().filter { !pane.selection.contains(it) }
        pane.selection.clear()
        pane.selection.addAll(inverted)
        pane.updateSelectionUi()
    }

    /** 类选：选中与当前首选项同扩展名的全部文件（首选项为文件夹时选全部文件夹）。 */
    private fun classSelect() {
        val pane = active ?: return
        val seed = pane.selection.firstOrNull() ?: return
        val targets = pane.adapter.files().filter {
            if (seed.isDirectory) it.isDirectory
            else !it.isDirectory && it.extension.equals(seed.extension, ignoreCase = true)
        }
        if (targets.isEmpty()) {
            toast(getString(R.string.fm_class_none))
            return
        }
        pane.selection.addAll(targets)
        pane.updateSelectionUi()
    }

    /** 连选：选中已选项首尾之间的整个区间。 */
    private fun rangeSelect() {
        val pane = active ?: return
        val positions = pane.selection.map { pane.adapter.positionOf(it) }.filter { it >= 0 }
        if (positions.size < 2) {
            toast(getString(R.string.fm_range_none))
            return
        }
        for (i in positions.min()..positions.max()) pane.selection.add(pane.adapter.fileAt(i))
        pane.updateSelectionUi()
    }

    // ===== 单文件操作 =====

    private fun createFolder() {
        val pane = active ?: return
        val dir = pane.dir
        showInputDialog(R.string.fm_new_folder, "", R.string.fm_new_folder_hint) { name ->
            if (name.isEmpty()) return@showInputDialog
            val target = File(dir, name)
            if (target.exists()) {
                toast(getString(R.string.fm_name_exists))
            } else if (target.mkdirs()) {
                toast(getString(R.string.fm_created, name))
                pane.refresh()
            } else {
                toast(getString(R.string.fm_create_failed))
            }
        }
    }

    private fun renameSelected() {
        val pane = active ?: return
        val file = pane.selection.firstOrNull() ?: return
        pane.clearSelection()
        showInputDialog(R.string.fm_rename, file.name, R.string.fm_rename_hint) { name ->
            if (name.isEmpty() || name == file.name) return@showInputDialog
            val target = File(file.parentFile, name)
            if (target.exists()) {
                toast(getString(R.string.fm_name_exists))
            } else if (file.renameTo(target)) {
                toast(getString(R.string.fm_renamed))
                paneA.refresh()
                paneB.refresh()
            } else {
                toast(getString(R.string.fm_op_failed))
            }
        }
    }

    private fun confirmDelete() {
        val pane = active ?: return
        val files = pane.selection.toList()
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
                pane.clearSelection()
                pane.refresh()
                if (count > 0) toast(getString(R.string.fm_deleted, count))
                else toast(getString(R.string.fm_op_failed))
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    private fun shareSelected() {
        val pane = active ?: return
        val files = pane.selection.toList()
        if (files.isEmpty()) return
        pane.clearSelection()
        shareFiles(files)
    }

    private fun propsSelected() {
        val pane = active ?: return
        val file = pane.selection.firstOrNull() ?: return
        pane.clearSelection()
        showProperties(file)
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

    // ===== 侧拉栏 =====

    private fun buildStorageRoots(): List<Pair<String, File>> {
        val roots = mutableListOf<Pair<String, File>>()
        roots.add(getString(R.string.fm_root_dir) to File("/"))
        val primary = Environment.getExternalStorageDirectory()
        roots.add(getString(R.string.fm_internal) to primary)
        val seen = mutableSetOf(primary.absolutePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.forEach { vol ->
                val dir = vol.directory ?: return@forEach
                if (!vol.isPrimary && dir.exists() && seen.add(dir.absolutePath)) {
                    val label = runCatching { vol.getDescription(this) }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: getString(R.string.fm_external)
                    roots.add(label to dir)
                }
            }
        } else {
            ContextCompat.getExternalFilesDirs(this, null).forEach { dir ->
                if (dir == null) return@forEach
                val idx = dir.absolutePath.indexOf("/Android/")
                if (idx > 0) {
                    val rootPath = dir.absolutePath.substring(0, idx)
                    if (seen.add(rootPath)) roots.add(getString(R.string.fm_external) to File(rootPath))
                }
            }
        }
        return roots
    }

    private fun openDrawer() {
        rebuildDrawer()
        binding.drawer.visibility = View.VISIBLE
        binding.drawerScrim.visibility = View.VISIBLE
        binding.drawer.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        val width = resources.getDimensionPixelSize(R.dimen.drawer_width).toFloat()
        binding.drawerScrim.visibility = View.GONE
        binding.drawer.animate().translationX(-width).setDuration(200)
            .withEndAction { binding.drawer.visibility = View.INVISIBLE }.start()
    }

    private fun rebuildDrawer() {
        binding.storageList.removeAllViews()
        for ((label, file) in storageRoots) {
            binding.storageList.addView(drawerRow("▤", label, file, isBookmark = false))
        }
        binding.bookmarkList.removeAllViews()
        if (bookmarks.isEmpty()) {
            val pad = resources.getDimensionPixelSize(R.dimen.space_lg)
            val hint = TextView(this).apply {
                setText(R.string.fm_bookmark_empty)
                setTextColor(ContextCompat.getColor(this@FileManagerActivity, R.color.on_dark_muted))
                textSize = 13f
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            binding.bookmarkList.addView(hint)
        } else {
            for (bm in bookmarks) {
                binding.bookmarkList.addView(
                    drawerRow("★", bm.name.ifEmpty { bm.absolutePath }, bm, isBookmark = true)
                )
            }
        }
    }

    private fun drawerRow(icon: String, label: String, target: File, isBookmark: Boolean): View {
        val item = ItemDrawerEntryBinding.inflate(layoutInflater, binding.storageList, false)
        item.tvIcon.text = icon
        item.tvLabel.text = label
        item.root.setOnClickListener {
            val pane = active ?: return@setOnClickListener
            if (!pane.navigateTo(target)) toast(getString(R.string.fm_path_invalid))
            closeDrawer()
            updateBars()
        }
        if (isBookmark) {
            item.root.setOnLongClickListener {
                confirmRemoveBookmark(target)
                true
            }
        }
        return item.root
    }

    private fun loadBookmarks() {
        bookmarks.clear()
        prefs.getString(KEY_BOOKMARKS, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .forEach { bookmarks.add(File(it)) }
    }

    private fun saveBookmarks() {
        prefs.edit()
            .putString(KEY_BOOKMARKS, bookmarks.joinToString("\n") { it.absolutePath })
            .apply()
    }

    private fun addBookmark(dir: File?) {
        if (dir == null) return
        if (bookmarks.any { it.absolutePath == dir.absolutePath }) {
            toast(getString(R.string.fm_bookmark_exists))
            return
        }
        bookmarks.add(dir)
        saveBookmarks()
        toast(getString(R.string.fm_bookmark_added))
        if (binding.drawer.visibility == View.VISIBLE) rebuildDrawer()
    }

    private fun confirmRemoveBookmark(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.fm_remove_bookmark_title)
            .setMessage(getString(R.string.fm_remove_bookmark_msg, file.name.ifEmpty { file.absolutePath }))
            .setPositiveButton(R.string.fm_remove) { _, _ ->
                bookmarks.removeAll { it.absolutePath == file.absolutePath }
                saveBookmarks()
                rebuildDrawer()
                toast(getString(R.string.fm_bookmark_removed))
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    // ===== 路径显示 =====

    private fun displayPath(dir: File): String {
        val abs = dir.absolutePath
        for ((label, rootFile) in storageRoots) {
            val rootPath = rootFile.absolutePath
            if (abs == rootPath) return label
            if (rootPath != "/" && abs.startsWith(rootPath + File.separator)) {
                return label + abs.substring(rootPath.length)
            }
        }
        return abs
    }

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

    // ===== 单个文件窗口 =====

    private inner class Pane(val vb: ViewFilePaneBinding) {

        val adapter = FileAdapter(this@FileManagerActivity)
        var dir: File = File("/")
        var filter: String = ""
        val selection = LinkedHashSet<File>()

        private val backStack = ArrayDeque<File>()
        private val forwardStack = ArrayDeque<File>()

        fun bind() {
            adapter.isSelected = { selection.contains(it) }
            vb.list.adapter = adapter
            vb.list.emptyView = vb.tvEmpty

            vb.tvPath.setOnClickListener { jumpPath() }
            vb.tvPath.setOnLongClickListener { jumpPath(); true }
            vb.btnStar.setOnClickListener { addBookmark(dir) }
            vb.btnFilterClear.setOnClickListener { filter = ""; refresh() }

            vb.list.setOnItemClickListener { _, _, position, _ ->
                setActive(this)
                val file = adapter.fileAt(position)
                if (selection.isNotEmpty()) {
                    toggleAt(position, select = !selection.contains(file))
                } else if (file.isDirectory) {
                    navigateTo(file)
                    updateBars()
                } else {
                    if (!FileUtils.openFile(this@FileManagerActivity, file)) {
                        toast(getString(R.string.fm_open_failed))
                    }
                }
            }
            vb.list.setOnItemLongClickListener { _, _, position, _ ->
                setActive(this)
                toggleAt(position, select = true)
                true
            }
            vb.list.setOnTouchListener(makeSwipeSelectListener())
        }

        /** MT 特色：左右滑动列表即进入多选，滑过的行被选中。 */
        private fun makeSwipeSelectListener(): View.OnTouchListener {
            val slop = ViewConfiguration.get(this@FileManagerActivity).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var swiping = false
            val touched = HashSet<Int>()
            return View.OnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        swiping = false
                        touched.clear()
                        setActive(this@Pane)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!swiping) {
                            val dx = ev.x - downX
                            val dy = ev.y - downY
                            if (abs(dx) > slop && abs(dx) > abs(dy)) swiping = true
                        }
                        if (swiping) {
                            val pos = vb.list.pointToPosition(ev.x.toInt(), ev.y.toInt())
                            if (pos >= 0 && touched.add(pos)) toggleAt(pos, select = true)
                        }
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (swiping) {
                            swiping = false
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }

        fun refresh() {
            val all = dir.listFiles()?.toMutableList() ?: mutableListOf()
            all.sortWith(comparator())
            val shown = if (filter.isEmpty()) all else all.filter { matchFilter(it, filter) }
            selection.retainAll(shown.toSet())
            adapter.submit(shown)
            vb.tvPath.text = displayPath(dir)
            if (filter.isEmpty()) {
                vb.filterBar.visibility = View.GONE
            } else {
                vb.filterBar.visibility = View.VISIBLE
                vb.tvFilter.text = getString(R.string.fm_filter_on, filter)
            }
            updateSelectionUi()
        }

        fun navigateTo(target: File, record: Boolean = true): Boolean {
            val dest = target.absoluteFile
            if (!dest.isDirectory) return false
            if (dest.absolutePath == dir.absolutePath) return true
            if (record) {
                backStack.addLast(dir)
                forwardStack.clear()
            }
            dir = dest
            clearSelection()
            refresh()
            return true
        }

        fun back(): Boolean {
            if (backStack.isEmpty()) return false
            forwardStack.addLast(dir)
            dir = backStack.removeLast()
            clearSelection()
            refresh()
            return true
        }

        fun forward(): Boolean {
            if (forwardStack.isEmpty()) return false
            backStack.addLast(dir)
            dir = forwardStack.removeLast()
            clearSelection()
            refresh()
            return true
        }

        fun up(): Boolean {
            val parent = dir.parentFile ?: return false
            if (parent.absolutePath == dir.absolutePath) return false
            return navigateTo(parent)
        }

        fun canBack(): Boolean = backStack.isNotEmpty()
        fun canForward(): Boolean = forwardStack.isNotEmpty()

        fun clearSelection() {
            if (selection.isEmpty()) return
            selection.clear()
            updateSelectionUi()
        }

        fun toggleAt(position: Int, select: Boolean) {
            if (position < 0 || position >= adapter.count) return
            val file = adapter.fileAt(position)
            if (select) selection.add(file) else selection.remove(file)
            setActive(this)
            updateSelectionUi()
        }

        fun updateSelectionUi() {
            adapter.notifyDataSetChanged()
            if (this === active) updateBars()
        }

        fun updateActiveUi() {
            vb.root.setBackgroundResource(
                if (this === active) R.drawable.bg_pane_active else R.drawable.bg_pane
            )
        }

        private fun jumpPath() {
            setActive(this)
            showInputDialog(R.string.fm_path_jump, dir.absolutePath, R.string.fm_path_hint) { input ->
                if (input.isEmpty()) return@showInputDialog
                val target = if (input.startsWith("/")) File(input)
                else File(dir.absolutePath + File.separator + input)
                if (target.isDirectory) {
                    navigateTo(target)
                    updateBars()
                } else {
                    toast(getString(R.string.fm_path_invalid))
                }
            }
        }
    }

    companion object {
        private const val KEY_BOOKMARKS = "bookmarks"
    }
}
