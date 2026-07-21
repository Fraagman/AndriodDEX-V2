package com.androiddex.core

/**
 * Common interface for all loadable plugins in the system.
 */
interface Plugin {
    val id: String
    val version: String
    
    /** Validates if the plugin can run in the current environment. */
    fun validate(capabilityManager: CapabilityManager): Boolean
    
    /** Initializes the plugin. */
    fun initialize()
    
    /** Performs a health check on the plugin. Returns true if healthy. */
    fun healthCheck(): Boolean
    
    /** Shuts down and cleans up plugin resources. */
    fun shutdown()
}

/**
 * Manages the lifecycle of all registered plugins.
 */
interface PluginManager {
    fun registerPlugin(plugin: Plugin)
    fun getPlugin(id: String): Plugin?
    fun initializeAll(capabilityManager: CapabilityManager)
    fun shutdownAll()
}
