package com.watchface.android.wearable.alpha

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.watchface.android.wearable.alpha.databinding.ActivityTimeLineBinding

class TimeLine : AppCompatActivity() {

    private val binding by lazy{
        ActivityTimeLineBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)


    }
}
