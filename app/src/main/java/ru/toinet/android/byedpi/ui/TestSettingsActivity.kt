package ru.toinet.android.byedpi.ui

import android.os.Bundle
import android.view.MenuItem
import ru.toinet.android.R
import ru.toinet.android.byedpi.fragments.DomainListsFragment
import ru.toinet.android.byedpi.fragments.ProxyTestSettingsFragment

class TestSettingsActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_settings)
        setSupportActionBar(findViewById(R.id.toolbar))

        val openFragment = intent.getStringExtra("open_fragment")

        when (openFragment) {
            "domain_lists" -> {
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.test_settings, DomainListsFragment())
                    .commit()
            }
            else -> {
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.test_settings, ProxyTestSettingsFragment())
                    .commit()
            }
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

