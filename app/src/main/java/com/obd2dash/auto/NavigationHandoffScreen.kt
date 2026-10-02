package com.obd2dash.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/**
 * Shown if Android Auto hands this app a "navigate to" request.
 *
 * The app sits in the navigation category only because that is the category
 * allowed to draw a custom dashboard. It never starts guidance, so starting a
 * route here would silently do nothing; saying so plainly is better.
 */
class NavigationHandoffScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(
            "OBD2 Dashboard shows car data and does not navigate. " +
                    "Open Google Maps from the app bar, then ask for your destination again."
        )
            .setTitle("Navigation")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Back to dashboard")
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
}
