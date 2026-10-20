package com.animarvle.app

import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.NavHostFragment
import android.view.ViewGroup
import androidx.core.view.isVisible
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.animarvle.app.databinding.ActivityMainBinding
import com.animarvle.app.ui.theme.AppTheme

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val topLevelDestinations = setOf(
        R.id.homeFragment,
        R.id.scheduleFragment,
        R.id.myListFragment,
        R.id.downloadFragment,
        R.id.profileFragment,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Palette overlay must be applied before the view is inflated so the
        // accent and background come from the chosen theme (PROMPT §5).
        setTheme(AppTheme.overlayFor(this))
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavContainer) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            binding.bottomNavInsetSpacer.layoutParams = binding.bottomNavInsetSpacer.layoutParams.apply {
                height = bottom
            }
            insets
        }

        val navHost = supportFragmentManager
            .findFragmentById(binding.navHost.id) as NavHostFragment
        val navController = navHost.navController

        val accountRepo = (application as AnimarvleApp).container.account
        if (!accountRepo.isOnboardingCompleted() && savedInstanceState == null) {
            val graph = navController.navInflater.inflate(R.navigation.nav_main)
            graph.setStartDestination(R.id.welcomeFragment)
            navController.graph = graph
        }

        // Setup bottom nav tabs matching AniVortex reference
        data class NavTabItem(
            val view: View,
            val destId: Int,
            val icon: ImageView,
            val label: TextView,
            val indicator: View,
        )

        val navTabs = listOf(
            NavTabItem(binding.navItemHome, R.id.homeFragment, binding.navIconHome, binding.navLabelHome, binding.navIndicatorHome),
            NavTabItem(binding.navItemSchedule, R.id.scheduleFragment, binding.navIconSchedule, binding.navLabelSchedule, binding.navIndicatorSchedule),
            NavTabItem(binding.navItemMyList, R.id.myListFragment, binding.navIconMyList, binding.navLabelMyList, binding.navIndicatorMyList),
            NavTabItem(binding.navItemDownload, R.id.downloadFragment, binding.navIconDownload, binding.navLabelDownload, binding.navIndicatorDownload),
            NavTabItem(binding.navItemProfile, R.id.profileFragment, binding.navIconProfile, binding.navLabelProfile, binding.navIndicatorProfile),
        )

        navTabs.forEach { tab ->
            tab.view.setOnClickListener { navigateToTab(tab.destId) }
        }

        // Bottom nav stays visible exclusively on top-level root tabs (Home, Schedule, My List, Download, Profile).
        // It disappears on child, detail, player, search, and settings flows for maximum immersion and screen space.
        navHost.navController.addOnDestinationChangedListener { _, destination, _ ->
            val isTopLevel = destination.id in topLevelDestinations
            binding.bottomNavContainer.isVisible = isTopLevel

            val activeColor = Color.WHITE
            val inactiveColor = Color.parseColor("#8E8E93")
            navTabs.forEach { tab ->
                val isSelected = destination.id == tab.destId
                tab.icon.imageTintList = ColorStateList.valueOf(if (isSelected) activeColor else inactiveColor)
                tab.label.setTextColor(if (isSelected) activeColor else inactiveColor)
                tab.label.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                tab.indicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            }
        }

        val targetMediaId = intent?.getLongExtra("mediaId", -1L) ?: -1L
        val targetMediaType = intent?.getStringExtra("mediaType")
        if (targetMediaId > 0) {
            val args = Bundle().apply {
                putLong("mediaId", targetMediaId)
                if (!targetMediaType.isNullOrBlank()) {
                    putString("mediaType", targetMediaType)
                }
            }
            navHost.navController.navigate(R.id.detailFragment, args)
        }

        if (intent?.getStringExtra("navigateTo") == "downloadFragment") {
            navHost.navController.navigate(R.id.downloadFragment)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001,
                )
            }
        }

        if (savedInstanceState == null) {
            runSplashAnimation()
        }
    }

    fun navigateToTab(destId: Int) {
        val navHost = supportFragmentManager
            .findFragmentById(binding.navHost.id) as? NavHostFragment ?: return
        val navController = navHost.navController
        if (navController.currentDestination?.id == destId) return
        val navOptions = androidx.navigation.NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.findStartDestination().id, inclusive = false, saveState = true)
            .build()
        navController.navigate(destId, null, navOptions)
    }

    fun setBottomNavVisible(visible: Boolean) {
        val navHost = supportFragmentManager
            .findFragmentById(binding.navHost.id) as? NavHostFragment ?: return
        val currentDestId = navHost.navController.currentDestination?.id
        val isTopLevel = currentDestId in topLevelDestinations
        binding.bottomNavContainer.isVisible = visible && isTopLevel
    }

    private fun runSplashAnimation() {
        binding.splashOverlay.isVisible = true
        binding.splashOverlay.alpha = 1f
        binding.splashLogo.alpha = 0f
        binding.splashLogo.scaleX = 0.85f
        binding.splashLogo.scaleY = 0.85f
        binding.splashLoader.alpha = 0f

        // Step 1: Animate brand logo in
        binding.splashLogo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400L)
            .withEndAction {
                // Step 2: Show circular progress indicator spinning beneath logo
                binding.splashLoader.animate()
                    .alpha(1f)
                    .setDuration(300L)
                    .start()

                // Step 3: Transition to app smoothly after warming up
                binding.splashOverlay.postDelayed({
                    binding.splashOverlay.animate()
                        .alpha(0f)
                        .setDuration(350L)
                        .withEndAction {
                            binding.splashOverlay.isVisible = false
                        }
                        .start()
                }, 1100L)
            }
            .start()
    }
}