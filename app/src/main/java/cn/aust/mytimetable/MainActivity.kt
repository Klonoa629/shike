package cn.aust.mytimetable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import cn.aust.mytimetable.school.SchoolViewModel

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        ViewModelProvider(this)[SchoolViewModel::class.java].checkAutomaticSync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[SchoolViewModel::class.java]
        setContent {
            TimetableHome(model)
        }
    }
}
