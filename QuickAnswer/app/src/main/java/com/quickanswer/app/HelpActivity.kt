package com.quickanswer.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.quickanswer.app.databinding.ActivityHelpBinding

/** 使用说明页：操作步骤与模式说明，只读展示。 */
class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
    }
}
