package com.novadrive.app.nav

/**
 * What the map is showing. Distinct from [NavigationPhase]: a session can be NAVIGATING
 * while the camera is briefly in overview.
 *
 * The Amap adapter owns the pixels; this is the SDK-free fact the controller and tests see.
 */
enum class NaviPresentation {
    IDLE,
    ROUTE_PREVIEW,
    DRIVING,
    OVERVIEW,
}
